package com.localdrop.transfer

import com.localdrop.network.ControlConnection
import com.localdrop.network.ControlServer
import com.localdrop.protocol.ErrorCode
import com.localdrop.protocol.ErrorPayload
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.ProtocolError
import com.localdrop.protocol.TransferAcceptPayload
import com.localdrop.protocol.TransferCompletePayload
import com.localdrop.protocol.TransferMetadata
import com.localdrop.protocol.TransferRequestPayload
import com.localdrop.protocol.TransferStartPayload
import com.localdrop.protocol.CancelPayload
import com.localdrop.protocol.TypedPayload
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// M07 §17C/E/F(small)/G (receiver side): accept/receive/finalize over
// loopback with full M04+M05 stacks (real auth, real authorized sessions).
// Mirrors the Windows M06 ReceiverEngineTests. Covers validation order, temp
// lifecycle, collisions, preamble binding, failures, cancel, progress, and
// metrics. No Internet.
class ReceiverEngineTest {

    companion object {
        const val ZEROS64 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }

    private class Harness(
        val serverStore: SoftwareDeviceIdentityStore,
        val clientStore: SoftwareDeviceIdentityStore,
        val serverDevice: com.localdrop.discovery.DeviceInfo,
        val clientDevice: com.localdrop.discovery.DeviceInfo,
        val serverAuth: AuthManager,
        val clientAuth: AuthManager,
        val sessions: AuthorizedSessionStore,
        val engine: ReceiverEngine,
        val server: ControlServer,
        val client: ControlConnection,
        val clientMailbox: TransferMailbox,
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
        authenticate: Boolean = true,
        authorize: Boolean = true,
        completeTimeoutMs: Long = 10000L,
        pendingTimeoutMs: Long = 30000L
    ): Harness {
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
        val engine = ReceiverEngine(
            serverDevice, serverAuth, sessions, destDir, logger = log,
            dataPort = 0, completeTimeoutMs = completeTimeoutMs, pendingTimeoutMs = pendingTimeoutMs
        )
        val handler = engine.createHandler()
        val authorizer = TransactionAuthorizer(serverAuth, txns, sessions, log, serverDevice.deviceId, handler)
        serverAuth.inner = authorizer
        val server = ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
        server.start()
        engine.start()
        val clientMailbox = TransferMailbox()
        clientAuth.inner = clientMailbox
        val client = ControlConnection(clientDevice, clientAuth)
        check(client.connect("127.0.0.1", server.localPort())) { "M04 connect failed" }
        if (authenticate) {
            val outcome = AuthTestSupport.awaitOutcome(clientAuth.beginAuth(client), 15000)
            check(outcome is AuthOutcome.Authenticated) { "auth failed: $outcome" }
            if (authorize) sessions.authorize(clientDevice.deviceId, UUID.randomUUID().toString())
        }
        return Harness(serverStore, clientStore, serverDevice, clientDevice, serverAuth, clientAuth,
            sessions, engine, server, client, clientMailbox, destDir)
    }

    private fun patternBytes(size: Int, seed: Long = 0x5A17L): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) out[i] = ((i * 31L + seed) and 0xFF).toByte()
        return out
    }

    // Drives one full sender-side exchange over an established harness:
    // REQUEST -> await ACCEPT -> data connect + preamble + bytes -> COMPLETE.
    private fun driveTransfer(
        h: Harness,
        transferId: String,
        fileName: String,
        content: ByteArray,
        sendComplete: Boolean = true,
        completeSize: Long? = null,
        closeDataAfterBytes: Boolean = true
    ): TransferOutcome? {
        val metadata = TransferMetadata(
            transferId, h.clientDevice.deviceId, h.serverDevice.deviceId,
            UUID.randomUUID().toString(), fileName, content.size.toLong(),
            com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
        )
        val request = LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
            null, null, transferId,
            TypedPayload.TransferRequest(TransferRequestPayload(metadata))
        )
        assertTrue(h.client.send(request))
        val accept = h.clientMailbox.awaitResponse(
            transferId, setOf(MessageType.TRANSFER_ACCEPT), 10000
        )
        assertNotNull(accept)
        val data = Socket("127.0.0.1", h.engine.localPort())
        data.use { socket ->
            val out = socket.getOutputStream()
            out.write((transferId + "\n").toByteArray(Charsets.US_ASCII))
            out.flush()
            val start = LocalDropMessage(
                MessageType.TRANSFER_START, ProtocolCodec.newMessageId(),
                null, null, transferId,
                TypedPayload.TransferStart(TransferStartPayload(transferId, 0L))
            )
            assertTrue(h.client.send(start))
            out.write(content)
            out.flush()
            if (sendComplete) {
                val hasher = java.security.MessageDigest.getInstance("SHA-256")
                val actualSha = hasher.digest(content).joinToString("") { "%02x".format(it) }.lowercase()
                val complete = LocalDropMessage(
                    MessageType.TRANSFER_COMPLETE, ProtocolCodec.newMessageId(),
                    null, null, transferId,
                    TypedPayload.TransferComplete(
                        TransferCompletePayload(transferId, completeSize ?: content.size.toLong(), actualSha))
                )
                assertTrue(h.client.send(complete))
            }
            if (!closeDataAfterBytes) Thread.sleep(500)
        }
        return h.engine.waitForOutcome(transferId, 15000)
    }

    @Test
    fun `accept receive finalize byte exact with clean temp lifecycle`() {
        val h = setup()
        try {
            val transferId = UUID.randomUUID().toString()
            val content = patternBytes(300_000)
            assertFalse(File(h.destDir, "a.bin.localdrop-part").exists())
            val outcome = driveTransfer(h, transferId, "a.bin", content)
            val done = outcome as? TransferOutcome.TransportComplete
                ?: throw AssertionError("expected TransportComplete, got $outcome")
            assertEquals(content.size.toLong(), done.bytesTransferred)
            assertFalse(File(h.destDir, "a.bin.localdrop-part").exists())
            val final = File(h.destDir, "a.bin")
            assertTrue(final.exists())
            assertArrayEquals(content, final.readBytes())
            assertEquals(content.size.toLong(), done.metrics.bytesTransferred)
            assertTrue(done.metrics.finalBlockSizeBytes in 65536..1048576)
            assertTrue(done.elapsedMs >= 0)
            assertNotNull(done.averageBytesPerSec)
        } finally {
            h.close()
        }
    }

    @Test
    fun `zero byte file accepted and finalized empty`() {
        val h = setup()
        try {
            val outcome = driveTransfer(h, UUID.randomUUID().toString(), "empty.bin", ByteArray(0))
            assertTrue(outcome is TransferOutcome.TransportComplete)
            val final = File(h.destDir, "empty.bin")
            assertTrue(final.exists())
            assertEquals(0L, final.length())
        } finally {
            h.close()
        }
    }

    @Test
    fun `existing destination never overwritten uniquified`() {
        val h = setup()
        try {
            val finalPath = File(h.destDir, "keep.bin")
            finalPath.writeBytes("original-user-file".toByteArray())
            val content = patternBytes(10_000)
            val outcome = driveTransfer(h, UUID.randomUUID().toString(), "keep.bin", content)
            assertTrue(outcome is TransferOutcome.TransportComplete)
            assertArrayEquals("original-user-file".toByteArray(), finalPath.readBytes())
            val uniquified = File(h.destDir, "keep (2).bin")
            assertTrue(uniquified.exists())
            assertArrayEquals(content, uniquified.readBytes())
        } finally {
            h.close()
        }
    }

    @Test
    fun `unauthenticated request rejected with pairing required`() {
        val h = setup(authenticate = false)
        try {
            val transferId = UUID.randomUUID().toString()
            val metadata = TransferMetadata(
                transferId, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "x.bin", 10L,
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, transferId, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            val response = h.clientMailbox.awaitResponse(
                transferId, setOf(MessageType.TRANSFER_REJECT), 10000)
            assertNotNull(response)
            val reject = (response!!.payload as TypedPayload.TransferReject).v
            assertEquals(ErrorCode.PAIRING_REQUIRED, reject.error.code)
            assertFalse(h.engine.isBusy())
            // No file created by the unauthorized request.
            assertTrue(h.destDir.listFiles()?.isEmpty() != false)
        } finally {
            h.close()
        }
    }

    @Test
    fun `no authorized session rejected`() {
        val h = setup(authenticate = true, authorize = false)
        try {
            val transferId = UUID.randomUUID().toString()
            val metadata = TransferMetadata(
                transferId, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "x.bin", 10L,
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, transferId, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            val response = h.clientMailbox.awaitResponse(
                transferId, setOf(MessageType.TRANSFER_REJECT), 10000)
            assertNotNull(response)
            val reject = (response!!.payload as TypedPayload.TransferReject).v
            assertEquals(ErrorCode.TRANSFER_REJECTED, reject.error.code)
        } finally {
            h.close()
        }
    }

    @Test
    fun `misaddressed and resume requests rejected`() {
        val h = setup()
        try {
            val cases = listOf(
                Triple(10L, UUID.randomUUID().toString(), 0L),
                Triple(1000L, h.serverDevice.deviceId, 512L)
            )
            for ((size, receiver, resume) in cases) {
                val transferId = UUID.randomUUID().toString()
                val metadata = TransferMetadata(
                    transferId, h.clientDevice.deviceId, receiver,
                    UUID.randomUUID().toString(), "f-$transferId.bin", size,
                    com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, resume
                )
                assertTrue(h.client.send(LocalDropMessage(
                    MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                    null, null, transferId, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
                val response = h.clientMailbox.awaitResponse(
                    transferId, setOf(MessageType.TRANSFER_REJECT), 10000)
                assertNotNull("case size=$size resume=$resume", response)
                val expectedCode = if (resume > 0L) ErrorCode.INVALID_PROTOCOL else ErrorCode.TRANSFER_REJECTED
                assertEquals(expectedCode,
                    (response!!.payload as TypedPayload.TransferReject).v.error.code)
            }
        } finally {
            h.close()
        }
    }

    @Test
    fun `second concurrent request accepted while first is pending`() {
        val h = setup()
        try {
            // First request occupies a pending slot (no data connected yet).
            val first = UUID.randomUUID().toString()
            val metadata = TransferMetadata(
                first, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "first.bin", 100L,
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, first, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            assertNotNull(h.clientMailbox.awaitResponse(first, setOf(MessageType.TRANSFER_ACCEPT), 10000))

            // Second request must now be ACCEPTED under multi-session (not rejected).
            val second = UUID.randomUUID().toString()
            val metadata2 = TransferMetadata(
                second, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "second.bin", 100L,
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, second, TypedPayload.TransferRequest(TransferRequestPayload(metadata2)))))
            val response = h.clientMailbox.awaitResponse(
                second, setOf(MessageType.TRANSFER_ACCEPT), 10000)
            assertNotNull("second request must be accepted, not rejected", response)

            // Cancel both pending transfers and verify outcomes.
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.CANCEL, ProtocolCodec.newMessageId(),
                null, null, first, TypedPayload.Cancel(CancelPayload(first, "test cancel")))))
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.CANCEL, ProtocolCodec.newMessageId(),
                null, null, second, TypedPayload.Cancel(CancelPayload(second, "test cancel")))))
            val outcome1 = h.engine.waitForOutcome(first, 10000)
            val outcome2 = h.engine.waitForOutcome(second, 10000)
            assertTrue(outcome1 is TransferOutcome.Cancelled)
            assertTrue(outcome2 is TransferOutcome.Cancelled)
        } finally {
            h.close()
        }
    }


    private fun shaHex(bytes: ByteArray): String {
        val hasher = java.security.MessageDigest.getInstance("SHA-256")
        return hasher.digest(bytes).joinToString("") { "%02x".format(it) }.lowercase()
    }

    // M12: sends TRANSFER_REQUEST and awaits TRANSFER_ACCEPT. Returns the
    // accepted transferId. The transfer stays pending (no data connected).
    private fun requestTransfer(h: Harness, fileName: String, content: ByteArray): String {
        val transferId = UUID.randomUUID().toString()
        val metadata = TransferMetadata(
            transferId, h.clientDevice.deviceId, h.serverDevice.deviceId,
            UUID.randomUUID().toString(), fileName, content.size.toLong(),
            com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
        )
        assertTrue(h.client.send(LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
            null, null, transferId, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
        assertNotNull("ACCEPT for $fileName",
            h.clientMailbox.awaitResponse(transferId, setOf(MessageType.TRANSFER_ACCEPT), 10000))
        return transferId
    }

    // M12: connects a data socket, binds it with the preamble, and sends
    // TRANSFER_START. Returns the OPEN socket (caller streams then closes).
    private fun openDataStream(h: Harness, transferId: String): Socket {
        val socket = Socket("127.0.0.1", h.engine.localPort())
        val out = socket.getOutputStream()
        out.write((transferId + "\n").toByteArray(Charsets.US_ASCII))
        out.flush()
        assertTrue(h.client.send(LocalDropMessage(
            MessageType.TRANSFER_START, ProtocolCodec.newMessageId(),
            null, null, transferId,
            TypedPayload.TransferStart(TransferStartPayload(transferId, 0L)))))
        return socket
    }

    private fun sendComplete(h: Harness, transferId: String, content: ByteArray) {
        assertTrue(h.client.send(LocalDropMessage(
            MessageType.TRANSFER_COMPLETE, ProtocolCodec.newMessageId(),
            null, null, transferId,
            TypedPayload.TransferComplete(
                TransferCompletePayload(transferId, content.size.toLong(), shaHex(content))))))
    }

    // M12: waits until the transfer is adopted into the active set
    // (per-transfer byte counter becomes visible), proving the data preamble
    // was accepted. Fails deterministically on timeout instead of sleeping.
    private fun awaitAdopted(h: Harness, transferId: String, timeoutMs: Long = 10000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (h.engine.activeReceivedBytes(transferId) >= 0L) return
            Thread.sleep(25)
        }
        throw AssertionError("transfer $transferId was not adopted within ${timeoutMs}ms")
    }

    // M12: two distinct transfers stream concurrently and BOTH complete with
    // exact expected content. Progress events must stay associated with the
    // correct transfer (per-ID monotonic, each ending at 100%).
    @Test
    fun `two concurrent transfers both complete byte exact`() {
        val h = setup()
        val progressById = java.util.concurrent.ConcurrentHashMap<String, MutableList<TransferProgress>>()
        h.engine.onProgress = { p ->
            progressById.getOrPut(p.transferId) {
                java.util.Collections.synchronizedList(mutableListOf())
            }.add(p)
        }
        try {
            val contentA = patternBytes(200_000, seed = 0xA11CE)
            val contentB = patternBytes(50_000, seed = 0xBEEF)
            val tidA = requestTransfer(h, "a-conc.bin", contentA)
            var tidB = ""
            val dataA = openDataStream(h, tidA)
            try {
                val outA = dataA.getOutputStream()
                outA.write(contentA, 0, 1000) // trickle, keep A open
                outA.flush()

                tidB = requestTransfer(h, "b-conc.bin", contentB)
                val dataB = openDataStream(h, tidB)
                try {
                    val outB = dataB.getOutputStream()
                    outB.write(contentB)
                    outB.flush()
                    sendComplete(h, tidB, contentB)
                } finally {
                    try { dataB.close() } catch (e: Exception) { }
                }
                val outcomeB = h.engine.waitForOutcome(tidB, 15000)
                assertTrue("B must complete, got $outcomeB",
                    outcomeB is TransferOutcome.TransportComplete)
                assertEquals(contentB.size.toLong(),
                    (outcomeB as TransferOutcome.TransportComplete).bytesTransferred)
                assertArrayEquals(contentB, File(h.destDir, "b-conc.bin").readBytes())

                // Finish A normally: byte-exact completion still works.
                outA.write(contentA, 1000, contentA.size - 1000)
                outA.flush()
                sendComplete(h, tidA, contentA)
            } finally {
                try { dataA.close() } catch (e: Exception) { }
            }
            val outcomeA = h.engine.waitForOutcome(tidA, 15000)
            assertTrue("A must complete, got $outcomeA",
                outcomeA is TransferOutcome.TransportComplete)
            assertEquals(contentA.size.toLong(),
                (outcomeA as TransferOutcome.TransportComplete).bytesTransferred)
            assertArrayEquals(contentA, File(h.destDir, "a-conc.bin").readBytes())
            assertFalse(File(h.destDir, "a-conc.bin.localdrop-part").exists())
            assertFalse(File(h.destDir, "b-conc.bin.localdrop-part").exists())

            // Progress/counters stayed with the correct transfer.
            for ((tid, expected) in listOf(tidA to contentA, tidB to contentB)) {
                val seen = progressById[tid]
                assertNotNull("no progress for $tid", seen)
                assertTrue("empty progress for $tid", seen!!.isNotEmpty())
                var last = -1L
                for (s in seen) {
                    assertEquals("progress crossed transfers", tid, s.transferId)
                    assertTrue("non-monotonic progress for $tid", s.transferredBytes >= last)
                    last = s.transferredBytes
                }
                assertEquals(100.0, seen.last().percent, 1e-9)
                assertEquals(expected.size.toLong(), seen.last().transferredBytes)
            }
        } finally {
            h.close()
        }
    }

    // M12: cancelling one transfer leaves the other running to a byte-exact
    // completion. Cancel cleanup must not touch the surviving session.
    @Test
    fun `cancelling one transfer leaves the other running`() {
        val h = setup()
        try {
            val contentA = patternBytes(300_000, seed = 0xCA9CE1)
            val contentB = patternBytes(100_000, seed = 0x5EED)
            val tidA = requestTransfer(h, "a-cancel.bin", contentA)
            val dataA = openDataStream(h, tidA)
            awaitAdopted(h, tidA)
            val tidB = requestTransfer(h, "b-survivor.bin", contentB)

            assertTrue(h.client.send(LocalDropMessage(
                MessageType.CANCEL, ProtocolCodec.newMessageId(),
                null, null, tidA, TypedPayload.Cancel(CancelPayload(tidA, "test cancel")))))
            val outcomeA = h.engine.waitForOutcome(tidA, 10000)
            assertTrue("A must be Cancelled, got $outcomeA", outcomeA is TransferOutcome.Cancelled)
            try { dataA.close() } catch (e: Exception) { }
            assertFalse(File(h.destDir, "a-cancel.bin").exists())
            assertFalse(File(h.destDir, "a-cancel.bin.localdrop-part").exists())

            // B is untouched: streams to a byte-exact completion.
            val dataB = openDataStream(h, tidB)
            try {
                val outB = dataB.getOutputStream()
                outB.write(contentB)
                outB.flush()
                sendComplete(h, tidB, contentB)
            } finally {
                try { dataB.close() } catch (e: Exception) { }
            }
            val outcomeB = h.engine.waitForOutcome(tidB, 15000)
            assertTrue("B must complete, got $outcomeB",
                outcomeB is TransferOutcome.TransportComplete)
            assertArrayEquals(contentB, File(h.destDir, "b-survivor.bin").readBytes())
            assertFalse(h.engine.isBusy())
        } finally {
            h.close()
        }
    }

    // M12: a sender ERROR addressed to one transfer must fail exactly that
    // transfer with the sender's code and leave the other session intact.
    @Test
    fun `sender error for one transfer does not fail another`() {
        val h = setup()
        try {
            val contentA = patternBytes(120_000, seed = 0xE8808)
            val contentB = patternBytes(1_000, seed = 0xBAAD)
            val tidA = requestTransfer(h, "a-clean.bin", contentA)
            val tidB = requestTransfer(h, "b-broken.bin", contentB)
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.ERROR, ProtocolCodec.newMessageId(),
                null, null, tidB,
                TypedPayload.Error(ErrorPayload(ProtocolError(ErrorCode.FILE_NOT_FOUND, "gone"), null)))))
            val outcomeB = h.engine.waitForOutcome(tidB, 10000)
            val failedB = outcomeB as? TransferOutcome.Failed
                ?: throw AssertionError("expected Failed for B, got $outcomeB")
            assertEquals(ErrorCode.FILE_NOT_FOUND, failedB.code)
            assertEquals(tidB, failedB.transferId)

            // A proceeds to a byte-exact completion with its own content.
            val dataA = openDataStream(h, tidA)
            try {
                val outA = dataA.getOutputStream()
                outA.write(contentA)
                outA.flush()
                sendComplete(h, tidA, contentA)
            } finally {
                try { dataA.close() } catch (e: Exception) { }
            }
            val outcomeA = h.engine.waitForOutcome(tidA, 15000)
            assertTrue("A must complete, got $outcomeA",
                outcomeA is TransferOutcome.TransportComplete)
            assertArrayEquals(contentA, File(h.destDir, "a-clean.bin").readBytes())
            assertFalse(File(h.destDir, "b-broken.bin").exists())
        } finally {
            h.close()
        }
    }

    // M12: two concurrent transfers for the SAME fileName follow the
    // established collision policy (never overwrite, uniquify) with both
    // payloads byte-exact and no temp leftovers.
    @Test
    fun `concurrent same filename uniquifies without corruption`() {
        val h = setup()
        try {
            val contentA = patternBytes(100_000, seed = 0xD9A)
            val contentB = patternBytes(60_000, seed = 0xD9B)
            val tidA = requestTransfer(h, "dup.bin", contentA)
            val dataA = openDataStream(h, tidA)
            val tidB = requestTransfer(h, "dup.bin", contentB)
            val dataB = openDataStream(h, tidB)
            // Both adopted (streaming) before either lands: exercises temp
            // reservation and commit-time final-name re-resolution.
            awaitAdopted(h, tidA)
            awaitAdopted(h, tidB)
            try {
                val outA = dataA.getOutputStream()
                outA.write(contentA)
                outA.flush()
                sendComplete(h, tidA, contentA)
                val outB = dataB.getOutputStream()
                outB.write(contentB)
                outB.flush()
                sendComplete(h, tidB, contentB)
            } finally {
                try { dataA.close() } catch (e: Exception) { }
                try { dataB.close() } catch (e: Exception) { }
            }
            val outcomeA = h.engine.waitForOutcome(tidA, 15000)
            val outcomeB = h.engine.waitForOutcome(tidB, 15000)
            assertTrue("A must complete, got $outcomeA",
                outcomeA is TransferOutcome.TransportComplete)
            assertTrue("B must complete, got $outcomeB",
                outcomeB is TransferOutcome.TransportComplete)
            val first = File(h.destDir, "dup.bin")
            val second = File(h.destDir, "dup (2).bin")
            assertTrue(first.exists())
            assertTrue(second.exists())
            val landed = setOf(shaHex(first.readBytes()), shaHex(second.readBytes()))
            assertEquals(setOf(shaHex(contentA), shaHex(contentB)), landed)
            assertNull(h.destDir.listFiles()?.firstOrNull { it.name.endsWith(".localdrop-part") })
            assertNull(h.destDir.listFiles()?.firstOrNull { it.name.endsWith(".localdrop-commit") })
        } finally {
            h.close()
        }
    }

    // M12: shutdown releases every session — the active transfer and every
    // pending transfer each reach a terminal Cancelled outcome.
    @Test
    fun `stop releases every active and pending transfer`() {
        val h = setup()
        try {
            val contentA = patternBytes(5 * 1024 * 1024, seed = 0x5109)
            val tidA = requestTransfer(h, "a-stop.bin", contentA)
            val dataA = openDataStream(h, tidA)
            awaitAdopted(h, tidA)
            val tidB = requestTransfer(h, "b-stop.bin", patternBytes(1_000))
            h.engine.stop()
            try { dataA.close() } catch (e: Exception) { }
            val outcomeA = h.engine.waitForOutcome(tidA, 10000)
            val outcomeB = h.engine.waitForOutcome(tidB, 10000)
            assertTrue("active must be Cancelled, got $outcomeA",
                outcomeA is TransferOutcome.Cancelled)
            assertTrue("pending must be Cancelled, got $outcomeB",
                outcomeB is TransferOutcome.Cancelled)
            assertFalse(File(h.destDir, "a-stop.bin").exists())
            assertFalse(File(h.destDir, "b-stop.bin").exists())
        } finally {
            h.close()
        }
    }

    // M12: a second request reusing a live transferId is rejected and the
    // original session still completes byte-exact.
    @Test
    fun `duplicate transfer id is rejected without disturbing live transfer`() {
        val h = setup()
        try {
            val content = patternBytes(40_000)
            val tid = requestTransfer(h, "orig.bin", content)
            val evil = TransferMetadata(
                tid, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "evil.bin", 10L,
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, tid, TypedPayload.TransferRequest(TransferRequestPayload(evil)))))
            val reject = h.clientMailbox.awaitResponse(
                tid, setOf(MessageType.TRANSFER_REJECT), 10000)
            assertNotNull("duplicate transferId must be rejected", reject)

            val data = openDataStream(h, tid)
            try {
                val out = data.getOutputStream()
                out.write(content)
                out.flush()
                sendComplete(h, tid, content)
            } finally {
                try { data.close() } catch (e: Exception) { }
            }
            val outcome = h.engine.waitForOutcome(tid, 15000)
            assertTrue(outcome is TransferOutcome.TransportComplete)
            assertArrayEquals(content, File(h.destDir, "orig.bin").readBytes())
            assertFalse(File(h.destDir, "evil.bin").exists())
        } finally {
            h.close()
        }
    }

    // M12: the bounded-concurrency limit (4 simultaneous slots) is enforced:
    // the fifth request is rejected while four are held, and slots are freed
    // as transfers terminate.
    @Test
    fun `fifth concurrent request rejected while four slots full`() {
        val h = setup()
        try {
            val held = (1..4).map { i ->
                requestTransfer(h, "slot-$i.bin", patternBytes(100))
            }
            val fifth = UUID.randomUUID().toString()
            val metadata = TransferMetadata(
                fifth, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "slot-5.bin", 100L,
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, fifth, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            val reject = h.clientMailbox.awaitResponse(
                fifth, setOf(MessageType.TRANSFER_REJECT), 10000)
            assertNotNull("fifth request must be rejected while full", reject)
            assertEquals(ErrorCode.TRANSFER_REJECTED,
                (reject!!.payload as TypedPayload.TransferReject).v.error.code)

            for (tid in held) {
                assertTrue(h.client.send(LocalDropMessage(
                    MessageType.CANCEL, ProtocolCodec.newMessageId(),
                    null, null, tid, TypedPayload.Cancel(CancelPayload(tid, "release")))))
            }
            for (tid in held) {
                val outcome = h.engine.waitForOutcome(tid, 10000)
                assertTrue("slot $tid must cancel, got $outcome",
                    outcome is TransferOutcome.Cancelled)
            }
            assertFalse(h.engine.isBusy())
            // Slot freed: a new request is accepted again.
            val after = requestTransfer(h, "slot-free.bin", patternBytes(100))
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.CANCEL, ProtocolCodec.newMessageId(),
                null, null, after, TypedPayload.Cancel(CancelPayload(after, "release")))))
            assertTrue(h.engine.waitForOutcome(after, 10000) is TransferOutcome.Cancelled)
        } finally {
            h.close()
        }
    }


    @Test
    fun `unknown preamble closes data socket without touching files`() {
        val h = setup()
        try {
            val bogus = UUID.randomUUID().toString()
            Socket("127.0.0.1", h.engine.localPort()).use { socket ->
                val out = socket.getOutputStream()
                out.write((bogus + "\n").toByteArray(Charsets.US_ASCII))
                out.flush()
                Thread.sleep(300)
            }
            Thread.sleep(700) // let the engine process + close
            assertTrue(h.destDir.listFiles()?.isEmpty() != false)
            assertFalse(h.engine.isBusy())
            // Engine still serves a legitimate transfer afterwards.
            val outcome = driveTransfer(h, UUID.randomUUID().toString(), "after.bin", patternBytes(1024))
            assertTrue(outcome is TransferOutcome.TransportComplete)
        } finally {
            h.close()
        }
    }

    @Test
    fun `early EOF fails and cleans temp`() {
        val h = setup()
        try {
            val transferId = UUID.randomUUID().toString()
            val full = patternBytes(200_000)
            val metadata = TransferMetadata(
                transferId, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "cut.bin", full.size.toLong(),
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, transferId, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            assertNotNull(h.clientMailbox.awaitResponse(transferId, setOf(MessageType.TRANSFER_ACCEPT), 10000))
            Socket("127.0.0.1", h.engine.localPort()).use { socket ->
                val out = socket.getOutputStream()
                out.write((transferId + "\n").toByteArray(Charsets.US_ASCII))
                out.flush()
                out.write(full, 0, full.size / 2) // half, then close = early EOF
                out.flush()
            }
            val outcome = h.engine.waitForOutcome(transferId, 15000)
            val failed = outcome as? TransferOutcome.Failed
                ?: throw AssertionError("expected Failed, got $outcome")
            assertEquals(ErrorCode.NETWORK_LOST, failed.code)
            assertTrue(File(h.destDir, "cut.bin.localdrop-part").exists())
            assertFalse(File(h.destDir, "cut.bin").exists())
        } finally {
            h.close()
        }
    }

    @Test
    fun `missing complete times out without false completion`() {
        val h = setup(completeTimeoutMs = 1000L)
        try {
            val outcome = driveTransfer(h, UUID.randomUUID().toString(), "nocomplete.bin",
                patternBytes(50_000), sendComplete = false)
            val failed = outcome as? TransferOutcome.Failed
                ?: throw AssertionError("expected Failed, got $outcome")
            assertEquals(ErrorCode.TIMEOUT, failed.code)
            assertTrue(File(h.destDir, "nocomplete.bin.localdrop-part").exists())
            assertFalse(File(h.destDir, "nocomplete.bin").exists())
        } finally {
            h.close()
        }
    }

    @Test
    fun `completion size mismatch fails without finalizing`() {
        val h = setup()
        try {
            val content = patternBytes(50_000)
            val outcome = driveTransfer(h, UUID.randomUUID().toString(), "mismatch.bin", content,
                completeSize = content.size.toLong() + 100)
            val failed = outcome as? TransferOutcome.Failed
                ?: throw AssertionError("expected Failed, got $outcome")
            assertEquals(ErrorCode.INVALID_PROTOCOL, failed.code)
            assertFalse(File(h.destDir, "mismatch.bin").exists())
        } finally {
            h.close()
        }
    }

    @Test
    fun `cancel mid transfer cleans temp and reports cancelled`() {
        val h = setup()
        try {
            val transferId = UUID.randomUUID().toString()
            val content = patternBytes(20 * 1024 * 1024)
            val metadata = TransferMetadata(
                transferId, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "cancel.bin", content.size.toLong(),
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, transferId, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            assertNotNull(h.clientMailbox.awaitResponse(transferId, setOf(MessageType.TRANSFER_ACCEPT), 10000))
            val socket = Socket("127.0.0.1", h.engine.localPort())
            val writer = Thread({
                try {
                    val out = socket.getOutputStream()
                    out.write((transferId + "\n").toByteArray(Charsets.US_ASCII))
                    out.flush()
                    var offset = 0
                    val chunk = ByteArray(65536)
                    while (offset < content.size) {
                        val n = minOf(chunk.size, content.size - offset)
                        System.arraycopy(content, offset, chunk, 0, n)
                        out.write(chunk, 0, n)
                        out.flush()
                        offset += n
                        Thread.sleep(10)
                    }
                } catch (e: Exception) { }
            }, "cancel-writer").apply { isDaemon = true; start() }
            // Wait until bytes are flowing, then cancel over control.
            val deadline = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < deadline && h.engine.activeReceivedBytes() <= 0) {
                Thread.sleep(25)
            }
            assertTrue(h.engine.activeReceivedBytes() > 0)
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.CANCEL, ProtocolCodec.newMessageId(),
                null, null, transferId, TypedPayload.Cancel(CancelPayload(transferId, "test cancel")))))
            val outcome = h.engine.waitForOutcome(transferId, 15000)
            writer.join(5000)
            try { socket.close() } catch (e: Exception) { }
            assertTrue(outcome is TransferOutcome.Cancelled)
            assertFalse(File(h.destDir, "cancel.bin.localdrop-part").exists())
            assertFalse(File(h.destDir, "cancel.bin").exists())
        } finally {
            h.close()
        }
    }

    @Test
    fun `sender error during pending fails with sender code`() {
        val h = setup()
        try {
            val transferId = UUID.randomUUID().toString()
            val metadata = TransferMetadata(
                transferId, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "err.bin", 100L,
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, transferId, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            assertNotNull(h.clientMailbox.awaitResponse(transferId, setOf(MessageType.TRANSFER_ACCEPT), 10000))
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.ERROR, ProtocolCodec.newMessageId(),
                null, null, transferId,
                TypedPayload.Error(ErrorPayload(ProtocolError(ErrorCode.FILE_NOT_FOUND, "gone"), null)))))
            val outcome = h.engine.waitForOutcome(transferId, 10000)
            val failed = outcome as? TransferOutcome.Failed
                ?: throw AssertionError("expected Failed, got $outcome")
            assertEquals(ErrorCode.FILE_NOT_FOUND, failed.code)
        } finally {
            h.close()
        }
    }

    @Test
    fun `progress is monotonic and ends at 100 percent`() {
        val h = setup()
        try {
            val seen = LinkedBlockingQueue<TransferProgress>()
            h.engine.onProgress = { seen.offer(it) }
            val outcome = driveTransfer(h, UUID.randomUUID().toString(), "prog.bin", patternBytes(2 * 1024 * 1024))
            assertTrue(outcome is TransferOutcome.TransportComplete)
            val snapshots = mutableListOf<TransferProgress>()
            seen.drainTo(snapshots)
            assertTrue(snapshots.isNotEmpty())
            var last = -1L
            for (s in snapshots) {
                assertTrue(s.transferredBytes >= last)
                last = s.transferredBytes
            }
            assertEquals(100.0, snapshots.last().percent, 1e-9)
        } finally {
            h.close()
        }
    }

    @Test
    fun `temp uses localdrop-part suffix and disappears after finalize`() {
        val h = setup()
        try {
            val dest = FileReceiverDestination(h.destDir)
            assertEquals("f.bin.localdrop-part", dest.tempFileFor("f.bin").name)
            val outcome = driveTransfer(h, UUID.randomUUID().toString(), "f.bin", patternBytes(4096))
            assertTrue(outcome is TransferOutcome.TransportComplete)
            assertNull(h.destDir.listFiles()?.firstOrNull { it.name.endsWith(".localdrop-part") })
        } finally {
            h.close()
        }
    }

    @Test
    fun `slow drip still completes byte exact`() {
        val h = setup()
        try {
            val transferId = UUID.randomUUID().toString()
            val content = patternBytes(256 * 1024)
            val metadata = TransferMetadata(
                transferId, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "drip.bin", content.size.toLong(),
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, transferId, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            assertNotNull(h.clientMailbox.awaitResponse(transferId, setOf(MessageType.TRANSFER_ACCEPT), 10000))
            Socket("127.0.0.1", h.engine.localPort()).use { socket ->
                val out = socket.getOutputStream()
                out.write((transferId + "\n").toByteArray(Charsets.US_ASCII))
                out.flush()
                // 1 KiB per ~5 ms: slow storage/network simulation.
                var offset = 0
                while (offset < content.size) {
                    val n = minOf(1024, content.size - offset)
                    out.write(content, offset, n)
                    out.flush()
                    offset += n
                    Thread.sleep(5)
                }
                val hasher = java.security.MessageDigest.getInstance("SHA-256")
                val actualSha = hasher.digest(content).joinToString("") { "%02x".format(it) }.lowercase()
                val complete = LocalDropMessage(
                    MessageType.TRANSFER_COMPLETE, ProtocolCodec.newMessageId(),
                    null, null, transferId,
                    TypedPayload.TransferComplete(
                        TransferCompletePayload(transferId, content.size.toLong(), actualSha))
                )
                assertTrue(h.client.send(complete))
            }
            val outcome = h.engine.waitForOutcome(transferId, 30000)
            assertTrue(outcome is TransferOutcome.TransportComplete)
            assertArrayEquals(content, File(h.destDir, "drip.bin").readBytes())
        } finally {
            h.close()
        }
    }

    @Test
    fun `large counters progress math without overflow`() {
        val total = 50L * 1024 * 1024 * 1024
        val progress = TransferProgress.compute("t", total, total / 2, 10_000, 100_000_000.0,
            TransferEngineState.TRANSFERRING)
        assertEquals(50.0, progress.percent, 1e-9)
        assertEquals(total / 2, progress.transferredBytes)
        assertNotNull(progress.etaMs)
    }

    // Verification audit (§7 races): cancel wins deterministically when the
    // receiver waits for COMPLETE; a LATE complete must not append a second
    // outcome, and no final file may appear.
    @Test
    fun `cancel during complete wait yields exactly one cancelled`() {
        val h = setup(completeTimeoutMs = 15000L)
        try {
            val transferId = UUID.randomUUID().toString()
            val content = patternBytes(50_000)
            val metadata = TransferMetadata(
                transferId, h.clientDevice.deviceId, h.serverDevice.deviceId,
                UUID.randomUUID().toString(), "race.bin", content.size.toLong(),
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, transferId, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            assertNotNull(h.clientMailbox.awaitResponse(transferId, setOf(MessageType.TRANSFER_ACCEPT), 10000))
            val socket = Socket("127.0.0.1", h.engine.localPort())
            try {
                val out = socket.getOutputStream()
                out.write((transferId + "\n").toByteArray(Charsets.US_ASCII))
                out.flush()
                out.write(content)
                out.flush()
                // All bytes delivered; receiver now waits for COMPLETE.
                val deadline = System.currentTimeMillis() + 10000
                while (System.currentTimeMillis() < deadline && h.engine.activeReceivedBytes() < content.size) {
                    Thread.sleep(25)
                }
                assertEquals(content.size.toLong(), h.engine.activeReceivedBytes())
                assertTrue(h.client.send(LocalDropMessage(
                    MessageType.CANCEL, ProtocolCodec.newMessageId(),
                    null, null, transferId, TypedPayload.Cancel(CancelPayload(transferId, "race cancel")))))
                val outcome = h.engine.waitForOutcome(transferId, 10000)
                assertTrue(outcome is TransferOutcome.Cancelled)
                // Late COMPLETE after cancellation: stray, single outcome stands.
                assertTrue(h.client.send(LocalDropMessage(
                    MessageType.TRANSFER_COMPLETE, ProtocolCodec.newMessageId(),
                    null, null, transferId,
                    TypedPayload.TransferComplete(
                        TransferCompletePayload(transferId, content.size.toLong(), "0000000000000000000000000000000000000000000000000000000000000000")))))
                Thread.sleep(500)
                // Exactly one terminal outcome: the queued Cancelled, then nothing.
                val queued = h.engine.waitForAnyOutcome(2000)
                assertTrue(queued is TransferOutcome.Cancelled)
                assertEquals(transferId, (queued as TransferOutcome.Cancelled).transferId)
                assertNull(h.engine.waitForAnyOutcome(1000))
                assertFalse(File(h.destDir, "race.bin").exists())
                assertFalse(File(h.destDir, "race.bin.localdrop-part").exists())
            } finally {
                try { socket.close() } catch (e: Exception) { }
            }
        } finally {
            h.close()
        }
    }

    // Mirror: after a clean completion, a late CANCEL must not append a
    // second outcome.
    @Test
    fun `late cancel after completion does not duplicate outcome`() {
        val h = setup()
        try {
            val outcome = driveTransfer(h, UUID.randomUUID().toString(), "latecancel.bin", patternBytes(4096))
            assertTrue(outcome is TransferOutcome.TransportComplete)
            val transferId = (outcome as TransferOutcome.TransportComplete).transferId
            assertTrue(h.client.send(LocalDropMessage(
                MessageType.CANCEL, ProtocolCodec.newMessageId(),
                null, null, transferId, TypedPayload.Cancel(CancelPayload(transferId, "late")))))
            Thread.sleep(500)
            // Exactly one terminal outcome: the queued TransportComplete, then nothing.
            val queued = h.engine.waitForAnyOutcome(2000)
            assertTrue(queued is TransferOutcome.TransportComplete)
            assertEquals(transferId, (queued as TransferOutcome.TransportComplete).transferId)
            assertNull(h.engine.waitForAnyOutcome(1000))
            assertTrue(File(h.destDir, "latecancel.bin").exists())
        } finally {
            h.close()
        }
    }

    // Verification audit (§7 boundedness): a flood of stray TRANSFER_COMPLETE
    // frames must not break the live transfer or grow without bound.
    @Test
    fun `stray complete flood does not break live transfer`() {
        val h = setup()
        try {
            repeat(100) { i ->
                val bogus = UUID.randomUUID().toString()
                assertTrue(h.client.send(LocalDropMessage(
                    MessageType.TRANSFER_COMPLETE, ProtocolCodec.newMessageId(),
                    null, null, bogus,
                    TypedPayload.TransferComplete(
                        TransferCompletePayload(bogus, i.toLong(), "0000000000000000000000000000000000000000000000000000000000000000")))))
            }
            val outcome = driveTransfer(h, UUID.randomUUID().toString(), "after-flood.bin", patternBytes(8192))
            assertTrue(outcome is TransferOutcome.TransportComplete)
            assertArrayEquals(patternBytes(8192), File(h.destDir, "after-flood.bin").readBytes())
        } finally {
            h.close()
        }
    }

    // Verification audit F2: if destination setup fails at data adoption
    // (simulated here), the pending slot must be released immediately — the
    // receiver must report Failed AND become idle (not busy until prune).
    class FailingSetupDestination(private val delegate: FileReceiverDestination) : ReceiverDestination by delegate {
        override fun uniqueFinalFileFor(fileName: String): File =
            throw java.io.IOException("simulated setup failure")
    }

    @Test
    fun `adoption setup failure releases pending slot`() {
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
        val engine = ReceiverEngine(
            serverDevice, serverAuth, sessions,
            FailingSetupDestination(FileReceiverDestination(destDir)), logger = log, dataPort = 0
        )
        val handler = engine.createHandler()
        val authorizer = TransactionAuthorizer(serverAuth, txns, sessions, log, serverDevice.deviceId, handler)
        serverAuth.inner = authorizer
        val server = ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
        server.start()
        engine.start()
        val clientMailbox = TransferMailbox()
        clientAuth.inner = clientMailbox
        val client = ControlConnection(clientDevice, clientAuth)
        try {
            check(client.connect("127.0.0.1", server.localPort())) { "M04 connect failed" }
            val authOutcome = AuthTestSupport.awaitOutcome(clientAuth.beginAuth(client), 15000)
            check(authOutcome is AuthOutcome.Authenticated) { "auth failed: $authOutcome" }
            sessions.authorize(clientDevice.deviceId, UUID.randomUUID().toString())
            val transferId = UUID.randomUUID().toString()
            val metadata = TransferMetadata(
                transferId, clientDevice.deviceId, serverDevice.deviceId,
                UUID.randomUUID().toString(), "setup.bin", 100L,
                com.localdrop.core.AppConfig.DEFAULT_CHECKPOINT_BYTES, null, 0L
            )
            assertTrue(client.send(LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.newMessageId(),
                null, null, transferId, TypedPayload.TransferRequest(TransferRequestPayload(metadata)))))
            assertNotNull(clientMailbox.awaitResponse(transferId, setOf(MessageType.TRANSFER_ACCEPT), 10000))
            java.net.Socket("127.0.0.1", engine.localPort()).use { socket ->
                val out = socket.getOutputStream()
                out.write((transferId + "\n").toByteArray(Charsets.US_ASCII))
                out.flush()
                Thread.sleep(300)
            }
            val outcome = engine.waitForOutcome(transferId, 10000)
            val failed = outcome as? TransferOutcome.Failed
                ?: throw AssertionError("expected Failed, got $outcome")
            assertEquals(ErrorCode.INTERNAL_ERROR, failed.code)
            // Slot released: engine idle immediately (no 30 s prune wait).
            assertFalse(engine.isBusy())
            assertTrue(destDir.listFiles()?.isEmpty() != false)
        } finally {
            try { client.disconnect() } catch (e: Exception) { }
            try { engine.stop() } catch (e: Exception) { }
            try { server.stop() } catch (e: Exception) { }
            try { destDir.deleteRecursively() } catch (e: Exception) { }
        }
    }
}
