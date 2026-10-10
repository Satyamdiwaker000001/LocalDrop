// M05: mutual peer authentication over an M04 control connection
// (shared-protocol/authentication.md §§2-5). Wraps a connection listener:
// AUTH_REQUEST/AUTH_RESPONSE are consumed here, everything else is forwarded
// to `inner`, so frozen M04 behavior is untouched.
// Both sides send auth-hello (no leader election; per-direction FIFO keeps
// the state machine deterministic). Failure fails closed: the control
// connection is dropped and nothing is authorized. Handshake success proves
// identity ONLY, never trust or authorization.
using LocalDrop.Core;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Protocol;
using System.Collections.Concurrent;

namespace LocalDrop.Security;

public sealed record VerifiedPeer(string DeviceId, byte[] PublicKeySpki)
{
    public bool Equals(VerifiedPeer? other) =>
        other is not null && DeviceId == other.DeviceId &&
        PublicKeySpki.AsSpan().SequenceEqual(other.PublicKeySpki);

    public override int GetHashCode() => HashCode.Combine(DeviceId, PublicKeySpki.Length);
}

public abstract record AuthOutcome
{
    public sealed record Authenticated(VerifiedPeer Peer) : AuthOutcome;
    public sealed record Failed(string Reason) : AuthOutcome; // generic text, never secrets
}

public interface IAuthEvents
{
    void OnAuthenticated(ControlConnection connection, VerifiedPeer peer);
    void OnAuthFailed(ControlConnection connection, string reason);
}

internal enum AuthPhase { HELLO_SENT, PEER_KNOWN, PROOF_SENT, AWAIT_RESULT, DONE, FAILED }

internal sealed class AuthHandshake
{
    public required byte[] MyNonce { get; init; }
    public bool HelloSent;
    public AuthProtocol.ParsedHello? PeerHello;
    public AuthPhase Phase = AuthPhase.HELLO_SENT;
    public TaskCompletionSource<AuthOutcome> Future { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public long DeadlineMillis;
}

// Receiver-side PAIR submit result (sender perspective).
public abstract record PairSubmitOutcome
{
    public sealed record Accepted : PairSubmitOutcome;
    public sealed record Rejected(string Reason) : PairSubmitOutcome;
    public sealed record Failed(string Reason) : PairSubmitOutcome;
}

public sealed class AuthManager : IControlEvents
{
    private readonly DeviceInfo _local;
    private readonly IDeviceIdentityStore _store;
    private readonly ISecurityLogger _logger;
    private readonly int _authTimeoutMs;
    private readonly IDiscoveryClock _clock;
    // Next listener in the chain (receives non-AUTH messages). Settable so
    // the receiver wiring (AuthManager -> TransactionAuthorizer, which itself
    // needs the AuthManager reference) can be assembled after construction.
    public IControlEvents? Inner { get; set; }
    private readonly IAuthEvents? _events;
    private readonly ConcurrentDictionary<string, AuthHandshake> _handshakes = new();
    private readonly ConcurrentDictionary<string, VerifiedPeer> _verified = new();
    private readonly ConcurrentDictionary<string, TaskCompletionSource<PairSubmitOutcome>> _pairPending = new();

    public Action<string, SenderAuthUiState>? SenderObserver { get; set; }

    public AuthManager(
        DeviceInfo local,
        IDeviceIdentityStore store,
        ISecurityLogger logger,
        int authTimeoutMs = AppConfig.AuthTimeoutMs,
        IDiscoveryClock? clock = null,
        IControlEvents? inner = null,
        IAuthEvents? events = null)
    {
        local.Validate();
        _local = local;
        _store = store;
        _logger = logger;
        _authTimeoutMs = authTimeoutMs;
        _clock = clock ?? SystemDiscoveryClock.Instance;
        Inner = inner;
        _events = events;
    }

    // Starts (or joins) mutual authentication on a READY control connection.
    // Returns a task completing with Authenticated/Failed (bounded by the
    // auth timeout; connection loss also completes it). Never throws.
    public Task<AuthOutcome> BeginAuthAsync(ControlConnection connection)
    {
        if (_handshakes.TryGetValue(connection.Id, out var existing))
            return existing.Future.Task;
        DeviceIdentity me;
        try
        {
            me = _store.Identity();
        }
        catch
        {
            return Task.FromResult<AuthOutcome>(new AuthOutcome.Failed("local identity unavailable"));
        }
        var hs = new AuthHandshake
        {
            MyNonce = AuthProtocol.NewNonce(),
            DeadlineMillis = _clock.NowMillis() + _authTimeoutMs,
        };
        // Atomic: a responder-side creation on the read task may race us.
        // The loser joins the winner so exactly one hello goes on the wire.
        if (!_handshakes.TryAdd(connection.Id, hs))
            return _handshakes[connection.Id].Future.Task;
        PublishSender(connection, SenderPhase.AUTHENTICATING, null);
        if (!SendHello(connection, hs, me))
        {
            FailHandshake(connection, hs, "send failed");
            return hs.Future.Task;
        }
        StartWatchdog(connection, hs);
        return hs.Future.Task;
    }

    public bool IsAuthenticated(ControlConnection connection) =>
        _verified.ContainsKey(connection.Id);

    public VerifiedPeer? VerifiedPeer(ControlConnection connection) =>
        _verified.TryGetValue(connection.Id, out var peer) ? peer : null;

    // Submits a receiver-displayed code over this connection and awaits the
    // PAIR_RESPONSE (one outstanding per connection; concurrent attempts get
    // Failed("busy")). Returns Accepted / Rejected(reason) / Failed.
    public async Task<PairSubmitOutcome> SenderSubmitCodeAsync(
        ControlConnection connection, string code, int timeoutMs = 10000,
        CancellationToken ct = default)
    {
        string normalized = AuthUiState.NormalizeInput(code);
        if (normalized.Length != AppConfig.TransactionCodeDigits)
            return new PairSubmitOutcome.Failed("code must be 6 digits");
        DeviceIdentity me;
        try
        {
            me = _store.Identity();
        }
        catch
        {
            return new PairSubmitOutcome.Failed("local identity unavailable");
        }
        var future = new TaskCompletionSource<PairSubmitOutcome>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_pairPending.TryAdd(connection.Id, future))
            return new PairSubmitOutcome.Failed("busy");
        PublishSender(connection, SenderPhase.SUBMITTING, null);
        try
        {
            var request = new LocalDropMessage(
                MessageType.PAIR_REQUEST,
                ProtocolCodec.NewMessageId(),
                null, null, null,
                new TypedPayload.PairRequest(new PairRequestPayload(me.DeviceId, _local.DeviceName, normalized)));
            if (!await connection.SendAsync(request, ct))
            {
                _pairPending.TryRemove(connection.Id, out _);
                PublishSender(connection, SenderPhase.FAILED, "send failed");
                return new PairSubmitOutcome.Failed("send failed");
            }
            try
            {
                using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeoutCts.CancelAfter(timeoutMs);
                return await future.Task.WaitAsync(timeoutCts.Token);
            }
            catch
            {
                _pairPending.TryRemove(connection.Id, out _);
                PublishSender(connection, SenderPhase.FAILED, "no response");
                return new PairSubmitOutcome.Failed("no response");
            }
        }
        finally
        {
            _pairPending.TryRemove(connection.Id, out _);
        }
    }

    // ---- IControlEvents (wrapping) ----

    public void OnMessage(ControlConnection connection, LocalDropMessage message)
    {
        switch (message.Type)
        {
            case MessageType.AUTH_REQUEST:
                HandleAuthRequest(connection, message);
                break;
            case MessageType.AUTH_RESPONSE:
                HandleAuthResponse(connection, message);
                break;
            case MessageType.PAIR_RESPONSE:
                if (_pairPending.TryRemove(connection.Id, out var pending))
                {
                    var payload = (message.Payload as TypedPayload.PairResponse)?.V;
                    if (payload is not null && payload.Accepted)
                    {
                        PublishSender(connection, SenderPhase.AUTHENTICATED, null);
                        PublishSender(connection, SenderPhase.AUTHORIZED, null);
                        pending.TrySetResult(new PairSubmitOutcome.Accepted());
                    }
                    else
                    {
                        string reason = payload?.Reason ?? "rejected";
                        PublishSender(connection, SenderPhase.FAILED, reason);
                        pending.TrySetResult(new PairSubmitOutcome.Rejected(reason));
                    }
                }
                else
                {
                    Inner?.OnMessage(connection, message);
                }
                break;
            default:
                Inner?.OnMessage(connection, message);
                break;
        }
    }

    public void OnClosed(ControlConnection connection, CloseReason reason, string detail)
    {
        if (_handshakes.TryRemove(connection.Id, out var hs))
            hs.Future.TrySetResult(new AuthOutcome.Failed("connection closed"));
        if (_pairPending.TryRemove(connection.Id, out var pending))
            pending.TrySetResult(new PairSubmitOutcome.Failed("connection closed"));
        _verified.TryRemove(connection.Id, out _);
        try
        {
            Inner?.OnClosed(connection, reason, detail);
        }
        catch
        {
            // Listener bugs must not propagate.
        }
    }

    // ---- handshake internals ----

    private bool SendHello(ControlConnection connection, AuthHandshake hs, DeviceIdentity me)
    {
        var hello = new LocalDropMessage(
            MessageType.AUTH_REQUEST,
            ProtocolCodec.NewMessageId(),
            null, null, null,
            new TypedPayload.AuthRequest(new AuthRequestPayload(
                me.DeviceId, AppConfig.AuthMethodId,
                AuthProtocol.HelloToken(me.DeviceId, me.PublicKeySpki, hs.MyNonce))));
        // Synchronous from possibly the read loop: same pattern as the M04
        // implementation (no sync context on the read path).
        bool sent;
        try
        {
            sent = connection.SendAsync(hello).GetAwaiter().GetResult();
        }
        catch
        {
            sent = false;
        }
        if (sent) hs.HelloSent = true;
        return sent;
    }

    private bool SendProof(ControlConnection connection, AuthHandshake hs, DeviceIdentity me)
    {
        var peer = hs.PeerHello;
        if (peer is null) return false;
        byte[] transcript = AuthProtocol.BuildTranscript(
            AppConfig.AuthMethodId, me.DeviceId, hs.MyNonce, peer.DeviceId, peer.Nonce);
        byte[] signature;
        try
        {
            signature = _store.Sign(transcript);
        }
        catch
        {
            return false;
        }
        var proof = new LocalDropMessage(
            MessageType.AUTH_REQUEST,
            ProtocolCodec.NewMessageId(),
            null, null, null,
            new TypedPayload.AuthRequest(new AuthRequestPayload(
                me.DeviceId, AppConfig.AuthMethodId, AuthProtocol.ProofToken(signature))));
        bool sent;
        try
        {
            sent = connection.SendAsync(proof).GetAwaiter().GetResult();
        }
        catch
        {
            sent = false;
        }
        if (sent) hs.Phase = AuthPhase.PROOF_SENT;
        return sent;
    }

    private void HandleAuthRequest(ControlConnection connection, LocalDropMessage message)
    {
        if (message.Payload is not TypedPayload.AuthRequest req)
        {
            RejectAndClose(connection, "malformed auth request");
            return;
        }
        if (req.V.Method != AppConfig.AuthMethodId)
        {
            RejectAndClose(connection, "unknown auth method");
            return;
        }
        if (!_handshakes.TryGetValue(connection.Id, out var hs))
        {
            // Responder path: peer initiated, we have no state yet. Atomic
            // for the same race in reverse (a test-thread BeginAuthAsync may
            // win; the loser joins the winner with no side effects).
            var created = new AuthHandshake
            {
                MyNonce = AuthProtocol.NewNonce(),
                DeadlineMillis = _clock.NowMillis() + _authTimeoutMs,
            };
            if (!_handshakes.TryAdd(connection.Id, created))
            {
                hs = _handshakes[connection.Id];
            }
            else
            {
                hs = created;
                PublishSender(connection, SenderPhase.AUTHENTICATING, null);
                StartWatchdog(connection, hs);
            }
        }
        lock (hs)
        {
            if (hs.Phase is AuthPhase.DONE or AuthPhase.FAILED) return;
            string? step = PeekStep(req.V.Token);
            if (step == AuthProtocol.StepHello)
            {
                if (hs.PeerHello is not null)
                {
                    RejectAndClose(connection, "duplicate hello");
                    return;
                }
                DeviceIdentity? me = LocalIdentityOrReject(connection, hs);
                if (me is null) return;
                if (AuthProtocol.ParseHello(req.V.Token) is not
                    Protocol.PayloadResult<AuthProtocol.ParsedHello>.Ok helloOk)
                {
                    RejectAndClose(connection, "bad hello token");
                    return;
                }
                if (!CheckBinding(helloOk.Value))
                {
                    RejectAndClose(connection, "deviceId binding mismatch");
                    return;
                }
                hs.PeerHello = helloOk.Value;
                hs.Phase = AuthPhase.PEER_KNOWN;
                if (!hs.HelloSent && !SendHello(connection, hs, me))
                {
                    RejectAndClose(connection, "send failed");
                    return;
                }
                if (!SendProof(connection, hs, me))
                {
                    RejectAndClose(connection, "send failed");
                }
            }
            else if (step == AuthProtocol.StepProof)
            {
                var peer = hs.PeerHello;
                if (peer is null)
                {
                    RejectAndClose(connection, "proof before hello");
                    return;
                }
                if (AuthProtocol.ParseProof(req.V.Token) is not
                    Protocol.PayloadResult<AuthProtocol.ParsedProof>.Ok proofOk)
                {
                    RejectAndClose(connection, "bad proof token");
                    return;
                }
                DeviceIdentity? me = LocalIdentityOrReject(connection, hs);
                if (me is null) return;
                byte[] transcript = AuthProtocol.BuildTranscript(
                    AppConfig.AuthMethodId, me.DeviceId, hs.MyNonce,
                    peer.DeviceId, peer.Nonce);
                if (!DeviceIdentityCrypto.VerifySignature(transcript, proofOk.Value.RawSignature, peer.PublicKeySpki))
                {
                    RejectAndClose(connection, "bad proof signature");
                    return;
                }
                var response = new LocalDropMessage(
                    MessageType.AUTH_RESPONSE,
                    ProtocolCodec.NewMessageId(),
                    null, null, null,
                    new TypedPayload.AuthResponse(new AuthResponsePayload(me.DeviceId, true, null)));
                bool sent;
                try
                {
                    sent = connection.SendAsync(response).GetAwaiter().GetResult();
                }
                catch
                {
                    sent = false;
                }
                if (!sent)
                {
                    RejectAndClose(connection, "send failed");
                    return;
                }
                hs.Phase = AuthPhase.AWAIT_RESULT;
            }
            else
            {
                RejectAndClose(connection, "unexpected auth step");
            }
        }
    }

    private void HandleAuthResponse(ControlConnection connection, LocalDropMessage message)
    {
        if (!_handshakes.TryGetValue(connection.Id, out var hs)) return; // stray: ignore, fail-closed
        lock (hs)
        {
            if (hs.Phase != AuthPhase.AWAIT_RESULT)
            {
                RejectAndClose(connection, "unexpected auth result");
                return;
            }
            if (message.Payload is not TypedPayload.AuthResponse resp || !resp.V.Accepted)
            {
                RejectAndClose(connection, "peer rejected authentication");
                return;
            }
            var peer = hs.PeerHello;
            if (peer is null)
            {
                RejectAndClose(connection, "no peer hello");
                return;
            }
            hs.Phase = AuthPhase.DONE;
            var verifiedPeer = new VerifiedPeer(peer.DeviceId, peer.PublicKeySpki);
            _verified[connection.Id] = verifiedPeer;
            PublishSender(connection, SenderPhase.AUTHENTICATED, null);
            _logger.AuthEvent(true, peer.DeviceId, "mutual authentication complete");
            try
            {
                _events?.OnAuthenticated(connection, verifiedPeer);
            }
            catch
            {
                // Listener bugs must not corrupt auth state.
            }
            hs.Future.TrySetResult(new AuthOutcome.Authenticated(verifiedPeer));
        }
    }

    private DeviceIdentity? LocalIdentityOrReject(ControlConnection connection, AuthHandshake hs)
    {
        try
        {
            return _store.Identity();
        }
        catch
        {
            RejectAndClose(connection, "local failure");
            return null;
        }
    }

    private static bool CheckBinding(AuthProtocol.ParsedHello hello)
    {
        try
        {
            return DeviceIdentityCrypto.DeriveDeviceId(hello.PublicKeySpki) == hello.DeviceId;
        }
        catch
        {
            return false;
        }
    }

    private static string? PeekStep(string token)
    {
        if (Protocol.JsonCanonical.Parse(token) is Protocol.JsonParseResult.Ok ok &&
            ok.Value is Protocol.JsonValue.Obj obj)
        {
            foreach (var kv in obj.Entries)
            {
                if (kv.Key == "step" && kv.Value is Protocol.JsonValue.Str s) return s.Value;
            }
        }
        return null;
    }

    private void RejectAndClose(ControlConnection connection, string logDetail)
    {
        _handshakes.TryGetValue(connection.Id, out var hs);
        string? peerId = null;
        if (hs is not null)
        {
            lock (hs)
            {
                if (hs.Phase is AuthPhase.DONE or AuthPhase.FAILED) return;
                hs.Phase = AuthPhase.FAILED;
                peerId = hs.PeerHello?.DeviceId;
            }
        }
        _logger.AuthEvent(false, peerId, logDetail);
        PublishSender(connection, SenderPhase.FAILED, "Authentication failed.");
        try
        {
            var me = _store.Identity();
            var reject = new LocalDropMessage(
                MessageType.AUTH_RESPONSE,
                ProtocolCodec.NewMessageId(),
                null, null, null,
                new TypedPayload.AuthResponse(new AuthResponsePayload(me.DeviceId, false, "Authentication failed.")));
            connection.SendAsync(reject).GetAwaiter().GetResult();
        }
        catch
        {
            // Best effort; the disconnect below is the real enforcement.
        }
        _handshakes.TryRemove(connection.Id, out _);
        hs?.Future.TrySetResult(new AuthOutcome.Failed("Authentication failed."));
        try
        {
            _events?.OnAuthFailed(connection, "Authentication failed.");
        }
        catch
        {
        }
        try
        {
            connection.DisconnectAsync().GetAwaiter().GetResult();
        }
        catch
        {
        }
    }

    private bool FailHandshake(ControlConnection connection, AuthHandshake hs, string logDetail)
    {
        lock (hs)
        {
            if (hs.Phase is AuthPhase.DONE or AuthPhase.FAILED) return false;
            hs.Phase = AuthPhase.FAILED;
        }
        _handshakes.TryRemove(connection.Id, out _);
        _logger.AuthEvent(false, hs.PeerHello?.DeviceId, logDetail);
        PublishSender(connection, SenderPhase.FAILED, "Authentication failed.");
        hs.Future.TrySetResult(new AuthOutcome.Failed("Authentication failed."));
        try
        {
            _events?.OnAuthFailed(connection, "Authentication failed.");
        }
        catch
        {
        }
        try
        {
            connection.DisconnectAsync().GetAwaiter().GetResult();
        }
        catch
        {
        }
        return false;
    }

    private void StartWatchdog(ControlConnection connection, AuthHandshake hs)
    {
        _ = Task.Run(async () =>
        {
            long remaining = hs.DeadlineMillis - _clock.NowMillis();
            if (remaining > 0)
            {
                try
                {
                    await Task.Delay(TimeSpan.FromMilliseconds(remaining));
                }
                catch
                {
                    return;
                }
            }
            lock (hs)
            {
                if (hs.Phase is AuthPhase.DONE or AuthPhase.FAILED) return;
            }
            FailHandshake(connection, hs, "auth timeout");
        });
    }

    private void PublishSender(ControlConnection connection, SenderPhase phase, string? error)
    {
        try
        {
            SenderObserver?.Invoke(connection.Id,
                new SenderAuthUiState(connection.PeerDeviceId, phase, error));
        }
        catch
        {
        }
    }
}
