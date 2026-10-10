// M05 tests 8, 15-18, 20, 22-23: the full receiver/sender authorization flow
// over live M04 channels: auth + code + binding matrix.
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;

namespace LocalDrop.Protocol.Tests;

public sealed class AuthorizationFlowTests
{
    private sealed class Flow : IDisposable
    {
        public required SoftwareDeviceIdentityStore ReceiverStore { get; init; }
        public required SoftwareDeviceIdentityStore SenderStore { get; init; }
        public required FakeDiscoveryClock Clock { get; init; }
        public required RecordingSecurityLogger ReceiverLog { get; init; }
        public required AuthManager ReceiverAuth { get; init; }
        public required AuthManager SenderAuth { get; init; }
        public required TransactionManager Txns { get; init; }
        public required AuthorizedSessionStore Sessions { get; init; }
        public required ControlServer Server { get; init; }
        public required ControlConnection Client { get; init; }
        public DeviceInfo ReceiverDevice => AuthTestSupport.DeviceFor(ReceiverStore, "PcB");
        public DeviceInfo SenderDevice => AuthTestSupport.DeviceFor(SenderStore, "PhoneA");

        public async Task<VerifiedPeer> AuthenticateAsync()
        {
            var outcome = await AuthTestSupport.AwaitOutcomeAsync(SenderAuth.BeginAuthAsync(Client));
            var authenticated = Assert.IsType<AuthOutcome.Authenticated>(outcome);
            return authenticated.Peer;
        }

        public void Dispose()
        {
            try { Client.DisconnectAsync().GetAwaiter().GetResult(); } catch { }
            try { Server.StopAsync().GetAwaiter().GetResult(); } catch { }
            try { Server.Dispose(); } catch { }
            try { SenderStore.Dispose(); } catch { }
            try { ReceiverStore.Dispose(); } catch { }
        }
    }

    private static Flow NewFlow(long lifetimeMs = 300_000L, int authTimeoutMs = 8000)
    {
        var receiverStore = new SoftwareDeviceIdentityStore();
        var senderStore = new SoftwareDeviceIdentityStore();
        var clock = new FakeDiscoveryClock();
        var receiverLog = new RecordingSecurityLogger();
        var senderLog = new RecordingSecurityLogger();
        var receiverDevice = AuthTestSupport.DeviceFor(receiverStore, "PcB");
        var senderDevice = AuthTestSupport.DeviceFor(senderStore, "PhoneA");
        var receiverAuth = new AuthManager(receiverDevice, receiverStore, receiverLog, authTimeoutMs);
        var senderAuth = new AuthManager(senderDevice, senderStore, senderLog, authTimeoutMs);
        var txns = new TransactionManager(clock, lifetimeMs, receiverLog);
        var sessions = new AuthorizedSessionStore();
        var authorizer = new TransactionAuthorizer(
            receiverAuth, txns, sessions, receiverLog, receiverDevice.DeviceId);
        receiverAuth.Inner = authorizer;
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => receiverAuth);
        server.Start();
        var client = new ControlConnection(senderDevice, senderAuth);
        if (!client.ConnectAsync("127.0.0.1", server.LocalPort).GetAwaiter().GetResult())
            throw new Xunit.Sdk.XunitException("M04 connect failed");
        return new Flow
        {
            ReceiverStore = receiverStore,
            SenderStore = senderStore,
            Clock = clock,
            ReceiverLog = receiverLog,
            ReceiverAuth = receiverAuth,
            SenderAuth = senderAuth,
            Txns = txns,
            Sessions = sessions,
            Server = server,
            Client = client,
        };
    }

    [Fact]
    public async Task AuthorizedSession_RequiresAuthenticationPlusCorrectCode()
    {
        using var flow = NewFlow();
        var verified = await flow.AuthenticateAsync();
        Assert.Equal(flow.ReceiverStore.Identity().DeviceId, verified.DeviceId);

        var txn = flow.Txns.CreateTransaction();
        var outcome = await flow.SenderAuth.SenderSubmitCodeAsync(flow.Client, txn.Code);
        Assert.IsType<PairSubmitOutcome.Accepted>(outcome);
        Assert.True(flow.Sessions.IsAuthorized(flow.SenderDevice.DeviceId, txn.Id));
        var session = Assert.Single(flow.Sessions.SessionsFor(flow.SenderDevice.DeviceId));
        Assert.Equal(txn.Id, session.TransactionId);
        Assert.True(session.AuthorizedAtMillis > 0);
        // Binding evidence: no other peer is authorized for anything.
        Assert.Empty(flow.Sessions.SessionsFor(flow.ReceiverDevice.DeviceId));
    }

    [Fact]
    public async Task WrongCode_Rejected_CorrectCodeStillWorks()
    {
        using var flow = NewFlow();
        await flow.AuthenticateAsync();
        var txn = flow.Txns.CreateTransaction();
        string wrong = txn.Code[..^1] + (txn.Code[^1] == '0' ? '1' : '0');
        var rejected = Assert.IsType<PairSubmitOutcome.Rejected>(
            await flow.SenderAuth.SenderSubmitCodeAsync(flow.Client, wrong));
        Assert.Equal(TransactionAuthorizer.GenericRejectReason, rejected.Reason);
        Assert.False(flow.Sessions.IsAuthorized(flow.SenderDevice.DeviceId, txn.Id));
        // The correct code was NOT consumed by the wrong attempt.
        Assert.IsType<PairSubmitOutcome.Accepted>(
            await flow.SenderAuth.SenderSubmitCodeAsync(flow.Client, txn.Code));
        Assert.True(flow.Sessions.IsAuthorized(flow.SenderDevice.DeviceId, txn.Id));
    }

    [Fact]
    public async Task UnauthenticatedChannel_CannotAuthorizeEvenWithRightCode()
    {
        using var receiverStore = new SoftwareDeviceIdentityStore();
        var clock = new FakeDiscoveryClock();
        var receiverLog = new RecordingSecurityLogger();
        var receiverDevice = AuthTestSupport.DeviceFor(receiverStore, "PcB");
        var receiverAuth = new AuthManager(receiverDevice, receiverStore, receiverLog);
        var txns = new TransactionManager(clock, 300_000L, receiverLog);
        var sessions = new AuthorizedSessionStore();
        receiverAuth.Inner = new TransactionAuthorizer(
            receiverAuth, txns, sessions, receiverLog, receiverDevice.DeviceId);
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => receiverAuth);
        server.Start();
        try
        {
            // Plain M04 connection: M04 handshake only, NO M05 auth.
            using var senderStore = new SoftwareDeviceIdentityStore();
            var senderDevice = AuthTestSupport.DeviceFor(senderStore, "PhoneA");
            var capture = new ControlTestSupport.Capture();
            var raw = new ControlConnection(senderDevice, capture);
            Assert.True(await raw.ConnectAsync("127.0.0.1", server.LocalPort));
            try
            {
                var txn = txns.CreateTransaction();
                var request = new LocalDropMessage(
                    MessageType.PAIR_REQUEST,
                    ProtocolCodec.NewMessageId(),
                    null, null, null,
                    new TypedPayload.PairRequest(new PairRequestPayload(
                        senderDevice.DeviceId, senderDevice.DeviceName, txn.Code)));
                Assert.True(await raw.SendAsync(request));
                var response = await capture.AwaitMessageAsync();
                Assert.Equal(MessageType.PAIR_RESPONSE, response.Type);
                var payload = Assert.IsType<TypedPayload.PairResponse>(response.Payload).V;
                Assert.False(payload.Accepted);
                Assert.Equal(TransactionAuthorizer.GenericRejectReason, payload.Reason);
                Assert.Empty(sessions.SessionsFor(senderDevice.DeviceId));
            }
            finally
            {
                await raw.DisconnectAsync();
            }
        }
        finally
        {
            await server.StopAsync();
        }
    }

    [Fact]
    public async Task ExpiredCode_RejectedOverAuthenticatedChannel()
    {
        using var flow = NewFlow();
        await flow.AuthenticateAsync();
        var txn = flow.Txns.CreateTransaction();
        flow.Clock.Advance(300_001L);
        var outcome = await flow.SenderAuth.SenderSubmitCodeAsync(flow.Client, txn.Code);
        Assert.IsType<PairSubmitOutcome.Rejected>(outcome);
        Assert.False(flow.Sessions.IsAuthorized(flow.SenderDevice.DeviceId, txn.Id));
    }

    [Fact]
    public async Task ReplayOfConsumedCode_IsRejected()
    {
        using var flow = NewFlow();
        await flow.AuthenticateAsync();
        var txn = flow.Txns.CreateTransaction();
        Assert.IsType<PairSubmitOutcome.Accepted>(
            await flow.SenderAuth.SenderSubmitCodeAsync(flow.Client, txn.Code));
        var replay = await flow.SenderAuth.SenderSubmitCodeAsync(flow.Client, txn.Code);
        Assert.IsType<PairSubmitOutcome.Rejected>(replay);
        Assert.Single(flow.Sessions.SessionsFor(flow.SenderDevice.DeviceId));
    }

    [Fact]
    public async Task OneReceiverCode_CannotAuthorizeAnotherReceiversTransaction()
    {
        // Receiver A + Receiver B are independent servers with isolated state.
        using var storeA = new SoftwareDeviceIdentityStore();
        using var storeB = new SoftwareDeviceIdentityStore();
        using var senderStore = new SoftwareDeviceIdentityStore();
        var deviceA = AuthTestSupport.DeviceFor(storeA, "PcA");
        var deviceB = AuthTestSupport.DeviceFor(storeB, "PcB");
        var senderDevice = AuthTestSupport.DeviceFor(senderStore, "PhoneA");

        (ControlServer Server, TransactionManager Txns, AuthorizedSessionStore Sessions) Receiver(
            DeviceInfo device, SoftwareDeviceIdentityStore store)
        {
            var auth = new AuthManager(device, store, new RecordingSecurityLogger());
            var txns = new TransactionManager(new FakeDiscoveryClock(), 300_000L, new RecordingSecurityLogger());
            var sessions = new AuthorizedSessionStore();
            auth.Inner = new TransactionAuthorizer(auth, txns, sessions,
                new RecordingSecurityLogger(), device.DeviceId);
            var server = new ControlServer(device, port: 0, listenerFactory: () => auth);
            server.Start();
            return (server, txns, sessions);
        }

        var (serverA, txnsA, sessionsA) = Receiver(deviceA, storeA);
        var (serverB, _, sessionsB) = Receiver(deviceB, storeB);
        try
        {
            var txnA = txnsA.CreateTransaction();
            // Sender authenticates with B, then submits A's code to B.
            var senderAuth = new AuthManager(senderDevice, senderStore, new RecordingSecurityLogger());
            var client = new ControlConnection(senderDevice, senderAuth);
            Assert.True(await client.ConnectAsync("127.0.0.1", serverB.LocalPort));
            try
            {
                var outcome = await AuthTestSupport.AwaitOutcomeAsync(senderAuth.BeginAuthAsync(client));
                Assert.IsType<AuthOutcome.Authenticated>(outcome);
                var rejected = await senderAuth.SenderSubmitCodeAsync(client, txnA.Code);
                Assert.IsType<PairSubmitOutcome.Rejected>(rejected);
                Assert.Empty(sessionsB.SessionsFor(senderDevice.DeviceId));
                Assert.Empty(sessionsA.SessionsFor(senderDevice.DeviceId));
            }
            finally
            {
                await client.DisconnectAsync();
            }
        }
        finally
        {
            await serverA.StopAsync();
            await serverB.StopAsync();
        }
    }

    [Fact]
    public async Task FailedAuthentication_LeavesNoSession_ChannelDropped()
    {
        // Raw peer sends garbage auth; then a good client must still work
        // (server survives failures) and no session exists for the attacker.
        using var receiverStore = new SoftwareDeviceIdentityStore();
        var receiverDevice = AuthTestSupport.DeviceFor(receiverStore, "PcB");
        var receiverLog = new RecordingSecurityLogger();
        var receiverAuth = new AuthManager(receiverDevice, receiverStore, receiverLog);
        var txns = new TransactionManager(new FakeDiscoveryClock(), 300_000L, receiverLog);
        var sessions = new AuthorizedSessionStore();
        receiverAuth.Inner = new TransactionAuthorizer(
            receiverAuth, txns, sessions, receiverLog, receiverDevice.DeviceId);
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => receiverAuth);
        server.Start();
        try
        {
            using var evilStore = new SoftwareDeviceIdentityStore();
            using var raw = new AuthTestSupport.RawPeer("127.0.0.1", server.LocalPort, evilStore, "Evil");
            raw.SendAuthRequest("{}", method: "bogus");
            await AuthTestSupport.WaitForAsync("attacker dropped",
                () => server.Connections.Snapshot().Count == 0);
            Assert.Empty(sessions.SessionsFor(evilStore.Identity().DeviceId));
            // Server still healthy for a legitimate client.
            using var senderStore = new SoftwareDeviceIdentityStore();
            var senderDevice = AuthTestSupport.DeviceFor(senderStore, "PhoneA");
            var senderAuth = new AuthManager(senderDevice, senderStore, new RecordingSecurityLogger());
            var client = new ControlConnection(senderDevice, senderAuth);
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            try
            {
                var outcome = await AuthTestSupport.AwaitOutcomeAsync(senderAuth.BeginAuthAsync(client));
                Assert.IsType<AuthOutcome.Authenticated>(outcome);
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
    public async Task SenderSubmit_TimesOutCleanlyWithNoAuthorizerReply()
    {
        // Authenticated channel, but the server never answers PAIR_REQUEST.
        using var pair = AuthTestSupport.ConnectedPair();
        // Wire NO authorizer: raw AuthManager only.
        try
        {
            var outcome = await AuthTestSupport.AwaitOutcomeAsync(pair.ClientAuth.BeginAuthAsync(pair.Client));
            Assert.IsType<AuthOutcome.Authenticated>(outcome);
            var result = await pair.ClientAuth.SenderSubmitCodeAsync(pair.Client, "123456", timeoutMs: 1500);
            Assert.IsType<PairSubmitOutcome.Failed>(result);
        }
        finally
        {
            await pair.Client.DisconnectAsync();
            await pair.Server.StopAsync();
        }
    }
}
