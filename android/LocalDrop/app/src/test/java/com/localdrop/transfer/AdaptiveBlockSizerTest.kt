package com.localdrop.transfer

import com.localdrop.core.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// M06 §24C + ADR 3-8: deterministic sizer behavior under scripted observations
// with a fake nanosecond clock. No I/O, no timing flakes.
class AdaptiveBlockSizerTest {

    private class Clock(var nowNanos: Long = 0L) {
        fun advanceMs(ms: Long) {
            nowNanos += ms * 1_000_000L
        }
    }

    private fun sizer(fileSize: Long, clock: Clock): AdaptiveBlockSizer =
        AdaptiveBlockSizer(fileSizeBytes = fileSize, nanoTime = { clock.nowNanos })

    // Fast window: 256 KiB in 1 ms (~256 MB/s). Slow window: stalled flag.
    private fun fast(s: AdaptiveBlockSizer, bytes: Long = 262144L): Boolean =
        s.observe(bytes, 1_000_000L, stalled = false)

    private fun stalled(s: AdaptiveBlockSizer, bytes: Long = 65536L): Boolean =
        s.observe(bytes, 600_000_000L, stalled = true)

    @Test
    fun `initial size is file-size aware and bounded`() {
        val clock = Clock()
        assertEquals(65536, sizer(0L, clock).currentBlockSize)
        assertEquals(65536, sizer(100L, clock).currentBlockSize)
        assertEquals(65536, sizer(8L * 65536L, clock).currentBlockSize) // 512 KiB / 8
        assertEquals(262144, sizer(8L * 1024 * 1024, clock).currentBlockSize) // capped
        assertEquals(262144, sizer(50L * 1024 * 1024 * 1024, clock).currentBlockSize)
        assertTrue(sizer(1024L, clock).currentBlockSize in 65536..1048576)
    }

    @Test
    fun `growth needs three fast windows past cooldown`() {
        val clock = Clock()
        val s = sizer(100L * 1024 * 1024, clock)
        assertEquals(262144, s.currentBlockSize)
        // Two fast windows: no change yet.
        assertFalse(fast(s))
        assertFalse(fast(s))
        clock.advanceMs(2500L)
        // Third fast window past cooldown: exactly one doubling.
        assertTrue(fast(s))
        assertEquals(524288, s.currentBlockSize)
        assertEquals(1, s.history.size)
        assertTrue(s.history[0].startsWith("grow 262144->524288"))
    }

    @Test
    fun `growth waits for cooldown`() {
        val clock = Clock()
        val s = sizer(100L * 1024 * 1024, clock)
        // Three fast windows but no cooldown elapsed: hold.
        assertFalse(fast(s))
        assertFalse(fast(s))
        assertFalse(fast(s))
        assertEquals(262144, s.currentBlockSize)
        assertTrue(s.history.isEmpty())
        clock.advanceMs(2500L)
        assertTrue(fast(s))
        assertEquals(524288, s.currentBlockSize)
    }

    @Test
    fun `growth saturates at maximum`() {
        val clock = Clock()
        val s = sizer(100L * 1024 * 1024, clock)
        repeat(10) {
            clock.advanceMs(2500L)
            fast(s)
            fast(s)
            fast(s)
        }
        assertEquals(AppConfig.TRANSFER_BLOCK_MAX_BYTES, s.currentBlockSize)
        // Further fast windows change nothing.
        clock.advanceMs(2500L)
        assertFalse(fast(s))
        assertFalse(fast(s))
        assertFalse(fast(s))
    }

    @Test
    fun `shrink needs three stalled windows past cooldown`() {
        val clock = Clock()
        val s = sizer(100L * 1024 * 1024, clock)
        clock.advanceMs(2500L)
        assertFalse(stalled(s))
        assertFalse(stalled(s))
        assertTrue(stalled(s))
        assertEquals(131072, s.currentBlockSize)
        assertTrue(s.history[0].startsWith("shrink 262144->131072"))
    }

    @Test
    fun `shrink floors at minimum`() {
        val clock = Clock()
        val s = sizer(100L, clock) // starts at MIN
        assertEquals(65536, s.currentBlockSize)
        clock.advanceMs(2500L)
        assertFalse(stalled(s))
        assertFalse(stalled(s))
        assertFalse(stalled(s)) // would halve below MIN: held
        assertEquals(65536, s.currentBlockSize)
        assertTrue(s.history.isEmpty())
    }

    @Test
    fun `alternating conditions never oscillate`() {
        val clock = Clock()
        val s = sizer(100L * 1024 * 1024, clock)
        repeat(20) {
            clock.advanceMs(1000L)
            if (it % 2 == 0) fast(s) else stalled(s)
        }
        assertEquals(262144, s.currentBlockSize)
        assertTrue(s.history.isEmpty())
    }

    @Test
    fun `slow but unstalled windows below half baseline shrink`() {
        val clock = Clock()
        val s = sizer(100L * 1024 * 1024, clock)
        // Seed baseline with a fast window, then three windows at < 0.5x.
        fast(s) // seeds baseline ~256 MB/s
        clock.advanceMs(2500L)
        repeat(3) { s.observe(262144L, 10_000_000L, stalled = false) } // ~26 MB/s
        assertEquals(131072, s.currentBlockSize)
    }

    @Test
    fun `shrink disarms regrowth until genuine improvement`() {
        val clock = Clock()
        val s = sizer(100L * 1024 * 1024, clock)
        // Grow once: 262144 -> 524288.
        clock.advanceMs(2500L)
        assertFalse(fast(s))
        assertFalse(fast(s))
        assertTrue(fast(s))
        assertEquals(524288, s.currentBlockSize)
        // Stall down: 524288 -> 262144, probing disarmed.
        clock.advanceMs(2500L)
        assertFalse(stalled(s))
        assertFalse(stalled(s))
        assertTrue(stalled(s))
        assertEquals(262144, s.currentBlockSize)
        // Steady good windows at the old level: NO regrowth (would cycle).
        clock.advanceMs(2500L)
        repeat(6) { assertFalse(fast(s)) }
        assertEquals(262144, s.currentBlockSize)
        // Genuinely faster link (>= 1.2x best): re-arms, then regrows.
        // A neutral window first resets the carried-over good counter so the
        // three-window rule is observed exactly.
        assertFalse(s.observe(262144L, 1_333_333L, stalled = false)) // ~0.75x: neutral
        clock.advanceMs(2500L)
        assertFalse(s.observe(262144L, 500_000L, stalled = false)) // 2x best: re-arm
        assertFalse(s.observe(262144L, 500_000L, stalled = false))
        assertTrue(s.observe(262144L, 500_000L, stalled = false))
        assertEquals(524288, s.currentBlockSize)
    }

    @Test
    fun `good windows reset bad counter and vice versa`() {        val clock = Clock()
        val s = sizer(100L * 1024 * 1024, clock)
        clock.advanceMs(2500L)
        assertFalse(stalled(s))
        assertFalse(stalled(s))
        assertFalse(fast(s)) // resets stall count
        assertFalse(stalled(s))
        assertFalse(stalled(s)) // only 2 consecutive: hold
        assertEquals(262144, s.currentBlockSize)
    }
}
