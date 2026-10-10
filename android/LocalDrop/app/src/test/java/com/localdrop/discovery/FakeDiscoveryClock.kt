package com.localdrop.discovery

// Deterministic clock for expiry tests: no sleeping.
class FakeDiscoveryClock(var now: Long = 1_000_000L) : DiscoveryClock {
    override fun nowMillis(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}
