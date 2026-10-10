// M14 Windows tray icon adapter (production only — needs the UI thread and
// an interactive desktop; never linked into headless unit tests). Thin
// WinForms NotifyIcon wrapper: all decisions (menu model, close/exit
// semantics, text) come from TrayPolicy. Creates no engines, no discovery,
// no workers: callbacks only restore the existing window or request exit.
using System.Drawing;
using System.Windows.Forms;

namespace LocalDrop.Infrastructure;

public sealed class WindowsTrayIcon : IDisposable
{
    private readonly NotifyIcon _icon;
    private readonly Action<TrayMenuAction> _onMenu;
    private readonly EventHandler _doubleClickHandler;
    private bool _disposed;

    public WindowsTrayIcon(Action<TrayMenuAction> onMenu)
    {
        ArgumentNullException.ThrowIfNull(onMenu);
        _onMenu = onMenu;
        _icon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "LocalDrop",
            Visible = false,
        };
        var menu = new ContextMenuStrip();
        foreach (var item in TrayPolicy.MenuItems)
        {
            var captured = item;
            if (captured.Action == TrayMenuAction.Exit && menu.Items.Count > 0)
                menu.Items.Add(new ToolStripSeparator());
            var entry = new ToolStripMenuItem(captured.Label);
            entry.Click += (_, _) => { try { _onMenu(captured.Action); } catch { } };
            menu.Items.Add(entry);
        }
        _icon.ContextMenuStrip = menu;
        _doubleClickHandler = (_, _) => { try { _onMenu(TrayMenuAction.Open); } catch { } };
        _icon.DoubleClick += _doubleClickHandler;
    }

    // Called on the UI thread after the main window exists.
    public void Show()
    {
        if (_disposed) return;
        _icon.Visible = true;
    }

    public void UpdateText(string tooltip)
    {
        if (_disposed) return;
        // NotifyIcon.Text caps at 63 chars; file names are bounded by M02
        // validation, tooltip text by TrayPolicy — truncate defensively.
        string text = string.IsNullOrWhiteSpace(tooltip) ? "LocalDrop" : tooltip;
        if (text.Length > 63) text = text[..63];
        try { _icon.Text = text; } catch { }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        // Remove the icon BEFORE disposing so no ghost icon lingers in the
        // tray after exit. Detach the menu (its Click closures reference
        // this adapter) so nothing fires during teardown.
        try { _icon.Visible = false; } catch { }
        try { _icon.DoubleClick -= _doubleClickHandler; } catch { }
        try
        {
            var menu = _icon.ContextMenuStrip;
            _icon.ContextMenuStrip = null;
            menu?.Dispose();
        }
        catch { }
        try { _icon.Dispose(); } catch { }
    }
}
