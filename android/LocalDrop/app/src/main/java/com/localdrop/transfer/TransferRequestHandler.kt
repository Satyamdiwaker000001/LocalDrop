package com.localdrop.transfer

import com.localdrop.network.CloseReason
import com.localdrop.network.ControlChannelListener
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.TypedPayload

// M07 receiver-side TRANSFER_REQUEST handler (Android). Mirrors the Windows
// M06 TransferRequestHandler. Sits in the control listener chain BEHIND
// AuthManager/TransactionAuthorizer and enforces, in order: channel
// authenticated → authorized M05 session for the peer → metadata addressed to
// this receiver → no resume requested (M10 owns that) → receiver idle →
// destination viable. Replies TRANSFER_ACCEPT (registers the pending transfer
// and starts the data wait) or TRANSFER_REJECT with a frozen M02 error code.
// TRANSFER_COMPLETE / CANCEL / ERROR for the active transfer are forwarded to
// the engine; all other types are ignored here.
class TransferRequestHandler(private val engine: ReceiverEngine) : ControlChannelListener {
    override fun onMessage(connection: ControlConnection, message: LocalDropMessage) {
        when (message.type) {
            MessageType.TRANSFER_REQUEST -> {
                val req = message.payload as? TypedPayload.TransferRequest ?: return
                engine.handleTransferRequest(connection, req.v.metadata)
            }
            MessageType.TRANSFER_COMPLETE -> {
                val done = message.payload as? TypedPayload.TransferComplete ?: return
                engine.handleTransferComplete(connection, done.v)
            }
            MessageType.CANCEL -> {
                val cancel = message.payload as? TypedPayload.Cancel ?: return
                engine.handleCancel(connection, cancel.v.transferId)
            }
            MessageType.ERROR -> {
                val error = message.payload as? TypedPayload.Error ?: return
                engine.handlePeerError(connection, message.transferId, error.v.error)
            }
            else -> { /* Not transfer-scoped here; other layers own the rest. */ }
        }
    }

    override fun onClosed(connection: ControlConnection, reason: CloseReason, detail: String) {
        engine.handleControlClosed(connection)
    }
}
