package com.localdrop.security

import com.localdrop.core.AppConfig
import com.localdrop.discovery.DiscoveryClock
import com.localdrop.discovery.SystemDiscoveryClock
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// M05: receiver transaction authorization codes
// (shared-protocol/authentication.md §6). In-memory only: codes are
// short-lived single-use secrets by design; a crash loses them and the
// receiver creates a new transaction. No database, no file I/O.
enum class CodeState { ACTIVE, EXPIRED, CONSUMED, CANCELLED }

data class ReceiverTransaction(
    val id: String,
    val code: String, // 6 digits, e.g. "583921". Never logged in full.
    val createdAtMillis: Long,
    val state: CodeState,
    val authorizedPeerDeviceId: String? = null
)

sealed interface VerifyOutcome {
    data class Authorized(val transactionId: String) : VerifyOutcome
    data class Rejected(val reason: CodeReject) : VerifyOutcome
}

enum class CodeReject { UNKNOWN_CODE, EXPIRED, CONSUMED, CANCELLED }

class TransactionManager(
    private val clock: DiscoveryClock = SystemDiscoveryClock,
    private val codeLifetimeMs: Long = AppConfig.TRANSACTION_CODE_LIFETIME_MS,
    private val logger: SecurityLogger? = null
) {
    private val transactions = ConcurrentHashMap<String, ReceiverTransaction>()
    private val secureRandom = SecureRandom()

    // Thin-UI hook: the receiver screen observes these snapshots; the
    // manager remains the single owner of transaction state.
    var receiverObserver: ((ReceiverTransactionUiState) -> Unit)? = null

    // Creates a transaction with a fresh unique 6-digit code.
    // Display form ("583 921") is a UI concern (see AuthUiState.formatCode).
    fun createTransaction(): ReceiverTransaction {
        repeat(20) {
            val code = "%06d".format(Locale.US, secureRandom.nextInt(1_000_000))
            if (transactions.values.none { t -> t.code == code && t.state == CodeState.ACTIVE }) {
                val txn = ReceiverTransaction(
                    id = UUID.randomUUID().toString(),
                    code = code,
                    createdAtMillis = clock.nowMillis(),
                    state = CodeState.ACTIVE
                )
                transactions[txn.id] = txn
                logger?.transactionEvent("created", txn.id, null, "receiver transaction opened")
                publish(txn, ReceiverPhase.CODE_ACTIVE, null)
                return txn
            }
        }
        throw IllegalStateException("could not mint a unique transaction code")
    }

    // Validates a submitted code. Wrong submissions do NOT consume the
    // correct code. Expiry is lazy: ACTIVE -> EXPIRED on observation.
    fun verifyByCode(submittedCode: String, peerDeviceId: String): VerifyOutcome {
        val normalized = submittedCode.filter { it.isDigit() }
        val txn = transactions.values.firstOrNull { it.code == normalized }
        if (txn == null) {
            logger?.transactionEvent("rejected", "-", peerDeviceId, "unknown code")
            publishUnknown()
            return VerifyOutcome.Rejected(CodeReject.UNKNOWN_CODE)
        }
        val now = clock.nowMillis()
        val current = if (txn.state == CodeState.ACTIVE && now - txn.createdAtMillis >= codeLifetimeMs) {
            val expired = txn.copy(state = CodeState.EXPIRED)
            transactions[txn.id] = expired
            expired
        } else txn
        return when (current.state) {
            CodeState.ACTIVE -> {
                transactions[txn.id] = current.copy(
                    state = CodeState.CONSUMED, authorizedPeerDeviceId = peerDeviceId
                )
                logger?.transactionEvent("authorized", txn.id, peerDeviceId, "code consumed")
                publish(current.copy(state = CodeState.CONSUMED, authorizedPeerDeviceId = peerDeviceId),
                    ReceiverPhase.AUTHORIZED, peerDeviceId)
                VerifyOutcome.Authorized(txn.id)
            }
            CodeState.EXPIRED -> {
                logger?.transactionEvent("rejected", txn.id, peerDeviceId, "expired code")
                publish(current, ReceiverPhase.EXPIRED, peerDeviceId)
                VerifyOutcome.Rejected(CodeReject.EXPIRED)
            }
            CodeState.CONSUMED -> {
                logger?.transactionEvent("rejected", txn.id, peerDeviceId, "replay of consumed code")
                publish(current, ReceiverPhase.INVALID, peerDeviceId)
                VerifyOutcome.Rejected(CodeReject.CONSUMED)
            }
            CodeState.CANCELLED -> {
                logger?.transactionEvent("rejected", txn.id, peerDeviceId, "cancelled transaction")
                publish(current, ReceiverPhase.INVALID, peerDeviceId)
                VerifyOutcome.Rejected(CodeReject.CANCELLED)
            }
        }
    }

    fun cancel(transactionId: String): Boolean {
        val txn = transactions[transactionId] ?: return false
        if (txn.state == CodeState.ACTIVE) {
            val cancelled = txn.copy(state = CodeState.CANCELLED)
            transactions[transactionId] = cancelled
            logger?.transactionEvent("cancelled", transactionId, null, "receiver cancelled")
            publish(cancelled, ReceiverPhase.CANCELLED, null)
        }
        return true
    }

    fun get(transactionId: String): ReceiverTransaction? = transactions[transactionId]

    // Visible ACTIVE transactions only (prunes nothing; expiry is lazy).
    fun activeTransactions(): List<ReceiverTransaction> {
        val now = clock.nowMillis()
        return transactions.values
            .map {
                if (it.state == CodeState.ACTIVE && now - it.createdAtMillis >= codeLifetimeMs) {
                    val expired = it.copy(state = CodeState.EXPIRED)
                    transactions[it.id] = expired
                    expired
                } else it
            }
            .filter { it.state == CodeState.ACTIVE }
    }

    fun clear() = transactions.clear()

    private fun publish(txn: ReceiverTransaction, phase: ReceiverPhase, peer: String?) {
        try {
            receiverObserver?.invoke(
                ReceiverTransactionUiState(
                    transactionId = txn.id,
                    codeDisplay = AuthUiState.formatCode(txn.code),
                    phase = phase,
                    expiresAtMillis = txn.createdAtMillis + codeLifetimeMs,
                    authorizedPeerDeviceId = peer?.takeIf { phase == ReceiverPhase.AUTHORIZED }
                )
            )
        } catch (e: Exception) {
        }
    }

    private fun publishUnknown() {
        try {
            receiverObserver?.invoke(
                ReceiverTransactionUiState(
                    transactionId = null,
                    codeDisplay = "",
                    phase = ReceiverPhase.INVALID,
                    expiresAtMillis = 0L
                )
            )
        } catch (e: Exception) {
        }
    }
}
