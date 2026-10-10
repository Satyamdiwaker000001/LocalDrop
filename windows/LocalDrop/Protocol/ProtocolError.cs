// M02: structured protocol error model (PROTOCOL v1.0 §10).
// Exactly these 12 codes; unknown codes are rejected, never passed through.
// Errors are serializable VALUES (see ERROR payload), not thrown exceptions.
namespace LocalDrop.Protocol;

public enum ErrorCode
{
    INVALID_PROTOCOL,
    AUTH_FAILED,
    PAIRING_REQUIRED,
    TRANSFER_REJECTED,
    NOT_ENOUGH_STORAGE,
    FILE_NOT_FOUND,
    NETWORK_LOST,
    RESUME_UNAVAILABLE,
    CHECKSUM_MISMATCH,
    CANCELLED,
    TIMEOUT,
    INTERNAL_ERROR,
}

public static class ErrorCodes
{
    public static string ToWire(ErrorCode code) => code.ToString();

    public static bool TryParse(string? wire, out ErrorCode code)
    {
        code = default;
        if (wire is null) return false;
        foreach (ErrorCode candidate in Enum.GetValues<ErrorCode>())
        {
            if (candidate.ToString() == wire)
            {
                code = candidate;
                return true;
            }
        }
        return false;
    }
}

// Structured error value. Message must not contain secrets (SECURITY v1.0 §5).
public sealed record ProtocolError(ErrorCode Code, string Message)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("code", new JsonValue.Str(ErrorCodes.ToWire(Code))),
        new("message", new JsonValue.Str(Message)),
    ];

    public static PayloadResult<ProtocolError> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "error");
        var codeWire = r.GetString("code", 1, 64);
        ErrorCode code = default;
        bool codeOk = codeWire is not null && ErrorCodes.TryParse(codeWire, out code);
        if (codeWire is not null && !codeOk)
            return new PayloadResult<ProtocolError>.Fail($"error.code: unknown error code '{codeWire}'");
        var message = r.GetString("message", 0, Protocol.MaxErrorMessageLen);
        var err = r.Error();
        if (err is not null || !codeOk || message is null)
            return new PayloadResult<ProtocolError>.Fail(err ?? "error: invalid error object");
        return new PayloadResult<ProtocolError>.Ok(new ProtocolError(code, message));
    }
}
