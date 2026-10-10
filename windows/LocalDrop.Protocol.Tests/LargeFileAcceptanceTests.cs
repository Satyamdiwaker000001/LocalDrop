using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using Xunit;
using Xunit.Abstractions;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class LargeFileAcceptanceTests : IDisposable
{
    private readonly string _destDir;
    private readonly ITestOutputHelper _output;

    public LargeFileAcceptanceTests(ITestOutputHelper output)
    {
        _output = output;
        _destDir = Path.Combine(Path.GetTempPath(), "LocalDrop_M09_LargeFile_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_destDir);
    }

    public void Dispose()
    {
        try { Directory.Delete(_destDir, true); } catch { }
    }

    private sealed class SimulatedLargeTransferSource : ITransferSource
    {
        public string DisplayName { get; }
        public long SizeBytes { get; }
        public long LastModifiedMs => 0L;
        public SimulatedLargeTransferSource(string name, long size)
        {
            DisplayName = name;
            SizeBytes = size;
        }
        public Stream OpenStream() => new SimulatedStream(SizeBytes);
    }

    private sealed class SimulatedStream : Stream
    {
        private long _position;
        private readonly long _length;

        public SimulatedStream(long length) { _length = length; }

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => _length;
        public override long Position { get => _position; set => throw new NotSupportedException(); }
        public override void Flush() { }

        public override int Read(byte[] buffer, int offset, int count)
        {
            long remaining = _length - _position;
            if (remaining <= 0) return 0;
            int toRead = (int)Math.Min(count, remaining);
            for (int i = 0; i < toRead; i++)
            {
                buffer[offset + i] = (byte)((_position + i) % 256);
            }
            _position += toRead;
            return toRead;
        }
        
        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
            Task.FromResult(Read(buffer, offset, count));
            
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
        {
            long remaining = _length - _position;
            if (remaining <= 0) return ValueTask.FromResult(0);
            int toRead = (int)Math.Min(buffer.Length, remaining);
            var span = buffer.Span;
            for (int i = 0; i < toRead; i++)
            {
                span[i] = (byte)((_position + i) % 256);
            }
            _position += toRead;
            return ValueTask.FromResult(toRead);
        }

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    private static async Task<string> ComputeStreamHashAsync(long sizeBytes)
    {
        using var stream = new SimulatedStream(sizeBytes);
        using var sha = SHA256.Create();
        var hash = await sha.ComputeHashAsync(stream);
        return Convert.ToHexString(hash);
    }

    private static async Task<string> ComputeFileHashAsync(string path)
    {
        using var stream = File.OpenRead(path);
        using var sha = SHA256.Create();
        var hash = await sha.ComputeHashAsync(stream);
        return Convert.ToHexString(hash);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1_048_576)] // Exactly 1 MiB boundary
    [InlineData(100_000_000)] // 100 MB
    [InlineData(5_000_000_000)] // >4 GiB (4.65 GiB)
    [InlineData(21_474_836_480)] // 20 GiB
    public async Task Transfer_VariousSizes_SucceedsWithCorrectContent(long sizeBytes)
    {
        // Drive space check for large transfers
        var driveInfo = new DriveInfo(Path.GetPathRoot(_destDir)!);
        if (driveInfo.AvailableFreeSpace < sizeBytes + 1_000_000_000) // Buffer space
        {
            _output.WriteLine($"SKIPPED: Insufficient disk space for {sizeBytes} bytes. Available: {driveInfo.AvailableFreeSpace}");
            return;
        }

        using var senderStore = new SoftwareDeviceIdentityStore();
        using var receiverStore = new SoftwareDeviceIdentityStore();
        var senderDevice = AuthTestSupport.DeviceFor(senderStore, "PcA");
        var receiverDevice = AuthTestSupport.DeviceFor(receiverStore, "PhoneB");
        var log = new RecordingSecurityLogger();

        var serverAuth = new AuthManager(receiverDevice, receiverStore, log);
        var txns = new TransactionManager(null, Core.AppConfig.TransactionCodeLifetimeMs, log);
        var sessions = new AuthorizedSessionStore();
        var engine = new ReceiverEngine(receiverDevice, serverAuth, sessions, _destDir, log, dataPort: 0);
        var handler = engine.CreateHandler();
        serverAuth.Inner = new TransactionAuthorizer(serverAuth, txns, sessions, log, receiverDevice.DeviceId, handler);
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        engine.Start();

        var senderDiscovery = new LanDiscoveryService(senderDevice, port: 0, announceIntervalMs: 100, broadcastAddress: IPAddress.Loopback);
        senderDiscovery.Start();
        var receiverDiscovery = new LanDiscoveryService(receiverDevice, port: 0, announceIntervalMs: 100, broadcastAddress: IPAddress.Loopback, announcePort: senderDiscovery.LocalPort, controlPort: server.LocalPort);
        receiverDiscovery.Start();

        try
        {
            var deadline = DateTimeOffset.UtcNow.AddSeconds(15);
            (string Host, int Port)? endpoint = null;
            while (DateTimeOffset.UtcNow < deadline)
            {
                var seen = senderDiscovery.Snapshot().FirstOrDefault(p => p.Record.DeviceId == receiverDevice.DeviceId);
                if (seen is not null) endpoint = PeerEndpointResolver.Resolve(seen);
                if (endpoint is not null) break;
                await Task.Delay(50);
            }
            Assert.True(endpoint is not null, "receiver never became dialable");

            var txn = txns.CreateTransaction();
            var coordinator = new TransferCoordinator(senderDevice, senderStore, log, senderDiscovery, dataPort: engine.LocalPort);
            
            var fileName = $"file_{sizeBytes}.bin";
            var source = new SimulatedLargeTransferSource(fileName, sizeBytes);
            
            var expectedHashTask = ComputeStreamHashAsync(sizeBytes);
            
            var startTime = DateTimeOffset.UtcNow;
            var outcome = await coordinator.SendFileAsync(receiverDevice.DeviceId, source, txn.Code);
            var sent = Assert.IsType<TransferOutcome.TransportComplete>(outcome);
            Assert.Equal(sizeBytes, sent.BytesTransferred);

            var received = await engine.WaitForAnyOutcomeAsync(1_800_000); // 30 mins timeout for 20 GiB
            var done = Assert.IsType<TransferOutcome.TransportComplete>(received);
            var elapsedMs = (DateTimeOffset.UtcNow - startTime).TotalMilliseconds;
            
            _output.WriteLine($"Size: {sizeBytes} bytes, Time: {elapsedMs:F0} ms, Speed: {(sizeBytes / 1024.0 / 1024.0) / (elapsedMs / 1000.0):F2} MB/s");

            var expectedHash = await expectedHashTask;
            var finalPath = Path.Combine(_destDir, fileName);
            var actualHash = await ComputeFileHashAsync(finalPath);
            Assert.Equal(expectedHash, actualHash);
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
        }
    }
}
