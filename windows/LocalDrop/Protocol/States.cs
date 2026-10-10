// M02: protocol/domain state vocabularies — values only.
// NO transition engine, NO timers, NO workers in M02 (later milestones own
// the engine; M02 owns the words). Parsing rejects any other value.
namespace LocalDrop.Protocol;

// Transfer states (UI/UX Spec §13). Wire strings match AppConfig (M01).
public enum TransferState
{
    AVAILABLE,
    CONNECTING,
    WAITING_ACCEPTANCE,
    TRANSFERRING,
    PAUSED,
    RECONNECTING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

public static class TransferStates
{
    private static readonly Dictionary<TransferState, string> Wire = new()
    {
        [TransferState.AVAILABLE] = "available",
        [TransferState.CONNECTING] = "connecting",
        [TransferState.WAITING_ACCEPTANCE] = "waiting acceptance",
        [TransferState.TRANSFERRING] = "transferring",
        [TransferState.PAUSED] = "paused",
        [TransferState.RECONNECTING] = "reconnecting",
        [TransferState.VERIFYING] = "verifying",
        [TransferState.COMPLETED] = "completed",
        [TransferState.FAILED] = "failed",
        [TransferState.CANCELLED] = "cancelled",
    };

    public static string ToWire(TransferState state) => Wire[state];

    public static bool TryParse(string? wire, out TransferState state)
    {
        state = default;
        if (wire is null) return false;
        foreach (var kv in Wire)
        {
            if (kv.Value == wire)
            {
                state = kv.Key;
                return true;
            }
        }
        return false;
    }
}

// Session states (PROTOCOL v1.0 §4).
public enum SessionState
{
    DISCOVERED,
    CONNECTING,
    NEGOTIATING,
    AUTHENTICATING,
    READY,
    TRANSFER_REQUESTED,
    ACCEPTED,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    REJECTED,
    CANCELLED,
    FAILED,
    RECONNECTING,
    RESUMING,
}

public static class SessionStates
{
    public static string ToWire(SessionState state) => state.ToString();

    public static bool TryParse(string? wire, out SessionState state)
    {
        state = default;
        if (wire is null) return false;
        foreach (SessionState candidate in Enum.GetValues<SessionState>())
        {
            if (candidate.ToString() == wire)
            {
                state = candidate;
                return true;
            }
        }
        return false;
    }
}
