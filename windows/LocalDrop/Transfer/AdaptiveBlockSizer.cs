// M06 adaptive logical block sizer (ADRs 3-8). Deterministic, bounded,
// oscillation-resistant probe controller: growth (×2, ≤ MAX) on N
// consecutive non-degraded windows past cooldown while probing is armed;
// shrink (÷2, ≥ MIN) on N consecutive stalled/slow windows past cooldown,
// disarming further growth until a window at >= growthRatio × best
// re-arms it. Pure logic (injected timestamp source) — fully unit-testable
// without I/O. One instance per transfer; single-threaded use.
using LocalDrop.Core;

namespace LocalDrop.Transfer;

public sealed class AdaptiveBlockSizer
{
    private readonly int _minBytes;
    private readonly int _maxBytes;
    private readonly long _cooldownMs;
    private readonly int _consecutiveWindows;
    private readonly double _growthRatio;
    private readonly double _shrinkRatio;
    private readonly Func<long> _nanoTime;

    private double? _baselineBytesPerSec;
    private bool _growthArmed = true;
    private int _goodWindows;
    private int _badWindows;
    private long _lastChangeNanos;
    private readonly List<string> _history = [];

    public int CurrentBlockSize { get; private set; }
    public IReadOnlyList<string> History => _history;

    public const int MaxHistoryEntries = 128;

    public AdaptiveBlockSizer(
        long fileSizeBytes,
        int minBytes = AppConfig.TransferBlockMinBytes,
        int maxBytes = AppConfig.TransferBlockMaxBytes,
        int initialCapBytes = AppConfig.TransferBlockInitialBytes,
        long cooldownMs = AppConfig.AdaptCooldownMs,
        int consecutiveWindows = AppConfig.AdaptConsecutiveWindows,
        double growthRatio = AppConfig.AdaptGrowthRatio,
        double shrinkRatio = AppConfig.AdaptShrinkRatio,
        Func<long>? nanoTime = null)
    {
        _minBytes = minBytes;
        _maxBytes = maxBytes;
        _cooldownMs = cooldownMs;
        _consecutiveWindows = consecutiveWindows;
        _growthRatio = growthRatio;
        _shrinkRatio = shrinkRatio;
        _nanoTime = nanoTime ?? (() => System.Diagnostics.Stopwatch.GetTimestamp() * 1_000_000_000L / System.Diagnostics.Stopwatch.Frequency);
        CurrentBlockSize = InitialFor(fileSizeBytes, minBytes, initialCapBytes);
        _lastChangeNanos = _nanoTime();
    }

    private static int InitialFor(long fileSizeBytes, int minBytes, int initialCapBytes)
    {
        if (fileSizeBytes <= 0L) return minBytes;
        // File-size-aware start: tiny files don't need the full initial cap;
        // huge files start at the cap (growth handles the rest).
        long scaled = fileSizeBytes / 8L;
        long clamped = Math.Clamp(scaled, minBytes, initialCapBytes);
        return (int)clamped;
    }

    // Records one completed block operation. Returns true when the block
    // size changed. `stalled` must be true when that operation took longer
    // than the stall threshold (measured by the caller).
    public bool Observe(long blockBytes, long elapsedNanos, bool stalled)
    {
        long now = _nanoTime();
        double windowBps = elapsedNanos > 0 ? blockBytes * 1e9 / elapsedNanos : 0.0;
        double? baseline = _baselineBytesPerSec;
        if (!stalled && (baseline is null || windowBps > baseline))
        {
            // New best (or first sample): adopt it; genuine improvement
            // re-arms probing after a shrink.
            if (baseline is not null && windowBps >= baseline * _growthRatio) _growthArmed = true;
            _baselineBytesPerSec = windowBps;
        }
        double reference = _baselineBytesPerSec ?? windowBps;
        bool isGood = !stalled && windowBps >= reference;
        bool isBad = stalled || windowBps < reference * _shrinkRatio;
        if (isGood)
        {
            _goodWindows++;
            _badWindows = 0;
        }
        else if (isBad)
        {
            _badWindows++;
            _goodWindows = 0;
        }
        else
        {
            _goodWindows = 0;
            _badWindows = 0;
        }
        // Stopwatch timestamp units -> ms.
        long nanosPerMs = 1_000_000L;
        bool cooledDown = (now - _lastChangeNanos) / nanosPerMs >= _cooldownMs;
        if (_goodWindows >= _consecutiveWindows && cooledDown && _growthArmed &&
            CurrentBlockSize < _maxBytes && reference > 0.0)
        {
            int next = (int)Math.Min((long)CurrentBlockSize * 2, _maxBytes);
            if (next != CurrentBlockSize)
            {
                Record("grow", CurrentBlockSize, next, windowBps);
                CurrentBlockSize = next;
                _baselineBytesPerSec = windowBps;
                _lastChangeNanos = now;
                _goodWindows = 0;
                _badWindows = 0;
                return true;
            }
        }
        if (_badWindows >= _consecutiveWindows && cooledDown && CurrentBlockSize > _minBytes)
        {
            int next = Math.Max(CurrentBlockSize / 2, _minBytes);
            if (next != CurrentBlockSize)
            {
                Record("shrink", CurrentBlockSize, next, windowBps);
                CurrentBlockSize = next;
                // Baseline deliberately RETAINED at the pre-shrink best:
                // re-growth past this stall point then requires a window at
                // >= growthRatio × that best (genuine improvement), which is
                // what breaks the stall-point limit cycle.
                _growthArmed = false;
                _lastChangeNanos = now;
                _goodWindows = 0;
                _badWindows = 0;
                return true;
            }
        }
        return false;
    }

    private void Record(string kind, int from, int to, double windowBps)
    {
        _history.Add($"{kind} {from}->{to} windowBps={(long)windowBps}");
        while (_history.Count > MaxHistoryEntries) _history.RemoveAt(0);
    }
}
