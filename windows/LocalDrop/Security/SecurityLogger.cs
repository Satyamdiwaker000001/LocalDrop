// M05: security event logging. Callers pass ONLY safe values (transaction
// ids, peer device ids, event types, reasons, timestamps). Private keys,
// nonces, signatures, public-key bytes, and full transaction codes MUST
// NEVER reach any method here; test 21 asserts their absence.
namespace LocalDrop.Security;

public interface ISecurityLogger
{
    void AuthEvent(bool succeeded, string? peerDeviceId, string detail);
    void TransactionEvent(string type, string transactionId, string? peerDeviceId, string detail);
}

// Test sink: records lines for secret-absence assertions.
public sealed class RecordingSecurityLogger : ISecurityLogger
{
    private readonly object _gate = new();
    private readonly List<string> _entries = [];

    public void AuthEvent(bool succeeded, string? peerDeviceId, string detail)
    {
        lock (_gate) _entries.Add($"auth ok={succeeded} peer={peerDeviceId ?? "-"} {detail}");
    }

    public void TransactionEvent(string type, string transactionId, string? peerDeviceId, string detail)
    {
        lock (_gate) _entries.Add($"txn type={type} id={transactionId} peer={peerDeviceId ?? "-"} {detail}");
    }

    public List<string> Snapshot()
    {
        lock (_gate) return [.. _entries];
    }
}
