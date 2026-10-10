package com.localdrop.security

// M05: thin UI state models. Security/domain layers own the state machines;
// UI observes snapshots through AuthUiObserver. No Compose/WPF imports, no
// second authorization engine in the UI.
object AuthUiState {
    // Receiver display form: "583921" -> "583 921".
    fun formatCode(code: String): String =
        if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

    // Sender input normalization: strips spaces/dashes, keeps digits.
    fun normalizeInput(input: String): String = input.filter { it.isDigit() }
}

enum class ReceiverPhase { IDLE, CODE_ACTIVE, AUTHORIZED, INVALID, EXPIRED, CANCELLED }

data class ReceiverTransactionUiState(
    val transactionId: String?,
    val codeDisplay: String, // "" when no active code; full code only on the receiver screen
    val phase: ReceiverPhase,
    val expiresAtMillis: Long,
    val authorizedPeerDeviceId: String? = null
)

enum class SenderPhase {
    IDLE, AUTHENTICATING, AUTHENTICATED, SUBMITTING, AUTHORIZED, FAILED
}

data class SenderAuthUiState(
    val peerDeviceId: String?,
    val phase: SenderPhase,
    val lastError: String? = null // generic text only, never secrets
)

interface AuthUiObserver {
    fun onReceiverState(state: ReceiverTransactionUiState)
    fun onSenderState(state: SenderAuthUiState)
}
