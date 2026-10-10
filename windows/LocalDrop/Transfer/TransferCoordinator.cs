// Verification audit F5: minimum discovery-to-transfer integration on the
// Windows (sender) side. Connects the EXISTING components — M03 discovery
// snapshot -> endpoint resolution (no manual IP) -> M04 control dial ->
// M05 mutual auth -> transaction-code authorization -> M07 SenderEngine —
// without new protocols, new discovery, or UI redesign. The 6-digit code is
// relayed by the users (receiver displays, sender types), exactly as M05
// designed; everything else is resolved programmatically.
using LocalDrop.Core;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Protocol;
using LocalDrop.Security;

namespace LocalDrop.Transfer;

public sealed class TransferCoordinator : ITransferSender
{
    private readonly DeviceInfo _local;
    private readonly IDeviceIdentityStore _store;
    private readonly ISecurityLogger _logger;
    private readonly LanDiscoveryService _discovery;
    private readonly int _dataPort;
    private readonly int _authTimeoutMs;
    private readonly int _codeTimeoutMs;
    private readonly int _commitLingerMs;

    public TransferCoordinator(
        DeviceInfo local,
        IDeviceIdentityStore store,
        ISecurityLogger logger,
        LanDiscoveryService discovery,
        int dataPort = AppConfig.DataTcpPort,
        int authTimeoutMs = 30000,
        int codeTimeoutMs = 30000,
        int commitLingerMs = AppConfig.TransferCommitLingerMs)
    {
        local.Validate();
        ArgumentNullException.ThrowIfNull(store);
        ArgumentNullException.ThrowIfNull(logger);
        ArgumentNullException.ThrowIfNull(discovery);
        _local = local;
        _store = store;
        _logger = logger;
        _discovery = discovery;
        _dataPort = dataPort;
        _authTimeoutMs = authTimeoutMs;
        _codeTimeoutMs = codeTimeoutMs;
        _commitLingerMs = commitLingerMs;
    }

    // Peers the discovery layer currently sees (UI observes this; the
    // coordinator dials from it — the user picks a device, never an IP).
    public List<DiscoveredPeer> NearbyPeers() => _discovery.Snapshot();

    public static (string Host, int Port)? ResolvePeer(DiscoveredPeer peer) =>
        PeerEndpointResolver.Resolve(peer);

    // Full sender flow for one file to a DISCOVERED peer. Returns a terminal
    // TransferOutcome (never throws on peer/network failure; cancellation
    // maps to Cancelled per the engine contract).
    public async Task<TransferOutcome> SendFileAsync(
        string peerDeviceId,
        ITransferSource source,
        string code,
        CancellationToken ct = default,
        Action<TransferProgress>? onProgress = null)
    {
        var peer = _discovery.Snapshot().FirstOrDefault(p => p.Record.DeviceId == peerDeviceId);
        if (peer is null)
            return new TransferOutcome.Failed(null, ErrorCode.INTERNAL_ERROR,
                "Peer not discovered; select a nearby device.");
        var endpoint = PeerEndpointResolver.Resolve(peer);
        if (endpoint is null)
            return new TransferOutcome.Failed(null, ErrorCode.INTERNAL_ERROR,
                "Peer has no dialable endpoint yet; wait for discovery.");
        var mailbox = new TransferMailbox();
        var auth = new AuthManager(_local, _store, _logger) { Inner = mailbox };
        var control = new ControlConnection(_local, auth);
        bool connected;
        try
        {
            connected = await control.ConnectAsync(endpoint.Value.Host, endpoint.Value.Port, ct);
        }
        catch (OperationCanceledException)
        {
            return new TransferOutcome.Cancelled(null, 0L);
        }
        if (!connected)
            return new TransferOutcome.Failed(null, ErrorCode.NETWORK_LOST,
                "Control connection failed.");
        try
        {
            AuthOutcome authOutcome;
            try
            {
                authOutcome = await auth.BeginAuthAsync(control).WaitAsync(
                    TimeSpan.FromMilliseconds(_authTimeoutMs), ct);
            }
            catch (OperationCanceledException)
            {
                return new TransferOutcome.Cancelled(null, 0L);
            }
            catch (TimeoutException)
            {
                return new TransferOutcome.Failed(null, ErrorCode.TIMEOUT,
                    "Authentication timed out.");
            }
            if (authOutcome is not AuthOutcome.Authenticated)
                return new TransferOutcome.Failed(null, ErrorCode.AUTH_FAILED,
                    "Peer authentication failed.");
            PairSubmitOutcome pair;
            try
            {
                pair = await auth.SenderSubmitCodeAsync(control, code, _codeTimeoutMs, ct);
            }
            catch (OperationCanceledException)
            {
                return new TransferOutcome.Cancelled(null, 0L);
            }
            switch (pair)
            {
                case PairSubmitOutcome.Accepted:
                    break;
                case PairSubmitOutcome.Rejected rejected:
                    return new TransferOutcome.Failed(null, ErrorCode.TRANSFER_REJECTED,
                        $"Transfer code rejected: {rejected.Reason}");
                case PairSubmitOutcome.Failed failed:
                    return new TransferOutcome.Failed(null, ErrorCode.TIMEOUT,
                        $"Transfer code submission failed: {failed.Reason}");
                default:
                    return new TransferOutcome.Failed(null, ErrorCode.INTERNAL_ERROR,
                        "Transfer code submission failed.");
            }
            var sender = new SenderEngine(_local.DeviceId, control, mailbox, dataPort: _dataPort);
            var outcome = await sender.SendAsync(new SenderEngine.SendRequest(
                source, peerDeviceId, DataHost: endpoint.Value.Host,
                CancellationToken: ct, OnProgress: onProgress));
            if (outcome is TransferOutcome.TransportComplete)
            {
                // Commit grace (audit F5): TRANSFER_COMPLETE travels on the
                // CONTROL connection while bytes travel on the DATA
                // connection, so arrival order at the receiver is NOT
                // guaranteed. Tearing down control immediately after the
                // sender-side signal can win the race against the receiver's
                // commit and fail an otherwise complete transfer (control
                // loss fails closed by design). Linger so the commit frame
                // is processed before teardown. The RECEIVER stays
                // authoritative for completion; the sender outcome means
                // "bytes + commit signal delivered". Selectively cancelling
                // during the linger still reports the delivered outcome.
                try
                {
                    await Task.Delay(_commitLingerMs, ct);
                }
                catch (OperationCanceledException)
                {
                    // Outcome already decided; teardown grace only.
                }
            }
            return outcome;
        }
        finally
        {
            try { await control.DisconnectAsync(); } catch { }
        }
    }
}
