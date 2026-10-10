// M05: receiver-side PAIR_REQUEST handler (shared-protocol/authentication.md
// §6). Sits inside the listener chain BEHIND AuthManager (which consumes
// AUTH_*): connection events = AuthManager(inner = TransactionAuthorizer).
// Enforces, in order: channel authenticated → payload deviceId matches the
// verified peer → code known/ACTIVE/unexpired → consume + authorize.
// Every failure replies with the same generic reason (never the expected
// code) and changes no authorization state. Wrong attempts do NOT consume.
using LocalDrop.Network;
using LocalDrop.Protocol;

namespace LocalDrop.Security;

public sealed class TransactionAuthorizer(
    AuthManager auth,
    TransactionManager transactions,
    AuthorizedSessionStore sessions,
    ISecurityLogger logger,
    string receiverDeviceId,
    IControlEvents? inner = null) : IControlEvents
{
    // Generic peer-facing reason. Never varies by failure cause, so it
    // cannot oracle the expected code.
    public const string GenericRejectReason = "Invalid transfer code.";

    public void OnMessage(ControlConnection connection, LocalDropMessage message)
    {
        if (message.Type != MessageType.PAIR_REQUEST)
        {
            inner?.OnMessage(connection, message);
            return;
        }
        if (message.Payload is not TypedPayload.PairRequest pair)
        {
            // Malformed per frozen M02 validation this cannot happen (the M04
            // codec would have rejected it); defend anyway without state change.
            Reply(connection, false);
            return;
        }
        var peer = auth.VerifiedPeer(connection);
        if (peer is null)
        {
            logger.TransactionEvent("rejected", "-", null, "pair submit on unauthenticated channel");
            Reply(connection, false);
            return;
        }
        if (pair.V.DeviceId != peer.DeviceId)
        {
            logger.TransactionEvent("rejected", "-", peer.DeviceId, "pair deviceId != authenticated peer");
            Reply(connection, false);
            return;
        }
        switch (transactions.VerifyByCode(pair.V.DisplayCode, peer.DeviceId))
        {
            case VerifyOutcome.Authorized authorized:
                sessions.Authorize(peer.DeviceId, authorized.TransactionId);
                Reply(connection, true);
                break;
            case VerifyOutcome.Rejected:
                Reply(connection, false);
                break;
        }
    }

    public void OnClosed(ControlConnection connection, CloseReason reason, string detail)
    {
        try
        {
            inner?.OnClosed(connection, reason, detail);
        }
        catch
        {
        }
    }

    private void Reply(ControlConnection connection, bool accepted)
    {
        try
        {
            var response = new LocalDropMessage(
                MessageType.PAIR_RESPONSE,
                ProtocolCodec.NewMessageId(),
                null, null, null,
                new TypedPayload.PairResponse(new PairResponsePayload(
                    receiverDeviceId,
                    accepted,
                    accepted ? null : GenericRejectReason)));
            connection.SendAsync(response).GetAwaiter().GetResult();
        }
        catch
        {
            // Best effort; channel teardown is handled by the owner.
        }
    }
}
