// M02: exact wire message-type vocabulary (PROTOCOL v1.0 §5).
// Exactly these 20 values; anything else is rejected on receipt.
// Models are data contracts only — no feature behavior in M02.
namespace LocalDrop.Protocol;

public enum MessageType
{
    HELLO,
    HELLO_ACK,
    PAIR_REQUEST,
    PAIR_RESPONSE,
    AUTH_REQUEST,
    AUTH_RESPONSE,
    TRANSFER_REQUEST,
    TRANSFER_ACCEPT,
    TRANSFER_REJECT,
    TRANSFER_START,
    TRANSFER_PROGRESS,
    PAUSE,
    RESUME,
    CANCEL,
    RESUME_QUERY,
    RESUME_RESPONSE,
    TRANSFER_COMPLETE,
    VERIFY_REQUEST,
    VERIFY_RESULT,
    ERROR,
}

public static class MessageTypes
{
    public static string ToWire(MessageType type) => type.ToString();

    public static bool TryParse(string? wire, out MessageType type)
    {
        type = default;
        if (wire is null) return false;
        foreach (MessageType candidate in Enum.GetValues<MessageType>())
        {
            if (candidate.ToString() == wire)
            {
                type = candidate;
                return true;
            }
        }
        return false;
    }
}
