// M06 §24C + ADRs 3-8: deterministic sizer behavior under scripted observations
// with a fake timestamp source. No I/O, no timing flakes.
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class AdaptiveBlockSizerTests
{
    private sealed class Clock
    {
        public long NowNanos;
        public void AdvanceMs(long ms) => NowNanos += ms * 1_000_000L;
    }

    private static AdaptiveBlockSizer Sizer(long fileSize, Clock clock) =>
        new(fileSizeBytes: fileSize, nanoTime: () => clock.NowNanos);

    // Fast window: 256 KiB in 1 ms (~256 MB/s). Slow window: stalled flag.
    private static bool Fast(AdaptiveBlockSizer s, long bytes = 262144L) =>
        s.Observe(bytes, 1_000_000L, stalled: false);

    private static bool Stalled(AdaptiveBlockSizer s, long bytes = 65536L) =>
        s.Observe(bytes, 600_000_000L, stalled: true);

    [Fact]
    public void InitialSize_IsFileSizeAwareAndBounded()
    {
        var clock = new Clock();
        Assert.Equal(65536, Sizer(0L, clock).CurrentBlockSize);
        Assert.Equal(65536, Sizer(100L, clock).CurrentBlockSize);
        Assert.Equal(65536, Sizer(8L * 65536L, clock).CurrentBlockSize);
        Assert.Equal(262144, Sizer(8L * 1024 * 1024, clock).CurrentBlockSize);
        Assert.Equal(262144, Sizer(50L * 1024 * 1024 * 1024, clock).CurrentBlockSize);
        Assert.InRange(Sizer(1024L, clock).CurrentBlockSize, 65536, 1048576);
    }

    [Fact]
    public void Growth_NeedsThreeFastWindowsPastCooldown()
    {
        var clock = new Clock();
        var s = Sizer(100L * 1024 * 1024, clock);
        Assert.Equal(262144, s.CurrentBlockSize);
        Assert.False(Fast(s));
        Assert.False(Fast(s));
        clock.AdvanceMs(2500L);
        Assert.True(Fast(s));
        Assert.Equal(524288, s.CurrentBlockSize);
        Assert.Single(s.History);
        Assert.StartsWith("grow 262144->524288", s.History[0]);
    }

    [Fact]
    public void Growth_WaitsForCooldown()
    {
        var clock = new Clock();
        var s = Sizer(100L * 1024 * 1024, clock);
        Assert.False(Fast(s));
        Assert.False(Fast(s));
        Assert.False(Fast(s));
        Assert.Equal(262144, s.CurrentBlockSize);
        Assert.Empty(s.History);
        clock.AdvanceMs(2500L);
        Assert.True(Fast(s));
        Assert.Equal(524288, s.CurrentBlockSize);
    }

    [Fact]
    public void Growth_SaturatesAtMaximum()
    {
        var clock = new Clock();
        var s = Sizer(100L * 1024 * 1024, clock);
        for (int i = 0; i < 10; i++)
        {
            clock.AdvanceMs(2500L);
            Fast(s);
            Fast(s);
            Fast(s);
        }
        Assert.Equal(Core.AppConfig.TransferBlockMaxBytes, s.CurrentBlockSize);
        clock.AdvanceMs(2500L);
        Assert.False(Fast(s));
        Assert.False(Fast(s));
        Assert.False(Fast(s));
    }

    [Fact]
    public void Shrink_NeedsThreeStalledWindowsPastCooldown()
    {
        var clock = new Clock();
        var s = Sizer(100L * 1024 * 1024, clock);
        clock.AdvanceMs(2500L);
        Assert.False(Stalled(s));
        Assert.False(Stalled(s));
        Assert.True(Stalled(s));
        Assert.Equal(131072, s.CurrentBlockSize);
        Assert.StartsWith("shrink 262144->131072", s.History[0]);
    }

    [Fact]
    public void Shrink_FloorsAtMinimum()
    {
        var clock = new Clock();
        var s = Sizer(100L, clock);
        Assert.Equal(65536, s.CurrentBlockSize);
        clock.AdvanceMs(2500L);
        Assert.False(Stalled(s));
        Assert.False(Stalled(s));
        Assert.False(Stalled(s));
        Assert.Equal(65536, s.CurrentBlockSize);
        Assert.Empty(s.History);
    }

    [Fact]
    public void AlternatingConditions_NeverOscillate()
    {
        var clock = new Clock();
        var s = Sizer(100L * 1024 * 1024, clock);
        for (int i = 0; i < 20; i++)
        {
            clock.AdvanceMs(1000L);
            if (i % 2 == 0) Fast(s); else Stalled(s);
        }
        Assert.Equal(262144, s.CurrentBlockSize);
        Assert.Empty(s.History);
    }

    [Fact]
    public void SlowButUnstalledWindows_BelowHalfBaselineShrink()
    {
        var clock = new Clock();
        var s = Sizer(100L * 1024 * 1024, clock);
        Fast(s); // seeds baseline ~256 MB/s
        clock.AdvanceMs(2500L);
        for (int i = 0; i < 3; i++) s.Observe(262144L, 10_000_000L, stalled: false); // ~26 MB/s
        Assert.Equal(131072, s.CurrentBlockSize);
    }

    [Fact]
    public void Shrink_DisarmsRegrowthUntilGenuineImprovement()
    {
        var clock = new Clock();
        var s = Sizer(100L * 1024 * 1024, clock);
        clock.AdvanceMs(2500L);
        Assert.False(Fast(s));
        Assert.False(Fast(s));
        Assert.True(Fast(s));
        Assert.Equal(524288, s.CurrentBlockSize);
        clock.AdvanceMs(2500L);
        Assert.False(Stalled(s));
        Assert.False(Stalled(s));
        Assert.True(Stalled(s));
        Assert.Equal(262144, s.CurrentBlockSize);
        // Steady good windows at the old level: NO regrowth (would cycle).
        clock.AdvanceMs(2500L);
        for (int i = 0; i < 6; i++) Assert.False(Fast(s));
        Assert.Equal(262144, s.CurrentBlockSize);
        // Genuinely faster link (>= 1.2x best): re-arms, then regrows.
        Assert.False(s.Observe(262144L, 1_333_333L, stalled: false)); // ~0.75x: neutral
        clock.AdvanceMs(2500L);
        Assert.False(s.Observe(262144L, 500_000L, stalled: false)); // 2x best: re-arm
        Assert.False(s.Observe(262144L, 500_000L, stalled: false));
        Assert.True(s.Observe(262144L, 500_000L, stalled: false));
        Assert.Equal(524288, s.CurrentBlockSize);
    }

    [Fact]
    public void GoodWindows_ResetBadCounterAndViceVersa()
    {
        var clock = new Clock();
        var s = Sizer(100L * 1024 * 1024, clock);
        clock.AdvanceMs(2500L);
        Assert.False(Stalled(s));
        Assert.False(Stalled(s));
        Assert.False(Fast(s)); // resets stall count
        Assert.False(Stalled(s));
        Assert.False(Stalled(s)); // only 2 consecutive: hold
        Assert.Equal(262144, s.CurrentBlockSize);
    }
}
