// M07 §17D (cross-runtime): real Windows SenderEngine (CLR) -> real Android
// ReceiverEngine (JVM) over loopback. Gated on LOCALDROP_INTEROP=1; without
// it these pass vacuously. Mirrors the M06 TransferInteropTests pattern with
// the direction reversed: here the Java driver hosts the receiver (control +
// data + auth + transaction), and this test drives mutual auth, submits the
// transaction code, then streams via the production Windows SenderEngine.
//
// The driver script sets:
//   LOCALDROP_SOURCE_FILE (bytes to send),
//   LOCALDROP_CONTROL_PORT (Java ControlServer port),
//   LOCALDROP_DATA_PORT (Java ReceiverEngine data port),
//   LOCALDROP_RECEIVER_ID (Java device id),
//   LOCALDROP_CODE_FILE (file containing the transaction code to submit).
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class SenderInteropTests
{
    private static bool InteropEnabled() =>
        Environment.GetEnvironmentVariable("LOCALDROP_INTEROP") == "1";

    [Fact]
    public async Task WindowsSender_AndroidReceiver_FileTransfer()
    {
        if (!InteropEnabled()) return;
        string sourceFile = Environment.GetEnvironmentVariable("LOCALDROP_SOURCE_FILE")
            ?? throw new Xunit.Sdk.XunitException("missing LOCALDROP_SOURCE_FILE");
        string controlPortRaw = Environment.GetEnvironmentVariable("LOCALDROP_CONTROL_PORT")
            ?? throw new Xunit.Sdk.XunitException("missing LOCALDROP_CONTROL_PORT");
        string dataPortRaw = Environment.GetEnvironmentVariable("LOCALDROP_DATA_PORT")
            ?? throw new Xunit.Sdk.XunitException("missing LOCALDROP_DATA_PORT");
        string receiverId = Environment.GetEnvironmentVariable("LOCALDROP_RECEIVER_ID")
            ?? throw new Xunit.Sdk.XunitException("missing LOCALDROP_RECEIVER_ID");
        string codeFile = Environment.GetEnvironmentVariable("LOCALDROP_CODE_FILE")
            ?? throw new Xunit.Sdk.XunitException("missing LOCALDROP_CODE_FILE");
        Assert.True(File.Exists(sourceFile));
        int controlPort = int.Parse(controlPortRaw);
        int dataPort = int.Parse(dataPortRaw);
        string code = (await File.ReadAllTextAsync(codeFile)).Trim();

        using var clientStore = new SoftwareDeviceIdentityStore();
        var clientDevice = AuthTestSupport.DeviceFor(clientStore, "PcA");
        var log = new RecordingSecurityLogger();
        var clientAuth = new AuthManager(clientDevice, clientStore, log);
        var mailbox = new TransferMailbox();
        clientAuth.Inner = mailbox;
        var control = new ControlConnection(clientDevice, clientAuth);
        try
        {
            Assert.True(await control.ConnectAsync("127.0.0.1", controlPort));
            var authOutcome = await clientAuth.BeginAuthAsync(control).WaitAsync(TimeSpan.FromSeconds(30));
            Assert.IsType<AuthOutcome.Authenticated>(authOutcome);
            // Authorize the transaction the Java receiver created (the real
            // M05 TransactionAuthorizer on the JVM side validates + records).
            var pairOutcome = await clientAuth.SenderSubmitCodeAsync(control, code, 30000);
            Assert.IsType<PairSubmitOutcome.Accepted>(pairOutcome);

            var sender = new SenderEngine(clientDevice.DeviceId, control, mailbox, dataPort: dataPort);
            var outcome = await sender.SendAsync(new SenderEngine.SendRequest(
                new FileTransferSource(sourceFile), receiverId, DataHost: "127.0.0.1"));
            var done = Assert.IsType<TransferOutcome.TransportComplete>(outcome);
            var expected = new FileInfo(sourceFile);
            Assert.Equal(expected.Length, done.BytesTransferred);
            // Commit grace (audit F5): TRANSFER_COMPLETE travels on control
            // while bytes travel on data; tearing down control immediately
            // can race the receiver's commit and fail it closed. Hold the
            // channel briefly so the commit is processed first (the receiver
            // stays authoritative for completion). Without this, the test
            // flakes with receiver-side NETWORK_LOST/"Control channel lost".
            await Task.Delay(1000);
            double mbps = done.AverageBytesPerSec.HasValue
                ? done.AverageBytesPerSec.Value / (1024.0 * 1024.0) : double.NaN;
            Console.WriteLine(
                $"[interop-m07] bytes={done.BytesTransferred} elapsedMs={done.Metrics.ElapsedMs} " +
                $"avgMBs={mbps:F1} finalBlock={done.Metrics.FinalBlockSizeBytes} " +
                $"adapts={done.Metrics.Adaptations.Count} stalls={done.Metrics.StallCount}");
        }
        finally
        {
            try { await control.DisconnectAsync(); } catch { }
        }
    }
}
