// M07 §17D (same-stack interop): real production Windows SenderEngine ->
// real production Windows ReceiverEngine over loopback with the full
// M04+M05 stack (real mutual auth, real authorized session). This proves the
// M07 sender and the M06 receiver implement the same data-plane contract;
// cross-runtime (C# sender -> JVM Android receiver) runs via the gated
// SenderInteropTests + temp JVM driver, mirroring the M06 interop pattern.
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class SenderReceiverInteropTests
{
    [Fact]
    public async Task ProductionSender_To_ProductionReceiver_ByteExact()
    {
        using var serverStore = new SoftwareDeviceIdentityStore();
        using var clientStore = new SoftwareDeviceIdentityStore();
        var serverDevice = AuthTestSupport.DeviceFor(serverStore, "PcB");
        var clientDevice = AuthTestSupport.DeviceFor(clientStore, "PcA");
        var log = new RecordingSecurityLogger();
        var serverAuth = new AuthManager(serverDevice, serverStore, log);
        var clientAuth = new AuthManager(clientDevice, clientStore, log);
        var txns = new TransactionManager(null, Core.AppConfig.TransactionCodeLifetimeMs, log);
        var sessions = new AuthorizedSessionStore();
        string destDir = Path.Combine(Path.GetTempPath(), "localdrop-test-" + Guid.NewGuid().ToString("D"));
        var receiver = new ReceiverEngine(serverDevice, serverAuth, sessions, destDir, log, dataPort: 0);
        var handler = receiver.CreateHandler();
        var authorizer = new TransactionAuthorizer(serverAuth, txns, sessions, log,
            serverDevice.DeviceId, handler);
        serverAuth.Inner = authorizer;
        var server = new ControlServer(serverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        receiver.Start();
        var clientMailbox = new TransferMailbox();
        clientAuth.Inner = clientMailbox;
        var client = new ControlConnection(clientDevice, clientAuth);
        try
        {
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            var authOutcome = await clientAuth.BeginAuthAsync(client).WaitAsync(TimeSpan.FromSeconds(15));
            Assert.IsType<AuthOutcome.Authenticated>(authOutcome);
            sessions.Authorize(clientDevice.DeviceId, Guid.NewGuid().ToString("D"));

            // Production sender engine (M07) drives the whole flow.
            var sender = new SenderEngine(clientDevice.DeviceId, client, clientMailbox,
                dataPort: receiver.LocalPort);
            byte[] content = new byte[5 * 1024 * 1024];
            new Random(11).NextBytes(content);
            var progress = new System.Collections.Concurrent.ConcurrentQueue<TransferProgress>();
            var sendTask = sender.SendAsync(new SenderEngine.SendRequest(
                new ByteArrayTransferSource("interop.bin", content), serverDevice.DeviceId,
                DataHost: "127.0.0.1", OnProgress: progress.Enqueue));
            var receiveTask = receiver.WaitForAnyOutcomeAsync(60000);
            await Task.WhenAll((Task)sendTask, (Task)receiveTask);
            var sent = Assert.IsType<TransferOutcome.TransportComplete>(await sendTask);
            var received = Assert.IsType<TransferOutcome.TransportComplete>(await receiveTask);
            Assert.Equal(content.LongLength, sent.BytesTransferred);
            Assert.Equal(content.LongLength, received.BytesTransferred);
            Assert.Equal(sent.TransferId, received.TransferId);
            string finalPath = Path.Combine(destDir, "interop.bin");
            Assert.True(File.Exists(finalPath));
            Assert.Equal(content, await File.ReadAllBytesAsync(finalPath));
            Assert.InRange(sent.Metrics.FinalBlockSizeBytes, 65536, 1048576);
            Assert.InRange(received.Metrics.FinalBlockSizeBytes, 65536, 1048576);
        }
        finally
        {
            try { await client.DisconnectAsync(); } catch { }
            try { await receiver.StopAsync(); } catch { }
            try { await server.StopAsync(); } catch { }
            try { server.Dispose(); } catch { }
            try { Directory.Delete(destDir, recursive: true); } catch { }
        }
    }
}
