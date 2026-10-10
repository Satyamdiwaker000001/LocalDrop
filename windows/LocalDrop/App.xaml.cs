// M07 UI closure: application root. Builds the production send stack once
// (identity, discovery, coordinator, send controller) on the UI thread and
// tears it down on exit. No transfer logic here: MainWindow binds to
// SendController, which drives the existing TransferCoordinator off the UI
// thread. Sockets/threads are owned by discovery/coordinator internals.
using System.Windows;
using LocalDrop.Core;
using LocalDrop.Discovery;
using LocalDrop.Infrastructure;
using LocalDrop.Presentation;
using LocalDrop.Security;
using LocalDrop.Transfer;

namespace LocalDrop;

public partial class App : System.Windows.Application
{
    public SendController Send { get; private set; } = null!;
    public string LocalDeviceName { get; private set; } = "";
    private IDeviceIdentityStore? _identityStore;
    private LanDiscoveryService? _discovery;
    private MainWindow? _window;
    private WindowsTrayIcon? _tray;
    // Set ONLY by the tray Exit path (after any busy confirmation). The
    // window [X]/Alt+F4 path never sets it: close always hides to tray.
    private bool _exitRequested;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        try
        {
            StartLocalDrop();
        }
        catch (Exception ex)
        {
            // Never start half-wired (e.g. identity store unavailable):
            // explain and exit instead of running a dead UI.
            MessageBox.Show(
                $"LocalDrop could not start its local services.{Environment.NewLine}{ex.Message}",
                "LocalDrop", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
            return;
        }
        var window = new MainWindow { DataContext = Send };
        _window = window;
        window.StateChanged += OnWindowStateChanged;
        window.Closing += OnWindowClosing;
        Send.PropertyChanged += OnSendStateChanged;
        _tray = new WindowsTrayIcon(OnTrayMenu);
        _tray.Show();
        RefreshTrayText();
        window.Show();
    }

    // Minimize button docks to the tray instead of the taskbar. The window
    // is hidden (never closed), so live transfers keep running untouched.
    private void OnWindowStateChanged(object? sender, EventArgs e)
    {
        try
        {
            if (_window is not null && _window.WindowState == WindowState.Minimized)
                HideToTray();
        }
        catch { }
    }

    // [X]/Alt+F4 hides to the tray (TrayPolicy); only the tray Exit item
    // (with _exitRequested set) is allowed to actually close the window and
    // shut the application down.
    private void OnWindowClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        try
        {
            if (TrayPolicy.ShouldHideToTrayOnClose(_exitRequested))
            {
                e.Cancel = true;
                HideToTray();
            }
        }
        catch { }
    }

    private void HideToTray()
    {
        if (_window is null) return;
        _window.Hide();
        _window.ShowInTaskbar = false;
    }

    private void RestoreWindow()
    {
        if (_window is null) return;
        _window.Show();
        _window.WindowState = WindowState.Normal;
        _window.ShowInTaskbar = true;
        try { _window.Activate(); } catch { }
    }

    private void OnTrayMenu(TrayMenuAction action)
    {
        switch (action)
        {
            case TrayMenuAction.Open:
            case TrayMenuAction.ActiveTransfers:
            case TrayMenuAction.Send:
                // Single-window app: every open action restores the main
                // window (which already contains Nearby Devices, Send, and
                // Active Transfers). No new windows are invented.
                Dispatcher.Invoke(RestoreWindow);
                break;
            case TrayMenuAction.Exit:
                Dispatcher.Invoke(RequestExit);
                break;
        }
    }

    // The ONLY user-visible exit path. Busy (live send) exits confirm first
    // because shutdown cooperatively cancels the transfer; idle exits do
    // not prompt. Follows the documented shutdown policy in OnExit.
    private void RequestExit()
    {
        try
        {
            if (Send is not null && TrayPolicy.ConfirmExitNeeded(Send.IsBusy))
            {
                var answer = MessageBox.Show(
                    TrayPolicy.ExitConfirmText, TrayPolicy.ExitConfirmTitle,
                    MessageBoxButton.YesNo, MessageBoxImage.Warning);
                if (answer != MessageBoxResult.Yes) return;
            }
        }
        catch { }
        _exitRequested = true;
        Shutdown();
    }

    private void OnSendStateChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(SendController.IsBusy)
            or nameof(SendController.Phase)
            or nameof(SendController.FileName))
        {
            RefreshTrayText();
        }
    }

    private void RefreshTrayText()
    {
        try
        {
            _tray?.UpdateText(Send is null
                ? "LocalDrop"
                : TrayPolicy.TooltipText(Send.IsBusy, Send.Phase, Send.FileName));
        }
        catch { }
    }

    private void StartLocalDrop()
    {
        var securityLogger = SecurityLoggers.CreateDefault();
        _identityStore = new CngDeviceIdentityStore(AppConfig.DeviceIdentityKeyName);
        string deviceId = _identityStore.Identity().DeviceId;
        // MachineName is short in practice; clamp defensively to the M02 bound.
        string machine = Environment.MachineName;
        LocalDeviceName = string.IsNullOrWhiteSpace(machine) ? "Windows PC" : machine;
        if (LocalDeviceName.Length > Protocol.Protocol.MaxDeviceName)
            LocalDeviceName = LocalDeviceName[..Protocol.Protocol.MaxDeviceName];
        var local = new DeviceInfo(deviceId, LocalDeviceName, "windows");
        _discovery = new LanDiscoveryService(local, controlPort: AppConfig.ControlTcpPort);
        _discovery.Start();
        var coordinator = new TransferCoordinator(local, _identityStore, securityLogger, _discovery);
        Send = new SendController(
            coordinator,
            coordinator.NearbyPeers,
            PickFileWithNativeDialog,
            TimeSpan.FromSeconds(2),
            SynchronizationContext.Current);
        Send.RefreshPeersNow();
    }

    // Native file picker (view-adjacent mechanics only): returns a source for
    // the engine to stream on a worker thread, or null when cancelled.
    private static ITransferSource? PickFileWithNativeDialog()
    {
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Choose a file to send with LocalDrop",
            CheckFileExists = true,
            Multiselect = false,
        };
        bool? chosen;
        try
        {
            chosen = dialog.ShowDialog();
        }
        catch
        {
            return null;
        }
        if (chosen != true || string.IsNullOrWhiteSpace(dialog.FileName)) return null;
        try
        {
            return new FileTransferSource(dialog.FileName);
        }
        catch
        {
            return null;
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        // Teardown order is deliberate: tray icon FIRST (Visible=false so no
        // ghost icon lingers; handlers detached so nothing fires mid-exit),
        // then the send stack (Send.Dispose cooperatively cancels a live
        // send), then discovery sockets, then identity. Every Dispose/Stop
        // is idempotent, so shutdown is effectively once. Fail-fast startup
        // may exit before these exist: each is null-guarded, never NRE.
        try
        {
            if (Send is not null)
                Send.PropertyChanged -= OnSendStateChanged;
        }
        catch { }
        try
        {
            if (_window is not null)
            {
                _window.StateChanged -= OnWindowStateChanged;
                _window.Closing -= OnWindowClosing;
            }
        }
        catch { }
        try { _tray?.Dispose(); } catch { }
        _tray = null;
        // Fail-fast startup may exit before Send exists: clean up each owned
        // resource explicitly (never rely on throwing/catching NRE). Every
        // Dispose/Stop below is idempotent, so shutdown is effectively once.
        try { if (Send is not null) Send.Dispose(); } catch { }
        try
        {
            // Best-effort graceful discovery shutdown off the UI thread.
            if (_discovery is not null)
                Task.Run(async () => await _discovery.StopAsync()).Wait(TimeSpan.FromSeconds(3));
        }
        catch { }
        try { (_identityStore as IDisposable)?.Dispose(); } catch { }
        base.OnExit(e);
    }
}
