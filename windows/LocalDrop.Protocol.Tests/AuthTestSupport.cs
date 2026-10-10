// Shared wiring for M05 auth tests: full-duplex M04 channels with AuthManager
// on both ends, plus raw-socket peers driving evil handshakes. Loopback only.
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;

namespace LocalDrop.Protocol.Tests;

internal static class AuthTestSupport
{
    internal static DeviceInfo DeviceFor(SoftwareDeviceIdentityStore store, string name) =>
        new(store.Identity().DeviceId, name, "windows");

    internal sealed class AuthEventsCapture : IAuthEvents
    {
        public ConcurrentQueue<(string ConnectionId, VerifiedPeer Peer)> Authenticated { get; } = new();
        public ConcurrentQueue<string> Failed { get; } = new();
        public void OnAuthenticated(ControlConnection connection, VerifiedPeer peer) =>
            Authenticated.Enqueue((connection.Id, peer));
        public void OnAuthFailed(ControlConnection connection, string reason) =>
            Failed.Enqueue(connection.Id);

        public async Task<(string ConnectionId, VerifiedPeer Peer)> AwaitAuthenticatedAsync(int timeoutMs = 8000)
        {
            var deadline = DateTimeOffset.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTimeOffset.UtcNow < deadline)
            {
                if (Authenticated.TryDequeue(out var item)) return item;
                await Task.Delay(25);
            }
            throw new Xunit.Sdk.XunitException("timed out waiting for authentication");
        }
    }

    internal sealed class AuthedPair : IDisposable
    {
        public required ControlServer Server { get; init; }
        public required AuthManager ServerAuth { get; init; }
        public required AuthEventsCapture ServerEvents { get; init; }
        public required SoftwareDeviceIdentityStore ServerStore { get; init; }
        public required ControlConnection Client { get; init; }
        public required AuthManager ClientAuth { get; init; }
        public required AuthEventsCapture ClientEvents { get; init; }
        public required SoftwareDeviceIdentityStore ClientStore { get; init; }
        public required RecordingSecurityLogger ServerLog { get; init; }
        public required RecordingSecurityLogger ClientLog { get; init; }

        public void Dispose()
        {
            try { Client.DisconnectAsync().GetAwaiter().GetResult(); } catch { }
            try { ClientStore.Dispose(); } catch { }
            try { ServerStore.Dispose(); } catch { }
            try { Server.StopAsync().GetAwaiter().GetResult(); } catch { }
            try { Server.Dispose(); } catch { }
        }
    }

    // Establishes M04 both ways but performs NO authentication yet.
    internal static AuthedPair ConnectedPair(
        SoftwareDeviceIdentityStore? serverStore = null,
        SoftwareDeviceIdentityStore? clientStore = null,
        int authTimeoutMs = 8000)
    {
        serverStore ??= new SoftwareDeviceIdentityStore();
        clientStore ??= new SoftwareDeviceIdentityStore();
        var serverLog = new RecordingSecurityLogger();
        var clientLog = new RecordingSecurityLogger();
        var serverDevice = DeviceFor(serverStore, "PcB");
        var clientDevice = DeviceFor(clientStore, "PhoneA");
        var serverEvents = new AuthEventsCapture();
        var clientEvents = new AuthEventsCapture();
        var serverAuth = new AuthManager(serverDevice, serverStore, serverLog, authTimeoutMs, events: serverEvents);
        var server = new ControlServer(serverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        var clientAuth = new AuthManager(clientDevice, clientStore, clientLog, authTimeoutMs, events: clientEvents);
        var client = new ControlConnection(clientDevice, clientAuth);
        if (!client.ConnectAsync("127.0.0.1", server.LocalPort).GetAwaiter().GetResult())
            throw new Xunit.Sdk.XunitException("M04 connect failed");
        return new AuthedPair
        {
            Server = server,
            ServerAuth = serverAuth,
            ServerEvents = serverEvents,
            ServerStore = serverStore,
            Client = client,
            ClientAuth = clientAuth,
            ClientEvents = clientEvents,
            ClientStore = clientStore,
            ServerLog = serverLog,
            ClientLog = clientLog,
        };
    }

    // Raw socket that completed the M04 handshake; caller drives auth bytes.
    internal sealed class RawPeer : IDisposable
    {
        public TcpClient Socket { get; }
        public DeviceInfo Device { get; }
        public SoftwareDeviceIdentityStore Store { get; }

        public RawPeer(string host, int port, SoftwareDeviceIdentityStore store, string name)
        {
            Store = store;
            Device = DeviceFor(store, name);
            Socket = new TcpClient();
            Socket.Connect(host, port);
            Socket.Client.ReceiveTimeout = 8000;
            var hello = ControlTestSupport.RawHandshakeAsync(Socket, Device).GetAwaiter().GetResult();
            if (hello is null) throw new Xunit.Sdk.XunitException("M04 handshake failed");
            // rawHandshake consumes the server HELLO but leaves the server
            // HELLO_ACK unread; drain it so auth reads align with auth writes.
            var leftover = ReadMessage(5000);
            if (leftover?.Type != MessageType.HELLO_ACK)
                throw new Xunit.Sdk.XunitException($"expected M04 HELLO_ACK, got {leftover?.Type}");
        }

        public void SendAuthRequest(string token, string method = Core.AppConfig.AuthMethodId)
        {
            var message = new LocalDropMessage(
                MessageType.AUTH_REQUEST,
                ProtocolCodec.NewMessageId(),
                null, null, null,
                new TypedPayload.AuthRequest(new AuthRequestPayload(Device.DeviceId, method, token)));
            byte[] frame = FrameCodec.EncodeMessage(message);
            Socket.GetStream().Write(frame);
            Socket.GetStream().Flush();
        }

        public LocalDropMessage? ReadMessage(int timeoutMs = 5000)
        {
            byte[]? body = ControlTestSupport.ReadRawFrameAsync(Socket.GetStream(), timeoutMs)
                .GetAwaiter().GetResult();
            if (body is null) return null;
            return ProtocolCodec.DecodeUtf8(body) is DecodeOutcome.Success ok ? ok.Message : null;
        }

        public (LocalDropMessage First, LocalDropMessage Second) ReadHelloAndProof()
        {
            var first = ReadMessage() ?? throw new Xunit.Sdk.XunitException("no server hello");
            var second = ReadMessage() ?? throw new Xunit.Sdk.XunitException("no server proof");
            if (first.Type != MessageType.AUTH_REQUEST || second.Type != MessageType.AUTH_REQUEST)
                throw new Xunit.Sdk.XunitException($"unexpected auth messages: {first.Type}, {second.Type}");
            return (first, second);
        }

        public void Dispose()
        {
            try { Socket.Dispose(); } catch { }
        }
    }

    internal static async Task<AuthOutcome> AwaitOutcomeAsync(Task<AuthOutcome> future, int timeoutMs = 10000)
    {
        try
        {
            return await future.WaitAsync(TimeSpan.FromMilliseconds(timeoutMs));
        }
        catch (Exception ex)
        {
            throw new Xunit.Sdk.XunitException($"timed out waiting for auth outcome: {ex.Message}");
        }
    }

    internal static async Task WaitForAsync(string description, Func<bool> condition, int timeoutMs = 8000)
    {
        var deadline = DateTimeOffset.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (condition()) return;
            await Task.Delay(50);
        }
        throw new Xunit.Sdk.XunitException($"timed out waiting for: {description}");
    }
}
