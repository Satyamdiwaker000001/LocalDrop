// M05 §24 cross-platform interop, gated on LOCALDROP_INTEROP=1.
// Without the variable (normal `dotnet test`) they pass vacuously; the
// interop script sets it and pairs each test with the Java driver running
// the PRODUCTION Android classes (same M02 bytes + same crypto both ways).
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;

namespace LocalDrop.Protocol.Tests;

public sealed class AuthInteropTests
{
    private const int InteropPort = 48502;

    private static bool InteropEnabled() =>
        Environment.GetEnvironmentVariable("LOCALDROP_INTEROP") == "1";

    // Java driver (production Android stack) dials, authenticates, and submits
    // the receiver-displayed code. This side creates the transaction and
    // asserts the authorized session binds the Java peer to OUR transaction.
    [Fact]
    public async Task CSharpResponder_JavaInitiator()
    {
        if (!InteropEnabled()) return;
        string? codeFile = Environment.GetEnvironmentVariable("LOCALDROP_CODE_FILE");
        Assert.False(string.IsNullOrEmpty(codeFile));
        using var store = new SoftwareDeviceIdentityStore();
        var device = new DeviceInfo(store.Identity().DeviceId, "CSharpServer", "windows");
        var log = new RecordingSecurityLogger();
        var auth = new AuthManager(device, store, log);
        var txns = new TransactionManager(null, Core.AppConfig.TransactionCodeLifetimeMs, log);
        var sessions = new AuthorizedSessionStore();
        auth.Inner = new TransactionAuthorizer(auth, txns, sessions, log, device.DeviceId);
        var server = new ControlServer(device, port: InteropPort, listenerFactory: () => auth);
        server.Start();
        try
        {
            var txn = txns.CreateTransaction();
            await File.WriteAllTextAsync(codeFile!, txn.Code);
            // The driver authenticates, submits the code, and disconnects.
            var deadline = DateTimeOffset.UtcNow.AddSeconds(60);
            AuthorizedSession? session = null;
            while (DateTimeOffset.UtcNow < deadline)
            {
                session = sessions.Snapshot().FirstOrDefault();
                if (session is not null) break;
                await Task.Delay(200);
            }
            Assert.NotNull(session);
            Assert.Equal(txn.Id, session!.TransactionId);
            Assert.NotEqual(device.DeviceId, session.PeerDeviceId);
            Assert.True(Guid.TryParse(session.PeerDeviceId, out _));
        }
        finally
        {
            await server.StopAsync();
        }
    }

    // This client dials the Java driver (production Android stack), which
    // created the transaction whose code arrives via LOCALDROP_TEST_CODE
    // (simulating the user reading the receiver display).
    [Fact]
    public async Task JavaResponder_CSharpInitiator()
    {
        if (!InteropEnabled()) return;
        string? code = Environment.GetEnvironmentVariable("LOCALDROP_TEST_CODE");
        Assert.False(string.IsNullOrEmpty(code));
        using var store = new SoftwareDeviceIdentityStore();
        var device = new DeviceInfo(store.Identity().DeviceId, "CSharpClient", "windows");
        var log = new RecordingSecurityLogger();
        var auth = new AuthManager(device, store, log);
        var client = new ControlConnection(device, auth);
        // The Java server may still be binding; retry the dial briefly.
        bool connected = false;
        var deadline = DateTimeOffset.UtcNow.AddSeconds(25);
        while (DateTimeOffset.UtcNow < deadline && !connected)
        {
            connected = await client.ConnectAsync("127.0.0.1", InteropPort);
            if (!connected) await Task.Delay(500);
        }
        Assert.True(connected);
        try
        {
            var outcome = await AuthTestSupport.AwaitOutcomeAsync(auth.BeginAuthAsync(client), 20000);
            var authenticated = Assert.IsType<AuthOutcome.Authenticated>(outcome);
            Assert.NotEqual(device.DeviceId, authenticated.Peer.DeviceId);
            var submit = await auth.SenderSubmitCodeAsync(client, code!, timeoutMs: 15000);
            Assert.IsType<PairSubmitOutcome.Accepted>(submit);
        }
        finally
        {
            await client.DisconnectAsync();
        }
    }
}
