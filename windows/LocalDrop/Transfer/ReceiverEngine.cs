// M06 Windows receiver engine (ADRs 1-2, 9, 11, 13-18, 20-23). Receives one
// file over a dedicated TCP data connection from an M05-authorized Android
// sender. Requires: M04 control channel + M05 mutual auth + M05 authorized
// session (all enforced here; the data socket additionally proves knowledge
// of the transferId preamble). Streams socket -> fixed reusable buffer ->
// temp file -> atomic finalize. No second transfer while one is active
// (M12 owns orchestration); no resume (M10); no hashing (M11).
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.IO;
using LocalDrop.Core;
using LocalDrop.Discovery;using LocalDrop.Network;
using LocalDrop.Protocol;
using LocalDrop.Security;
using LocalDrop.Storage;

namespace LocalDrop.Transfer;

public sealed class ReceiverEngine
{
    private readonly DeviceInfo _local;
    private readonly AuthManager _auth;
    private readonly AuthorizedSessionStore _sessions;
    private readonly string _destDir;
    private readonly ISecurityLogger? _logger;
    private readonly int _dataPort;
    private readonly int _completeTimeoutMs;
    private readonly long _progressIntervalMs;
    private readonly long _pendingTimeoutMs;
    private readonly object _gate = new();
    private readonly object _finalizeGate = new();
    private readonly ConcurrentDictionary<string, TaskCompletionSource<TransferOutcome>> _outcomeWaiters = new();
    private readonly ConcurrentDictionary<string, TransferCompletePayload> _earlyCompletes = new();
    // Decided terminal outcomes by transferId (also mirrored to _finished).
    // Lets a waiter that registers AFTER the decision still observe it.
    private readonly ConcurrentDictionary<string, TransferOutcome> _terminal = new();
    private readonly object _waitGate = new();
    // Terminal outcomes in arrival order (also delivered to per-transfer
    // waiters). Lets owners without a pre-known transferId observe
    // completion; doubles as the UI-facing completion feed.
    private readonly ConcurrentQueue<TransferOutcome> _finished = new();

    private TcpListener? _listener;
    private Task? _acceptTask;
    private CancellationTokenSource? _cts;
    private bool _running;
    private readonly System.Collections.Generic.Dictionary<string, ActiveReceive> _actives = new();
    private readonly System.Collections.Generic.Dictionary<string, PendingTransfer> _pendings = new();
    // Temp paths reserved by live (adopted, unterminated) transfers, guarded
    // by _gate. Two concurrent transfers for the same fileName must not share
    // one temp file (they would truncate/corrupt each other); the second
    // adopter falls back to a per-transfer temp while the single-transfer
    // `<name>.localdrop-part` contract is preserved whenever uncontended.
    private readonly System.Collections.Generic.HashSet<string> _reservedTemps = new(System.StringComparer.OrdinalIgnoreCase);
    private const int MaxConcurrentTransfers = 4;
    // Cap for unmatched TRANSFER_COMPLETE frames (mirrors the Android
    // MAX_EARLY_COMPLETES bound): a peer must not grow receiver memory with
    // unmatched frames. Entries for live transfers are always kept.
    private const int MaxEarlyCompletes = 16;
    // Per-transfer verification records (audit verify-gating): the verdict
    // the sender's VERIFY_REQUEST gets — content-hash match AND successful
    // finalization, plus the peer the record belongs to. Bounded against
    // hostile peers (oldest evicted); late/duplicate VERIFY_REQUESTs are
    // answered from here without touching live state.
    private readonly System.Collections.Generic.Dictionary<string, VerifiedRecord> _verifiedRecords = new();
    private readonly System.Collections.Generic.List<string> _verifiedRecordOrder = new();

    public sealed record VerifiedRecord(
        string TransferId,
        string PeerDeviceId,
        bool Match,
        string ComputedSha256Hex);
    
    public Action<TransferProgress>? Progress { get; set; }

    private sealed class PendingTransfer
    {
        public required string TransferId;
        public required TransferMetadata Metadata;
        public required ControlConnection Control;
        public required string PeerDeviceId;
        public required long AcceptedAtMs;
    }

    private sealed class ActiveReceive
    {
        public required string TransferId;
        public required TransferMetadata Metadata;
        public required ControlConnection Control;
        public required string PeerDeviceId;
        public required string TempPath;
        public required string FinalPath;
        public required TcpClient DataClient;
        public required CancellationTokenSource Cts;
        public long ReceivedBytes;
        // Set once the temp FileStream exists; abort paths dispose it BEFORE
        // deleting the temp (Windows refuses deleting open files). Written
        // by the stream task before first read, read by abort paths.
        public volatile FileStream? FileHandle;
        // Set under _gate when this transfer's terminal outcome is decided;
        // keeps failure paths from sending duplicate ERROR messages when
        // handler-driven and loop-driven aborts overlap.
        public bool OutcomeDelivered;
    }

    public ReceiverEngine(
        DeviceInfo local,
        AuthManager auth,
        AuthorizedSessionStore sessions,
        string destDir,
        ISecurityLogger? logger = null,
        ICheckpointStore? checkpoints = null,
        int dataPort = AppConfig.DataTcpPort,
        int completeTimeoutMs = AppConfig.TransferCompleteTimeoutMs,
        long progressIntervalMs = AppConfig.TransferProgressIntervalMs,
        long pendingTimeoutMs = AppConfig.DataAcceptTimeoutMs)
    {
        local.Validate();
        ArgumentException.ThrowIfNullOrWhiteSpace(destDir);
        _local = local;
        _auth = auth;
        _sessions = sessions;
        _destDir = destDir;
        _logger = logger;
        _checkpoints = checkpoints;
        _dataPort = dataPort;
        _completeTimeoutMs = completeTimeoutMs;
        _progressIntervalMs = progressIntervalMs;
        _pendingTimeoutMs = pendingTimeoutMs;
        _progressIntervalMs = progressIntervalMs;
        _pendingTimeoutMs = pendingTimeoutMs;
    }

    private readonly ICheckpointStore? _checkpoints;

    public bool IsBusy
    {
        get { lock (_gate) return _actives.Count + _pendings.Count >= MaxConcurrentTransfers; }
    }

    public string? ActiveTransferId
    {
        get { lock (_gate) { foreach (var k in _actives.Keys) return k; foreach (var k in _pendings.Keys) return k; return null; } }
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

    // Bytes streamed so far by the active transfer (test/diagnostics hook).
    internal long ActiveReceivedBytes
    {
        get { lock (_gate) { foreach (var v in _actives.Values) return v.ReceivedBytes; return -1L; } }
    }

    // Binds the data listener. Idempotent. Must be called before any
    // TRANSFER_ACCEPT can be honored (accept path requires the listener).
    public void Start()
    {
        lock (_gate)
        {
            if (_running) return;
            Directory.CreateDirectory(_destDir);
            var listener = new TcpListener(IPAddress.Any, _dataPort);
            listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            listener.Start();
            var cts = new CancellationTokenSource();
            _listener = listener;
            _cts = cts;
            _running = true;
            _acceptTask = Task.Run(() => AcceptLoopAsync(listener, cts.Token));
        }
    }

    // Aborts any active/pending transfer (temp cleaned), closes the data
    // listener, releases everything. Idempotent.
    public async Task StopAsync()
    {
        Task? accept;
        ActiveReceive[] activesToAbort;
        string[] pendingIds;
        lock (_gate)
        {
            if (!_running) return;
            _running = false;
            try { _cts?.Cancel(); } catch { }
            try { _listener?.Stop(); } catch { }
            accept = _acceptTask;
            _acceptTask = null;
            activesToAbort = System.Linq.Enumerable.ToArray(_actives.Values);
            pendingIds = System.Linq.Enumerable.ToArray(_pendings.Keys);
            _actives.Clear();
            _pendings.Clear();
        }
        if (accept is not null)
        {
            try { await accept.WaitAsync(TimeSpan.FromSeconds(5)); } catch { }
        }
        foreach (var active in activesToAbort)
        {
            await AbortActiveAsync(active, TransferEngineState.CANCELLED, "Engine stopped.");
        }
        // Pending transfers never reach the stream phase: no stream task will
        // deliver for them, so the terminal outcome is delivered here (waiters
        // must not hang on shutdown).
        foreach (var pendingId in pendingIds)
        {
            DeliverOutcome(pendingId, new TransferOutcome.Cancelled(pendingId, 0L));
        }
        lock (_gate)
        {
            _listener = null;
            _cts?.Dispose();
            _cts = null;
        }
    }

    public TransferRequestHandler CreateHandler() => new(this);

    // Awaits the next terminal outcome of ANY transfer (null on timeout).
    // Finished outcomes stay queued until consumed here (FIFO).
    public async Task<TransferOutcome?> WaitForAnyOutcomeAsync(int timeoutMs)
    {
        var deadline = DateTimeOffset.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (_finished.TryDequeue(out var outcome))
            {
                // Keep the decided-outcome map clean for consumed entries.
                if (OutcomeId(outcome) is string tid) _terminal.TryRemove(tid, out _);
                return outcome;
            }
            await Task.Delay(25);
        }
        if (_finished.TryDequeue(out var late))
        {
            if (OutcomeId(late) is string lateTid) _terminal.TryRemove(lateTid, out _);
            return late;
        }
        return null;
    }

    private static string? OutcomeId(TransferOutcome outcome) => outcome switch
    {
        TransferOutcome.TransportComplete c => c.TransferId,
        TransferOutcome.Failed f => f.TransferId,
        TransferOutcome.Cancelled c => c.TransferId,
        _ => null,
    };

    // Awaits the terminal outcome of one transfer (null on timeout).
    // Sees outcomes decided before the waiter registered (locked against
    // the publish path, so a fast transfer can never slip through).
    public async Task<TransferOutcome?> WaitForOutcomeAsync(string transferId, int timeoutMs)
    {
        Task<TransferOutcome> task;
        lock (_waitGate)
        {
            if (_terminal.TryRemove(transferId, out var decided)) return decided;
            var tcs = _outcomeWaiters.GetOrAdd(transferId,
                _ => new TaskCompletionSource<TransferOutcome>(TaskCreationOptions.RunContinuationsAsynchronously));
            task = tcs.Task;
        }
        try
        {
            using var timeoutCts = new CancellationTokenSource(timeoutMs);
            return await task.WaitAsync(timeoutCts.Token);
        }
        catch
        {
            return null;
        }
        finally
        {
            _outcomeWaiters.TryRemove(transferId, out _);
        }
    }

    // ---- control-plane entry points (called by TransferRequestHandler) ----

    internal void HandleTransferRequest(ControlConnection control, TransferMetadata metadata)
    {
        // 1. Authenticated channel? (M05 mandatory, §20.)
        if (!_auth.IsAuthenticated(control))
        {
            Reject(control, metadata.TransferId, ErrorCode.PAIRING_REQUIRED, "Authentication required.");
            return;
        }
        string? peerId = _auth.VerifiedPeer(control)?.DeviceId;
        if (peerId is null)
        {
            Reject(control, metadata.TransferId, ErrorCode.PAIRING_REQUIRED, "Authentication required.");
            return;
        }
        // 2. M05 authorized session for this peer? (peer-level binding: the
        // receiver user authorized THIS sender; the transfer inherits it.)
        if (_sessions.SessionsFor(peerId).Count == 0)
        {
            _logger?.TransactionEvent("rejected", "-", peerId, "transfer without authorized session");
            Reject(control, metadata.TransferId, ErrorCode.TRANSFER_REJECTED, "No authorized session.");
            return;
        }
        // 3. Addressed to this receiver?
        if (metadata.ReceiverDeviceId != _local.DeviceId)
        {
            Reject(control, metadata.TransferId, ErrorCode.TRANSFER_REJECTED, "Not addressed to this receiver.");
            return;
        }
        // 4. M10 Resume Support
        long confirmedOffset = 0L;
        if (_checkpoints != null)
        {
            confirmedOffset = _checkpoints.GetCheckpoint(metadata.SenderDeviceId, metadata.FileId, metadata.FileSize, metadata.TransferId);
        }
        if (metadata.ResumeOffset > confirmedOffset)
        {
            Reject(control, metadata.TransferId, ErrorCode.INVALID_PROTOCOL, "Requested resume offset exceeds confirmed checkpoint.");
            return;
        }
        // 5. File-name defense in depth (M02 codec already enforces this).
        if (metadata.FileName.Contains('/') || metadata.FileName.Contains('\\') ||
            metadata.FileName is "." or "..")
        {
            Reject(control, metadata.TransferId, ErrorCode.INVALID_PROTOCOL, "Invalid file name.");
            return;
        }
        System.Collections.Generic.List<string> expired;
        lock (_gate)
        {
            if (!_running)
            {
                Reject(control, metadata.TransferId, ErrorCode.INTERNAL_ERROR, "Receiver not running.");
                return;
            }
            expired = PruneStalePendingLocked();
            if (_actives.Count + _pendings.Count >= MaxConcurrentTransfers)
            {
                Reject(control, metadata.TransferId, ErrorCode.TRANSFER_REJECTED, "Receiver busy.");
                return;
            }
            // Duplicate transferId: never overwrite another transfer's slot.
            if (_actives.ContainsKey(metadata.TransferId) || _pendings.ContainsKey(metadata.TransferId))
            {
                Reject(control, metadata.TransferId, ErrorCode.TRANSFER_REJECTED, "Duplicate transfer ID.");
                return;
            }
            // 6. Advisory free-space precheck (fail-open on uncertainty;
            // write-time failures are still handled mid-stream).
            try
            {
                string? root = Path.GetPathRoot(Path.GetFullPath(_destDir));
                if (root is not null)
                {
                    long free = new DriveInfo(root).AvailableFreeSpace;
                    if (free >= 0 && metadata.FileSize > free)
                    {
                        Reject(control, metadata.TransferId, ErrorCode.NOT_ENOUGH_STORAGE, "Not enough storage.");
                        return;
                    }
                }
            }
            catch
            {
                // Fail open: the streaming write path reports real failures.
            }
            _pendings[metadata.TransferId] = new PendingTransfer
            {
                TransferId = metadata.TransferId,
                Metadata = metadata,
                Control = control,
                PeerDeviceId = peerId,
                AcceptedAtMs = Environment.TickCount64,
            };
        }
        foreach (var id in expired)
            DeliverOutcome(id, new TransferOutcome.Failed(id, ErrorCode.TIMEOUT, "Pending request expired."));
        _logger?.TransactionEvent("accepted", metadata.TransferId, peerId, $"size={metadata.FileSize}");
        var accept = new LocalDropMessage(
            MessageType.TRANSFER_ACCEPT,
            ProtocolCodec.NewMessageId(),
            null, null, metadata.TransferId,
            new TypedPayload.TransferAccept(new TransferAcceptPayload(metadata.TransferId, confirmedOffset)));
        try
        {
            bool sent = control.SendAsync(accept).GetAwaiter().GetResult();
            if (!sent) DiscardPending(metadata.TransferId);
        }
        catch
        {
            DiscardPending(metadata.TransferId);
        }
    }

    internal void HandleTransferComplete(ControlConnection control, TransferCompletePayload done)
    {
        // Entries for the live transfer are always kept; strays beyond the
        // cap are dropped (fail direction is safe — the live transfer never
        // depends on them).
        if (_earlyCompletes.ContainsKey(done.TransferId))
        {
            _earlyCompletes[done.TransferId] = done;
            return;
        }
        if (_earlyCompletes.Count >= MaxEarlyCompletes) return;
        _earlyCompletes[done.TransferId] = done;
    }

    internal void HandleCancel(ControlConnection control, CancelPayload cancel)
    {
        ActiveReceive? active = null;
        bool wasPending = false;
        lock (_gate)
        {
            if (_actives.TryGetValue(cancel.TransferId, out active))
            {
                // abort handled below
            }
            else if (_pendings.ContainsKey(cancel.TransferId))
            {
                _pendings.Remove(cancel.TransferId);
                wasPending = true;
            }
        }
        if (active is not null)
        {
            _ = AbortActiveAsync(active, TransferEngineState.CANCELLED, "Cancelled by sender.");
            return;
        }
        if (wasPending)
        {
            // Cancelled before the data connection arrived: no stream task
            // will ever run, so the terminal outcome is delivered here.
            DeliverOutcome(cancel.TransferId,
                new TransferOutcome.Cancelled(cancel.TransferId, 0L));
        }
    }

    private void RecordVerification(VerifiedRecord record)
    {
        lock (_gate)
        {
            if (_verifiedRecords.ContainsKey(record.TransferId))
            {
                _verifiedRecords[record.TransferId] = record;
                return;
            }
            _verifiedRecords[record.TransferId] = record;
            _verifiedRecordOrder.Add(record.TransferId);
            while (_verifiedRecords.Count > AppConfig.MaxVerifyRecords)
            {
                if (_verifiedRecordOrder.Count == 0) break;
                string oldest = _verifiedRecordOrder[0];
                _verifiedRecordOrder.RemoveAt(0);
                _verifiedRecords.Remove(oldest);
            }
        }
    }

    internal void HandleVerifyRequest(ControlConnection control, string transferId, string sha256Hex)
    {
        string? requestPeer = null;
        try { requestPeer = _auth.VerifiedPeer(control)?.DeviceId; } catch { }
        if (requestPeer is null)
        {
            ReplyTransferError(control, transferId, ErrorCode.PAIRING_REQUIRED, "Authentication required.");
            return;
        }
        VerifiedRecord? recorded = null;
        lock (_gate)
        {
            if (_verifiedRecords.TryGetValue(transferId, out var r)) recorded = r;
        }
        if (recorded is not null)
        {
            if (recorded.PeerDeviceId != requestPeer)
            {
                ReplyTransferError(control, transferId, ErrorCode.TRANSFER_REJECTED, "Not authorized for this transfer.");
                return;
            }
            bool match = recorded.Match && string.Equals(recorded.ComputedSha256Hex, sha256Hex, StringComparison.OrdinalIgnoreCase);
            ReplyVerifyResult(control, transferId, match, recorded.ComputedSha256Hex);
            return;
        }
        string? livePeer = null;
        long fileSize = 0L;
        lock (_gate)
        {
            if (_actives.TryGetValue(transferId, out var a))
            {
                livePeer = a.PeerDeviceId;
                fileSize = a.Metadata.FileSize;
            }
            else if (_pendings.TryGetValue(transferId, out var p))
            {
                livePeer = p.PeerDeviceId;
                fileSize = p.Metadata.FileSize;
            }
        }
        if (livePeer is null)
        {
            ReplyTransferError(control, transferId, ErrorCode.INVALID_PROTOCOL, "Unknown transfer.");
            return;
        }
        if (livePeer != requestPeer)
        {
            ReplyTransferError(control, transferId, ErrorCode.TRANSFER_REJECTED, "Not authorized for this transfer.");
            return;
        }
        _ = Task.Run(async () =>
        {
            long timeoutMs = AppConfig.TransferVerifyTimeoutMsFor(fileSize);
            var (late, decided) = await AwaitVerificationVerdictAsync(transferId, timeoutMs);
            if (late is not null && late.PeerDeviceId == requestPeer)
            {
                bool match = late.Match && string.Equals(late.ComputedSha256Hex, sha256Hex, StringComparison.OrdinalIgnoreCase);
                ReplyVerifyResult(control, transferId, match, late.ComputedSha256Hex);
            }
            else
            {
                ReplyTransferError(control, transferId, ErrorCode.TIMEOUT, "Transfer did not complete verification.");
            }
        });
    }

    private async Task<(VerifiedRecord? Record, bool Decided)> AwaitVerificationVerdictAsync(
        string transferId, long timeoutMs)
    {
        long deadline = Environment.TickCount64 + timeoutMs;
        while (true)
        {
            lock (_gate)
            {
                if (_verifiedRecords.TryGetValue(transferId, out var r)) return (r, true);
            }
            bool decided;
            lock (_waitGate)
            {
                decided = _terminal.ContainsKey(transferId);
            }
            if (decided) return (null, true);
            if (Environment.TickCount64 >= deadline) return (null, false);
            await Task.Delay(50);
        }
    }

    private void ReplyVerifyResult(
        ControlConnection control, string transferId, bool match, string computedSha)
    {
        try
        {
            var msg = new LocalDropMessage(
                MessageType.VERIFY_RESULT,
                ProtocolCodec.NewMessageId(),
                null, null, transferId,
                new TypedPayload.VerifyResult(new VerifyResultPayload(transferId, match, computedSha)));
            _ = control.SendAsync(msg);
        }
        catch { }
    }

    private void ReplyTransferError(
        ControlConnection control, string transferId, ErrorCode code, string detail)
    {
        try
        {
            var msg = new LocalDropMessage(
                MessageType.ERROR,
                ProtocolCodec.NewMessageId(),
                null, null, transferId,
                new TypedPayload.Error(new ErrorPayload(new ProtocolError(code, detail), null)));
            _ = control.SendAsync(msg);
        }
        catch { }
    }

    internal void HandlePeerError(ControlConnection control, string? transferId, ProtocolError error)
    {
        // Sender reports its own failure (e.g. source unreadable): fail only
        // the addressed transfer. Failing every transfer on the connection
        // would let one session's failure corrupt an unrelated session
        // multiplexed over the same control channel. A null/empty ID keeps
        // the legacy control-scoped behavior for senders that omit it.
        if (transferId is { Length: > 0 })
        {
            ActiveReceive? single = null;
            bool wasPending = false;
            lock (_gate)
            {
                if (_actives.TryGetValue(transferId, out var a) && a.Control == control)
                {
                    single = a;
                    _actives.Remove(transferId);
                }
                else if (_pendings.TryGetValue(transferId, out var p) && p.Control == control)
                {
                    _pendings.Remove(transferId);
                    wasPending = true;
                }
                else
                {
                    return; // Unknown transfer: touch nothing.
                }
            }
            if (single is not null)
            {
                _ = FailActiveAsync(single, error.Code, error.Message);
                return;
            }
            if (wasPending)
            {
                DeliverOutcome(transferId, new TransferOutcome.Failed(transferId, error.Code, error.Message));
            }
            return;
        }
        ActiveReceive[] activesToAbort;
        string[] pendingIds;
        lock (_gate)
        {
            activesToAbort = System.Linq.Enumerable.ToArray(System.Linq.Enumerable.Where(_actives.Values, v => v.Control == control));
            pendingIds = System.Linq.Enumerable.ToArray(System.Linq.Enumerable.Select(System.Linq.Enumerable.Where(_pendings.Values, v => v.Control == control), v => v.TransferId));
            foreach (var a in activesToAbort) _actives.Remove(a.TransferId);
            foreach (var p in pendingIds) _pendings.Remove(p);
        }
        foreach (var active in activesToAbort)
        {
            _ = FailActiveAsync(active, error.Code, error.Message);
        }
        foreach (var pendingId in pendingIds)
        {
            DeliverOutcome(pendingId, new TransferOutcome.Failed(pendingId, error.Code, error.Message));
        }
    }

    internal void HandleControlClosed(ControlConnection connection)
    {
        // Control loss with an active transfer: the sender cannot signal
        // COMPLETE, so fail fast rather than wait indefinitely. Pendings on
        // the lost channel can never reach the stream phase, so their
        // waiters get a terminal outcome here instead of hanging.
        ActiveReceive[] activesToAbort;
        string[] pendingIds;
        lock (_gate)
        {
            activesToAbort = System.Linq.Enumerable.ToArray(System.Linq.Enumerable.Where(_actives.Values, v => v.Control == connection));
            pendingIds = System.Linq.Enumerable.ToArray(System.Linq.Enumerable.Select(System.Linq.Enumerable.Where(_pendings.Values, v => v.Control == connection), v => v.TransferId));
            foreach (var a in activesToAbort) _actives.Remove(a.TransferId);
            foreach (var p in pendingIds) _pendings.Remove(p);
        }
        foreach (var active in activesToAbort)
        {
            _ = FailActiveAsync(active, ErrorCode.NETWORK_LOST, "Control channel lost.");
        }
        foreach (var pendingId in pendingIds)
        {
            DeliverOutcome(pendingId, new TransferOutcome.Failed(pendingId, ErrorCode.NETWORK_LOST, "Control channel lost."));
        }
    }

    // ---- data plane ----

    private async Task AcceptLoopAsync(TcpListener listener, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync(ct);
            }
            catch (OperationCanceledException) { return; }
            catch (SocketException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (Exception)
            {
                if (ct.IsCancellationRequested) return;
                continue;
            }
            _ = Task.Run(() => HandleDataClientAsync(client, ct), ct);
        }
    }

    private async Task HandleDataClientAsync(TcpClient client, CancellationToken ct)
    {
        using (client)
        {
            string? transferId;
            try
            {
                transferId = await ReadPreambleAsync(client.GetStream(), ct);
            }
            catch
            {
                transferId = null;
            }
            if (transferId is null)
            {
                _logger?.TransactionEvent("rejected", "-", null, "data connection without valid preamble");
                return; // using{} closes: unknown/ambiguous sender, no file touched.
            }
            PendingTransfer? pending;
            System.Collections.Generic.List<string> expired;
            lock (_gate)
            {
                expired = PruneStalePendingLocked();
                // Read-only match check here: pending is cleared ONLY inside
                // the atomic adoption below (or by a racing CANCEL, which
                // delivers its own outcome). Clearing here would reopen the
                // neither-pending-nor-active gap.
                _pendings.TryGetValue(transferId, out pending);
            }
            foreach (var id in expired)
                DeliverOutcome(id, new TransferOutcome.Failed(id, ErrorCode.TIMEOUT, "Pending request expired."));
            if (pending is null)
            {
                _logger?.TransactionEvent("rejected", transferId, null, "data connection for unknown transfer");
                return;
            }
            // Build the active record OUTSIDE the lock (filesystem probes),
            // then swap pending->active atomically UNDER the lock: a control
            // CANCEL can only ever observe pending (delivers outcome here)
            // or active (aborts there) — never neither (that gap dropped
            // outcomes under load).
            ActiveReceive active;
            try
            {
                active = new ActiveReceive
                {
                    TransferId = transferId,
                    Metadata = pending.Metadata,
                    Control = pending.Control,
                    PeerDeviceId = pending.PeerDeviceId,
                    TempPath = TempPathFor(pending.Metadata.FileName),
                    FinalPath = UniqueFinalPath(pending.Metadata.FileName),
                    DataClient = client,
                    Cts = CancellationTokenSource.CreateLinkedTokenSource(ct),
                };
            }
            catch (Exception ex)
            {
                FailPendingNow(pending, TransferErrors.DestinationUnavailable(ex.Message));
                return;
            }
            bool adopted;
            lock (_gate)
            {
                if (!_pendings.ContainsKey(transferId))
                {
                    adopted = false;
                }
                else
                {
                    // Reserve a per-transfer temp: a concurrent transfer for
                    // the same fileName must stream to its own temp file.
                    string temp = active.TempPath;
                    if (!_reservedTemps.Add(temp))
                    {
                        temp = Path.Combine(_destDir,
                            pending.Metadata.FileName + "." + transferId.Substring(0, 8) + ".localdrop-part");
                        _reservedTemps.Add(temp);
                    }
                    active.TempPath = temp;
                    _pendings.Remove(transferId);
                    _actives[transferId] = active;
                    adopted = true;
                }
            }
            if (!adopted)
            {
                // Lost a race: a second data socket (active set), or a
                // CANCEL/error consumed pending and delivered its outcome.
                // Never stream.
                try { active.Cts.Dispose(); } catch { }
                return;
            }
            await ReceiveBytesAsync(active, ct);
        }
    }

    // Delivers a terminal outcome for a pending transfer that will never
    // reach the stream phase (e.g. temp/final path setup failed).
    private void FailPendingNow(
        PendingTransfer pending, (ErrorCode Code, string Detail) error)
    {
        // The pending slot belongs to this transfer and no stream task will
        // ever run for it: release the slot NOW (not at the stale prune), or
        // the receiver would wrongly report busy until then and a late data
        // connection could still adopt the dead slot.
        lock (_gate)
        {
            _pendings.Remove(pending.TransferId);
        }
        _logger?.TransactionEvent("failed", pending.TransferId, pending.PeerDeviceId,
            $"code={ErrorCodes.ToWire(error.Code)}");
        DeliverOutcome(pending.TransferId,
            new TransferOutcome.Failed(pending.TransferId, error.Code, error.Detail));
    }

    // Reads the 37-byte preamble ("<uuid>\n"), capped at 64 bytes.
    internal static async Task<string?> ReadPreambleAsync(NetworkStream stream, CancellationToken ct)
    {
        var buf = new List<byte>(40);
        var one = new byte[1];
        while (buf.Count < AppConfig.DataPreambleMaxBytes)
        {
            int n;
            try
            {
                n = await stream.ReadAsync(one.AsMemory(0, 1), ct);
            }
            catch
            {
                return null;
            }
            if (n == 0) return null;
            if (one[0] == (byte)'\n') break;
            buf.Add(one[0]);
        }
        if (buf.Count == 0 || buf.Count >= AppConfig.DataPreambleMaxBytes) return null;
        string text = System.Text.Encoding.ASCII.GetString(buf.ToArray());
        if (text.Length != 36) return null;
        if (!Uuids.IsValid(text)) return null;
        return text.ToLowerInvariant();
    }

    private async Task ReceiveBytesAsync(ActiveReceive active, CancellationToken ct)
    {
        var metadata = active.Metadata;
        string tempPath = active.TempPath;
        var linkedCts = active.Cts;
        var client = active.DataClient;
        var sizer = new AdaptiveBlockSizer(metadata.FileSize);
        byte[] buffer = new byte[sizer.CurrentBlockSize];
        long received = metadata.ResumeOffset;
        long lastCheckpointOffset = received;
        long startNanos = NanoNow();
        long readNanosTotal = 0L;
        long writeNanosTotal = 0L;
        long stalls = 0L;
        long lastEmitMs = 0L;
        long windowBytes = 0L;
        long windowStartNanos = startNanos;
        double? recentBps = null;
        Emit(active, metadata.FileSize, 0L, 0L, recentBps, TransferEngineState.TRANSFERRING);
        FileStream? file = null;
        try
        {
            try
            {
                var mode = metadata.ResumeOffset > 0 ? FileMode.OpenOrCreate : FileMode.Create;
                file = new FileStream(tempPath, mode, FileAccess.Write, FileShare.None,
                    buffer.Length, FileOptions.SequentialScan | FileOptions.Asynchronous);
                if (metadata.ResumeOffset > 0)
                {
                    file.Position = metadata.ResumeOffset;
                }
            }
            catch (Exception ex)
            {
                await FailActiveAsync(active, TransferErrors.DestinationUnavailable(ex.Message));
                return;
            }
            using (file)
            {
                active.FileHandle = file;
                var stream = client.GetStream();
                // Disposes the temp file handle BEFORE any abort/fail path
                // deletes the temp (Windows refuses deleting open files).
                // The trailing using-dispose is then a harmless no-op.
                void DisposeFile()
                {
                    try { file.Dispose(); } catch { }
                }
                while (received < metadata.FileSize)
                {
                    linkedCts.Token.ThrowIfCancellationRequested();
                    if (buffer.Length != sizer.CurrentBlockSize)
                        buffer = new byte[sizer.CurrentBlockSize];
                    // remaining < buffer.Length implies remaining fits Int32
                    // (buffer.Length <= MAX_BLOCK << Int32.MaxValue); the cast
                    // is exact, never truncating (ADR 12 audit point).
                    long remainingBytes = metadata.FileSize - received;
                    int want = buffer.Length;
                    if (remainingBytes < want) want = (int)remainingBytes;
                    long readStart = NanoNow();
                    int n;
                    try
                    {
                        n = await stream.ReadAsync(buffer.AsMemory(0, want), linkedCts.Token);
                    }
                    catch (OperationCanceledException)
                    {
                        DisposeFile();
                        await AbortActiveAsync(active, TransferEngineState.CANCELLED, "Cancelled.");
                        return;
                    }
                    catch (Exception ex)
                    {
                        DisposeFile();
                        await FailActiveAsync(active, TransferErrors.ConnectionLost(ex.Message));
                        return;
                    }
                    long readNanos = NanoNow() - readStart;
                    if (n == 0)
                    {
                        DisposeFile();
                        await FailActiveAsync(active,
                            TransferErrors.ConnectionLost("Sender closed early."));
                        return;
                    }
                    long writeStart = NanoNow();
                    try
                    {
                        await file!.WriteAsync(buffer.AsMemory(0, n), linkedCts.Token);
                    }
                    catch (OperationCanceledException)
                    {
                        DisposeFile();
                        await AbortActiveAsync(active, TransferEngineState.CANCELLED, "Cancelled.");
                        return;
                    }
                    catch (Exception ex)
                    {
                        DisposeFile();
                        var (code, detail) = TransferErrors.ClassifyWriteFailure(ex);
                        await FailActiveAsync(active, (code, detail));
                        return;
                    }
                    long writeNanos = NanoNow() - writeStart;
                    readNanosTotal += readNanos;
                    writeNanosTotal += writeNanos;
                    received += n;
                    active.ReceivedBytes = received;
                    if (received - lastCheckpointOffset >= metadata.CheckpointSize || received == metadata.FileSize)
                    {
                        await file!.FlushAsync(linkedCts.Token);
                        _checkpoints?.SaveCheckpoint(active.TransferId, metadata.SenderDeviceId, metadata.FileId, metadata.FileName, metadata.FileSize, received);
                        lastCheckpointOffset = received;
                    }
                    long blockNanos = readNanos + writeNanos;
                    bool stalled = blockNanos / 1_000_000L > AppConfig.AdaptStallThresholdMs;
                    if (stalled) stalls++;
                    sizer.Observe(n, blockNanos, stalled);
                    windowBytes += n;
                    long nowMs = Environment.TickCount64;
                    if (nowMs - lastEmitMs >= AppConfig.TransferProgressIntervalMs)
                    {
                        lastEmitMs = nowMs;
                        long windowNanos = NanoNow() - windowStartNanos;
                        recentBps = windowNanos > 0 ? windowBytes * 1e9 / windowNanos : null;
                        windowBytes = 0L;
                        windowStartNanos = NanoNow();
                        Emit(active, metadata.FileSize, received,
                            (NanoNow() - startNanos) / 1_000_000L, recentBps, TransferEngineState.TRANSFERRING);
                    }
                }
            }
            file = null;
            long elapsedMs = (NanoNow() - startNanos) / 1_000_000L;
            double? average = elapsedMs > 0 ? received * 1000.0 / elapsedMs : null;
            Emit(active, metadata.FileSize, received, elapsedMs, recentBps, TransferEngineState.TRANSFERRING);
            // Byte-count match is necessary but NOT sufficient: the control
            // plane must commit with TRANSFER_COMPLETE (with timeout).
            TransferCompletePayload? done = TakeEarlyComplete(metadata.TransferId);
            if (done is null)
            {
                var deadline = DateTimeOffset.UtcNow.AddMilliseconds(_completeTimeoutMs);
                while (DateTimeOffset.UtcNow < deadline)
                {
                    if (linkedCts.Token.IsCancellationRequested)
                    {
                        await AbortActiveAsync(active, TransferEngineState.CANCELLED, "Cancelled.");
                        return;
                    }
                    done = TakeEarlyComplete(metadata.TransferId);
                    if (done is not null) break;
                    try
                    {
                        await Task.Delay(25, linkedCts.Token);
                    }
                    catch (OperationCanceledException)
                    {
                        await AbortActiveAsync(active, TransferEngineState.CANCELLED, "Cancelled.");
                        return;
                    }
                }
            }
            if (done is null)
            {
                var (code, detail) = TransferErrors.TimedOut("No TRANSFER_COMPLETE.");
                await FailActiveAsync(active, (code, detail));
                return;
            }
            if (done.FileSize != received || done.FileSize != metadata.FileSize)
            {
                var (code, detail) = TransferErrors.ProtocolViolation("Completion size mismatch.");
                await FailActiveAsync(active, (code, detail));
                return;
            }
            // 7. Verify SHA-256 (M11)
            string expectedSha = done.Sha256Hex.ToLowerInvariant();
            if (expectedSha == SenderEngine.M07HashNotComputed)
            {
                var (code, detail) = TransferErrors.ProtocolViolation("SHA-256 hash not provided (M11 required).");
                RecordVerification(new VerifiedRecord(metadata.TransferId, active.PeerDeviceId, false, SenderEngine.M07HashNotComputed));
                await FailActiveAsync(active, (code, detail));
                return;
            }
            string computedSha;
            try
            {
                using var hasher = System.Security.Cryptography.IncrementalHash.CreateHash(System.Security.Cryptography.HashAlgorithmName.SHA256);
                using var readStream = new FileStream(tempPath, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, FileOptions.SequentialScan | FileOptions.Asynchronous);
                byte[] hashBuffer = new byte[65536];
                while (true)
                {
                    linkedCts.Token.ThrowIfCancellationRequested();
                    int bytesRead = await readStream.ReadAsync(hashBuffer.AsMemory(0, 65536), linkedCts.Token);
                    if (bytesRead == 0) break;
                    hasher.AppendData(hashBuffer, 0, bytesRead);
                }
                computedSha = Convert.ToHexString(hasher.GetHashAndReset()).ToLowerInvariant();
            }
            catch (Exception ex)
            {
                var (code, detail) = TransferErrors.DestinationUnavailable("Failed to verify SHA-256: " + ex.Message);
                RecordVerification(new VerifiedRecord(metadata.TransferId, active.PeerDeviceId, false, SenderEngine.M07HashNotComputed));
                await FailActiveAsync(active, (code, detail));
                return;
            }

            if (computedSha != expectedSha)
            {
                var (code, detail) = TransferErrors.ProtocolViolation($"SHA-256 mismatch. Expected {expectedSha}, got {computedSha}");
                DeleteTempQuietly(tempPath, _logger, metadata.TransferId);
                _checkpoints?.ClearCheckpoint(metadata.TransferId);
                RecordVerification(new VerifiedRecord(metadata.TransferId, active.PeerDeviceId, false, computedSha));
                await FailActiveAsync(active, (code, detail));
                return;
            }
            // Shape already validated by the frozen M02 codec; the M06 zeros
            // sentinel is explicitly NOT verification (ADR 20).
            // Commit under _finalizeGate (mirrors the Android finalizeGate):
            // the decision to finalize, the finalization itself, and the
            // terminal-outcome claim are one atomic step against abort/fail
            // paths. A cancel racing the commit either wins first (no
            // finalize, single Cancelled) or loses (finalize runs fully,
            // single TransportComplete) — never a final file with a
            // Cancelled outcome, and never two outcomes for one transfer.
            TransferOutcome.TransportComplete? success = null;
            lock (_finalizeGate)
            {
                lock (_gate)
                {
                    if (active.OutcomeDelivered) return;
                    active.OutcomeDelivered = true;
                }
                try
                {
                    // Re-resolve the final name at commit: a concurrent
                    // transfer for the same fileName may have landed since
                    // adoption. The commit section is serialized by
                    // _finalizeGate, so the second committer observes the
                    // first file and uniquifies per the established collision
                    // policy instead of failing or overwriting. (Residual
                    // TOCTOU against EXTERNAL actors is accepted per ADR 13.)
                    if (File.Exists(active.FinalPath))
                    {
                        active.FinalPath = UniqueFinalPath(metadata.FileName);
                    }
                    File.Move(active.TempPath, active.FinalPath, overwrite: false);
                    _checkpoints?.ClearCheckpoint(metadata.TransferId);
                    RecordVerification(new VerifiedRecord(active.TransferId, active.PeerDeviceId, true, computedSha));
                }
                catch (Exception ex)
                {
                    var (code, detail) = TransferErrors.DestinationWriteFailed(ex.Message);
                    RecordVerification(new VerifiedRecord(active.TransferId, active.PeerDeviceId, false, computedSha));
                    CompleteActive(active, new TransferOutcome.Failed(active.TransferId, code, detail));
                    _logger?.TransactionEvent("failed", active.TransferId, active.PeerDeviceId,
                        $"code={ErrorCodes.ToWire(code)}");
                    return;
                }
                Emit(active, metadata.FileSize, received, elapsedMs, recentBps, TransferEngineState.TRANSPORT_COMPLETE);
                success = new TransferOutcome.TransportComplete(
                    metadata.TransferId, received, elapsedMs, average,
                    new TransferMetrics(elapsedMs, received, average, readNanosTotal, writeNanosTotal,
                        stalls, [.. sizer.History], sizer.CurrentBlockSize));
                CompleteActive(active, success);
            }
            _logger?.TransactionEvent("transport-complete", metadata.TransferId, active.PeerDeviceId,
                $"bytes={received} elapsedMs={elapsedMs}");
        }
        finally
        {
            linkedCts.Dispose();
        }
    }

    // ---- completion/failure plumbing ----

    private TransferCompletePayload? TakeEarlyComplete(string transferId)
    {
        _earlyCompletes.TryRemove(transferId, out var done);
        return done;
    }

    private void Emit(ActiveReceive active, long total, long transferred, long elapsedMs,
        double? recentBps, TransferEngineState state)
    {
        try
        {
            Progress?.Invoke(TransferProgress.Compute(
                active.TransferId, total, transferred, elapsedMs, recentBps, state));
        }
        catch
        {
            // Listener bugs must not break the transfer.
        }
    }

    private void CompleteActive(ActiveReceive active, TransferOutcome outcome)
    {
        lock (_gate)
        {
            _actives.Remove(active.TransferId);
            _reservedTemps.Remove(active.TempPath);
        }
        try { active.Cts.Cancel(); } catch { }
        try { active.DataClient.Close(); } catch { }
        DeliverOutcome(active.TransferId, outcome);
    }

    // Single publishing point for terminal outcomes (active + pending
    // paths): recorded for late waiters, queued for any-order waiters, and
    // pushed to a live waiter — atomically under one gate.
    private void DeliverOutcome(string transferId, TransferOutcome outcome)
    {
        TaskCompletionSource<TransferOutcome>? waiter = null;
        lock (_waitGate)
        {
            _terminal[transferId] = outcome;
            _finished.Enqueue(outcome);
            if (_outcomeWaiters.TryRemove(transferId, out var w)) waiter = w;
        }
        waiter?.TrySetResult(outcome);
    }

    private async Task FailActiveAsync(ActiveReceive active, (ErrorCode Code, string Detail) error)
    {
        await FailActiveAsync(active, error.Code, error.Detail);
    }

    private async Task FailActiveAsync(ActiveReceive active, ErrorCode code, string detail)
    {
        try { active.Cts.Cancel(); } catch { }
        // Close the temp handle on THIS thread first: outcome delivery below
        // must observe a deleted temp deterministically (Windows refuses
        // deleting open files, and the stream task may lag behind).
        try { active.FileHandle?.Dispose(); } catch { }
        bool first;
        lock (_gate)
        {
            first = !active.OutcomeDelivered;
            active.OutcomeDelivered = true;
        }
        // M10: Do not delete temp file on failure; retain for resume.
        if (first)
        {
            try
            {
                var error = new LocalDropMessage(
                    MessageType.ERROR,
                    ProtocolCodec.NewMessageId(),
                    null, null, active.TransferId,
                    new TypedPayload.Error(new ErrorPayload(new ProtocolError(code, detail), null)));
                await active.Control.SendAsync(error);
            }
            catch
            {
                // Best effort; the data socket is already doomed.
            }
        }
        CompleteActive(active, new TransferOutcome.Failed(active.TransferId, code, detail));
        _logger?.TransactionEvent("failed", active.TransferId, active.PeerDeviceId,
            $"code={ErrorCodes.ToWire(code)}");
    }

    private async Task AbortActiveAsync(ActiveReceive active, TransferEngineState state, string reason)
    {
        try { active.Cts.Cancel(); } catch { }
        // Same deterministic ordering as FailActiveAsync (see above).
        try { active.FileHandle?.Dispose(); } catch { }
        // First-claim-wins (mirrors the Android claimOutcome): concurrent
        // terminal paths yield EXACTLY ONE outcome per transfer. A late abort
        // after completion/failure stays silent.
        lock (_gate)
        {
            if (active.OutcomeDelivered) return;
            active.OutcomeDelivered = true;
        }
        if (state == TransferEngineState.CANCELLED)
        {
            // Lock order everywhere: _finalizeGate -> _gate (never the
            // reverse), matching the commit path above — no deadlock.
            lock (_finalizeGate)
            {
                DeleteTempQuietly(active.TempPath, _logger, active.TransferId);
                _checkpoints?.ClearCheckpoint(active.TransferId);
            }
            CompleteActive(active, new TransferOutcome.Cancelled(active.TransferId, active.ReceivedBytes));
        }
        else
        {
            // M10: Do not delete temp file on non-cancellation aborts.
            CompleteActive(active, new TransferOutcome.Failed(active.TransferId, ErrorCode.INTERNAL_ERROR, reason));
        }
        await Task.CompletedTask;
    }

    private void DiscardPending(string transferId)
    {
        bool removed;
        lock (_gate)
        {
            removed = _pendings.Remove(transferId);
        }
        // The ACCEPT send failed: the sender never learns of this transfer,
        // so no stream task will ever run. Deliver a terminal outcome here
        // instead of leaving waiters hanging until their own timeout.
        if (removed)
        {
            DeliverOutcome(transferId, new TransferOutcome.Failed(
                transferId, ErrorCode.NETWORK_LOST, "Failed to send TRANSFER_ACCEPT."));
        }
    }

    // Removes expired pending requests and returns their IDs. Callers deliver
    // a terminal TIMEOUT outcome OUTSIDE the gate: expiry must release the slot
    // AND unblock waiters, never silently drop them.
    private System.Collections.Generic.List<string> PruneStalePendingLocked()
    {
        var now = Environment.TickCount64;
        var stale = new System.Collections.Generic.List<string>();
        foreach (var p in _pendings.Values)
        {
            if (now - p.AcceptedAtMs >= _pendingTimeoutMs) stale.Add(p.TransferId);
        }
        foreach (var id in stale) _pendings.Remove(id);
        return stale;
    }

    private void Reject(ControlConnection control, string transferId, ErrorCode code, string message)
    {
        _logger?.TransactionEvent("rejected", transferId, control.PeerDeviceId,
            $"code={ErrorCodes.ToWire(code)}");
        var reject = new LocalDropMessage(
            MessageType.TRANSFER_REJECT,
            ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferReject(new TransferRejectPayload(transferId, new ProtocolError(code, message))));
        try
        {
            control.SendAsync(reject).GetAwaiter().GetResult();
        }
        catch
        {
            // Best effort.
        }
    }

    // ---- temp/final paths (ADR 13) ----

    internal string TempPathFor(string fileName) =>
        Path.Combine(_destDir, fileName + ".localdrop-part");

    internal string UniqueFinalPath(string fileName)
    {
        string candidate = Path.Combine(_destDir, fileName);
        if (!File.Exists(candidate) && !Directory.Exists(candidate)) return candidate;
        string stem = Path.GetFileNameWithoutExtension(fileName);
        string ext = Path.GetExtension(fileName);
        for (int n = 2; n <= 99999; n++)
        {
            candidate = Path.Combine(_destDir, $"{stem} ({n}){ext}");
            if (!File.Exists(candidate) && !Directory.Exists(candidate)) return candidate;
        }
        // Practically unreachable; fail safe rather than loop forever.
        throw new IOException("No free destination name.");
    }

    internal static void DeleteTempQuietly(string tempPath, ISecurityLogger? logger, string transferId)
    {
        for (int i = 0; i < 3; i++)
        {
            try
            {
                if (File.Exists(tempPath)) File.Delete(tempPath);
                return;
            }
            catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException)
            {
                if (i == 2)
                {
                    logger?.TransactionEvent("failed", transferId, null, "temporary file cleanup failed");
                }
                else
                {
                    System.Threading.Thread.Sleep(50);
                }
            }
        }
    }

    private static long NanoNow()
    {
        long freq = System.Diagnostics.Stopwatch.Frequency;
        return System.Diagnostics.Stopwatch.GetTimestamp() * 1_000_000_000L / freq;
    }
}
