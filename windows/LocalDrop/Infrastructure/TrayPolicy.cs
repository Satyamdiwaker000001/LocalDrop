// M14 Windows tray: pure policy/model, zero UI-framework references so it
// is unit-testable headless. The WinForms NotifyIcon adapter
// (WindowsTrayIcon) and App wiring interpret these decisions; they create
// no policy of their own.
using LocalDrop.Transfer;

namespace LocalDrop.Infrastructure;

// Menu items, exactly per UI/UX Spec §12 (no invented entries). All items
// except Exit restore the single main window (which already contains Nearby
// Devices, Send, and Active Transfers); no new windows are created.
public enum TrayMenuAction
{
    Open,
    ActiveTransfers,
    Send,
    Exit,
}

public sealed record TrayMenuItem(TrayMenuAction Action, string Label, bool IsDefault);

public static class TrayPolicy
{
    // Canonical menu: ids, labels, and order are frozen here (M01 placeholder
    // named Open/Active-Transfers/Exit; UI/UX §12 adds Nearby Devices/Send).
    public static IReadOnlyList<TrayMenuItem> MenuItems { get; } = new List<TrayMenuItem>
    {
        new(TrayMenuAction.Open, "Open LocalDrop", IsDefault: true),
        new(TrayMenuAction.ActiveTransfers, "Active Transfers", IsDefault: false),
        new(TrayMenuAction.Send, "Nearby Devices / Send", IsDefault: false),
        new(TrayMenuAction.Exit, "Exit", IsDefault: false),
    }.AsReadOnly();

    // Window-close policy: the [X] button and Alt+F4 NEVER exit the app —
    // the window hides to the tray. Only the tray Exit item (after any
    // required confirmation) shuts down. Returns true when the close event
    // must be cancelled and the window hidden instead.
    public static bool ShouldHideToTrayOnClose(bool exitRequested) => !exitRequested;

    // Exit confirmation: leaving with a live send is destructive (the send
    // is cooperatively cancelled on shutdown), so Exit asks first. Idle
    // exit needs no prompt. Real state only — the caller passes the live
    // SendController busy flag.
    public static bool ConfirmExitNeeded(bool sendBusy) => sendBusy;

    public const string ExitConfirmTitle = "Exit LocalDrop";
    public const string ExitConfirmText =
        "A transfer is in progress. Exit anyway? The active transfer will be cancelled.";

    // Real-state-only tray text (UI/UX §12: the indicator may reflect active
    // transfer state but must reflect real state only). Derived from the
    // live send controller; never invented, never stale-cached (callers
    // refresh on every controller state change).
    public static string TooltipText(bool sendBusy, SendPhase phase, string fileName) =>
        sendBusy switch
        {
            true when !string.IsNullOrWhiteSpace(fileName) => $"LocalDrop — Sending {fileName}",
            true => "LocalDrop — Sending…",
            _ => "LocalDrop",
        };

    public static string StatusText(bool sendBusy, SendPhase phase, string fileName) =>
        (sendBusy, phase) switch
        {
            (true, SendPhase.Sending) => string.IsNullOrWhiteSpace(fileName)
                ? "Sending…"
                : $"Sending {fileName}…",
            (true, _) => "Working…",
            (false, SendPhase.Succeeded) => "Last transfer completed.",
            (false, SendPhase.Failed) => "Last transfer failed.",
            (false, SendPhase.Cancelled) => "Last transfer cancelled.",
            _ => "Ready. Nearby devices appear automatically.",
        };
}
