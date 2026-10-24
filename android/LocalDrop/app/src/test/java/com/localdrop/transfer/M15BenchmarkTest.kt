package com.localdrop.transfer

import com.localdrop.discovery.DeviceInfo
import com.localdrop.network.ControlConnection
import com.localdrop.network.ControlServer
import com.localdrop.security.AuthManager
import com.localdrop.security.AuthOutcome
import com.localdrop.security.AuthTestSupport
import com.localdrop.security.AuthorizedSessionStore
import com.localdrop.security.RecordingSecurityLogger
import com.localdrop.security.SoftwareDeviceIdentityStore
import com.localdrop.security.TransactionAuthorizer
import com.localdrop.security.TransactionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

// M15 benchmark harness (Android/JVM, real loopback TCP).
//
// Measures the PRODUCTION Android stack end to end: production ControlServer
// + AuthManager + TransactionAuthorizer + ReceiverEngine (receiver) and the
// production ControlConnection + SenderEngine (sender) over a real TCP
// loopback connection. Nothing is simulated: the receiver writes the byte
// stream to a real file, and the content is verified against the INDEPENDENT
// SHA-256 oracle committed in test-assets/performance/m15/vectors.json
// (produced by Python hashlib).
//
// Test type: same-process harness on a DESKTOP JVM (not ART, not a device).
// It is NOT a physical Wi-Fi / hotspot measurement (that is M16).
//
// Runtime caveat: this runs on the JVM unit-test runtime, not on Android's
// ART runtime and not on a phone. The numbers characterise the JVM pipeline
// and are NOT Android-device performance numbers.
class M15BenchmarkTest {

    // ------------------------------------------------ deterministic content

    /** Same "m15-pattern-v1" content as the Python oracle and the .NET harness. */
    object Pattern {
        const val CHUNK = 1 shl 20
        private const val LCG_MUL = 1103515245L
        private const val LCG_INC = 12345L

        private var cycle: ByteArray? = null

        fun cycle(): ByteArray {
            cycle?.let { return it }
            val c = ByteArray(CHUNK)
            for (i in 0 until CHUNK) {
                val v = ((i.toLong() * LCG_MUL + LCG_INC) and 0xFFFFFFFFL)
                c[i] = ((v ushr 16) and 0xFF).toByte()
            }
            cycle = c
            return c
        }

        fun tag(chunkIndex: Int): ByteArray {
            val k = chunkIndex and 0xFFFF
            return byteArrayOf(
                (k and 0xFF).toByte(),
                ((k shr 8) and 0xFF).toByte(),
                (0x5A xor (k and 0xFF)).toByte(),
                (0xA5 xor ((k shr 8) and 0xFF)).toByte()
            )
        }

        fun fill(buffer: ByteArray, offset: Int, count: Int, absolutePosition: Long) {
            val cyc = cycle()
            var written = 0
            while (written < count) {
                val pos = absolutePosition + written
                val chunkIndex = (pos / CHUNK).toInt()
                val within = (pos % CHUNK).toInt()
                val take = minOf(count - written, CHUNK - within)
                val tag = tag(chunkIndex)
                val tagOff = within and 3
                for (i in 0 until take) {
                    buffer[offset + written + i] =
                        (cyc[within + i].toInt() xor tag[(tagOff + i) and 3].toInt()).toByte()
                }
                written += take
            }
        }

        fun writeFile(path: File, size: Long) {
            val buffer = ByteArray(CHUNK)
            FileOutputStream(path).use { out ->
                var written = 0L
                while (written < size) {
                    val n = minOf(CHUNK.toLong(), size - written).toInt()
                    fill(buffer, 0, n, written)
                    out.write(buffer, 0, n)
                    written += n
                }
                out.flush()
                out.fd.sync()
            }
        }
    }

    // -------------------------------------------------------------- vectors

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "test-assets").isDirectory && File(dir, "shared-protocol").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("LocalDrop repository root not found from ${System.getProperty("user.dir")}")
    }

    private fun vectors(): Map<Long, String> {
        val file = File(repoRoot(), "test-assets/performance/m15/vectors.json")
        require(file.isFile) { "M15 golden vectors not found at $file" }
        val text = file.readText()
        val out = HashMap<Long, String>()
        val entry = Regex("\\{[^{}]*\\}").findAll(text)
        for (m in entry) {
            val body = m.value
            val bytes = Regex("\"bytes\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLong()
            val sha = Regex("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(body)?.groupValues?.get(1)
            if (bytes != null && sha != null) out[bytes] = sha
        }
        require(out.isNotEmpty()) { "M15 golden vector file contains no sizes" }
        return out
    }

    private fun expected(size: Long): String =
        vectors()[size] ?: throw IllegalStateException("No independent SHA-256 oracle vector for size $size")

    // --------------------------------------------------------------- memory

    /**
     * Process memory sampler for the JVM: the runtime heap (used/total) and
     * the precise cumulative allocation counter of the current thread's
     * allocated bytes. A whole-file buffer would show up in the allocation
     * counter as an allocation >= file size.
     */
    private class MemorySampler(private val targetThread: Thread) : Runnable {
        @Volatile var running = true
        var peakHeapUsed = 0L
        var peakHeapTotal = 0L
        var samples = 0L
        var startThreadAllocated = 0L
        var endThreadAllocated = 0L
        private val samplerThread = Thread(this, "m15-memory-sampler")

        init {
            samplerThread.isDaemon = true
            startThreadAllocated = allocated()
            samplerThread.start()
        }

        fun allocated(): Long = try {
            val mgmt = Class.forName("java.lang.management.ManagementFactory")
            val getBean = mgmt.getMethod("getThreadMXBean")
            val bean = getBean.invoke(null)
            val getBytes = bean.javaClass.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            getBytes.invoke(bean, targetThread.id) as Long
        } catch (e: Throwable) {
            0L
        }

        override fun run() {
            val rt = Runtime.getRuntime()
            while (running) {
                val used = rt.totalMemory() - rt.freeMemory()
                if (used > peakHeapUsed) peakHeapUsed = used
                if (rt.totalMemory() > peakHeapTotal) peakHeapTotal = rt.totalMemory()
                samples++
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }

        fun finish() {
            running = false
            try {
                samplerThread.join(2000)
            } catch (e: InterruptedException) {
            }
            endThreadAllocated = allocated()
        }

        val allocationDelta: Long get() = endThreadAllocated - startThreadAllocated
    }

    private class NamedFileSource(
        private val inner: FileTransferSource,
        override val displayName: String
    ) : TransferSource by inner

    // ---------------------------------------------------------------- stack

    private class Stack(val destDir: File) {
        val serverStore = SoftwareDeviceIdentityStore()
        val clientStore = SoftwareDeviceIdentityStore()
        val serverDevice: DeviceInfo = AuthTestSupport.deviceFor(serverStore, "PhoneB")
        val clientDevice: DeviceInfo = AuthTestSupport.deviceFor(clientStore, "PcA")
        val log = RecordingSecurityLogger()
        val serverAuth = AuthManager(serverDevice, serverStore, log)
        val clientAuth = AuthManager(clientDevice, clientStore, log)
        val txns = TransactionManager(logger = log)
        val sessions = AuthorizedSessionStore()
        val receiver = ReceiverEngine(serverDevice, serverAuth, sessions, destDir, logger = log, dataPort = 0)
        val server: ControlServer
        val clientMailbox = TransferMailbox()
        val client: ControlConnection
        val sender: SenderEngine

        init {
            val handler = receiver.createHandler()
            serverAuth.inner = TransactionAuthorizer(serverAuth, txns, sessions, log, serverDevice.deviceId, handler)
            server = ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
            server.start()
            receiver.start()
            clientAuth.inner = clientMailbox
            client = ControlConnection(clientDevice, clientAuth)
            sender = SenderEngine(
                localDeviceId = clientDevice.deviceId,
                control = client,
                mailbox = clientMailbox,
                dataPort = receiver.localPort()
            )
        }

        fun authenticate() {
            check(client.connect("127.0.0.1", server.localPort())) { "M04 control connect failed" }
            val outcome = AuthTestSupport.awaitOutcome(clientAuth.beginAuth(client), 15000)
            check(outcome is AuthOutcome.Authenticated) { "M05 mutual auth failed: $outcome" }
            sessions.authorize(clientDevice.deviceId, UUID.randomUUID().toString())
        }

        fun close() {
            try { client.disconnect() } catch (e: Exception) { }
            try { receiver.stop() } catch (e: Exception) { }
            try { server.stop() } catch (e: Exception) { }
        }
    }

    private fun sha256Of(path: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input: InputStream ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun prepareSource(dir: File, size: Long): File {
        val path = File(dir, "m15_src_$size.bin")
        if (!path.isFile || path.length() != size) {
            Pattern.writeFile(path, size)
        }
        return path
    }

    // ----------------------------------------------------------------- test

    /**
     * Size matrix on the JVM runtime. The 5 GB / 20 GiB acceptance sizes are
     * covered by the Windows harness (same production code paths, real disk);
     * the JVM matrix covers 0 B / 1 MiB / 100 MB / 1 GB so that the Android
     * implementation is measured on the same matrix semantics.
     */
    @Test
    fun `m15 benchmark android jvm loopback size matrix`() {
        val work = File(System.getProperty("java.io.tmpdir"), "localdrop-m15-android-" + UUID.randomUUID())
        val destDir = File(work, "dest")
        val srcDir = File(work, "src")
        destDir.mkdirs()
        srcDir.mkdirs()
        try {
            val matrix = listOf(0L, 1L shl 20, 100_000_000L, 1_000_000_000L)
            val stack = Stack(destDir)
            try {
                stack.authenticate()
                println("[m15-android] runtime=${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
                for (size in matrix) {
                    val expected = expected(size)
                    val source = prepareSource(srcDir, size)
                    assertEquals("source hash must match the independent oracle", expected, sha256Of(source))

                    val repetitions = if (size >= 1_000_000_000L) 1 else 3
                    val warmups = if (size >= 1_000_000_000L) 0 else 1
                    val walls = ArrayList<Double>()
                    val senderMs = ArrayList<Double>()
                    val receiverMs = ArrayList<Double>()
                    var allocDelta = 0L
                    var peakHeap = 0L
                    var finalBlock = 0
                    var adaptations = 0
                    var stalls = 0L
                    for (rep in 0 until (warmups + repetitions)) {
                        val name = "m15_android_${size}_$rep.bin"
                        val outcomeRef = AtomicReference<TransferOutcome?>()
                        val thread = Thread({
                            outcomeRef.set(
                                stack.sender.send(
                                    SenderEngine.SendRequest(
                                        source = NamedFileSource(FileTransferSource(source), name),
                                        receiverDeviceId = stack.serverDevice.deviceId,
                                        dataHost = "127.0.0.1"
                                    )
                                )
                            )
                        }, "m15-android-sender")
                        thread.isDaemon = true
                        val sampler = MemorySampler(thread)
                        val start = System.nanoTime()
                        thread.start()
                        val received = stack.receiver.waitForAnyOutcome(600_000)
                        thread.join(600_000)
                        val elapsed = (System.nanoTime() - start) / 1_000_000.0
                        sampler.finish()

                        val sent = outcomeRef.get() as? TransferOutcome.TransportComplete
                            ?: throw AssertionError("sender failed: ${outcomeRef.get()}")
                        val got = received as? TransferOutcome.TransportComplete
                            ?: throw AssertionError("receiver failed: $received")
                        assertEquals(size, sent.bytesTransferred)
                        assertEquals(size, got.bytesTransferred)
                        assertEquals(sent.transferId, got.transferId)
                        val out = File(destDir, name)
                        assertTrue("destination file missing", out.isFile)
                        assertEquals(size, out.length())
                        assertEquals("received content must match the independent oracle", expected, sha256Of(out))
                        out.delete()

                        if (rep >= warmups) {
                            walls.add(elapsed)
                            senderMs.add(sent.elapsedMs.toDouble())
                            receiverMs.add(got.elapsedMs.toDouble())
                            allocDelta = maxOf(allocDelta, sampler.allocationDelta)
                            peakHeap = maxOf(peakHeap, sampler.peakHeapUsed)
                            finalBlock = sent.metrics.finalBlockSizeBytes
                            adaptations = sent.metrics.adaptations.size
                            stalls = sent.metrics.stallCount
                        }
                    }
                    val median = walls.sorted()[walls.size / 2]
                    val mbps = size / 1_000_000.0 / (median / 1000.0)
                    val mibps = size / 1_048_576.0 / (median / 1000.0)
                    println(
                        "[m15-android] size=$size reps=$repetitions warmups=$warmups " +
                            "wallMedianMs=${median.toLong()} ${"%.2f".format(mbps)} MB/s | " +
                            "${"%.2f".format(mibps)} MiB/s senderMs=${senderMs.sorted()[senderMs.size / 2].toLong()} " +
                            "receiverMs=${receiverMs.sorted()[receiverMs.size / 2].toLong()} " +
                            "finalBlock=$finalBlock adaptations=$adaptations stalls=$stalls " +
                            "allocDeltaBytes=$allocDelta peakHeapBytes=$peakHeap"
                    )
                    assertTrue(finalBlock in 65536..1048576)
                    assertTrue(
                        "managed allocation must not scale with file size (got $allocDelta bytes for $size bytes)",
                        allocDelta < 64L * 1024 * 1024
                    )
                }
            } finally {
                stack.close()
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
