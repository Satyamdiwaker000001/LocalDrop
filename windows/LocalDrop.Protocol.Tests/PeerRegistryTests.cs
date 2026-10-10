// M03 §23: registry validation, lifecycle states, shared vector
// compatibility. Pure logic, fake clock, no sockets, no Internet.
using LocalDrop.Discovery;
using LocalDrop.Protocol;

namespace LocalDrop.Protocol.Tests;

internal sealed class FakeDiscoveryClock(long now = 1_000_000L) : IDiscoveryClock
{
    public long Now = now;
    public long NowMillis() => Now;
    public void Advance(long ms) => Now += ms;
}

public sealed class PeerRegistryTests
{
    private const string Self = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private const string PeerA = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private const string PeerB = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

    private static byte[] HelloBytes(
        string deviceId = PeerA,
        string deviceName = "TestPeer",
        string deviceType = "windows") =>
        System.Text.Encoding.UTF8.GetBytes(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"HELLO\"," +
            "\"messageId\":\"11111111-1111-4111-8111-111111111111\"," +
            $"\"payload\":{{\"deviceId\":\"{deviceId}\",\"deviceName\":\"{deviceName}\"," +
            $"\"deviceType\":\"{deviceType}\",\"controlPort\":0,\"capabilities\":[\"transfer\"]}}}}");

    [Fact]
    public void ValidDiscoveryRecord_Accepted()
    {
        var registry = new PeerRegistry(Self);
        var bytes = HelloBytes();
        Assert.Equal(PeerRegistry.HandleResult.Discovered, registry.HandleDatagram(bytes, bytes.Length));
        var peers = registry.Snapshot();
        Assert.Single(peers);
        Assert.Equal(PeerA, peers[0].Record.DeviceId);
        Assert.Equal("TestPeer", peers[0].Record.DeviceName);
    }

    [Fact]
    public void MalformedDiscoveryRecord_Rejected()
    {
        var registry = new PeerRegistry(Self);
        var bad = System.Text.Encoding.UTF8.GetBytes("{not json");
        Assert.Equal(PeerRegistry.HandleResult.Ignored, registry.HandleDatagram(bad, bad.Length));
        Assert.Empty(registry.Snapshot());
    }

    [Fact]
    public void UnsupportedProtocolVersion_Rejected()
    {
        var registry = new PeerRegistry(Self);
        var bad = System.Text.Encoding.UTF8.GetBytes(
            Vectors.Hello.Replace("\"protocolVersion\":1", "\"protocolVersion\":2"));
        Assert.Equal(PeerRegistry.HandleResult.Ignored, registry.HandleDatagram(bad, bad.Length));
        Assert.Empty(registry.Snapshot());
    }

    [Fact]
    public void OversizedDiscoveryPacket_Rejected()
    {
        var registry = new PeerRegistry(Self);
        var big = new byte[4097];
        Array.Fill(big, (byte)'x');
        Assert.Equal(PeerRegistry.HandleResult.Ignored, registry.HandleDatagram(big, big.Length));
        Assert.Empty(registry.Snapshot());
    }

    [Fact]
    public void InvalidDeviceId_Rejected()
    {
        var registry = new PeerRegistry(Self);
        var bad = HelloBytes(deviceId: "not-a-uuid");
        Assert.Equal(PeerRegistry.HandleResult.Ignored, registry.HandleDatagram(bad, bad.Length));
        Assert.Empty(registry.Snapshot());
    }

    [Fact]
    public void NonHelloMessage_Ignored()
    {
        var registry = new PeerRegistry(Self);
        var pause = System.Text.Encoding.UTF8.GetBytes(Vectors.Pause);
        Assert.Equal(PeerRegistry.HandleResult.Ignored, registry.HandleDatagram(pause, pause.Length));
        Assert.Empty(registry.Snapshot());
    }

    [Fact]
    public void SelfDiscovery_FilteredByDeviceId()
    {
        var registry = new PeerRegistry(Self);
        var me = HelloBytes(deviceId: Self, deviceName: "Myself", deviceType: "windows");
        Assert.Equal(PeerRegistry.HandleResult.Ignored, registry.HandleDatagram(me, me.Length));
        Assert.Empty(registry.Snapshot());
    }

    [Fact]
    public void RepeatedAnnouncement_UpdatesExistingPeerWithoutDuplicates()
    {
        var registry = new PeerRegistry(Self);
        var first = HelloBytes();
        Assert.Equal(PeerRegistry.HandleResult.Discovered, registry.HandleDatagram(first, first.Length));
        var second = HelloBytes(deviceName: "Renamed");
        Assert.Equal(PeerRegistry.HandleResult.Refreshed, registry.HandleDatagram(second, second.Length));
        var peers = registry.Snapshot();
        Assert.Single(peers);
        Assert.Equal("Renamed", peers[0].Record.DeviceName);
    }

    [Fact]
    public void MultiplePeers_MaintainedIndependently()
    {
        var registry = new PeerRegistry(Self);
        var a = HelloBytes(deviceId: PeerA, deviceName: "A");
        var b = HelloBytes(deviceId: PeerB, deviceName: "B");
        registry.HandleDatagram(a, a.Length);
        registry.HandleDatagram(b, b.Length);
        Assert.Equal(2, registry.Snapshot().Count);
    }

    [Fact]
    public void PeerExpires_AfterTimeout()
    {
        var clock = new FakeDiscoveryClock();
        var registry = new PeerRegistry(Self, clock, peerTimeoutMs: 10_000L);
        var bytes = HelloBytes();
        registry.HandleDatagram(bytes, bytes.Length);
        Assert.Single(registry.Snapshot());
        clock.Advance(10_001L);
        Assert.Empty(registry.Snapshot());
    }

    [Fact]
    public void PeerRefresh_PreventsPrematureExpiry()
    {
        var clock = new FakeDiscoveryClock();
        var registry = new PeerRegistry(Self, clock, peerTimeoutMs: 10_000L);
        var bytes = HelloBytes();
        registry.HandleDatagram(bytes, bytes.Length);
        clock.Advance(9_000L);
        registry.HandleDatagram(bytes, bytes.Length); // refresh
        clock.Advance(9_000L);
        Assert.Single(registry.Snapshot()); // 18s total, refreshed at 9s
        clock.Advance(1_001L);
        Assert.Empty(registry.Snapshot());
    }

    [Fact]
    public void PeerCanReappear_AfterExpiry()
    {
        var clock = new FakeDiscoveryClock();
        var registry = new PeerRegistry(Self, clock, peerTimeoutMs: 10_000L);
        var bytes = HelloBytes();
        registry.HandleDatagram(bytes, bytes.Length);
        clock.Advance(11_000L);
        Assert.Empty(registry.Snapshot());
        Assert.Equal(
            PeerRegistry.HandleResult.Discovered,
            registry.HandleDatagram(bytes, bytes.Length));
        Assert.Single(registry.Snapshot());
    }

    [Fact]
    public void AnnouncementBuilder_EmitsValidHelloForLocalIdentity()
    {
        var local = new DeviceInfo(Self, "WinPC", "windows", ["transfer"]);
        var bytes = LanDiscoveryService.BuildAnnouncement(local);
        var success = Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.DecodeUtf8(bytes));
        Assert.Equal(MessageType.HELLO, success.Message.Type);
        var hello = Assert.IsType<TypedPayload.Hello>(success.Message.Payload);
        Assert.Equal(Self, hello.V.DeviceId);
        Assert.Equal("WinPC", hello.V.DeviceName);
    }

    [Fact]
    public void SharedM02HelloVector_AcceptedAsDiscoveryAnnouncement()
    {
        // Android/Windows shared discovery vector compatibility: the exact
        // M02 test-vector bytes must decode to the expected nearby peer.
        var registry = new PeerRegistry("ffffffff-ffff-4fff-8fff-ffffffffffff");
        var bytes = System.Text.Encoding.UTF8.GetBytes(Vectors.Hello);
        Assert.Equal(PeerRegistry.HandleResult.Discovered, registry.HandleDatagram(bytes, bytes.Length));
        var peers = registry.Snapshot();
        Assert.Single(peers);
        Assert.Equal("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", peers[0].Record.DeviceId);
        Assert.Equal("Pixel 8", peers[0].Record.DeviceName);
        Assert.Equal("android", peers[0].Record.DeviceType);
    }
}
