package com.localdrop.service

// M13: collision-safe notification identity.
//
// NEVER use `transferId.hashCode()` as a notification ID: distinct strings
// share hash codes (e.g. "Aa".hashCode() == "BB".hashCode()), so two live
// transfers could share/cancel each other's notifications. This registry
// hands out a distinct monotonically-allocated integer per transferId and
// routes cancellation to the exact integer that was issued for that ID.
class NotificationIds(
    private val baseId: Int = FIRST_DYNAMIC_ID,
    private val maxIds: Int = MAX_IDS
) {
    companion object {
        // 1 is the persistent foreground-service summary notification.
        const val FOREGROUND_SUMMARY_ID = 1
        const val FIRST_DYNAMIC_ID = 1000
        const val MAX_IDS = 1024
    }

    private val gate = Any()
    private val assigned = LinkedHashMap<String, Int>()
    private var next = baseId

    // Returns the stable notification ID for this transfer, allocating a new
    // one on first use. Same ID is returned for repeated calls with the same
    // transferId (no duplicate notifications).
    fun idFor(transferId: String): Int = synchronized(gate) {
        assigned[transferId]?.let { return it }
        if (assigned.size >= maxIds) {
            // Evict the oldest mapping; its notification must already have
            // been cancelled by the caller (terminal transfers release).
            val oldest = assigned.keys.first()
            assigned.remove(oldest)
        }
        val id = next++
        assigned[transferId] = id
        return id
    }

    // Exact per-transfer routing: returns the ID to cancel, or null when this
    // transfer never held a notification (nothing to cancel; must NOT cancel
    // another transfer's ID). Removes the mapping so IDs are not reused
    // while a notification could still be visible — release is called AFTER
    // the caller cancels the returned ID.
    fun release(transferId: String): Int? = synchronized(gate) {
        return assigned.remove(transferId)
    }

    fun peek(transferId: String): Int? = synchronized(gate) { assigned[transferId] }

    fun size(): Int = synchronized(gate) { assigned.size }
}
