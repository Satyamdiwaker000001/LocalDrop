# M13 — Service-Owned Transfer Graph: Architecture Decisions

## Ownership and lifecycle
- ADR-1: The `TransferService` (not any Activity) owns the receive stack:
  control server, data engine, discovery loop, checkpoint DAO. Activities
  bind only to observe snapshots; bind/unbind starts or stops nothing.
- ADR-2: `TransferGraph` (framework-free) enforces the exact lifecycle:
  start is idempotent and ordered (control → data → discovery); stop is
  idempotent and reversed; partial-init failure rolls back started
  components and propagates. Repeated starts create no duplicate engines,
  listeners, loops, or workers (proven by `startCount`).
- ADR-3: Zero active transfers keeps the graph listening (idle ≠ stopped).
  The service drops foreground status and cancels per-transfer
  notifications; only explicit `stop()` / `onDestroy()` shuts listeners
  down. Activity recreation re-binds to the same graph instance.
- ADR-4: `MainActivity.AppStack` keeps identical checkpoint wiring for the
  UI-bound path; on device the service builds the same graph. Both go
  through `CheckpointStore.resolve()` so failure handling is uniform.

## Notification identity
- ADR-5: `transferId.hashCode()` is NEVER a notification ID ("Aa" vs "BB"
  collide). `NotificationIds` allocates a distinct stable integer per
  transferId and `release()` routes each cancellation to the exact issued
  ID; unknown IDs cancel nothing.

## Platform compatibility (targetSdk 36, minSdk 26)
- ADR-6: Foreground-service type is `dataSync` (declared in the manifest
  with `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` permissions).
  `dataSync` is the type whose documented purpose is data transfer; no
  other type fits. `POST_NOTIFICATIONS` (API 33+) gates display at runtime.
- ADR-7 (limits, honestly stated — no unlimited background execution
  claimed): the service must call `startForeground` within the system
  timeout after `startForegroundService`; background starts are restricted
  outside user-visible exemptions; Doze/App Standby defer discovery and
  network; the user or system may stop the service at any time. Foreground
  status reduces, never removes, killability. Verified against the manifest
  and `targetSdk = 36` in `app/build.gradle.kts`; on-device behavior is
  NOT RUN (no device in this environment).

## Widget state
- ADR-8: Widgets render `TransferWidgetSnapshot`s derived from the
  authoritative engine at transition time only (no poll loop;
  `updatePeriodMillis="0"`). Snapshots carry epoch + generation; stale
  (>60 s) or post-recreation-absent snapshots render as idle — stale
  active transfers are never presented as current.

## Resume integration (M10)
- ADR-9: `ReceiveCoordinator` takes the `CheckpointDao` and hands it to
  `ReceiverEngine` (which already calls `getCheckpointAndMigrate` /
  `saveCheckpoint` / `clearCheckpoint`). The app graph supplies the Room
  implementation; tests prove it with the same contract.
- ADR-10: Room init failure yields `(dao=null, failure=e)`; the graph runs
  with offset-0 transfers AND the UI shows `failureNote()`. Resume is never
  silently disabled.

## Preserved behavior
- ADR-11: No change to M05 auth/session/authorization ordering, M10
  checkpoint rules, M11 SHA-256 verification, or M12 multi-session
  isolation. The full pre-existing suites must stay green; the wiring test
  additionally proves code-less rejection and resume-offset enforcement
  through the service-owned graph.

## Bidirectional audit (no-emulator follow-up)
- ADR-12 (gap found and fixed): outgoing transfers were ViewModel-owned
  (AppStack built SendCoordinator + LanDiscovery; the ViewModel started/
  stopped them; UI close() actively cancelled in-flight sends) while only
  incoming was service-owned — and the service path was unreachable dead
  code (nothing called startService). Now TransferService owns BOTH:
  TransferGraph carries control + data + receive-discovery + send-discovery;
  OutgoingTransfers runs every sendFile on tracked service daemon threads
  (cap 4, per-send cancel flags, shutdown cancels + joins). MainActivity
  binds (startService + BIND_AUTO_CREATE + 15 s watchdog) and builds UI-only
  controllers over the bound coordinators; ViewModel.onCleared detaches via
  release() only — never stops coordinators/graph/service, never cancels.
- ADR-13: release() (detach-only) vs close() (cancel + stop, standalone/
  test use) on both UI controllers. Proven: release() lets in-flight sends
  succeed and pending inbound survive; close() keeps its old semantics.
- ADR-14: service merges outbound rows (file name + progress) into the
  canonical widget snapshot and drives per-transfer notifications from
  outgoing transition feeds (throttled 1/s); terminal outcomes cancel
  exactly that transfer's notification.
- Evidence/limits: 34 M13 JVM+loopback tests green; full suite 246/246;
  assembleDebug green. JVM tests prove ownership/idempotence/isolation/
  cleanup of the graph — they do NOT prove Android OS service behavior
  (process survival, FGS timeouts, Doze). Emulator tests: NOT RUN (no
  Android Studio; not required). Physical-device tests: NOT RUN.
