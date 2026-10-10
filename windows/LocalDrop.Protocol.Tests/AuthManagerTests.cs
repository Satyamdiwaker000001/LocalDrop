// M05 tests 3-4 (+22/23 cleanup): mutual auth success/failure over live M04
// channels, fail-closed behavior, timeouts, cancellation.
using LocalDrop.Network;
using LocalDrop.Security;

namespace LocalDrop.Protocol.Tests;

public sealed class AuthManagerTests
{
    [Fact]
    public async Task MutualAuthentication_SucceedsBothDirections()
    {
        using var pair = AuthTestSupport.ConnectedPair();
        try
        {
            var future = pair.ClientAuth.BeginAuthAsync(pair.Client);
            // Server side authenticates automatically (responder path).
            var clientOutcome = await AuthTestSupport.AwaitOutcomeAsync(future);
            var authenticated = Assert.IsType<AuthOutcome.Authenticated>(clientOutcome);
            Assert.Equal(pair.ServerStore.Identity().DeviceId, authenticated.Peer.DeviceId);
            Assert.True(authenticated.Peer.PublicKeySpki.AsSpan()
                .SequenceEqual(pair.ServerStore.Identity().PublicKeySpki));

            var (serverConnId, serverPeer) = await pair.ServerEvents.AwaitAuthenticatedAsync();
            Assert.Equal(pair.ClientStore.Identity().DeviceId, serverPeer.DeviceId);

            Assert.True(pair.ClientAuth.IsAuthenticated(pair.Client));
            Assert.Equal(authenticated.Peer, pair.ClientAuth.VerifiedPeer(pair.Client));
            var serverConn = pair.Server.Connections.Snapshot().First(c => c.Id == serverConnId);
            Assert.True(pair.ServerAuth.IsAuthenticated(serverConn));
        }
        finally
        {
            await pair.Client.DisconnectAsync();
            await pair.Server.StopAsync();
        }
    }

    [Fact]
    public async Task ForgedProofSignature_IsRejectedAndConnectionDropped()
    {
        using var serverStore = new SoftwareDeviceIdentityStore();
        using var forgerStore = new SoftwareDeviceIdentityStore();
        using var otherKey = new SoftwareDeviceIdentityStore();
        var serverLog = new RecordingSecurityLogger();
        var serverEvents = new AuthTestSupport.AuthEventsCapture();
        var serverDevice = AuthTestSupport.DeviceFor(serverStore, "PcB");
        var serverAuth = new AuthManager(serverDevice, serverStore, serverLog, events: serverEvents);
        var server = new ControlServer(serverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        try
        {
            using var raw = new AuthTestSupport.RawPeer("127.0.0.1", server.LocalPort, forgerStore, "Evil");
            // Valid hello (own true identity), then a proof signed with a
            // DIFFERENT key over the correct transcript.
            var myId = forgerStore.Identity();
            byte[] myNonce = AuthProtocol.NewNonce();
            raw.SendAuthRequest(AuthProtocol.HelloToken(myId.DeviceId, myId.PublicKeySpki, myNonce));
            var (serverHello, _) = raw.ReadHelloAndProof();
            var serverToken = Assert.IsType<TypedPayload.AuthRequest>(serverHello.Payload).V.Token;
            var serverParsed = Assert.IsType<PayloadResult<AuthProtocol.ParsedHello>.Ok>(
                AuthProtocol.ParseHello(serverToken));
            byte[] transcript = AuthProtocol.BuildTranscript(
                Core.AppConfig.AuthMethodId, myId.DeviceId, myNonce,
                serverStore.Identity().DeviceId, serverParsed.Value.Nonce);
            byte[] forged = otherKey.Sign(transcript); // wrong key!
            raw.SendAuthRequest(AuthProtocol.ProofToken(forged));
            // Server must reject (AUTH_RESPONSE false) and drop the channel.
            var response = raw.ReadMessage();
            Assert.NotNull(response);
            Assert.Equal(MessageType.AUTH_RESPONSE, response!.Type);
            Assert.False(Assert.IsType<TypedPayload.AuthResponse>(response.Payload).V.Accepted);
            await AuthTestSupport.WaitForAsync("server drops forger",
                () => server.Connections.Snapshot().Count == 0);
            // Nothing was ever verified on the server side.
            Assert.True(serverEvents.Authenticated.IsEmpty);
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task UnknownAuthMethod_IsRejected()
    {
        using var serverStore = new SoftwareDeviceIdentityStore();
        using var evilStore = new SoftwareDeviceIdentityStore();
        var serverDevice = AuthTestSupport.DeviceFor(serverStore, "PcB");
        var serverAuth = new AuthManager(serverDevice, serverStore, new RecordingSecurityLogger());
        var server = new ControlServer(serverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        try
        {
            using var raw = new AuthTestSupport.RawPeer("127.0.0.1", server.LocalPort, evilStore, "Evil");
            var myId = evilStore.Identity();
            raw.SendAuthRequest(
                AuthProtocol.HelloToken(myId.DeviceId, myId.PublicKeySpki, AuthProtocol.NewNonce()),
                method: "bogus-method");
            var response = raw.ReadMessage();
            Assert.NotNull(response);
            Assert.Equal(MessageType.AUTH_RESPONSE, response!.Type);
            Assert.False(Assert.IsType<TypedPayload.AuthResponse>(response.Payload).V.Accepted);
            await AuthTestSupport.WaitForAsync("server drops unknown method",
                () => server.Connections.Snapshot().Count == 0);
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task DeviceIdBindingMismatch_IsRejected()
    {
        using var serverStore = new SoftwareDeviceIdentityStore();
        using var evilStore = new SoftwareDeviceIdentityStore();
        var serverDevice = AuthTestSupport.DeviceFor(serverStore, "PcB");
        var serverAuth = new AuthManager(serverDevice, serverStore, new RecordingSecurityLogger());
        var server = new ControlServer(serverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        try
        {
            using var raw = new AuthTestSupport.RawPeer("127.0.0.1", server.LocalPort, evilStore, "Evil");
            var myId = evilStore.Identity();
            // Claim a deviceId that is NOT derived from our public key.
            raw.SendAuthRequest(AuthProtocol.HelloToken(
                "ffffffff-ffff-4fff-8fff-ffffffffffff", myId.PublicKeySpki, AuthProtocol.NewNonce()));
            var response = raw.ReadMessage();
            Assert.NotNull(response);
            Assert.Equal(MessageType.AUTH_RESPONSE, response!.Type);
            Assert.False(Assert.IsType<TypedPayload.AuthResponse>(response.Payload).V.Accepted);
            await AuthTestSupport.WaitForAsync("server drops binding mismatch",
                () => server.Connections.Snapshot().Count == 0);
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task DuplicateHello_IsRejected()
    {
        using var serverStore = new SoftwareDeviceIdentityStore();
        using var evilStore = new SoftwareDeviceIdentityStore();
        var serverDevice = AuthTestSupport.DeviceFor(serverStore, "PcB");
        var serverAuth = new AuthManager(serverDevice, serverStore, new RecordingSecurityLogger());
        var server = new ControlServer(serverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        try
        {
            using var raw = new AuthTestSupport.RawPeer("127.0.0.1", server.LocalPort, evilStore, "Evil");
            var myId = evilStore.Identity();
            string token = AuthProtocol.HelloToken(myId.DeviceId, myId.PublicKeySpki, AuthProtocol.NewNonce());
            raw.SendAuthRequest(token);
            raw.ReadHelloAndProof(); // drain server hello+proof
            raw.SendAuthRequest(token); // duplicate
            var response = raw.ReadMessage();
            Assert.NotNull(response);
            Assert.Equal(MessageType.AUTH_RESPONSE, response!.Type);
            Assert.False(Assert.IsType<TypedPayload.AuthResponse>(response.Payload).V.Accepted);
            await AuthTestSupport.WaitForAsync("server drops duplicate",
                () => server.Connections.Snapshot().Count == 0);
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task SilentPeer_TriggersAuthTimeoutAndCleanup()
    {
        // Server completes M04 but never answers AUTH (no AuthManager wired).
        var serverDevice = AuthTestSupport.DeviceFor(new SoftwareDeviceIdentityStore(), "PcB");
        var server = new ControlServer(serverDevice, port: 0, listenerFactory: () => null);
        server.Start();
        try
        {
            using var clientStore = new SoftwareDeviceIdentityStore();
            var clientDevice = AuthTestSupport.DeviceFor(clientStore, "PhoneA");
            var log = new RecordingSecurityLogger();
            var clientAuth = new AuthManager(clientDevice, clientStore, log, authTimeoutMs: 1000);
            var client = new ControlConnection(clientDevice, clientAuth);
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            var outcome = await AuthTestSupport.AwaitOutcomeAsync(clientAuth.BeginAuthAsync(client), 8000);
            Assert.IsType<AuthOutcome.Failed>(outcome);
            await AuthTestSupport.WaitForAsync("client channel cleaned up",
                () => client.State == ControlState.DISCONNECTED || client.State == ControlState.FAILED);
            Assert.False(clientAuth.IsAuthenticated(client));
            await client.DisconnectAsync();
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task DisconnectDuringAuth_CompletesFutureAndCleansUp()
    {
        using var pair = AuthTestSupport.ConnectedPair(authTimeoutMs: 15000);
        try
        {
            // Server will never answer: stop it mid-handshake after hello.
            var future = pair.ClientAuth.BeginAuthAsync(pair.Client);
            await pair.Server.StopAsync();
            var outcome = await AuthTestSupport.AwaitOutcomeAsync(future, 10000);
            Assert.IsType<AuthOutcome.Failed>(outcome);
            Assert.False(pair.ClientAuth.IsAuthenticated(pair.Client));
        }
        finally
        {
            await pair.Client.DisconnectAsync();
        }
    }
}
