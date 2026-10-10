package com.localdrop.widget

// M13: canonical widget state.
//
// Widgets display SNAPSHOTS derived from authoritative transfer state (the
// ReceiverEngine/SendCoordinator outcomes), never an independent copy:
// - The service pushes a fresh snapshot ONLY on transitions (transfer
//   started / progress-throttled tick / terminal outcome), not on a polling
//   loop: battery-conscious by construction.
// - Each snapshot carries epochMs + generation. The provider discards any
//   snapshot older than STALE_AFTER_MS or from an older generation, showing
//   idle instead of a stale "active" transfer.
// - Process/service recreation: the new process starts with NO snapshot
//   (generation 0, empty). A recreated widget therefore shows idle — it must
//   NEVER resurrect a pre-kill "transferring" row as current.
data class WidgetTransferEntry(
    val transferId: String,
    val fileName: String,
    val percent: Double,
    val state: String
)

data class TransferWidgetSnapshot(
    val epochMs: Long,
    val generation: Long,
    val transfers: List<WidgetTransferEntry>
) {
    companion object {
        // Refreshes are transition-driven; a snapshot this old is stale.
        const val STALE_AFTER_MS: Long = 60_000L

        fun idle(nowMs: Long, generation: Long = 0L): TransferWidgetSnapshot =
            TransferWidgetSnapshot(nowMs, generation, emptyList())

        fun isStale(snapshot: TransferWidgetSnapshot, nowMs: Long): Boolean =
            (nowMs - snapshot.epochMs) > STALE_AFTER_MS

        // Canonical render decision: null means "show idle". A stale
        // snapshot with transfers is treated as idle (never present stale
        // active transfers as current).
        fun displayable(snapshot: TransferWidgetSnapshot?, nowMs: Long): TransferWidgetSnapshot? {
            if (snapshot == null) return null
            if (snapshot.transfers.isEmpty()) return snapshot
            return if (isStale(snapshot, nowMs)) null else snapshot
        }
    }
}
