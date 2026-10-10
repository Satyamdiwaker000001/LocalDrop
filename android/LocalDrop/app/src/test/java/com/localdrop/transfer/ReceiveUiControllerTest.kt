package com.localdrop.transfer

import com.localdrop.discovery.LanDiscovery
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.ErrorCode
import com.localdrop.security.AuthManager
import com.localdrop.security.AuthOutcome
import com.localdrop.security.AuthTestSupport
import com.localdrop.security.PairSubmitOutcome
import com.localdrop.security.RecordingSecurityLogger
import com.localdrop.security.SoftwareDeviceIdentityStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.UUID

// M07 UI closure: ReceiveUiController integration tests. The controller is
// framework-free (plain JVM tests); the full-path cases drive a real sender
// stack over loopback into the production ReceiveCoordinator + engine, so
// progress/outcome assertions observe real transfer events — never simulated
// success.
class ReceiveUiControllerTest {

    private fun waitFor(c: ReceiveUiController, phase: ReceivePhase, timeoutMs: Long = 20000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.state.phase != phase && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals("state=${c.state}", phase, c.state.phase)
    }

    @Test
    fun `code display requires running receiver`() {
        val store = SoftwareDeviceIdentityStore()
        val device = AuthTestSupport.deviceFor(store, "Receiver")
        val destDir = File(System.getProperty("java.io.tmpdir"), "localdrop-test-" + UUID.randomUUID())
        destDir.mkdirs()
        val coordinator = ReceiveCoordinator(device, store, RecordingSecurityLogger(), destDir,
            controlPort = 0, dataPort = 0, discoveryPort = 0)
        val c = ReceiveUiController(coordinator)
        try {
            c.newCode()
            assertTrue(c.state.resultText.contains("Start receiving first"))
            assertEquals("", c.state.codeDisplay)
        } finally {
            c.close()
            try { destDir.deleteRecursively() } catch (e: Exception) { }
        }
    }

    @Test
    fun `new code shows spaced six digits with single-use note`() {
        val store = SoftwareDeviceIdentityStore()
        val device = AuthTestSupport.deviceFor(store, "Receiver")
        val destDir = File(System.getProperty("java.io.tmpdir"), "localdrop-test-" + UUID.randomUUID())
        destDir.mkdirs()
        val coordinator = ReceiveCoordinator(device, store, RecordingSecurityLogger(), destDir,
            controlPort = 0, dataPort = 0, discoveryPort = 0)
        val c = ReceiveUiController(coordinator)
        c.start()
        try {
            Thread.sleep(300)
            assertTrue(c.state.running)
            c.newCode()
            assertTrue(c.state.codeDisplay.matches(Regex("\\d{3} \\d{3}")))
            assertTrue(c.state.codeNote.contains("Single-use", ignoreCase = true))
        } finally {
            c.close()
            try { destDir.deleteRecursively() } catch (e: Exception) { }
        }
    }

    // Shared loopback rig: production ReceiveCoordinator + raw sender stack.
    private class Rig(
        val senderDevice: com.localdrop.discovery.DeviceInfo,
        val receiverDevice: com.localdrop.discovery.DeviceInfo,
        val coordinator: ReceiveCoordinator,
        val controller: ReceiveUiController,
        val control: ControlConnection,
        val senderAuth: AuthManager,
        val destDir: File
    ) {
        fun close() {
            try { controller.close() } catch (e: Exception) { }
            try { control.disconnect() } catch (e: Exception) { }
            try { destDir.deleteRecursively() } catch (e: Exception) { }
        }
    }

    private fun setupRig(): Rig {
        val senderStore = SoftwareDeviceIdentityStore()
        val receiverStore = SoftwareDeviceIdentityStore()
        val senderDevice = AuthTestSupport.deviceFor(senderStore, "Sender")
        val receiverDevice = AuthTestSupport.deviceFor(receiverStore, "Receiver")
        val log = RecordingSecurityLogger()
        val destDir = File(System.getProperty("java.io.tmpdir"), "localdrop-test-" + UUID.randomUUID())
        destDir.mkdirs()
        val coordinator = ReceiveCoordinator(receiverDevice, receiverStore, log, destDir,
            controlPort = 0, dataPort = 0, discoveryPort = 0)
        val controller = ReceiveUiController(coordinator)
        controller.start()
        controller.attachProgress()
        Thread.sleep(300)
        val senderAuth = AuthManager(senderDevice, senderStore, log)
        // No inner listener needed for auth + code submit (both complete via
        // futures); driveSend attaches a transfer mailbox afterwards.
        val control = ControlConnection(senderDevice, senderAuth)
        check(control.connect("127.0.0.1", coordinator.server.localPort())) { "control connect failed" }
        val authOutcome = AuthTestSupport.awaitOutcome(senderAuth.beginAuth(control), 15000)
        check(authOutcome is AuthOutcome.Authenticated) { "auth failed: $authOutcome" }
        return Rig(senderDevice, receiverDevice, coordinator, controller, control, senderAuth, destDir)
    }

    private fun driveSend(
        rig: Rig, fileName: String, bytes: ByteArray, code: String
    ): TransferOutcome {
        val pair = rig.senderAuth.senderSubmitCode(rig.control, code, 15000L)
        check(pair is PairSubmitOutcome.Accepted) { "code rejected: $pair" }
        // Auth + pairing are complete, so re-point the listener chain at a
        // transfer mailbox (mirrors production sender wiring where
        // AuthManager.inner is the mailbox) before driving SenderEngine.
        val acceptBox = TransferMailbox()
        rig.senderAuth.inner = acceptBox
        val sender = SenderEngine(
            localDeviceId = rig.senderDevice.deviceId, control = rig.control,
            mailbox = acceptBox, dataPort = rig.coordinator.engine.localPort())
        return sender.send(
            SenderEngine.SendRequest(
                source = ByteArrayTransferSource(fileName, bytes),
                receiverDeviceId = rig.receiverDevice.deviceId,
                dataHost = "127.0.0.1"))
    }

    @Test
    fun `inbound transfer shows real progress info and succeeds`() {
        val rig = setupRig()
        try {
            val txn = rig.coordinator.createTransaction()
            rig.controller.newCode()
            assertTrue(rig.controller.state.codeDisplay.isNotBlank())
            val bytes = ByteArray(512 * 1024) { (it and 0xFF).toByte() }
            var outcome: TransferOutcome? = null
            val t = Thread({
                outcome = driveSend(rig, "inbound.bin", bytes, txn.code)
            }, "test-sender").apply { isDaemon = true; start() }
            // Progress + incoming info must reflect the live transfer.
            val deadline = System.currentTimeMillis() + 20000
            while (System.currentTimeMillis() < deadline && rig.controller.state.progress == null) {
                Thread.sleep(25)
            }
            assertTrue(rig.controller.state.progress != null)
            assertEquals("inbound.bin", rig.controller.state.incomingName)
            assertEquals(bytes.size.toLong(), rig.controller.state.incomingSizeBytes)
            t.join(30000)
            assertTrue(outcome is TransferOutcome.TransportComplete)
            waitFor(rig.controller, ReceivePhase.Succeeded)
            assertTrue(rig.controller.state.resultText.contains("Completed"))
            val actual = File(rig.destDir, "inbound.bin").readBytes()
            assertEquals(bytes.size, actual.size)
            assertTrue(bytes.contentEquals(actual))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `receiver cancel aborts inbound and cleans up`() {
        val rig = setupRig()
        try {
            val txn = rig.coordinator.createTransaction()
            val bytes = ByteArray(8 * 1024 * 1024) { (it and 0xFF).toByte() }
            var outcome: TransferOutcome? = null
            val t = Thread({
                outcome = driveSend(rig, "cancelled.bin", bytes, txn.code)
            }, "test-sender").apply { isDaemon = true; start() }
            val deadline = System.currentTimeMillis() + 20000
            while (System.currentTimeMillis() < deadline && rig.controller.state.progress == null) {
                Thread.sleep(25)
            }
            assertTrue(rig.controller.state.progress != null)
            assertTrue(rig.controller.cancelIncoming())
            waitFor(rig.controller, ReceivePhase.Cancelled)
            assertTrue(rig.controller.state.resultText.contains("Cancelled"))
            t.join(30000)
            // Sender observes the abort as connection loss (fail-closed).
            assertTrue(outcome is TransferOutcome.Failed)
            assertEquals(ErrorCode.NETWORK_LOST, (outcome as TransferOutcome.Failed).code)
            assertFalse(File(rig.destDir, "cancelled.bin").exists())
            assertTrue(rig.destDir.listFiles()?.none { it.name.endsWith(".localdrop-part") } != false)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `close stops coordinator and ends callbacks`() {
        val store = SoftwareDeviceIdentityStore()
        val device = AuthTestSupport.deviceFor(store, "Receiver")
        val destDir = File(System.getProperty("java.io.tmpdir"), "localdrop-test-" + UUID.randomUUID())
        destDir.mkdirs()
        val coordinator = ReceiveCoordinator(device, store, RecordingSecurityLogger(), destDir,
            controlPort = 0, dataPort = 0, discoveryPort = 0)
        val c = ReceiveUiController(coordinator)
        var changes = 0
        c.onChange = { changes++ }
        c.start()
        Thread.sleep(300)
        assertTrue(coordinator.discovery.isRunning())
        val before = changes
        c.close()
        Thread.sleep(300)
        assertFalse(coordinator.discovery.isRunning())
        assertEquals(before, changes)
        try { destDir.deleteRecursively() } catch (e: Exception) { }
    }
}
