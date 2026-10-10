# M07 Architecture Decision Record — Windows → Android streaming transfer

Status: M07 initial configuration. M07 is the reverse-direction counterpart of
M06 (Android → Windows) over the UNCHANGED wire contract: no M02/M04/M05
protocol change, no new message type, field, port, or security contract (see
the protocol change gate, §20 below). Numeric bounds are the frozen M06
INITIAL CONSERVATIVE values, reused verbatim; file sizes are unbounded
(64-bit) and throughput is uncapped.

Shared terms: "block" = one application-level read/write unit (NOT a TCP
packet). "Sizer" = AdaptiveBlockSizer. All timeouts/sizes live in AppConfig.

---

## 1. M06 reuse versus platform-specific implementations

### Decision
Reuse directly (approach A): `AdaptiveBlockSizer`, `TransferModels`
(`TransferProgress`/`TransferOutcome`/`TransferMetrics`/`TransferErrors`/
transfer-ID routing), `TransferMailbox`, the M04 control channel, the M05
auth/session stack, and the `shared-protocol/data-transfer.md` data-plane
contract (port 48425, 37-byte preamble, TRANSFER_START/stream/PROGRESS/
COMPLETE flow, zeros-hash sentinel, `.localdrop-part` temp discipline).
Mirror as platform-specific counterparts (approach C) ONLY where platform
APIs demand it: Windows `SenderEngine` + `TransferSource` (async/`CancellationToken`
idioms) and Android `ReceiverEngine` + `ReceiverDestination` +
`TransferRequestHandler` (thread/`Socket` idioms). No shared-algorithm
extraction (approach B) was needed: the sizer is already behavior-identical
on both platforms by construction (same rules, M06-tested).

### Why
M07 must not become a second transfer system (§4). The M06 flow is
direction-agnostic: swapping the sender/receiver roles changes which OS
calls which API, not the protocol.

### Problem Solved
Prevents protocol drift, duplicate tuning surfaces, and double maintenance
of the sizing/error/completion contracts.

### Technology/Mechanism
New files only: `windows/LocalDrop/Transfer/{SenderEngine,TransferSource}.cs`,
`android/.../transfer/{ReceiverEngine,ReceiverDestination,TransferRequestHandler}.kt`,
plus tests. Zero modifications to M01–M06 production sources (one
M06-behavior-preserving addition: `::ffff:` normalization in the NEW sender
only — see ADR 7).

### Why This Technology
Mirroring keeps each platform idiomatic (async .NET vs thread JVM) while the
wire behavior stays single-sourced in `data-transfer.md`.

### Alternatives
- Extract a shared C#/Kotlin transfer core (impossible without a shared
  runtime; rejected — no new toolchain).
- Copy-paste M06 classes wholesale (rejected — sender and receiver have
  genuinely different lifecycles; copies would rot).

### Rejected Alternatives
See above.

### Trade-offs
Two engine implementations to maintain; gained: each is reviewable in one
file and testable against the other's platform (cross-runtime interop).

### Bounds
None new (M06 bounds reused unchanged).

### Why Bounds Exist
N/A — no new bound introduced.

### Enforcement
Static: M06 files untouched (verified by file listing); new files reference
only frozen contracts.

### Measurement
Cross-runtime interop (Windows CLR sender → JVM Android receiver): 100 MB
MATCH in 0.4–0.9 s, 1 GB MATCH in 5.2 s — proves one contract, two stacks.

### Tests
21 Windows sender tests, 17 Android receiver tests, 1+1 same-stack interop
tests, 1 gated cross-runtime test; full suites 215 (Windows) + 168 (Android)
green.

---

## 2. Windows sender lifecycle

### Decision
`SenderEngine.SendAsync(SendRequest)`: validate source → TRANSFER_REQUEST →
await ACCEPT/REJECT/ERROR via `TransferMailbox` → data dial + preamble →
TRANSFER_START → stream → throttled PROGRESS → TRANSFER_COMPLETE (zeros
sentinel) → close. Fully `async`; `CancellationToken` linked from caller
token + per-request token; every `await` observes cancellation and maps to
`Cancelled` (never swallowed as generic failure).

### Why
WPF forbids blocking the UI thread; .NET async I/O gives cancellation +
backpressure without extra threads.

### Problem Solved
Prompt, leak-free abort from any state (request wait, connect, streaming,
completion) with deterministic socket/stream disposal (`finally` + `using`).

### Technology/Mechanism
`TcpClient.ConnectAsync` with `CancelAfter(DATA_CONNECT_TIMEOUT_MS)`,
`NetworkStream.WriteAsync`, `FileStream` (`useAsync`, sequential hint),
`TransferMailbox.AwaitResponse` on a worker thread (`Task.Run`, since the
mailbox blocks on `Monitor.Wait`), `mailbox.Forget` in `finally`.

### Why This Technology
Matches the existing Windows M04 idiom (`SendAsync`, CTS); no new
concurrency framework.

### Alternatives
- Blocking synchronous sender on a background thread (rejected — fights the
  platform idiom, cancellation becomes `Close()`-races).
- Reusing the Android thread model via `Task.Run` wrappers everywhere
  (rejected — discards structured cancellation).

### Rejected Alternatives
See above.

### Trade-offs
`AwaitResponse` still blocks a pool thread briefly (bounded by
`TRANSFER_REQUEST_TIMEOUT_MS`); gained: zero changes to the frozen mailbox.

### Bounds
Request wait ≤ `TRANSFER_REQUEST_TIMEOUT_MS` (10 s); data connect ≤
`DATA_CONNECT_TIMEOUT_MS` (5 s). Both centralized in AppConfig (M04/M06
values, unchanged).

### Why Bounds Exist
Connect/handshake must never block indefinitely (M04 lesson: blackhole and
silent-peer tests).

### Enforcement
`CancelAfter` on connect; mailbox deadline on the request wait.

### Measurement
Refused/timeout tests assert bounded failure; cancel test asserts CANCEL
reaches the peer.

### Tests
`SenderEngineTests`: refused → NETWORK_LOST/TIMEOUT, silent → TIMEOUT,
cancel → Cancelled + peer CANCEL observed.

---

## 3. Android receiver lifecycle

### Decision
`ReceiverEngine`: `start()` binds `ServerSocket(DATA_TCP_PORT)` + daemon
accept thread; per-data-client worker thread does preamble → atomic
pending→active adoption → `receiveBytes` loop → COMPLETE wait (bounded) →
finalize → terminal outcome. `stop()` closes the listener, aborts
active/pending (temp cleaned), joins promptly. No coroutines (M06 ADR 14
upheld: the M04 stack is thread-based; one flag-check per block needs no
dispatcher model).

### Why
Symmetric with the Windows M06 receiver and the Android M04 control stack;
one mental model per platform.

### Problem Solved
Prompt abort from any state without orphaned threads/sockets/files; a racing
CANCEL can only ever observe pending (delivers outcome) or active (aborts)
— never neither (the M06 "neither-pending-nor-active gap" fix is carried
over: pending clears ONLY inside the atomic adoption).

### Technology/Mechanism
Daemon threads, `synchronized(gate)` adoption, `CompletableFuture`
per-transfer waiters + `_terminal` decided-map + `_finished` FIFO (same as
M06 Windows receiver, ported to JVM), `volatile cancelled` + socket close to
unblock a blocked read, `deleteTempQuietly` on every abort path.

### Why This Technology
JVM `Socket` I/O unblocks only via `close()`; the flag+close pair is the
only prompt mechanism (same finding as M06 ADR 14).

### Alternatives
- Kotlin coroutines + `Dispatchers.IO` (rejected — new dependency/dispatcher
  model for one flag check per block; M13 revisits for the service).
- NIO async channels (rejected — callback machinery, zero single-stream
  benefit; M06 ADR 1 reasoning stands).

### Rejected Alternatives
See above.

### Trade-offs
Poll-per-block cancel granularity (≤1 block latency); gained: no framework,
deterministic tests.

### Bounds
Cancel latency ≤ one block op + close propagation; pending slot ≤
`DATA_ACCEPT_TIMEOUT_MS` (30 s); COMPLETE wait ≤
`TRANSFER_COMPLETE_TIMEOUT_MS` (10 s).

### Why Bounds Exist
A stale pending slot must not wedge the single-transfer receiver (M06
lesson); COMPLETE must arrive promptly or the transfer fails closed.

### Enforcement
`pruneStalePendingLocked()` on every request/data arrival; deadline loop for
COMPLETE; `stop()` joins accept thread ≤2 s.

### Measurement
Cancel test asserts prompt `Cancelled` + temp gone; COMPLETE-timeout test
asserts `TIMEOUT` failure (not completion).

### Tests
17 `ReceiverEngineTest` cases incl. busy-reject, stale races, stop semantics
via `isBusy()`.

---

## 4. Adaptive sizing reuse

### Decision
Reuse the M06 `AdaptiveBlockSizer` on both sides UNCHANGED (no new sizing
code on either platform). Sender sizes reads+writes; receiver sizes
socket-reads+file-writes independently — same algorithm, independent
instances, as in M06.

### Why
Sizing is direction-agnostic: it observes (bytes, nanos, stalled) samples
of a local read→write loop. The M07 loops are structurally identical to the
M06 loops.

### Problem Solved
Avoids two independently evolving controllers (explicitly forbidden without
technical reason, §10).

### Technology/Mechanism
`AdaptiveBlockSizer(total)` + per-block `observe()`; file-size-aware init;
[64 KiB, 1 MiB]; ×2/÷2; 3-window confirmation; 2 s cooldown; shrink disarms
regrowth until 1.2× improvement (M06 ADRs 3–8, unchanged).

### Why This Technology
Deterministic, fake-clock-testable, oscillation-resistant by construction;
already proven on both platforms (M06 sizer suites green, untouched).

### Alternatives
- Direction-specific tuning (rejected — zero evidence the reverse path
  differs; M15 owns tuning with benchmark data).
- Fixed block size for M07 (rejected — punts §10 requirements).

### Rejected Alternatives
See above.

### Trade-offs
None new; inherits M06 trade-offs (up to ~2 s + 3 windows reaction lag for
stability).

### Bounds
MIN 64 KiB / MAX 1 MiB / init-cap 256 KiB / stall 500 ms / cooldown 2 s /
3 windows / 1.2× re-arm / 0.5× shrink — all frozen M06 values.

### Why Bounds Exist
See M06 ADRs 4–8 (syscall amortization vs heap/cancel-latency ceilings).

### Enforcement
Sizer clamps every change; engines use `currentBlockSize` only; tests assert
final sizes within [MIN, MAX] on every transfer.

### Measurement
`adaptations` history in metrics; 1 GB cross-runtime run adapted twice on a
loopback transient (bounded history, documented normal controller behavior —
same phenomenon as M06's 1 GB run).

### Tests
M06 sizer suites (11 Android + 10 Windows) untouched and green; M07 engine
tests assert bounds on every transfer; equivalence holds by shared test
vectors (same rules, same constants — no fork to diverge).

---

## 5. Bounded buffering

### Decision
Exactly one reusable heap buffer per side sized to the current block; no
queues between read → write (M06 ADR 2, unchanged).

### Why
Any producer/consumer queue can grow without bound under speed mismatch
(fast disk + slow hotspot, or fast network + slow storage).

### Problem Solved
Unbounded pending-data accumulation; O(1) memory independent of file size.

### Technology/Mechanism
Synchronous read→write loop per direction; buffer field reallocated ONLY
when the sizer changes size (≤1 MiB).

### Why This Technology
Absence of a data structure is the strongest bound (no watermark tuning).

### Alternatives
- Bounded blocking queue + reader/writer threads (rejected — threads +
  tuning for no single-stream gain; duplicates kernel buffering worse).
- Double buffering (rejected — 2× heap for unneeded overlap; M15-gated).

### Rejected Alternatives
See above.

### Trade-offs
No read/write overlap; gained: minimal memory, trivial correctness.

### Bounds
Peak app heap per transfer ≈ 2 × block ≤ 2 MiB + kernel socket buffers.

### Why Bounds Exist
Phone heap safety (4–6 GB devices share RAM with OS/apps).

### Enforcement
Code inspection: exactly one `ByteArray`/`byte[]` field per engine; sizer
caps size; tests transfer 64 MB (sender) / 20 MB (cancel) on default JVM
heap.

### Measurement
Metrics `finalBlockSizeBytes` asserted in-range on every transfer test.

### Tests
Slow-receiver (4 MB @ ~3 MB/s) and slow-drip tests complete byte-exact with
unchanged peak allocation by construction.

---

## 6. Backpressure

### Decision
Structural backpressure via blocking I/O, zero app queues (M06 ADR 11,
unchanged). Sender: slow network → `WriteAsync` pends → reads pause.
Receiver: slow disk → file write pends → socket reads pause → sender blocks.
Documented locations: sender `WriteAsync` (SenderEngine.cs), receiver
`FileOutputStream.write` (ReceiverEngine.kt).

### Why
Explicit queues need watermarks, tuning, and overflow policy; blocking I/O
already implements perfect backpressure in the OS.

### Problem Solved
Memory-growth under producer/consumer speed mismatch (§9).

### Technology/Mechanism
Unbuffered `NetworkStream`/`OutputStream` writes of fixed-size blocks; the
OS socket buffers (typically 64–256 KiB, kernel-bounded) are the ONLY queue.

### Why This Technology
Kernel bounds are outside app control and already safe; the app adds
nothing.

### Alternatives
App-level bounded queue + producer/consumer threads (rejected — same as
ADR 5: extra threads + tuning, no benefit on one stream).

### Rejected Alternatives
See above.

### Trade-offs
No overlap (see ADR 5); gained: provable boundedness.

### Bounds
Kernel socket buffers only; app holds ≤ 1 block per side.

### Why Bounds Exist
Kernel bounds are pre-existing and safe.

### Enforcement
No queue data structures in the engines (auditable by inspection; audit
rerun for M07 — clean).

### Measurement
Slow-receiver test: 4 MiB at ~3 MB/s takes >1 s (proves the sender waited);
byte equality asserted.

### Tests
`SlowReceiver_BackpressureHolds_WithoutGrowth` (Windows),
`slow drip still completes byte exact` (Android).

---

## 7. TCP data-channel binding

### Decision
Reuse the M06 preamble contract verbatim: first bytes `"<transferId>\n"`
(37 ASCII bytes), receiver reads to `\n` capped at 64 bytes; unknown/
overlong/EOF → close with NO file touched. Transfer ID is routing/binding
only — authorization comes from the M05 session (checked BEFORE ACCEPT),
never from the preamble (§8, §16).

### Why
The anonymous data socket must attach to exactly one authorized transfer;
the preamble is the minimal binding with no wire change.

### Problem Solved
Cross-transfer and unsolicited data connections cannot reach the filesystem.

### Technology/Mechanism
`Encoding.ASCII` write + flush (sender); byte-at-a-time read to `\n`
(receiver); `Uuids` shape check + pending-slot match + atomic adoption.

### Why This Technology
Same bytes both platforms already prove (M06 + M07 interop MATCH).

### Alternatives
- M02 framing on the data connection (rejected — payload IS the file
  stream; length known from metadata; count-delimited, no per-block
  headers by design).
- Auth token on the data socket (rejected — new security contract; the
  control-plane session already authorizes; §20 gate).

### Rejected Alternatives
See above.

### Trade-offs
Preamble is plaintext UUID (fine: it is a capability-scoped binder inside
an already-authorized association, not a secret).

### Bounds
Preamble cap 64 bytes (`DATA_PREAMBLE_MAX_BYTES`); 36-char UUID + `\n`.

### Why Bounds Exist
A malicious peer must not grow receiver memory before authentication of the
binding (rejected without parsing/allocation).

### Enforcement
Length cap before parse; shape check before slot lookup; slot lookup before
any file creation.

### Measurement
Negative tests: bogus-UUID data socket → rejected, dest empty, engine still
serves the next legitimate transfer.

### Tests
`unknown preamble rejected without touching destination` (Android);
`completion size mismatch` / busy / misaddressed rejections (both).

### Fix note (M07)
Windows `DeriveControlPeerHost` normalizes IPv4-mapped IPv6
(`::ffff:127.0.0.1` → `127.0.0.1`, produced by dual-stack listeners):
the mapped form usually dials, but normalization gives stable logs and
avoids stacks that dislike it. Found by `ControlPeerHost_...` test;
sender-only addition, no M06 behavior changed.

---

## 8. 64-bit counters

### Decision
`long`/`Long` for every size, offset, and counter end-to-end (metadata
`fileSize`, transferred accumulators, progress snapshots, ETA math);
`Double` only for the final percentage display value (M06 ADR 12,
unchanged).

### Why
32-bit overflows at 2 GiB — below the 10 GB+ first-class requirement.

### Problem Solved
Truncation/negative counts/premature completion on large files.

### Technology/Mechanism
Native 64-bit on both runtimes; M02 JSON integers already 64-bit.

### Why This Technology
Zero cost, zero new code.

### Alternatives
`Int` with overflow checks (rejected — checks don't raise the ceiling).

### Rejected Alternatives
See above.

### Trade-offs
None material.

### Bounds
`0..2^63-1` (protocol bound); no app-level maximum (ADR 16).

### Why Bounds Exist
Protocol integers are signed 64-bit; the app adds no further limit.

### Enforcement
Audit: `int` appears only for ports/timeouts/counts/buffer lengths
(≤1 MiB, safely 32-bit); the two `int` casts (`want` from `remaining`) are
guarded by `remaining < buffer.Length` and documented at the site.

### Measurement
1 GB cross-runtime transfer tracked exactly (1,073,741,824 bytes both
ends).

### Tests
50 GB progress-math unit tests (both platforms); 10 GiB M02 vector
(pre-existing); `BoundarySizes` incl. 0/1-byte.

---

## 9. Android destination abstraction

### Decision
NEW `ReceiverDestination` interface + `FileReceiverDestination` default
(approach C). The engine streams through `tempFileFor` /
`uniqueFinalFileFor` / `freeSpaceBytes` / `finalize` /
`deleteTempQuietly`; it never assumes a raw path beyond what the
implementation provides.

### Why
JVM tests and app-private dirs ARE ordinary paths, but SAF/document-tree
destinations are NOT (no atomic rename, no `File` semantics,
`ContentResolver` streams). The seam lets a SAF backend plug in without
touching transfer logic.

### Problem Solved
Future SAF support without engine rewrite; honest capability boundaries
today.

### Technology/Mechanism
`File`-backed default: `<name>.localdrop-part` temp, first-free
`<stem> (n)<ext>` final, `renameTo` + copy fallback, best-effort temp
delete.

### Why This Technology
Same naming/uniquification contract as the proven Windows M06 receiver, so
behavior matches across platforms for file-backed destinations.

### Alternatives
- Raw `File destDir` in the engine (rejected — bakes the path assumption
  into transfer logic; SAF retrofit would fork the engine).
- Full SAF implementation now (rejected — needs device APIs + permissions
  unjustified in M07; no M13 service yet).

### Rejected Alternatives
See above.

### Trade-offs
One small interface to maintain; gained: storage-backend independence.

### Bounds
Suffix scan `2..99999` (practically unbounded; each probe one syscall).

### Why Bounds Exist
No arbitrary cap on user files; fail-safe exception instead of infinite
loop.

### Enforcement
`finalize` throws (never overwrites) on occupied final; engine fails the
transfer on any `finalize` throw — never reports success.

### Measurement
Collision test asserts original untouched + uniquified output, byte-exact.

### Tests
`existing destination never overwritten uniquified`, temp-suffix unit,
cleanup-on-every-failure-path tests.

---

## 10. Temporary-file lifecycle

### Decision
Receive into `<finalName>.localdrop-part`; on transport completion move to
the final name; delete the temp on cancel/failure/timeout/early-EOF/COMPLETE-
timeout (M06 ADR 13, unchanged; M07 temps explicitly non-durable — M10 will
define durability).

### Why
A mid-transfer file must never masquerade as complete; failed transfers
must not litter storage.

### Problem Solved
Partial-file confusion + storage leakage.

### Technology/Mechanism
`FileOutputStream(temp, false)` → close → `finalize` after byte-count +
COMPLETE match; `finally`/`deleteTempQuietly` on all abort paths. Temp holds
raw prefix bytes `[0..received)` with no header — adoptable by M10 resume.

### Why This Technology
OS same-volume rename is atomic (NTFS/ext4/f2fs); raw-prefix layout keeps
M10 options open without building resume now.

### Alternatives
- Write directly to final name (rejected — exposes partial bytes as real
  files).
- Hidden temp dir elsewhere (rejected — breaks same-volume atomicity).

### Rejected Alternatives
See above.

### Trade-offs
Temp + final briefly coexist at finalize (instantaneous); gained: crash
safety + no clobber.

### Bounds
None (size-agnostic by construction).

### Why Bounds Exist
N/A.

### Enforcement
`finalize` ONLY after byte-count equality AND `TRANSFER_COMPLETE` receipt
with matching size; every other exit deletes the temp (asserted per-path).

### Measurement
Tests assert temp presence rules: absent before data, present mid-slow-
transfer (implicit), absent after success/failure/cancel.

### Tests
Zero-byte path, early-EOF cleanup, COMPLETE-timeout cleanup, cancel
cleanup, mismatch-no-finalize.

---

## 11. Collision safety

### Decision
Occupied final name is NEVER overwritten: first-free `<stem> (n)<ext>` wins,
probed immediately before the move (M06 ADR 13, unchanged).

### Why
Silently destroying an unrelated user file is the classic
download-manager data-loss bug.

### Problem Solved
Destination clobbering.

### Technology/Mechanism
Existence check + numeric-suffix scan in `uniqueFinalFileFor` (computed at
adoption; re-checked by `finalize` throwing on occupied final).

### Why This Technology
Least-surprising no-clobber policy; matches platform conventions.

### Alternatives
- Overwrite with prompt (rejected — no UI workflow in M07; silent
  overwrite explicitly forbidden).
- Fail the transfer on collision (rejected — worse UX than uniquification;
  spec-conformant behavior is uniquify).

### Rejected Alternatives
See above.

### Trade-offs
TOCTOU window between probe and move (accepted, documented — same as all
file managers); gained: no data loss in the normal case.

### Bounds
Suffix scan capped at 99999 with fail-safe throw.

### Why Bounds Exist
Bounded probes; practically unreachable ceiling.

### Enforcement
`finalize` throws if final exists at commit time (defense in depth beyond
the adoption-time probe).

### Measurement
Collision test: original bytes identical after transfer; newcomer at
`keep (2).bin`, byte-exact.

### Tests
`ExistingDestination_NeverOverwritten_Uniquified` (both platforms).

---

## 12. Cancellation

### Decision
Cooperative cancellation, platform-idiomatic (M06 ADR 14, unchanged):
Windows `CancellationToken` plumbed through every `await` + per-block
checks; Android `volatile cancelled` + socket close to unblock I/O +
per-block checks; control-plane CANCEL in both directions; temp cleanup +
terminal `Cancelled` outcome on every path; cancellation NEVER reported as
`Failed` or success.

### Why
Blocking I/O does not poll flags while blocked; closing the socket is the
only prompt unblock on both stacks.

### Problem Solved
Prompt, leak-free abort from any state without orphaned resources or
ambiguous outcomes.

### Technology/Mechanism
Windows: linked CTS (caller + request), `CancelAfter` on connect, cancel
→ best-effort CANCEL send → `Cancelled(transferred)`. Android: `cancelled`
flag + `dataSocket.close()` + `fileOut.close()`; handler `CANCEL` →
pending-outcome-here or active-abort; `cancelTransfer()` local entry point.

### Why This Technology
Matches each platform's M04 idiom; zero new dependencies.

### Alternatives
Coroutines (Android) / blocking sender thread (Windows) — rejected per
ADRs 2–3.

### Rejected Alternatives
See above.

### Trade-offs
≤1-block cancel latency (~27 ms at target speed); gained: determinism.

### Bounds
Cancel latency ≤ one block op + close propagation.

### Why Bounds Exist
Responsiveness without per-byte polling cost.

### Enforcement
Check before every block read/write; close unblocks the current op;
double-cancel idempotent (flag + `OutcomeDelivered` guard).

### Measurement
Cancel tests assert prompt return + `Cancelled` state + temp gone + peer
observes CANCEL (sender-side) / stream stops (receiver-side).

### Tests
Cancel-mid-transfer both initiation directions; `sender error during
pending` (peer ERROR → pending Failed with sender's code).

---

## 13. Completion semantics

### Decision
Transport completion (`byte-count equality AND TRANSFER_COMPLETE receipt
with matching size → finalized → TransportComplete`) is EXPLICITLY NOT
integrity verification (M06 ADR 20, unchanged). No `verifying` state; state
`completed` = transport-complete only. `TRANSFER_COMPLETE.sha256Hex` carries
64 zeros (`M07HashNotComputed`, same value as M06) satisfying the frozen
shape; the receiver shape-validates (via M02 codec) and IGNORES the value.
SHA-256 verification belongs to M11 — not implemented, not half-built.

### Why
Hashing without verification burns CPU for zero assurance ("hash theater");
M11 owns verify-before-complete.

### Problem Solved
Prevents misreading transport success as integrity proof.

### Technology/Mechanism
Zero-sentinel constant; receiver compares ONLY sizes; no digest code
anywhere in the transfer path (audited — clean).

### Why This Technology
Satisfies the frozen required-field shape with an explicit, documented,
unverifiable sentinel — no wire change.

### Alternatives
- Omitting the field (rejected — violates frozen M02 schema).
- Computing-but-not-verifying (rejected — CPU cost, invites misreading).

### Rejected Alternatives
See above.

### Trade-offs
M07 completion is explicitly unverified; gained: honest states + clean M15
numbers.

### Bounds
None.

### Why Bounds Exist
N/A.

### Enforcement
Sender always emits the sentinel; receiver never compares it; size mismatch
→ `INVALID_PROTOCOL` failure (tested: proves the gate is sizes, not hash).

### Measurement
N/A in M07 (M11 measures verify cost separately).

### Tests
`completion size mismatch fails without finalizing`; COMPLETE-timeout →
`TIMEOUT` (proves COMPLETE is REQUIRED, not optional); zero-sentinel shape via
frozen M02 validation.

---

## 14. Progress reporting

### Decision
Reuse M06 progress model verbatim: 64-bit counters,
`percent = transferred*100/total` (`total==0 → 100%`), average from
monotonic clock, recent = last report-window rate, ETA only when recent > 0;
engine-throttled emits (≤1 per 500 ms + unconditional final); peer
`TRANSFER_PROGRESS` on the same throttle; listener exceptions isolated
(M06 ADR 15, unchanged).

### Why
Per-block callbacks would spam observers (~160 K for 10 GB at 64 KiB);
monotonic clocks survive NTP jumps; throttled peer messages keep control
responsive.

### Problem Solved
Observer flooding + div-by-zero + 32-bit overflow in one place.

### Technology/Mechanism
`TransferProgress.compute(...)` snapshots; `nanoTime`/`Stopwatch` (never
wall clock) for durations; `onProgress` callback (Android) /
`Action<TransferProgress>` (Windows); UI owns nothing (no new UI workflow).

### Why This Technology
Snapshots decouple engine/UI; time-gate is two comparisons.

### Alternatives
Callback per block / wall-clock durations (rejected — spam + jumps).

### Rejected Alternatives
See above.

### Trade-offs
500 ms granularity; gained: cheap, stable.

### Bounds
Throttle 500 ms (INITIAL CONSERVATIVE, M06 value).

### Why Bounds Exist
Human-perception rate; negligible overhead.

### Enforcement
Time-gate in emit path + unconditional final emit.

### Measurement
Tests assert monotonic non-decreasing transferred, final 100%, ETA null
early / sane later.

### Tests
Monotonicity + final-100% tests (both platforms); 0-byte → 100% unit check.

---

## 15. Performance instrumentation

### Decision
Reuse M06 `TransferMetrics` verbatim: elapsed, bytes, avg/recent
throughput, read/write nanos, stall count, adaptation history (capped 128),
final block size. Near-zero overhead (counters only; no per-block logging/
allocations in the hot loop).

### Why
M15 needs baselines; per-block logging would itself distort measurement.

### Problem Solved
Observable performance without observer effect.

### Technology/Mechanism
Primitive fields updated inline; history appended ONLY on adaptation
events (cooldown-bounded, rare).

### Why This Technology
A few ns per block; cheapest possible.

### Alternatives
ETW/Android tracing spans per block (rejected — heavyweight, M15 can add).

### Rejected Alternatives
See above.

### Trade-offs
Coarse (no per-block trace); gained: zero distortion.

### Bounds
History ≤ 128 entries (diagnostics bound, enforced by eviction).

### Why Bounds Exist
Unbounded history would itself grow with file size.

### Enforcement
Append-only-on-adapt; tests assert small history (1 GB run: 2 entries).

### Measurement
100 MB cross-runtime: sender 928 ms test-duration; receiver 409 ms /
244.5 MB/s. 1 GB cross-runtime: 5.2 s / 196 MB/s. Both far above the 37 MB/s
target (target, not gate — ADR 17).

### Tests
Metrics sanity on every transfer test (bytes == expected, elapsed ≥ 0,
avg == bytes/elapsed); interop logs measured MB/s.

---

## 16. Why no application-level file-size limit exists

### Decision
No `MAX_FILE_SIZE`-style constant; validation accepts the full protocol
range `0..2^63-1`; completion = byte-count equality (size-agnostic) — M06
ADR 18, unchanged.

### Why
The frozen spec requires 10 GB+ first-class; any app constant below
filesystem limits is an invented product limit.

### Problem Solved
Removes "works for small files only" bugs as a class.

### Technology/Mechanism
64-bit counters (ADR 8) + bounded memory (ADR 5) + count-equality
completion — none scale with size.

### Why This Technology
Size-agnostic completion is the only correct stream definition.

### Alternatives
Staged limits (rejected — explicitly forbidden; removal later = regression
risk).

### Rejected Alternatives
See above.

### Trade-offs
Very large files surface only real limits (disk/FS/OS); gained: no ceiling.

### Bounds
Filesystem / storage / OS only (outside app control).

### Why Bounds Exist
Physics, not policy.

### Enforcement
Audit for `MAX_*SIZE`/`2GB`/`4GB` shows only buffer bounds + fixtures
(rerun for M07 — clean); free-space precheck is advisory (fail-open;
write-time errors still handled).

### Measurement
100 MB + 1 GB loopback cross-runtime MATCH; 10 GB+ deferred (time, not
capability — see §20 of this ADR set / Deferred Work).

### Tests
50 GB progress math; engine transfers whatever the source yields; no
size-branching code exists to test.

---

## 17. Why 37 MB/s is a target and not a cap

### Decision
No throttling/pacing/sleep/rate-limit code anywhere in the transfer path
(M06 ADR 17, unchanged). The number 37 appears only in docs/tests as a
reporting reference.

### Why
Link-speed transfer is the product promise; any cap betrays faster hardware
(hotspot Wi-Fi routinely exceeds 100 MB/s; loopback measured ~200 MB/s).

### Problem Solved
Prevents "helpful" throttling from becoming a permanent ceiling.

### Technology/Mechanism
Absence of mechanism, enforced by audit (grep throttle/rate/sleep in
transfer code shows only progress throttling + adapt cooldowns, neither of
which gates bytes — rerun for M07).

### Why This Technology
A cap cannot be misconfigured if it cannot be expressed.

### Alternatives
Configurable cap defaulting to unlimited (rejected — a cap-shaped knob
invites capping; YAGNI).

### Rejected Alternatives
See above.

### Trade-offs
None (a missing limiter has no downside).

### Bounds
None — unbounded by design.

### Why Bounds Exist
N/A.

### Enforcement
Static audit + measured throughput (196–294 MB/s, proving no cap).

### Measurement
Interop logs measured MB/s; report states ≥37 achieved (not a pass/fail
gate — hardware-dependent).

### Tests
Throughput assertion is informational only.

---

## 18. Why resume remains deferred to M10

### Decision
No checkpoint persistence, no offset negotiation beyond `offset=0`, no
reconnect continuation. `resumeOffset != 0` → `TRANSFER_REJECTED`.
Interruption ⇒ clean failure + temp cleanup (M06 ADR 19, unchanged).

### Why
Resume needs durable checkpoints + identity revalidation + hash-gated
completion (M10/M11 machinery) that must not be half-built into transfers.

### Problem Solved
Keeps M07 reviewable; prevents half-resume corruption.

### Technology/Mechanism
`TRANSFER_ACCEPT.resumeOffset` always 0; nonzero rejected; temp raw-prefix
layout pre-adoptable by M10.

### Why This Technology
Explicit non-support beats partial support.

### Alternatives
Best-effort resume now (rejected — resuming without M11 verification risks
silent corruption; worse than clean failure).

### Rejected Alternatives
See above.

### Trade-offs
Interrupted large transfers restart; gained: correctness + clear scope.

### Bounds
None.

### Why Bounds Exist
N/A.

### Enforcement
Receiver rejects nonzero `resumeOffset` (tested both values classes);
no checkpoint code exists (audited).

### Measurement
Interruption (early EOF, control loss) → clean failure, never hang or
false completion.

### Tests
Early-EOF, COMPLETE-timeout, control-close paths all fail safely + clean
temp.

---

## 19. Why SHA-256 verification remains deferred to M11

### Decision
M07 computes no hashes (M06 ADR 20, unchanged). See ADR 13 above for the
sentinel mechanics.

### Why
Same as ADR 13: verification is a milestone of machinery (digest both ends
+ verify-before-complete states), not a flag.

### Problem Solved
Avoids hash theater and keeps M07 throughput numbers clean for M15.

### Technology/Mechanism
`M07HashNotComputed` / `M06_HASH_NOT_COMPUTED` 64-zero constant.

### Why This Technology
No wire change; upgrade path defined (M11 sends the real hash in the same
field; receivers re-tested then).

### Alternatives
Compute-without-verify (rejected — CPU for zero security benefit).

### Rejected Alternatives
See above.

### Trade-offs
Completion explicitly unverified; gained: honest states.

### Bounds
None.

### Why Bounds Exist
N/A.

### Enforcement
No `MessageDigest`/`SHA256` in production transfer code (audited — test-only
hashing is absent too; byte comparison is direct).

### Measurement
N/A in M07.

### Tests
Sentinel asserted on the wire (`COMPLETE carries zeros`); malformed hash
shape still rejected by frozen M02 validation (pre-existing tests).

---

## 20. Physical acceptance-test limitations
### Decision
Physical offline acceptance (mobile-data-OFF + hotspot-ON + 100 MB/1 GB/
10 GB+ over real Wi-Fi) is recorded NOT RUN for M07: no Android hardware is
available in this environment. Loopback cross-runtime interop (100 MB +
1 GB MATCH) is the strongest available proxy, exactly as in M06.

### Why
Inventing physical results would be fabrication; loopback proves the full
production stacks (M04 handshake + M05 ECDSA auth + code authorization +
preamble binding + streaming + finalize) with only the destination address
differing from the hotspot path.

### Problem Solved
Honest verification status; M16 owns the physical gate.

### Technology/Mechanism
127.0.0.1 loopback with production ports/auth/crypto/engines; temp harness
(`interop7.ps1` + `ReceiverInterop.java`, not committed — same convention
as M06's temp harness).

### Why This Technology
Same send/receive/validate code as the hotspot path; only L2 differs.

### Alternatives
- Claiming hotspot success from loopback (rejected — dishonest).
- Skipping interop entirely (rejected — leaves the M07 direction unproven
  across runtimes).

### Rejected Alternatives
See above.

### Trade-offs
Radio effects (congestion, stalls, Hotspot NAT quirks) unobserved until
M16; gained: truthful log.

### Bounds
N/A.

### Why Bounds Exist
N/A.

### Enforcement
IMPLEMENTATION_LOG marks physical status NOT RUN / 10 GB+ DEFERRED.

### Measurement
Loopback: 100 MB MATCH (409 ms receiver-side, 244.5 MB/s), 1 GB MATCH
(5.2 s, 196 MB/s), independent stream-compare on the JVM side.

### Tests
Automated unit/integration (215 + 168) + loopback interop (same-stack both
platforms + cross-runtime both sizes). Physical: NOT RUN.

---

## Protocol change gate (§20 of the milestone)

No protocol change was required. Every M07 need mapped to a frozen M02
type/field: TRANSFER_REQUEST/ACCEPT/REJECT/START/PROGRESS/COMPLETE/CANCEL/
ERROR with existing ranges (`resumeOffset=0`, omitted-then-zero `sha256Hex`,
existing error codes). Ports 48423/48424/48425 reused. No new type, field,
framing rule, port behavior, or security contract was introduced; M02/M04/
M05 contracts are byte- and behavior-compatible (proven by cross-runtime
MATCH against the untouched M06-era JVM receiver logic operating the same
flow in reverse).

---

# Verification-audit amendments (2026-10-09 and later)

An independent audit of the M07 work found real defects and one genuine
integration gap. Each was fixed minimally inside M07 scope (plus two
minimal M03 prerequisite corrections). No wire change; protocol stays v1.

## A1. Staging commit in FileReceiverDestination.finalize (HIGH, fixed)

Defect: the copy fallback wrote DIRECTLY to the final name � a mid-copy
crash/exception left a partial file AT the final name (a misleading
"completed" file), and `FileOutputStream(final)` could clobber a file that
appeared after the existence check. The word "atomic" was also over-claimed
for that path.

Fix: the final name is NEVER written directly. Fast path = same-directory
rename of the temp. Slow path (rename failed, e.g. cross-volume) = copy
into `<final>.localdrop-commit` STAGING (with CREATE_NEW guard + length
check + re-checked absent final), then same-directory rename of the
staging file. Any failure deletes staging and throws; the engine fails the
transfer (never success). A crash can leave temp/staging files behind, but
never a partial file at the final name.

Tests: `ReceiverDestinationTest` (6): fast path, occupied-final no-clobber,
forced-staging byte-exact (rename seam override), commit-failure cleanup
(no final, no staging leftover), missing-temp failure, uniquification
sequence.

## A2. Pending-slot leak on adoption setup failure (MEDIUM, fixed)

Defect: Android `failPendingNow` delivered the Failed outcome but left the
pending slot set � the receiver reported busy until the 30 s stale prune.

Fix: release the slot under gate before delivering (M07 file only). The
identical latent pattern in frozen M06 Windows `ReceiverEngine` is NOTED
but untouched per scope (self-heals via prune; trigger needs 99999
collisions).

Tests: `adoption setup failure releases pending slot` (fake destination
throws at adoption ? Failed + `isBusy()==false` + empty dest dir).

## A3. Pre-cancelled token escaped as exception (MEDIUM, fixed)

Defect: Windows `Task.Run(..., token)` with an already-cancelled token
throws `TaskCanceledException` out of `SendAsync` instead of returning the
contractual `Cancelled` outcome.

Fix: catch `OperationCanceledException` around the mailbox wait ? best-effort
CANCEL + `Cancelled` (M07 file only).

Tests: `PreCancelledToken_ReturnsCancelledWithoutThrow`,
`CancelDuringRequestWait_ReturnsCancelledPromptly`.

## A4. Discovery carries no dialable endpoint (HIGH gap, fixed minimally)

Defect (pre-existing M03/M04 gap, blocking �3 end-to-end): announcements
hardcoded `controlPort 0` and registries dropped the UDP source address, so
no app code could dial a discovered peer � manual IP entry was the only
path.

Fix (minimal prerequisite, no wire change � `controlPort` 0..65535 is
M02-legal, `lastSeenHost` is a local dial hint, keying stays deviceId-only):
`DiscoveredPeer.lastSeenHost` (nullable, blank/oversized normalized away,
refresh preserves last-known); `handleDatagram(..., senderHost?)` (default
null � all M03 tests unmodified and green); `buildAnnouncement(local,
controlPort = 0)` (default preserves M03 shape); listen loops record the
source address. Host is NEVER identity (same-LAN distrust upheld).

Tests: `PeerEndpointTest(s)` (5+5): record/preserve/replace/normalize,
announcement port, loopback propagation of host + port on both platforms.

## A5. Discovery-to-transfer integration (HIGH gap, fixed minimally)

Gap: placeholder UI + engines existed, but no connected
discover ? dial ? auth ? code ? send path; the commit-vs-teardown race
(sender disconnect racing receiver commit across two TCP connections) was
also found live during audit interop (receiver `NETWORK_LOST`/"Control
channel lost" after sender success).

Fix: Windows `TransferCoordinator` (snapshot ? `PeerEndpointResolver` ?
dial ? mutual auth ? code submit ? `SenderEngine` ? 1 s commit linger ?
teardown; receiver stays authoritative) + `TransferCommitLingerMs = 1000`
(INITIAL, AppConfig) + Android `ReceiveCoordinator` (hosts
discovery-with-live-port + control + auth + transactions + engine) +
`PeerEndpoint.kt` resolver. No UI redesign (explicit remaining gap: screens
are a later milestone; users cannot yet tap-to-send � verdict reflects it).

Tests: `TransferCoordinatorTests` (resolver null-rules, unknown-peer
fail-closed, full loopback discovery?transfer with addresses asserted from
the snapshot only), `CoordinatorInteropTest` (same, Android-hosted).

## A6. Outcome-claim races + stray-frame bound (MEDIUM, fixed)

Defect: concurrent terminal paths (commit vs racing cancel/failure) could
deliver TWO outcomes for one transfer (map overwrite + double queue entry);
unmatched TRANSFER_COMPLETE frames accumulated unboundedly.

Fix (M07 Android file): first-claim-wins (`claimOutcome` under gate;
detach/close always run; delivery only on claim) + commit serialized with
abort-deletion under `finalizeGate` (lock order finalizeGate ? gate
everywhere; commit-cancel interleave can only resolve to one consistent
pair: no-final+Cancelled or final+TransportComplete) + `earlyCompletes`
capped at 16 (live-transfer entry always kept; strays beyond cap dropped).
Frozen M06 Windows shapes noted, untouched.

Tests: `cancel during complete wait yields exactly one cancelled`
(+late-COMPLETE stray ignored, no final), `late cancel after completion
does not duplicate outcome`, `stray complete flood does not break live
transfer`.

## A7. Cross-runtime harness committed (process fix)

The M06-convention temp harness is now committed as
`test-assets/interop-m07/` (driver + parameterized `interop7.ps1` + README)
so the CLR→JVM proof is re-runnable, not temp-only. Gated
`SenderInteropTests` gained the same commit grace as the coordinator
(1 s hold before teardown) after the audit caught the race there too.
Re-verified: 10 MB + 100 MB MATCH via the committed script.

---

## U1. Framework-free UI controllers (M07 UI closure)

### Decision
All UI state and workflow logic lives in framework-free controller classes
— Windows `Transfer/SendController.cs` (INotifyPropertyChanged, WPF-bound)
and Android `transfer/SendUiController.kt` + `transfer/ReceiveUiController.kt`
(plain state + listener, Compose-observed). Views (XAML, Compose) render
state and dispatch user actions only. No sockets, file I/O, crypto, or
orchestration in any view.

### Why
UI/UX Spec §18 requires exactly this layering, and it makes the workflow
unit-testable without UI automation: the same outcome/progress channel the
engines use is the only thing the controllers display.

### Problem Solved
Tap-to-send usability without a second networking stack and without
untestable code-behind logic.

### Technology/Mechanism
Windows: controller owns a worker task driving `ITransferSender`
(production: `TransferCoordinator`), a 2 s discovery-snapshot poll timer,
and a CTS; notifications marshalled to an explicitly injected
`SynchronizationContext` (never ambient capture). Android: controllers own
one worker thread each driving `SendCoordinator`/`ReceiveCoordinator`;
state holder + `onChange` callback observed by Compose snapshot state.

### Why This Technology
Matches each platform's existing idiom (async/Timer + sync context on
Windows; threads + callbacks on Android — same as the M04/M06 stacks) with
zero new dependencies.

### Alternatives
- Logic in code-behind/Composables (rejected — untestable, violates §18).
- MVVM framework / ViewModel dependency (rejected — new dependency for
  needs the current stack already satisfies).
- Ambient `SynchronizationContext.Current` capture (rejected — xUnit
  installs a non-pumping test-runner context that silently drops updates;
  found live during controller testing; explicit injection instead).

### Rejected Alternatives
See above.

### Trade-offs
Controllers duplicate a thin phase vocabulary per platform; gained: full
unit coverage of the user-visible workflow on both platforms.

### Bounds
One active transfer per controller (M12 owns more); peer poll 2 s;
duplicate StartSend while busy returns false.

### Why Bounds Exist
Single-transfer engines beneath; poll matches the 2 s announce interval.

### Enforcement
Lock-gated busy flag checked synchronously in StartSend; timer/threads
joined in close/Dispose.

### Measurement
State assertions per workflow step in controller tests.

### Tests
`SendControllerTests` (24, Windows) + `SendUiControllerTest` (15) +
`ReceiveUiControllerTest` (5) incl. a full loopback run through the real
`SendCoordinator` asserting discovery-resolved dialling and byte equality.

## U2. Windows app wiring without a second network path

### Decision
`App.xaml.cs` builds the production stack once (CNG identity store →
DeviceInfo → `LanDiscoveryService` announcing the real control port →
`TransferCoordinator` → `SendController` with native-dialog picker) and
`MainWindow` binds to it; code-behind handlers only delegate to the
controller. `StartupUri` removed so exactly one window exists with exactly
one controller.

### Why
One construction site guarantees the UI drives the same coordinator the
integration tests prove; removing `StartupUri` prevents a second,
controller-less window.

### Problem Solved
The user flow (discover → select → file → code → send → progress → outcome)
with no manual address and no parallel stack.

### Technology/Mechanism
`CngDeviceIdentityStore(AppConfig.DeviceIdentityKeyName)` (persisted,
non-exportable; identity recomputed deterministically), machine-name device
name clamped to the M02 bound, `SecurityLoggers.CreateDefault()` MEL sink.

### Why This Technology
Existing production components only; no new stores, no new logging.

### Alternatives
- Software (ephemeral) identity in production (rejected — deviceId would
  rotate every launch, breaking peer recognition and M05 continuity).
- Wiring in MainWindow ctor (rejected — untestable ordering, harder
  lifecycle).

### Rejected Alternatives
See above.

### Trade-offs
App startup does KeyStore + discovery bind on the UI thread (both fast
local operations, bounded); gained: single obvious construction order.

### Bounds
None new.

### Why Bounds Exist
N/A.

### Enforcement
Code review: App owns construction/disposal; controller owns workflow;
coordinator owns connections.

### Measurement
Manual launch checklist (window opens, peers populate); automated coverage
via controller + coordinator suites.

### Tests
Build 0 errors; `SendControllerTests` + `TransferCoordinatorTests` green;
full suite green (see log).

## U3. Android SAF document source through the existing abstraction

### Decision
`ContentUriTransferSource` implements the frozen `TransferSource` interface
over a Storage Access Framework `content://` URI: display name/size captured
once at pick time via `OpenableColumns`, bytes streamed later via
`ContentResolver.openInputStream` on the worker thread. The engine is
unchanged and never assumes a raw path.

### Why
`OpenDocument` (API 19+, no new permission, per-URI grants) is the
supported picker on the whole minSdk range; the M06 source abstraction was
designed exactly for non-path sources (ADR 1).

### Problem Solved
Sending files from Downloads/Drive/SD cards without path assumptions and
without READ_EXTERNAL_STORAGE-style permission escalation.

### Technology/Mechanism
`ActivityResultContracts.OpenDocument()` with `*/*`; one metadata query on
the UI thread; fail-fast validation (blank/non-base names rejected at pick
time with an actionable message; the engine re-validates per M02 anyway).

### Why This Technology
OS-supported picker contract; no new dependency (activity-compose already
shipped); no new manifest permission (INTERNET already covers sockets).

### Alternatives
- `PickVisualMedia` (rejected — media-only, narrower than the transfer use
  case).
- Raw `/sdcard` paths (rejected — breaks on scoped storage, contradicts the
  abstraction).
- Full SAF-tree destination receiver (rejected for M07 — larger than the
  minimum; file-backed app-private destination documented as the M07
  default, seam retained).

### Rejected Alternatives
See above.

### Trade-offs
One file per transfer (engine scope); gained: every provider works through
one streaming shape.

### Bounds
None new (engine bounds apply).

### Why Bounds Exist
N/A.

### Enforcement
Constructor validation; engine M02 name/shape validation downstream.

### Measurement
Picker-cancel and picker-failure paths covered in controller tests; byte
correctness via the loopback e2e test (in-memory source) + engine suites.

### Tests
`setFile(null)` keeps state; `showMessage` path for unreadable documents;
`ContentUriTransferSource` validation asserts (covered via picker-failure
unit paths; ContentResolver itself is untestable on JVM by design).

## U4. Receiver acceptance model (no interactive Accept button)

### Decision
No Accept/Reject buttons on the Android receive screen. Authorization IS
acceptance in the M05 design: the receiver user mints and reads out a
single-use expiring code; the transfer starts only after the sender submits
it and the `TransactionAuthorizer` consumes it. The UI offers New code,
live incoming info/progress, outcome, and receiver-initiated Cancel
(`cancelTransfer`).

### Why
The frozen receiver engine auto-accepts authorized sessions; inventing a
second accept gate would be a parallel authorization scheme (forbidden).

### Problem Solved
Spec §7.4 "accept/reject if required" resolved as NOT required — without
weakening anything.

### Technology/Mechanism
`ReceiveCoordinator.createTransaction()` → spaced display form
(`AuthUiState.formatCode`); expiry/consumed/replay rules enforced by the
untouched `TransactionManager`; cancel via the existing engine abort
(sender observes fail-closed connection loss; no partial file).

### Why This Technology
Zero new security code; the exact flow M05 tests prove.

### Alternatives
- Interactive accept dialog gating TRANSFER_ACCEPT (rejected — duplicates
  the code gate, adds races with the pending-slot timeout).
- Auto-approving codes (rejected — bypass, explicitly forbidden).

### Rejected Alternatives
See above.

### Trade-offs
Receiver cannot screen individual files before bytes flow — but the code
relay already restricts senders to humans the receiver talks to.

### Bounds
M05 code lifetime/single-use unchanged.

### Why Bounds Exist
Unchanged (M05).

### Enforcement
Untouched `TransactionManager` + authorizer; controller displays codes only
(full code never logged — M05 `SecurityLogger` contract).

### Measurement
Expiry/replay covered by direct `TransactionManager` tests with a fake
clock; e2e wrong-code path covered at coordinator level.

### Tests
`expired code is rejected by the preserved M05 rule`,
`receiver cancel aborts inbound and cleans up` (sender sees NETWORK_LOST,
no final/temp leftovers).

## U5. Completion wording honesty (spec deviation, documented)

### Decision
Success screens say "Completed" (the frozen engine/outcome vocabulary) plus
a one-line note that file integrity verification arrives in M11. No
"Verifying…/Verified" states are shown anywhere.

### Why
UI/UX Spec §6/§8/§13 wants verification before Completed, but M11 is
deferred by frozen milestone scope; displaying verification would fabricate
assurance the code cannot provide.

### Problem Solved
No fake progress, no fake assurance (§10/§17 rules upheld).

### Technology/Mechanism
Static note under Succeeded outcomes on both platforms; progress/ETA come
only from real `TransferProgress` snapshots.

### Why This Technology
Truthful within current capability; the note disappears naturally when M11
lands.

### Alternatives
- Showing "Verified" on transport-complete (rejected — dishonest).
- Renaming the state to "Transport complete" in UI (rejected — diverges
  from the frozen TRANSFER_STATES vocabulary users of both platforms
  share; the note carries the nuance instead).

### Rejected Alternatives
See above.

### Trade-offs
Users see a caveat line; gained: no false security claims.

### Bounds
None.

### Why Bounds Exist
N/A.

### Enforcement
Grep-able static strings; no VERIFY-state code exists to display.

### Measurement
N/A.

### Tests
Succeeded assertions check the "Completed" text and the M11 note.

## U6. Lifecycle and rotation limits (explicit)

### Decision
Controllers own worker threads/discovery-polling and join/cancel them in
`close()`/`Dispose()`; Compose `DisposableEffect` / WPF `OnExit` drive
cleanup. Screen rotation recreates the Activity and therefore the
controllers: an active send is cooperatively cancelled, an active receive
is aborted (temp cleaned), discovery restarts (~2 s to repopulate).

### Why
Without a ViewModel/service dependency (new deps rejected for M07),
Activity-scoped ownership is the smallest leak-free design; M13 owns
background transfers.

### Problem Solved
No leaked threads/sockets/listeners across recompose, navigation, or exit.

### Technology/Mechanism
Idempotent close (flag + join with timeouts); daemon worker threads;
coordinator stop cascades to engines.

### Why This Technology
Deterministic teardown provable in unit tests (no device needed).

### Alternatives
- ViewModel/new lifecycle dependency (rejected — unjustified new dep).
- Leaking controllers across rotation (rejected — socket/thread ownership
  ambiguity).

### Rejected Alternatives
See above.

### Trade-offs
Rotation cancels active transfers; gained: no leaks, no new dependencies.

### Bounds
Join timeouts 3–5 s (tests assert prompt close).

### Enforcement
`close stops coordinator and ends callbacks` tests on both platforms.

### Measurement
Callback counters before/after close; discovery `isRunning` assertions.

### Tests
`Dispose_CancelsActiveTransfer_AndStopsPolling` (Windows),
`close stops coordinator and ends callbacks` (Android).

## U7. Explicitly NOT built (scope guard)

Widgets (all four), Windows tray, transfer history, paired-devices lists,
settings screens, pause/resume buttons, multi-file batch send, SAF-tree
destinations, and real-device Wi-Fi proof remain future work (M12/M13/M14/
M16+). Progress animation represents real snapshots only; no decorative
motion added. No new production dependency was added on either platform
(manifest and both dependency lists unchanged — verified by diff).
