package com.localdrop.transfer

import com.localdrop.core.AppConfig

// M06 adaptive logical block sizer (ADRs 3-8). Deterministic, bounded,
// oscillation-resistant probe controller:
//
// - Growth (×2, ≤ MAX) probes for headroom when the current size performs
//   steadily (N consecutive non-degraded windows) past cooldown, and only
//   while probing is armed.
// - Shrink (÷2, ≥ MIN) on N consecutive stalled/slow windows past cooldown,
//   and disarms further growth: re-growth past a stall point requires
//   genuinely improved conditions (a window ≥ growthRatio × baseline).
// - This probe-with-backoff prevents the grow/shrink/grow limit cycle on
//   marginal links while still exploiting real headroom.
//
// Pure logic (injected nanosecond clock) — fully unit-testable without I/O.
// One instance per transfer; not thread-safe by design (the engine calls
// observe() from its single transfer thread).
class AdaptiveBlockSizer(
    fileSizeBytes: Long,
    private val minBytes: Int = AppConfig.TRANSFER_BLOCK_MIN_BYTES,
    private val maxBytes: Int = AppConfig.TRANSFER_BLOCK_MAX_BYTES,
    private val initialCapBytes: Int = AppConfig.TRANSFER_BLOCK_INITIAL_BYTES,
    private val stallThresholdMs: Long = AppConfig.ADAPT_STALL_THRESHOLD_MS,
    private val cooldownMs: Long = AppConfig.ADAPT_COOLDOWN_MS,
    private val consecutiveWindows: Int = AppConfig.ADAPT_CONSECUTIVE_WINDOWS,
    private val growthRatio: Double = AppConfig.ADAPT_GROWTH_RATIO,
    private val shrinkRatio: Double = AppConfig.ADAPT_SHRINK_RATIO,
    private val nanoTime: () -> Long = { System.nanoTime() }
) {
    var currentBlockSize: Int = initialFor(fileSizeBytes)
        private set

    // Best non-stalled window throughput observed (bytes/sec). The reference
    // for "degraded" and "improved"; monotone non-decreasing per transfer.
    private var baselineBytesPerSec: Double? = null

    // True until a shrink disarms probing; re-armed only by genuine
    // improvement (>= growthRatio × baseline). Prevents stall-point cycling.
    private var growthArmed = true
    private var goodWindows = 0
    private var badWindows = 0
    private var lastChangeNanos: Long = nanoTime()
    private val _history = mutableListOf<String>()
    val history: List<String> get() = _history.toList()

    companion object {
        // Diagnostics bound: history never grows with file size (ADR 16).
        const val MAX_HISTORY_ENTRIES = 128
    }

    private fun initialFor(fileSizeBytes: Long): Int {
        if (fileSizeBytes <= 0L) return minBytes
        // File-size-aware start: tiny files don't need the full initial cap;
        // huge files start at the cap (growth probes the rest). Integer
        // division is exact here; clamp keeps the invariant MIN..cap.
        val scaled = fileSizeBytes / 8L
        val clamped = scaled.coerceIn(minBytes.toLong(), initialCapBytes.toLong())
        return clamped.toInt()
    }

    // Records one completed block operation. `elapsedNanos` covers the
    // read+write of `blockBytes`. `stalled` must be true when that operation
    // took longer than the stall threshold (measured by the caller).
    // Returns true when the block size changed.
    fun observe(blockBytes: Long, elapsedNanos: Long, stalled: Boolean): Boolean {
        val now = nanoTime()
        val windowBps = if (elapsedNanos > 0) blockBytes.toDouble() * 1e9 / elapsedNanos.toDouble() else 0.0
        val baseline = baselineBytesPerSec
        if (!stalled && (baseline == null || windowBps > baseline)) {
            // New best (or first sample): adopt it; genuine improvement
            // re-arms probing after a shrink.
            if (baseline != null && windowBps >= baseline * growthRatio) growthArmed = true
            baselineBytesPerSec = windowBps
        }
        val reference = baselineBytesPerSec ?: windowBps
        val isGood = !stalled && windowBps >= reference
        val isBad = stalled || windowBps < reference * shrinkRatio
        if (isGood) {
            goodWindows++
            badWindows = 0
        } else if (isBad) {
            badWindows++
            goodWindows = 0
        } else {
            goodWindows = 0
            badWindows = 0
        }
        val cooledDown = (now - lastChangeNanos) / 1_000_000L >= cooldownMs
        if (goodWindows >= consecutiveWindows && cooledDown && growthArmed &&
            currentBlockSize < maxBytes && reference > 0.0
        ) {
            val next = (currentBlockSize.toLong() * 2).coerceAtMost(maxBytes.toLong()).toInt()
            if (next != currentBlockSize) {
                record("grow", windowBps)
                currentBlockSize = next
                baselineBytesPerSec = windowBps
                lastChangeNanos = now
                goodWindows = 0
                badWindows = 0
                return true
            }
        }
        if (badWindows >= consecutiveWindows && cooledDown && currentBlockSize > minBytes) {
            val next = (currentBlockSize / 2).coerceAtLeast(minBytes)
            if (next != currentBlockSize) {
                record("shrink", windowBps)
                currentBlockSize = next
                // Baseline deliberately RETAINED at the pre-shrink best:
                // re-growth past this stall point then requires a window at
                // >= growthRatio × that best (genuine improvement), which is
                // what breaks the stall-point limit cycle.
                growthArmed = false
                lastChangeNanos = now
                goodWindows = 0
                badWindows = 0
                return true
            }
        }
        return false
    }

    private fun record(kind: String, windowBps: Double) {
        _history.add(
            "$kind ${currentBlockSize}->${if (kind == "grow") {
                (currentBlockSize.toLong() * 2).coerceAtMost(maxBytes.toLong())
            } else {
                (currentBlockSize / 2).coerceAtLeast(minBytes)
            }} windowBps=${windowBps.toLong()}"
        )
        while (_history.size > MAX_HISTORY_ENTRIES) _history.removeAt(0)
    }
}
