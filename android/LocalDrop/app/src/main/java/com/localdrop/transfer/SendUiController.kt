package com.localdrop.transfer

import com.localdrop.protocol.ErrorCode
import com.localdrop.security.AuthUiState

// M07 UI closure: framework-free send-workflow state holder. The Compose
// screens observe [state] and call the methods; all networking runs on a
// single worker thread owned here. No sockets, file I/O, or crypto in this
// class beyond delegating to SendCoordinator/SenderEngine. Thread-safe:
// worker mutates under lock, then invokes [onChange] (Compose writes to
// snapshot state, which is thread-safe).
enum class SendPhase { Idle, Sending, Succeeded, Cancelled, Failed }

data class SendPeerItem(
    val deviceId: String,
    val displayName: String,
    val deviceType: String,
    val dialable: Boolean,
    val status: String
)

data class SendUiState(
    val peers: List<SendPeerItem> = emptyList(),
    val selectedPeerId: String? = null,
    val fileName: String = "",
    val fileSizeBytes: Long = -1L,
    val code: String = "",
    val phase: SendPhase = SendPhase.Idle,
    val progressPercent: Double = 0.0,
    val progressText: String = "",
    val speedText: String = "",
    val etaText: String = "",
    val statusText: String = "Choose a nearby device and a file to send.",
    val resultText: String = "",
    val busy: Boolean = false
) {
    // Mirrors the Windows SendController.CanSend gate: idle, listed peer
    // selected, file chosen, code in M05 6-digit shape. UX affordance only;
    // the engine re-validates everything and authorization still decides.
    val canSend: Boolean
        get() = !busy && fileSizeBytes >= 0L && selectedPeerId != null &&
            peers.any { it.deviceId == selectedPeerId } &&
            AuthUiState.normalizeInput(code).length == 6
}

class SendUiController(
    private val sender: SendCoordinatorFacade,
    private val peerSnapshots: () -> List<com.localdrop.discovery.DiscoveredPeer> =
        { sender.nearbyPeers() }
) {
    // Narrow facade so tests can substitute canned outcomes without sockets.
    interface SendCoordinatorFacade {
        fun nearbyPeers(): List<com.localdrop.discovery.DiscoveredPeer>
        fun sendFile(
            peerDeviceId: String,
            source: TransferSource,
            code: String,
            isCancelled: () -> Boolean,
            onProgress: (TransferProgress) -> Unit
        ): TransferOutcome
    }

    private val gate = Any()
    @Volatile var state: SendUiState = SendUiState()
        private set
    var onChange: () -> Unit = {}
    private var source: TransferSource? = null
    private var cancelled = false
    private var worker: Thread? = null
    private var closed = false
    private var detached = false

    private fun update(next: SendUiState) {
        state = next
        try { onChange() } catch (e: Exception) { /* listener bugs never break transfers */ }
    }

    fun refreshPeers() {
        val snapshot = try { peerSnapshots() } catch (e: Exception) {
            return // Discovery hiccup: keep the last list, retry later.
        }
        synchronized(gate) {
            if (closed) return
            val items = snapshot.map { peer ->
                val dialable = PeerEndpointResolver.resolve(peer) != null
                SendPeerItem(
                    peer.record.deviceId, peer.record.deviceName, peer.record.deviceType,
                    dialable, if (dialable) "Online" else "Waiting for address…")
            }
            var s = state.copy(peers = items)
            if (s.selectedPeerId != null && items.none { it.deviceId == s.selectedPeerId }) {
                s = s.copy(statusText = "The selected device is no longer nearby. Choose another device.")
            }
            update(s)
        }
    }

    fun selectPeer(deviceId: String?) {
        synchronized(gate) {
            if (closed || state.busy) return
            var s = state.copy(selectedPeerId = deviceId)
            if (s.phase != SendPhase.Idle) s = s.copy(phase = SendPhase.Idle)
            if (s.resultText.startsWith("The selected device")) s = s.copy(resultText = "")
            update(s.copy(statusText = defaultStatus(s)))
        }
    }

    fun setFile(next: TransferSource?) {
        synchronized(gate) {
            if (closed || state.busy) return
            source = next
            var s = if (next == null) {
                state.copy(fileName = "", fileSizeBytes = -1L)
            } else {
                state.copy(fileName = next.displayName, fileSizeBytes = next.sizeBytes, resultText = "")
            }
            if (s.phase != SendPhase.Idle) s = s.copy(phase = SendPhase.Idle)
            update(s.copy(statusText = defaultStatus(s)))
        }
    }

    fun setCode(next: String) {
        synchronized(gate) {
            if (closed) return
            update(state.copy(code = next))
        }
    }

    // Shows an informational message without changing the phase (picker
    // failures, hints). Never used for transfer outcomes.
    fun showMessage(message: String) {
        synchronized(gate) {
            if (closed) return
            update(state.copy(resultText = message))
        }
    }

    // Starts one transfer. Returns false (with explanatory result text) when
    // not actionable. Duplicate submissions while busy are ignored: exactly
    // one active transfer per controller (M12 owns more).
    fun startSend(): Boolean {
        val req: Triple<String, TransferSource, String>
        synchronized(gate) {
            if (closed || detached || state.busy) return false
            val src = source
            val peerId = state.selectedPeerId
            if (src == null || peerId == null) {
                update(state.copy(resultText = "Select a device and a file first."))
                return false
            }
            if (AuthUiState.normalizeInput(state.code).length != 6) {
                update(state.copy(resultText = "Enter the 6-digit code shown on the receiver."))
                return false
            }
            if (state.peers.none { it.deviceId == peerId }) {
                update(state.copy(resultText = "The selected device is no longer nearby. Choose another device."))
                return false
            }
            cancelled = false
            req = Triple(peerId, src, state.code)
            update(state.copy(
                busy = true, phase = SendPhase.Sending, resultText = "",
                progressPercent = 0.0, progressText = "", speedText = "", etaText = "",
                statusText = "Connecting…"))
            worker = Thread({
                val outcome = try {
                    sender.sendFile(req.first, req.second, req.third,
                        isCancelled = { synchronized(gate) { cancelled } },
                        onProgress = ::onEngineProgress)
                } catch (e: Exception) {
                    // Coordinators never throw on peer/network failure by
                    // contract; this guards purely against programming errors
                    // so the UI can never hang in Sending.
                    TransferOutcome.Failed(null, ErrorCode.INTERNAL_ERROR, "Unexpected error: ${e.message}")
                }
                applyOutcome(req.first, req.second, outcome)
            }, "localdrop-send-ui").apply { isDaemon = true; start() }
        }
        return true
    }

    fun cancelSend() {
        synchronized(gate) {
            if (!state.busy) return
            cancelled = true
            update(state.copy(statusText = "Cancelling…"))
        }
    }

    private fun onEngineProgress(p: TransferProgress) {
        // Engine worker thread: these snapshots ARE the displayed progress
        // (no simulation, no smoothing inventions).
        synchronized(gate) {
            if (closed) return
            update(state.copy(
                progressPercent = p.percent,
                progressText = "${formatBytes(p.transferredBytes)} of ${formatBytes(p.totalBytes)} (${"%.1f".format(p.percent)}%)",
                speedText = if (p.recentBytesPerSec != null && p.recentBytesPerSec > 0)
                    formatSpeed(p.recentBytesPerSec) else "—",
                etaText = if (p.etaMs != null) "ETA ${formatDuration(p.etaMs)}" else "ETA —",
                statusText = humanState(p.state)))
        }
    }

    private fun applyOutcome(peerId: String, src: TransferSource, outcome: TransferOutcome) {
        synchronized(gate) {
            if (closed) {
                worker = null
                return
            }
            val peerName = state.peers.firstOrNull { it.deviceId == peerId }?.displayName ?: "device"
            val s = when (outcome) {
                is TransferOutcome.TransportComplete -> {
                    val avg = if (outcome.averageBytesPerSec != null && outcome.averageBytesPerSec > 0)
                        " at ${formatSpeed(outcome.averageBytesPerSec)}" else ""
                    state.copy(
                        phase = SendPhase.Succeeded, busy = false, progressPercent = 100.0,
                        // TransportComplete is reported by the engine ONLY after
                        // the receiver confirms SHA-256 verification +
                        // finalization (VERIFY_RESULT match), so this text may
                        // say verified. Anything else is Failed/Cancelled.
                        resultText = "Completed — verified by receiver. " +
                            "${src.displayName} sent to $peerName " +
                            "(${formatBytes(outcome.bytesTransferred)}$avg).",
                        statusText = "Completed — verified.")
                }
                is TransferOutcome.Cancelled -> state.copy(
                    phase = SendPhase.Cancelled, busy = false,
                    resultText = "Cancelled. No partial file was left on the receiver.",
                    statusText = "Cancelled.")
                is TransferOutcome.Failed -> state.copy(
                    phase = SendPhase.Failed, busy = false,
                    resultText = friendlyError(outcome.code, outcome.detail),
                    statusText = "Failed.")
            }
            worker = null
            update(s)
        }
    }

    fun close() {
        val w: Thread?
        synchronized(gate) {
            if (closed) return
            closed = true
            cancelled = true
            w = worker
            worker = null
        }
        try { w?.join(3000) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // M13 bidirectional audit: UI-detach for the service-owned path. Drops
    // the observer and blocks NEW sends from this (discarded) controller,
    // but does NOT cancel an in-flight send and does NOT join the worker:
    // the send is service-owned and runs to its terminal outcome (tracked
    // by OutgoingTransfers + notifications) even though this UI is gone.
    // close() above stays for standalone/test use (cancel + join).
    fun release() {
        synchronized(gate) {
            if (closed || detached) return
            detached = true
            onChange = {}
        }
    }

    private fun defaultStatus(s: SendUiState): String = when {
        s.fileSizeBytes < 0L && s.selectedPeerId == null ->
            "Choose a nearby device and a file to send."
        s.fileSizeBytes < 0L -> "Now choose a file to send."
        s.selectedPeerId == null -> "Now choose a nearby device."
        else -> "File ready. Enter the 6-digit code shown on the receiver, then Send."
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 0) return "—"
            val k = 1024.0
            if (bytes < k) return "$bytes B"
            if (bytes < k * k) return "%.1f KB".format(bytes / k)
            if (bytes < k * k * k) return "%.1f MB".format(bytes / (k * k))
            return "%.2f GB".format(bytes / (k * k * k))
        }

        fun formatSpeed(bytesPerSec: Double): String {
            if (bytesPerSec.isNaN() || bytesPerSec.isInfinite() || bytesPerSec <= 0) return "—"
            return "${formatBytes(bytesPerSec.toLong())}/s"
        }

        fun formatDuration(millis: Long): String {
            if (millis < 0) return "—"
            val totalSeconds = millis / 1000
            if (totalSeconds < 60) return "00:%02d".format(totalSeconds)
            val minutes = totalSeconds / 60
            if (minutes < 60) return "%02d:%02d".format(minutes, totalSeconds % 60)
            return "%dh %02dm".format(minutes / 60, minutes % 60)
        }

        fun humanState(state: TransferEngineState): String = when (state) {
            TransferEngineState.CONNECTING -> "Connecting…"
            TransferEngineState.WAITING_ACCEPTANCE -> "Waiting for the receiver…"
            TransferEngineState.TRANSFERRING -> "Sending…"
            TransferEngineState.TRANSPORT_COMPLETE -> "Finalizing…"
            TransferEngineState.FAILED -> "Failed."
            TransferEngineState.CANCELLED -> "Cancelling…"
        }

        // Actionable, secret-free error text. Engine details are display-safe
        // (frozen M02 error vocabulary carries no keys/codes/tokens).
        fun friendlyError(code: ErrorCode, detail: String): String = when (code) {
            ErrorCode.TRANSFER_REJECTED -> "Receiver rejected the transfer. $detail You can try again."
            ErrorCode.AUTH_FAILED -> "Authentication with the receiver failed. Make sure you selected the right device and try again."
            ErrorCode.PAIRING_REQUIRED -> "Authentication is required before sending. Try again."
            ErrorCode.TIMEOUT -> "Timed out. $detail Check that both devices stay on the hotspot and try again."
            ErrorCode.NETWORK_LOST -> "Connection lost. $detail Stay on the local Wi-Fi and try again. Never switch to the Internet for this app."
            ErrorCode.NOT_ENOUGH_STORAGE -> "The receiver is out of storage. Free space on the receiver and try again."
            ErrorCode.FILE_NOT_FOUND -> "The file is no longer available. $detail"
            ErrorCode.CANCELLED -> "Cancelled."
            ErrorCode.INVALID_PROTOCOL -> "Protocol error. $detail Both devices may need the same app version."
            ErrorCode.CHECKSUM_MISMATCH -> "Integrity check failed. The file must not be treated as received."
            ErrorCode.RESUME_UNAVAILABLE -> "Resume is not available in this version; the transfer stops."
            ErrorCode.INTERNAL_ERROR -> "Transfer failed. $detail"
        }
    }
}
