// Verification audit F4/F5: resolves a discovered peer to a dialable
// control endpoint with NO manual IP/port entry. The host comes from the
// UDP source address recorded by PeerRegistry; the port comes from the
// peer's own HELLO controlPort announcement. Returns null when either is
// unknown (caller fails cleanly — never dials a guess).
//
// Security note: the endpoint is a DIAL HINT only. Identity is the M05
// ECDSA-verified deviceId and authorization is the consumed transaction
// code; a hostile host:port either fails authentication or is rejected.
// Same LAN does not imply trust.
using LocalDrop.Discovery;

namespace LocalDrop.Transfer;

public static class PeerEndpointResolver
{
    public static (string Host, int Port)? Resolve(DiscoveredPeer peer)
    {
        if (string.IsNullOrWhiteSpace(peer.LastSeenHost)) return null;
        if (peer.Record.ControlPort is < 1 or > 65535) return null;
        return (peer.LastSeenHost.Trim(), (int)peer.Record.ControlPort);
    }
}
