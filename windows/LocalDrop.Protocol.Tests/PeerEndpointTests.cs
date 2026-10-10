// Verification audit F4: discovery carries dialable endpoints so the app can
// connect discovery -> control -> transfer with no manual IP/port entry.
// Covers: source-host recording/preservation, controlPort announcement,
// loopback propagation of both.
using System.Net;
using LocalDrop.Core;
using LocalDrop.Discovery;

namespace LocalDrop.Protocol.Tests;

public sealed class PeerEndpointTests
{
    private const string Self = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private const string PeerA = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private static byte[] HelloBytes(string deviceId = PeerA, long controlPort = 0) =>
        System.Text.Encoding.UTF8.GetBytes(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"HELLO\"," +
            "\"messageId\":\"11111111-1111-4111-8111-111111111111\"," +
            $"\"payload\":{{\"deviceId\":\"{deviceId}\",\"deviceName\":\"Peer\"," +
            $"\"deviceType\":\"windows\",\"controlPort\":{controlPort},\"capabilities\":[\"transfer\"]}}}}");

    [Fact]
    public void SourceHost_RecordedOnDiscover()
    {
        var registry = new PeerRegistry(Self);
        var bytes = HelloBytes();
        Assert.Equal(PeerRegistry.HandleResult.Discovered,
            registry.HandleDatagram(bytes, bytes.Length, "192.168.43.10"));
        Assert.Equal("192.168.43.10", registry.Snapshot()[0].LastSeenHost);
    }

    [Fact]
    public void MissingHost_Null_AndRefresh_PreservesKnownHost()
    {
        var registry = new PeerRegistry(Self);
        var bytes = HelloBytes();
        registry.HandleDatagram(bytes, bytes.Length);
        Assert.Null(registry.Snapshot()[0].LastSeenHost);
        registry.HandleDatagram(bytes, bytes.Length, "192.168.43.10");
        Assert.Equal("192.168.43.10", registry.Snapshot()[0].LastSeenHost);
        // Refresh without source info must not erase the known address.
        registry.HandleDatagram(bytes, bytes.Length);
        Assert.Equal("192.168.43.10", registry.Snapshot()[0].LastSeenHost);
        // Newer announcement replaces it.
        registry.HandleDatagram(bytes, bytes.Length, "192.168.43.11");
        Assert.Equal("192.168.43.11", registry.Snapshot()[0].LastSeenHost);
    }

    [Fact]
    public void BlankAndOversizedHosts_NormalizeToNull()
    {
        var registry = new PeerRegistry(Self);
        var bytes = HelloBytes();
        registry.HandleDatagram(bytes, bytes.Length, "   ");
        Assert.Null(registry.Snapshot()[0].LastSeenHost);
        registry.HandleDatagram(bytes, bytes.Length, new string('x', 300));
        Assert.Null(registry.Snapshot()[0].LastSeenHost);
    }

    [Fact]
    public void Announcement_CarriesConfiguredControlPort()
    {
        var device = new DeviceInfo(Self, "Me", "windows");
        string zero = System.Text.Encoding.UTF8.GetString(LanDiscoveryService.BuildAnnouncement(device));
        Assert.Contains("\"controlPort\":0", zero);
        string live = System.Text.Encoding.UTF8.GetString(
            LanDiscoveryService.BuildAnnouncement(device, AppConfig.ControlTcpPort));
        Assert.Contains("\"controlPort\":48424", live);
    }

    [Fact]
    public async Task LoopbackDiscovery_PropagatesHostAndControlPort()
    {
        var deviceA = new DeviceInfo(Self, "A", "windows");
        var deviceB = new DeviceInfo(PeerA, "B", "windows");
        var a = new LanDiscoveryService(
            deviceA, port: 0, announceIntervalMs: 100, broadcastAddress: IPAddress.Loopback);
        a.Start();
        try
        {
            // B announces into A's listener, advertising the live control port.
            var b = new LanDiscoveryService(
                deviceB, port: 0, announceIntervalMs: 100,
                broadcastAddress: IPAddress.Loopback, announcePort: a.LocalPort,
                controlPort: AppConfig.ControlTcpPort);
            b.Start();
            try
            {
                var deadline = DateTimeOffset.UtcNow.AddSeconds(8);
                DiscoveredPeer? peer = null;
                while (DateTimeOffset.UtcNow < deadline)
                {
                    peer = a.Snapshot().FirstOrDefault(p => p.Record.DeviceId == deviceB.DeviceId);
                    if (peer is not null) break;
                    await Task.Delay(50);
                }
                Assert.NotNull(peer);
                Assert.Equal(AppConfig.ControlTcpPort, peer!.Record.ControlPort);
                Assert.Equal("127.0.0.1", peer.LastSeenHost);
            }
            finally
            {
                await b.StopAsync();
                b.Dispose();
            }
        }
        finally
        {
            await a.StopAsync();
            a.Dispose();
        }
    }
}
