package com.localdrop.security

import com.localdrop.discovery.FakeDiscoveryClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// M05 tests 5-7, 9-14, 19 (+UI model checks): transaction lifecycle with a
// fake clock. No sockets here; channel binding is covered in flow tests.
class TransactionManagerTest {

    private fun managerAt(clock: FakeDiscoveryClock, lifetimeMs: Long = 300_000L) =
        TransactionManager(clock, lifetimeMs, RecordingSecurityLogger())

    @Test
    fun `created code is six digits`() {
        val txns = managerAt(FakeDiscoveryClock())
        repeat(20) {
            val txn = txns.createTransaction()
            assertTrue("code must be 6 digits: ${txn.code}", Regex("^\\d{6}$").matches(txn.code))
            assertEquals(CodeState.ACTIVE, txns.get(txn.id)?.state)
        }
    }

    @Test
    fun `codes are unique across samples`() {
        val txns = managerAt(FakeDiscoveryClock())
        val codes = (1..200).map { txns.createTransaction().code }.toSet()
        assertEquals(200, codes.size)
    }

    @Test
    fun `correct code authorizes and consumes`() {
        val txns = managerAt(FakeDiscoveryClock())
        val txn = txns.createTransaction()
        val outcome = txns.verifyByCode(txn.code, "peer-1")
        assertTrue(outcome is VerifyOutcome.Authorized)
        assertEquals(txn.id, (outcome as VerifyOutcome.Authorized).transactionId)
        assertEquals(CodeState.CONSUMED, txns.get(txn.id)?.state)
        assertEquals("peer-1", txns.get(txn.id)?.authorizedPeerDeviceId)
    }

    @Test
    fun `wrong code rejected without consuming the correct code`() {
        val txns = managerAt(FakeDiscoveryClock())
        val txn = txns.createTransaction()
        val wrong = if (txn.code != "000000") "000000" else "111111"
        val rejected = txns.verifyByCode(wrong, "peer-1")
        assertTrue(rejected is VerifyOutcome.Rejected)
        assertEquals(CodeReject.UNKNOWN_CODE, (rejected as VerifyOutcome.Rejected).reason)
        // Correct code still works afterwards.
        assertTrue(txns.verifyByCode(txn.code, "peer-1") is VerifyOutcome.Authorized)
    }

    @Test
    fun `expired code rejected and never refreshed`() {
        val clock = FakeDiscoveryClock()
        val txns = managerAt(clock)
        val txn = txns.createTransaction()
        clock.advance(300_001L) // past the 5-minute lifetime
        val outcome = txns.verifyByCode(txn.code, "peer-1")
        assertTrue(outcome is VerifyOutcome.Rejected)
        assertEquals(CodeReject.EXPIRED, (outcome as VerifyOutcome.Rejected).reason)
        assertEquals(CodeState.EXPIRED, txns.get(txn.id)?.state)
        clock.advance(60_000L)
        assertTrue(txns.verifyByCode(txn.code, "peer-1") is VerifyOutcome.Rejected)
    }

    @Test
    fun `consumed code rejects replays`() {
        val txns = managerAt(FakeDiscoveryClock())
        val txn = txns.createTransaction()
        assertTrue(txns.verifyByCode(txn.code, "peer-1") is VerifyOutcome.Authorized)
        val replay = txns.verifyByCode(txn.code, "peer-1")
        assertTrue(replay is VerifyOutcome.Rejected)
        assertEquals(CodeReject.CONSUMED, (replay as VerifyOutcome.Rejected).reason)
    }

    @Test
    fun `cancelled transaction rejects`() {
        val txns = managerAt(FakeDiscoveryClock())
        val txn = txns.createTransaction()
        assertTrue(txns.cancel(txn.id))
        assertEquals(CodeState.CANCELLED, txns.get(txn.id)?.state)
        val outcome = txns.verifyByCode(txn.code, "peer-1")
        assertTrue(outcome is VerifyOutcome.Rejected)
        assertEquals(CodeReject.CANCELLED, (outcome as VerifyOutcome.Rejected).reason)
        assertFalse(txns.cancel("no-such-id"))
    }

    @Test
    fun `simultaneous transactions stay isolated`() {
        val txns = managerAt(FakeDiscoveryClock())
        val a = txns.createTransaction()
        val b = txns.createTransaction()
        // Code A authorizes only transaction A.
        val outcomeA = txns.verifyByCode(a.code, "peer-1")
        assertTrue(outcomeA is VerifyOutcome.Authorized)
        assertEquals(a.id, (outcomeA as VerifyOutcome.Authorized).transactionId)
        // Code B still authorizes transaction B afterwards.
        val outcomeB = txns.verifyByCode(b.code, "peer-1")
        assertTrue(outcomeB is VerifyOutcome.Authorized)
        assertEquals(b.id, (outcomeB as VerifyOutcome.Authorized).transactionId)
    }

    @Test
    fun `spaced input normalizes to digits`() {
        val txns = managerAt(FakeDiscoveryClock())
        val txn = txns.createTransaction()
        val spaced = "${txn.code.substring(0, 3)} ${txn.code.substring(3)}"
        assertTrue(txns.verifyByCode(spaced, "peer-1") is VerifyOutcome.Authorized)
    }

    @Test
    fun `receiver UI snapshots follow the lifecycle`() {
        val txns = managerAt(FakeDiscoveryClock())
        val seen = mutableListOf<ReceiverTransactionUiState>()
        txns.receiverObserver = { seen.add(it) }
        val txn = txns.createTransaction()
        val lastFlipped = txn.code.dropLast(1) + if (txn.code.last() == '0') '1' else '0'
        txns.verifyByCode(lastFlipped, "peer-1")
        txns.verifyByCode(txn.code, "peer-1")
        val phases = seen.map { it.phase }
        assertTrue(phases.contains(ReceiverPhase.CODE_ACTIVE))
        assertTrue(phases.contains(ReceiverPhase.INVALID))
        assertTrue(phases.contains(ReceiverPhase.AUTHORIZED))
        val authorized = seen.last { it.phase == ReceiverPhase.AUTHORIZED }
        assertEquals("peer-1", authorized.authorizedPeerDeviceId)
        assertEquals(AuthUiState.formatCode(txn.code), authorized.codeDisplay)
        assertEquals("583 921", AuthUiState.formatCode("583921"))
        assertEquals("583921", AuthUiState.normalizeInput("583-921"))
    }
}
