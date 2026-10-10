package com.localdrop.transfer

import com.localdrop.discovery.DiscoveredPeer
import com.localdrop.discovery.LanDiscovery
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.DiscoveryRecord
import com.localdrop.security.AuthManager
import com.localdrop.security.AuthOutcome
import com.localdrop.security.AuthTestSupport
import com.localdrop.security.PairSubmitOutcome
import com.localdrop.security.RecordingSecurityLogger
import com.localdrop.security.SoftwareDeviceIdentityStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.util.UUID

// Verification audit F5 (Android side): ReceiveCoordinator hosts the full
// receive path while a sender discovers it and resolves the endpoint purely
// from the discovery snapshot — no manual address entry. Auth + code
// authorization enforced by the production M05 stack throughout.
class CoordinatorInteropTest {

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
    fun `resolver null unless host and port known`() {
        val base = AuthTestSupport.deviceFor(SoftwareDeviceIdentityStore(), "X")
        val record = DiscoveryRecord(
            base.deviceId, "N", "android", 0L, listOf("transfer"))
        val peer = DiscoveredPeer(record, 0L, 0L, null)
        assertNull(PeerEndpointResolver.resolve(peer))
        val noPort = peer.copy(
            record = record.copy(controlPort = 0L), lastSeenHost = "192.168.1.5")
        assertNull(PeerEndpointResolver.resolve(noPort))
        val good = peer.copy(
            record = record.copy(controlPort = 48424L), lastSeenHost = "192.168.1.5")
        assertEquals(PeerEndpointResolver.Endpoint("192.168.1.5", 48424),
            PeerEndpointResolver.resolve(good))
    }

    @Test
    fun `discovery to receive with no manual address`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val senderStore = SoftwareDeviceIdentityStore()
        val receiverStore = SoftwareDeviceIdentityStore()
        val senderDevice = AuthTestSupport.deviceFor(senderStore, "Sender")
        val receiverDevice = AuthTestSupport.deviceFor(receiverStore, "Receiver")
        val log = RecordingSecurityLogger()
        val destDir = File(System.getProperty("java.io.tmpdir"), "localdrop-test-" + UUID.randomUUID())
        destDir.mkdirs()

        // Sender-side discovery listener.
        val senderDiscovery = LanDiscovery(senderDevice, port = 0, broadcastAddress = loopback)
        senderDiscovery.start()
        // Coordinator announces INTO the sender's listener (loopback
        // cross-target); ephemeral control/data ports.
        val coordinator = ReceiveCoordinator(
            receiverDevice, receiverStore, log, destDir,
            controlPort = 0, dataPort = 0, discoveryPort = 0,
            broadcastAddress = loopback, announcePort = senderDiscovery.localPort()
        )
        coordinator.start()
        val senderMailbox = TransferMailbox()
        val senderAuth = AuthManager(senderDevice, senderStore, log)
        senderAuth.inner = senderMailbox
        val control = ControlConnection(senderDevice, senderAuth)
        try {
            // Resolve the receiver endpoint purely from discovery.
            val deadline = System.currentTimeMillis() + 15000
            var endpoint: PeerEndpointResolver.Endpoint? = null
            while (System.currentTimeMillis() < deadline) {
                val seen = senderDiscovery.snapshot()
                    .firstOrNull { it.record.deviceId == receiverDevice.deviceId }
                if (seen != null) endpoint = PeerEndpointResolver.resolve(seen)
                if (endpoint != null) break
                Thread.sleep(50)
            }
            assertTrue("receiver dialable via discovery", endpoint != null)
            // The ONLY address used below comes from the snapshot.
            assertEquals("127.0.0.1", endpoint!!.host)
            assertEquals(coordinator.server.localPort(), endpoint.port)

            check(control.connect(endpoint.host, endpoint.port)) { "control connect failed" }
            val authOutcome = AuthTestSupport.awaitOutcome(senderAuth.beginAuth(control), 15000)
            check(authOutcome is AuthOutcome.Authenticated) { "auth failed: $authOutcome" }
            // User relays the displayed code (M05 design, out-of-band here).
            val txn = coordinator.createTransaction()
            val pair = senderAuth.senderSubmitCode(control, txn.code, 15000L)
            check(pair is PairSubmitOutcome.Accepted) { "code rejected: $pair" }

            val size = 1L * 1024 * 1024
            val sender = SenderEngine(
                localDeviceId = senderDevice.deviceId, control = control,
                mailbox = senderMailbox, dataPort = coordinator.engine.localPort())
            val outcome = sender.send(
                SenderEngine.SendRequest(
                    source = PatternSource("coord.bin", size),
                    receiverDeviceId = receiverDevice.deviceId,
                    dataHost = endpoint.host))
            val sent = outcome as? TransferOutcome.TransportComplete
                ?: throw AssertionError("sender failed: $outcome")
            // Commit grace: the receiver commits on the control plane while
            // the sender returns after emitting the commit signal; hold the
            // control channel briefly so teardown cannot race the commit
            // (same discipline as the Windows TransferCoordinator linger).
            Thread.sleep(1000)
            val received = coordinator.waitForAnyOutcome(30000)
            val done = received as? TransferOutcome.TransportComplete
                ?: throw AssertionError("receiver failed: $received")
            assertEquals(sent.transferId, done.transferId)
            assertEquals(size, done.bytesTransferred)
            val expected = PatternSource("coord.bin", size).openStream()
            expected.use { stream ->
                File(destDir, "coord.bin").inputStream().use { actual ->
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
        } finally {
            try { control.disconnect() } catch (e: Exception) { }
            try { coordinator.stop() } catch (e: Exception) { }
            try { senderDiscovery.stop() } catch (e: Exception) { }
            try { destDir.deleteRecursively() } catch (e: Exception) { }
        }
    }
}
