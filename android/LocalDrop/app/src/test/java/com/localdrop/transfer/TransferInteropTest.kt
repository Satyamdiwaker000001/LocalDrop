package com.localdrop.transfer

import com.localdrop.discovery.DeviceInfo
import com.localdrop.network.ControlConnection
import com.localdrop.network.ControlServer
import com.localdrop.protocol.MessageType
import com.localdrop.security.AuthOutcome
import com.localdrop.security.AuthManager
import com.localdrop.security.AuthTestSupport
import com.localdrop.security.AuthorizedSessionStore
import com.localdrop.security.RecordingSecurityLogger
import com.localdrop.security.SoftwareDeviceIdentityStore
import com.localdrop.security.TransactionAuthorizer
import com.localdrop.security.TransactionManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

// M07 §17D (same-stack interop): real production Android SenderEngine (M06)
// -> real production Android ReceiverEngine (M07) over loopback with the full
// M04+M05 stack (real mutual auth, real authorized session). This proves both
// engines implement the same data-plane contract; cross-runtime (JVM sender
// -> CLR receiver) was proven for this exact contract in M06, and the reverse
// direction runs via the gated Windows SenderInteropTests + JVM driver.
class TransferInteropTest {

    class PatternSource(
        override val displayName: String,
        override val sizeBytes: Long,
        private val seed: Long = 0x5A17L
    ) : TransferSource {
        override val lastModifiedMs: Long = 0L
        override fun openStream(): InputStream = object : InputStream() {
            private var position = 0L
            override fun read(): Int {
                if (position >= sizeBytes) return -1
                return ((position++ * 31 + seed) and 0xFF).toInt()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= sizeBytes) return -1
                val n = minOf(length.toLong(), sizeBytes - position).toInt()
                for (i in 0 until n) buffer[offset + i] = ((position + i) * 31 + seed and 0xFF).toByte()
                position += n
                return n
            }
        }
    }

    @Test
    fun `production sender to production receiver byte exact`() {
        val serverStore = SoftwareDeviceIdentityStore()
        val clientStore = SoftwareDeviceIdentityStore()
        val serverDevice = AuthTestSupport.deviceFor(serverStore, "PhoneB")
        val clientDevice = AuthTestSupport.deviceFor(clientStore, "PcA")
        val log = RecordingSecurityLogger()
        val serverAuth = AuthManager(serverDevice, serverStore, log)
        val clientAuth = AuthManager(clientDevice, clientStore, log)
        val txns = TransactionManager(logger = log)
        val sessions = AuthorizedSessionStore()
        val destDir = File(System.getProperty("java.io.tmpdir"), "localdrop-test-" + UUID.randomUUID())
        destDir.mkdirs()
        val receiver = ReceiverEngine(serverDevice, serverAuth, sessions, destDir, logger = log, dataPort = 0)
        val handler = receiver.createHandler()
        val authorizer = TransactionAuthorizer(serverAuth, txns, sessions, log, serverDevice.deviceId, handler)
        serverAuth.inner = authorizer
        val server = ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
        server.start()
        receiver.start()
        val clientMailbox = TransferMailbox()
        clientAuth.inner = clientMailbox
        val client = ControlConnection(clientDevice, clientAuth)
        try {
            check(client.connect("127.0.0.1", server.localPort())) { "M04 connect failed" }
            val authOutcome = AuthTestSupport.awaitOutcome(clientAuth.beginAuth(client), 15000)
            check(authOutcome is AuthOutcome.Authenticated) { "auth failed: $authOutcome" }
            sessions.authorize(clientDevice.deviceId, UUID.randomUUID().toString())

            // Production sender engine (M06) drives the whole flow.
            val sender = SenderEngine(
                localDeviceId = clientDevice.deviceId,
                control = client,
                mailbox = clientMailbox,
                dataPort = receiver.localPort()
            )
            val size = 5L * 1024 * 1024
            val progress = LinkedBlockingQueue<TransferProgress>()
            val senderOutcome = AtomicReference<TransferOutcome?>()
            val senderThread = Thread({
                senderOutcome.set(
                    sender.send(
                        SenderEngine.SendRequest(
                            source = PatternSource("interop.bin", size),
                            receiverDeviceId = serverDevice.deviceId,
                            dataHost = "127.0.0.1",
                            onProgress = { progress.offer(it) }
                        )
                    )
                )
            }, "interop-sender").apply { isDaemon = true; start() }
            val received = receiver.waitForAnyOutcome(60000)
            senderThread.join(60000)
            val sent = senderOutcome.get() as? TransferOutcome.TransportComplete
                ?: throw AssertionError("sender failed: ${senderOutcome.get()}")
            val done = received as? TransferOutcome.TransportComplete
                ?: throw AssertionError("receiver failed: $received")
            assertEquals(size, sent.bytesTransferred)
            assertEquals(size, done.bytesTransferred)
            assertEquals(sent.transferId, done.transferId)
            val final = File(destDir, "interop.bin")
            assertTrue(final.exists())
            // Stream-compare without holding two full copies in RAM.
            val expected = PatternSource("interop.bin", size).openStream()
            expected.use { stream ->
                final.inputStream().use { actual ->
                    val eb = ByteArray(65536)
                    val ab = ByteArray(65536)
                    while (true) {
                        val ne = stream.read(eb)
                        val na = actual.read(ab)
                        assertEquals(ne, na)
                        if (ne < 0) break
                        assertArrayEquals(eb.copyOf(ne), ab.copyOf(na))
                    }
                }
            }
            assertTrue(sent.metrics.finalBlockSizeBytes in 65536..1048576)
            assertTrue(done.metrics.finalBlockSizeBytes in 65536..1048576)
        } finally {
            try { client.disconnect() } catch (e: Exception) { }
            try { receiver.stop() } catch (e: Exception) { }
            try { server.stop() } catch (e: Exception) { }
            try { destDir.deleteRecursively() } catch (e: Exception) { }
        }
    }
}
