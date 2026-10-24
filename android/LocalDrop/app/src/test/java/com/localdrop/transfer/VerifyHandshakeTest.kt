package com.localdrop.transfer

import com.localdrop.core.AppConfig
import com.localdrop.discovery.DeviceInfo
import com.localdrop.network.CloseReason
import com.localdrop.network.ControlChannelListener
import com.localdrop.network.ControlConnection
import com.localdrop.network.ControlServer
import com.localdrop.protocol.ErrorCode
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.ProtocolError
import com.localdrop.protocol.TransferAcceptPayload
import com.localdrop.protocol.TransferCompletePayload
import com.localdrop.protocol.TransferMetadata
import com.localdrop.protocol.TransferRequestPayload
import com.localdrop.protocol.TransferStartPayload
import com.localdrop.protocol.TypedPayload
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Audit verify-gating: the sender reports TransportComplete ONLY after the
// receiver confirms hash verification + finalization (VERIFY_RESULT
// match=true). Scripted-fake receiver for sender verdicts; real receiver
// engine for receiver verdicts. No Internet.
class VerifyHandshakeTest {

    private val senderId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val receiverId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    // ---- scripted fake receiver (sender-side verdicts) ----

    enum class VerifyMode { Match, Mismatch, Silent }

    class FakeReceiver(val verifyMode: VerifyMode = VerifyMode.Match) {
        val receivedControl = LinkedBlockingQueue<LocalDropMessage>()
        val dataServer = ServerSocket(0)
        val actualDataPort: Int = dataServer.localPort
        val dataDone = LinkedBlockingQueue<Boolean>()
        val dataThread = Thread({
            try {
                dataServer.soTimeout = 60000
                val socket = dataServer.accept()
                socket.use { s ->
                    readLine(s.getInputStream(), 64)
                    val buf = ByteArray(65536)
                    while (true) {
                        if (s.getInputStream().read(buf) < 0) break
                    }
                    dataDone.offer(true)
                }
            } catch (e: Exception) {
                dataDone.offer(false)
            }
        }, "verify-fake-data").apply { isDaemon = true }

        val controlListener = object : ControlChannelListener {
            override fun onMessage(c: ControlConnection, m: LocalDropMessage) {
                receivedControl.offer(m)
                if (m.type == MessageType.TRANSFER_REQUEST) {
                    val req = (m.payload as TypedPayload.TransferRequest).v.metadata
                    c.send(LocalDropMessage(
                        MessageType.TRANSFER_ACCEPT, UUID.randomUUID().toString(),
                        null, null, req.transferId,
                        TypedPayload.TransferAccept(TransferAcceptPayload(req.transferId, 0L))))
                }
                if (m.type == MessageType.VERIFY_REQUEST) {
                    val req = (m.payload as TypedPayload.VerifyRequest).v
                    if (verifyMode != VerifyMode.Silent) {
                        c.send(LocalDropMessage(
                            MessageType.VERIFY_RESULT, UUID.randomUUID().toString(),
                            null, null, req.transferId,
                            TypedPayload.VerifyResult(
                                com.localdrop.protocol.VerifyResultPayload(
                                    req.transferId, verifyMode == VerifyMode.Match, req.sha256Hex))))
                    }
                }
            }

            override fun onClosed(c: ControlConnection, r: CloseReason, d: String) {}
        }

        fun awaitType(type: MessageType, timeoutMs: Long = 15000L): LocalDropMessage {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val m = receivedControl.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (m.type == type) return m
            }
            throw AssertionError("timed out waiting for $type")
        }

        companion object {
            fun readLine(input: InputStream, cap: Int): String? {
                val buf = ByteArrayOutputStream()
                while (buf.size() < cap) {
                    val b = input.read()
                    if (b < 0) return null
                    if (b == '\n'.code) break
                    buf.write(b)
                }
                return buf.toString(Charsets.US_ASCII)
            }
        }
    }

    private class SendHarness(
        val controlServer: ControlServer,
        val senderControl: ControlConnection,
        val fake: FakeReceiver,
        val engine: SenderEngine
    ) {
        fun close() {
            try { senderControl.disconnect() } catch (e: Exception) { }
            try { controlServer.stop() } catch (e: Exception) { }
            try { fake.dataServer.close() } catch (e: Exception) { }
        }
    }

    private fun sendHarness(
        verifyMode: VerifyMode,
        verifyTimeoutMs: Long = 8000L
    ): SendHarness {
        val senderDevice = DeviceInfo(senderId, "PhoneA", "android")
        val receiverDevice = DeviceInfo(receiverId, "PcB", "windows")
        val fake = FakeReceiver(verifyMode)
        val controlServer = ControlServer(
            receiverDevice, port = 0, listenerFactory = { fake.controlListener })
        controlServer.start()
        val mailbox = TransferMailbox()
        val senderControl = ControlConnection(senderDevice, mailbox)
        check(senderControl.connect("127.0.0.1", controlServer.localPort())) { "connect failed" }
        fake.dataThread.start()
        val engine = SenderEngine(
            localDeviceId = senderId, control = senderControl, mailbox = mailbox,
            dataPort = fake.actualDataPort, verifyTimeoutMs = verifyTimeoutMs)
        return SendHarness(controlServer, senderControl, fake, engine)
    }

    private class PatternSource(
        override val displayName: String,
        override val sizeBytes: Long
    ) : TransferSource {
        override val lastModifiedMs: Long = 0L
        override fun openStream(): InputStream {
            val bytes = ByteArray(sizeBytes.toInt()) { i -> ((i * 31) and 0xFF).toByte() }
            return java.io.ByteArrayInputStream(bytes)
        }
    }

    @Test
    fun `verified success returns TransportComplete after VERIFY_RESULT match`() {
        val h = sendHarness(VerifyMode.Match)
        try {
            val outcome = h.engine.send(SenderEngine.SendRequest(
                source = PatternSource("v.bin", 200000),
                receiverDeviceId = receiverId, dataHost = "127.0.0.1",
                onProgress = {}))
            assertTrue("expected TransportComplete, got $outcome",
                outcome is TransferOutcome.TransportComplete)
            // The sender actually asked; the verdict names this transfer.
            val verify = h.fake.awaitType(MessageType.VERIFY_REQUEST)
            assertEquals(
                (outcome as TransferOutcome.TransportComplete).transferId, verify.transferId)
        } finally {
            h.close()
        }
    }

    @Test
    fun `digest mismatch is Failed never success`() {
        val h = sendHarness(VerifyMode.Mismatch)
        try {
            val outcome = h.engine.send(SenderEngine.SendRequest(
                source = PatternSource("v.bin", 100000),
                receiverDeviceId = receiverId, dataHost = "127.0.0.1",
                onProgress = {}))
            assertTrue("expected Failed, got $outcome", outcome is TransferOutcome.Failed)
            val failed = outcome as TransferOutcome.Failed
            assertEquals(ErrorCode.CHECKSUM_MISMATCH, failed.code)
        } finally {
            h.close()
        }
    }

    @Test
    fun `silent verifier times out as Failed never success`() {
        val h = sendHarness(VerifyMode.Silent, verifyTimeoutMs = 3000L)
        try {
            val outcome = h.engine.send(SenderEngine.SendRequest(
                source = PatternSource("v.bin", 50000),
                receiverDeviceId = receiverId, dataHost = "127.0.0.1",
                onProgress = {}))
            assertTrue("expected Failed, got $outcome", outcome is TransferOutcome.Failed)
            // Control is still up: this is an unconfirmed-verification
            // timeout, not a connection loss.
            assertEquals(ErrorCode.TIMEOUT, (outcome as TransferOutcome.Failed).code)
            assertTrue(outcome.detail.contains("did not confirm verification"))
        } finally {
            h.close()
        }
    }

    @Test
    fun `control loss during verification is NETWORK_LOST not success`() {
        val h = sendHarness(VerifyMode.Silent, verifyTimeoutMs = 15000L)
        try {
            var outcome: TransferOutcome? = null
            val t = Thread({
                outcome = h.engine.send(SenderEngine.SendRequest(
                    source = PatternSource("v.bin", 50000),
                    receiverDeviceId = receiverId, dataHost = "127.0.0.1",
                    onProgress = {}))
            }, "verify-loss-send").apply { isDaemon = true; start() }
            // Wait until the sender is parked in the verify wait, then kill
            // the channel underneath it.
            h.fake.awaitType(MessageType.VERIFY_REQUEST)
            Thread.sleep(300)
            h.senderControl.disconnect()
            t.join(20000)
            assertNotNull(outcome)
            assertTrue("expected Failed, got $outcome", outcome is TransferOutcome.Failed)
            assertEquals(ErrorCode.NETWORK_LOST, (outcome as TransferOutcome.Failed).code)
        } finally {
            h.close()
        }
    }

    @Test
    fun `cancel during verification wait maps to Cancelled`() {
        val h = sendHarness(VerifyMode.Silent, verifyTimeoutMs = 20000L)
        try {
            val cancel = AtomicBoolean(false)
            var outcome: TransferOutcome? = null
            val t = Thread({
                outcome = h.engine.send(SenderEngine.SendRequest(
                    source = PatternSource("v.bin", 50000),
                    receiverDeviceId = receiverId, dataHost = "127.0.0.1",
                    isCancelled = { cancel.get() },
                    onProgress = {}))
            }, "verify-cancel-send").apply { isDaemon = true; start() }
            h.fake.awaitType(MessageType.VERIFY_REQUEST)
            cancel.set(true)
            t.join(20000)
            assertNotNull(outcome)
            assertTrue("expected Cancelled, got $outcome", outcome is TransferOutcome.Cancelled)
            // Cooperative cancel is signalled on the wire.
            h.fake.awaitType(MessageType.CANCEL)
        } finally {
            h.close()
        }
    }

    // ---- real receiver engine (receiver-side verdicts) ----

    private class RecvHarness(
        val senderDevice: DeviceInfo,
        val receiverDevice: DeviceInfo,
        val engine: ReceiverEngine,
        val server: ControlServer,
        val client: ControlConnection,
        val mailbox: TransferMailbox,
        val destDir: java.io.File,
        val failFinalize: AtomicBoolean = AtomicBoolean(false)
    ) {
        fun close() {
            try { client.disconnect() } catch (e: Exception) { }
            try { engine.stop() } catch (e: Exception) { }
            try { server.stop() } catch (e: Exception) { }
            try { destDir.deleteRecursively() } catch (e: Exception) { }
        }
    }

    private fun recvHarness(failFinalize: AtomicBoolean = AtomicBoolean(false)): RecvHarness {
        val serverStore = SoftwareDeviceIdentityStore()
        val clientStore = SoftwareDeviceIdentityStore()
        val serverDevice = AuthTestSupport.deviceFor(serverStore, "PhoneB")
        val clientDevice = AuthTestSupport.deviceFor(clientStore, "PcA")
        val log = RecordingSecurityLogger()
        val serverAuth = AuthManager(serverDevice, serverStore, log)
        val clientAuth = AuthManager(clientDevice, clientStore, log)
        val txns = TransactionManager(logger = log)
        val sessions = AuthorizedSessionStore()
        val destDir = java.io.File(
            System.getProperty("java.io.tmpdir"), "localdrop-verify-" + UUID.randomUUID())
        destDir.mkdirs()
        val base = FileReceiverDestination(destDir)
        // Delegating wrapper so the finalize-failure test can inject a
        // commit throw after a hash match (paced by the flag).
        val destination: ReceiverDestination = DelegatingDestination(base, failFinalize)
        val engine = ReceiverEngine(
            serverDevice, serverAuth, sessions, destination, null,
            logger = log, dataPort = 0)
        val handler = engine.createHandler()
        serverAuth.inner = TransactionAuthorizer(
            serverAuth, txns, sessions, log, serverDevice.deviceId, handler)
        val server = ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
        server.start()
        engine.start()
        val mailbox = TransferMailbox()
        clientAuth.inner = mailbox
        val client = ControlConnection(clientDevice, clientAuth)
        check(client.connect("127.0.0.1", server.localPort())) { "connect failed" }
        val authOutcome = AuthTestSupport.awaitOutcome(clientAuth.beginAuth(client), 15000)
        check(authOutcome is AuthOutcome.Authenticated) { "auth failed" }
        sessions.authorize(clientDevice.deviceId, UUID.randomUUID().toString())
        return RecvHarness(clientDevice, serverDevice, engine, server, client, mailbox, destDir, failFinalize)
    }

    private class DelegatingDestination(
        private val base: ReceiverDestination,
        private val failFinalize: AtomicBoolean
    ) : ReceiverDestination {
        override fun tempFileFor(fileName: String) = base.tempFileFor(fileName)
        override fun uniqueFinalFileFor(fileName: String) = base.uniqueFinalFileFor(fileName)
        override fun freeSpaceBytes(): Long? = base.freeSpaceBytes()
        override fun finalize(temp: java.io.File, final: java.io.File) {
            if (failFinalize.get()) throw IOException("injected finalize failure")
            base.finalize(temp, final)
        }
        override fun deleteTempQuietly(temp: java.io.File) = base.deleteTempQuietly(temp)
    }

    private fun driveBytes(
        h: RecvHarness, transferId: String, fileId: String, content: ByteArray,
        completeSha: String? = null
    ) {
        val metadata = TransferMetadata(
            transferId, h.senderDevice.deviceId, h.receiverDevice.deviceId,
            fileId, "verify.bin", content.size.toLong(),
            AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L)
        assertTrue(h.client.send(LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
            null, null, transferId,
            TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
        val accept = h.mailbox.awaitResponse(
            transferId, setOf(MessageType.TRANSFER_ACCEPT), 10000)
        assertNotNull(accept)
        val data = Socket("127.0.0.1", h.engine.localPort())
        data.use { socket ->
            val out = socket.getOutputStream()
            out.write((transferId + "\n").toByteArray(Charsets.US_ASCII))
            out.flush()
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_START, ProtocolCodec.newMessageId(),
                null, null, transferId,
                TypedPayload.TransferStart(TransferStartPayload(transferId, 0L)))))
            out.write(content)
            out.flush()
            val sha = completeSha ?: java.security.MessageDigest.getInstance("SHA-256")
                .digest(content).joinToString("") { "%02x".format(it) }.lowercase()
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_COMPLETE, ProtocolCodec.newMessageId(),
                null, null, transferId,
                TypedPayload.TransferComplete(TransferCompletePayload(transferId, content.size.toLong(), sha)))))
            // Hold the data socket briefly so the receiver can drain it.
            Thread.sleep(500)
        }
    }

    private fun awaitVerify(h: RecvHarness, transferId: String): LocalDropMessage {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            val m = h.mailbox.awaitResponse(
                transferId, setOf(MessageType.VERIFY_RESULT, MessageType.ERROR), 2000)
            if (m != null) return m
        }
        throw AssertionError("timed out waiting for VERIFY_RESULT/ERROR")
    }

    private fun requestVerify(h: RecvHarness, transferId: String, sha: String) {
        assertTrue(h.client.send(LocalDropMessage(
            MessageType.VERIFY_REQUEST, ProtocolCodec.newMessageId(),
            null, null, transferId,
            TypedPayload.VerifyRequest(
                com.localdrop.protocol.VerifyRequestPayload(transferId, sha)))))
    }

    @Test
    fun `receiver answers VERIFY_RESULT match after real finalize`() {
        val h = recvHarness()
        try {
            val tid = UUID.randomUUID().toString()
            val content = ByteArray(300000) { i -> ((i * 31) and 0xFF).toByte() }
            driveBytes(h, tid, UUID.randomUUID().toString(), content)
            val engineOutcome = h.engine.waitForOutcome(tid, 15000)
            assertTrue(engineOutcome is TransferOutcome.TransportComplete)
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(content).joinToString("") { "%02x".format(it) }.lowercase()
            requestVerify(h, tid, sha)
            val verdict = awaitVerify(h, tid)
            assertEquals(MessageType.VERIFY_RESULT, verdict.type)
            val v = (verdict.payload as TypedPayload.VerifyResult).v
            assertTrue(v.match)
            assertEquals(sha, v.sha256Hex)
            // The file actually landed byte-exact.
            assertTrue(java.io.File(h.destDir, "verify.bin").readBytes().contentEquals(content))
        } finally {
            h.close()
        }
    }

    @Test
    fun `receiver answers mismatch for wrong sha`() {
        val h = recvHarness()
        try {
            val tid = UUID.randomUUID().toString()
            val content = ByteArray(100000) { i -> ((i * 31) and 0xFF).toByte() }
            driveBytes(h, tid, UUID.randomUUID().toString(), content)
            assertTrue(h.engine.waitForOutcome(tid, 15000) is TransferOutcome.TransportComplete)
            requestVerify(h, tid, "f".repeat(64))
            val verdict = awaitVerify(h, tid)
            assertEquals(MessageType.VERIFY_RESULT, verdict.type)
            assertTrue(!(verdict.payload as TypedPayload.VerifyResult).v.match)
        } finally {
            h.close()
        }
    }

    @Test
    fun `receiver rejects VERIFY_REQUEST for unknown transfer`() {
        val h = recvHarness()
        try {
            val tid = UUID.randomUUID().toString()
            requestVerify(h, tid, "e".repeat(64))
            val verdict = awaitVerify(h, tid)
            assertEquals(MessageType.ERROR, verdict.type)
        } finally {
            h.close()
        }
    }

    @Test
    fun `delayed VERIFY_REQUEST waits for finalization then matches`() {
        val h = recvHarness()
        try {
            val tid = UUID.randomUUID().toString()
            val fileId = UUID.randomUUID().toString()
            val content = ByteArray(200000) { i -> ((i * 7) and 0xFF).toByte() }
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(content).joinToString("") { "%02x".format(it) }.lowercase()
            // Drive everything except the final COMPLETE, then ask early.
            val metadata = TransferMetadata(
                tid, h.senderDevice.deviceId, h.receiverDevice.deviceId,
                fileId, "verify.bin", content.size.toLong(),
                AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L)
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, tid,
                TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            assertNotNull(h.mailbox.awaitResponse(
                tid, setOf(MessageType.TRANSFER_ACCEPT), 10000))
            val data = Socket("127.0.0.1", h.engine.localPort())
            data.use { socket ->
                val out = socket.getOutputStream()
                out.write((tid + "\n").toByteArray(Charsets.US_ASCII))
                out.flush()
                assertTrue(h.client.send(LocalDropMessage(
                    MessageType.TRANSFER_START, ProtocolCodec.newMessageId(),
                    null, null, tid,
                    TypedPayload.TransferStart(TransferStartPayload(tid, 0L)))))
                out.write(content)
                out.flush()
                // Ask BEFORE the receiver can finish: it must wait, then
                // answer match=true (never a premature verdict).
                requestVerify(h, tid, sha)
                Thread.sleep(300)
                assertTrue(h.client.send(LocalDropMessage(
                    MessageType.TRANSFER_COMPLETE, ProtocolCodec.newMessageId(),
                    null, null, tid,
                    TypedPayload.TransferComplete(TransferCompletePayload(tid, content.size.toLong(), sha)))))
                Thread.sleep(500)
            }
            val verdict = awaitVerify(h, tid)
            assertEquals(MessageType.VERIFY_RESULT, verdict.type)
            val v = (verdict.payload as TypedPayload.VerifyResult).v
            assertTrue(v.match)
            assertEquals(sha, v.sha256Hex)
        } finally {
            h.close()
        }
    }

    @Test
    fun `corrupt bytes fail verification and VERIFY_REQUEST reports mismatch`() {
        val h = recvHarness()
        try {
            val tid = UUID.randomUUID().toString()
            val content = ByteArray(100000) { i -> ((i * 31) and 0xFF).toByte() }
            // Non-zeros wrong hash: exercises the real mismatch branch (the
            // all-zeros sentinel takes the missing-hash rejection instead).
            val wrongSha = "f".repeat(64)
            driveBytes(h, tid, UUID.randomUUID().toString(), content, completeSha = wrongSha)
            val engineOutcome = h.engine.waitForOutcome(tid, 15000)
            assertTrue("expected receiver Failed, got $engineOutcome",
                engineOutcome is TransferOutcome.Failed)
            // Discard the failure ERROR already consumed from this transfer
            // so the verdict below is unambiguously the VERIFY answer.
            h.mailbox.drain(tid)
            requestVerify(h, tid, wrongSha)
            val verdict = awaitVerify(h, tid)
            assertEquals(MessageType.VERIFY_RESULT, verdict.type)
            assertTrue(!(verdict.payload as TypedPayload.VerifyResult).v.match)
            // No final file was left behind.
            assertTrue(!java.io.File(h.destDir, "verify.bin").exists())
        } finally {
            h.close()
        }
    }

    @Test
    fun `finalize failure records non success and VERIFY_REQUEST reports mismatch`() {
        val failFinalize = AtomicBoolean(false)
        val h = recvHarness(failFinalize)
        try {
            val tid = UUID.randomUUID().toString()
            val content = ByteArray(100000) { i -> ((i * 13) and 0xFF).toByte() }
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(content).joinToString("") { "%02x".format(it) }.lowercase()
            failFinalize.set(true) // commit will throw after a hash match
            driveBytes(h, tid, UUID.randomUUID().toString(), content)
            val engineOutcome = h.engine.waitForOutcome(tid, 15000)
            assertTrue("expected receiver Failed, got $engineOutcome",
                engineOutcome is TransferOutcome.Failed)
            requestVerify(h, tid, sha)
            val verdict = awaitVerify(h, tid)
            assertEquals(MessageType.VERIFY_RESULT, verdict.type)
            // Hashes matched but nothing finalized: must NOT be success.
            assertTrue(!(verdict.payload as TypedPayload.VerifyResult).v.match)
        } finally {
            h.close()
        }
    }
}
