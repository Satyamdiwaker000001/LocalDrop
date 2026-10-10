# LocalDrop — No-Compromise Completion Directive

## Mission

Complete the existing LocalDrop project as quickly as reasonably possible without compromising any frozen requirement, security property, correctness guarantee, performance-validation requirement, or release-quality criterion.

This directive authorizes efficient execution, not scope reduction, test weakening, failure concealment, or unnecessary rewrites.

## 1. Non-negotiable rules

- Follow the authoritative LocalDrop Master Specification v1.2, protocol, security, and test-plan documents.
- Preserve offline-only, direct Wi-Fi peer-to-peer operation. No cloud, relay, external API, or Internet dependency.
- Preserve authenticated and authorized transfers, cryptographic identity, and transaction-code security.
- Keep file transfers streaming with bounded application memory, backpressure, and 64-bit byte counters.
- Implement durable, correct resume semantics and end-to-end SHA-256 integrity verification.
- Preserve the specified bidirectional Android ↔ Windows transfer and Android ↔ Android support.
- Do not impose arbitrary application-level file-size or throughput caps.
- Validate duplicate filenames, cancellation, partial failures, temporary-file cleanup, and safe finalization.
- Never claim a test, benchmark, security property, or physical-device scenario passed without evidence.
- Never weaken, delete, skip, or trivialize tests to obtain a passing build.
- Avoid unrelated dependency upgrades, UI redesigns, and architectural rewrites.
- Preserve already-working code and verified progress. Reuse it unless a demonstrated defect requires a change.

If implementation and specification conflict, follow the authoritative specification, document the discrepancy, and fix it. Do not silently redefine requirements.

## 2. Execution order

### Phase A — M07/M08 closure: verify existing work; do not repeat it

The latest implementation report claims:
- Windows test suite: 251/251 passing.
- Android unit tests: successful.
- Windows temporary-file cleanup: bounded retries for selected I/O failures, with exhausted failures logged.
- Android lifecycle scoping fixes.
- M08 performance methodology document created.

First inspect the current repository, actual test output, changed files, and implementation log to determine which claims are verified.

Do not repeat completed work without a concrete reason. Fix only genuine remaining M07/M08 blockers. Verify that the methodology describes actual harnesses and results rather than merely proposing future tests.

Keep physical-device acceptance explicitly pending until real devices have been tested.

If the existing evidence is sufficient for development progression, proceed directly to M09. Do not restart Phase A just because this directive is being supplied again.

### Phase B — M09–M12: core transfer correctness

**M09: Large-file acceptance**
- Test representative file sizes, including files larger than 4 GiB where the environment supports them.
- Verify exact byte counts, bounded memory, completion behavior, cancellation, disk-space errors, and cleanup.
- Record unavailable scenarios as NOT RUN or BLOCKED. Do not substitute smaller tests silently.

**M10: Reliable resume**
- Implement receiver-side durable checkpoints and correct restart/reconnect semantics.
- Prevent incorrect continuation, duplicate writes, corrupted partial files, and unsafe checkpoint reuse.
- Test interrupted transfers, process restarts, stale checkpoints, changed source files, cancellation, and finalization.
- Justify checkpoint frequency and durability trade-offs. Do not invent arbitrary policies.

**M11: SHA-256 integrity**
- Verify content end-to-end using SHA-256.
- Prevent successful completion when the digest or expected size does not match.
- Define failure handling, cleanup, and resume interactions.
- Use independent correctness oracles where appropriate.

**M12: Multi-session**
- Implement safe concurrent sessions according to the specification.
- Isolate session identity, buffers, cancellation, checkpoints, progress, temporary files, and errors.
- Test concurrent transfers, filename collisions, cancellation isolation, resource pressure, and shutdown.
- Justify and test any concurrency limits.

### Phase C — M13–M14: platform integration

**M13: Android**
- Implement the required foreground transfer service, notifications, and widgets.
- Handle activity recreation, process lifecycle, cancellation, and long-running transfers correctly.
- Test lifecycle and background-execution behavior.

**M14: Windows**
- Implement system-tray integration and safe shutdown.
- Preserve single-instance/window behavior and clean resource disposal.
- Test shutdown during active transfers and recovery from partial initialization.

Do not redesign working UI without a demonstrated functional or usability defect.

### Phase D — M15–M18: validation and release

**M15: Benchmarking**
- Provide reproducible benchmarks with environment details, sizes, warm-up policy, repetitions, units, measurement boundaries, and correctness verification.
- Separate loopback results from real Wi-Fi results.
- Compare adaptive sizing with fixed sizing only when a valid baseline exists.
- Treat 37 MB/s as a target, never a throughput cap.
- Report measured results and limitations; never fabricate measurements.

**M16: Physical offline acceptance**
- Test real Android and Windows devices using the same phone hotspot with mobile data disabled.
- Verify discovery, authorization, both transfer directions, cancellation, large files, integrity, and resume.
- Record devices, OS versions, network conditions, and actual outcomes.
- Loopback and simulated tests cannot substitute for physical-device acceptance.

**M17: Packaging**
- Produce usable Android and Windows release artifacts.
- Validate installation, startup, permissions, firewall/network behavior, data locations, and applicable uninstall/reinstall scenarios.
- Document signing and distribution limitations honestly.

**M18: Hardening**
- Run final regression, security, failure-injection, cleanup, and lifecycle tests.
- Review logs for sensitive-data leakage.
- Check protocol validation, resource exhaustion, concurrent operations, and dependency advisories.
- Fix genuine critical blockers before declaring release readiness.

## 3. Required execution discipline

For each milestone:

1. Inspect the relevant specification, current implementation, existing tests, and previous evidence.
2. Identify the smallest correct change that satisfies the acceptance criteria.
3. Explain significant architectural decisions: decision, rationale, alternatives, trade-offs, enforcement, and verification.
4. Reuse correct existing code and tests. Do not rebuild working functionality without cause.
5. Run relevant tests and builds; capture actual commands, exit codes, and results.
6. Inspect the complete diff for regressions, accidental limits, missing tests, and unrelated changes.
7. Update the implementation log and milestone report with evidence and limitations.

Do not request approval for every routine implementation detail. Use the frozen specifications and existing architecture. Ask only when a genuine ambiguity or irreversible decision cannot be resolved from authoritative sources.

If blocked, record the precise blocker and continue independent work where safe.

## 4. Definition of done

LocalDrop is complete only when:

- All M01–M18 acceptance criteria are satisfied, except for explicitly permitted and documented limitations.
- Required automated tests and builds pass.
- Both transfer directions and required platform combinations work as specified.
- Security, integrity, resume, multi-session, and large-file behavior have evidence.
- Real-device offline acceptance has actually been performed.
- Android and Windows release artifacts are produced and validated.
- No known critical correctness, security, or data-loss defect remains.
- The final report distinguishes PASS, FAIL, NOT RUN, BLOCKED, and NOT APPLICABLE.

A successful build alone does not establish release readiness.

## 5. Immediate next action

Inspect the existing M07/M08 results without repeating completed work. If no concrete M07/M08 blocker remains, start **M09 — Large-File Acceptance** immediately.

Complete and report M09 before starting M10. Continue through M18 in order, reusing verified implementation and tests.

Do not claim completion before evidence exists. Do not compromise quality to meet a date. Optimize for the shortest correct path to a genuinely complete LocalDrop release.