// M04: one direct-TCP control connection to one peer (Complete Spec §5.2,
// PROTOCOL v1.0 §2). Transports frozen M02 frames over TCP:
//
//   M02 Message -> M02 serialization -> M02 frame -> TCP
//
// and back through the M02 incremental decoder (partial reads, coalesced
// frames handled; never assumes one read == one message).
// Out of scope (later milestones): file transfer (M06+), pairing/auth (M05).
// Fully async with cancellation; no WPF coupling.
using System.Collections.Concurrent;
using System.IO;
using System.Net.Sockets;
using LocalDrop.Core;
using LocalDrop.Discovery;
using LocalDrop.Protocol;

namespace LocalDrop.Network;

public sealed class ControlConnection
{
    private readonly DeviceInfo _local;
    private readonly IControlEvents? _events;
    private readonly int _connectTimeoutMs;
    private readonly int _handshakeTimeoutMs;
    private readonly object _gate = new();
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly FrameCodec.Decoder _decoder = new();
    private readonly ConcurrentDictionary<string, TaskCompletionSource<LocalDropMessage>> _pending = new();
    private int _closeNotified; // 0/1 via Interlocked

    private TcpClient? _client;
    private NetworkStream? _stream;
    private Task? _readTask;
    private CancellationTokenSource? _loopCts;
    private volatile bool _shutdownInitiated;

    // Handshake bookkeeping, reset per connect/accept cycle.
    private string? _myHelloId;
    private bool _sawHello;
    private bool _sawAck;
    private TaskCompletionSource<bool>? _handshakeTcs;

    public ControlState State { get; private set; } = ControlState.DISCONNECTED;

    // Stable per-instance key for ControlConnections tracking.
    public string Id { get; } = Guid.NewGuid().ToString("D");

    // Peer's validated HELLO payload once received (null before handshake).
    // Identity for display/correlation only: NOT trust (M05 owns trust).
    public HelloPayload? PeerHello { get; private set; }

    public string? PeerDeviceId => PeerHello?.DeviceId;
    public string RemoteAddress => _client?.Client?.RemoteEndPoint?.ToString() ?? "(none)";

    public ControlConnection(
        DeviceInfo local,
        IControlEvents? events = null,
        int connectTimeoutMs = AppConfig.ControlConnectTimeoutMs,
        int handshakeTimeoutMs = AppConfig.ControlHandshakeTimeoutMs)
    {
        local.Validate();
        _local = local;
        _events = events;
        _connectTimeoutMs = connectTimeoutMs;
        _handshakeTimeoutMs = handshakeTimeoutMs;
    }

    // Outbound dial. Bounded by the connect + handshake timeouts.
    // Returns true iff the channel reached READY.
    public async Task<bool> ConnectAsync(string host, int port, CancellationToken ct = default)
    {
        lock (_gate)
        {
            if (State != ControlState.DISCONNECTED && State != ControlState.FAILED)
                return State == ControlState.READY;
            ResetCycleLocked();
            State = ControlState.CONNECTING;
        }
        var client = new TcpClient();
        try
        {
            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeoutCts.CancelAfter(_connectTimeoutMs);
            await client.ConnectAsync(host, port, timeoutCts.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            client.Dispose();
            return Fail(CloseReason.CONNECT_FAILED, "connect timed out");
        }
        catch (Exception ex)
        {
            client.Dispose();
            return Fail(CloseReason.CONNECT_FAILED, $"connect failed: {ex.Message}");
        }
        return await BeginOnClientAsync(client, ct);
    }

    // Inbound side, used by ControlServer for an accepted client. Bounded by
    // the handshake timeout. Returns true iff READY.
    public async Task<bool> AcceptAsync(TcpClient inbound, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(inbound);
        lock (_gate)
        {
            if (State != ControlState.DISCONNECTED && State != ControlState.FAILED)
                return State == ControlState.READY;
            ResetCycleLocked();
            State = ControlState.NEGOTIATING;
        }
        return await BeginOnClientAsync(inbound, ct);
    }

    // Sends one M02 message as a single complete frame. Concurrent senders
    // serialize on a semaphore so frame bytes can never interleave.
    // Returns false if the channel is not usable. No unbounded queue.
    public async Task<bool> SendAsync(LocalDropMessage message, CancellationToken ct = default)
    {
        var stream = _stream;
        if (stream is null) return false;
        byte[] frame;
        try
        {
            frame = FrameCodec.EncodeMessage(message);
        }
        catch (ArgumentException)
        {
            return false;
        }
        await _sendLock.WaitAsync(ct);
        try
        {
            stream = _stream;
            if (stream is null) return false;
            await stream.WriteAsync(frame, ct);
            await stream.FlushAsync(ct);
            return true;
        }
        catch
        {
            return false;
        }
        finally
        {
            _sendLock.Release();
        }
    }

    // Sends a request and awaits the correlated response (M02 correlationId
    // == request messageId). Returns null on timeout, cancellation, send
    // failure, or channel close.
    public async Task<LocalDropMessage?> SendAndReceiveAsync(
        LocalDropMessage message, int timeoutMs, CancellationToken ct = default)
    {
        var tcs = new TaskCompletionSource<LocalDropMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[message.MessageId] = tcs;
        try
        {
            if (!await SendAsync(message, ct))
            {
                _pending.TryRemove(message.MessageId, out _);
                return null;
            }
            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeoutCts.CancelAfter(timeoutMs);
            try
            {
                return await tcs.Task.WaitAsync(timeoutCts.Token);
            }
            catch
            {
                _pending.TryRemove(message.MessageId, out _);
                return null;
            }
        }
        finally
        {
            _pending.TryRemove(message.MessageId, out _);
        }
    }

    // Graceful, idempotent disconnect: stops the read loop, closes the
    // socket, cancels pending requests, notifies LOCAL_DISCONNECT exactly
    // once per cycle. Calling twice never crashes.
    public async Task DisconnectAsync()
    {
        bool wasActive;
        lock (_gate)
        {
            wasActive = State != ControlState.DISCONNECTED;
            if (!wasActive) return;
            State = ControlState.DISCONNECTING;
        }
        _shutdownInitiated = true;
        TcpClient? client;
        Task? read;
        lock (_gate)
        {
            client = _client;
            _client = null;
            _stream = null;
            read = _readTask;
            _readTask = null;
            try { _loopCts?.Cancel(); } catch { }
        }
        FailPendingAll(new IOException("connection closed"));
        CloseQuietly(client);
        if (read is not null)
        {
            try { await read.WaitAsync(TimeSpan.FromSeconds(2)); } catch { }
        }
        lock (_gate)
        {
            _loopCts?.Dispose();
            _loopCts = null;
            State = ControlState.DISCONNECTED;
        }
        NotifyClosed(CloseReason.LOCAL_DISCONNECT, "local disconnect");
    }

    public bool IsReady => State == ControlState.READY;

    // ---- internals ----

    private void ResetCycleLocked()
    {
        _closeNotified = 0;
        _shutdownInitiated = false;
        PeerHello = null;
        _myHelloId = null;
        _sawHello = false;
        _sawAck = false;
        _handshakeTcs = null;
        _decoder.Reset();
    }

    private async Task<bool> BeginOnClientAsync(TcpClient client, CancellationToken ct)
    {
        NetworkStream stream;
        try
        {
            stream = client.GetStream();
        }
        catch (Exception ex)
        {
            client.Dispose();
            return Fail(CloseReason.IO_ERROR, $"no stream: {ex.Message}");
        }
        var loopCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        lock (_gate)
        {
            _client = client;
            _stream = stream;
            _loopCts = loopCts;
            State = ControlState.NEGOTIATING;
            _myHelloId = ProtocolCodec.NewMessageId();
            _handshakeTcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            _readTask = Task.Run(() => ReadLoopAsync(stream, loopCts.Token));
        }
        // Symmetric handshake: HELLO first, then wait for peer HELLO +
        // the matching HELLO_ACK (bounded by the handshake timeout).
        string helloId;
        TaskCompletionSource<bool> tcs;
        lock (_gate)
        {
            helloId = _myHelloId!;
            tcs = _handshakeTcs!;
        }
        if (!await SendAsync(HelloMessage(helloId), ct))
        {
            return Fail(CloseReason.IO_ERROR, "failed to send HELLO");
        }
        try
        {
            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeoutCts.CancelAfter(_handshakeTimeoutMs);
            await tcs.Task.WaitAsync(timeoutCts.Token);
            return State == ControlState.READY;
        }
        catch (OperationCanceledException)
        {
            Fail(CloseReason.HANDSHAKE_TIMEOUT, "handshake timed out");
            return false;
        }
    }

    private LocalDropMessage HelloMessage(string helloId) => new(
        MessageType.HELLO,
        helloId,
        null, null, null,
        new TypedPayload.Hello(new HelloPayload(
            _local.DeviceId, _local.DeviceName, _local.DeviceType,
            AppConfig.ControlTcpPort,
            [.. _local.SafeCapabilities])));

    private async Task ReadLoopAsync(NetworkStream stream, CancellationToken ct)
    {
        var buf = new byte[8192];
        try
        {
            while (!ct.IsCancellationRequested && !_shutdownInitiated)
            {
                int n;
                try
                {
                    n = await stream.ReadAsync(buf.AsMemory(0, buf.Length), ct);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (IOException)
                {
                    break;
                }
                catch (ObjectDisposedException)
                {
                    return;
                }
                if (n == 0) break; // orderly peer shutdown
                foreach (var evt in _decoder.Feed(buf, 0, n))
                {
                    if (evt is FrameCodec.FrameEvent.Failure failure)
                    {
                        Fail(CloseReason.PROTOCOL_VIOLATION, $"framing: {failure.Detail}");
                        return;
                    }
                    HandleFrame(((FrameCodec.FrameEvent.Message)evt).JsonUtf8);
                    if (_shutdownInitiated || State == ControlState.FAILED) return;
                }
            }
        }
        finally
        {
            if (!_shutdownInitiated)
            {
                // Peer went away (or I/O broke) without a local disconnect.
                Fail(CloseReason.REMOTE_CLOSE, "peer closed connection");
            }
        }
    }

    private void HandleFrame(byte[] jsonUtf8)
    {
        DecodeOutcome outcome;
        try
        {
            outcome = ProtocolCodec.DecodeUtf8(jsonUtf8);
        }
        catch
        {
            Fail(CloseReason.PROTOCOL_VIOLATION, "decode threw");
            return;
        }
        if (outcome is not DecodeOutcome.Success success)
        {
            var detail = outcome is DecodeOutcome.Failure f ? f.Detail : "invalid message";
            Fail(CloseReason.PROTOCOL_VIOLATION, $"invalid message: {detail}");
            return;
        }
        var message = success.Message;
        if (State == ControlState.NEGOTIATING)
        {
            HandleHandshakeMessage(message);
            return;
        }
        if (State != ControlState.READY) return; // closing/closed: drop
        // Post-handshake: complete correlation waiters, then deliver.
        if (message.CorrelationId is not null &&
            _pending.TryRemove(message.CorrelationId, out var waiter))
        {
            waiter.TrySetResult(message);
        }
        try
        {
            _events?.OnMessage(this, message);
        }
        catch
        {
            // Listener bugs must not kill the read loop.
        }
    }

    private void HandleHandshakeMessage(LocalDropMessage message)
    {
        if (message.Type == MessageType.HELLO &&
            message.Payload is TypedPayload.Hello hello)
        {
            PeerHello = hello.V;
            _sawHello = true;
            var ack = new LocalDropMessage(
                MessageType.HELLO_ACK,
                ProtocolCodec.NewMessageId(),
                message.MessageId,
                null, null,
                new TypedPayload.HelloAck(new HelloAckPayload(
                    _local.DeviceId, _local.DeviceName, _local.DeviceType, true)));
            // Synchronous send from the read-loop thread: safe because the
            // read loop never holds _sendLock and runs without a sync context.
            bool sent;
            try
            {
                sent = SendAsync(ack).GetAwaiter().GetResult();
            }
            catch
            {
                sent = false;
            }
            if (!sent)
            {
                Fail(CloseReason.IO_ERROR, "failed to send HELLO_ACK");
                return;
            }
            MaybeHandshakeComplete();
            return;
        }
        if (message.Type == MessageType.HELLO_ACK &&
            message.Payload is TypedPayload.HelloAck ackPayload)
        {
            if (!ackPayload.V.Accepted || message.CorrelationId != _myHelloId)
            {
                Fail(CloseReason.PROTOCOL_VIOLATION, "bad HELLO_ACK");
                return;
            }
            _sawAck = true;
            MaybeHandshakeComplete();
            return;
        }
        Fail(CloseReason.PROTOCOL_VIOLATION, $"unexpected {message.Type} during handshake");
    }

    private void MaybeHandshakeComplete()
    {
        TaskCompletionSource<bool>? tcs = null;
        lock (_gate)
        {
            if (_sawHello && _sawAck && State == ControlState.NEGOTIATING)
            {
                State = ControlState.READY;
                tcs = _handshakeTcs;
            }
        }
        tcs?.TrySetResult(true);
    }

    // Transitions to FAILED + notifies exactly once. Returns false so callers
    // can `return Fail(...)` directly.
    private bool Fail(CloseReason reason, string detail)
    {
        _shutdownInitiated = true;
        TcpClient? client;
        lock (_gate)
        {
            client = _client;
            _client = null;
            _stream = null;
            try { _loopCts?.Cancel(); } catch { }
            if (State != ControlState.DISCONNECTED) State = ControlState.FAILED;
        }
        FailPendingAll(new IOException(detail));
        CloseQuietly(client);
        NotifyClosed(reason, detail);
        return false;
    }

    private void NotifyClosed(CloseReason reason, string detail)
    {
        if (Interlocked.CompareExchange(ref _closeNotified, 1, 0) == 0)
        {
            try
            {
                _events?.OnClosed(this, reason, detail);
            }
            catch
            {
                // Listener bugs must not propagate.
            }
        }
    }

    private void FailPendingAll(Exception ex)
    {
        foreach (var kv in _pending) kv.Value.TrySetException(ex);
        _pending.Clear();
    }

    private static void CloseQuietly(TcpClient? client)
    {
        try
        {
            client?.Close();
        }
        catch
        {
            // Best effort during shutdown.
        }
        try
        {
            client?.Dispose();
        }
        catch
        {
            // Best effort during shutdown.
        }
    }
}
