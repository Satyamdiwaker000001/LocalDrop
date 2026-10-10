// M07 UI closure: minimal sender abstraction so the WPF send workflow is
// testable without sockets. TransferCoordinator is the production
// implementation; behavior is unchanged.
using LocalDrop.Discovery;

namespace LocalDrop.Transfer;

public interface ITransferSender
{
    List<DiscoveredPeer> NearbyPeers();
    Task<TransferOutcome> SendFileAsync(
        string peerDeviceId,
        ITransferSource source,
        string code,
        CancellationToken ct = default,
        // Live engine progress (worker thread); null = outcome only.
        Action<TransferProgress>? onProgress = null);
}
