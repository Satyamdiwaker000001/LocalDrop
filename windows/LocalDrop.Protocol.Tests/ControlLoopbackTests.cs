// M04 §24 (items 1-4, 7, 8, 14-19, 21-loopback): connection, handshake,
// message exchange, correlation, concurrency, disconnect, cancel, restart.
using LocalDrop.Discovery;
using LocalDrop.Network;

namespace LocalDrop.Protocol.Tests;

public sealed class ControlLoopbackTests
{
    [Fact]
    public async Task ConnectionSucceeds_HandshakeCompletesBothSides()
    {
        var serverEvents = new ControlTestSupport.Capture();
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0, listenerFactory: () => serverEvents);
        server.Start();
        try
        {
            var clientEvents = new ControlTestSupport.Capture();
            var client = new ControlConnection(ControlTestSupport.DeviceA, clientEvents);
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            Assert.True(client.IsReady);
            Assert.Equal(ControlState.READY, client.State);
            await ControlTestSupport.WaitForAsync("server side ready",
                () => server.Connections.Snapshot().Any(c => c.IsReady));
            var accepted = server.Connections.Snapshot().First(c => c.IsReady);
            Assert.Equal(ControlTestSupport.DeviceA.DeviceId, accepted.PeerDeviceId);
            Assert.Equal(ControlTestSupport.DeviceB.DeviceId, client.PeerDeviceId);
            await client.DisconnectAsync();
            var close = await clientEvents.AwaitCloseAsync();
            Assert.Equal(CloseReason.LOCAL_DISCONNECT, close.Reason);
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task ConnectionRefused_FailsCleanly()
    {
        var probe = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        probe.Start();
        int closedPort = ((System.Net.IPEndPoint)probe.LocalEndpoint).Port;
        probe.Stop();
        var events = new ControlTestSupport.Capture();
        var client = new ControlConnection(ControlTestSupport.DeviceA, events);
        var start = DateTimeOffset.UtcNow;
        Assert.False(await client.ConnectAsync("127.0.0.1", closedPort));
        var elapsed = DateTimeOffset.UtcNow - start;
        Assert.True(elapsed < TimeSpan.FromSeconds(15), $"refused should fail fast, took {elapsed}");
        var close = await events.AwaitCloseAsync();
        Assert.Equal(CloseReason.CONNECT_FAILED, close.Reason);
        Assert.Equal(ControlState.FAILED, client.State);
    }

    [Fact]
    public async Task ConnectionTimeout_IsBounded()
    {
        // TEST-NET-1 (RFC 5737): must not route anywhere; ConnectAsync must
        // give up within budget instead of blocking forever.
        var events = new ControlTestSupport.Capture();
        var client = new ControlConnection(
            ControlTestSupport.DeviceA, events, connectTimeoutMs: 1500);
        var start = DateTimeOffset.UtcNow;
        Assert.False(await client.ConnectAsync("192.0.2.1", 48424));
        var elapsed = DateTimeOffset.UtcNow - start;
        Assert.True(elapsed < TimeSpan.FromSeconds(20), $"connect must be bounded, took {elapsed}");
        var close = await events.AwaitCloseAsync();
        Assert.Equal(CloseReason.CONNECT_FAILED, close.Reason);
    }

    [Fact]
    public async Task ValidMessage_TransmittedAndReceivedBothDirections()
    {
        var serverEvents = new ControlTestSupport.Capture();
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0, listenerFactory: () => serverEvents);
        server.Start();
        try
        {
            var clientEvents = new ControlTestSupport.Capture();
            var client = new ControlConnection(ControlTestSupport.DeviceA, clientEvents);
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            try
            {
                Assert.True(await client.SendAsync(ControlTestSupport.PauseMessage(ControlTestSupport.Tid)));
                var atServer = await serverEvents.AwaitMessageAsync();
                Assert.Equal(MessageType.PAUSE, atServer.Type);
                Assert.Equal(ControlTestSupport.Tid,
                    Assert.IsType<TypedPayload.Pause>(atServer.Payload).V.TransferId);
                var accepted = server.Connections.Snapshot().First(c => c.IsReady);
                Assert.True(await accepted.SendAsync(ControlTestSupport.CancelMessage(ControlTestSupport.Tid)));
                var atClient = await clientEvents.AwaitMessageAsync();
                Assert.Equal(MessageType.CANCEL, atClient.Type);
            }
            finally
            {
                await client.DisconnectAsync();
            }
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task RequestResponse_CorrelationMatches()
    {
        var serverEvents = new ControlTestSupport.Capture();
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0, listenerFactory: () => serverEvents);
        server.Start();
        try
        {
            var client = new ControlConnection(ControlTestSupport.DeviceA);
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            try
            {
                var request = ControlTestSupport.PauseMessage(ControlTestSupport.Tid);
                // Server side: reply with correlated ERROR once request lands.
                var replier = Task.Run(async () =>
                {
                    var incoming = await serverEvents.AwaitMessageAsync(8000);
                    var accepted = server.Connections.Snapshot().First(c => c.IsReady);
                    await accepted.SendAsync(ControlTestSupport.ErrorReply(
                        incoming, ErrorCode.TRANSFER_REJECTED, "nope"));
                });
                var response = await client.SendAndReceiveAsync(request, 8000);
                Assert.NotNull(response);
                Assert.Equal(MessageType.ERROR, response!.Type);
                Assert.Equal(request.MessageId, response.CorrelationId);
                Assert.Equal(ErrorCode.TRANSFER_REJECTED,
                    Assert.IsType<TypedPayload.Error>(response.Payload).V.Error.Code);
                await replier;
            }
            finally
            {
                await client.DisconnectAsync();
            }
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task ConcurrentSends_CannotInterleaveFrames()
    {
        var serverEvents = new ControlTestSupport.Capture();
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0, listenerFactory: () => serverEvents);
        server.Start();
        try
        {
            var client = new ControlConnection(ControlTestSupport.DeviceA);
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            try
            {
                const int threads = 8;
                const int perThread = 25;
                int sent = 0;
                var tasks = Enumerable.Range(0, threads).Select(t => Task.Run(async () =>
                {
                    for (int i = 0; i < perThread; i++)
                    {
                        string transferId = $"{t:X8}-0000-4000-8000-{(t * 1000 + i):X12}";
                        if (await client.SendAsync(ControlTestSupport.PauseMessage(transferId)))
                            Interlocked.Increment(ref sent);
                    }
                })).ToArray();
                await Task.WhenAll(tasks).WaitAsync(TimeSpan.FromSeconds(20));
                Assert.Equal(threads * perThread, sent);
                var seen = new HashSet<string>();
                var deadline = DateTimeOffset.UtcNow.AddSeconds(15);
                while (seen.Count < threads * perThread && DateTimeOffset.UtcNow < deadline)
                {
                    while (serverEvents.Messages.TryDequeue(out var m))
                        seen.Add(Assert.IsType<TypedPayload.Pause>(m.Payload).V.TransferId);
                    if (seen.Count < threads * perThread) await Task.Delay(50);
                }
                Assert.Equal(threads * perThread, seen.Count);
            }
            finally
            {
                await client.DisconnectAsync();
            }
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task GracefulDisconnect_IsIdempotent()
    {
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0);
        server.Start();
        try
        {
            var events = new ControlTestSupport.Capture();
            var client = new ControlConnection(ControlTestSupport.DeviceA, events);
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            await client.DisconnectAsync();
            await client.DisconnectAsync(); // second call must not crash
            Assert.Equal(ControlState.DISCONNECTED, client.State);
            var close = await events.AwaitCloseAsync();
            Assert.Equal(CloseReason.LOCAL_DISCONNECT, close.Reason);
            Assert.True(events.Closes.IsEmpty);
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task RemoteDisconnect_IsReported()
    {
        var serverEvents = new ControlTestSupport.Capture();
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0, listenerFactory: () => serverEvents);
        server.Start();
        try
        {
            var client = new ControlConnection(ControlTestSupport.DeviceA);
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            // Abrupt close from the client side (no graceful message).
            await client.DisconnectAsync();
            var close = await serverEvents.AwaitCloseAsync();
            Assert.True(close.Reason is CloseReason.REMOTE_CLOSE or CloseReason.PROTOCOL_VIOLATION);
            await ControlTestSupport.WaitForAsync("server drops the connection",
                () => server.Connections.Snapshot().Count == 0);
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task Cancellation_UnblocksPendingRequest()
    {
        // Null factory: server never replies.
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0, listenerFactory: () => null);
        server.Start();
        try
        {
            var client = new ControlConnection(ControlTestSupport.DeviceA);
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            try
            {
                var request = ControlTestSupport.PauseMessage(ControlTestSupport.Tid);
                var canceller = Task.Run(async () =>
                {
                    await Task.Delay(400);
                    await client.DisconnectAsync();
                });
                var start = DateTimeOffset.UtcNow;
                Assert.Null(await client.SendAndReceiveAsync(request, 10000));
                var elapsed = DateTimeOffset.UtcNow - start;
                Assert.True(elapsed < TimeSpan.FromSeconds(8), $"cancel should unblock promptly, took {elapsed}");
                await canceller;
            }
            finally
            {
                await client.DisconnectAsync();
            }
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task ControlChannel_RestartsAndReconnects()
    {
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0);
        server.Start();
        var client = new ControlConnection(ControlTestSupport.DeviceA);
        Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
        await client.DisconnectAsync();
        await server.StopAsync();
        server = new ControlServer(ControlTestSupport.DeviceB, port: 0);
        server.Start();
        try
        {
            var client2 = new ControlConnection(ControlTestSupport.DeviceA);
            Assert.True(await client2.ConnectAsync("127.0.0.1", server.LocalPort));
            Assert.True(client2.IsReady);
            await client2.DisconnectAsync();
        }
        finally
        {
            await server.StopAsync();
        }
    }
}
