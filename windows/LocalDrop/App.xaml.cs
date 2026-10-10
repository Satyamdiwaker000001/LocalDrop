// M07 UI closure: application root. Builds the production send stack once
// (identity, discovery, coordinator, send controller) on the UI thread and
// tears it down on exit. No transfer logic here: MainWindow binds to
// SendController, which drives the existing TransferCoordinator off the UI
// thread. Sockets/threads are owned by discovery/coordinator internals.
using System.Windows;
using LocalDrop.Core;
using LocalDrop.Discovery;
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
        window.Show();
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
