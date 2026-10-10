package com.localdrop.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// M05 tests 11 (wrong-code path logging), 21: no secret material in logs.
// Runs a full success flow plus a wrong-code attempt, then scans every
// recorded line for the known secrets (code, display form, both SPKI blobs)
// and for any long base64 blob (nonce/signature shape).
class SecurityLogTest {

    @Test
    fun `no secret material appears in security logs`() {
        val receiverStore = SoftwareDeviceIdentityStore()
        val senderStore = SoftwareDeviceIdentityStore()
        val receiverDevice = AuthTestSupport.deviceFor(receiverStore, "PcB")
        val senderDevice = AuthTestSupport.deviceFor(senderStore, "PhoneA")
        val receiverLog = RecordingSecurityLogger()
        val senderLog = RecordingSecurityLogger()
        val receiverAuth = AuthManager(receiverDevice, receiverStore, receiverLog)
        val senderAuth = AuthManager(senderDevice, senderStore, senderLog)
        val txns = TransactionManager(
            com.localdrop.discovery.FakeDiscoveryClock(), 300_000L, receiverLog
        )
        val sessions = AuthorizedSessionStore()
        receiverAuth.inner = TransactionAuthorizer(
            receiverAuth, txns, sessions, receiverLog, receiverDevice.deviceId
        )
        val server = com.localdrop.network.ControlServer(
            receiverDevice, port = 0, listenerFactory = { receiverAuth }
        )
        server.start()
        val secrets = mutableListOf<String>()
        try {
            val client = com.localdrop.network.ControlConnection(senderDevice, senderAuth)
            assertTrue(client.connect("127.0.0.1", server.localPort()))
            try {
                val outcome = AuthTestSupport.awaitOutcome(senderAuth.beginAuth(client))
                assertTrue(outcome is AuthOutcome.Authenticated)
                val txn = txns.createTransaction()
                secrets.add(txn.code)
                secrets.add(AuthUiState.formatCode(txn.code))
                secrets.add(AuthProtocol.b64encode(receiverStore.identity().publicKeySpki))
                secrets.add(AuthProtocol.b64encode(senderStore.identity().publicKeySpki))
                // A wrong attempt first (exercises the reject log path).
                val wrong = txn.code.dropLast(1) + if (txn.code.last() == '0') '1' else '0'
                assertTrue(senderAuth.senderSubmitCode(client, wrong) is PairSubmitOutcome.Rejected)
                assertTrue(senderAuth.senderSubmitCode(client, txn.code) is PairSubmitOutcome.Accepted)
            } finally {
                client.disconnect()
            }
        } finally {
            server.stop()
        }
        val lines = receiverLog.snapshot() + senderLog.snapshot()
        assertFalse("expected security log lines", lines.isEmpty())
        val blobRe = Regex("[A-Za-z0-9+/]{40,}={0,2}")
        for (line in lines) {
            for (secret in secrets) {
                assertFalse("secret material leaked in log: $line", line.contains(secret))
            }
            assertFalse("base64 blob (nonce/sig/key?) in log: $line", blobRe.containsMatchIn(line))
        }
        // Positive control: logs DO carry ids + event words.
        val joined = lines.joinToString("\n")
        assertTrue(joined.contains(receiverDevice.deviceId))
        assertTrue(joined.contains(senderDevice.deviceId))
        assertTrue(joined.contains("rejected") || joined.contains("authorized"))
    }

    @Test
    fun `peer-facing reject reason is generic`() {
        assertEquals("Invalid transfer code.", TransactionAuthorizer.GENERIC_REJECT_REASON)
        assertFalse(TransactionAuthorizer.GENERIC_REJECT_REASON.contains("583921"))
    }
}
