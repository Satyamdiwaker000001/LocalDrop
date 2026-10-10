// M03 §23 (items 13-16): real loopback UDP sockets (127.0.0.1, ephemeral
// ports). No Internet, no hardware: instances cross-target each other over
// loopback with short intervals.
using System.Net;
using System.Net.Sockets;
using System.Text;
using LocalDrop.Discovery;

namespace LocalDrop.Protocol.Tests;

public sealed class LanDiscoveryTests
{
    private static readonly DeviceInfo DeviceA =
        new("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "PhoneA", "android");
    private static readonly DeviceInfo DeviceB =
        new("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "PcB", "windows");

    private static async Task WaitForAsync(string description, Func<bool> condition, int timeoutMs = 5000)
    {
        var deadline = DateTimeOffset.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (condition()) return;
            await Task.Delay(50);
        }
        throw new Xunit.Sdk.XunitException($"timed out waiting for: {description}");
    }

    [Fact]
    public async Task StartAndStop_Lifecycle()
    {
        var discovery = new LanDiscoveryService(DeviceA, port: 0, broadcastAddress: IPAddress.Loopback);
        Assert.False(discovery.IsRunning);
        discovery.Start();
        Assert.True(discovery.IsRunning);
        Assert.True(discovery.LocalPort > 0);
        // Second start is a no-op, not a duplicate listener.
        discovery.Start();
        Assert.True(discovery.IsRunning);
        await discovery.StopAsync();
        Assert.False(discovery.IsRunning);
        // Second stop is a no-op.
        await discovery.StopAsync();
        discovery.Dispose();
    }

    [Fact]
    public async Task Restart_BindsCleanlyWithoutAddressInUse()
    {
        var discovery = new LanDiscoveryService(DeviceA, port: 0, broadcastAddress: IPAddress.Loopback);
        discovery.Start();
        await discovery.StopAsync();
        discovery.Start();
        Assert.True(discovery.IsRunning);
        await discovery.StopAsync();
        Assert.False(discovery.IsRunning);
        discovery.Dispose();
    }

    [Fact]
    public async Task PeerDiscovered_OverLoopbackUdp()
    {
        var a = new LanDiscoveryService(
            DeviceA, port: 0, announceIntervalMs: 100, broadcastAddress: IPAddress.Loopback);
        a.Start();
        try
        {
            int portA = a.LocalPort;
            // B announces into A's listener; B's own listener sits idle.
            var b = new LanDiscoveryService(
                DeviceB, port: 0, announceIntervalMs: 100,
                broadcastAddress: IPAddress.Loopback, announcePort: portA);
            b.Start();
            try
            {
                await WaitForAsync("A discovers B",
                    () => a.Snapshot().Any(p => p.Record.DeviceId == DeviceB.DeviceId));
                // B's registry holds nobody (A announces to its own port).
                Assert.DoesNotContain(b.Snapshot(), p => p.Record.DeviceId == DeviceA.DeviceId);
                var peer = a.Snapshot().First(p => p.Record.DeviceId == DeviceB.DeviceId);
                Assert.Equal("PcB", peer.Record.DeviceName);
                Assert.Equal("windows", peer.Record.DeviceType);
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

    [Fact]
    public async Task SelfAnnouncements_NeverAppearAsNearbyPeer()
    {
        var a = new LanDiscoveryService(
            DeviceA, port: 0, announceIntervalMs: 100, broadcastAddress: IPAddress.Loopback);
        // Announce to self: loopback copy of own announcements arrives, but
        // device-ID filtering must drop them.
        a.Start();
        try
        {
            await Task.Delay(600);
            Assert.DoesNotContain(a.Snapshot(), p => p.Record.DeviceId == DeviceA.DeviceId);
        }
        finally
        {
            await a.StopAsync();
            a.Dispose();
        }
    }

    [Fact]
    public async Task MalformedUdpInput_DoesNotCrashDiscovery()
    {
        var a = new LanDiscoveryService(DeviceA, port: 0, broadcastAddress: IPAddress.Loopback);
        a.Start();
        try
        {
            using var sender = new UdpClient();
            int target = a.LocalPort;
            var garbage = new[]
            {
                Encoding.UTF8.GetBytes("{not json"),
                Encoding.ASCII.GetBytes(new string('z', 5000)),
                new byte[] { 0xFF, 0x00, 0x01 },
                Encoding.UTF8.GetBytes("{\"protocol\":\"localdrop\",\"protocolVersion\":99}"),
            };
            foreach (var g in garbage)
                await sender.SendAsync(g, new IPEndPoint(IPAddress.Loopback, target));
            await Task.Delay(400);
            Assert.True(a.IsRunning);
            Assert.Empty(a.Snapshot());
        }
        finally
        {
            await a.StopAsync();
            a.Dispose();
        }
    }

    [Fact]
    public async Task Stop_CancelsBlockingReceivePromptly()
    {
        var a = new LanDiscoveryService(DeviceA, port: 0, broadcastAddress: IPAddress.Loopback);
        a.Start();
        var start = DateTimeOffset.UtcNow;
        await a.StopAsync();
        var elapsed = DateTimeOffset.UtcNow - start;
        Assert.True(elapsed < TimeSpan.FromSeconds(4), $"stop took {elapsed}, expected prompt shutdown");
        Assert.False(a.IsRunning);
        a.Dispose();
    }
}
