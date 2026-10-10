package com.localdrop.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// M13 §4: canonical widget state — snapshots derived from authoritative
// state; stale snapshots and post-recreation emptiness never render as
// active transfers.
class TransferWidgetStateTest {

    private fun active(now: Long, gen: Long = 7L) = TransferWidgetSnapshot(
        now, gen,
        listOf(WidgetTransferEntry("t-1", "photo.jpg", 42.0, "transferring")))

    @Test
    fun `fresh snapshot is displayable`() {
        val now = 1_000_000L
        assertNotNull(TransferWidgetSnapshot.displayable(active(now), now))
    }

    @Test
    fun `stale active snapshot renders as idle never stale-active`() {
        val old = active(1_000_000L)
        val now = 1_000_000L + TransferWidgetSnapshot.STALE_AFTER_MS + 1
        assertNull(TransferWidgetSnapshot.displayable(old, now))
    }

    @Test
    fun `empty snapshot is idle not stale`() {
        val now = 5_000L
        val idle = TransferWidgetSnapshot.idle(now)
        // Even far in the future, an empty snapshot stays a valid idle row.
        assertNotNull(
            TransferWidgetSnapshot.displayable(idle, now + 10 * TransferWidgetSnapshot.STALE_AFTER_MS))
    }

    @Test
    fun `no snapshot after recreation shows idle`() {
        // Fresh process has pushed nothing: provider input is null.
        assertNull(TransferWidgetSnapshot.displayable(null, 9_999_999L))
    }

    @Test
    fun `widget encoding round trips and rejects hostile names`() {
        val snap = active(1234L)
        // Encode via the provider's flat format through reflection-free path:
        // build the raw string the same way LastSnapshot.put does.
        val sb = StringBuilder()
        sb.append(snap.epochMs).append(';').append(snap.generation).append(';')
        snap.transfers.joinTo(sb, "|") {
            "${it.transferId},${it.fileName},${it.percent},${it.state}"
        }
        val parsed = TransferWidgetProvider.LastSnapshot.parse(sb.toString())
        assertNotNull(parsed)
        assertEquals(snap, parsed)
        // A fileName smuggling a separator must not parse into a forged row.
        assertNull(TransferWidgetProvider.LastSnapshot.parse("1;2;t-1,a,b.jpg,1.0,transferring"))
        assertNull(TransferWidgetProvider.LastSnapshot.parse("garbage"))
    }
}
