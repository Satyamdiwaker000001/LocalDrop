// M15 regression test for the receiver-side verification-answer race.
//
// DEFECT (found by the M15 benchmark, both platforms):
//   The receiver answers a late VERIFY_REQUEST from a background task that
//   waited for the TERMINAL OUTCOME entry of the transfer. That entry is
//   owned by whoever consumes the outcome: ReceiverEngine.WaitForAnyOutcomeAsync
//   (any-order consumer) DELETES it as soon as it dequeues the outcome from
//   the finished queue. When the VERIFY_REQUEST arrived while the receiver
//   was still hashing, the answer therefore depended on an unrelated
//   consumer's polling interval: the consumer deleted the entry first, the
//   background task never saw it, and the sender waited the full 60 s
//   verify timeout and reported a FALSE FAILURE for a transfer whose bytes
//   had been received and verified byte-exactly.
//
// FIX: the answer waits for the VERIFICATION RECORD (written on every
//   verification path, never removed by an outcome consumer) instead of the
//   consumer-owned terminal-outcome entry.
//
// This test drives exactly the racing pattern: the receiver's outcome is
// consumed by a polling any-outcome waiter WHILE the sender is still in its
// verification wait. Before the fix this test failed after 60 s with
// TIMEOUT; after the fix it completes in seconds.
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;
using LocalDrop.Transfer;
using Xunit;

namespace LocalDrop.Protocol.Tests;

public sealed class M15VerifyRaceRegressionTests
{
    /// <summary>
    /// 64 MiB: large enough that the receiver's SHA-256 re-read pass is
    /// still running when the sender's VERIFY_REQUEST arrives, which is the
    /// window in which the race occurred.
    /// </summary>
    private const long PayloadBytes = 64L * 1024 * 1024;

    [Fact]
    public async Task VerifierAnswerSurvivesConcurrentOutcomeConsumer()
    {
        string work = Path.Combine(Path.GetTempPath(), "localdrop-m15-verify-" + Guid.NewGuid().ToString("N"));
        string destDir = Path.Combine(work, "dest");
        string srcDir = Path.Combine(work, "src");
        Directory.CreateDirectory(destDir);
        Directory.CreateDirectory(srcDir);
        string srcPath = Path.Combine(srcDir, "payload.bin");
        try
        {
            // Deterministic, bounded-memory source content.
            byte[] block = new byte[1 << 20];
            using (var fs = new FileStream(srcPath, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                for (long written = 0; written < PayloadBytes; written += block.Length)
                {
                    for (int i = 0; i < block.Length; i++) block[i] = (byte)((written + i) * 31 + 7);
                    fs.Write(block, 0, block.Length);
                }
            }

            using var serverStore = new SoftwareDeviceIdentityStore();
            using var clientStore = new SoftwareDeviceIdentityStore();
            var serverDevice = AuthTestSupport.DeviceFor(serverStore, "PcB");
            var clientDevice = AuthTestSupport.DeviceFor(clientStore, "PcA");
            var log = new RecordingSecurityLogger();
            var serverAuth = new AuthManager(serverDevice, serverStore, log);
            var clientAuth = new AuthManager(clientDevice, clientStore, log);
            var txns = new TransactionManager(null, Core.AppConfig.TransactionCodeLifetimeMs, log);
            var sessions = new AuthorizedSessionStore();
            var receiver = new ReceiverEngine(serverDevice, serverAuth, sessions, destDir, log, dataPort: 0);
            serverAuth.Inner = new TransactionAuthorizer(serverAuth, txns, sessions, log,
                serverDevice.DeviceId, receiver.CreateHandler());
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

                var sender = new SenderEngine(clientDevice.DeviceId, client, clientMailbox,
                    dataPort: receiver.LocalPort);

                // The racing pattern: an any-outcome consumer polls the
                // receiver's outcome WHILE the sender waits for its
                // verification verdict.
                var recvTask = receiver.WaitForAnyOutcomeAsync(120_000);
                var sendTask = sender.SendAsync(new SenderEngine.SendRequest(
                    new FileTransferSource(srcPath), serverDevice.DeviceId, DataHost: "127.0.0.1"));

                // Awaiting the receiver outcome first keeps the any-outcome
                // consumer polling (and therefore owning the terminal entry)
                // exactly as in the racing pattern; the sender's verdict must
                // still arrive promptly.
                var got = await recvTask.WaitAsync(TimeSpan.FromSeconds(30));
                var sent = await sendTask.WaitAsync(TimeSpan.FromSeconds(30));

                var sentOk = Assert.IsType<TransferOutcome.TransportComplete>(sent);
                var gotOk = Assert.IsType<TransferOutcome.TransportComplete>(got);
                Assert.Equal(PayloadBytes, sentOk.BytesTransferred);
                Assert.Equal(PayloadBytes, gotOk.BytesTransferred);
                Assert.Equal(sentOk.TransferId, gotOk.TransferId);
            }
            finally
            {
                try { await client.DisconnectAsync(); } catch { }
                try { await receiver.StopAsync(); } catch { }
                try { await server.StopAsync(); } catch { }
                try { server.Dispose(); } catch { }
            }
        }
        finally
        {
            try { Directory.Delete(work, recursive: true); } catch { }
        }
    }
}
