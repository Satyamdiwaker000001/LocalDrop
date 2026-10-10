// Verification audit F5: TransferCoordinator connects discovery -> control
// -> auth -> code authorization -> M07 transfer with NO manual IP/port.
// The full loopback test resolves the receiver endpoint purely from the
// discovery snapshot (the test never types an address).
using System.Net;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class TransferCoordinatorTests
{
    private const string SenderId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    [Fact]
    public void ResolvePeer_NullUnlessHostAndPortKnown()
    {
        var noHost = new DiscoveredPeer(
            new DiscoveryRecord(SenderId, "N", "windows", 48424, []), 0L, 0L, null);
        Assert.Null(PeerEndpointResolver.Resolve(noHost));
        var noPort = new DiscoveredPeer(
            new DiscoveryRecord(SenderId, "N", "windows", 0L, []), 0L, 0L, "192.168.1.5");
        Assert.Null(PeerEndpointResolver.Resolve(noPort));
        var good = new DiscoveredPeer(
            new DiscoveryRecord(SenderId, "N", "windows", 48424, []), 0L, 0L, "192.168.1.5");
        Assert.Equal(("192.168.1.5", 48424), PeerEndpointResolver.Resolve(good));
    }

    [Fact]
    public async Task UnknownPeer_FailsWithoutNetwork()
    {
        using var store = new SoftwareDeviceIdentityStore();
        var device = AuthTestSupport.DeviceFor(store, "PcA");
        var log = new RecordingSecurityLogger();
        var discovery = new LanDiscoveryService(device, port: 0, broadcastAddress: IPAddress.Loopback);
        var coordinator = new TransferCoordinator(device, store, log, discovery);
        var outcome = await coordinator.SendFileAsync(
            Guid.NewGuid().ToString("D"),
            new ByteArrayTransferSource("x.bin", [1, 2, 3]), "000000");
        var failed = Assert.IsType<TransferOutcome.Failed>(outcome);
        Assert.Equal(ErrorCode.INTERNAL_ERROR, failed.Code);
        discovery.Dispose();
    }

    [Fact]
    public async Task DiscoveryToTransfer_NoManualAddress()
    {
        using var senderStore = new SoftwareDeviceIdentityStore();
        using var receiverStore = new SoftwareDeviceIdentityStore();
        var senderDevice = AuthTestSupport.DeviceFor(senderStore, "PcA");
        var receiverDevice = AuthTestSupport.DeviceFor(receiverStore, "PhoneB");
        var log = new RecordingSecurityLogger();

        // Receiver stack: control server + authorized session + data engine.
        var serverAuth = new AuthManager(receiverDevice, receiverStore, log);
        var txns = new TransactionManager(null, Core.AppConfig.TransactionCodeLifetimeMs, log);
        var sessions = new AuthorizedSessionStore();
        string destDir = Path.Combine(Path.GetTempPath(), "localdrop-test-" + Guid.NewGuid().ToString("D"));
        var engine = new ReceiverEngine(receiverDevice, serverAuth, sessions, destDir, log, dataPort: 0);
        var handler = engine.CreateHandler();
        serverAuth.Inner = new TransactionAuthorizer(serverAuth, txns, sessions, log,
            receiverDevice.DeviceId, handler);
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        engine.Start();

        // Receiver announces its LIVE control port; sender listens.
        var senderDiscovery = new LanDiscoveryService(
            senderDevice, port: 0, announceIntervalMs: 100, broadcastAddress: IPAddress.Loopback);
        senderDiscovery.Start();
        var receiverDiscovery = new LanDiscoveryService(
            receiverDevice, port: 0, announceIntervalMs: 100,
            broadcastAddress: IPAddress.Loopback, announcePort: senderDiscovery.LocalPort,
            controlPort: server.LocalPort);
        receiverDiscovery.Start();
        try
        {
            // Wait until discovery yields a DIALABLE endpoint (host + port).
            var deadline = DateTimeOffset.UtcNow.AddSeconds(15);
            (string Host, int Port)? endpoint = null;
            while (DateTimeOffset.UtcNow < deadline)
            {
                var seen = senderDiscovery.Snapshot()
                    .FirstOrDefault(p => p.Record.DeviceId == receiverDevice.DeviceId);
                if (seen is not null) endpoint = PeerEndpointResolver.Resolve(seen);
                if (endpoint is not null) break;
                await Task.Delay(50);
            }
            Assert.True(endpoint is not null, "receiver never became dialable via discovery");
            // The ONLY address used below comes from the discovery snapshot.
            Assert.Equal("127.0.0.1", endpoint!.Value.Host);
            Assert.Equal(server.LocalPort, endpoint.Value.Port);

            // The user relays the displayed code out-of-band (as M05 designed).
            var txn = txns.CreateTransaction();
            var coordinator = new TransferCoordinator(senderDevice, senderStore, log,
                senderDiscovery, dataPort: engine.LocalPort);
            byte[] content = new byte[1 * 1024 * 1024];
            new Random(21).NextBytes(content);
            var outcome = await coordinator.SendFileAsync(
                receiverDevice.DeviceId,
                new ByteArrayTransferSource("coord.bin", content), txn.Code);
            Assert.True(outcome is TransferOutcome.TransportComplete, $"outcome={outcome}");
            var sent = (TransferOutcome.TransportComplete)outcome;
            Assert.Equal(content.LongLength, sent.BytesTransferred);
            var received = await engine.WaitForAnyOutcomeAsync(30000);
            var done = Assert.IsType<TransferOutcome.TransportComplete>(received);
            Assert.Equal(sent.TransferId, done.TransferId);
            Assert.Equal(content, await File.ReadAllBytesAsync(Path.Combine(destDir, "coord.bin")));
        }
        finally
        {
            await receiverDiscovery.StopAsync();
            receiverDiscovery.Dispose();
            await senderDiscovery.StopAsync();
            senderDiscovery.Dispose();
            await engine.StopAsync();
            await server.StopAsync();
            server.Dispose();
            try { Directory.Delete(destDir, recursive: true); } catch { }
        }
    }
}
