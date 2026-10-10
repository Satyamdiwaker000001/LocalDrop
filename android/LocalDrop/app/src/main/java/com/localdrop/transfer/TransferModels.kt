package com.localdrop.transfer

import com.localdrop.protocol.ErrorCode
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.TypedPayload

// M06 transfer models. State wires reuse the exact M01 TRANSFER_STATES
// strings (UI/UX Spec §13); "completed" means TRANSPORT-complete only —
// verification (and any verified state) belongs to M11. All sizes/offsets
// are Long (ADR 12); no Int appears in any size path.

// Existing protocol vocabulary, reused verbatim.
enum class TransferEngineState(val wire: String) {
    CONNECTING("connecting"),
    WAITING_ACCEPTANCE("waiting acceptance"),
    TRANSFERRING("transferring"),
    TRANSPORT_COMPLETE("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun wires(): List<String> = entries.map { it.wire }
    }
}

data class TransferProgress(
    val transferId: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val percent: Double,
    val elapsedMs: Long,
    val averageBytesPerSec: Double?,
    val recentBytesPerSec: Double?,
    val etaMs: Long?,
    val state: TransferEngineState
) {
    companion object {
        fun compute(
            transferId: String,
            totalBytes: Long,
            transferredBytes: Long,
            elapsedMs: Long,
            recentBytesPerSec: Double?,
            state: TransferEngineState
        ): TransferProgress {
            val percent = if (totalBytes <= 0L) 100.0
            else (transferredBytes.toDouble() * 100.0 / totalBytes.toDouble()).coerceIn(0.0, 100.0)
            val average = if (elapsedMs > 0) transferredBytes.toDouble() * 1000.0 / elapsedMs.toDouble() else null
            val eta = if (recentBytesPerSec != null && recentBytesPerSec > 0.0 && totalBytes > transferredBytes) {
                ((totalBytes - transferredBytes).toDouble() * 1000.0 / recentBytesPerSec).toLong()
            } else null
            return TransferProgress(
                transferId, totalBytes, transferredBytes.coerceAtMost(totalBytes),
                percent, elapsedMs, average, recentBytesPerSec, eta, state
            )
        }
    }
}

sealed interface TransferOutcome {
    data class TransportComplete(
        val transferId: String,
        val bytesTransferred: Long,
        val elapsedMs: Long,
        val averageBytesPerSec: Double?,
        val metrics: TransferMetrics
    ) : TransferOutcome

    data class Failed(val transferId: String?, val code: ErrorCode, val detail: String) : TransferOutcome
    data class Cancelled(val transferId: String?, val bytesTransferred: Long) : TransferOutcome
}

data class TransferMetrics(
    val elapsedMs: Long,
    val bytesTransferred: Long,
    val averageBytesPerSec: Double?,
    val readNanosTotal: Long,
    val writeNanosTotal: Long,
    val stallCount: Long,
    val adaptations: List<String>,
    val finalBlockSizeBytes: Int
)

// Maps local failures to the frozen M02 error vocabulary (errors.md).
// No new codes; messages carry no secrets.
object TransferErrors {
    fun sourceUnavailable(detail: String) = ErrorCode.FILE_NOT_FOUND to "Source unavailable: $detail"
    fun sourceReadFailed(detail: String) = ErrorCode.INTERNAL_ERROR to "Source read failed: $detail"
    fun connectionFailed(detail: String) = ErrorCode.NETWORK_LOST to "Data connection failed: $detail"
    fun connectionLost(detail: String) = ErrorCode.NETWORK_LOST to "Data connection lost: $detail"
    fun destinationUnavailable(detail: String) = ErrorCode.INTERNAL_ERROR to "Destination unavailable: $detail"
    fun destinationWriteFailed(detail: String) = ErrorCode.INTERNAL_ERROR to "Destination write failed: $detail"
    fun noSpace(detail: String) = ErrorCode.NOT_ENOUGH_STORAGE to "Not enough storage: $detail"
    fun protocolViolation(detail: String) = ErrorCode.INVALID_PROTOCOL to "Protocol violation: $detail"
    fun cancelled() = ErrorCode.CANCELLED to "Cancelled."
    fun timedOut(detail: String) = ErrorCode.TIMEOUT to "Timed out: $detail"
    fun rejected(detail: String) = ErrorCode.TRANSFER_REJECTED to detail
    fun internal(detail: String) = ErrorCode.INTERNAL_ERROR to "Internal failure: $detail"

    // Best-effort classification of filesystem write failures.
    fun classifyWriteFailure(exception: Exception): Pair<ErrorCode, String> {
        val message = (exception.message ?: "").lowercase()
        return if ("no space" in message || "enospc" in message || "disk full" in message) {
            noSpace(exception.message ?: "storage full")
        } else {
            destinationWriteFailed(exception.message ?: "write failed")
        }
    }
}

// Extracts the transferId from any transfer-scoped M02 message (null for
// non-transfer types). Used by mailboxes to route control-plane responses.
fun transferIdOf(message: LocalDropMessage): String? = when (val p = message.payload) {
    is TypedPayload.TransferRequest -> p.v.metadata.transferId
    is TypedPayload.TransferAccept -> p.v.transferId
    is TypedPayload.TransferReject -> p.v.transferId
    is TypedPayload.TransferStart -> p.v.transferId
    is TypedPayload.TransferProgress -> p.v.transferId
    is TypedPayload.Pause -> p.v.transferId
    is TypedPayload.Resume -> p.v.transferId
    is TypedPayload.Cancel -> p.v.transferId
    is TypedPayload.ResumeQuery -> p.v.transferId
    is TypedPayload.ResumeResponse -> p.v.transferId
    is TypedPayload.TransferComplete -> p.v.transferId
    is TypedPayload.VerifyRequest -> p.v.transferId
    is TypedPayload.VerifyResult -> p.v.transferId
    is TypedPayload.Error -> null
    else -> null
}
