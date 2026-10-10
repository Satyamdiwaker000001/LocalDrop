// M04 §16/§17/§20: server lifecycle, independent per-peer connections,
// tracking cleanup, rebind after stop.
using LocalDrop.Discovery;
using LocalDrop.Network;

namespace LocalDrop.Protocol.Tests;

public sealed class ControlServerTests
{
    [Fact]
    public async Task ServerStartStop_LifecycleIsIdempotent()
    {
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0);
        server.Start();
        Assert.True(server.IsRunning);
        Assert.True(server.LocalPort > 0);
        server.Start(); // no-op, no duplicate accept loop
        Assert.True(server.IsRunning);
        await server.StopAsync();
        await server.StopAsync(); // no-op, no crash
    }

    [Fact]
    public async Task MultiplePeerConnections_AreIndependent()
    {
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0);
        server.Start();
        try
        {
            int port = server.LocalPort;
            var c1 = new ControlConnection(ControlTestSupport.DeviceA);
            var deviceC = new DeviceInfo("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "PcC", "windows");
            var c2 = new ControlConnection(deviceC);
            Assert.True(await c1.ConnectAsync("127.0.0.1", port));
            Assert.True(await c2.ConnectAsync("127.0.0.1", port));
            await ControlTestSupport.WaitForAsync("both accepted",
                () => server.Connections.Snapshot().Count == 2);
            // Kill one connection; the other must stay READY and usable.
            await c1.DisconnectAsync();
            await ControlTestSupport.WaitForAsync("one remains",
                () => server.Connections.Snapshot().Count == 1);
            var survivor = server.Connections.Snapshot().Single();
            Assert.True(survivor.IsReady);
            Assert.True(c2.IsReady);
            Assert.True(await c2.SendAsync(
                ControlTestSupport.PauseMessage(ControlTestSupport.Tid)));
            await c2.DisconnectAsync();
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task FailedHandshake_DoesNotAffectHealthyConnections()
    {
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0);
        server.Start();
        try
        {
            int port = server.LocalPort;
            var good = new ControlConnection(ControlTestSupport.DeviceA);
            Assert.True(await good.ConnectAsync("127.0.0.1", port));
            // A raw socket that sends garbage during handshake.
            using var raw = new System.Net.Sockets.TcpClient();
            await raw.ConnectAsync("127.0.0.1", port);
            var garbage = System.Text.Encoding.UTF8.GetBytes("garbage-bytes");
            await raw.GetStream().WriteAsync(garbage);
            await raw.GetStream().FlushAsync();
            await ControlTestSupport.WaitForAsync("bad connection dropped",
                () => server.Connections.Snapshot().Count == 1);
            Assert.True(good.IsReady);
            Assert.True(await good.SendAsync(
                ControlTestSupport.PauseMessage(ControlTestSupport.Tid)));
            await good.DisconnectAsync();
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task Manager_TracksRekeysAndClosesAll()
    {
        var manager = new ControlConnections();
        var c1 = new ControlConnection(ControlTestSupport.DeviceA);
        var c2 = new ControlConnection(ControlTestSupport.DeviceB);
        manager.Add(c1.Id, c1);
        manager.Add(c2.Id, c2);
        Assert.Equal(2, manager.Count);
        manager.Rekey(c1.Id, ControlTestSupport.DeviceA.DeviceId);
        Assert.Same(c1, manager.Get(ControlTestSupport.DeviceA.DeviceId));
        manager.Remove(c2.Id);
        Assert.Equal(1, manager.Count);
        await manager.CloseAllAsync();
        Assert.Equal(0, manager.Count);
    }

    [Fact]
    public async Task Server_RebindsAfterStop()
    {
        var first = new ControlServer(ControlTestSupport.DeviceB, port: 0);
        first.Start();
        await first.StopAsync();
        var second = new ControlServer(ControlTestSupport.DeviceB, port: 0);
        second.Start();
        try
        {
            var client = new ControlConnection(ControlTestSupport.DeviceA);
            Assert.True(await client.ConnectAsync("127.0.0.1", second.LocalPort));
            await client.DisconnectAsync();
        }
        finally
        {
            await second.StopAsync();
        }
    }
}
