package com.localdrop.service

import com.localdrop.core.AppConfig
import com.localdrop.discovery.DiscoveredPeer
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.DiscoveryRecord
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TransferMetadata
import com.localdrop.protocol.TransferRequestPayload
import com.localdrop.protocol.TypedPayload
import com.localdrop.security.AuthManager
import com.localdrop.security.AuthOutcome
import com.localdrop.security.AuthTestSupport
import com.localdrop.security.RecordingSecurityLogger
import com.localdrop.security.SoftwareDeviceIdentityStore
import com.localdrop.transfer.ByteArrayTransferSource
import com.localdrop.transfer.ReceiveCoordinator
import com.localdrop.transfer.ReceiveUiController
import com.localdrop.transfer.SendPhase
import com.localdrop.transfer.SendUiController
import com.localdrop.transfer.TransferMailbox
import com.localdrop.transfer.TransferOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// M13 bidirectional audit: UI/ViewModel destruction must NEVER stop the
// service-owned graph, cancel tracked sends, or abort live inbound
// transfers. Deterministic JVM + loopback tests (no emulator/device):
// - release() (what ViewModel.onCleared calls) detaches observers only.
// - close() (standalone/test teardown) keeps its cancel+stop semantics.
// - graph.stop() (service teardown equivalent) releases everything.
class UiDestructionTest {

    private fun peer(id: String) = DiscoveredPeer(
        DiscoveryRecord(id, "Peer", "android", 48424L, emptyList()),
        System.currentTimeMillis(), System.currentTimeMillis(), "127.0.0.1")

    @Test
    fun `releasing send UI does not cancel the service side send`() {
        val gate = CountDownLatch(1)
        val facade = object : SendUiController.SendCoordinatorFacade {
            override fun nearbyPeers() = listOf(peer("peer-1"))
            override fun sendFile(
                peerDeviceId: String,
                source: com.localdrop.transfer.TransferSource,
                code: String,
                isCancelled: () -> Boolean,
                onProgress: (com.localdrop.transfer.TransferProgress) -> Unit
            ): TransferOutcome {
                gate.await(10, TimeUnit.SECONDS)
                return if (isCancelled()) TransferOutcome.Cancelled("t", 0L)
                else TransferOutcome.TransportComplete(
                    "t", 10L, 5L, 2.0,
                    com.localdrop.transfer.TransferMetrics(5L, 10L, 2.0, 0L, 0L, 0, emptyList(), 65536))
            }
        }
        val controller = SendUiController(facade)
        controller.refreshPeers()
        controller.selectPeer("peer-1")
        controller.setFile(ByteArrayTransferSource("a.bin", ByteArray(10)))
        controller.setCode("123456")
        assertTrue(controller.startSend())
        Thread.sleep(200) // worker is now inside facade.sendFile
        controller.release() // ViewModel.onCleared path: detach, do NOT cancel
        gate.countDown() // let the service-side send finish
        val deadline = System.currentTimeMillis() + 5000
        while (controller.state.busy && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse("send must not be stuck busy", controller.state.busy)
        // The old close()-cancels behavior must NOT apply: outcome is success.
        assertEquals(SendPhase.Succeeded, controller.state.phase)
        assertFalse(controller.startSend()) // detached controller starts nothing new
    }

    @Test
    fun `closing send UI still cancels (standalone teardown unchanged)`() {
        val entered = CountDownLatch(1)
        val senderSawCancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val facade = object : SendUiController.SendCoordinatorFacade {
            override fun nearbyPeers() = listOf(peer("peer-1"))
            override fun sendFile(
                peerDeviceId: String,
                source: com.localdrop.transfer.TransferSource,
                code: String,
                isCancelled: () -> Boolean,
                onProgress: (com.localdrop.transfer.TransferProgress) -> Unit
            ): TransferOutcome {
                entered.countDown()
                while (!isCancelled()) Thread.sleep(20)
                senderSawCancel.set(true)
                return TransferOutcome.Cancelled("t", 0L)
            }
        }
        val controller = SendUiController(facade)
        controller.refreshPeers()
        controller.selectPeer("peer-1")
        controller.setFile(ByteArrayTransferSource("a.bin", ByteArray(10)))
        controller.setCode("123456")
        assertTrue(controller.startSend())
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        controller.close() // standalone teardown: cancel + join (sender observes it)
        assertTrue("sender must observe close()-driven cancel", senderSawCancel.get())
        // Late outcome is intentionally dropped post-close (no observer left).
        assertEquals(SendPhase.Sending, controller.state.phase)
    }

    // Real incoming stack (production ReceiveCoordinator, ephemeral ports):
    // an ACCEPTED-but-not-streaming pending transfer must survive UI release
    // and only die at graph stop.
    @Test
    fun `releasing receive UI keeps the pending inbound alive until graph stop`() {
        val senderStore = SoftwareDeviceIdentityStore()
        val receiverStore = SoftwareDeviceIdentityStore()
        val senderDevice = AuthTestSupport.deviceFor(senderStore, "Sender")
        val receiverDevice = AuthTestSupport.deviceFor(receiverStore, "Receiver")
        val log = RecordingSecurityLogger()
        val destDir = File(System.getProperty("java.io.tmpdir"), "localdrop-ui-destroy-" + UUID.randomUUID())
        destDir.mkdirs()
        val coordinator = ReceiveCoordinator(receiverDevice, receiverStore, log, destDir,
            controlPort = 0, dataPort = 0, discoveryPort = 0)
        val controller = ReceiveUiController(coordinator)
        val senderAuth = AuthManager(senderDevice, senderStore, log)
        val mailbox = TransferMailbox()
        senderAuth.inner = mailbox
        val control = ControlConnection(senderDevice, senderAuth)
        try {
            controller.start()
            Thread.sleep(300)
            assertTrue(control.connect("127.0.0.1", coordinator.server.localPort()))
            val authOutcome = AuthTestSupport.awaitOutcome(senderAuth.beginAuth(control), 15000)
            assertTrue(authOutcome is AuthOutcome.Authenticated)
            coordinator.sessions.authorize(senderDevice.deviceId, UUID.randomUUID().toString())

            val tid = UUID.randomUUID().toString()
            val metadata = TransferMetadata(
                tid, senderDevice.deviceId, receiverDevice.deviceId,
                UUID.randomUUID().toString(), "pending.bin", 1000L,
                AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L)
            assertTrue(control.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, tid,
                TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            val accept = mailbox.awaitResponse(
                tid, setOf(MessageType.TRANSFER_ACCEPT, MessageType.TRANSFER_REJECT), 10000)
            assertNotNull(accept)
            assertEquals(MessageType.TRANSFER_ACCEPT, accept!!.type)
            assertEquals(1, coordinator.engine.liveTransferCount())

            // Service-owned graph over the REAL components (all ephemeral).
            val graph = TransferGraph(
                listOf(
                    object : TransferGraph.LifecycleComponent {
                        override fun start() = coordinator.server.start()
                        override fun stop() = coordinator.server.stop()
                    },
                    object : TransferGraph.LifecycleComponent {
                        override fun start() = coordinator.engine.start()
                        override fun stop() = coordinator.engine.stop()
                    },
                    object : TransferGraph.LifecycleComponent {
                        override fun start() = coordinator.discovery.start()
                        override fun stop() = coordinator.discovery.stop()
                    }),
                activeCount = { coordinator.engine.liveTransferCount() })
            graph.start() // everything already running: idempotent, no duplicates
            graph.start() // duplicate start: still exactly one initialization
            assertEquals(1, graph.startCount)

            controller.release() // ViewModel.onCleared path: detach only
            // Graph untouched: pending still live, listeners still bound.
            assertEquals(1, coordinator.engine.liveTransferCount())
            assertTrue(coordinator.server.localPort() > 0)
            assertTrue(graph.isRunning())
            controller.newCode() // blocked after release; service-side unaffected
            assertEquals(1, coordinator.engine.liveTransferCount())

            // Service teardown equivalent: everything released, waiter
            // unblocked with Cancelled (no hang).
            graph.stop()
            assertFalse(graph.isRunning())
            val terminal = coordinator.engine.waitForOutcome(tid, 5000)
            assertNotNull(terminal)
            assertTrue(terminal is TransferOutcome.Cancelled)
        } finally {
            try { control.disconnect() } catch (e: Exception) { }
            try { controller.close() } catch (e: Exception) { }
            try { destDir.deleteRecursively() } catch (e: Exception) { }
        }
    }
}
