// M06 §24F + §25(loopback): real Android sender engine (JVM) -> real Windows
// receiver engine over loopback. Gated on LOCALDROP_INTEROP=1; without it
// these pass vacuously. The script sets:
//   LOCALDROP_EXPECTED_FILE (source bytes to compare against, stream-wise),
//   LOCALDROP_DEST_DIR, LOCALDROP_CODE_FILE, LOCALDROP_ID_FILE,
//   LOCALDROP_PORT_FILE (control port for the Java dialer).
using System.Net;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class TransferInteropTests
{
    private static bool InteropEnabled() =>
        Environment.GetEnvironmentVariable("LOCALDROP_INTEROP") == "1";

    [Fact]
    public async Task AndroidSender_WindowsReceiver_FileTransfer()
    {
        if (!InteropEnabled()) return;
        string expectedFile = Environment.GetEnvironmentVariable("LOCALDROP_EXPECTED_FILE")
            ?? throw new Xunit.Sdk.XunitException("missing LOCALDROP_EXPECTED_FILE");
        string destDir = Environment.GetEnvironmentVariable("LOCALDROP_DEST_DIR")
            ?? throw new Xunit.Sdk.XunitException("missing LOCALDROP_DEST_DIR");
        string codeFile = Environment.GetEnvironmentVariable("LOCALDROP_CODE_FILE")
            ?? throw new Xunit.Sdk.XunitException("missing LOCALDROP_CODE_FILE");
        string idFile = Environment.GetEnvironmentVariable("LOCALDROP_ID_FILE")
            ?? throw new Xunit.Sdk.XunitException("missing LOCALDROP_ID_FILE");
        string portFile = Environment.GetEnvironmentVariable("LOCALDROP_PORT_FILE")
            ?? throw new Xunit.Sdk.XunitException("missing LOCALDROP_PORT_FILE");
        Assert.True(File.Exists(expectedFile));

        using var serverStore = new SoftwareDeviceIdentityStore();
        var serverDevice = AuthTestSupport.DeviceFor(serverStore, "PcB");
        var log = new RecordingSecurityLogger();
        var serverAuth = new AuthManager(serverDevice, serverStore, log);
        var txns = new TransactionManager(null, Core.AppConfig.TransactionCodeLifetimeMs, log);
        var sessions = new AuthorizedSessionStore();
        var engine = new ReceiverEngine(serverDevice, serverAuth, sessions, destDir, log);
        var handler = engine.CreateHandler();
        var authorizer = new TransactionAuthorizer(serverAuth, txns, sessions, log,
            serverDevice.DeviceId, handler);
        serverAuth.Inner = authorizer;
        var server = new ControlServer(serverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        engine.Start();
        try
        {
            // Full production stack: the Java driver performs mutual auth,
            // submits this transaction's code (validated by the real
            // TransactionAuthorizer, which records the session), then the
            // production Android SenderEngine streams the file.
            var txn = txns.CreateTransaction();
            await File.WriteAllTextAsync(codeFile, txn.Code);
            await File.WriteAllTextAsync(idFile, serverDevice.DeviceId);
            await File.WriteAllTextAsync(portFile,
                $"{server.LocalPort} {engine.LocalPort} {txn.Id}");
            // The Java driver authenticates, submits the code (authorizer
            // records the session), then streams. Await transport completion.
            var outcome = await engine.WaitForAnyOutcomeAsync(300000);
            var done = Assert.IsType<TransferOutcome.TransportComplete>(outcome);
            // Stream-compare received file vs expected (no full-file load).
            string receivedName = Directory.GetFiles(destDir).Select(Path.GetFileName)
                .First(f => f is not null && !f.EndsWith(".localdrop-part", StringComparison.Ordinal))!;
            string receivedPath = Path.Combine(destDir, receivedName);
            Assert.True(StreamEqual(expectedFile, receivedPath));
            var expected = new FileInfo(expectedFile);
            Assert.Equal(expected.Length, done.BytesTransferred);
            double mbps = done.AverageBytesPerSec.HasValue
                ? done.AverageBytesPerSec.Value / (1024.0 * 1024.0) : double.NaN;
            Console.WriteLine(
                $"[interop] bytes={done.BytesTransferred} elapsedMs={done.Metrics.ElapsedMs} " +
                $"avgMBs={mbps:F1} finalBlock={done.Metrics.FinalBlockSizeBytes} " +
                $"adapts={done.Metrics.Adaptations.Count} stalls={done.Metrics.StallCount}");
        }
        finally
        {
            await engine.StopAsync();
            await server.StopAsync();
            server.Dispose();
        }
    }

    private static bool StreamEqual(string a, string b)
    {
        using var fa = new FileStream(a, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20,
            FileOptions.SequentialScan | FileOptions.Asynchronous);
        using var fb = new FileStream(b, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20,
            FileOptions.SequentialScan | FileOptions.Asynchronous);
        if (fa.Length != fb.Length) return false;
        var ba = new byte[1 << 20];
        var bb = new byte[1 << 20];
        while (true)
        {
            int na = fa.Read(ba, 0, ba.Length);
            int nb = fb.Read(bb, 0, bb.Length);
            if (na != nb) return false;
            if (na == 0) return true;
            if (!ba.AsSpan(0, na).SequenceEqual(bb.AsSpan(0, nb))) return false;
        }
    }
}
