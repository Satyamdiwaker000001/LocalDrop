package com.localdrop.transfer

import com.localdrop.core.AppConfig
import com.localdrop.protocol.ErrorCode
import com.localdrop.security.AuthUiState

// M07 UI closure: framework-free receive-workflow state holder. Owns a
// ReceiveCoordinator (production M04/M05/M07 stack) and surfaces the
// receiver UX: readiness, the single-use transaction code the sender must
// type, incoming-transfer info/progress, and terminal outcomes. Thread-safe:
// worker mutates under lock, then invokes [onChange].
//
// Authorization model (unchanged M05): the receiver user creates a code and
// reads it out; the sender types it. Transfers start only for authorized
// sessions — appearing in discovery never authorizes anything, and this
// controller performs no authorization itself. There is no interactive
// Accept/Reject in the engine: code-gated authorization IS the acceptance.
enum class ReceivePhase { Idle, Receiving, Succeeded, Cancelled, Failed }

data class ReceiveUiState(
    val running: Boolean = false,
    val codeDisplay: String = "",
    val codeNote: String = "",
    val incomingName: String = "",
    val incomingSizeBytes: Long = -1L,
    val incomingPeer: String = "",
    val progress: TransferProgress? = null,
    val phase: ReceivePhase = ReceivePhase.Idle,
    val resultText: String = "",
    val statusText: String = "Start receiving to show a code for the sender."
)

class ReceiveUiController(private val coordinator: ReceiveCoordinator) {
    private val gate = Any()
    @Volatile var state: ReceiveUiState = ReceiveUiState()
        private set
    var onChange: () -> Unit = {}
    private var worker: Thread? = null
    private var closed = false
    private var released = false

    private fun update(next: ReceiveUiState) {
        state = next
        try { onChange() } catch (e: Exception) { /* listener bugs never break transfers */ }
    }

    fun start() {
        synchronized(gate) {
            if (closed || released || state.running) return
            update(state.copy(running = true, statusText = "Receiving is on. Nearby senders can find this device."))
            worker = Thread({
                try {
                    coordinator.start()
                } catch (e: Exception) {
                    synchronized(gate) {
                        if (!closed) update(state.copy(
                            running = false,
                            resultText = "Could not start receiving: ${e.message}",
                            statusText = "Failed."))
                    }
                    return@Thread
                }
                // Outcome feed: terminal outcomes drive the result display.
                // waitForAnyOutcome returns real engine outcomes only.
                while (true) {
                    synchronized(gate) { if (closed || released) return@Thread }
                    val outcome = try {
                        coordinator.waitForAnyOutcome(2000)
                    } catch (e: Exception) {
                        null
                    }
                    synchronized(gate) {
                        if (closed || released) return@Thread
                        refreshIncomingLocked()
                        if (outcome != null) applyOutcomeLocked(outcome)
                    }
                }
            }, "localdrop-receive-ui").apply { isDaemon = true; start() }
        }
    }

    // Opens a fresh single-use transaction and displays its code for the
    // sender user to type. Previous codes stay expired/consumed per M05
    // rules; this never approves anything by itself.
    fun newCode() {
        synchronized(gate) {
            if (closed || released || !state.running) {
                update(state.copy(resultText = "Start receiving first."))
                return
            }
        }
        val txn = try {
            coordinator.transactions.createTransaction()
        } catch (e: Exception) {
            synchronized(gate) {
                update(state.copy(resultText = "Could not create a code: ${e.message}"))
            }
            return
        }
        synchronized(gate) {
            if (closed) return
            val minutes = AppConfig.TRANSACTION_CODE_LIFETIME_MS / 60000
            update(state.copy(
                codeDisplay = AuthUiState.formatCode(txn.code),
                codeNote = "Read this code out to the sender. Single-use, valid about $minutes minutes.",
                resultText = ""))
        }
    }

    // Receiver-initiated abort of the current inbound transfer (UI Cancel).
    // Returns false when nothing is streaming. The sender observes connection
    // loss (fail-closed); no partial file is left behind.
    fun cancelIncoming(): Boolean {
        val id = coordinator.engine.activeTransferId() ?: return false
        coordinator.engine.cancelTransfer(id)
        return true
    }

    fun attachProgress() {
        coordinator.engine.onProgress = { p ->
            synchronized(gate) {
                if (!closed) {
                    var s = state.copy(
                        progress = p,
                        phase = ReceivePhase.Receiving,
                        statusText = SendUiController.humanState(p.state))
                    val info = coordinator.engine.activeTransferInfo()
                    if (info != null) {
                        s = s.copy(
                            incomingName = info.fileName,
                            incomingSizeBytes = info.fileSize,
                            incomingPeer = info.peerDeviceId)
                    }
                    update(s)
                }
            }
        }
    }

    private fun refreshIncomingLocked() {
        val info = try { coordinator.engine.activeTransferInfo() } catch (e: Exception) { null }
        if (info != null) {
            update(state.copy(
                incomingName = info.fileName,
                incomingSizeBytes = info.fileSize,
                incomingPeer = info.peerDeviceId,
                phase = if (state.phase == ReceivePhase.Idle) ReceivePhase.Receiving else state.phase))
        }
    }

    private fun applyOutcomeLocked(outcome: TransferOutcome) {
        val s = when (outcome) {
            is TransferOutcome.TransportComplete -> state.copy(
                phase = ReceivePhase.Succeeded,
                progress = null,
                resultText = "Completed — ${formatIncoming()} (${SendUiController.formatBytes(outcome.bytesTransferred)}).",
                statusText = "Completed.")
            is TransferOutcome.Cancelled -> state.copy(
                phase = ReceivePhase.Cancelled,
                progress = null,
                resultText = "Cancelled. No partial file was kept.",
                statusText = "Cancelled.")
            is TransferOutcome.Failed -> state.copy(
                phase = ReceivePhase.Failed,
                progress = null,
                resultText = SendUiController.friendlyError(outcome.code, outcome.detail),
                statusText = "Failed.")
        }
        update(s)
    }

    private fun formatIncoming(): String {
        val name = state.incomingName.ifEmpty { "file" }
        return if (state.incomingSizeBytes >= 0) "$name (${SendUiController.formatBytes(state.incomingSizeBytes)})"
        else name
    }

    fun close() {
        val w: Thread?
        synchronized(gate) {
            if (closed) return
            closed = true
            coordinator.engine.onProgress = null
            w = worker
            worker = null
        }
        // Stopping the coordinator aborts any active inbound transfer
        // cooperatively (temp cleaned); the outcome feed reports it.
        // Standalone/test teardown only — the service-owned UI path must
        // call release(), never this (the service owns the coordinator).
        try { coordinator.stop() } catch (e: Exception) { }
        try { w?.join(5000) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // M13 bidirectional audit: UI-detach for the service-owned path. Drops
    // the observer, detaches engine progress, and exits the outcome-feed
    // worker WITHOUT stopping the coordinator and WITHOUT aborting live
    // inbound transfers: receiving continues service-side after this UI is
    // gone. New actions (start/newCode) are blocked after release.
    fun release() {
        val w: Thread?
        synchronized(gate) {
            if (closed || released) return
            released = true
            onChange = {}
            try { coordinator.engine.onProgress = null } catch (e: Exception) { }
            w = worker
            worker = null
        }
        try { w?.interrupt() } catch (e: Exception) { }
        try { w?.join(5000) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        // ErrorCode coverage shared with the send side (same 12-code
        // vocabulary); receiver-specific wording lives here if it diverges.
        fun friendlyError(code: ErrorCode, detail: String): String =
            SendUiController.friendlyError(code, detail)
    }
}
