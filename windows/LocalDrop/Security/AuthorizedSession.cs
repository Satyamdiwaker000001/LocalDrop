// M05: the authenticated + authorized session M06 will consume.
// Holds ids and timestamps ONLY: never codes, keys, nonces, or signatures.
using System.Collections.Concurrent;
using LocalDrop.Discovery;

namespace LocalDrop.Security;

public sealed record AuthorizedSession(
    string PeerDeviceId,
    string TransactionId,
    long AuthorizedAtMillis);

public sealed class AuthorizedSessionStore
{
    private readonly ConcurrentDictionary<(string Peer, string Txn), AuthorizedSession> _sessions = new();
    private readonly IDiscoveryClock _clock;

    public AuthorizedSessionStore(IDiscoveryClock? clock = null) =>
        _clock = clock ?? SystemDiscoveryClock.Instance;

    public AuthorizedSession Authorize(string peerDeviceId, string transactionId)
    {
        var session = new AuthorizedSession(peerDeviceId, transactionId, _clock.NowMillis());
        _sessions[(peerDeviceId, transactionId)] = session;
        return session;
    }

    public bool IsAuthorized(string peerDeviceId, string transactionId) =>
        _sessions.ContainsKey((peerDeviceId, transactionId));

    public List<AuthorizedSession> SessionsFor(string peerDeviceId) =>
        _sessions.Values.Where(s => s.PeerDeviceId == peerDeviceId).ToList();

    public List<AuthorizedSession> Snapshot() => [.. _sessions.Values];

    public bool Revoke(string peerDeviceId, string transactionId) =>
        _sessions.TryRemove((peerDeviceId, transactionId), out _);

    public void Clear() => _sessions.Clear();

    public int Count => _sessions.Count;
}
