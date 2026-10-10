// M04 §24 (items 5, 6, 9-13, 17-raw): handshake rejection paths and TCP
// stream edge cases against raw sockets. Loopback only.
using System.Net;
using System.Net.Sockets;
using System.Text;
using LocalDrop.Network;

namespace LocalDrop.Protocol.Tests;

public sealed class ControlFramingTests
{
    [Fact]
    public async Task IncompatibleProtocolVersion_Rejected()
    {
        var events = new ControlTestSupport.Capture();
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0, listenerFactory: () => events);
        server.Start();
        try
        {
            using var raw = new TcpClient();
            await raw.ConnectAsync(IPAddress.Loopback, server.LocalPort);
            // Well-formed frame, but protocolVersion 2.
            string bad = "{\"protocol\":\"localdrop\",\"protocolVersion\":2," +
                "\"type\":\"HELLO\",\"messageId\":\"11111111-1111-4111-8111-111111111111\"," +
                $"\"payload\":{{\"deviceId\":\"{ControlTestSupport.DeviceA.DeviceId}\"," +
                "\"deviceName\":\"X\",\"deviceType\":\"android\"," +
                "\"controlPort\":0,\"capabilities\":[]}}";
            var frame = FrameCodec.Encode(Encoding.UTF8.GetBytes(bad));
            await raw.GetStream().WriteAsync(frame);
            await raw.GetStream().FlushAsync();
            var close = await events.AwaitCloseAsync();
            Assert.Equal(CloseReason.PROTOCOL_VIOLATION, close.Reason);
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task MalformedHandshakeBytes_RejectedWithoutCrash()
    {
        var events = new ControlTestSupport.Capture();
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0, listenerFactory: () => events);
        server.Start();
        try
        {
            using var raw = new TcpClient();
            await raw.ConnectAsync(IPAddress.Loopback, server.LocalPort);
            var garbage = Encoding.UTF8.GetBytes("definitely not a frame");
            await raw.GetStream().WriteAsync(garbage);
            await raw.GetStream().FlushAsync();
            var close = await events.AwaitCloseAsync();
            Assert.Equal(CloseReason.PROTOCOL_VIOLATION, close.Reason);
            Assert.True(server.IsRunning);
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task HandshakeTimeout_WhenPeerStaysSilent()
    {
        var acceptor = new TcpListener(IPAddress.Loopback, 0);
        acceptor.Start();
        try
        {
            int port = ((IPEndPoint)acceptor.LocalEndpoint).Port;
            var clientEvents = new ControlTestSupport.Capture();
            var client = new ControlConnection(
                ControlTestSupport.DeviceA, clientEvents, handshakeTimeoutMs: 1000);
            var connectTask = client.ConnectAsync("127.0.0.1", port);
            // Accept but never speak: client must time out, not hang.
            using var peer = await acceptor.AcceptTcpClientAsync().WaitAsync(TimeSpan.FromSeconds(10));
            Assert.False(await connectTask.WaitAsync(TimeSpan.FromSeconds(15)));
            Assert.False(client.IsReady);
            var close = await clientEvents.AwaitCloseAsync();
            Assert.Equal(CloseReason.HANDSHAKE_TIMEOUT, close.Reason);
        }
        finally
        {
            acceptor.Stop();
        }
    }

    [Fact]
    public async Task PartialTcpFrame_Reassembled()
    {
        var acceptor = new TcpListener(IPAddress.Loopback, 0);
        acceptor.Start();
        try
        {
            int port = ((IPEndPoint)acceptor.LocalEndpoint).Port;
            var clientEvents = new ControlTestSupport.Capture();
            var client = new ControlConnection(ControlTestSupport.DeviceA, clientEvents);
            var connectTask = client.ConnectAsync("127.0.0.1", port);
            using var raw = await acceptor.AcceptTcpClientAsync().WaitAsync(TimeSpan.FromSeconds(10));
            raw.Client.ReceiveTimeout = 8000;
            Assert.NotNull(await ControlTestSupport.RawHandshakeAsync(raw, ControlTestSupport.DeviceB));
            Assert.True(await connectTask.WaitAsync(TimeSpan.FromSeconds(10)));
            Assert.True(client.IsReady);
            // Deliver one frame one byte at a time.
            var frame = FrameCodec.EncodeMessage(ControlTestSupport.PauseMessage(ControlTestSupport.Tid));
            var stream = raw.GetStream();
            foreach (byte b in frame)
            {
                await stream.WriteAsync(new byte[] { b });
                await stream.FlushAsync();
                await Task.Delay(2);
            }
            var received = await clientEvents.AwaitMessageAsync(8000);
            Assert.Equal(MessageType.PAUSE, received.Type);
            Assert.Equal(ControlTestSupport.Tid,
                Assert.IsType<TypedPayload.Pause>(received.Payload).V.TransferId);
            await client.DisconnectAsync();
        }
        finally
        {
            acceptor.Stop();
        }
    }

    [Fact]
    public async Task MultipleFramesInOneTcpRead_AllReceived()
    {
        var acceptor = new TcpListener(IPAddress.Loopback, 0);
        acceptor.Start();
        try
        {
            int port = ((IPEndPoint)acceptor.LocalEndpoint).Port;
            var clientEvents = new ControlTestSupport.Capture();
            var client = new ControlConnection(ControlTestSupport.DeviceA, clientEvents);
            var connectTask = client.ConnectAsync("127.0.0.1", port);
            using var raw = await acceptor.AcceptTcpClientAsync().WaitAsync(TimeSpan.FromSeconds(10));
            raw.Client.ReceiveTimeout = 8000;
            Assert.NotNull(await ControlTestSupport.RawHandshakeAsync(raw, ControlTestSupport.DeviceB));
            Assert.True(await connectTask.WaitAsync(TimeSpan.FromSeconds(10)));
            Assert.True(client.IsReady);
            // Three frames concatenated in a single write.
            var combined = FrameCodec.EncodeMessage(ControlTestSupport.PauseMessage(ControlTestSupport.Tid))
                .Concat(FrameCodec.EncodeMessage(ControlTestSupport.CancelMessage(ControlTestSupport.Tid)))
                .Concat(FrameCodec.EncodeMessage(ControlTestSupport.PauseMessage(ControlTestSupport.Tid)))
                .ToArray();
            await raw.GetStream().WriteAsync(combined);
            await raw.GetStream().FlushAsync();
            var types = new List<MessageType>();
            for (int i = 0; i < 3; i++) types.Add((await clientEvents.AwaitMessageAsync(8000)).Type);
            Assert.Equal([MessageType.PAUSE, MessageType.CANCEL, MessageType.PAUSE], types);
            await client.DisconnectAsync();
        }
        finally
        {
            acceptor.Stop();
        }
    }

    [Fact]
    public async Task OversizedFrame_RejectedWithoutAllocation()
    {
        var events = new ControlTestSupport.Capture();
        var server = new ControlServer(ControlTestSupport.DeviceB, port: 0, listenerFactory: () => events);
        server.Start();
        try
        {
            using var raw = new TcpClient();
            await raw.ConnectAsync(IPAddress.Loopback, server.LocalPort);
            // Declare >1MiB; send almost no body. Decoder must fail on
            // the prefix without allocating the declared length.
            int claim = Protocol.MaxFrameBytes + 1;
            byte[] prefix = [(byte)(claim >> 24), (byte)(claim >> 16), (byte)(claim >> 8), (byte)claim];
            await raw.GetStream().WriteAsync(prefix.Concat(new byte[16]).ToArray());
            await raw.GetStream().FlushAsync();
            var close = await events.AwaitCloseAsync();
            Assert.Equal(CloseReason.PROTOCOL_VIOLATION, close.Reason);
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public void ControlPort_DiffersFromDiscoveryPort()
    {
        Assert.NotEqual(Core.AppConfig.ControlTcpPort, Core.AppConfig.DiscoveryUdpPort);
        Assert.Equal(48424, Core.AppConfig.ControlTcpPort);
    }
}
