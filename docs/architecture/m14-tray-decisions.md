# M14 — Windows System Tray Integration: Architecture Decisions

Authoritative basis: Master Spec v1.2 milestone table ("M14 Windows Tray:
System tray and quick actions") + UI/UX Spec v1.0 §12 (tray icon mandatory;
menu Open LocalDrop / Active Transfers / Nearby Devices-Send / Exit; tray
indicator may reflect active transfer state but real state only).

## Ownership and lifecycle
- ADR-1: The App object owns exactly one transfer stack (existing
  StartLocalDrop: identity, discovery, coordinator, SendController) and one
  tray icon. The tray creates NO engines, NO discovery, NO control workers
  (verified by grep: the only `new LanDiscoveryService` /
  `new TransferCoordinator` are the pre-existing startup lines).
- ADR-2: `ShutdownMode="OnExplicitShutdown"`. Window [X]/Alt+F4/minimize
  hides to the tray (window.Hide + ShowInTaskbar=false); transfers keep
  running untouched. Only the tray Exit item sets _exitRequested and calls
  Shutdown(). No second source of truth, no hidden background processes.
- ADR-3: Teardown order in OnExit is tray-first (Visible=false so no ghost
  icon; menu disposed; window/send events detached), then Send.Dispose
  (cooperatively cancels a live send), then discovery StopAsync (3 s
  bound), then identity dispose. All idempotent; fail-fast startup path
  stays null-guarded.
- ADR-4: Exit with a live send confirms first (Yes/No MessageBox naming
  cancellation explicitly); idle exit does not prompt. Close/minimize never
  prompts (they cancel nothing).

## Testability split
- ADR-5: All policy lives in framework-free `TrayPolicy` (menu model,
  close-vs-exit, confirm matrix, real-state-only tooltip/status text),
  linked into the headless xUnit suite (16 tests). The WinForms
  `NotifyIcon` adapter is deliberately thin and production-only: it needs
  the UI thread + interactive desktop, so it is NOT unit-tested here.
- ADR-6: `UseWindowsForms=true` (in-box framework, no new packages,
  offline restore verified) with the implicit `System.Windows.Forms`
  using REMOVED project-wide: its CloseReason collided with
  `LocalDrop.Network.CloseReason`. Only WindowsTrayIcon.cs references
  WinForms explicitly; no transfer/security file touched.

## Status text honesty
- ADR-7: Tooltip/status derive from the live SendController
  (IsBusy/Phase/FileName) on every state change; idle shows plain
  "LocalDrop". Note: the Windows UI currently wires the SEND stack only
  (no receive UI exists on Windows yet) — "Active Transfers" restores the
  main window, which shows send progress. No fake receive state invented.

## Evidence status
- Build + 276/276 xUnit green (see log). Interactive tray behavior
  (icon visibility, menu clicks, minimize/restore, confirm dialog):
  NOT RUN in this headless environment; not claimed.
