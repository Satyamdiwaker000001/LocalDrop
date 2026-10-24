// M07 §17B/E/F(small)/G (sender side): Windows SenderEngine over loopback
// against a fake receiver (control ACCEPT/REJECT + data socket capture).
// Mirrors the Android M06 SenderEngineTest. No Internet.
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class SenderEngineTests
{
    private const string SenderId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private const string ReceiverId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    // Generates `size` deterministic bytes on demand (no disk, no RAM spike).
    private sealed class PatternStream(long size, long seed = 0x5A17L) : Stream
    {
        private long _position;
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => size;
        public override long Position { get => _position; set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_position >= size) return 0;
            int n = (int)Math.Min(count, size - _position);
            for (int i = 0; i < n; i++)
                buffer[offset + i] = (byte)(((_position + i) * 31 + seed) & 0xFF);
            _position += n;
            return n;
        }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    private sealed class PatternSource(string displayName, long size, long seed = 0x5A17L) : ITransferSource
    {
        public string DisplayName => displayName;
        public long SizeBytes => size;
        public long LastModifiedMs => 0L;
        public Stream OpenStream() => new PatternStream(size, seed);
        public byte[] ExpectedBytes()
        {
            using var output = new MemoryStream();
            using var stream = new PatternStream(size, seed);
            byte[] buf = new byte[65536];
            while (true)
            {
                int n = stream.Read(buf, 0, buf.Length);
                if (n == 0) break;
                output.Write(buf, 0, n);
            }
            return output.ToArray();
        }
    }

    // Minimal receiver stand-in. Control side replies ACCEPT/REJECT to the one
    // TRANSFER_REQUEST and records everything the sender emits
    // (START/PROGRESS/COMPLETE/CANCEL/ERROR/VERIFY_REQUEST). Data side
    // captures one connection (preamble + bytes). throttleMsPerRead simulates
    // a slow receiver/disk for backpressure tests. VerifyMode scripts the
    // VERIFY verdict: Match answers match=true with the request sha (a
    // correct receiver), Mismatch answers match=false, Silent never answers
    // (engines must time out, never succeed).
    internal enum VerifyMode { Match, Mismatch, Silent }

    private sealed class FakeReceiver : IDisposable
    {
        private readonly bool _accept;
        private readonly int _throttleMsPerRead;
        private readonly VerifyMode _verifyMode;
        public readonly ConcurrentQueue<LocalDropMessage> ReceivedControl = new();
        public readonly TcpListener DataListener;
        public int ActualDataPort => ((IPEndPoint)DataListener.LocalEndpoint).Port;
        public string? Preamble { get; private set; }
        public readonly MemoryStream ReceivedBytes = new();
        public readonly TaskCompletionSource<bool> DataDone =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly Task _dataTask;

        public FakeReceiver(bool accept = true, int throttleMsPerRead = 0, VerifyMode verifyMode = VerifyMode.Match)
        {
            _accept = accept;
            _throttleMsPerRead = throttleMsPerRead;
            _verifyMode = verifyMode;
            DataListener = new TcpListener(IPAddress.Loopback, 0);
            DataListener.Start();
            _dataTask = Task.Run(DataLoopAsync);
        }

        private async Task DataLoopAsync()
        {
            try
            {
                using var socket = await DataListener.AcceptTcpClientAsync();
                using var stream = socket.GetStream();
                Preamble = await ReadLineAsync(stream, 64);
                byte[] buf = new byte[65536];
                while (true)
                {
                    int n = await stream.ReadAsync(buf);
                    if (n == 0) break;
                    lock (ReceivedBytes) ReceivedBytes.Write(buf, 0, n);
                    if (_throttleMsPerRead > 0) await Task.Delay(_throttleMsPerRead);
                }
                DataDone.TrySetResult(true);
            }
            catch
            {
                DataDone.TrySetResult(false);
            }
        }

        private static async Task<string?> ReadLineAsync(NetworkStream input, int cap)
        {
            using var buf = new MemoryStream();
            while (buf.Length < cap)
            {
                int b = input.ReadByte();
                if (b < 0) return null;
                if (b == '\n') break;
                buf.WriteByte((byte)b);
            }
            if (buf.Length >= cap) return null;
            string text = Encoding.ASCII.GetString(buf.ToArray());
            return text.Length == 36 ? text : null;
        }

        public IControlEvents Listener => new FakeListener(this);

        private sealed class FakeListener(FakeReceiver parent) : IControlEvents
        {
            public void OnMessage(ControlConnection connection, LocalDropMessage message)
            {
                parent.ReceivedControl.Enqueue(message);
                if (message.Type == MessageType.TRANSFER_REQUEST &&
                    message.Payload is TypedPayload.TransferRequest req)
                {
                    LocalDropMessage reply = parent._accept
                        ? new LocalDropMessage(
                            MessageType.TRANSFER_ACCEPT, ProtocolCodec.NewMessageId(),
                            null, null, req.V.Metadata.TransferId,
                            new TypedPayload.TransferAccept(
                                new TransferAcceptPayload(req.V.Metadata.TransferId, 0L)))
                        : new LocalDropMessage(
                            MessageType.TRANSFER_REJECT, ProtocolCodec.NewMessageId(),
                            null, null, req.V.Metadata.TransferId,
                            new TypedPayload.TransferReject(
                                new TransferRejectPayload(req.V.Metadata.TransferId,
                                    new ProtocolError(ErrorCode.TRANSFER_REJECTED, "Nope."))));
                    _ = connection.SendAsync(reply);
                }
                if (message.Type == MessageType.VERIFY_REQUEST &&
                    message.Payload is TypedPayload.VerifyRequest verify)
                {
                    if (parent._verifyMode != VerifyMode.Silent)
                    {
                        _ = connection.SendAsync(new LocalDropMessage(
                            MessageType.VERIFY_RESULT, ProtocolCodec.NewMessageId(),
                            null, null, verify.V.TransferId,
                            new TypedPayload.VerifyResult(new VerifyResultPayload(
                                verify.V.TransferId,
                                parent._verifyMode == VerifyMode.Match,
                                verify.V.Sha256Hex))));
                    }
                }
            }

            public void OnClosed(ControlConnection connection, CloseReason reason, string detail) { }
        }

        public void Dispose()
        {
            try { DataListener.Stop(); } catch { }
        }
    }

    private sealed class Harness : IDisposable
    {
        public required ControlServer ControlServer { get; init; }
        public required ControlConnection SenderControl { get; init; }
        public required TransferMailbox SenderMailbox { get; init; }
        public required FakeReceiver Fake { get; init; }
        public required SenderEngine Engine { get; init; }
        public required ConcurrentQueue<TransferProgress> Progress { get; init; }

        public void Dispose()
        {
            try { SenderControl.DisconnectAsync().GetAwaiter().GetResult(); } catch { }
            try { ControlServer.StopAsync().GetAwaiter().GetResult(); } catch { }
            try { ControlServer.Dispose(); } catch { }
            Fake.Dispose();
        }
    }

    private static Harness CreateHarness(
        bool accept = true, int requestTimeoutMs = 8000, int throttleMsPerRead = 0,
        VerifyMode verifyMode = VerifyMode.Match, int verifyTimeoutMs = 8000)
    {
        var senderDevice = new DeviceInfo(SenderId, "PcA", "windows");
        var receiverDevice = new DeviceInfo(ReceiverId, "PhoneB", "android");
        var fake = new FakeReceiver(accept, throttleMsPerRead, verifyMode);
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => fake.Listener);
        server.Start();
        var mailbox = new TransferMailbox();
        var control = new ControlConnection(senderDevice, mailbox);
        if (!control.ConnectAsync("127.0.0.1", server.LocalPort).GetAwaiter().GetResult())
            throw new Xunit.Sdk.XunitException("control connect failed");
        var progress = new ConcurrentQueue<TransferProgress>();
        var engine = new SenderEngine(
            SenderId, control, mailbox,
            dataPort: fake.ActualDataPort, requestTimeoutMs: requestTimeoutMs,
            verifyTimeoutMs: verifyTimeoutMs);
        return new Harness
        {
            ControlServer = server,
            SenderControl = control,
            SenderMailbox = mailbox,
            Fake = fake,
            Engine = engine,
            Progress = progress,
        };
    }

    private static Task<TransferOutcome> SendAsync(
        SenderEngine engine, ITransferSource source, ConcurrentQueue<TransferProgress> progress,
        CancellationToken ct = default) =>
        engine.SendAsync(new SenderEngine.SendRequest(
            source, ReceiverId, DataHost: "127.0.0.1",
            CancellationToken: ct, OnProgress: progress.Enqueue));

    [Fact]
    public void ControlPeerHost_DerivesFromLiveConnection()
    {
        using var h = CreateHarness();
        Assert.Equal("127.0.0.1", SenderEngine.DeriveControlPeerHost(h.SenderControl));
    }

    [Fact]
    public async Task SmallFile_RoundTripsByteExact_WithPreamble()
    {
        using var h = CreateHarness();
        var source = new PatternSource("small.bin", 100 * 1024);
        var outcome = await SendAsync(h.Engine, source, h.Progress);
        var done = Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        Assert.Equal(source.SizeBytes, done.BytesTransferred);
        Assert.True(await h.Fake.DataDone.Task.WaitAsync(TimeSpan.FromSeconds(15)));
        Assert.Equal(source.ExpectedBytes(), h.Fake.ReceivedBytes.ToArray());
        // Preamble carried a valid transfer UUID (data-plane binding).
        Assert.NotNull(h.Fake.Preamble);
        Assert.Matches("^[0-9a-f-]{36}$", h.Fake.Preamble!);
        // Control plane saw START then COMPLETE; COMPLETE carries zeros.
        Assert.Contains(h.Fake.ReceivedControl, m => m.Type == MessageType.TRANSFER_START);
        var complete = Assert.Single(h.Fake.ReceivedControl, m => m.Type == MessageType.TRANSFER_COMPLETE);
        var payload = Assert.IsType<TypedPayload.TransferComplete>(complete.Payload).V;
        string expectedSha = Convert.ToHexString(System.Security.Cryptography.SHA256.Create().ComputeHash(source.ExpectedBytes())).ToLowerInvariant();
        Assert.Equal(expectedSha, payload.Sha256Hex);
        Assert.Equal(source.SizeBytes, payload.FileSize);
    }

    [Fact]
    public async Task RealFileSource_RoundTripsByteExact()
    {
        string dir = Path.Combine(Path.GetTempPath(), "localdrop-test-" + Guid.NewGuid().ToString("D"));
        Directory.CreateDirectory(dir);
        try
        {
            string path = Path.Combine(dir, "real.bin");
            byte[] content = new byte[200_000];
            new Random(7).NextBytes(content);
            await File.WriteAllBytesAsync(path, content);
            using var h = CreateHarness();
            var outcome = await SendAsync(h.Engine, new FileTransferSource(path), h.Progress);
            var done = Assert.IsType<TransferOutcome.TransportComplete>(outcome);
            Assert.Equal(content.LongLength, done.BytesTransferred);
            Assert.True(await h.Fake.DataDone.Task.WaitAsync(TimeSpan.FromSeconds(15)));
            Assert.Equal(content, h.Fake.ReceivedBytes.ToArray());
        }
        finally
        {
            try { Directory.Delete(dir, recursive: true); } catch { }
        }
    }

    [Theory]
    [InlineData(0L)]
    [InlineData(1L)]
    [InlineData(63L * 1024)]
    [InlineData(64L * 1024)]
    [InlineData(65L * 1024)]
    [InlineData(256L * 1024)]
    [InlineData(1024L * 1024 + 1)]
    [InlineData(5L * 1024 * 1024)]
    public async Task BoundarySizes_RoundTrip(long size)
    {
        using var h = CreateHarness();
        var source = new PatternSource($"boundary-{size}.bin", size);
        var outcome = await SendAsync(h.Engine, source, h.Progress);
        Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        Assert.True(await h.Fake.DataDone.Task.WaitAsync(TimeSpan.FromSeconds(20)));
        Assert.Equal(source.ExpectedBytes(), h.Fake.ReceivedBytes.ToArray());
    }

    [Fact]
    public async Task LargeGeneratedStream_TransfersWith64BitCounters()
    {
        using var h = CreateHarness();
        long size = 64L * 1024 * 1024;
        var source = new PatternSource("large.bin", size);
        var outcome = await SendAsync(h.Engine, source, h.Progress);
        var done = Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        Assert.Equal(size, done.BytesTransferred);
        Assert.True(await h.Fake.DataDone.Task.WaitAsync(TimeSpan.FromSeconds(30)));
        Assert.Equal(source.ExpectedBytes(), h.Fake.ReceivedBytes.ToArray());
        Assert.Equal(size, done.Metrics.BytesTransferred);
        Assert.InRange(done.Metrics.FinalBlockSizeBytes, 65536, 1048576);
        Assert.True(done.Metrics.ElapsedMs >= 0);
    }

    [Fact]
    public void ProgressMath_HandlesLargeFiles_WithoutOverflow()
    {
        long total = 50L * 1024 * 1024 * 1024;
        long half = total / 2;
        var progress = TransferProgress.Compute("t", total, half, 10_000, 100_000_000.0,
            TransferEngineState.TRANSFERRING);
        Assert.Equal(50.0, progress.Percent, precision: 9);
        Assert.Equal(half, progress.TransferredBytes);
        Assert.NotNull(progress.EtaMs);
        var zero = TransferProgress.Compute("t", 0L, 0L, 0L, null, TransferEngineState.TRANSPORT_COMPLETE);
        Assert.Equal(100.0, zero.Percent, precision: 9);
    }

    [Fact]
    public async Task Progress_IsMonotonic_AndEndsAt100Percent()
    {
        using var h = CreateHarness();
        var outcome = await SendAsync(h.Engine, new PatternSource("prog.bin", 2L * 1024 * 1024), h.Progress);
        Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        Assert.NotEmpty(h.Progress);
        long last = -1L;
        foreach (var snapshot in h.Progress)
        {
            Assert.True(snapshot.TransferredBytes >= last);
            last = snapshot.TransferredBytes;
        }
        Assert.Equal(100.0, h.Progress.Last().Percent, precision: 9);
        Assert.Contains(h.Progress, s => s.State == TransferEngineState.TRANSFERRING);
    }

    [Fact]
    public async Task RejectedTransfer_NeverOpensDataConnection()
    {
        using var h = CreateHarness(accept: false);
        var outcome = await SendAsync(h.Engine, new PatternSource("rej.bin", 1024), h.Progress);
        var failed = Assert.IsType<TransferOutcome.Failed>(outcome);
        Assert.Equal(ErrorCode.TRANSFER_REJECTED, failed.Code);
        // No data connection arrives (short accept window expires).
        var probe = await h.Fake.DataListener.AcceptTcpClientAsync()
            .WaitAsync(TimeSpan.FromMilliseconds(1500))
            .ContinueWith(t => t.IsCompletedSuccessfully ? t.Result : null);
        Assert.Null(probe);
        probe?.Dispose();
    }

    [Fact]
    public async Task CancelMidTransfer_AbortsCleanly()
    {
        using var h = CreateHarness();
        using var cts = new CancellationTokenSource();
        // Cancel after the first progress snapshot lands.
        _ = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested && h.Progress.IsEmpty)
                await Task.Delay(5);
            await cts.CancelAsync();
        });
        var outcome = await SendAsync(
            h.Engine, new PatternSource("cancel.bin", 64L * 1024 * 1024), h.Progress, cts.Token);
        Assert.IsType<TransferOutcome.Cancelled>(outcome);
        // Receiver observed CANCEL on control (poll; sender/receiver threads differ).
        var deadline = DateTimeOffset.UtcNow.AddSeconds(8);
        while (DateTimeOffset.UtcNow < deadline &&
               !h.Fake.ReceivedControl.Any(m => m.Type == MessageType.CANCEL))
            await Task.Delay(50);
        Assert.Contains(h.Fake.ReceivedControl, m => m.Type == MessageType.CANCEL);
    }

    [Fact]
    public async Task SourceFailure_MapsToInternalError()
    {
        using var h = CreateHarness();
        var bad = new ThrowingSource("bad.bin", 1024L);
        var outcome = await SendAsync(h.Engine, bad, h.Progress);
        var failed = Assert.IsType<TransferOutcome.Failed>(outcome);
        Assert.Equal(ErrorCode.INTERNAL_ERROR, failed.Code);
    }

    private sealed class ThrowingSource(string name, long size) : ITransferSource
    {
        public string DisplayName => name;
        public long SizeBytes => size;
        public long LastModifiedMs => 0L;
        public Stream OpenStream() => new ThrowingStream();
        private sealed class ThrowingStream : Stream
        {
            public override bool CanRead => true;
            public override bool CanSeek => false;
            public override bool CanWrite => false;
            public override long Length => throw new NotSupportedException();
            public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
            public override void Flush() { }
            public override int Read(byte[] buffer, int offset, int count) => throw new IOException("boom");
            public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
            public override void SetLength(long value) => throw new NotSupportedException();
            public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        }
    }

    [Fact]
    public async Task DataConnectionRefused_MapsToNetworkLostOrTimeout()
    {
        var senderDevice = new DeviceInfo(SenderId, "PcA", "windows");
        var receiverDevice = new DeviceInfo(ReceiverId, "PhoneB", "android");
        using var fake = new FakeReceiver(accept: true);
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => fake.Listener);
        server.Start();
        try
        {
            var mailbox = new TransferMailbox();
            var control = new ControlConnection(senderDevice, mailbox);
            Assert.True(await control.ConnectAsync("127.0.0.1", server.LocalPort));
            try
            {
                int deadPort;
                using (var probe = new TcpListener(IPAddress.Loopback, 0))
                {
                    probe.Start();
                    deadPort = ((IPEndPoint)probe.LocalEndpoint).Port;
                }
                var engine = new SenderEngine(SenderId, control, mailbox, dataPort: deadPort);
                var outcome = await engine.SendAsync(new SenderEngine.SendRequest(
                    new PatternSource("x.bin", 1024), ReceiverId, DataHost: "127.0.0.1"));
                var failed = Assert.IsType<TransferOutcome.Failed>(outcome);
                Assert.True(failed.Code is ErrorCode.NETWORK_LOST or ErrorCode.TIMEOUT);
            }
            finally
            {
                await control.DisconnectAsync();
            }
        }
        finally
        {
            await server.StopAsync();
            server.Dispose();
        }
    }

    [Fact]
    public async Task NoAcceptReply_MapsToTimeout()
    {
        var senderDevice = new DeviceInfo(SenderId, "PcA", "windows");
        var receiverDevice = new DeviceInfo(ReceiverId, "PhoneB", "android");
        var silent = new SilentListener();
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => silent);
        server.Start();
        try
        {
            var mailbox = new TransferMailbox();
            var control = new ControlConnection(senderDevice, mailbox);
            Assert.True(await control.ConnectAsync("127.0.0.1", server.LocalPort));
            try
            {
                var engine = new SenderEngine(SenderId, control, mailbox, requestTimeoutMs: 1000);
                var outcome = await engine.SendAsync(new SenderEngine.SendRequest(
                    new PatternSource("x.bin", 1024), ReceiverId, DataHost: "127.0.0.1"));
                var failed = Assert.IsType<TransferOutcome.Failed>(outcome);
                Assert.Equal(ErrorCode.TIMEOUT, failed.Code);
            }
            finally
            {
                await control.DisconnectAsync();
            }
        }
        finally
        {
            await server.StopAsync();
            server.Dispose();
        }
    }

    private sealed class SilentListener : IControlEvents
    {
        public void OnMessage(ControlConnection connection, LocalDropMessage message) { }
        public void OnClosed(ControlConnection connection, CloseReason reason, string detail) { }
    }

    [Fact]
    public async Task SlowReceiver_BackpressureHolds_WithoutGrowth()
    {
        // Receiver drains 64 KiB per ~20 ms (~3 MB/s): the sender must slow to
        // the receiver (blocking writes = backpressure, ADR 11) and still
        // finish byte-exact with bounded buffers.
        using var h = CreateHarness(throttleMsPerRead: 20);
        long size = 4L * 1024 * 1024;
        var source = new PatternSource("slow.bin", size);
        var start = DateTimeOffset.UtcNow;
        var outcome = await SendAsync(h.Engine, source, h.Progress);
        var elapsed = DateTimeOffset.UtcNow - start;
        var done = Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        // 4 MiB at ~3 MB/s must take well over a second: proves the sender
        // actually waited on the receiver (not fire-and-forget).
        Assert.True(elapsed > TimeSpan.FromSeconds(1), $"elapsed={elapsed.TotalMilliseconds}ms");
        Assert.True(await h.Fake.DataDone.Task.WaitAsync(TimeSpan.FromSeconds(30)));
        Assert.Equal(source.ExpectedBytes(), h.Fake.ReceivedBytes.ToArray());
        Assert.InRange(done.Metrics.FinalBlockSizeBytes, 65536, 1048576);
    }

    [Fact]
    public async Task InvalidFileName_FailsBeforeNetwork()
    {
        using var h = CreateHarness();
        var outcome = await SendAsync(h.Engine, new PatternSource("a/b.bin", 10), h.Progress);
        Assert.IsType<TransferOutcome.Failed>(outcome);
        Assert.Empty(h.Fake.ReceivedControl);
    }

    [Fact]
    public async Task PreCancelledToken_ReturnsCancelledWithoutThrow()
    {
        // Verification audit F3: a token cancelled before the request wait
        // must map to Cancelled (never escape as TaskCanceledException).
        using var h = CreateHarness();
        using var cts = new CancellationTokenSource();
        await cts.CancelAsync();
        TransferOutcome outcome = await h.Engine.SendAsync(new SenderEngine.SendRequest(
            new PatternSource("x.bin", 1024), ReceiverId, DataHost: "127.0.0.1",
            CancellationToken: cts.Token));
        Assert.IsType<TransferOutcome.Cancelled>(outcome);
    }

    [Fact]
    public async Task CancelDuringRequestWait_ReturnsCancelledPromptly()
    {
        // Silent receiver: no ACCEPT ever arrives; cancelling mid-wait must
        // yield Cancelled (not a timeout failure, not an exception).
        var senderDevice = new DeviceInfo(SenderId, "PcA", "windows");
        var receiverDevice = new DeviceInfo(ReceiverId, "PhoneB", "android");
        var silent = new SilentListener();
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => silent);
        server.Start();
        try
        {
            var mailbox = new TransferMailbox();
            var control = new ControlConnection(senderDevice, mailbox);
            Assert.True(await control.ConnectAsync("127.0.0.1", server.LocalPort));
            try
            {
                using var cts = new CancellationTokenSource();
                var engine = new SenderEngine(SenderId, control, mailbox,
                    dataPort: 1, requestTimeoutMs: 5000);
                var sendTask = engine.SendAsync(new SenderEngine.SendRequest(
                    new PatternSource("x.bin", 1024), ReceiverId, DataHost: "127.0.0.1",
                    CancellationToken: cts.Token));
                await Task.Delay(200);
                await cts.CancelAsync();
                // Mailbox wait is bounded by the 5 s request timeout; the
                // outcome must be Cancelled (cancel observed), never TIMEOUT.
                var outcome = await sendTask.WaitAsync(TimeSpan.FromSeconds(10));
                Assert.IsType<TransferOutcome.Cancelled>(outcome);
            }
            finally
            {
                await control.DisconnectAsync();
            }
        }
        finally
        {
            await server.StopAsync();
            server.Dispose();
        }
    }
}
