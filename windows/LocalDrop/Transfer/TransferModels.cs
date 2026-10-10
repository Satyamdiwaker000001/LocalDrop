// M06 transfer models. State wires reuse the exact M01 TRANSFER_STATES
// strings (UI/UX Spec §13); "completed" means TRANSPORT-complete only —
// verification (and any verified state) belongs to M11. All sizes/offsets
// are long (ADR 12); no int appears in any size path.
using LocalDrop.Protocol;

namespace LocalDrop.Transfer;

public enum TransferEngineState
{
    CONNECTING,
    WAITING_ACCEPTANCE,
    TRANSFERRING,
    TRANSPORT_COMPLETE,
    FAILED,
    CANCELLED,
}

public static class TransferEngineStates
{
    public static string ToWire(TransferEngineState state) => state switch
    {
        TransferEngineState.CONNECTING => "connecting",
        TransferEngineState.WAITING_ACCEPTANCE => "waiting acceptance",
        TransferEngineState.TRANSFERRING => "transferring",
        TransferEngineState.TRANSPORT_COMPLETE => "completed",
        TransferEngineState.FAILED => "failed",
        TransferEngineState.CANCELLED => "cancelled",
        _ => throw new ArgumentOutOfRangeException(nameof(state)),
    };
}

public sealed record TransferProgress(
    string TransferId,
    long TotalBytes,
    long TransferredBytes,
    double Percent,
    long ElapsedMs,
    double? AverageBytesPerSec,
    double? RecentBytesPerSec,
    long? EtaMs,
    TransferEngineState State)
{
    public static TransferProgress Compute(
        string transferId,
        long totalBytes,
        long transferredBytes,
        long elapsedMs,
        double? recentBytesPerSec,
        TransferEngineState state)
    {
        double percent = totalBytes <= 0L ? 100.0
            : Math.Clamp(transferredBytes * 100.0 / totalBytes, 0.0, 100.0);
        double? average = elapsedMs > 0 ? transferredBytes * 1000.0 / elapsedMs : null;
        long? eta = recentBytesPerSec is > 0.0 && totalBytes > transferredBytes
            ? (long)((totalBytes - transferredBytes) * 1000.0 / recentBytesPerSec)
            : null;
        return new TransferProgress(
            transferId, totalBytes, Math.Min(transferredBytes, totalBytes),
            percent, elapsedMs, average, recentBytesPerSec, eta, state);
    }
}

public abstract record TransferOutcome
{
    public sealed record TransportComplete(
        string TransferId,
        long BytesTransferred,
        long ElapsedMs,
        double? AverageBytesPerSec,
        TransferMetrics Metrics) : TransferOutcome;

    public sealed record Failed(string? TransferId, ErrorCode Code, string Detail) : TransferOutcome;
    public sealed record Cancelled(string? TransferId, long BytesTransferred) : TransferOutcome;
}

public sealed record TransferMetrics(
    long ElapsedMs,
    long BytesTransferred,
    double? AverageBytesPerSec,
    long ReadNanosTotal,
    long WriteNanosTotal,
    long StallCount,
    List<string> Adaptations,
    int FinalBlockSizeBytes);

// Maps local failures to the frozen M02 error vocabulary (errors.md).
// No new codes; messages carry no secrets.
public static class TransferErrors
{
    public static (ErrorCode Code, string Detail) SourceUnavailable(string detail) =>
        (ErrorCode.FILE_NOT_FOUND, $"Source unavailable: {detail}");
    public static (ErrorCode Code, string Detail) SourceReadFailed(string detail) =>
        (ErrorCode.INTERNAL_ERROR, $"Source read failed: {detail}");
    public static (ErrorCode Code, string Detail) ConnectionFailed(string detail) =>
        (ErrorCode.NETWORK_LOST, $"Data connection failed: {detail}");
    public static (ErrorCode Code, string Detail) ConnectionLost(string detail) =>
        (ErrorCode.NETWORK_LOST, $"Data connection lost: {detail}");
    public static (ErrorCode Code, string Detail) DestinationUnavailable(string detail) =>
        (ErrorCode.INTERNAL_ERROR, $"Destination unavailable: {detail}");
    public static (ErrorCode Code, string Detail) DestinationWriteFailed(string detail) =>
        (ErrorCode.INTERNAL_ERROR, $"Destination write failed: {detail}");
    public static (ErrorCode Code, string Detail) NoSpace(string detail) =>
        (ErrorCode.NOT_ENOUGH_STORAGE, $"Not enough storage: {detail}");
    public static (ErrorCode Code, string Detail) ProtocolViolation(string detail) =>
        (ErrorCode.INVALID_PROTOCOL, $"Protocol violation: {detail}");
    public static (ErrorCode Code, string Detail) Cancelled() =>
        (ErrorCode.CANCELLED, "Cancelled.");
    public static (ErrorCode Code, string Detail) TimedOut(string detail) =>
        (ErrorCode.TIMEOUT, $"Timed out: {detail}");
    public static (ErrorCode Code, string Detail) Rejected(string detail) =>
        (ErrorCode.TRANSFER_REJECTED, detail);
    public static (ErrorCode Code, string Detail) Internal(string detail) =>
        (ErrorCode.INTERNAL_ERROR, $"Internal failure: {detail}");

    // Best-effort classification of filesystem write failures.
    public static (ErrorCode Code, string Detail) ClassifyWriteFailure(Exception exception)
    {
        string message = exception.Message.ToLowerInvariant();
        return message.Contains("no space") || message.Contains("enospc") || message.Contains("disk full")
            ? NoSpace(exception.Message)
            : DestinationWriteFailed(exception.Message);
    }
}

// Extracts the transferId from any transfer-scoped M02 message (null for
// non-transfer types). Used by mailboxes to route control-plane responses.
public static class TransferIds
{
    public static string? Of(LocalDropMessage message) => message.Payload switch
    {
        TypedPayload.TransferRequest p => p.V.Metadata.TransferId,
        TypedPayload.TransferAccept p => p.V.TransferId,
        TypedPayload.TransferReject p => p.V.TransferId,
        TypedPayload.TransferStart p => p.V.TransferId,
        TypedPayload.TransferProgress p => p.V.TransferId,
        TypedPayload.Pause p => p.V.TransferId,
        TypedPayload.Resume p => p.V.TransferId,
        TypedPayload.Cancel p => p.V.TransferId,
        TypedPayload.ResumeQuery p => p.V.TransferId,
        TypedPayload.ResumeResponse p => p.V.TransferId,
        TypedPayload.TransferComplete p => p.V.TransferId,
        TypedPayload.VerifyRequest p => p.V.TransferId,
        TypedPayload.VerifyResult p => p.V.TransferId,
        _ => null,
    };
}
