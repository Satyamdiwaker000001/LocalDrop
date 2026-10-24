package com.localdrop.widget

// Canonical widget state (M13 + audit Finding 2).
//
// Widgets display SNAPSHOTS derived from authoritative transfer/discovery
// state (ReceiverEngine outcomes, tracked OutgoingTransfers, LanDiscovery
// snapshots), never an independent copy, never fake progress:
// - The service persists a fresh snapshot ONLY on bounded transitions
//   (transfer start / throttled progress tick / terminal outcome / peer
//   refresh while UI is alive), not on a polling loop: battery-conscious
//   by construction.
// - Each snapshot carries epochMs + generation. Providers discard any
//   snapshot older than STALE_AFTER_MS with live content, showing idle /
//   unavailable instead of stale "active" rows.
// - Process/service recreation: the new process starts with NO snapshot.
//   A recreated widget therefore shows idle — it must NEVER resurrect a
//   pre-kill "transferring" row or peer list as current.
// - No secrets ever: transferId/fileName/percent/state and public peer
//   identity only. Transaction codes and file contents are never stored.
data class WidgetTransferEntry(
    val transferId: String,
    val fileName: String,
    val percent: Double,
    val state: String,
    // Direction of the transfer: "in" (inbound) or "out" (outbound).
    // "" means unknown (snapshots persisted before directions existed).
    val direction: String = "",
    val transferredBytes: Long = -1L,
    val totalBytes: Long = -1L,
    // Human short speed ("12.0 MB/s", "—"), measured only, never faked.
    val speedText: String = ""
)

// One discovered peer row (public LAN identity only — the same record the
// app UI lists; never an independent discovery engine).
data class WidgetPeer(
    val deviceId: String,
    val displayName: String,
    val deviceType: String,
    val status: String
)

data class TransferWidgetSnapshot(
    val epochMs: Long,
    val generation: Long,
    val transfers: List<WidgetTransferEntry>,
    val peers: List<WidgetPeer> = emptyList(),
    // Last terminal result line ("Completed — verified by receiver …") so
    // the status widget shows an accurate recent state when idle.
    val recent: String = ""
) {
    companion object {
        // Refreshes are transition-driven; a snapshot this old is stale.
        const val STALE_AFTER_MS: Long = 60_000L

        fun idle(nowMs: Long, generation: Long = 0L): TransferWidgetSnapshot =
            TransferWidgetSnapshot(nowMs, generation, emptyList())

        fun isStale(snapshot: TransferWidgetSnapshot, nowMs: Long): Boolean =
            (nowMs - snapshot.epochMs) > STALE_AFTER_MS

        // Canonical render decision: null means "show idle/unavailable". A
        // stale snapshot WITH live content (transfers or peers) is never
        // presented as current. Empty snapshots stay renderable (idle).
        fun displayable(snapshot: TransferWidgetSnapshot?, nowMs: Long): TransferWidgetSnapshot? {
            if (snapshot == null) return null
            if (snapshot.transfers.isEmpty() && snapshot.peers.isEmpty()) return snapshot
            return if (isStale(snapshot, nowMs)) null else snapshot
        }
    }
}

// Pure presentation + routing decisions shared by all three widgets
// (fully JVM-testable; PendingIntent construction itself needs a device).
object WidgetContent {
    // Which app tab a transfer row deep-links to ("send" for outbound,
    // "receive" for inbound; unknown direction opens receive as the safe
    // default — it never starts anything by itself).
    fun routeFor(entry: WidgetTransferEntry): String =
        if (entry.direction == "out") "send" else "receive"

    // One-line status row: real file name, percent, transferred/total,
    // measured speed, authoritative state. Placeholders ("—") only where
    // nothing was measured — never invented numbers.
    fun statusLine(entry: WidgetTransferEntry): String {
        val size = if (entry.transferredBytes >= 0 && entry.totalBytes >= 0)
            "${formatBytes(entry.transferredBytes)} of ${formatBytes(entry.totalBytes)}"
        else "—"
        val speed = entry.speedText.ifEmpty { "—" }
        return "${entry.fileName} — ${"%.1f".format(entry.percent)}% ($size, $speed) [${entry.state}]"
    }

    fun peerLine(peer: WidgetPeer): String =
        "${peer.displayName} · ${peer.deviceType} · ${peer.status}"

    // Quick-send subtitle from the authoritative peer count: never fakes
    // availability (0 peers says so plainly; the app shows the state).
    fun quickSendSubtitle(peerCount: Int): String =
        if (peerCount > 0) "$peerCount nearby device${if (peerCount == 1) "" else "s"} — tap to send"
        else "No nearby devices — tap to open"

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val k = 1024.0
        if (bytes < k) return "$bytes B"
        if (bytes < k * k) return "%.1f KB".format(bytes / k)
        if (bytes < k * k * k) return "%.1f MB".format(bytes / (k * k))
        return "%.2f GB".format(bytes / (k * k * k))
    }
}
