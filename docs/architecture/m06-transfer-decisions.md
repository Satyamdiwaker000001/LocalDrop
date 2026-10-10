# M06 Architecture Decision Record — Android → Windows streaming transfer

Status: M06 initial conservative configuration. Numeric bounds below are
justified by engineering constraint where a constraint exists, and otherwise
marked INITIAL CONSERVATIVE for M15 benchmark validation. No bound is a
product limit: file sizes are unbounded (64-bit), throughput is uncapped.

Shared terms: "block" = one application-level read/write unit (NOT a TCP
packet). "Sizer" = AdaptiveBlockSizer. All timeouts/sizes live in AppConfig.

---

## 1. Streaming I/O

### Decision
Both ends process the file incrementally with O(1) memory: Android reads
`InputStream` → fixed reusable buffer → socket; Windows reads socket →
fixed reusable buffer → `FileStream`. No method ever holds more than one
block per side plus OS socket buffers.

### Why
LocalDrop must move 10 GB+ files on phones with 4–6 GB RAM shared with the
OS and foreground apps.

### Problem Solved
Full-file RAM loading would OOM-kill the app (or force swapping) on any
file larger than free memory.

### Technology/Mechanism
Android: `java.io.InputStream.read(byte[])` loop (works for files, SAF
document streams, any content URI — no `File` path assumption).
Windows: `NetworkStream.ReadAsync` loop + `FileStream` with
`useAsync:true`, sequential-scan hint, incremental `WriteAsync` + final
`Flush`.

### Why This Technology
Platform-native streaming primitives; no dependency; backpressure comes
free from blocking I/O (see ADR 11). Content-URI-compatible on Android
(hotspot phones serve from shared storage, not raw paths).

### Alternatives
- Memory-mapped files (`MappedByteBuffer` / `MemoryMappedFile`).
- NIO async channels with completion callbacks.

### Why Alternatives Were Rejected
Mmap still faults pages per access with less predictable pressure on
low-RAM devices and complicates content-URI sources; async channels add
callback machinery with zero throughput benefit on a single stream —
revisit only on M15 evidence.

### Trade-offs
Slightly more CPU copy per block vs zero-copy; gained: strict memory bound
and simple cancellation.

### Bounds
One live buffer per side, size = current block size (64 KiB–1 MiB).

### Why Bounds Exist
See ADRs 4–5.

### Enforcement
Buffers allocated once per transfer at min size and reused; growth
reallocates at most to MAX (sizer enforces).

### Measurement
`TransferMetrics`: peak live buffer bytes is constant by construction;
loopback tests transfer 100 MB with default JVM heap and assert byte
equality (any accidental full buffering would survive functionally but is
ruled out structurally — exactly one `ByteArray`/`byte[]` field per engine
plus socket buffers).

### Tests
Small/boundary/large loopback transfers with byte-exact comparison;
0-byte file; code-level single-buffer audit.

---

## 2. Bounded buffering

### Decision
Exactly one reusable heap buffer per side sized to the current block.
No queues between read → write on either side.

### Why
Any queue between producer (disk) and consumer (network/disk) can grow
without bound when speeds differ.

### Problem Solved
Unbounded pending-data accumulation under speed mismatch (fast disk +
slow Wi-Fi, or fast network + slow SD card).

### Technology/Mechanism
Synchronous read→write loop in one thread/task per direction; buffer field
reused across iterations.

### Why This Technology
A pipeline with no queue cannot accumulate: the absence of a data
structure is the bound (strongest possible guarantee, no watermark tuning).

### Alternatives
- Bounded blocking queue (e.g. capacity 4) between reader and writer threads.
- Double buffering (2 alternating buffers).

### Why Alternatives Were Rejected
A queue adds threads + synchronization for no M06 gain on one stream;
double buffering doubles peak heap for overlapped I/O the hardware rarely
needs at this stage. Revisit on M15 evidence.

### Trade-offs
Reader idles while writer works (no overlap); gained: minimal memory,
trivial correctness, no tuning surface.

### Bounds
Peak heap per transfer ≈ 2 × block ≤ 2 MiB + socket buffers.

### Why Bounds Exist
OS socket buffers are outside app control; app heap must stay phone-safe.

### Enforcement
No collection ever holds block data; sizer caps block size (ADR 5).

### Measurement
Metrics record final block size; tests assert it stays within [MIN, MAX].

### Tests
Slow-receiver test (throttled writes): transfer still completes byte-exact;
sender peak allocation unchanged by construction.

---

## 3. Adaptive logical block sizing

### Decision
Block size adapts at runtime within [64 KiB, 1 MiB] via a deterministic
windowed controller with cooldown + hysteresis (ADRs 4–8). File-size-aware
initial value; pure function of observed (bytes, nanos, stalled) samples.

### Why
One fixed size cannot serve 10 KiB config files and 50 GB videos well, and
static tuning ignores real conditions (hotspot congestion, SD-card stalls).

### Problem Solved
Syscall/copy overhead at tiny sizes vs memory/latency cost at huge sizes,
under varying runtime conditions.

### Technology/Mechanism
`AdaptiveBlockSizer`: per-block `observe()` + consecutive-window counters +
cooldown timestamps + throughput baseline; injected nanosecond clock for
deterministic tests.

### Why This Technology
Deterministic state machine (no randomness, no platform timers inside) is
unit-testable to exact sequences and cannot oscillate by construction.

### Alternatives
- Fixed 256 KiB forever.
- TCP-info-driven sizing (e.g. congestion window sniffing).

### Why Alternatives Were Rejected
Fixed size punts M06 §6–§7 requirements without evidence; TCP internals
are unportable (Android vs Windows APIs differ) and unnecessary at this
stage.

### Trade-offs
Small controller complexity (~100 lines) + rare reallocations; gained:
self-tuning within safe walls.

### Bounds
See ADRs 4–5; adaptation step ×2/÷2; cooldown 2 s; 3-window confirmation.

### Why Bounds Exist
See ADRs 4–5, 8.

### Enforcement
Sizer clamps every change; engine uses `sizer.currentBlockSize` only.

### Measurement
`adaptations` history (old→new + reason + timestamp) in metrics; tests
replay scripted throughputs and assert exact size sequences.

### Tests
Sizer unit tests (growth/shrink/hold/oscillation scripts); engine tests
assert final size within bounds.

---

## 4. Minimum block/buffer bound — 64 KiB

### Decision
`TRANSFER_BLOCK_MIN_BYTES = 65536`.

### Why
Below ~tens of KiB, per-operation overhead (JNI/syscall dispatch, stream
method calls, progress accounting) dominates and throughput collapses on
large files.

### Problem Solved
Death by 4 KiB writes: a 10 GB file at 4 KiB blocks needs 2.6 M iterations;
at 64 KiB it needs 160 K — ~16× fewer calls for the same bytes.

### Technology/Mechanism
Hard floor in sizer init and every shrink path.

### Why This Technology
A constant floor is the simplest correct enforcement.

### Alternatives
- 4 KiB (page size) or 8 KiB minimum.

### Why Alternatives Were Rejected
Page granularity helps mmap, not stream copies; 64 KiB is a standard
socket/file sweet spot (matches typical socket buffer quanta and keeps
iteration counts sane: 10 GB → ≤160 K ops).

### Trade-offs
Tiny files (< 64 KiB) allocate a 64 KiB buffer (negligible) and finish in
one short read; gained: uniform fast path.

### Bounds
This ADR *is* the lower bound.

### Why Bounds Exist
Memory safety is not the concern here — CPU/throughput collapse is.

### Enforcement
`coerceAtLeast(MIN)` on init, shrink results, and external sets.

### Measurement
Time-per-GB on loopback stays flat across file sizes in tests.

### Tests
Unit: shrink never goes below MIN under sustained-stall scripts; tiny-file
transfers complete in one pass.

---

## 5. Maximum block/buffer bound — 1 MiB

### Decision
`TRANSFER_BLOCK_MAX_BYTES = 1048576`.

### Why
Unbounded growth risks multi-MB heap spikes per transfer, long GC pauses,
slow cancellation response (a blocked 100 MB write is uninterruptible on
some stacks), and poor progress granularity.

### Problem Solved
Caps worst-case app heap per transfer direction at ~1 MiB + socket
buffers, regardless of file size or adaptation enthusiasm.

### Technology/Mechanism
Hard ceiling in sizer init and every growth path.

### Why This Technology
Same as ADR 4: a constant ceiling is the simplest correct enforcement.

### Alternatives
- 4–8 MiB max (fewer syscalls on 10 GB+ files).

### Why Alternatives Were Rejected
Diminishing returns past 1 MiB on Wi-Fi throughputs (a 1 MiB block at
37 MB/s is ~27 ms — already coarse enough for progress/cancel
granularity); larger maxima hurt pause/cancel latency and low-end phones.
M15 may raise with evidence.

### Trade-offs
More iterations on huge files than a bigger max would need; gained: strict
heap bound + responsive control.

### Bounds
This ADR *is* the upper bound. INITIAL CONSERVATIVE pending M15.

### Why Bounds Exist
Phone heap safety + operation-latency ceiling.

### Enforcement
`coerceAtMost(MAX)` on init, growth results, and external sets.

### Measurement
Metrics `finalBlockSize ≤ MAX` asserted in every transfer test.

### Tests
Growth scripts saturate at exactly MAX; engine tests assert the bound.

---

## 6. Growth rule — double after 3 steady consecutive windows past cooldown

### Decision
Grow ×2 (≤ MAX) only when ALL hold: (a) last 3 windows non-degraded
(each ≥ current best baseline — steady performance, tolerating equality),
(b) probing is armed (true initially; a shrink disarms it; only a window at
≥ 1.2× baseline re-arms it), (c) ≥ 2000 ms since the last change. A failed
probe therefore cannot immediately repeat: regrowth past a stall point
demands genuinely improved conditions, which breaks the grow/shrink/grow
limit cycle on marginal links.

### Why
Growth must react to sustained headroom, not one lucky window, and must
not re-probe a size that just stalled without evidence the link improved.

### Problem Solved
Prevents ratcheting up on noise and prevents stall-point cycling: after a
shrink, the pre-shrink best is retained as baseline, so steady windows
merely hold while only real improvement re-arms growth.

### Technology/Mechanism
Consecutive-good counter + best-baseline + armed flag + cooldown timestamp.

### Why This Technology
Three cheap integers, one flag, one timestamp; fully deterministic under
a fake clock.

### Alternatives
- Grow on any single fast window.
- Additive growth (+64 KiB).
- Grow only on strict improvement (never probes steady links).

### Why Alternatives Were Rejected
Single-window growth oscillates; additive growth needs ~15 steps to cross
the range (slow to exploit headroom) — doubling crosses 64 KiB→1 MiB in 4
steps; improvement-only growth would never leave the initial size on the
common steady-link case, wasting the adaptive range.

### Trade-offs
Needs 3 good windows before acting (slightly slow start); gained: stability
plus genuine headroom exploitation.

### Bounds
Non-degraded threshold (≥ baseline), 3 windows, 2000 ms cooldown,
re-arm ratio 1.2×.

### Why Bounds Exist
Equality-tolerant steadiness avoids float-noise vetoes; 20% re-arm margin
exceeds typical loopback/Wi-Fi jitter; 3 windows filter transients; 2 s
bounds adaptation frequency (~0.5 Hz max).

### Enforcement
Single `maybeGrow()` gate checked per observation.

### Measurement
Adaptation history entries with reason + before/after.

### Tests
Scripted throughputs: sustained steady for 3 windows → exactly one
doubling; 2 fast + 1 slow → no growth; growth inside cooldown → deferred;
post-shrink steady → held; post-shrink 2× → re-armed and regrown.

---

## 7. Shrink rule — halve after 3 stalled/slow consecutive windows past cooldown

### Decision
Shrink ÷2 (≥ MIN) only when ALL hold: (a) last 3 windows each stalled
(single-block time > 500 ms) OR throughput < 0.5× baseline, (b) ≥ 2000 ms
since the last change.

### Why
Stalls signal the pipeline cannot absorb the current operation size
(congested hotspot, slow SD card); smaller operations restore granularity
and responsiveness.

### Problem Solved
A 1 MiB write stalled on a congested link blocks progress reporting and
cancellation for its whole duration; shrinking bounds that latency.

### Technology/Mechanism
Consecutive-bad counter (stall flag comes from measured per-block elapsed
> 500 ms, not from guessing) + same cooldown gate.

### Why This Technology
Measured stall (wall time per block) is platform-neutral — no TCP-stack
sniffing needed on either OS.

### Alternatives
- Shrink on first stall.
- Never shrink (grow-only).

### Why Alternatives Were Rejected
First-stall shrink overreacts to GC pauses/scheduling hiccups; grow-only
can wedge at MAX on a deteriorating link.

### Trade-offs
Tolerates up to 3 slow windows before acting (~1.5 s+ of degraded
throughput); gained: no thrash on transient hiccups.

### Bounds
÷2, floor MIN, 500 ms stall threshold, 3 windows, 2000 ms cooldown.

### Why Bounds Exist
500 ms exceeds normal scheduling jitter but catches real stalls quickly;
halving converges 1 MiB→64 KiB in 4 steps.

### Enforcement
Single `maybeShrink()` gate checked per observation.

### Measurement
Stall counter in metrics; history entries on shrink.

### Tests
Scripted stall sequences → exact halving sequence floored at MIN;
interleaved good windows reset the counter (no shrink).

---

## 8. Hysteresis/cooldown — 3-window confirmation + 2 s quiet period

### Decision
No adaptation without 3 consecutive agreeing windows AND 2 s since the
previous change; growth and shrink counters reset each other.

### Why
Without stabilization, borderline throughput flip-flops sizes every
window (grow/shrink/grow…), wasting reallocations and destabilizing
progress/ETA math.

### Problem Solved
Oscillation: the classic control-loop failure the milestone explicitly
calls out.

### Technology/Mechanism
Mutually resetting consecutive counters + `lastChangeNanos` timestamp.

### Why This Technology
Proven bang-bang-with-deadband pattern; two integers + one timestamp.

### Alternatives
- EWMA smoothing without cooldown.
- PID-style controller.

### Why Alternatives Were Rejected
EWMA alone still acts every window; PID is overkill for a 16× range with
4 doubling steps and needs tuning constants M15 hasn't measured.

### Trade-offs
Up to ~2 s + 3 windows of lag before reacting; gained: provable
non-oscillation (a change requires sustained agreement, then a quiet
period).

### Bounds
3 windows, 2000 ms. INITIAL CONSERVATIVE pending M15.

### Why Bounds Exist
Bounds the adaptation rate below human-perceptible progress jitter while
staying responsive to real link changes.

### Enforcement
Checked before every grow/shrink; unit-tested with alternating scripts
asserting zero changes.

### Measurement
History length per transfer stays small (tests assert ≤ ~10 entries for
short transfers).

### Tests
Alternating fast/slow script → zero adaptations; sustained scripts →
exactly the expected steps.

---

## 9. TCP data channel separation

### Decision
Bulk bytes travel on a dedicated TCP connection to `DATA_TCP_PORT`
(48425); the M04 control connection carries only M02 messages.

### Why
A 50 GB bulk stream sharing one socket with control traffic would delay
cancellation, errors, progress, and all future control operations behind
gigabytes of queued bytes.

### Problem Solved
Control-plane starvation and head-of-line blocking between bulk data and
signalling.

### Technology/Mechanism
Second `Socket`/`TcpClient` pair: sender dials receiver's data port after
`TRANSFER_ACCEPT`; receiver's data listener is bound alongside (not inside)
the control server.

### Why This Technology
OS-level isolation: independent send/receive buffers, independent close
semantics, no app multiplexing code to get wrong.

### Alternatives
- Multiplex data + control on one socket with framing.

### Why Alternatives Were Rejected
Requires a stream multiplexer (flow control, channel IDs, interleaving
rules) — a new protocol on the wire, explicitly against M06 scope, with
strictly worse debuggability.

### Trade-offs
One extra socket + one extra port per transfer; gained: control stays
responsive at any file size.

### Bounds
One data stream per transfer (ADR 10); one active transfer per receiver
in M06 (second request → `TRANSFER_REJECT` until M12).

### Why Bounds Exist
M06 scope is the first single transfer; orchestration is M12.

### Enforcement
Receiver guard flag; sender never writes file bytes to the control socket
(structurally: control `send()` takes only `LocalDropMessage`).

### Measurement
During a 100 MB transfer, control round-trip (e.g. progress/ack exchange
in tests) completes in ms while bulk flows.

### Tests
Cancel-during-transfer propagates over control while data flows; no file
bytes ever appear on the control stream (protocol-level assertions).

---

## 10. Single data stream initially

### Decision
Exactly one TCP data connection per transfer. No parallel streams in M06.

### Why
One stream gives total ordering for free, one lifecycle, one congestion
controller, and unambiguous benchmark numbers.

### Problem Solved
Avoids reassembly buffers, out-of-order writes, and multi-socket failure
modes before single-stream behavior is even measured.

### Technology/Mechanism
One accepted `Socket` per transfer; sequential read→write loop.

### Why This Technology
Simplest correct transport; matches frozen spec ("one TCP data stream
initially; parallel streams require benchmark evidence").

### Alternatives
- 2–4 parallel range streams.

### Why Alternatives Were Rejected
No evidence of a single-stream bottleneck; parallelism is M15-gated by
the frozen architecture.

### Trade-offs
Throughput limited to one stream's fair share; gained: correctness and
clarity.

### Bounds
One. Enforced by single-accept handling per transfer.

### Enforcement
Receiver closes any second data connection for the same transferId;
sender opens exactly one.

### Measurement
M15 benchmark compares single-stream throughput vs link capacity.

### Tests
Interop transfer completes byte-exact over one stream; no second socket
is ever opened (assert via connection counting in tests where practical).

---

## 11. Backpressure

### Decision
Structural backpressure: blocking writes propagate slowness upstream with
zero queues. Sender: slow network → `write()` blocks → reads pause.
Receiver: slow disk → file `write()` blocks → socket reads pause → sender
blocks.

### Why
Any explicit queue needs watermarks, tuning, and overflow policy; blocking
I/O already implements perfect backpressure in the OS.

### Problem Solved
Unbounded memory growth under producer/consumer speed mismatch (M06 §8).

### Technology/Mechanism
Blocking `OutputStream.write` (Android/JVM) and `await WriteAsync`
without buffering wrappers (.NET) on fixed-size blocks; no app queues.

### Why This Technology
The OS socket buffers (typically 64–256 KiB) are the ONLY queue, and they
are bounded by the kernel, not the app.

### Alternatives
- App-level bounded queue + producer/consumer threads.

### Why Alternatives Were Rejected
Extra threads + tuning for no benefit on a single stream; the queue would
just duplicate kernel buffering less efficiently.

### Trade-offs
No read/write overlap (see ADR 2); gained: provable boundedness.

### Bounds
Kernel socket buffers only; app holds ≤ 1 block per side.

### Why Bounds Exist
Kernel bounds are outside app control and already safe; app adds nothing.

### Enforcement
No queue data structures exist in the engines (audit-able by inspection).

### Measurement
Slow-receiver test: throttled disk writes lower sender throughput without
growing sender memory (completion + byte equality asserted).

### Tests
Slow-disk simulation; stalled-network simulation; cancellation during stall
still prompt (socket close unblocks).

---

## 12. 64-bit counters

### Decision
`Long`/`long` for every size, offset, and counter; no `Int`/`int` in any
size path. Progress math in `Long` with `Double` only for the final
percentage display value.

### Why
32-bit overflows at 2 GiB — below the 10 GB+ first-class requirement. A
single `Int` cast would corrupt progress, completion checks, and framing.

### Problem Solved
Overflow-induced truncation, negative byte counts, and premature/never
completion on large files.

### Technology/Mechanism
Kotlin `Long` / C# `long` end-to-end (metadata `fileSize`, transferred
accumulators, progress snapshot, ETA math); M02 JSON integers already
64-bit.

### Why This Technology
Native 64-bit on both runtimes; zero cost.

### Alternatives
- `Int` with overflow checks.

### Why Alternatives Were Rejected
Checks don't raise the ceiling; the requirement exceeds it.

### Trade-offs
None material.

### Bounds
`0..2^63-1` (protocol bound); no app-level file-size maximum exists.

### Why Bounds Exist
Protocol integers are signed 64-bit; the app adds no further limit.

### Enforcement
Audit: grep for `Int`/`int` in transfer paths must show only ports,
timeouts, counts-of-things, and buffer sizes (≤ 1 MiB, safely 32-bit);
every documented conversion is explicit (`toLong()` at block-size use).

### Measurement
10 GB+ metadata accepted and tracked in tests without I/O of that size.

### Tests
Progress math at 10 GB/50 GB/near-`Long.MAX_VALUE` values; `TRANSFER_REQUEST`
with 10 GiB `fileSize` accepted by frozen validation (existing M02 tests).

---

## 13. Temporary/in-progress destination handling

### Decision
Receive into `<finalName>.localdrop-part` in the destination directory;
on transport-complete, atomically move to the final name (uniquified with
`name (n).ext` if occupied — never overwrite). Delete the temp on cancel
or failure.

### Why
A crash/mid-transfer file must never masquerade as the user's completed
file, and an unrelated existing file must never be silently destroyed.

### Problem Solved
Partial-file confusion + destination clobbering — the two classic
download-manager data-loss bugs.

### Technology/Mechanism
`FileStream` to temp path; `File.Move` overwrite:false after byte-count +
`TRANSFER_COMPLETE` match; pre-move existence check with numeric-suffix
scan; `finally`-path temp deletion on abort paths.

### Why This Technology
OS rename is atomic on same-volume moves (NTFS/APFS/ext4); suffix
uniquification is the least surprising no-clobber policy.

### Alternatives
- Write directly to the final name.
- Hidden temp directory elsewhere.

### Why Alternatives Were Rejected
Direct write exposes partial bytes as real files; elsewhere breaks
same-volume atomicity and litters other directories.

### Trade-offs
Temp + final briefly coexist (2× space during finalize — instantaneous);
gained: crash safety + no clobber.

### Bounds
Suffix scan capped (first free `n`, practically unbounded but each probe
is one existence check).

### Why Bounds Exist
No arbitrary cap on user files; probes are O(1) syscalls.

### Enforcement
Move only after full validation; existence check immediately before move
(TOCTOU window accepted and documented — same as all file managers).

### Measurement
Tests assert temp presence mid-transfer, absence after, final bytes exact.

### Tests
Collision test (pre-existing file untouched + uniquified output);
cancel/failure temp cleanup; 0-byte file path.

### M10 compatibility
Temp holds raw prefix bytes `[0..received)` with no header — a future
resumer can adopt it directly; M06 deletion-on-abort does not preclude
M10 durable naming.

---

## 14. Cancellation model

### Decision
Cooperative cancellation: per-block flag checks + socket close to unblock
I/O. Android: `AtomicBoolean` supplier (no coroutines dependency — the M04
stack is thread-based and M06 adds no new concurrency framework).
Windows: `CancellationToken` plumbed through async I/O.

### Why
Blocking I/O does not poll flags while blocked; closing the socket is the
only prompt unblock mechanism on both stacks.

### Problem Solved
Prompt, leak-free abort from any state (connecting, streaming, awaiting
completion) without orphaned threads/tasks/sockets/files.

### Technology/Mechanism
`isCancelled: () -> Boolean` (Android) / `CancellationToken` (Windows);
`stop()`/`disconnect()` closes sockets; temp cleanup in `finally`.

### Why This Technology
Matches each platform's existing M04 idiom; zero new dependencies.

### Alternatives
- Kotlin coroutines on Android.

### Why Alternatives Were Rejected
New dependency + dispatcher model for one flag check per block; M13 will
revisit for the service lifecycle, not M06.

### Trade-offs
Poll-per-block granularity (≤1 block latency, ≤ ~27 ms at target speed);
gained: no framework, deterministic tests.

### Bounds
Cancel latency ≤ one block operation + close propagation.

### Why Bounds Exist
Responsiveness requirement without per-byte polling cost.

### Enforcement
Check before every block read/write; close unblocks the current one.

### Measurement
Cancel latency measured in tests (assert prompt return).

### Tests
Cancel mid-transfer both directions of initiation; double-cancel idempotent;
no leaked threads (rebind works); temp cleaned; state `cancelled`; peer
sees failure (not completion).

---

## 15. Progress calculation

### Decision
64-bit counters; `percent = transferred*100/total` (`total==0` → 100%);
average = bytes/elapsed; recent = last report-window rate; ETA only when
recent rate > 0, else null. Engine-throttled callbacks (≤1 per 500 ms +
final).

### Why
UI needs stable numbers; per-block callbacks would spam observers
(~160 K callbacks for 10 GB at 64 KiB).

### Problem Solved
Observer flooding + div-by-zero + 32-bit overflow in one place.

### Technology/Mechanism
`TransferProgress` snapshot data class; monotonic time for durations
(`nanoTime`/`Stopwatch`, never wall clock).

### Why This Technology
Monotonic clocks survive NTP jumps; snapshots keep engine/UI decoupled.

### Alternatives
- Callback per block; wall-clock durations.

### Why Alternatives Were Rejected
Per-block spam; wall clocks jump.

### Trade-offs
500 ms granularity; gained: cheap, stable.

### Bounds
Throttle interval 500 ms (INITIAL CONSERVATIVE).

### Why Bounds Exist
Human-perception rate; negligible overhead.

### Enforcement
Time-gate in engine emit path + unconditional final emit.

### Measurement
Tests assert monotonic non-decreasing transferred, final 100%, ETA null
early / sane later.

### Tests
Progress math unit tests incl. 0-byte and 10 GB-scale values; loopback
tests assert monotonicity + final snapshot.

---

## 16. Performance instrumentation

### Decision
`TransferMetrics`: elapsed, bytes, avg/recent throughput, total read/write
nanos, stall count, adaptation history, final block size. Near-zero overhead
(counters + timestamps only, no per-block logging/allocations in the hot
loop — progress strings built only on throttled emits).

### Why
M15 needs baselines; blind code cannot be tuned, and logging per block
would itself distort measurement.

### Problem Solved
Observable performance without observer effect.

### Technology/Mechanism
Primitive fields updated inline; history list appended only on adaptation
events (rare).

### Why This Technology
Cheapest possible: a fewns per block.

### Alternatives
- ETW/Android tracing spans per block.

### Why Alternatives Were Rejected
Heavyweight for M06; platform-specific; M15 can add.

### Trade-offs
Coarse (no per-block trace); gained: zero distortion.

### Bounds
History entries bounded by adaptation count (cooldown-limited).

### Why Bounds Exist
Unbounded history would itself grow with file size.

### Enforcement
Append-only-on-adapt; tests assert small history.

### Measurement
100 MB interop reports avg throughput + duration; compared against the
37 MB/s target (target, not gate).

### Tests
Metrics sanity on every transfer test (bytes == expected, elapsed > 0,
avg == bytes/elapsed within rounding).

---

## 17. Why 37 MB/s is a target and not a cap

### Decision
No throttling, pacing, sleeping, or rate-limiting code exists anywhere in
the transfer path. The number 37 appears only in docs/tests as a reporting
reference.

### Why
LocalDrop's promise is link-speed transfer; any cap would silently betray
faster hardware (Wi-Fi 6 hotspots routinely exceed 100 MB/s).

### Problem Solved
Prevents "helpful" throttling from becoming a permanent ceiling.

### Technology/Mechanism
Absence of mechanism, enforced by audit (grep for sleep/throttle/rate in
transfer code must show only progress throttling and adapt cooldowns,
neither of which gates bytes).

### Why This Technology
A cap cannot be misconfigured if it cannot be expressed.

### Alternatives
- Configurable cap defaulting to unlimited.

### Why Alternatives Were Rejected
A cap-shaped knob invites capping; YAGNI.

### Trade-offs
None (a missing limiter has no downside).

### Bounds
None — unbounded by design.

### Why Bounds Exist
N/A.

### Enforcement
Static audit + loopback throughput measurement (whatever the loopback
gives, well above 37 MB/s, proving no cap).

### Measurement
Interop test logs measured MB/s; report states whether ≥37 achieved.

### Tests
Throughput assertion is informational (log + report), never a pass/fail
gate (hardware-dependent).

---

## 18. Why no application-level file-size limit exists

### Decision
No `MAX_FILE_SIZE`-style constant; validation accepts the full protocol
range `0..2^63-1`; completion is defined by byte-count equality, which is
size-agnostic.

### Why
The frozen spec requires 10 GB+ first-class and names 50/100 GB+ as
supported; any app constant below filesystem limits would be an invented
product limit.

### Problem Solved
Removes an entire class of "works for small files only" bugs.

### Technology/Mechanism
64-bit counters (ADR 12) + bounded memory (ADR 2) + count-equality
completion — none of which scale with size.

### Why This Technology
Size-agnostic completion is the only correct definition for streams.

### Alternatives
- Staged limits (100 MB now, raise later).

### Why Alternatives Were Rejected
Explicitly forbidden by the milestone; limits would need removal later
with regression risk.

### Trade-offs
Very large files surface only real limits (disk space, filesystem caps);
gained: no artificial ceiling.

### Bounds
Filesystem / storage / OS only (outside app control).

### Why Bounds Exist
Physics, not policy.

### Enforcement
Audit for `MAX_*SIZE`, `2GB`, `4GB` etc. must show only buffer bounds and
test fixtures; pre-transfer free-space check is advisory (fail-open on
uncertainty, write-time errors still handled).

### Measurement
100 MB and 1 GB loopback transfers; 10 GB+ deferred with reason (time,
not capability).

### Tests
Metadata accepts 10 GiB/50 GiB values; engine transfers whatever the
source yields; no size-branching code exists to test.

---

## 19. Why resume is deferred to M10

### Decision
M06 has no checkpoint persistence, no offset negotiation beyond `offset=0`,
no reconnect continuation. Interruption ⇒ failure + temp cleanup.

### Why
Resume needs durable checkpoints, identity revalidation, and hash-gated
completion — three milestones of machinery (M10/M11) that must not be
half-built into the first transfer.

### Problem Solved
Keeps M06 reviewable and prevents a half-resume that corrupts files.

### Technology/Mechanism
`resumeOffset` always 0; `TRANSFER_ACCEPT.resumeOffset` always 0; nonzero
resume requests rejected; temp layout (raw prefix, ADR 13) pre-adoptable.

### Why This Technology
Explicit non-support beats partial support.

### Alternatives
- Best-effort resume now.

### Why Alternatives Were Rejected
Resuming without integrity verification (M11) risks silent corruption —
worse than clean failure.

### Trade-offs
Interrupted 50 GB transfers restart; gained: correctness + clear scope.

### Bounds
None.

### Enforcement
Receiver rejects nonzero `resumeOffset`; no checkpoint code exists.

### Measurement
Interruption test → clean failure (not completion, not hang).

### Tests
Kill data connection mid-transfer → both sides fail safely; temp gone.

---

## 20. Why SHA-256 verification is deferred to M11

### Decision
M06 computes no hashes. `TRANSFER_COMPLETE.sha256Hex` (required 64-hex by
the frozen M02 schema) carries 64 zeros meaning "not computed — M11
pending". State is `completed` = TRANSPORT-complete only; no verified state
exists in M06.

### Why
Hashing every byte through a digest while establishing the pipeline adds
CPU/perf variables and fake assurance (a computed-but-unverified hash
proves nothing); M11 owns verify-before-complete.

### Problem Solved
Avoids shipping hash theater and keeps M06 throughput numbers clean for M15.

### Technology/Mechanism
Constant `M06_HASH_NOT_COMPUTED = "000…0"` (64 chars); receiver ignores the
field after shape validation and MUST NOT treat transport completion as
verification.

### Why This Technology
Satisfies the frozen required-field shape with an explicit, documented,
unverifiable sentinel — no wire change, no silent invention (§28-safe:
field exists, meaning documented, upgrade path defined).

### Alternatives
- Omitting the field (violates frozen schema).
- Computing-but-not-verifying hash.

### Why Alternatives Were Rejected
Omission breaks M02 validation; compute-without-verify burns CPU for zero
security benefit and invites misreading as "verified".

### Trade-offs
M06 completion is explicitly unverified; gained: honest states + clean M15
numbers.

### Bounds
None.

### Enforcement
Sender always emits the sentinel; receiver never compares it (asserted in
tests: tampered bytes still "complete" transport — documents the gap M11
closes; see also integrity test plan).

### Measurement
N/A in M06 (M11 measures verify cost separately).

### Tests
Accept zeros; reject malformed hash shape (frozen validation); state name
is transport-complete, never verified.
