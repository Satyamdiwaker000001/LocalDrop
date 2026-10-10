package com.localdrop.transfer

import com.localdrop.discovery.DiscoveredPeer
import com.localdrop.discovery.FakeDiscoveryClock
import com.localdrop.discovery.LanDiscovery
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.DiscoveryRecord
import com.localdrop.protocol.ErrorCode
import com.localdrop.security.AuthManager
import com.localdrop.security.AuthOutcome
import com.localdrop.security.AuthTestSupport
import com.localdrop.security.CodeReject
import com.localdrop.security.PairSubmitOutcome
import com.localdrop.security.RecordingSecurityLogger
import com.localdrop.security.SoftwareDeviceIdentityStore
import com.localdrop.security.TransactionManager
import com.localdrop.security.VerifyOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// M07 UI closure: SendUiController integration tests. The controller is
// framework-free, so these run as plain JVM tests against a stub facade
// plus REAL TransferProgress/TransferOutcome values through the production
// callback path (no simulated success), plus one full loopback run through
// the real SendCoordinator (production path, no manual address).
class SendUiControllerTest {

    private val peerId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private class StubFacade : SendUiController.SendCoordinatorFacade {
        var peers: List<DiscoveredPeer> = emptyList()
        var calls = 0
        var handler: (String, TransferSource, String, () -> Boolean, (TransferProgress) -> Unit) -> TransferOutcome =
            { _, _, _, _, _ -> error("no handler") }
        override fun nearbyPeers(): List<DiscoveredPeer> = peers
        override fun sendFile(
            peerDeviceId: String, source: TransferSource, code: String,
            isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit
        ): TransferOutcome {
            calls++
            return handler(peerDeviceId, source, code, isCancelled, onProgress)
        }
    }

    private fun makePeer(
        id: String = peerId, name: String = "Phone", controlPort: Long = 48424, host: String? = "127.0.0.1"
    ) = DiscoveredPeer(DiscoveryRecord(id, name, "android", controlPort, listOf("transfer")), 0L, 0L, host)

    private fun makeFile(name: String = "a.bin", size: Int = 100) =
        ByteArrayTransferSource(name, ByteArray(size))

    private fun waitFor(c: SendUiController, phase: SendPhase, timeoutMs: Long = 15000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.state.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals("state=${c.state}", phase, c.state.phase)
    }

    @Test
    fun `peer list updates empty and dialable flags`() {
        val facade = StubFacade()
        val c = SendUiController(facade)
        c.refreshPeers()
        assertTrue(c.state.peers.isEmpty())
        facade.peers = listOf(makePeer(), makePeer("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "Tablet", 0L, null))
        c.refreshPeers()
        assertEquals(2, c.state.peers.size)
        assertTrue(c.state.peers[0].dialable)
        assertEquals("Online", c.state.peers[0].status)
        assertFalse(c.state.peers[1].dialable)
    }

    @Test
    fun `no peers available blocks send`() {
        val facade = StubFacade()
        val c = SendUiController(facade)
        c.setFile(makeFile())
        c.setCode("123456")
        assertFalse(c.state.canSend)
        assertFalse(c.startSend())
        assertEquals(0, facade.calls)
    }

    @Test
    fun `peer disappears before send blocks with actionable message`() {
        val facade = StubFacade()
        facade.peers = listOf(makePeer())
        val c = SendUiController(facade)
        c.refreshPeers()
        c.selectPeer(peerId)
        c.setFile(makeFile())
        c.setCode("123456")
        assertTrue(c.state.canSend)
        facade.peers = emptyList()
        c.refreshPeers()
        assertTrue(c.state.peers.isEmpty())
        assertFalse(c.state.canSend)
        assertTrue(c.state.statusText.contains("no longer nearby"))
        assertFalse(c.startSend())
        assertEquals(0, facade.calls)
    }

    @Test
    fun `successful transfer reports actual terminal result`() {
        val facade = StubFacade()
        facade.peers = listOf(makePeer())
        facade.handler = { peer, source, code, _, progress ->
            assertEquals(peerId, peer)
            assertEquals("a.bin", source.displayName)
            // Raw input passes through; M05 normalization is the engine's job.
            assertEquals("123 456", code)
            progress(TransferProgress.compute("t", 100, 50, 1000, 50000.0, TransferEngineState.TRANSFERRING))
            TransferOutcome.TransportComplete("t", 100, 2000, 50000.0,
                TransferMetrics(2000, 100, 50000.0, 0, 0, 0, emptyList(), 65536))
        }
        val c = SendUiController(facade)
        c.refreshPeers()
        c.selectPeer(peerId)
        c.setFile(makeFile())
        c.setCode("123 456")
        assertTrue(c.startSend())
        waitFor(c, SendPhase.Succeeded)
        assertEquals(1, facade.calls)
        assertEquals(100.0, c.state.progressPercent, 1e-9)
        assertTrue(c.state.progressText.contains("50 B of 100 B"))
        assertTrue(c.state.resultText.contains("Completed"))
        assertTrue(c.state.resultText.contains("Phone"))
    }

    @Test
    fun `rejected authorization shows actionable error`() {
        val facade = StubFacade()
        facade.peers = listOf(makePeer())
        facade.handler = { _, _, _, _, _ ->
            TransferOutcome.Failed("t", ErrorCode.TRANSFER_REJECTED, "Invalid transfer code.")
        }
        val c = SendUiController(facade)
        c.refreshPeers()
        c.selectPeer(peerId)
        c.setFile(makeFile())
        c.setCode("000000")
        assertTrue(c.startSend())
        waitFor(c, SendPhase.Failed)
        assertTrue(c.state.resultText.contains("rejected", ignoreCase = true))
        assertTrue(c.state.resultText.contains("try again", ignoreCase = true))
    }

    @Test
    fun `invalid code blocked before network`() {
        val facade = StubFacade()
        facade.peers = listOf(makePeer())
        val c = SendUiController(facade)
        c.refreshPeers()
        c.selectPeer(peerId)
        c.setFile(makeFile())
        c.setCode("12")
        assertFalse(c.state.canSend)
        assertFalse(c.startSend())
        assertEquals(0, facade.calls)
    }

    @Test
    fun `duplicate submission prevented`() {
        val facade = StubFacade()
        facade.peers = listOf(makePeer())
        val release = CountDownLatch(1)
        facade.handler = { _, _, _, _, _ ->
            assertTrue(release.await(15, TimeUnit.SECONDS))
            TransferOutcome.TransportComplete("t", 100, 10, null,
                TransferMetrics(10, 100, null, 0, 0, 0, emptyList(), 65536))
        }
        val c = SendUiController(facade)
        c.refreshPeers()
        c.selectPeer(peerId)
        c.setFile(makeFile())
        c.setCode("123456")
        assertTrue(c.startSend())
        assertFalse(c.startSend())
        assertFalse(c.startSend())
        release.countDown()
        waitFor(c, SendPhase.Succeeded)
        assertEquals(1, facade.calls)
        c.close()
    }

    @Test
    fun `picker cancellation keeps previous state`() {
        val facade = StubFacade()
        val c = SendUiController(facade)
        c.setFile(null)
        assertEquals("", c.state.fileName)
        assertEquals(-1L, c.state.fileSizeBytes)
    }

    @Test
    fun `progress reflects real engine events`() {
        val facade = StubFacade()
        facade.peers = listOf(makePeer())
        var seen: TransferProgress? = null
        facade.handler = { _, _, _, _, progress ->
            val snaps = listOf(
                TransferProgress.compute("t", 2048, 1024, 500, 102400.0, TransferEngineState.TRANSFERRING),
                TransferProgress.compute("t", 2048, 2048, 1000, 102400.0, TransferEngineState.TRANSFERRING))
            snaps.forEach { progress(it); seen = it }
            TransferOutcome.TransportComplete("t", 2048, 1000, 102400.0,
                TransferMetrics(1000, 2048, 102400.0, 0, 0, 0, emptyList(), 65536))
        }
        val c = SendUiController(facade)
        c.refreshPeers()
        c.selectPeer(peerId)
        c.setFile(makeFile("b.bin", 2048))
        c.setCode("123456")
        assertTrue(c.startSend())
        waitFor(c, SendPhase.Succeeded)
        assertNotNull(seen)
        assertEquals(seen!!.percent, c.state.progressPercent, 1e-9)
        assertTrue(c.state.progressText.contains("2.0 KB of 2.0 KB"))
        assertTrue(c.state.speedText.contains("100.0 KB/s"))
        c.close()
    }

    @Test
    fun `cancel reports cancelled state`() {
        val facade = StubFacade()
        facade.peers = listOf(makePeer())
        // Faithful to the production contract: cooperative cancellation
        // surfaces as a Cancelled outcome, never as an escaped exception.
        facade.handler = { _, _, _, isCancelled, _ ->
            val deadline = System.currentTimeMillis() + 15000
            while (!isCancelled() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            TransferOutcome.Cancelled("t", 10)
        }
        val c = SendUiController(facade)
        c.refreshPeers()
        c.selectPeer(peerId)
        c.setFile(makeFile())
        c.setCode("123456")
        assertTrue(c.startSend())
        Thread.sleep(200)
        c.cancelSend()
        waitFor(c, SendPhase.Cancelled)
        assertTrue(c.state.resultText.contains("Cancelled"))
        c.close()
    }

    @Test
    fun `close cancels active transfer and stops callbacks`() {
        val facade = StubFacade()
        facade.peers = listOf(makePeer())
        val started = CountDownLatch(1)
        val observedCancel = CountDownLatch(1)
        var callbacksAfterClose = 0
        facade.handler = { _, _, _, isCancelled, _ ->
            started.countDown()
            val deadline = System.currentTimeMillis() + 15000
            while (!isCancelled() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            observedCancel.countDown()
            TransferOutcome.Cancelled("t", 0)
        }
        val c = SendUiController(facade)
        var changes = 0
        c.onChange = { changes++ }
        c.refreshPeers()
        c.selectPeer(peerId)
        c.setFile(makeFile())
        c.setCode("123456")
        assertTrue(c.startSend())
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val before = changes
        c.close()
        assertTrue(observedCancel.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(before, changes)
    }

    @Test
    fun `expired code is rejected by the preserved M05 rule`() {
        val clock = FakeDiscoveryClock()
        val tm = TransactionManager(clock = clock)
        val txn = tm.createTransaction()
        clock.advance(300_000L + 1)
        val outcome = tm.verifyByCode(txn.code, peerId)
        assertTrue(outcome is VerifyOutcome.Rejected)
        assertEquals(CodeReject.EXPIRED, (outcome as VerifyOutcome.Rejected).reason)
        // And a wrong code never consumes the right one.
        val clock2 = FakeDiscoveryClock()
        val tm2 = TransactionManager(clock = clock2)
        val txn2 = tm2.createTransaction()
        assertTrue(tm2.verifyByCode("000000", peerId) is VerifyOutcome.Rejected)
        assertTrue(tm2.verifyByCode(txn2.code, peerId) is VerifyOutcome.Authorized)
    }

    @Test
    fun `friendly error covers every protocol code`() {
        for (code in ErrorCode.entries) {
            val text = SendUiController.friendlyError(code, "detail")
            assertTrue(text.isNotBlank())
        }
    }

    @Test
    fun `presentation helpers format sanely`() {
        assertEquals("0 B", SendUiController.formatBytes(0))
        assertEquals("1.0 KB", SendUiController.formatBytes(1024))
        assertEquals("10.0 MB", SendUiController.formatBytes(10L * 1024 * 1024))
        assertEquals("—", SendUiController.formatSpeed(0.0))
        assertEquals("—", SendUiController.formatDuration(-1))
        assertEquals("00:05", SendUiController.formatDuration(5000))
        for (state in TransferEngineState.entries) {
            assertTrue(SendUiController.humanState(state).isNotBlank())
        }
    }

    @Test
    fun `end to end through real coordinator over loopback`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val senderStore = SoftwareDeviceIdentityStore()
        val receiverStore = SoftwareDeviceIdentityStore()
        val senderDevice = AuthTestSupport.deviceFor(senderStore, "Sender")
        val receiverDevice = AuthTestSupport.deviceFor(receiverStore, "Receiver")
        val log = RecordingSecurityLogger()
        val destDir = File(System.getProperty("java.io.tmpdir"), "localdrop-test-" + UUID.randomUUID())
        destDir.mkdirs()
        val senderDiscovery = LanDiscovery(senderDevice, port = 0, broadcastAddress = loopback)
        senderDiscovery.start()
        val receiver = ReceiveCoordinator(
            receiverDevice, receiverStore, log, destDir,
            controlPort = 0, dataPort = 0, discoveryPort = 0,
            broadcastAddress = loopback, announcePort = senderDiscovery.localPort())
        receiver.start()
        val sendCoordinator = SendCoordinator(
            senderDevice, senderStore, log, senderDiscovery,
            dataPort = receiver.engine.localPort())
        val txn = receiver.createTransaction()
        val facade = object : SendUiController.SendCoordinatorFacade {
            override fun nearbyPeers() = sendCoordinator.nearbyPeers()
            override fun sendFile(
                peerDeviceId: String, source: TransferSource, code: String,
                isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit
            ) = sendCoordinator.sendFile(peerDeviceId, source, code, isCancelled, onProgress)
        }
        val c = SendUiController(facade)
        try {
            // Wait until discovery yields a dialable endpoint (no manual IP).
            val deadline = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < deadline) {
                c.refreshPeers()
                if (c.state.peers.any { it.deviceId == receiverDevice.deviceId && it.dialable }) break
                Thread.sleep(100)
            }
            val seen = c.state.peers.first { it.deviceId == receiverDevice.deviceId }
            assertTrue(seen.dialable)
            c.selectPeer(receiverDevice.deviceId)
            val bytes = ByteArray(256 * 1024) { (it and 0xFF).toByte() }
            c.setFile(ByteArrayTransferSource("e2e.bin", bytes))
            c.setCode(txn.code)
            assertTrue(c.startSend())
            waitFor(c, SendPhase.Succeeded, 60000L)
            assertTrue(c.state.resultText.contains("Completed"))
            val received = receiver.waitForAnyOutcome(30000)
            assertTrue(received is TransferOutcome.TransportComplete)
            assertEquals(bytes.size.toLong(), (received as TransferOutcome.TransportComplete).bytesTransferred)
            val actual = File(destDir, "e2e.bin").readBytes()
            assertEquals(bytes.size, actual.size)
            assertTrue(bytes.contentEquals(actual))
        } finally {
            c.close()
            try { receiver.stop() } catch (e: Exception) { }
            try { senderDiscovery.stop() } catch (e: Exception) { }
            try { destDir.deleteRecursively() } catch (e: Exception) { }
        }
    }
}
