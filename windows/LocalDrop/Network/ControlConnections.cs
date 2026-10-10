// M04: tracks the independent control connections of one instance
// (one entry per peer). A failed connection never affects the others:
// each entry owns its socket/task and closes on its own. The usual key
// is the peer deviceId (known after handshake); ControlServer uses the
// connection id until then. No business logic here.
using System.Collections.Concurrent;

namespace LocalDrop.Network;

public sealed class ControlConnections
{
    private readonly ConcurrentDictionary<string, ControlConnection> _entries = new();

    public void Add(string key, ControlConnection connection) => _entries[key] = connection;

    public ControlConnection? Get(string key) =>
        _entries.TryGetValue(key, out var c) ? c : null;

    public bool Remove(string key) => _entries.TryRemove(key, out _);

    public void Rekey(string oldKey, string newKey)
    {
        if (_entries.TryRemove(oldKey, out var existing)) _entries[newKey] = existing;
    }

    public IReadOnlyCollection<string> Keys => [.. _entries.Keys];

    public List<ControlConnection> Snapshot() => [.. _entries.Values];

    public int Count => _entries.Count;

    public async Task CloseAllAsync()
    {
        var snapshot = Snapshot();
        _entries.Clear();
        foreach (var connection in snapshot)
        {
            try
            {
                await connection.DisconnectAsync();
            }
            catch
            {
                // Best effort per connection; keep closing the rest.
            }
        }
    }
}
