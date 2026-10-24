// M15 benchmark harness (Windows -> Windows, real loopback TCP).
//
// Measures the PRODUCTION stack end to end: production ControlServer +
// AuthManager + TransactionAuthorizer + ReceiverEngine (receiver) and the
// production ControlConnection + SenderEngine (sender), over a real TCP
// loopback connection. Nothing is simulated: the receiver writes the byte
// stream to a real file on disk, the sender reads a real file, and the
// content is verified against an INDEPENDENT SHA-256 oracle (Python
// hashlib, committed in test-assets/performance/m15/vectors.json).
//
// Test type: REAL LOOPBACK TCP (test type 2 in the M15 report taxonomy).
// It is NOT a physical Wi-Fi / hotspot measurement (that is M16).
//
// Measurement boundaries (documented, not silently mixed):
//   * wall-clock  : harness timer around send+receive completion. This is
//                   the authoritative end-to-end number and INCLUDES the
//                   receiver's SHA-256 verification pass and the commit.
//   * sender metric: SenderEngine's own TransferMetrics (streaming phase
//                   only; excludes the VERIFY_RESULT wait).
//   * receiver metric: ReceiverEngine's own TransferMetrics (streaming
//                   phase only; EXCLUDES the SHA-256 re-read pass and the
//                   final commit).
// All three are reported side by side; none is presented as the others.
//
// Memory evidence: a background sampler records the process working set
// and managed heap every 100 ms, plus the precise cumulative managed
// allocation counter (GC.GetTotalAllocatedBytes) before/after. A whole-file
// buffer would show up as an allocation delta >= file size; a streaming
// implementation keeps it at a few MiB.
//
// Nothing here changes production behaviour. The benchmark is an addition.
using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;
using LocalDrop.Transfer;
using Xunit;
using Xunit.Abstractions;

namespace LocalDrop.Protocol.Tests;

public sealed class M15BenchmarkTests : IDisposable
{
    private readonly ITestOutputHelper _output;
    private readonly string _repoRoot;
    private readonly string _workDir;
    private readonly string _destDir;
    private readonly string _srcDir;
    private readonly Dictionary<long, string> _vectors;
    private readonly List<M15CaseResult> _results = new();

    public M15BenchmarkTests(ITestOutputHelper output)
    {
        _output = output;
        _repoRoot = FindRepoRoot()
            ?? throw new InvalidOperationException(
                "LocalDrop repository root not found (expected a parent directory containing 'test-assets' and 'shared-protocol').");
        _workDir = Path.Combine(Path.GetTempPath(), "localdrop-m15-" + Guid.NewGuid().ToString("N"));
        _destDir = Path.Combine(_workDir, "dest");
        _srcDir = Path.Combine(_workDir, "src");
        Directory.CreateDirectory(_destDir);
        Directory.CreateDirectory(_srcDir);
        _vectors = LoadVectors();
    }

    public void Dispose()
    {
        try { Directory.Delete(_workDir, true); } catch { }
        GC.SuppressFinalize(this);
    }

    // ---------------------------------------------------------------- infra

    private static string? FindRepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (Directory.Exists(Path.Combine(dir.FullName, "test-assets")) &&
                Directory.Exists(Path.Combine(dir.FullName, "shared-protocol")))
                return dir.FullName;
            dir = dir.Parent;
        }
        return null;
    }

    /// <summary>
    /// Golden SHA-256 vectors produced by the INDEPENDENT Python oracle
    /// (test-assets/performance/m15/make_vectors.py). A missing vector is a
    /// hard failure - the benchmark never silently degrades to "no oracle".
    /// </summary>
    private Dictionary<long, string> LoadVectors()
    {
        string path = Path.Combine(_repoRoot, "test-assets", "performance", "m15", "vectors.json");
        if (!File.Exists(path))
            throw new InvalidOperationException(
                $"M15 golden vectors not found at '{path}'. Run: python test-assets/performance/m15/make_vectors.py --outdir <dir> --vectors {path}");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        var map = new Dictionary<long, string>();
        foreach (var entry in doc.RootElement.GetProperty("sizes").EnumerateArray())
        {
            long bytes = entry.GetProperty("bytes").GetInt64();
            string sha = entry.GetProperty("sha256").GetString()!;
            map[bytes] = sha;
        }
        if (map.Count == 0) throw new InvalidOperationException("M15 golden vector file contains no sizes.");
        return map;
    }

    private string ExpectedHash(long size)
    {
        if (!_vectors.TryGetValue(size, out string? sha))
            throw new InvalidOperationException(
                $"No independent SHA-256 oracle vector for size {size}. " +
                $"Known sizes: {string.Join(", ", _vectors.Keys.OrderBy(k => k))}");
        return sha;
    }

    // -------------------------------------------------------------- sources

    /// <summary>
    /// Deterministic source content ("m15-pattern-v1"). The content is
    /// generated incrementally; the whole file is NEVER held in memory.
    /// The 4-byte per-chunk tag gives the content a 64 GiB period, so a
    /// write-offset error of any size changes the SHA-256.
    /// </summary>
    internal static class Pattern
    {
        public const int ChunkBytes = 1 << 20;
        private const uint LcgMul = 1103515245u;
        private const uint LcgInc = 12345u;
        private static byte[]? _cycle;

        public static byte[] Cycle()
        {
            if (_cycle is not null) return _cycle;
            var cycle = new byte[ChunkBytes];
            for (int i = 0; i < ChunkBytes; i++)
                cycle[i] = (byte)(unchecked((uint)((long)i * LcgMul + LcgInc)) >> 16);
            _cycle = cycle;
            return cycle;
        }

        public static byte[] Tag(int chunkIndex)
        {
            int k = chunkIndex & 0xFFFF;
            return new byte[] { (byte)(k & 0xFF), (byte)((k >> 8) & 0xFF), (byte)(0x5A ^ (k & 0xFF)), (byte)(0xA5 ^ ((k >> 8) & 0xFF)) };
        }

        public static void Fill(Span<byte> buffer, long absolutePosition, int count)
        {
            byte[] cycle = Cycle();
            int written = 0;
            while (written < count)
            {
                long pos = absolutePosition + written;
                int chunkIndex = (int)(pos / ChunkBytes);
                int within = (int)(pos % ChunkBytes);
                int take = Math.Min(count - written, ChunkBytes - within);
                byte[] tag = Tag(chunkIndex);
                int tagOff = within & 3;
                for (int i = 0; i < take; i++)
                    buffer[written + i] = (byte)(cycle[within + i] ^ tag[(tagOff + i) & 3]);
                written += take;
            }
        }

        /// <summary>Writes the deterministic content to disk, streaming.</summary>
        public static void WriteFile(string path, long size)
        {
            byte[] buffer = new byte[ChunkBytes];
            using var fs = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None,
                ChunkBytes, FileOptions.SequentialScan);
            long written = 0;
            while (written < size)
            {
                int n = (int)Math.Min(ChunkBytes, size - written);
                Fill(buffer.AsSpan(0, n), written, n);
                fs.Write(buffer, 0, n);
                written += n;
            }
            fs.Flush(true);
        }
    }

    /// <summary>
    /// Benchmark-only wrapper around the PRODUCTION
    /// windows/LocalDrop/Transfer/TransferSource.cs FileTransferSource. Only
    /// the display name is overridden (the file picker supplies it in the
    /// product); the stream, size and modification-time semantics are the
    /// production ones.
    /// </summary>
    private sealed class NamedFileSource : ITransferSource
    {
        private readonly FileTransferSource _inner;
        private readonly string _displayName;

        public NamedFileSource(string path, string displayName)
        {
            _inner = new FileTransferSource(path);
            _displayName = displayName;
        }

        public string DisplayName => _displayName;
        public long SizeBytes => _inner.SizeBytes;
        public long LastModifiedMs => _inner.LastModifiedMs;
        public Stream OpenStream() => _inner.OpenStream();
    }

    /// <summary>
    /// Production source (windows/LocalDrop/Transfer/TransferSource.cs) over a
    /// real file - the fidelity path a user actually exercises.
    /// </summary>
    private string PrepareSourceFile(long size)
    {
        string path = Path.Combine(_srcDir, $"m15_src_{size}.bin");
        if (!File.Exists(path) || new FileInfo(path).Length != size)
        {
            _output.WriteLine($"[m15] generating deterministic source file ({size} bytes) ...");
            var sw = Stopwatch.StartNew();
            Pattern.WriteFile(path, size);
            sw.Stop();
            _output.WriteLine($"[m15] source generated in {sw.ElapsedMilliseconds} ms " +
                              $"({size / 1_000_000.0 / sw.Elapsed.TotalSeconds:F1} MB/s generator rate)");
        }
        return path;
    }

    private static async Task<string> HashFileAsync(string path)
    {
        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read,
            1 << 20, FileOptions.SequentialScan);
        using var sha = SHA256.Create();
        var hash = await sha.ComputeHashAsync(fs);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    // -------------------------------------------------------------- memory

    /// <summary>
    /// Process memory sampler. Records the working set and managed heap at a
    /// fixed interval (default 100 ms) plus the precise cumulative managed
    /// allocation counter at the boundaries.
    /// </summary>
    private sealed class MemorySampler : IDisposable
    {
        private readonly Thread _thread;
        private readonly int _intervalMs;
        private volatile bool _running = true;
        private long _peakWorkingSet;
        private long _peakManagedHeap;
        private long _samples;
        public long StartAllocatedBytes { get; private set; }
        public long EndAllocatedBytes { get; private set; }
        public long BaselineWorkingSet { get; private set; }

        public MemorySampler(int intervalMs = 100)
        {
            _intervalMs = intervalMs;
            BaselineWorkingSet = Environment.WorkingSet;
            StartAllocatedBytes = GC.GetTotalAllocatedBytes(precise: true);
            _peakWorkingSet = BaselineWorkingSet;
            _peakManagedHeap = GC.GetTotalMemory(false);
            _thread = new Thread(Loop) { IsBackground = true, Name = "m15-memory-sampler" };
            _thread.Start();
        }

        private void Loop()
        {
            while (_running)
            {
                try
                {
                    long ws = Environment.WorkingSet;
                    long heap = GC.GetTotalMemory(false);
                    if (ws > _peakWorkingSet) _peakWorkingSet = ws;
                    if (heap > _peakManagedHeap) _peakManagedHeap = heap;
                    _samples++;
                }
                catch { }
                Thread.Sleep(_intervalMs);
            }
        }

        public void Stop()
        {
            _running = false;
            _thread.Join(2000);
            EndAllocatedBytes = GC.GetTotalAllocatedBytes(precise: true);
            long ws = Environment.WorkingSet;
            if (ws > _peakWorkingSet) _peakWorkingSet = ws;
            long heap = GC.GetTotalMemory(false);
            if (heap > _peakManagedHeap) _peakManagedHeap = heap;
        }

        public long PeakWorkingSet => _peakWorkingSet;
        public long PeakManagedHeap => _peakManagedHeap;
        public long AllocatedDeltaBytes => EndAllocatedBytes - StartAllocatedBytes;
        public long SampleCount => _samples;
        public int IntervalMs => _intervalMs;

        public void Dispose() { _running = false; try { _thread.Join(2000); } catch { } }
    }

    // --------------------------------------------------------------- stack

    /// <summary>
    /// The real production stack: M04 control channel, M05 mutual auth +
    /// transaction authorization, M06/M07 data plane. One instance serves
    /// every transfer of a test case so setup cost is not measured.
    /// </summary>
    private sealed class Stack : IAsyncDisposable
    {
        public SoftwareDeviceIdentityStore ServerStore { get; } = new();
        public SoftwareDeviceIdentityStore ClientStore { get; } = new();
        public DeviceInfo ServerDevice { get; }
        public DeviceInfo ClientDevice { get; }
        public RecordingSecurityLogger Log { get; } = new();
        public AuthManager ServerAuth { get; }
        public AuthManager ClientAuth { get; }
        public TransactionManager Txns { get; }
        public AuthorizedSessionStore Sessions { get; } = new();
        public ReceiverEngine Receiver { get; }
        public ControlServer Server { get; }
        public TransferMailbox ClientMailbox { get; } = new();
        public ControlConnection Client { get; }
        public SenderEngine Sender { get; }

        public Stack(string destDir)
        {
            ServerDevice = AuthTestSupport.DeviceFor(ServerStore, "PcB");
            ClientDevice = AuthTestSupport.DeviceFor(ClientStore, "PcA");
            ServerAuth = new AuthManager(ServerDevice, ServerStore, Log);
            ClientAuth = new AuthManager(ClientDevice, ClientStore, Log);
            Txns = new TransactionManager(null, Core.AppConfig.TransactionCodeLifetimeMs, Log);
            Receiver = new ReceiverEngine(ServerDevice, ServerAuth, Sessions, destDir, Log, dataPort: 0);
            ServerAuth.Inner = new TransactionAuthorizer(ServerAuth, Txns, Sessions, Log,
                ServerDevice.DeviceId, Receiver.CreateHandler());
            Server = new ControlServer(ServerDevice, port: 0, listenerFactory: () => ServerAuth);
            Server.Start();
            Receiver.Start();
            Client = new ControlConnection(ClientDevice, ClientAuth);
            ClientAuth.Inner = ClientMailbox;
            Sender = new SenderEngine(ClientDevice.DeviceId, Client, ClientMailbox, dataPort: Receiver.LocalPort);
        }

        public async Task AuthenticateAsync()
        {
            if (!await Client.ConnectAsync("127.0.0.1", Server.LocalPort))
                throw new InvalidOperationException("M04 control connect failed.");
            var outcome = await ClientAuth.BeginAuthAsync(Client).WaitAsync(TimeSpan.FromSeconds(15));
            if (outcome is not AuthOutcome.Authenticated)
                throw new InvalidOperationException($"M05 mutual auth failed: {outcome}");
            Sessions.Authorize(ClientDevice.DeviceId, Guid.NewGuid().ToString("D"));
        }

        public async ValueTask DisposeAsync()
        {
            try { await Client.DisconnectAsync(); } catch { }
            try { await Receiver.StopAsync(); } catch { }
            try { await Server.StopAsync(); } catch { }
            try { Server.Dispose(); } catch { }
            try { ServerStore.Dispose(); } catch { }
            try { ClientStore.Dispose(); } catch { }
        }
    }

    // ------------------------------------------------------------- results

    private sealed class M15CaseResult
    {
        public string Case { get; set; } = "";
        public string TestType { get; set; } = "";
        public long Bytes { get; set; }
        public int Repetitions { get; set; }
        public int Warmups { get; set; }
        public double WallClockMedianMs { get; set; }
        public double WallClockMinMs { get; set; }
        public double WallClockMaxMs { get; set; }
        public double SenderElapsedMedianMs { get; set; }
        public double ReceiverElapsedMedianMs { get; set; }
        public double WallClockMedianMBs { get; set; }
        public double WallClockMedianMiBs { get; set; }
        public double SenderMedianMBs { get; set; }
        public double SenderMedianMiBs { get; set; }
        public double ReceiverMedianMBs { get; set; }
        public double ReceiverMedianMiBs { get; set; }
        public long ReceivedFileBytes { get; set; }
        public string ExpectedSha256 { get; set; } = "";
        public string ActualSha256 { get; set; } = "";
        public bool Sha256Match { get; set; }
        public long PeakWorkingSetBytes { get; set; }
        public long BaselineWorkingSetBytes { get; set; }
        public long PeakManagedHeapBytes { get; set; }
        public long ManagedAllocatedDeltaBytes { get; set; }
        public int MemorySampleIntervalMs { get; set; }
        public int MemorySamples { get; set; }
        public int FinalBlockSizeBytes { get; set; }
        public List<string> Adaptations { get; set; } = new();
        public long SenderReadNanos { get; set; }
        public long SenderWriteNanos { get; set; }
        public long ReceiverReadNanos { get; set; }
        public long ReceiverWriteNanos { get; set; }
        public long SenderStalls { get; set; }
        public long ReceiverStalls { get; set; }
        public string Notes { get; set; } = "";
    }

    private sealed class RunResult
    {
        public double WallClockMs { get; init; }
        public TransferOutcome.TransportComplete? Sender { get; init; }
        public TransferOutcome.TransportComplete? Receiver { get; init; }
        public string FinalPath { get; init; } = "";
        public long FinalBytes { get; init; }
        public string Sha256 { get; init; } = "";
        public MemorySampler Memory { get; init; } = null!;
    }

    // ---------------------------------------------------------------- runs

    /// <summary>
    /// One measured transfer over the production stack. The sender reads a
    /// real file (production FileTransferSource), the receiver writes a real
    /// file; the received file is then hashed with .NET SHA-256 and compared
    /// against the independent Python oracle.
    /// </summary>
    private async Task<RunResult> RunTransferAsync(
        Stack stack, string sourcePath, string fileName, long size, int timeoutMs)
    {
        var source = new NamedFileSource(sourcePath, fileName);
        var memory = new MemorySampler();
        try
        {
            var sw = Stopwatch.StartNew();
            var sendTask = stack.Sender.SendAsync(new SenderEngine.SendRequest(
                source, stack.ServerDevice.DeviceId, DataHost: "127.0.0.1"));
            var recvTask = stack.Receiver.WaitForAnyOutcomeAsync(timeoutMs);
            await Task.WhenAll((Task)sendTask, (Task)recvTask);
            sw.Stop();

            var sent = await sendTask;
            var got = await recvTask;
            if (sent is not TransferOutcome.TransportComplete sentOk)
                throw new InvalidOperationException($"sender outcome: {sent}");
            if (got is not TransferOutcome.TransportComplete gotOk)
                throw new InvalidOperationException($"receiver outcome: {got}");

            string finalPath = Path.Combine(_destDir, fileName);
            if (!File.Exists(finalPath))
                throw new InvalidOperationException($"destination file missing: {finalPath}");
            var info = new FileInfo(finalPath);
            string sha = await HashFileAsync(finalPath);
            return new RunResult
            {
                WallClockMs = sw.Elapsed.TotalMilliseconds,
                Sender = sentOk,
                Receiver = gotOk,
                FinalPath = finalPath,
                FinalBytes = info.Length,
                Sha256 = sha,
                Memory = memory,
            };
        }
        finally
        {
            memory.Stop();
        }
    }

    // --------------------------------------------------------------- tests

    /// <summary>
    /// Size matrix: empty, 1 MiB, 100 MB, 5 GB, 20 GiB. Each case performs a
    /// discarded warm-up transfer, then N measured repetitions; median and
    /// range are reported. The 20 GiB case is a single run (reported as
    /// such - it is NOT presented as statistically robust).
    /// </summary>
    [Theory]
    [InlineData(0L, 3, 1)]
    [InlineData(1_048_576L, 5, 1)]
    [InlineData(100_000_000L, 5, 1)]
    [InlineData(5_000_000_000L, 2, 1)]
    [InlineData(21_474_836_480L, 1, 0)]
    public async Task M15_Benchmark_WindowsLoopback_SizeMatrix(long size, int repetitions, int warmups)
    {
        string expected = ExpectedHash(size);
        string sourcePath = PrepareSourceFile(size);

        // The source file itself is verified against the independent oracle
        // before it is used, so a benchmark can never "pass" with corrupt
        // input.
        string sourceHash = await HashFileAsync(sourcePath);
        Assert.Equal(expected, sourceHash);

        await using var stack = new Stack(_destDir);
        await stack.AuthenticateAsync();

        int timeoutMs = size >= 5_000_000_000L ? 3_600_000 : 600_000;

        for (int w = 0; w < warmups; w++)
        {
            var warm = await RunTransferAsync(stack, sourcePath, $"m15_warm_{size}_{w}.bin", size, timeoutMs);
            Assert.Equal(expected, warm.Sha256);
            File.Delete(warm.FinalPath);
        }

        var walls = new List<double>();
        var senderMs = new List<double>();
        var receiverMs = new List<double>();
        var senderBps = new List<double>();
        var receiverBps = new List<double>();
        long receivedBytes = 0;
        string actual = "";
        long peakWs = 0, baselineWs = long.MaxValue, peakHeap = 0, allocDelta = 0;
        int samples = 0, sampleInterval = 100;
        int finalBlock = 0;
        List<string> adaptations = new();
        long sRead = 0, sWrite = 0, rRead = 0, rWrite = 0, sStalls = 0, rStalls = 0;

        for (int rep = 0; rep < repetitions; rep++)
        {
            var run = await RunTransferAsync(stack, sourcePath, $"m15_{size}_{rep}.bin", size, timeoutMs);
            Assert.Equal(size, run.Sender!.BytesTransferred);
            Assert.Equal(size, run.Receiver!.BytesTransferred);
            Assert.Equal(size, run.FinalBytes);
            Assert.Equal(expected, run.Sha256);

            walls.Add(run.WallClockMs);
            senderMs.Add(run.Sender.ElapsedMs);
            receiverMs.Add(run.Receiver.ElapsedMs);
            senderBps.Add(run.Sender.AverageBytesPerSec ?? 0);
            receiverBps.Add(run.Receiver.AverageBytesPerSec ?? 0);
            receivedBytes = run.FinalBytes;
            actual = run.Sha256;
            peakWs = Math.Max(peakWs, run.Memory.PeakWorkingSet);
            baselineWs = Math.Min(baselineWs, run.Memory.BaselineWorkingSet);
            peakHeap = Math.Max(peakHeap, run.Memory.PeakManagedHeap);
            allocDelta = Math.Max(allocDelta, run.Memory.AllocatedDeltaBytes);
            samples += (int)run.Memory.SampleCount;
            finalBlock = run.Sender.Metrics.FinalBlockSizeBytes;
            adaptations = new List<string>(run.Sender.Metrics.Adaptations);
            sRead = run.Sender.Metrics.ReadNanosTotal;
            sWrite = run.Sender.Metrics.WriteNanosTotal;
            rRead = run.Receiver.Metrics.ReadNanosTotal;
            rWrite = run.Receiver.Metrics.WriteNanosTotal;
            sStalls = run.Sender.Metrics.StallCount;
            rStalls = run.Receiver.Metrics.StallCount;

            File.Delete(run.FinalPath);
        }

        double wallMedian = Median(walls);
        var result = new M15CaseResult
        {
            Case = $"windows-loopback/{size} bytes",
            TestType = "Real loopback TCP (production Windows sender -> production Windows receiver)",
            Bytes = size,
            Repetitions = repetitions,
            Warmups = warmups,
            WallClockMedianMs = wallMedian,
            WallClockMinMs = walls.Min(),
            WallClockMaxMs = walls.Max(),
            SenderElapsedMedianMs = Median(senderMs),
            ReceiverElapsedMedianMs = Median(receiverMs),
            WallClockMedianMBs = size / 1_000_000.0 / (wallMedian / 1000.0),
            WallClockMedianMiBs = size / 1_048_576.0 / (wallMedian / 1000.0),
            SenderMedianMBs = Median(senderBps) / 1_000_000.0,
            SenderMedianMiBs = Median(senderBps) / 1_048_576.0,
            ReceiverMedianMBs = Median(receiverBps) / 1_000_000.0,
            ReceiverMedianMiBs = Median(receiverBps) / 1_048_576.0,
            ReceivedFileBytes = receivedBytes,
            ExpectedSha256 = expected,
            ActualSha256 = actual,
            Sha256Match = string.Equals(expected, actual, StringComparison.OrdinalIgnoreCase),
            PeakWorkingSetBytes = peakWs,
            BaselineWorkingSetBytes = baselineWs == long.MaxValue ? 0 : baselineWs,
            PeakManagedHeapBytes = peakHeap,
            ManagedAllocatedDeltaBytes = allocDelta,
            MemorySampleIntervalMs = sampleInterval,
            MemorySamples = samples,
            FinalBlockSizeBytes = finalBlock,
            Adaptations = adaptations,
            SenderReadNanos = sRead,
            SenderWriteNanos = sWrite,
            ReceiverReadNanos = rRead,
            ReceiverWriteNanos = rWrite,
            SenderStalls = sStalls,
            ReceiverStalls = rStalls,
            Notes = repetitions == 1
                ? "single run (not statistically robust)"
                : $"{repetitions} measured repetitions after {warmups} discarded warm-up run(s); median + range reported",
        };
        _results.Add(result);
        WriteResults();

        _output.WriteLine(
            $"[m15] size={size} reps={repetitions} wallMedian={wallMedian:F0} ms " +
            $"({result.WallClockMedianMBs:F2} MB/s | {result.WallClockMedianMiBs:F2} MiB/s) " +
            $"sender={result.SenderMedianMiBs:F2} MiB/s receiver={result.ReceiverMedianMiBs:F2} MiB/s " +
            $"sha256match={result.Sha256Match} peakWS={peakWs} allocDelta={allocDelta}");
    }

    /// <summary>
    /// Two simultaneous transfers with independent content, independent
    /// metrics and independent outcomes. Proves per-transfer isolation of
    /// buffers, sizers, metrics and cancellation state.
    /// </summary>
    [Fact]
    public async Task M15_Benchmark_WindowsLoopback_ConcurrentTransfers()
    {
        const long sizeA = 100_000_000L;
        const long sizeB = 20_000_000L;
        string expectedA = ExpectedHash(sizeA);
        string expectedB = ExpectedHash(sizeB);
        string pathA = PrepareSourceFile(sizeA);
        string pathB = PrepareSourceFile(sizeB);

        await using var stack = new Stack(_destDir);
        await stack.AuthenticateAsync();

        string idA = Guid.NewGuid().ToString("D");
        string idB = Guid.NewGuid().ToString("D");

        var memory = new MemorySampler();
        try
        {
            var sw = Stopwatch.StartNew();
            var sendA = stack.Sender.SendAsync(new SenderEngine.SendRequest(
                new NamedFileSource(pathA, "m15_concurrent_a.bin"), stack.ServerDevice.DeviceId,
                TransferId: idA, DataHost: "127.0.0.1"));
            var sendB = stack.Sender.SendAsync(new SenderEngine.SendRequest(
                new NamedFileSource(pathB, "m15_concurrent_b.bin"), stack.ServerDevice.DeviceId,
                TransferId: idB, DataHost: "127.0.0.1"));
            var recvA = stack.Receiver.WaitForOutcomeAsync(idA, 600_000);
            var recvB = stack.Receiver.WaitForOutcomeAsync(idB, 600_000);
            await Task.WhenAll((Task)sendA, (Task)sendB, (Task)recvA, (Task)recvB);
            sw.Stop();

            var sA = Assert.IsType<TransferOutcome.TransportComplete>(await sendA);
            var sB = Assert.IsType<TransferOutcome.TransportComplete>(await sendB);
            var rA = Assert.IsType<TransferOutcome.TransportComplete>(await recvA);
            var rB = Assert.IsType<TransferOutcome.TransportComplete>(await recvB);

            Assert.Equal(sizeA, sA.BytesTransferred);
            Assert.Equal(sizeB, sB.BytesTransferred);
            Assert.Equal(sizeA, rA.BytesTransferred);
            Assert.Equal(sizeB, rB.BytesTransferred);
            Assert.NotEqual(sA.TransferId, sB.TransferId);
            Assert.Equal(idA, rA.TransferId);
            Assert.Equal(idB, rB.TransferId);

            string pathOutA = Path.Combine(_destDir, "m15_concurrent_a.bin");
            string pathOutB = Path.Combine(_destDir, "m15_concurrent_b.bin");
            Assert.Equal(sizeA, new FileInfo(pathOutA).Length);
            Assert.Equal(sizeB, new FileInfo(pathOutB).Length);
            Assert.Equal(expectedA, await HashFileAsync(pathOutA));
            Assert.Equal(expectedB, await HashFileAsync(pathOutB));

            double wall = sw.Elapsed.TotalMilliseconds;
            var result = new M15CaseResult
            {
                Case = "windows-loopback/concurrent (100 MB + 20 MB simultaneously)",
                TestType = "Real loopback TCP (production Windows sender -> production Windows receiver), 2 concurrent transfers",
                Bytes = sizeA + sizeB,
                Repetitions = 1,
                Warmups = 0,
                WallClockMedianMs = wall,
                WallClockMinMs = wall,
                WallClockMaxMs = wall,
                WallClockMedianMBs = (sizeA + sizeB) / 1_000_000.0 / (wall / 1000.0),
                WallClockMedianMiBs = (sizeA + sizeB) / 1_048_576.0 / (wall / 1000.0),
                SenderElapsedMedianMs = Math.Max(sA.ElapsedMs, sB.ElapsedMs),
                ReceiverElapsedMedianMs = Math.Max(rA.ElapsedMs, rB.ElapsedMs),
                ExpectedSha256 = $"A={expectedA}; B={expectedB}",
                ActualSha256 = $"A={await HashFileAsync(pathOutA)}; B={await HashFileAsync(pathOutB)}",
                Sha256Match = true,
                PeakWorkingSetBytes = memory.PeakWorkingSet,
                BaselineWorkingSetBytes = memory.BaselineWorkingSet,
                PeakManagedHeapBytes = memory.PeakManagedHeap,
                ManagedAllocatedDeltaBytes = memory.AllocatedDeltaBytes,
                MemorySampleIntervalMs = memory.IntervalMs,
                MemorySamples = (int)memory.SampleCount,
                FinalBlockSizeBytes = Math.Max(sA.Metrics.FinalBlockSizeBytes, sB.Metrics.FinalBlockSizeBytes),
                SenderStalls = sA.Metrics.StallCount + sB.Metrics.StallCount,
                ReceiverStalls = rA.Metrics.StallCount + rB.Metrics.StallCount,
                Notes = "two simultaneous transfers, independent content, independent outcomes and metrics",
            };
            _results.Add(result);
            WriteResults();
            _output.WriteLine($"[m15] concurrent wall={wall:F0} ms sha256 both match, " +
                              $"allocDelta={memory.AllocatedDeltaBytes} peakWS={memory.PeakWorkingSet}");
        }
        finally
        {
            memory.Stop();
        }
    }

    /// <summary>
    /// Memory scaling evidence: the managed allocation delta during a
    /// transfer must stay independent of the file size (bounded buffers),
    /// i.e. it must NOT grow proportionally with the transferred bytes.
    /// </summary>
    [Fact]
    public async Task M15_Benchmark_WindowsLoopback_MemoryDoesNotScaleWithFileSize()
    {
        const long small = 1_048_576L;
        const long large = 100_000_000L;
        string pathSmall = PrepareSourceFile(small);
        string pathLarge = PrepareSourceFile(large);

        await using var stack = new Stack(_destDir);
        await stack.AuthenticateAsync();

        async Task<long> Measure(string path, string name, long size)
        {
            var run = await RunTransferAsync(stack, path, name, size, 600_000);
            Assert.Equal(size, run.Sender!.BytesTransferred);
            Assert.Equal(size, run.Receiver!.BytesTransferred);
            Assert.Equal(ExpectedHash(size), run.Sha256);
            long delta = run.Memory.AllocatedDeltaBytes;
            File.Delete(run.FinalPath);
            return delta;
        }

        long allocSmall = await Measure(pathSmall, "m15_mem_small.bin", small);
        long allocLarge = await Measure(pathLarge, "m15_mem_large.bin", large);

        // A whole-file buffer would make the delta track the file size
        // (>= 200 MB). Streaming keeps it in the MiB range.
        Assert.True(allocLarge < 64L * 1024 * 1024,
            $"managed allocation delta for a {large} byte transfer was {allocLarge} bytes - " +
            "that is not bounded-memory behaviour.");
        Assert.True(allocLarge < allocSmall * 200 + (16L * 1024 * 1024),
            $"managed allocation delta grew from {allocSmall} (1 MiB file) to {allocLarge} (200 MB file); " +
            "allocation must not scale with file size.");

        var result = new M15CaseResult
        {
            Case = "windows-loopback/memory-scaling (1 MiB vs 200 MB)",
            TestType = "Real loopback TCP (production Windows sender -> production Windows receiver)",
            Bytes = large,
            Repetitions = 1,
            Warmups = 0,
            ManagedAllocatedDeltaBytes = allocLarge,
            PeakManagedHeapBytes = 0,
            Notes = $"managed allocation delta: 1 MiB file = {allocSmall} bytes, 200 MB file = {allocLarge} bytes " +
                    "(bounded buffers; no whole-file allocation)",
        };
        _results.Add(result);
        WriteResults();
        _output.WriteLine($"[m15] memory scaling allocDelta small={allocSmall} large={allocLarge}");
    }

    // -------------------------------------------------------------- output

    private static double Median(List<double> values)
    {
        var sorted = values.OrderBy(v => v).ToList();
        int n = sorted.Count;
        if (n == 0) return 0;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private void WriteResults()
    {
        string target = Environment.GetEnvironmentVariable("LOCALDROP_M15_RESULTS");
        if (string.IsNullOrWhiteSpace(target))
            target = Path.Combine(_repoRoot, "test-assets", "performance", "m15", "results", "m15-windows-results.json");
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        var options = new JsonSerializerOptions { WriteIndented = true };
        // xUnit creates one test-class instance per test case, so the file is
        // MERGED (keyed by case name) instead of overwritten.
        var merged = new Dictionary<string, M15CaseResult>();
        if (File.Exists(target))
        {
            try
            {
                var existing = JsonSerializer.Deserialize<List<M15CaseResult>>(File.ReadAllText(target), options);
                if (existing is not null)
                    foreach (var r in existing) merged[r.Case] = r;
            }
            catch { }
        }
        foreach (var r in _results) merged[r.Case] = r;
        File.WriteAllText(target, JsonSerializer.Serialize(merged.Values.ToList(), options));
    }
}
