// M04: inbound TCP control listener (one per application instance).
// Accepts clients and hands each to an independent ControlConnection, so one
// failed peer never affects unrelated peers (M04 §16). Owns no business
// logic; the listener factory wires per-connection events.
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using LocalDrop.Core;
using LocalDrop.Discovery;

namespace LocalDrop.Network;

public sealed class ControlServer : IDisposable
{
    private readonly DeviceInfo _local;
    private readonly int _port;
    private readonly int _handshakeTimeoutMs;
    private readonly Func<IControlEvents?>? _listenerFactory;
    private readonly object _gate = new();
    private bool _running;
    private bool _disposed;
    private TcpListener? _listener;
    private Task? _acceptTask;
    private CancellationTokenSource? _cts;

    public ControlConnections Connections { get; } = new();

    public ControlServer(
        DeviceInfo local,
        int port = AppConfig.ControlTcpPort,
        int handshakeTimeoutMs = AppConfig.ControlHandshakeTimeoutMs,
        Func<IControlEvents?>? listenerFactory = null)
    {
        local.Validate();
        _local = local;
        _port = port;
        _handshakeTimeoutMs = handshakeTimeoutMs;
        _listenerFactory = listenerFactory;
    }

    // Idempotent start. Rebinds cleanly after Stop (reuseAddress set).
    public void Start()
    {
        lock (_gate)
        {
            if (_disposed) throw new ObjectDisposedException(nameof(ControlServer));
            if (_running) return;
            var listener = new TcpListener(IPAddress.Any, _port);
            listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            listener.Start();
            var cts = new CancellationTokenSource();
            _listener = listener;
            _cts = cts;
            _running = true;
            _acceptTask = Task.Run(() => AcceptLoopAsync(listener, cts.Token));
        }
    }

    // Idempotent stop: halts accepts, disconnects tracked connections,
    // releases everything. Tracked entries removed as they close.
    public async Task StopAsync()
    {
        Task? accept;
        lock (_gate)
        {
            if (!_running) return;
            _running = false;
            try { _cts?.Cancel(); } catch { }
            try { _listener?.Stop(); } catch { }
            accept = _acceptTask;
            _acceptTask = null;
        }
        if (accept is not null)
        {
            try { await accept.WaitAsync(TimeSpan.FromSeconds(5)); } catch { }
        }
        await Connections.CloseAllAsync();
        lock (_gate)
        {
            _listener = null;
            _cts?.Dispose();
            _cts = null;
        }
    }

    public bool IsRunning
    {
        get { lock (_gate) return _running; }
    }

    public int LocalPort
    {
        get
        {
            lock (_gate)
            {
                if (_listener?.LocalEndpoint is IPEndPoint ep) return ep.Port;
                return -1;
            }
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
        }
        try { _cts?.Cancel(); } catch { }
        try { _listener?.Stop(); } catch { }
        try
        {
            var accept = _acceptTask;
            if (accept is not null) accept.Wait(TimeSpan.FromSeconds(5));
        }
        catch { }
        try
        {
            Connections.CloseAllAsync().GetAwaiter().GetResult();
        }
        catch { }
        _cts?.Dispose();
    }

    private async Task AcceptLoopAsync(TcpListener listener, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(ct);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (SocketException)
            {
                return; // stopped
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (Exception)
            {
                if (ct.IsCancellationRequested) return;
                continue;
            }
            _ = Task.Run(async () =>
            {
                var userEvents = _listenerFactory?.Invoke();
                var tracking = new TrackingEvents(Connections, userEvents);
                var connection = new ControlConnection(_local, tracking, handshakeTimeoutMs: _handshakeTimeoutMs);
                Connections.Add(connection.Id, connection);
                bool ready;
                try
                {
                    ready = await connection.AcceptAsync(client, ct);
                }
                catch
                {
                    ready = false;
                }
                if (!ready) Connections.Remove(connection.Id);
            }, ct);
        }
    }

    private sealed class TrackingEvents(ControlConnections connections, IControlEvents? inner) : IControlEvents
    {
        public void OnMessage(ControlConnection connection, Protocol.LocalDropMessage message)
        {
            inner?.OnMessage(connection, message);
        }

        public void OnClosed(ControlConnection connection, CloseReason reason, string detail)
        {
            try
            {
                inner?.OnClosed(connection, reason, detail);
            }
            finally
            {
                connections.Remove(connection.Id);
            }
        }
    }
}
