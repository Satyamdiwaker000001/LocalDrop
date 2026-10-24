package com.localdrop.transfer

import com.localdrop.security.AuthManager
import com.localdrop.security.AuthOutcome
import com.localdrop.security.AuthTestSupport
import com.localdrop.security.AuthorizedSessionStore
import com.localdrop.security.RecordingSecurityLogger
import com.localdrop.security.SoftwareDeviceIdentityStore
import com.localdrop.security.TransactionAuthorizer
import com.localdrop.security.TransactionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

// M15 regression test for the receiver-side verification-answer race.
//
// DEFECT (found by the M15 benchmark, both platforms):
//   The receiver answers a late VERIFY_REQUEST from a background task that
//   waited for the TERMINAL OUTCOME entry of the transfer. That entry is
//   owned by whoever consumes the outcome: ReceiverEngine.waitForAnyOutcome
//   (any-order consumer) DELETES it as soon as it polls the outcome out of
//   the finished queue. When the VERIFY_REQUEST arrived while the receiver
//   was still hashing, the answer therefore depended on an unrelated
//   consumer's polling interval: the consumer deleted the entry first, the
//   background task never saw it, and the sender waited the full 60 s verify
//   timeout and reported a FALSE FAILURE for a transfer whose bytes had
//   been received and verified byte-exactly.
//
// FIX: the answer waits for the VERIFICATION RECORD (written on every
//   verification path, never removed by an outcome consumer) instead of the
//   consumer-owned terminal-outcome entry.
//
// This test drives exactly the racing pattern: the receiver's outcome is
// consumed by a polling any-outcome waiter WHILE the sender is still in its
// verification wait.
class M15VerifyRaceRegressionTest {

    /** 64 MiB: large enough that the receiver's SHA-256 re-read pass is still running when the sender's VERIFY_REQUEST arrives. */
    private val payloadBytes = 64L * 1024 * 1024

    @Test
    fun `verifier answer survives a concurrent outcome consumer`() {
        val work = File(System.getProperty("java.io.tmpdir"), "localdrop-m15-verify-" + UUID.randomUUID())
        val destDir = File(work, "dest")
        val srcDir = File(work, "src")
        destDir.mkdirs()
        srcDir.mkdirs()
        val srcPath = File(srcDir, "payload.bin")
        try {
            val block = ByteArray(1 shl 20)
            FileOutputStream(srcPath).use { out ->
                var written = 0L
                while (written < payloadBytes) {
                    for (i in block.indices) block[i] = ((written + i) * 31 + 7).toByte()
                    out.write(block)
                    written += block.size
                }
                out.flush()
            }

            val serverStore = SoftwareDeviceIdentityStore()
            val clientStore = SoftwareDeviceIdentityStore()
            val serverDevice = AuthTestSupport.deviceFor(serverStore, "PhoneB")
            val clientDevice = AuthTestSupport.deviceFor(clientStore, "PcA")
            val log = RecordingSecurityLogger()
            val serverAuth = AuthManager(serverDevice, serverStore, log)
            val clientAuth = AuthManager(clientDevice, clientStore, log)
            val txns = TransactionManager(logger = log)
            val sessions = AuthorizedSessionStore()
            val receiver = ReceiverEngine(serverDevice, serverAuth, sessions, destDir, logger = log, dataPort = 0)
            val handler = receiver.createHandler()
            serverAuth.inner = TransactionAuthorizer(serverAuth, txns, sessions, log, serverDevice.deviceId, handler)
            val server = com.localdrop.network.ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
            server.start()
            receiver.start()
            val clientMailbox = TransferMailbox()
            clientAuth.inner = clientMailbox
            val client = com.localdrop.network.ControlConnection(clientDevice, clientAuth)
            try {
                assertTrue(client.connect("127.0.0.1", server.localPort()))
                val authOutcome = AuthTestSupport.awaitOutcome(clientAuth.beginAuth(client), 15000)
                assertTrue(authOutcome is AuthOutcome.Authenticated)
                sessions.authorize(clientDevice.deviceId, UUID.randomUUID().toString())
                val sender = SenderEngine(
                    localDeviceId = clientDevice.deviceId,
                    control = client,
                    mailbox = clientMailbox,
                    dataPort = receiver.localPort()
                )

                // The racing pattern: an any-outcome consumer polls the
                // receiver's outcome WHILE the sender waits for its verdict.
                val receiverThread = Thread({
                    try { receiver.waitForAnyOutcome(120_000) } catch (e: Exception) { }
                }, "m15-outcome-consumer")
                receiverThread.isDaemon = true
                receiverThread.start()

                val outcomeRef = AtomicReference<TransferOutcome?>()
                val senderThread = Thread({
                    outcomeRef.set(
                        sender.send(
                            SenderEngine.SendRequest(
                                source = FileTransferSource(srcPath),
                                receiverDeviceId = serverDevice.deviceId,
                                dataHost = "127.0.0.1"
                            )
                        )
                    )
                }, "m15-sender")
                senderThread.isDaemon = true
                senderThread.start()
                senderThread.join(60_000)

                assertTrue("sender did not finish within 60 s", !senderThread.isAlive)
                val sent = outcomeRef.get() as? TransferOutcome.TransportComplete
                    ?: throw AssertionError("sender failed: ${outcomeRef.get()}")
                assertEquals(payloadBytes, sent.bytesTransferred)
                assertTrue(sent.metrics.finalBlockSizeBytes in 65536..1048576)
            } finally {
                try { client.disconnect() } catch (e: Exception) { }
                try { receiver.stop() } catch (e: Exception) { }
                try { server.stop() } catch (e: Exception) { }
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
