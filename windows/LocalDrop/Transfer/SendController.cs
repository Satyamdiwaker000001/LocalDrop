// M07 UI closure: framework-free send-workflow controller. Holds UI state
// for the WPF send view and drives ITransferSender off the UI thread.
// No sockets, no file I/O, no crypto here: all networking lives in
// TransferCoordinator/SenderEngine; all bytes stay in TransferSource
// streams opened on worker threads. Property notifications are marshalled
// to the captured SynchronizationContext (UI thread in production;
// synchronous in unit tests where there is none).
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using LocalDrop.Core;
using LocalDrop.Discovery;
using LocalDrop.Protocol;

namespace LocalDrop.Transfer;

public enum SendPhase
{
    Idle,
    Sending,
    Succeeded,
    Cancelled,
    Failed,
}

public sealed record PeerItem(
    string DeviceId,
    string DisplayName,
    string DeviceType,
    bool Dialable,
    string Status);

public sealed class SendController : INotifyPropertyChanged, IDisposable
{
    private readonly ITransferSender _sender;
    private readonly Func<IReadOnlyList<DiscoveredPeer>> _peerSnapshots;
    private readonly Func<ITransferSource?> _filePicker;
    private readonly SynchronizationContext? _ui;
    private readonly System.Threading.Timer _peerTimer;
    private readonly object _gate = new();
    private bool _disposed;

    private ITransferSource? _source;
    private CancellationTokenSource? _activeCts;
    private bool _busy;

    public event PropertyChangedEventHandler? PropertyChanged;

    public ObservableCollection<PeerItem> Peers { get; } = new();

    private string? _selectedPeerId;
    public string? SelectedPeerId
    {
        get => _selectedPeerId;
        // XAML binds this two-way; ignore changes while a transfer owns the
        // selection (same guard as SelectPeer).
        set
        {
            if (_busy) return;
            if (SetField(ref _selectedPeerId, value)) { OnStateChanged(); }
        }
    }

    private string _fileName = "";
    public string FileName { get => _fileName; private set => SetField(ref _fileName, value); }

    private string _fileSizeText = "";
    public string FileSizeText { get => _fileSizeText; private set => SetField(ref _fileSizeText, value); }

    private string _code = "";
    public string Code
    {
        get => _code;
        set { if (SetField(ref _code, value)) { OnPropertyChanged(nameof(CanSend)); } }
    }

    private SendPhase _phase = SendPhase.Idle;
    public SendPhase Phase { get => _phase; private set => SetField(ref _phase, value); }

    private double _progressPercent;
    public double ProgressPercent { get => _progressPercent; private set => SetField(ref _progressPercent, value); }

    private string _progressText = "";
    public string ProgressText { get => _progressText; private set => SetField(ref _progressText, value); }

    private string _speedText = "";
    public string SpeedText { get => _speedText; private set => SetField(ref _speedText, value); }

    private string _etaText = "";
    public string EtaText { get => _etaText; private set => SetField(ref _etaText, value); }

    private string _statusText = "Choose a nearby device and a file to send.";
    public string StatusText { get => _statusText; private set => SetField(ref _statusText, value); }

    private string _resultText = "";
    public string ResultText { get => _resultText; private set => SetField(ref _resultText, value); }

    public bool IsBusy => _busy;
    public bool HasFile => _source is not null;

    // Send is allowed only when idle, a listed peer is selected, a file is
    // chosen, and the code normalizes to the M05 6-digit shape. The engine
    // re-validates everything; this gate is UX affordance, not security.
    public bool CanSend =>
        !_busy && _source is not null && _selectedPeerId is not null &&
        Peers.Any(p => p.DeviceId == _selectedPeerId) &&
        Security.AuthUiState.NormalizeInput(_code).Length == AppConfig.TransactionCodeDigits;

    public SendController(
        ITransferSender sender,
        Func<IReadOnlyList<DiscoveredPeer>> peerSnapshots,
        Func<ITransferSource?> filePicker,
        TimeSpan peerPollInterval,
        // UI-thread marshaller. Production passes the UI
        // SynchronizationContext explicitly; null invokes inline (unit
        // tests). Never captured implicitly: ambient test-runner contexts
        // do not pump and would silently drop updates.
        SynchronizationContext? uiContext = null)
    {
        ArgumentNullException.ThrowIfNull(sender);
        ArgumentNullException.ThrowIfNull(peerSnapshots);
        ArgumentNullException.ThrowIfNull(filePicker);
        _sender = sender;
        _peerSnapshots = peerSnapshots;
        _filePicker = filePicker;
        _ui = uiContext;
        _peerTimer = new System.Threading.Timer(
            _ => Post(RefreshPeersNow), null, peerPollInterval, peerPollInterval);
    }

    public void RefreshPeersNow()
    {
        IReadOnlyList<DiscoveredPeer> snapshot;
        try
        {
            snapshot = _peerSnapshots();
        }
        catch
        {
            return; // Discovery hiccup: keep the last list, retry next tick.
        }
        Post(() =>
        {
            if (_disposed) return;
            Peers.Clear();
            foreach (var peer in snapshot)
            {
                bool dialable = PeerEndpointResolver.Resolve(peer) is not null;
                Peers.Add(new PeerItem(
                    peer.Record.DeviceId,
                    peer.Record.DeviceName,
                    peer.Record.DeviceType,
                    dialable,
                    dialable ? "Online" : "Waiting for address…"));
            }
            if (_selectedPeerId is not null && !Peers.Any(p => p.DeviceId == _selectedPeerId))
                StatusText = "The selected device is no longer nearby. Choose another device.";
            OnPropertyChanged(nameof(CanSend));
        });
    }

    public void SelectPeer(string? deviceId)
    {
        if (_busy) return;
        SelectedPeerId = deviceId;
        if (deviceId is null) ResultText = "";
    }

    public void PickFile()
    {
        if (_busy) return;
        ITransferSource? picked;
        try
        {
            picked = _filePicker();
        }
        catch (Exception ex)
        {
            ResultText = $"Could not open that file: {ex.Message}";
            Phase = SendPhase.Failed;
            return;
        }
        if (picked is null) return; // Picker cancelled: keep previous state.
        _source = picked;
        FileName = picked.DisplayName;
        FileSizeText = FormatBytes(picked.SizeBytes);
        ResultText = "";
        if (Phase is SendPhase.Succeeded or SendPhase.Cancelled or SendPhase.Failed)
            Phase = SendPhase.Idle;
        StatusText = "File ready. Enter the 6-digit code shown on the receiver, then Send.";
        OnPropertyChanged(nameof(HasFile));
        OnPropertyChanged(nameof(CanSend));
    }

    public void ClearFile()
    {
        if (_busy) return;
        _source = null;
        FileName = "";
        FileSizeText = "";
        OnPropertyChanged(nameof(HasFile));
        OnPropertyChanged(nameof(CanSend));
    }

    // Starts one transfer. Returns false (with an explanatory ResultText)
    // when the request is not actionable. Duplicate submissions while busy
    // are ignored: exactly one active transfer per controller (M12 owns more).
    public bool StartSend()
    {
        lock (_gate)
        {
            if (_busy) return false;
            if (_source is null || _selectedPeerId is null)
            {
                ResultText = "Select a device and a file first.";
                return false;
            }
            if (Security.AuthUiState.NormalizeInput(_code).Length != AppConfig.TransactionCodeDigits)
            {
                ResultText = "Enter the 6-digit code shown on the receiver.";
                return false;
            }
            if (!Peers.Any(p => p.DeviceId == _selectedPeerId))
            {
                ResultText = "The selected device is no longer nearby. Choose another device.";
                return false;
            }
            _busy = true;
            _activeCts = new CancellationTokenSource();
        }
        string peerId = _selectedPeerId;
        ITransferSource source = _source;
        string code = _code;
        CancellationToken ct = _activeCts.Token;
        Phase = SendPhase.Sending;
        ResultText = "";
        ProgressPercent = 0;
        ProgressText = "";
        SpeedText = "";
        EtaText = "";
        StatusText = "Connecting…";
        OnPropertyChanged(nameof(IsBusy));
        OnPropertyChanged(nameof(CanSend));
        _ = Task.Run(async () =>
        {
            TransferOutcome outcome;
            try
            {
                outcome = await _sender.SendFileAsync(peerId, source, code, ct, OnEngineProgress);
            }
            catch (Exception ex)
            {
                // Coordinators never throw on peer/network failure by
                // contract; this guards purely against programming errors so
                // the UI can never hang in Sending.
                outcome = new TransferOutcome.Failed(null, ErrorCode.INTERNAL_ERROR,
                    $"Unexpected error: {ex.Message}");
            }
            Post(() => ApplyOutcome(peerId, source, outcome));
        });
        return true;
    }

    public void CancelSend()
    {
        CancellationTokenSource? cts;
        lock (_gate) cts = _activeCts;
        try
        {
            cts?.Cancel();
            Post(() =>
            {
                if (_busy) StatusText = "Cancelling…";
            });
        }
        catch { }
    }

    private void OnEngineProgress(TransferProgress p)
    {
        // Engine worker thread: marshal to UI. These snapshots ARE the
        // displayed progress (no simulation, no smoothing inventions).
        Post(() =>
        {
            if (_disposed) return;
            ProgressPercent = p.Percent;
            ProgressText = $"{FormatBytes(p.TransferredBytes)} of {FormatBytes(p.TotalBytes)} ({p.Percent:F1}%)";
            SpeedText = p.RecentBytesPerSec is > 0 ? $"{FormatSpeed(p.RecentBytesPerSec.Value)}" : "—";
            EtaText = p.EtaMs is not null ? $"ETA {FormatDuration(p.EtaMs.Value)}" : "ETA —";
            StatusText = HumanState(p.State);
        });
    }

    private void ApplyOutcome(string peerId, ITransferSource source, TransferOutcome outcome)
    {
        if (_disposed) return;
        string peerName = Peers.FirstOrDefault(p => p.DeviceId == peerId)?.DisplayName ?? "device";
        switch (outcome)
        {
            case TransferOutcome.TransportComplete done:
                Phase = SendPhase.Succeeded;
                ProgressPercent = 100;
                string avg = done.AverageBytesPerSec is > 0
                    ? $" at {FormatSpeed(done.AverageBytesPerSec.Value)}" : "";
                ResultText = $"Completed — {source.DisplayName} sent to {peerName} " +
                    $"({FormatBytes(done.BytesTransferred)}{avg}).";
                StatusText = "Completed.";
                break;
            case TransferOutcome.Cancelled:
                Phase = SendPhase.Cancelled;
                ResultText = "Cancelled. No partial file was left on the receiver.";
                StatusText = "Cancelled.";
                break;
            case TransferOutcome.Failed failed:
                Phase = SendPhase.Failed;
                ResultText = FriendlyError(failed.Code, failed.Detail);
                StatusText = "Failed.";
                break;
        }
        lock (_gate)
        {
            _busy = false;
            _activeCts?.Dispose();
            _activeCts = null;
        }
        OnPropertyChanged(nameof(IsBusy));
        OnPropertyChanged(nameof(CanSend));
    }

    private void OnStateChanged()
    {
        if (Phase is SendPhase.Succeeded or SendPhase.Cancelled or SendPhase.Failed)
            Phase = SendPhase.Idle;
        if (ResultText.StartsWith("The selected device", StringComparison.Ordinal))
            ResultText = "";
        StatusText = DefaultStatus();
        OnPropertyChanged(nameof(CanSend));
    }

    private string DefaultStatus()
    {
        if (_source is null && _selectedPeerId is null)
            return "Choose a nearby device and a file to send.";
        if (_source is null) return "Now choose a file to send.";
        if (_selectedPeerId is null) return "Now choose a nearby device.";
        return "File ready. Enter the 6-digit code shown on the receiver, then Send.";
    }

    private void Post(Action action)
    {
        var ui = _ui;
        if (ui is null) action();
        else ui.Post(_ => action(), null);
    }

    private bool SetField<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        OnPropertyChanged(name);
        return true;
    }

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            try { _peerTimer.Dispose(); } catch { }
            try { _activeCts?.Cancel(); } catch { }
            try { _activeCts?.Dispose(); } catch { }
            _activeCts = null;
            _busy = false;
        }
    }

    // ---- Pure presentation helpers (unit-tested, no I/O) ----

    public static string FormatBytes(long bytes)
    {
        if (bytes < 0) return "—";
        const double k = 1024.0;
        if (bytes < k) return $"{bytes} B";
        if (bytes < k * k) return $"{bytes / k:F1} KB";
        if (bytes < k * k * k) return $"{bytes / (k * k):F1} MB";
        return $"{bytes / (k * k * k):F2} GB";
    }

    public static string FormatSpeed(double bytesPerSec)
    {
        if (!(bytesPerSec > 0) || double.IsNaN(bytesPerSec) || double.IsInfinity(bytesPerSec))
            return "—";
        return $"{FormatBytes((long)bytesPerSec)}/s";
    }

    public static string FormatDuration(long millis)
    {
        if (millis < 0) return "—";
        long totalSeconds = millis / 1000;
        if (totalSeconds < 60) return $"00:{totalSeconds:D2}";
        long minutes = totalSeconds / 60;
        if (minutes < 60) return $"{minutes:D2}:{totalSeconds % 60:D2}";
        return $"{minutes / 60}h {minutes % 60:D2}m";
    }

    public static string HumanState(TransferEngineState state) => state switch
    {
        TransferEngineState.CONNECTING => "Connecting…",
        TransferEngineState.WAITING_ACCEPTANCE => "Waiting for the receiver…",
        TransferEngineState.TRANSFERRING => "Sending…",
        TransferEngineState.TRANSPORT_COMPLETE => "Finalizing…",
        TransferEngineState.FAILED => "Failed.",
        TransferEngineState.CANCELLED => "Cancelling…",
        _ => state.ToString(),
    };

    // Actionable, secret-free error text. Engine details are display-safe
    // (frozen M02 error vocabulary carries no keys/codes/tokens).
    public static string FriendlyError(ErrorCode code, string detail) => code switch
    {
        ErrorCode.TRANSFER_REJECTED => $"Receiver rejected the transfer. {detail} You can try again.",
        ErrorCode.AUTH_FAILED => "Authentication with the receiver failed. Make sure you selected the right device and try again.",
        ErrorCode.PAIRING_REQUIRED => "Authentication is required before sending. Try again.",
        ErrorCode.TIMEOUT => $"Timed out. {detail} Check that both devices stay on the hotspot and try again.",
        ErrorCode.NETWORK_LOST => $"Connection lost. {detail} Stay on the local Wi-Fi and try again. Never switch to the Internet for this app.",
        ErrorCode.NOT_ENOUGH_STORAGE => "The receiver is out of storage. Free space on the receiver and try again.",
        ErrorCode.FILE_NOT_FOUND => $"The file is no longer available. {detail}",
        ErrorCode.CANCELLED => "Cancelled.",
        ErrorCode.INVALID_PROTOCOL => $"Protocol error. {detail} Both devices may need the same app version.",
        ErrorCode.CHECKSUM_MISMATCH => "Integrity check failed. The file must not be treated as received.",
        ErrorCode.RESUME_UNAVAILABLE => "Resume is not available in this version; the transfer stops.",
        _ => $"Transfer failed. {detail}",
    };
}
