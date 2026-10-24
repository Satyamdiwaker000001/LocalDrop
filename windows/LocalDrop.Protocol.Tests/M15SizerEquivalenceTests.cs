// M15 cross-platform adaptive-block-sizer equivalence test.
//
// The .NET and Kotlin AdaptiveBlockSizer implementations must make exactly
// the same decisions for exactly the same observations (Master Spec: both
// platforms stay behaviour-compatible; M06 ADR 3-8). The expected
// decisions in this test come from an INDEPENDENT from-spec Python
// implementation (test-assets/performance/m15/make_sizer_vectors.py), not
// from either production platform, so a shared bug in both platforms is
// still detected.
//
// The vectors are shared by both platforms, so the same file proves
// cross-platform behavioural equivalence.
using System.Text.Json;
using LocalDrop.Transfer;
using Xunit;

namespace LocalDrop.Protocol.Tests;

public sealed class M15SizerEquivalenceTests
{
    private sealed class Scenario
    {
        public string Name { get; set; } = "";
        public long FileSizeBytes { get; set; }
        public List<Step> Steps { get; set; } = new();
        public List<int> ExpectedBlockSizes { get; set; } = new();
        public List<string> ExpectedHistory { get; set; } = new();
    }

    private sealed class Step
    {
        public long AdvanceMs { get; set; }
        public long BlockBytes { get; set; }
        public long ElapsedNanos { get; set; }
        public bool Stalled { get; set; }
    }

    private static string RepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (Directory.Exists(Path.Combine(dir.FullName, "test-assets")) &&
                Directory.Exists(Path.Combine(dir.FullName, "shared-protocol")))
                return dir.FullName;
            dir = dir.Parent;
        }
        throw new InvalidOperationException("LocalDrop repository root not found.");
    }

    private static List<Scenario> LoadScenarios()
    {
        string path = Path.Combine(RepoRoot(), "test-assets", "performance", "m15", "sizer-vectors.json");
        if (!File.Exists(path))
            throw new InvalidOperationException($"M15 sizer vectors not found at '{path}'.");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        var list = new List<Scenario>();
        foreach (var s in doc.RootElement.GetProperty("scenarios").EnumerateArray())
        {
            var scenario = new Scenario
            {
                Name = s.GetProperty("name").GetString()!,
                FileSizeBytes = s.GetProperty("fileSizeBytes").GetInt64(),
            };
            foreach (var st in s.GetProperty("steps").EnumerateArray())
            {
                scenario.Steps.Add(new Step
                {
                    AdvanceMs = st.GetProperty("advanceMs").GetInt64(),
                    BlockBytes = st.GetProperty("blockBytes").GetInt64(),
                    ElapsedNanos = st.GetProperty("elapsedNanos").GetInt64(),
                    Stalled = st.GetProperty("stalled").GetBoolean(),
                });
            }
            foreach (var b in s.GetProperty("expectedBlockSizes").EnumerateArray())
                scenario.ExpectedBlockSizes.Add(b.GetInt32());
            foreach (var h in s.GetProperty("expectedHistory").EnumerateArray())
                scenario.ExpectedHistory.Add(h.GetString()!);
            list.Add(scenario);
        }
        Assert.NotEmpty(list);
        return list;
    }

    [Fact]
    public void WindowsSizer_MatchesTheIndependentOracle()
    {
        foreach (var scenario in LoadScenarios())
        {
            long clock = 0;
            var sizer = new AdaptiveBlockSizer(scenario.FileSizeBytes, nanoTime: () => clock);
            var observed = new List<int> { sizer.CurrentBlockSize };
            foreach (var step in scenario.Steps)
            {
                clock += step.AdvanceMs * 1_000_000L;
                sizer.Observe(step.BlockBytes, step.ElapsedNanos, step.Stalled);
                observed.Add(sizer.CurrentBlockSize);
            }
            Assert.Equal(scenario.ExpectedBlockSizes, observed);
            Assert.Equal(scenario.ExpectedHistory, sizer.History.ToList());
        }
    }

    /// <summary>
    /// Determinism: the same observation sequence replayed on a fresh sizer
    /// with the same fake clock must produce the same decisions.
    /// </summary>
    [Fact]
    public void WindowsSizer_IsDeterministic()
    {
        foreach (var scenario in LoadScenarios())
        {
            List<int> Run()
            {
                long clock = 0;
                var sizer = new AdaptiveBlockSizer(scenario.FileSizeBytes, nanoTime: () => clock);
                var observed = new List<int> { sizer.CurrentBlockSize };
                foreach (var step in scenario.Steps)
                {
                    clock += step.AdvanceMs * 1_000_000L;
                    sizer.Observe(step.BlockBytes, step.ElapsedNanos, step.Stalled);
                    observed.Add(sizer.CurrentBlockSize);
                }
                return observed;
            }
            Assert.Equal(Run(), Run());
        }
    }

    /// <summary>
    /// The documented bounds must hold for every observation, including
    /// boundary inputs (zero-byte file, very large file, stalled I/O).
    /// </summary>
    [Fact]
    public void WindowsSizer_StaysInsideDocumentedBounds()
    {
        long[] sizes = { 0L, 1L, 100L, 65536L, 1L << 20, 5_000_000_000L, 21_474_836_480L, long.MaxValue };
        foreach (long size in sizes)
        {
            long clock = 0;
            var sizer = new AdaptiveBlockSizer(size, nanoTime: () => clock);
            Assert.InRange(sizer.CurrentBlockSize, Core.AppConfig.TransferBlockMinBytes, Core.AppConfig.TransferBlockMaxBytes);
            for (int i = 0; i < 200; i++)
            {
                clock += 2_500L * 1_000_000L;
                bool stalled = (i % 3) == 0;
                sizer.Observe(262144L, stalled ? 900_000_000L : 1_000_000L, stalled);
                Assert.InRange(sizer.CurrentBlockSize, Core.AppConfig.TransferBlockMinBytes, Core.AppConfig.TransferBlockMaxBytes);
            }
            Assert.True(sizer.History.Count <= AdaptiveBlockSizer.MaxHistoryEntries);
        }
    }
}
