#!/usr/bin/env python3
"""M15 adaptive-block-sizer equivalence vectors.

Third, INDEPENDENT implementation of the M06 AdaptiveBlockSizer decision
rules (written from the documented algorithm in
docs/architecture/m06-transfer-decisions.md ADR 3/6/7/8 and in
docs/performance/M08_STREAMING_PERFORMANCE_METHODOLOGY.md section G).

Both production implementations (.NET and Kotlin) must produce exactly the
same block-size decisions and the same adaptation history for the same
scripted observations. This script is the oracle that says what those
decisions are.

Rules implemented (identical text in both production platforms):
  * initial   = clamp(fileSize / 8, MIN, INITIAL)          (0-byte file -> MIN)
  * per window: windowBps = blockBytes * 1e9 / elapsedNanos
  * baseline  = best non-stalled window throughput (monotone)
  * re-arm    : baseline is not None and windowBps >= baseline * GROWTH_RATIO
  * good      : not stalled and windowBps >= baseline
  * bad       : stalled or windowBps < baseline * SHRINK_RATIO
  * grow      : goodCount >= 3 and cooldown elapsed and armed
                and size < MAX  -> size = min(size * 2, MAX)
  * shrink    : badCount >= 3 and cooldown elapsed and size > MIN
                -> size = max(size / 2, MIN), disarms growth
  * cooldown  : (now - lastChange) / 1e6 >= COOLDOWN_MS
  * history   : "<kind> <from>-><to> windowBps=<truncated bps>"
"""

import json
import os

MIN = 65536
MAX = 1048576
INITIAL = 262144
COOLDOWN_MS = 2000
WINDOWS = 3
GROWTH_RATIO = 1.2
SHRINK_RATIO = 0.5

NANOS_PER_MS = 1_000_000


class Sizer:
    def __init__(self, file_size):
        if file_size <= 0:
            self.size = MIN
        else:
            self.size = max(MIN, min(file_size // 8, INITIAL))
        self.baseline = None
        self.armed = True
        self.good = 0
        self.bad = 0
        self.last_change = 0
        self.history = []
        self.clock = 0

    def advance(self, ms):
        self.clock += ms * NANOS_PER_MS

    def observe(self, block_bytes, elapsed_nanos, stalled):
        now = self.clock
        window_bps = (block_bytes * 1e9 / elapsed_nanos) if elapsed_nanos > 0 else 0.0
        baseline = self.baseline
        if (not stalled) and (baseline is None or window_bps > baseline):
            if baseline is not None and window_bps >= baseline * GROWTH_RATIO:
                self.armed = True
            self.baseline = window_bps
        reference = self.baseline if self.baseline is not None else window_bps
        is_good = (not stalled) and window_bps >= reference
        is_bad = stalled or window_bps < reference * SHRINK_RATIO
        if is_good:
            self.good += 1
            self.bad = 0
        elif is_bad:
            self.bad += 1
            self.good = 0
        else:
            self.good = 0
            self.bad = 0
        cooled = (now - self.last_change) // NANOS_PER_MS >= COOLDOWN_MS
        if self.good >= WINDOWS and cooled and self.armed and self.size < MAX and reference > 0.0:
            nxt = min(self.size * 2, MAX)
            if nxt != self.size:
                self.history.append("grow %d->%d windowBps=%d" % (self.size, nxt, int(window_bps)))
                self.size = nxt
                self.baseline = window_bps
                self.last_change = now
                self.good = 0
                self.bad = 0
                return True
        if self.bad >= WINDOWS and cooled and self.size > MIN:
            nxt = max(self.size // 2, MIN)
            if nxt != self.size:
                self.history.append("shrink %d->%d windowBps=%d" % (self.size, nxt, int(window_bps)))
                self.size = nxt
                self.armed = False
                self.last_change = now
                self.good = 0
                self.bad = 0
                return True
        return False


FAST = {"advanceMs": 0, "blockBytes": 262144, "elapsedNanos": 1000000, "stalled": False}
STALL = {"advanceMs": 0, "blockBytes": 65536, "elapsedNanos": 600000000, "stalled": True}
SLOW = {"advanceMs": 0, "blockBytes": 262144, "elapsedNanos": 10000000, "stalled": False}


def scenario(name, file_size, steps):
    s = Sizer(file_size)
    sizes = [s.size]
    for step in steps:
        s.advance(step.get("advanceMs", 0))
        s.observe(step["blockBytes"], step["elapsedNanos"], step["stalled"])
        sizes.append(s.size)
    return {
        "name": name,
        "fileSizeBytes": file_size,
        "steps": steps,
        "expectedBlockSizes": sizes,
        "expectedHistory": s.history,
    }


def main():
    scenarios = []

    # 1. steady fast link: growth only after 3 windows AND the cooldown.
    steps = []
    for _ in range(3):
        steps.append(dict(FAST))
    steps.append(dict(FAST, advanceMs=2500))          # cooldown elapsed -> grow
    for _ in range(3):
        steps.append(dict(FAST))
    steps.append(dict(FAST, advanceMs=2500))          # -> grow again
    for _ in range(3):
        steps.append(dict(FAST))
    steps.append(dict(FAST, advanceMs=2500))          # -> saturate at MAX
    scenarios.append(scenario("steady-fast-growth-to-max", 100_000_000, steps))

    # 2. stalled link: shrink to the floor.
    steps = []
    for _ in range(6):
        steps.append(dict(STALL, advanceMs=2500 if _ >= 2 else 0))
    scenarios.append(scenario("stalled-shrink-to-min", 100_000_000, steps))

    # 3. alternating conditions must never oscillate.
    steps = []
    for i in range(20):
        steps.append(dict(FAST if i % 2 == 0 else STALL, advanceMs=1000))
    scenarios.append(scenario("alternating-never-oscillates", 100_000_000, steps))

    # 4. shrink then steady: no regrowth until genuine improvement.
    steps = []
    for _ in range(3):
        steps.append(dict(FAST))
    steps.append(dict(FAST, advanceMs=2500))              # grow 256K -> 512K
    for _ in range(3):
        steps.append(dict(STALL, advanceMs=2500 if _ >= 2 else 0))
    for _ in range(6):
        steps.append(dict(FAST, advanceMs=2500))
    # genuine improvement: >= 1.2x the retained pre-shrink best
    for _ in range(3):
        steps.append({"advanceMs": 2500, "blockBytes": 262144, "elapsedNanos": 500000, "stalled": False})
    scenarios.append(scenario("shrink-disarms-regrowth-until-improvement", 100_000_000, steps))

    # 5. boundary: zero-byte file starts at MIN and never grows below it.
    scenarios.append(scenario("zero-byte-file-stays-at-min", 0, [dict(FAST) for _ in range(6)]))

    # 6. boundary: tiny file clamps the file-size-aware start to MIN.
    scenarios.append(scenario("tiny-file-starts-at-min", 100, [dict(FAST) for _ in range(6)]))

    doc = {
        "spec": "m15-sizer-equivalence-v1",
        "minBytes": MIN,
        "maxBytes": MAX,
        "initialBytes": INITIAL,
        "cooldownMs": COOLDOWN_MS,
        "consecutiveWindows": WINDOWS,
        "growthRatio": GROWTH_RATIO,
        "shrinkRatio": SHRINK_RATIO,
        "oracle": "python3 independent re-implementation of the documented M06 rules",
        "scenarios": scenarios,
    }
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sizer-vectors.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=2)
        fh.write("\n")
    for s in scenarios:
        print("[m15-sizer] %-42s sizes=%s" % (s["name"], s["expectedBlockSizes"]))
        for h in s["expectedHistory"]:
            print("             %s" % h)
    print("[m15-sizer] written to %s" % out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
