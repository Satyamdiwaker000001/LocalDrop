// M05: receiver transaction authorization codes
// (shared-protocol/authentication.md §6). In-memory only: codes are
// short-lived single-use secrets by design; a crash loses them and the
// receiver creates a new transaction. No database, no file I/O.
using System.Collections.Concurrent;
using System.Security.Cryptography;
using LocalDrop.Core;
using LocalDrop.Discovery;

namespace LocalDrop.Security;

public enum CodeState { ACTIVE, EXPIRED, CONSUMED, CANCELLED }

public sealed record ReceiverTransaction(
    string Id,
    string Code, // 6 digits, e.g. "583921". Never logged in full.
    long CreatedAtMillis,
    CodeState State,
    string? AuthorizedPeerDeviceId = null);

public abstract record VerifyOutcome
{
    public sealed record Authorized(string TransactionId) : VerifyOutcome;
    public sealed record Rejected(CodeReject Reason) : VerifyOutcome;
}

public enum CodeReject { UNKNOWN_CODE, EXPIRED, CONSUMED, CANCELLED }

public sealed class TransactionManager
{
    private readonly ConcurrentDictionary<string, ReceiverTransaction> _transactions = new();
    private readonly IDiscoveryClock _clock;
    private readonly long _codeLifetimeMs;
    private readonly ISecurityLogger? _logger;

    // Thin-UI hook: the receiver screen observes these snapshots; the
    // manager remains the single owner of transaction state.
    public Action<ReceiverTransactionUiState>? ReceiverObserver { get; set; }

    public TransactionManager(
        IDiscoveryClock? clock = null,
        long codeLifetimeMs = AppConfig.TransactionCodeLifetimeMs,
        ISecurityLogger? logger = null)
    {
        _clock = clock ?? SystemDiscoveryClock.Instance;
        _codeLifetimeMs = codeLifetimeMs;
        _logger = logger;
    }

    // Creates a transaction with a fresh unique 6-digit code.
    // Display form ("583 921") is a UI concern (see AuthUiState.FormatCode).
    public ReceiverTransaction CreateTransaction()
    {
        for (int attempt = 0; attempt < 20; attempt++)
        {
            string code = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
            if (_transactions.Values.Any(t => t.Code == code && t.State == CodeState.ACTIVE))
                continue;
            var txn = new ReceiverTransaction(
                Guid.NewGuid().ToString("D"), code, _clock.NowMillis(), CodeState.ACTIVE);
            _transactions[txn.Id] = txn;
            _logger?.TransactionEvent("created", txn.Id, null, "receiver transaction opened");
            Publish(txn, ReceiverPhase.CODE_ACTIVE, null);
            return txn;
        }
        throw new InvalidOperationException("could not mint a unique transaction code");
    }

    // Validates a submitted code. Wrong submissions do NOT consume the
    // correct code. Expiry is lazy: ACTIVE -> EXPIRED on observation.
    public VerifyOutcome VerifyByCode(string submittedCode, string peerDeviceId)
    {
        string normalized = new(submittedCode.Where(char.IsDigit).ToArray());
        var txn = _transactions.Values.FirstOrDefault(t => t.Code == normalized);
        if (txn is null)
        {
            _logger?.TransactionEvent("rejected", "-", peerDeviceId, "unknown code");
            PublishUnknown();
            return new VerifyOutcome.Rejected(CodeReject.UNKNOWN_CODE);
        }
        long now = _clock.NowMillis();
        if (txn.State == CodeState.ACTIVE && now - txn.CreatedAtMillis >= _codeLifetimeMs)
        {
            txn = txn with { State = CodeState.EXPIRED };
            _transactions[txn.Id] = txn;
        }
        return txn.State switch
        {
            CodeState.ACTIVE => Authorize(txn, peerDeviceId),
            CodeState.EXPIRED => Reject(txn, peerDeviceId, CodeReject.EXPIRED, "expired code",
                ReceiverPhase.EXPIRED),
            CodeState.CONSUMED => Reject(txn, peerDeviceId, CodeReject.CONSUMED, "replay of consumed code",
                ReceiverPhase.INVALID),
            _ => Reject(txn, peerDeviceId, CodeReject.CANCELLED, "cancelled transaction",
                ReceiverPhase.INVALID),
        };
    }

    public bool Cancel(string transactionId)
    {
        if (!_transactions.TryGetValue(transactionId, out var txn)) return false;
        if (txn.State == CodeState.ACTIVE)
        {
            var cancelled = txn with { State = CodeState.CANCELLED };
            _transactions[transactionId] = cancelled;
            _logger?.TransactionEvent("cancelled", transactionId, null, "receiver cancelled");
            Publish(cancelled, ReceiverPhase.CANCELLED, null);
        }
        return true;
    }

    public ReceiverTransaction? Get(string transactionId) =>
        _transactions.TryGetValue(transactionId, out var txn) ? txn : null;

    // Visible ACTIVE transactions only (applies lazy expiry).
    public List<ReceiverTransaction> ActiveTransactions()
    {
        long now = _clock.NowMillis();
        var active = new List<ReceiverTransaction>();
        foreach (var txn in _transactions.Values)
        {
            if (txn.State == CodeState.ACTIVE && now - txn.CreatedAtMillis >= _codeLifetimeMs)
            {
                var expired = txn with { State = CodeState.EXPIRED };
                _transactions[txn.Id] = expired;
            }
            else if (txn.State == CodeState.ACTIVE)
            {
                active.Add(txn);
            }
        }
        return active;
    }

    public void Clear() => _transactions.Clear();

    private VerifyOutcome Authorize(ReceiverTransaction txn, string peerDeviceId)
    {
        _transactions[txn.Id] = txn with
        {
            State = CodeState.CONSUMED,
            AuthorizedPeerDeviceId = peerDeviceId,
        };
        _logger?.TransactionEvent("authorized", txn.Id, peerDeviceId, "code consumed");
        Publish(txn with { State = CodeState.CONSUMED, AuthorizedPeerDeviceId = peerDeviceId },
            ReceiverPhase.AUTHORIZED, peerDeviceId);
        return new VerifyOutcome.Authorized(txn.Id);
    }

    private VerifyOutcome Reject(
        ReceiverTransaction txn, string peerDeviceId, CodeReject reason, string detail,
        ReceiverPhase phase)
    {
        _logger?.TransactionEvent("rejected", txn.Id, peerDeviceId, detail);
        Publish(txn, phase, peerDeviceId);
        return new VerifyOutcome.Rejected(reason);
    }

    private void Publish(ReceiverTransaction txn, ReceiverPhase phase, string? peer)
    {
        try
        {
            ReceiverObserver?.Invoke(new ReceiverTransactionUiState(
                txn.Id,
                AuthUiState.FormatCode(txn.Code),
                phase,
                txn.CreatedAtMillis + _codeLifetimeMs,
                phase == ReceiverPhase.AUTHORIZED ? peer : null));
        }
        catch
        {
        }
    }

    private void PublishUnknown()
    {
        try
        {
            ReceiverObserver?.Invoke(new ReceiverTransactionUiState(
                null, "", ReceiverPhase.INVALID, 0L));
        }
        catch
        {
        }
    }
}
