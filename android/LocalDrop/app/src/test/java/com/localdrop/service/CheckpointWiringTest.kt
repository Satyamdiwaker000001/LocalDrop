package com.localdrop.service

import com.localdrop.core.AppConfig
import com.localdrop.discovery.DeviceInfo
import com.localdrop.network.ControlConnection
import com.localdrop.network.ControlServer
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TransferMetadata
import com.localdrop.protocol.TransferRequestPayload
import com.localdrop.protocol.TypedPayload
import com.localdrop.security.AuthManager
import com.localdrop.security.AuthOutcome
import com.localdrop.security.AuthTestSupport
import com.localdrop.security.AuthorizedSessionStore
import com.localdrop.security.RecordingSecurityLogger
import com.localdrop.security.SoftwareDeviceIdentityStore
import com.localdrop.security.TransactionAuthorizer
import com.localdrop.security.TransactionManager
import com.localdrop.storage.CheckpointDao
import com.localdrop.storage.CheckpointEntity
import com.localdrop.storage.TransferEntity
import com.localdrop.transfer.ReceiverEngine
import com.localdrop.transfer.TransferMailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

// M13 §5: the application graph supplies the persistent checkpoint
// implementation on the real receive path; resume survives receiver
// recreation; database init failure is loud, never a silent resume disable.
class CheckpointWiringTest {

    // In-memory stand-in for the Room CheckpointDao (same contract the
    // production Room implementation serves).
    private class MemoryCheckpoints : CheckpointDao {
        private val transfers = mutableMapOf<String, TransferEntity>()
        private val checkpoints = mutableMapOf<String, CheckpointEntity>()
        override fun getCheckpointOffset(sourceDeviceId: String, fileId: String, fileSize: Long): Long? =
            transfers.values.firstOrNull {
                it.sourceDeviceId == sourceDeviceId && it.fileId == fileId && it.fileSizeBytes == fileSize
            }?.transferId?.let { checkpoints[it]?.confirmedOffsetBytes }
        override fun getCheckpointTransferId(sourceDeviceId: String, fileId: String, fileSize: Long): String? =
            transfers.values.firstOrNull {
                it.sourceDeviceId == sourceDeviceId && it.fileId == fileId && it.fileSizeBytes == fileSize
            }?.transferId
        override fun updateTransferId(oldId: String, newId: String) {
            transfers.remove(oldId)?.copy(transferId = newId)?.let { transfers[newId] = it }
        }
        override fun updateCheckpointTransferId(oldId: String, newId: String) {
            checkpoints.remove(oldId)?.copy(transferId = newId)?.let { checkpoints[newId] = it }
        }
        override fun insertTransfer(transfer: TransferEntity) { transfers[transfer.transferId] = transfer }
        override fun insertCheckpoint(checkpoint: CheckpointEntity) { checkpoints[checkpoint.transferId] = checkpoint }
        override fun deleteCheckpoint(transferId: String) { checkpoints.remove(transferId) }
        override fun deleteTransfer(transferId: String) { transfers.remove(transferId) }
    }

    private class Harness(
        val serverDevice: DeviceInfo,
        val clientDevice: DeviceInfo,
        val engine: ReceiverEngine,
        val server: ControlServer,
        val client: ControlConnection,
        val mailbox: TransferMailbox,
        val destDir: File
    ) {
        fun close() {
            try { client.disconnect() } catch (e: Exception) { }
            try { engine.stop() } catch (e: Exception) { }
            try { server.stop() } catch (e: Exception) { }
            try { destDir.deleteRecursively() } catch (e: Exception) { }
        }
    }

    private fun setup(
        checkpoints: CheckpointDao?,
        authorize: Boolean = true,
        clientStore: SoftwareDeviceIdentityStore = SoftwareDeviceIdentityStore()
    ): Harness {
        val serverStore = SoftwareDeviceIdentityStore()
        val serverDevice = AuthTestSupport.deviceFor(serverStore, "PhoneB")
        val clientDevice = AuthTestSupport.deviceFor(clientStore, "PcA")
        val log = RecordingSecurityLogger()
        val serverAuth = AuthManager(serverDevice, serverStore, log)
        val clientAuth = AuthManager(clientDevice, clientStore, log)
        val txns = TransactionManager(logger = log)
        val sessions = AuthorizedSessionStore()
        val destDir = File(System.getProperty("java.io.tmpdir"), "localdrop-m13-" + UUID.randomUUID())
        destDir.mkdirs()
        val engine = ReceiverEngine(
            serverDevice, serverAuth, sessions, destDir, checkpoints,
            logger = log, dataPort = 0)
        val handler = engine.createHandler()
        val authorizer = TransactionAuthorizer(
            serverAuth, txns, sessions, log, serverDevice.deviceId, handler)
        serverAuth.inner = authorizer
        val server = ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
        server.start()
        engine.start()
        val mailbox = TransferMailbox()
        clientAuth.inner = mailbox
        val client = ControlConnection(clientDevice, clientAuth)
        check(client.connect("127.0.0.1", server.localPort())) { "connect failed" }
        val outcome = AuthTestSupport.awaitOutcome(clientAuth.beginAuth(client), 15000)
        check(outcome is AuthOutcome.Authenticated) { "auth failed: $outcome" }
        if (authorize) sessions.authorize(clientDevice.deviceId, UUID.randomUUID().toString())
        return Harness(serverDevice, clientDevice, engine, server, client, mailbox, destDir)
    }

    private fun request(
        h: Harness, transferId: String, fileId: String, size: Long, resume: Long
    ): LocalDropMessage? {
        val metadata = TransferMetadata(
            transferId, h.clientDevice.deviceId, h.serverDevice.deviceId,
            fileId, "resume.bin", size,
            AppConfig.DEFAULT_CHECKPOINT_BYTES, null, resume)
        assertTrue(h.client.send(LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
            null, null, transferId,
            TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
        // The engine answers ACCEPT, TRANSFER_REJECT (refusals), or ERROR.
        return h.mailbox.awaitResponse(
            transferId,
            setOf(MessageType.TRANSFER_ACCEPT, MessageType.TRANSFER_REJECT, MessageType.ERROR),
            10000)
    }

    @Test
    fun `application graph supplies persistent checkpoints and resume survives recreation`() {
        // The graph supplies a persistent (here in-memory, production: Room)
        // CheckpointDao — never null in the app path.
        val store = MemoryCheckpoints()
        val resolution = CheckpointStore.resolve { store }
        assertNull(resolution.failure)
        assertNotNull(resolution.dao)

        val fileId = UUID.randomUUID().toString()
        val size = 4_000_000L
        val confirmed = 1_500_000L
        val oldId = UUID.randomUUID().toString()

        // Receiver incarnation 1: seed the persistent store the way a
        // previous interrupted transfer would have (same sender/file/size).
        // The SENDER identity is stable across incarnations (one phone);
        // only the receiver is recreated.
        val senderStore = SoftwareDeviceIdentityStore()
        val h1 = setup(resolution.dao, clientStore = senderStore)
        val senderId: String
        val receiverId: String
        try {
            senderId = h1.clientDevice.deviceId
            receiverId = h1.serverDevice.deviceId
            store.saveCheckpoint(
                TransferEntity(oldId, fileId, senderId, receiverId, "resume.bin", size),
                CheckpointEntity(oldId, confirmed))
        } finally {
            h1.close()
        }

        // Receiver incarnation 2 (recreated, SAME persistent store) honors
        // the persisted offset...
        val h2 = setup(resolution.dao, clientStore = senderStore)
        try {
            val ok = request(h2, UUID.randomUUID().toString(), fileId, size, confirmed)
            assertNotNull(ok)
            assertEquals(MessageType.TRANSFER_ACCEPT, ok!!.type)
            // ...and rejects resume claims beyond the confirmed checkpoint
            // (M10 rule, still enforced through the wired graph).
            val bad = request(h2, UUID.randomUUID().toString(), fileId, size, confirmed + 1)
            assertNotNull(bad)
            assertEquals(MessageType.TRANSFER_REJECT, bad!!.type)
        } finally {
            h2.close()
        }

        // Receiver incarnation 3 (recreated again, SAME store) still honors
        // the checkpoint: resume survives repeated recreation.
        val h3 = setup(resolution.dao, clientStore = senderStore)
        try {
            val ok = request(h3, UUID.randomUUID().toString(), fileId, size, confirmed)
            assertNotNull(ok)
            assertEquals(MessageType.TRANSFER_ACCEPT, ok!!.type)
        } finally {
            h3.close()
        }
    }

    @Test
    fun `database initialization failure is loud never silent`() {
        val boom = IllegalStateException("no such table")
        val resolution = CheckpointStore.resolve(factory = { throw boom })
        assertNull(resolution.dao)
        assertNotNull(resolution.failure)
        val note = CheckpointStore.failureNote(resolution.failure)
        assertNotNull(note)
        assertTrue(note!!.contains("Resume unavailable"))
        // And the healthy path reports no failure note.
        assertNull(CheckpointStore.failureNote(null))
    }

    @Test
    fun `code-less sender still rejected through the wired graph (M05 preserved)`() {
        // Authenticated channel but NO authorized session (no transaction
        // code submitted): the wired graph must still reject — M05 ordering
        // intact through the service-owned path.
        val h = setup(MemoryCheckpoints(), authorize = false)
        try {
            val tid = UUID.randomUUID().toString()
            val metadata = TransferMetadata(
                tid, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "x.bin", 10L,
                AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L)
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, tid,
                TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            val resp = h.mailbox.awaitResponse(
                tid,
                setOf(MessageType.TRANSFER_ACCEPT, MessageType.TRANSFER_REJECT, MessageType.ERROR),
                10000)
            assertNotNull(resp)
            assertEquals(MessageType.TRANSFER_REJECT, resp!!.type)
        } finally {
            h.close()
        }
    }
}
