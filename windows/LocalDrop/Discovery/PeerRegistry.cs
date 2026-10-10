// M03: nearby-peer registry. Pure logic, thread-safe, no sockets.
// Keyed by authoritative device ID (never by IP: hotspot/NAT/multi-interface
// conditions make IP comparison unreliable, M03 §10).
// Expiry is lazy (on snapshot/handle/prune): no timer thread, no timer leak,
// fully deterministic under a fake clock.
using LocalDrop.Core;
using LocalDrop.Protocol;

namespace LocalDrop.Discovery;

public sealed class PeerRegistry
{
    public enum HandleResult
    {
        // Packet ignored: oversized, malformed, unsupported version/type,
        // invalid record, non-HELLO message, or self announcement.
        Ignored,
        // First sighting of this device ID.
        Discovered,
        // Repeat announcement: existing record updated, expiry refreshed.
        Refreshed,
    }

    private readonly string _selfDeviceId;
    private readonly IDiscoveryClock _clock;
    private readonly long _peerTimeoutMs;
    private readonly object _gate = new();
    private readonly Dictionary<string, DiscoveredPeer> _peers = new();

    public PeerRegistry(
        string selfDeviceId,
        IDiscoveryClock? clock = null,
        long peerTimeoutMs = AppConfig.DiscoveryPeerTimeoutMs)
    {
        _selfDeviceId = selfDeviceId;
        _clock = clock ?? SystemDiscoveryClock.Instance;
        _peerTimeoutMs = peerTimeoutMs;
    }

    // Processes one received UDP datagram. Never throws on peer input:
    // every invalid shape maps to Ignored (listener keeps running).
    // senderHost is the datagram source address (dial hint, stored on the
    // peer; null when unavailable). Identity keying stays deviceId-only.
    public HandleResult HandleDatagram(byte[] bytes, int length, string? senderHost = null)
    {
        if (length <= 0 || length > AppConfig.DiscoveryMaxPacketBytes)
            return HandleResult.Ignored;
        var slice = new byte[length];
        Buffer.BlockCopy(bytes, 0, slice, 0, length);
        DecodeOutcome outcome;
        try
        {
            outcome = ProtocolCodec.DecodeUtf8(slice);
        }
        catch
        {
            return HandleResult.Ignored;
        }
        if (outcome is not DecodeOutcome.Success success)
            return HandleResult.Ignored;
        var message = success.Message;
        if (message.Type != MessageType.HELLO)
            return HandleResult.Ignored;
        if (message.Payload is not TypedPayload.Hello hello)
            return HandleResult.Ignored;
        var record = new DiscoveryRecord(
            hello.V.DeviceId, hello.V.DeviceName, hello.V.DeviceType,
            hello.V.ControlPort, hello.V.Capabilities);
        if (record.DeviceId == _selfDeviceId)
            return HandleResult.Ignored;
        long now = _clock.NowMillis();
        // Blank/oversized hosts carry no dial value; normalize to null.
        // (Length cap: an IP literal never approaches this; anything larger
        // is garbage, not an address.)
        string? host = string.IsNullOrWhiteSpace(senderHost) ? null
            : senderHost.Trim() is string trimmed && trimmed.Length <= 256 ? trimmed : null;
        lock (_gate)
        {
            if (!_peers.TryGetValue(record.DeviceId, out var existing))
            {
                _peers[record.DeviceId] = new DiscoveredPeer(record, now, now, host);
                return HandleResult.Discovered;
            }
            // Preserve the last KNOWN host: a refresh without source info
            // must not erase a real address.
            _peers[record.DeviceId] = existing with
            {
                Record = record,
                LastSeenMillis = now,
                LastSeenHost = host ?? existing.LastSeenHost,
            };
            return HandleResult.Refreshed;
        }
    }

    // Active peers only (prunes expired first). Never exposes self: self is
    // filtered at insert, so it can never appear here.
    public List<DiscoveredPeer> Snapshot()
    {
        lock (_gate)
        {
            PruneLocked();
            return [.. _peers.Values];
        }
    }

    public void Prune()
    {
        lock (_gate) PruneLocked();
    }

    public void Clear()
    {
        lock (_gate) _peers.Clear();
    }

    public int Count
    {
        get { lock (_gate) return _peers.Count; }
    }

    private void PruneLocked()
    {
        long now = _clock.NowMillis();
        var expired = _peers
            .Where(kv => now - kv.Value.LastSeenMillis >= _peerTimeoutMs)
            .Select(kv => kv.Key)
            .ToList();
        foreach (var key in expired) _peers.Remove(key);
    }
}
