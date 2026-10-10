// M06 control-plane mailbox: terminal listener in the M04/M05 chain that
// collects transfer-scoped messages per transferId so request/response
// exchanges can be awaited with timeouts. Order-preserving: a wait that
// skips non-matching messages leaves the queue exactly as found minus the
// consumed match. Forwards nothing (terminal); callers use await/poll.
using LocalDrop.Network;
using LocalDrop.Protocol;
using System.Collections.Concurrent;

namespace LocalDrop.Transfer;

public sealed class TransferMailbox : IControlEvents
{
    private readonly ConcurrentDictionary<string, LinkedList<LocalDropMessage>> _queues = new();
    private readonly LinkedList<LocalDropMessage> _unscoped = new();
    private readonly object _unscopedGate = new();

    // Per-transfer control-message cap. A malicious peer must not grow
    // listener memory without bound; beyond the cap the OLDEST message is
    // dropped (fail direction is safe: awaited responses time out into
    // clean failure, never corruption). Normal flows hold a handful of
    // messages; the cap is never hit honestly. The unscoped queue (messages
    // without a transferId, e.g. ERROR) carries the same bound.
    public const int MaxQueuedPerTransfer = 128;
    public const int MaxQueuedUnscoped = 128;

    private LinkedList<LocalDropMessage> QueueFor(string transferId) =>
        _queues.GetOrAdd(transferId, _ => new LinkedList<LocalDropMessage>());

    public void OnMessage(ControlConnection connection, LocalDropMessage message)
    {
        string? id = TransferIds.Of(message);
        if (id is not null)
        {
            var queue = QueueFor(id);
            lock (queue)
            {
                queue.AddLast(message);
                while (queue.Count > MaxQueuedPerTransfer) queue.RemoveFirst();
                Monitor.PulseAll(queue);
            }
        }
        else
        {
            lock (_unscopedGate)
            {
                _unscoped.AddLast(message);
                while (_unscoped.Count > MaxQueuedUnscoped) _unscoped.RemoveFirst();
                Monitor.PulseAll(_unscopedGate);
            }
        }
    }

    public void OnClosed(ControlConnection connection, CloseReason reason, string detail)
    {
        // No-op: await loops poll with deadlines and observe closure via the
        // engine's own socket handling. Queues are drained per transfer.
    }

    // Waits for the next message for `transferId` whose type is in `types`,
    // preserving the order of all other queued messages. Null on timeout.
    public LocalDropMessage? AwaitResponse(
        string transferId, ISet<MessageType> types, int timeoutMs)
    {
        var queue = QueueFor(transferId);
        long deadline = Environment.TickCount64 + timeoutMs;
        lock (queue)
        {
            while (true)
            {
                var node = queue.First;
                while (node is not null)
                {
                    var next = node.Next;
                    if (types.Contains(node.Value.Type))
                    {
                        queue.Remove(node);
                        return node.Value;
                    }
                    node = next;
                }
                long remaining = deadline - Environment.TickCount64;
                if (remaining <= 0) return null;
                Monitor.Wait(queue, (int)Math.Min(remaining, int.MaxValue));
            }
        }
    }

    public int PendingCount(string transferId)
    {
        if (!_queues.TryGetValue(transferId, out var queue)) return 0;
        lock (queue) return queue.Count;
    }

    public List<LocalDropMessage> Drain(string transferId)
    {
        if (!_queues.TryGetValue(transferId, out var queue)) return [];
        lock (queue)
        {
            var outList = queue.ToList();
            queue.Clear();
            return outList;
        }
    }

    public void Forget(string transferId) => _queues.TryRemove(transferId, out _);
}
