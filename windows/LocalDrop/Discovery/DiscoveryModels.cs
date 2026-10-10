// M03: discovery models. Plain C#, no WPF references (discovery stays
// independent of UI). A discovered peer is NOT a trusted peer (same LAN !=
// trusted, M05 owns trust). No sockets, no TCP, no pairing, no file access.
using LocalDrop.Protocol;
using Proto = LocalDrop.Protocol.Protocol;

namespace LocalDrop.Discovery;

// Local identity this instance announces. Supplied by the application layer;
// validated with the same bounds the M02 contract enforces on peers.
public sealed record DeviceInfo(
    string DeviceId,
    string DeviceName,
    string DeviceType,
    List<string>? Capabilities = null)
{
    public List<string> SafeCapabilities => Capabilities ?? [];

    public void Validate()
    {
        if (!Uuids.IsValid(DeviceId))
            throw new ArgumentException("deviceId: invalid UUID syntax", nameof(DeviceId));
        if (DeviceName.Length is < 1 or > Proto.MaxDeviceName)
            throw new ArgumentException("deviceName: out of range", nameof(DeviceName));
        if (DeviceType != "android" && DeviceType != "windows")
            throw new ArgumentException("deviceType: must be android|windows", nameof(DeviceType));
        var caps = SafeCapabilities;
        if (caps.Count > Proto.MaxCapabilities ||
            caps.Any(c => c.Length is < 1 or > Proto.MaxCapabilityLen))
            throw new ArgumentException("capabilities: out of range", nameof(Capabilities));
    }
}

// One nearby peer: the validated M02 DiscoveryRecord plus discovery
// lifecycle timestamps. State vocabulary (M03 §16) maps as: absent = Unknown,
// first insert = Discovered, later update = Refreshed, pruned = Expired.
// LastSeenHost is the UDP source address of the most recent announcement
// (dial hint for the M04/M07 control connection; verification audit F4).
// Keying stays deviceId-only (M03 §10): the host is NEVER an identity signal
// (hotspot/NAT/multi-interface conditions make IP comparison unreliable) and
// may be null when the source address is unavailable.
public sealed record DiscoveredPeer(
    DiscoveryRecord Record,
    long FirstSeenMillis,
    long LastSeenMillis,
    string? LastSeenHost = null);

// Clock abstraction: production uses wall time; tests use a fake clock for
// deterministic expiry tests without sleeping.
public interface IDiscoveryClock
{
    long NowMillis();
}

public sealed class SystemDiscoveryClock : IDiscoveryClock
{
    public static readonly SystemDiscoveryClock Instance = new();
    public long NowMillis() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
