// M06 receiver-side TRANSFER_REQUEST handler (Windows). Sits in the control
// listener chain BEHIND AuthManager/TransactionAuthorizer and enforces, in
// order: channel authenticated → authorized M05 session for the peer →
// metadata addressed to this receiver → no resume requested (M10 owns that)
// → receiver idle → destination viable. Replies TRANSFER_ACCEPT (registers
// the pending transfer and starts the data wait) or TRANSFER_REJECT with a
// frozen M02 error code. TRANSFER_COMPLETE / CANCEL / ERROR for the active
// transfer are forwarded to the engine; all other types are ignored here.
using LocalDrop.Network;
using LocalDrop.Protocol;
using LocalDrop.Security;

namespace LocalDrop.Transfer;

public sealed class TransferRequestHandler : IControlEvents
{
    private readonly ReceiverEngine _engine;

    public TransferRequestHandler(ReceiverEngine engine) => _engine = engine;

    public void OnMessage(ControlConnection connection, LocalDropMessage message)
    {
        switch (message.Type)
        {
            case MessageType.TRANSFER_REQUEST:
                if (message.Payload is TypedPayload.TransferRequest req)
                    _engine.HandleTransferRequest(connection, req.V.Metadata);
                break;
            case MessageType.TRANSFER_COMPLETE:
                if (message.Payload is TypedPayload.TransferComplete done)
                    _engine.HandleTransferComplete(connection, done.V);
                break;
            case MessageType.CANCEL:
                if (message.Payload is TypedPayload.Cancel cancel)
                    _engine.HandleCancel(connection, cancel.V);
                break;
            case MessageType.ERROR:
                if (message.Payload is TypedPayload.Error error)
                    _engine.HandlePeerError(connection, message.TransferId, error.V.Error);
                break;
            default:
                break; // Not transfer-scoped here; other layers own the rest.
        }
    }

    public void OnClosed(ControlConnection connection, CloseReason reason, string detail) =>
        _engine.HandleControlClosed(connection);
}
