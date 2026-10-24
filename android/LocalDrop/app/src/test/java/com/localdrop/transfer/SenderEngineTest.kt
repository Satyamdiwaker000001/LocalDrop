package com.localdrop.transfer

import com.localdrop.discovery.DeviceInfo
import com.localdrop.network.CloseReason
import com.localdrop.network.ControlChannelListener
import com.localdrop.network.ControlConnection
import com.localdrop.network.ControlServer
import com.localdrop.protocol.ErrorCode
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolError
import com.localdrop.protocol.TransferAcceptPayload
import com.localdrop.protocol.TransferRejectPayload
import com.localdrop.protocol.TypedPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// M06 §24A/B/D/E/F(small)/G: sender engine over loopback against a fake
// receiver (control ACCEPT/REJECT + data socket capture). No Internet.
class SenderEngineTest {

    private val senderId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val receiverId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    // Generates `size` deterministic bytes on demand (no disk, no RAM spike).
    class PatternStream(val size: Long, private val seed: Long = 0x5A17L) : InputStream() {
        private var position = 0L
        override fun read(): Int {
            if (position >= size) return -1
            val value = ((position * 31 + seed) and 0xFF).toInt()
            position++
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val n = minOf(length.toLong(), size - position).toInt()
            for (i in 0 until n) {
                buffer[offset + i] = ((position + i) * 31 + seed and 0xFF).toByte()
            }
            position += n
            return n
        }
    }

    class PatternSource(
        override val displayName: String,
        override val sizeBytes: Long,
        private val seed: Long = 0x5A17L
    ) : TransferSource {
        override val lastModifiedMs: Long = 0L
        override fun openStream(): InputStream = PatternStream(sizeBytes, seed)
        fun expectedBytes(): ByteArray {
            val out = ByteArrayOutputStream()
            val stream = PatternStream(sizeBytes, seed)
            val buf = ByteArray(65536)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    // Minimal receiver stand-in. Control side replies ACCEPT/REJECT to the
    // one TRANSFER_REQUEST and records everything the sender emits
    // (START/PROGRESS/COMPLETE/CANCEL/ERROR/VERIFY_REQUEST). Data side
    // captures one connection (preamble + bytes). throttleMsPerRead
    // simulates a slow receiver/disk for backpressure tests. verifyMode
    // scripts the VERIFY verdict: Match answers match=true with the
    // COMPLETE sha (a correct receiver), Mismatch answers match=false,
    // Silent never answers (engines must time out, never succeed).
    enum class VerifyMode { Match, Mismatch, Silent }

    class FakeReceiver(
        val accept: Boolean = true,
        val throttleMsPerRead: Long = 0L,
        val verifyMode: VerifyMode = VerifyMode.Match
    ) {
        val receivedControl = LinkedBlockingQueue<LocalDropMessage>()
        val dataServer = ServerSocket(0)
        val actualDataPort: Int = dataServer.localPort
        val preamble = AtomicReference<String?>(null)
        val receivedBytes = ByteArrayOutputStream()
        val dataDone = LinkedBlockingQueue<Boolean>()
        val dataThread = Thread({
            try {
                dataServer.soTimeout = 60000
                val socket = dataServer.accept()
                socket.use { s ->
                    val line = readLine(s.getInputStream(), 64)
                    preamble.set(line)
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = s.getInputStream().read(buf)
                        if (n < 0) break
                        synchronized(receivedBytes) { receivedBytes.write(buf, 0, n) }
                        if (throttleMsPerRead > 0) Thread.sleep(throttleMsPerRead)
                    }
                    dataDone.offer(true)
                }
            } catch (e: Exception) {
                dataDone.offer(false)
            }
        }, "fake-receiver-data").apply { isDaemon = true }

        val controlListener = object : ControlChannelListener {
            override fun onMessage(c: ControlConnection, m: LocalDropMessage) {
                receivedControl.offer(m)
                if (m.type == MessageType.TRANSFER_REQUEST) {
                    val req = (m.payload as TypedPayload.TransferRequest).v.metadata
                    val reply = if (accept) {
                        LocalDropMessage(
                            MessageType.TRANSFER_ACCEPT, newId(), null, null, req.transferId,
                            TypedPayload.TransferAccept(TransferAcceptPayload(req.transferId, 0L))
                        )
                    } else {
                        LocalDropMessage(
                            MessageType.TRANSFER_REJECT, newId(), null, null, req.transferId,
                            TypedPayload.TransferReject(
                                TransferRejectPayload(
                                    req.transferId, ProtocolError(ErrorCode.TRANSFER_REJECTED, "Nope.")
                                )
                            )
                        )
                    }
                    c.send(reply)
                }
                if (m.type == MessageType.VERIFY_REQUEST) {
                    val req = (m.payload as TypedPayload.VerifyRequest).v
                    if (verifyMode != VerifyMode.Silent) {
                        val match = verifyMode == VerifyMode.Match
                        c.send(
                            LocalDropMessage(
                                MessageType.VERIFY_RESULT, newId(), null, null, req.transferId,
                                TypedPayload.VerifyResult(
                                    com.localdrop.protocol.VerifyResultPayload(
                                        req.transferId, match, req.sha256Hex)
                                )
                            )
                        )
                    }
                }
            }

            override fun onClosed(c: ControlConnection, r: CloseReason, d: String) {}
        }

        fun nextControl(timeoutMs: Long = 8000L): LocalDropMessage =
            receivedControl.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("timed out waiting for control message")

        companion object {
            fun newId(): String = UUID.randomUUID().toString()

            fun readLine(input: InputStream, cap: Int): String? {
                val buf = ByteArrayOutputStream()
                while (buf.size() < cap) {
                    val b = input.read()
                    if (b < 0) return null
                    if (b == '\n'.code) break
                    buf.write(b)
                }
                if (buf.size() >= cap) return null
                val text = buf.toString(Charsets.US_ASCII)
                return if (text.length == 36) text else null
            }
        }
    }

    data class Harness(
        val controlServer: ControlServer,
        val senderControl: ControlConnection,
        val senderMailbox: TransferMailbox,
        val fake: FakeReceiver,
        val engine: SenderEngine,
        val progress: LinkedBlockingQueue<TransferProgress>
    ) {
        fun close() {
            try { senderControl.disconnect() } catch (e: Exception) { }
            try { controlServer.stop() } catch (e: Exception) { }
            try { fake.dataServer.close() } catch (e: Exception) { }
        }
    }

    private fun harness(
        accept: Boolean = true,
        requestTimeoutMs: Long = 8000L,
        throttleMsPerRead: Long = 0L,
        verifyMode: VerifyMode = VerifyMode.Match,
        verifyTimeoutMs: Long = 8000L
    ): Harness {
        val senderDevice = DeviceInfo(senderId, "PhoneA", "android")
        val receiverDevice = DeviceInfo(receiverId, "PcB", "windows")
        val fake = FakeReceiver(accept, throttleMsPerRead, verifyMode)
        val controlServer = ControlServer(
            receiverDevice, port = 0, listenerFactory = { fake.controlListener }
        )
        controlServer.start()
        val senderMailbox = TransferMailbox()
        val senderControl = ControlConnection(senderDevice, senderMailbox)
        check(senderControl.connect("127.0.0.1", controlServer.localPort())) { "control connect failed" }
        fake.dataThread.start()
        val progress = LinkedBlockingQueue<TransferProgress>()
        val engine = SenderEngine(
            localDeviceId = senderId,
            control = senderControl,
            mailbox = senderMailbox,
            dataPort = fake.actualDataPort,
            requestTimeoutMs = requestTimeoutMs,
            verifyTimeoutMs = verifyTimeoutMs,
            logger = null
        )
        return Harness(controlServer, senderControl, senderMailbox, fake, engine, progress)
    }

    private fun sendRequest(
        engine: SenderEngine,
        source: TransferSource,
        progress: LinkedBlockingQueue<TransferProgress>,
        cancel: AtomicBoolean = AtomicBoolean(false)
    ): TransferOutcome = engine.send(
        SenderEngine.SendRequest(
            source = source,
            receiverDeviceId = receiverId,
            dataHost = "127.0.0.1",
            isCancelled = { cancel.get() },
            onProgress = { progress.offer(it) }
        )
    )

    @Test
    fun `control peer host derives from live connection`() {
        val h = harness()
        try {
            assertEquals("127.0.0.1", SenderEngine.deriveControlPeerHost(h.senderControl))
        } finally {
            h.close()
        }
    }

    @Test
    fun `small file round trips byte exact with preamble`() {
        val h = harness()
        try {
            val source = PatternSource("small.bin", 100 * 1024)
            val outcome = sendRequest(h.engine, source, h.progress)
            assertTrue(outcome is TransferOutcome.TransportComplete)
            val done = outcome as TransferOutcome.TransportComplete
            assertEquals(source.sizeBytes, done.bytesTransferred)
            assertTrue(h.fake.dataDone.poll(15, TimeUnit.SECONDS) == true)
            assertArrayEquals(source.expectedBytes(), h.fake.receivedBytes.toByteArray())
            // Preamble carried a valid transfer UUID (data-plane binding).
            assertTrue(h.fake.preamble.get()?.matches(Regex("^[0-9a-f-]{36}\$")) == true)
            // Control plane saw START then COMPLETE; COMPLETE carries zeros.
            val types = h.fake.receivedControl.map { it.type }
            assertTrue(types.contains(MessageType.TRANSFER_START))
            assertTrue(types.contains(MessageType.TRANSFER_COMPLETE))
            val complete = h.fake.receivedControl.first { it.type == MessageType.TRANSFER_COMPLETE }
            val payload = (complete.payload as TypedPayload.TransferComplete).v
            val hasher = java.security.MessageDigest.getInstance("SHA-256")
            val expectedSha = hasher.digest(source.expectedBytes()).joinToString("") { "%02x".format(it) }.lowercase()
            assertEquals(expectedSha, payload.sha256Hex)
            assertEquals(source.sizeBytes, payload.fileSize)
        } finally {
            h.close()
        }
    }

    @Test
    fun `boundary sizes round trip`() {
        val sizes = listOf(0L, 1L, 63L * 1024, 64L * 1024, 65L * 1024, 256L * 1024, 1024L * 1024 + 1, 5L * 1024 * 1024)
        for (size in sizes) {
            val h = harness()
            try {
                val source = PatternSource("boundary-$size.bin", size)
                val outcome = sendRequest(h.engine, source, h.progress)
                assertTrue("size=$size -> $outcome", outcome is TransferOutcome.TransportComplete)
                assertTrue(h.fake.dataDone.poll(20, TimeUnit.SECONDS) == true)
                assertArrayEquals(source.expectedBytes(), h.fake.receivedBytes.toByteArray())
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun `large generated stream transfers with 64-bit counters`() {
        val h = harness()
        try {
            val size = 64L * 1024 * 1024
            val source = PatternSource("large.bin", size)
            val outcome = sendRequest(h.engine, source, h.progress)
            assertTrue(outcome is TransferOutcome.TransportComplete)
            assertEquals(size, (outcome as TransferOutcome.TransportComplete).bytesTransferred)
            assertTrue(h.fake.dataDone.poll(30, TimeUnit.SECONDS) == true)
            assertArrayEquals(source.expectedBytes(), h.fake.receivedBytes.toByteArray())
            val metrics = outcome.metrics
            assertEquals(size, metrics.bytesTransferred)
            assertTrue(metrics.finalBlockSizeBytes in 65536..1048576)
            assertTrue(metrics.elapsedMs >= 0)
        } finally {
            h.close()
        }
    }

    @Test
    fun `progress is monotonic and ends at 100 percent`() {
        val h = harness()
        try {
            val outcome = sendRequest(h.engine, PatternSource("prog.bin", 2L * 1024 * 1024), h.progress)
            assertTrue(outcome is TransferOutcome.TransportComplete)
            val snapshots = mutableListOf<TransferProgress>()
            h.progress.drainTo(snapshots)
            assertTrue(snapshots.isNotEmpty())
            var last = -1L
            for (s in snapshots) {
                assertTrue(s.transferredBytes >= last)
                last = s.transferredBytes
            }
            assertEquals(100.0, snapshots.last().percent, 1e-9)
            assertTrue(snapshots.any { it.state == TransferEngineState.TRANSFERRING })
        } finally {
            h.close()
        }
    }

    @Test
    fun `rejected transfer never opens data connection`() {
        val h = harness(accept = false)
        try {
            val outcome = sendRequest(h.engine, PatternSource("rej.bin", 1024), h.progress)
            assertTrue(outcome is TransferOutcome.Failed)
            val failed = outcome as TransferOutcome.Failed
            assertEquals(ErrorCode.TRANSFER_REJECTED, failed.code)
            // Data listener saw nothing (accept with short timeout fails).
            h.fake.dataServer.soTimeout = 1500
            try {
                h.fake.dataServer.accept()
                throw AssertionError("data connection must not be attempted after reject")
            } catch (e: java.net.SocketTimeoutException) {
                // Expected: no data connection.
            }
        } finally {
            h.close()
        }
    }

    @Test
    fun `cancel mid transfer aborts cleanly`() {
        val h = harness()
        try {
            val cancel = AtomicBoolean(false)
            // Cancel after the first progress snapshot lands.
            val watcher = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    if (h.progress.peek() != null) {
                        cancel.set(true)
                        return@Thread
                    }
                    Thread.sleep(5)
                }
            }, "cancel-watcher").apply { isDaemon = true; start() }
            val outcome = sendRequest(h.engine, PatternSource("cancel.bin", 64L * 1024 * 1024), h.progress, cancel)
            watcher.interrupt()
            assertTrue(outcome is TransferOutcome.Cancelled)
            // Receiver observed CANCEL on control (await arrival; sender and
            // receiver run on different threads).
            val deadline = System.currentTimeMillis() + 8000
            var sawCancel = false
            while (System.currentTimeMillis() < deadline && !sawCancel) {
                sawCancel = h.fake.receivedControl.any { it.type == MessageType.CANCEL }
                if (!sawCancel) Thread.sleep(50)
            }
            assertTrue(sawCancel)
        } finally {
            h.close()
        }
    }

    @Test
    fun `source failure maps to internal error`() {
        val h = harness()
        try {
            val bad = object : TransferSource {
                override val displayName = "bad.bin"
                override val sizeBytes = 1024L
                override val lastModifiedMs = 0L
                override fun openStream(): InputStream = object : InputStream() {
                    override fun read(): Int = throw IOException("boom")
                }
            }
            val outcome = sendRequest(h.engine, bad, h.progress)
            assertTrue(outcome is TransferOutcome.Failed)
            assertEquals(ErrorCode.INTERNAL_ERROR, (outcome as TransferOutcome.Failed).code)
        } finally {
            h.close()
        }
    }

    @Test
    fun `data connection refused maps to network lost`() {
        val h = harness()
        h.close() // frees everything including the fake data port
        // Rebuild control-only path with a dead data port.
        val senderDevice = DeviceInfo(senderId, "PhoneA", "android")
        val receiverDevice = DeviceInfo(receiverId, "PcB", "windows")
        val fake = FakeReceiver(accept = true)
        val controlServer = ControlServer(receiverDevice, port = 0, listenerFactory = { fake.controlListener })
        controlServer.start()
        try {
            val mailbox = TransferMailbox()
            val control = ControlConnection(senderDevice, mailbox)
            assertTrue(control.connect("127.0.0.1", controlServer.localPort()))
            try {
                val deadPort = run {
                    val probe = ServerSocket(0)
                    val p = probe.localPort
                    probe.close()
                    p
                }
                val engine = SenderEngine(senderId, control, mailbox, dataPort = deadPort)
                val outcome = engine.send(
                    SenderEngine.SendRequest(PatternSource("x.bin", 1024), receiverId, dataHost = "127.0.0.1")
                )
                assertTrue(outcome is TransferOutcome.Failed)
                val code = (outcome as TransferOutcome.Failed).code
                assertTrue(code == ErrorCode.NETWORK_LOST || code == ErrorCode.TIMEOUT)
            } finally {
                control.disconnect()
            }
        } finally {
            controlServer.stop()
            fake.dataServer.close()
        }
    }

    @Test
    fun `no accept reply maps to timeout`() {
        // Receiver stays silent after REQUEST (never replies).
        val senderDevice = DeviceInfo(senderId, "PhoneA", "android")
        val receiverDevice = DeviceInfo(receiverId, "PcB", "windows")
        val silent = object : com.localdrop.network.ControlChannelListener {
            override fun onMessage(c: ControlConnection, m: LocalDropMessage) {}
            override fun onClosed(c: ControlConnection, r: com.localdrop.network.CloseReason, d: String) {}
        }
        val controlServer = ControlServer(receiverDevice, port = 0, listenerFactory = { silent })
        controlServer.start()
        try {
            val mailbox = TransferMailbox()
            val control = ControlConnection(senderDevice, mailbox)
            assertTrue(control.connect("127.0.0.1", controlServer.localPort()))
            try {
                val engine = SenderEngine(senderId, control, mailbox, requestTimeoutMs = 1000L)
                val outcome = engine.send(
                    SenderEngine.SendRequest(PatternSource("x.bin", 1024), receiverId, dataHost = "127.0.0.1")
                )
                assertTrue(outcome is TransferOutcome.Failed)
                assertEquals(ErrorCode.TIMEOUT, (outcome as TransferOutcome.Failed).code)
            } finally {
                control.disconnect()
            }
        } finally {
            controlServer.stop()
        }
    }

    @Test
    fun `slow receiver backpressure holds without growth`() {
        // Receiver drains 64 KiB per ~20 ms (~3 MB/s): the sender must slow
        // to the receiver (blocking writes = backpressure, ADR 11) and
        // still finish byte-exact with bounded buffers.
        val h = harness(throttleMsPerRead = 20L)
        try {
            val size = 4L * 1024 * 1024
            val source = PatternSource("slow.bin", size)
            val start = System.currentTimeMillis()
            val outcome = sendRequest(h.engine, source, h.progress)
            val elapsed = System.currentTimeMillis() - start
            assertTrue(outcome is TransferOutcome.TransportComplete)
            // 4 MiB at ~3 MB/s must take well over a second: proves the
            // sender actually waited on the receiver (not fire-and-forget).
            assertTrue("elapsed=${elapsed}ms", elapsed > 1000)
            assertTrue(h.fake.dataDone.poll(30, TimeUnit.SECONDS) == true)
            assertArrayEquals(source.expectedBytes(), h.fake.receivedBytes.toByteArray())
            val metrics = (outcome as TransferOutcome.TransportComplete).metrics
            assertTrue(metrics.finalBlockSizeBytes in 65536..1048576)
        } finally {
            h.close()
        }
    }

    @Test
    fun `invalid file name fails before network`() {
        val h = harness()
        try {
            val outcome = sendRequest(h.engine, PatternSource("a/b.bin", 10), h.progress)
            assertTrue(outcome is TransferOutcome.Failed)
            assertTrue(h.fake.receivedControl.isEmpty())
        } finally {
            h.close()
        }
    }
}
