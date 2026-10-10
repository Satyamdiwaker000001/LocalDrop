// M03: LAN-only UDP broadcast discovery (Complete Spec §5.1).
// One wire protocol shared with Android: each announcement datagram is the
// UTF-8 canonical JSON of an M02 HELLO envelope (same bytes the control
// channel will carry in M04; datagram boundaries replace framing here).
// Independent of WPF: no MainWindow/socket coupling; async with
// cancellation, no UI-thread blocking.
// Lifecycle: Start() / StopAsync(), both idempotent. No background service
// beyond discovery's own tasks (M13 owns the transfer service), no TCP,
// no pairing, no file access.
using System.Net;
using System.Net.Sockets;
using LocalDrop.Core;
using LocalDrop.Protocol;

namespace LocalDrop.Discovery;

public sealed class LanDiscoveryService : IDisposable
{
    private readonly DeviceInfo _local;
    private readonly int _port;
    private readonly long _announceIntervalMs;
    private readonly IPAddress _broadcastAddress;
    private readonly int _announcePort;
    private readonly IDiscoveryClock _clock;
    private readonly long _peerTimeoutMs;
    private readonly long _controlPort;

    private readonly object _gate = new();
    private bool _running;
    private bool _disposed;
    private CancellationTokenSource? _cts;
    private UdpClient? _listener;
    private UdpClient? _announcer;
    private Task? _listenTask;
    private Task? _announceTask;

    public PeerRegistry Registry { get; }

    public LanDiscoveryService(
        DeviceInfo local,
        int port = AppConfig.DiscoveryUdpPort,
        long announceIntervalMs = AppConfig.DiscoveryAnnounceIntervalMs,
        IPAddress? broadcastAddress = null,
        IDiscoveryClock? clock = null,
        long peerTimeoutMs = AppConfig.DiscoveryPeerTimeoutMs,
        // Target port for announcements. Defaults to the listen port
        // (production: symmetric). Tests cross-target two instances over
        // loopback.
        int? announcePort = null,
        // Control-plane TCP port advertised in announcements (M02 HELLO
        // controlPort, range 0..65535). Default 0 preserves the M03
        // announcement shape; production wiring passes
        // AppConfig.ControlTcpPort so dialers learn the real endpoint
        // (verification audit F4 — no manual IP/port).
        long controlPort = 0)
    {
        local.Validate();
        _local = local;
        _port = port;
        _announceIntervalMs = announceIntervalMs;
        _broadcastAddress = broadcastAddress ?? IPAddress.Broadcast;
        _clock = clock ?? SystemDiscoveryClock.Instance;
        _peerTimeoutMs = peerTimeoutMs;
        _announcePort = announcePort ?? port;
        _controlPort = controlPort;
        Registry = new PeerRegistry(local.DeviceId, _clock, peerTimeoutMs);
    }

    // Idempotent: a second Start() while running is a no-op (no duplicate
    // listeners, no duplicate announcement loops).
    public void Start()
    {
        lock (_gate)
        {
            if (_disposed) throw new ObjectDisposedException(nameof(LanDiscoveryService));
            if (_running) return;
            var listener = new UdpClient();
            listener.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            listener.Client.Bind(new IPEndPoint(IPAddress.Any, _port));
            var announcer = new UdpClient { EnableBroadcast = true };
            var cts = new CancellationTokenSource();
            _listener = listener;
            _announcer = announcer;
            _cts = cts;
            _running = true;
            _listenTask = Task.Run(() => ListenLoopAsync(listener, cts.Token));
            _announceTask = Task.Run(() => AnnounceLoopAsync(announcer, cts.Token));
        }
    }

    // Idempotent. Cancels loops, closes sockets (unblocks the receiver
    // immediately), awaits tasks with a timeout. No socket/task leaks;
    // restart via Start() rebinds cleanly.
    public async Task StopAsync()
    {
        Task? listen, announce;
        lock (_gate)
        {
            if (!_running) return;
            _running = false;
            try { _cts?.Cancel(); } catch { /* best effort */ }
            try { _listener?.Close(); } catch { /* best effort */ }
            try { _announcer?.Close(); } catch { /* best effort */ }
            listen = _listenTask;
            announce = _announceTask;
            _listenTask = null;
            _announceTask = null;
        }
        var tasks = new[] { listen, announce }.Where(t => t is not null).Cast<Task>().ToArray();
        if (tasks.Length > 0)
        {
            try { await Task.WhenAll(tasks).WaitAsync(TimeSpan.FromSeconds(5)); }
            catch { /* shutdown is best effort; loops already unblocked */ }
        }
        lock (_gate)
        {
            _listener?.Dispose();
            _announcer?.Dispose();
            _cts?.Dispose();
            _listener = null;
            _announcer = null;
            _cts = null;
        }
    }

    public bool IsRunning
    {
        get { lock (_gate) return _running; }
    }

    // Bound UDP port (useful when constructed with port 0 in tests).
    public int LocalPort
    {
        get
        {
            lock (_gate)
            {
                if (_listener?.Client.LocalEndPoint is IPEndPoint ep) return ep.Port;
                return -1;
            }
        }
    }

    // Validated nearby peers (expired pruned). UI observes this, never UDP.
    public List<DiscoveredPeer> Snapshot() => Registry.Snapshot();

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
        }
        // Synchronous dispose path: unblock everything, then release.
        try { _cts?.Cancel(); } catch { }
        try { _listener?.Close(); } catch { }
        try { _announcer?.Close(); } catch { }
        try
        {
            var tasks = new[] { _listenTask, _announceTask }
                .Where(t => t is not null).Cast<Task>().ToArray();
            if (tasks.Length > 0) Task.WhenAll(tasks).Wait(TimeSpan.FromSeconds(5));
        }
        catch { }
        _listener?.Dispose();
        _announcer?.Dispose();
        _cts?.Dispose();
    }

    private async Task ListenLoopAsync(UdpClient listener, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try
            {
                result = await listener.ReceiveAsync();
            }
            catch (SocketException)
            {
                // Socket closed by StopAsync/Dispose: exit quietly.
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (Exception)
            {
                // Malformed LAN traffic must not kill discovery.
                if (ct.IsCancellationRequested) return;
                continue;
            }
            // Registry bounds-checks length and validates content; oversized
            // or malformed datagrams map to Ignored, never throw. The UDP
            // source address is stored as the dial hint (audit F4).
            string? senderHost = result.RemoteEndPoint is IPEndPoint ep
                ? ep.Address.ToString() : null;
            Registry.HandleDatagram(result.Buffer, result.Buffer.Length, senderHost);
            if (ct.IsCancellationRequested) return;
        }
    }

    private async Task AnnounceLoopAsync(UdpClient announcer, CancellationToken ct)
    {
        var target = new IPEndPoint(_broadcastAddress, _announcePort);
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var bytes = BuildAnnouncement(_local, _controlPort);
                await announcer.SendAsync(bytes, target, ct);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception)
            {
                // Send failures (e.g. transient interface loss) must not kill
                // the loop; the next interval retries.
                if (ct.IsCancellationRequested) return;
            }
            try
            {
                await Task.Delay(TimeSpan.FromMilliseconds(_announceIntervalMs), ct);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    // One announcement = one HELLO envelope, fresh messageId per send.
    // controlPort 0 = unassigned (M03 default); callers pass the live M04
    // control port so peers can dial without manual entry (F4). The value is
    // M02-legal (0..65535): no wire change.
    public static byte[] BuildAnnouncement(DeviceInfo local, long controlPort = 0)
    {
        local.Validate();
        var message = new LocalDropMessage(
            MessageType.HELLO,
            ProtocolCodec.NewMessageId(),
            null, null, null,
            new TypedPayload.Hello(new HelloPayload(
                local.DeviceId, local.DeviceName, local.DeviceType, controlPort,
                [.. local.SafeCapabilities])));
        return ProtocolCodec.EncodeUtf8(message);
    }
}
