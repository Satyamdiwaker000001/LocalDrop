package com.localdrop.security

import com.localdrop.network.CloseReason
import com.localdrop.network.ControlChannelListener
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.PairResponsePayload
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TypedPayload

// M05: receiver-side PAIR_REQUEST handler (shared-protocol/authentication.md
// §6). Sits inside the listener chain BEHIND AuthManager (which consumes
// AUTH_*): connection.listener = AuthManager(inner = TransactionAuthorizer).
// Enforces, in order: channel authenticated → payload deviceId matches the
// verified peer → code known/ACTIVE/unexpired → consume + authorize.
// Every failure replies with the same generic reason (never the expected
// code) and changes no authorization state. Wrong attempts do NOT consume.
class TransactionAuthorizer(
    private val auth: AuthManager,
    private val transactions: TransactionManager,
    private val sessions: AuthorizedSessionStore,
    private val logger: SecurityLogger,
    private val receiverDeviceId: String,
    private val inner: ControlChannelListener? = null
) : ControlChannelListener {

    companion object {
        // Generic peer-facing reason. Never varies by failure cause, so it
        // cannot oracle the expected code.
        const val GENERIC_REJECT_REASON = "Invalid transfer code."
    }

    override fun onMessage(connection: ControlConnection, message: LocalDropMessage) {
        if (message.type != MessageType.PAIR_REQUEST) {
            inner?.onMessage(connection, message)
            return
        }
        val payload = (message.payload as? TypedPayload.PairRequest)?.v
        if (payload == null) {
            // Malformed per frozen M02 validation this cannot happen (the M04
            // codec would have rejected it); defend anyway without state change.
            reply(connection, false)
            return
        }
        val peer = auth.verifiedPeer(connection)
        if (peer == null) {
            logger.transactionEvent("rejected", "-", null, "pair submit on unauthenticated channel")
            reply(connection, false)
            return
        }
        if (payload.deviceId != peer.deviceId) {
            logger.transactionEvent("rejected", "-", peer.deviceId, "pair deviceId != authenticated peer")
            reply(connection, false)
            return
        }
        when (val result = transactions.verifyByCode(payload.displayCode, peer.deviceId)) {
            is VerifyOutcome.Authorized -> {
                sessions.authorize(peer.deviceId, result.transactionId)
                reply(connection, true)
            }
            is VerifyOutcome.Rejected -> reply(connection, false)
        }
    }

    override fun onClosed(connection: ControlConnection, reason: CloseReason, detail: String) {
        try {
            inner?.onClosed(connection, reason, detail)
        } catch (e: Exception) {
        }
    }

    private fun reply(connection: ControlConnection, accepted: Boolean) {
        try {
            connection.send(
                LocalDropMessage(
                    type = MessageType.PAIR_RESPONSE,
                    messageId = ProtocolCodec.newMessageId(),
                    correlationId = null, sessionId = null, transferId = null,
                    payload = TypedPayload.PairResponse(
                        PairResponsePayload(
                            receiverDeviceId,
                            accepted,
                            if (accepted) null else GENERIC_REJECT_REASON
                        )
                    )
                )
            )
        } catch (e: Exception) {
            // Best effort; channel teardown is handled by the owner.
        }
    }
}
