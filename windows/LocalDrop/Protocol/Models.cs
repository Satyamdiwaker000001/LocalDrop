// M02: payload models — data contracts only (shared-protocol/messages.md).
// No behavior: no networking, no pairing/authentication execution, no
// transfer execution. Every parse validates shape + bounds; failures map to
// INVALID_PROTOCOL. Serialization emits canonical key order.
using System.Text.RegularExpressions;

namespace LocalDrop.Protocol;

internal static class Shapes
{
    internal static readonly Regex Sha256 = new("^[0-9a-f]{64}$", RegexOptions.Compiled);

    internal static string? Sha256Field(FieldReader r, string key)
    {
        var s = r.GetString(key, 64, 64);
        if (s is not null && !Sha256.IsMatch(s)) return null;
        return s;
    }
}

// ---- capability / hello ----

public sealed record HelloPayload(
    string DeviceId,
    string DeviceName,
    string DeviceType,
    long ControlPort,
    List<string> Capabilities)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("deviceId", new JsonValue.Str(DeviceId)),
        new("deviceName", new JsonValue.Str(DeviceName)),
        new("deviceType", new JsonValue.Str(DeviceType)),
        new("controlPort", new JsonValue.Num(ControlPort)),
        new("capabilities", new JsonValue.Arr(Capabilities.ConvertAll(c => (JsonValue)new JsonValue.Str(c)))),
    ];

    public static PayloadResult<HelloPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "HELLO");
        var deviceId = r.GetUuid("deviceId");
        var deviceName = r.GetString("deviceName", 1, Protocol.MaxDeviceName);
        var deviceType = r.GetString("deviceType", 1, 16);
        if (deviceType is not null && deviceType != "android" && deviceType != "windows")
            return new PayloadResult<HelloPayload>.Fail("HELLO.deviceType: must be \"android\" or \"windows\"");
        var controlPort = r.GetInt("controlPort", 0, 65535);
        var capabilities = r.GetStringList("capabilities", Protocol.MaxCapabilities, 1, Protocol.MaxCapabilityLen);
        var err = r.Error();
        if (err is not null || deviceId is null || deviceName is null ||
            deviceType is null || controlPort is null || capabilities is null)
            return new PayloadResult<HelloPayload>.Fail(err ?? "HELLO: invalid payload");
        return new PayloadResult<HelloPayload>.Ok(
            new HelloPayload(deviceId, deviceName, deviceType, controlPort.Value, capabilities));
    }
}

public sealed record HelloAckPayload(
    string DeviceId,
    string DeviceName,
    string DeviceType,
    bool Accepted)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("deviceId", new JsonValue.Str(DeviceId)),
        new("deviceName", new JsonValue.Str(DeviceName)),
        new("deviceType", new JsonValue.Str(DeviceType)),
        new("accepted", new JsonValue.Bool(Accepted)),
    ];

    public static PayloadResult<HelloAckPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "HELLO_ACK");
        var deviceId = r.GetUuid("deviceId");
        var deviceName = r.GetString("deviceName", 1, Protocol.MaxDeviceName);
        var deviceType = r.GetString("deviceType", 1, 16);
        if (deviceType is not null && deviceType != "android" && deviceType != "windows")
            return new PayloadResult<HelloAckPayload>.Fail(
                "HELLO_ACK.deviceType: must be \"android\" or \"windows\"");
        var accepted = r.GetBool("accepted");
        var err = r.Error();
        if (err is not null || deviceId is null || deviceName is null ||
            deviceType is null || accepted is null)
            return new PayloadResult<HelloAckPayload>.Fail(err ?? "HELLO_ACK: invalid payload");
        return new PayloadResult<HelloAckPayload>.Ok(
            new HelloAckPayload(deviceId, deviceName, deviceType, accepted.Value));
    }
}

// ---- pairing (structure only; approval is M05) ----

public sealed record PairRequestPayload(string DeviceId, string DeviceName, string DisplayCode)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("deviceId", new JsonValue.Str(DeviceId)),
        new("deviceName", new JsonValue.Str(DeviceName)),
        new("displayCode", new JsonValue.Str(DisplayCode)),
    ];

    public static PayloadResult<PairRequestPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "PAIR_REQUEST");
        var deviceId = r.GetUuid("deviceId");
        var deviceName = r.GetString("deviceName", 1, Protocol.MaxDeviceName);
        var displayCode = r.GetString("displayCode", 1, Protocol.MaxDisplayCode);
        var err = r.Error();
        if (err is not null || deviceId is null || deviceName is null || displayCode is null)
            return new PayloadResult<PairRequestPayload>.Fail(err ?? "PAIR_REQUEST: invalid payload");
        return new PayloadResult<PairRequestPayload>.Ok(
            new PairRequestPayload(deviceId, deviceName, displayCode));
    }
}

public sealed record PairResponsePayload(string DeviceId, bool Accepted, string? Reason)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap()
    {
        var m = new List<KeyValuePair<string, JsonValue>>
        {
            new("deviceId", new JsonValue.Str(DeviceId)),
            new("accepted", new JsonValue.Bool(Accepted)),
        };
        if (Reason is not null) m.Add(new("reason", new JsonValue.Str(Reason)));
        return m;
    }

    public static PayloadResult<PairResponsePayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "PAIR_RESPONSE");
        var deviceId = r.GetUuid("deviceId");
        var accepted = r.GetBool("accepted");
        var reason = r.Contains("reason") ? r.GetString("reason", 0, Protocol.MaxReasonLen) : null;
        var err = r.Error();
        if (err is not null || deviceId is null || accepted is null)
            return new PayloadResult<PairResponsePayload>.Fail(err ?? "PAIR_RESPONSE: invalid payload");
        if (r.Contains("reason") && reason is null)
            return new PayloadResult<PairResponsePayload>.Fail(err ?? "PAIR_RESPONSE: invalid reason");
        return new PayloadResult<PairResponsePayload>.Ok(
            new PairResponsePayload(deviceId, accepted.Value, reason));
    }
}

// ---- authentication (structure only; handshake is M05) ----

public sealed record AuthRequestPayload(string DeviceId, string Method, string Token)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("deviceId", new JsonValue.Str(DeviceId)),
        new("method", new JsonValue.Str(Method)),
        new("token", new JsonValue.Str(Token)),
    ];

    public static PayloadResult<AuthRequestPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "AUTH_REQUEST");
        var deviceId = r.GetUuid("deviceId");
        var method = r.GetString("method", 1, Protocol.MaxAuthMethodLen);
        var token = r.GetString("token", 1, Protocol.MaxAuthTokenLen);
        var err = r.Error();
        if (err is not null || deviceId is null || method is null || token is null)
            return new PayloadResult<AuthRequestPayload>.Fail(err ?? "AUTH_REQUEST: invalid payload");
        return new PayloadResult<AuthRequestPayload>.Ok(new AuthRequestPayload(deviceId, method, token));
    }
}

public sealed record AuthResponsePayload(string DeviceId, bool Accepted, string? Reason)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap()
    {
        var m = new List<KeyValuePair<string, JsonValue>>
        {
            new("deviceId", new JsonValue.Str(DeviceId)),
            new("accepted", new JsonValue.Bool(Accepted)),
        };
        if (Reason is not null) m.Add(new("reason", new JsonValue.Str(Reason)));
        return m;
    }

    public static PayloadResult<AuthResponsePayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "AUTH_RESPONSE");
        var deviceId = r.GetUuid("deviceId");
        var accepted = r.GetBool("accepted");
        var reason = r.Contains("reason") ? r.GetString("reason", 0, Protocol.MaxReasonLen) : null;
        var err = r.Error();
        if (err is not null || deviceId is null || accepted is null)
            return new PayloadResult<AuthResponsePayload>.Fail(err ?? "AUTH_RESPONSE: invalid payload");
        if (r.Contains("reason") && reason is null)
            return new PayloadResult<AuthResponsePayload>.Fail(err ?? "AUTH_RESPONSE: invalid reason");
        return new PayloadResult<AuthResponsePayload>.Ok(
            new AuthResponsePayload(deviceId, accepted.Value, reason));
    }
}

// ---- transfer metadata (contract only; transfer engine is M06+) ----

public sealed record TransferMetadata(
    string TransferId,
    string SenderDeviceId,
    string ReceiverDeviceId,
    string FileId,
    string FileName,
    long FileSize,
    long CheckpointSize,
    string? Sha256Hex,
    long ResumeOffset)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap()
    {
        var m = new List<KeyValuePair<string, JsonValue>>
        {
            new("transferId", new JsonValue.Str(TransferId)),
            new("senderDeviceId", new JsonValue.Str(SenderDeviceId)),
            new("receiverDeviceId", new JsonValue.Str(ReceiverDeviceId)),
            new("fileId", new JsonValue.Str(FileId)),
            new("fileName", new JsonValue.Str(FileName)),
            new("fileSize", new JsonValue.Num(FileSize)),
            new("checkpointSize", new JsonValue.Num(CheckpointSize)),
        };
        if (Sha256Hex is not null) m.Add(new("sha256Hex", new JsonValue.Str(Sha256Hex)));
        m.Add(new("resumeOffset", new JsonValue.Num(ResumeOffset)));
        return m;
    }

    public static PayloadResult<TransferMetadata> Parse(
        IReadOnlyList<KeyValuePair<string, JsonValue>> map, string ctx = "TRANSFER_REQUEST")
    {
        var r = new FieldReader(map, ctx);
        var transferId = r.GetUuid("transferId");
        var senderDeviceId = r.GetUuid("senderDeviceId");
        var receiverDeviceId = r.GetUuid("receiverDeviceId");
        var fileId = r.GetString("fileId", 1, Protocol.MaxFileIdLen);
        var fileName = r.GetString("fileName", 1, Protocol.MaxFileName);
        if (fileName is not null && (fileName.Contains('/') || fileName.Contains('\\') ||
                fileName == "." || fileName == ".."))
            return new PayloadResult<TransferMetadata>.Fail($"{ctx}.fileName: must be a base file name");
        var fileSize = r.GetInt("fileSize", 0, long.MaxValue);
        var checkpointSize = r.Contains("checkpointSize")
            ? r.GetInt("checkpointSize", 1, Protocol.MaxCheckpointSize)
            : Protocol.DefaultCheckpointBytes;
        string? sha256Hex = null;
        if (r.Contains("sha256Hex"))
        {
            sha256Hex = Shapes.Sha256Field(r, "sha256Hex");
            if (sha256Hex is null || !Shapes.Sha256.IsMatch(sha256Hex))
                return new PayloadResult<TransferMetadata>.Fail(
                    r.Error() ?? $"{ctx}.sha256Hex: must be 64 lowercase hex chars");
        }
        var resumeOffset = r.GetInt("resumeOffset", 0, long.MaxValue);
        var err = r.Error();
        if (err is not null || transferId is null || senderDeviceId is null ||
            receiverDeviceId is null || fileId is null || fileName is null ||
            fileSize is null || checkpointSize is null || resumeOffset is null)
            return new PayloadResult<TransferMetadata>.Fail(err ?? $"{ctx}: invalid payload");
        if (resumeOffset.Value > fileSize.Value)
            return new PayloadResult<TransferMetadata>.Fail($"{ctx}.resumeOffset: exceeds fileSize");
        return new PayloadResult<TransferMetadata>.Ok(new TransferMetadata(
            transferId, senderDeviceId, receiverDeviceId, fileId, fileName,
            fileSize.Value, checkpointSize.Value, sha256Hex, resumeOffset.Value));
    }
}

// ---- transfer request / response ----

public sealed record TransferRequestPayload(TransferMetadata Metadata)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() => Metadata.ToJsonMap();

    public static PayloadResult<TransferRequestPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var m = TransferMetadata.Parse(map);
        if (m is PayloadResult<TransferMetadata>.Ok ok)
            return new PayloadResult<TransferRequestPayload>.Ok(new TransferRequestPayload(ok.Value));
        return new PayloadResult<TransferRequestPayload>.Fail(((PayloadResult<TransferMetadata>.Fail)m).Detail);
    }
}

public sealed record TransferAcceptPayload(string TransferId, long ResumeOffset)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("transferId", new JsonValue.Str(TransferId)),
        new("resumeOffset", new JsonValue.Num(ResumeOffset)),
    ];

    public static PayloadResult<TransferAcceptPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "TRANSFER_ACCEPT");
        var transferId = r.GetUuid("transferId");
        var resumeOffset = r.GetInt("resumeOffset", 0, long.MaxValue);
        var err = r.Error();
        if (err is not null || transferId is null || resumeOffset is null)
            return new PayloadResult<TransferAcceptPayload>.Fail(err ?? "TRANSFER_ACCEPT: invalid payload");
        return new PayloadResult<TransferAcceptPayload>.Ok(
            new TransferAcceptPayload(transferId, resumeOffset.Value));
    }
}

public sealed record TransferRejectPayload(string TransferId, ProtocolError Error)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("transferId", new JsonValue.Str(TransferId)),
        new("error", new JsonValue.Obj(Error.ToJsonMap())),
    ];

    public static PayloadResult<TransferRejectPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "TRANSFER_REJECT");
        var transferId = r.GetUuid("transferId");
        var errObj = r.GetObj("error");
        var err = r.Error();
        if (err is not null || transferId is null || errObj is null)
            return new PayloadResult<TransferRejectPayload>.Fail(err ?? "TRANSFER_REJECT: invalid payload");
        var e = ProtocolError.Parse(errObj);
        return e is PayloadResult<ProtocolError>.Ok ok
            ? new PayloadResult<TransferRejectPayload>.Ok(new TransferRejectPayload(transferId, ok.Value))
            : new PayloadResult<TransferRejectPayload>.Fail(((PayloadResult<ProtocolError>.Fail)e).Detail);
    }
}

// ---- progress signals (contract only) ----

public sealed record TransferStartPayload(string TransferId, long Offset)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("transferId", new JsonValue.Str(TransferId)),
        new("offset", new JsonValue.Num(Offset)),
    ];

    public static PayloadResult<TransferStartPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "TRANSFER_START");
        var transferId = r.GetUuid("transferId");
        var offset = r.GetInt("offset", 0, long.MaxValue);
        var err = r.Error();
        if (err is not null || transferId is null || offset is null)
            return new PayloadResult<TransferStartPayload>.Fail(err ?? "TRANSFER_START: invalid payload");
        return new PayloadResult<TransferStartPayload>.Ok(new TransferStartPayload(transferId, offset.Value));
    }
}

public sealed record TransferProgressPayload(string TransferId, long TransferredBytes, long TotalBytes)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("transferId", new JsonValue.Str(TransferId)),
        new("transferredBytes", new JsonValue.Num(TransferredBytes)),
        new("totalBytes", new JsonValue.Num(TotalBytes)),
    ];

    public static PayloadResult<TransferProgressPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "TRANSFER_PROGRESS");
        var transferId = r.GetUuid("transferId");
        var transferredBytes = r.GetInt("transferredBytes", 0, long.MaxValue);
        var totalBytes = r.GetInt("totalBytes", 0, long.MaxValue);
        var err = r.Error();
        if (err is not null || transferId is null || transferredBytes is null || totalBytes is null)
            return new PayloadResult<TransferProgressPayload>.Fail(err ?? "TRANSFER_PROGRESS: invalid payload");
        if (transferredBytes.Value > totalBytes.Value)
            return new PayloadResult<TransferProgressPayload>.Fail(
                "TRANSFER_PROGRESS.transferredBytes: exceeds totalBytes");
        return new PayloadResult<TransferProgressPayload>.Ok(
            new TransferProgressPayload(transferId, transferredBytes.Value, totalBytes.Value));
    }
}

// Shared by PAUSE and RESUME (identical shape, validated under own name).
public sealed record TransferIdOnlyPayload(string TransferId)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("transferId", new JsonValue.Str(TransferId)),
    ];

    public static PayloadResult<TransferIdOnlyPayload> Parse(
        IReadOnlyList<KeyValuePair<string, JsonValue>> map, string ctx)
    {
        var r = new FieldReader(map, ctx);
        var transferId = r.GetUuid("transferId");
        var err = r.Error();
        if (err is not null || transferId is null)
            return new PayloadResult<TransferIdOnlyPayload>.Fail(err ?? $"{ctx}: invalid payload");
        return new PayloadResult<TransferIdOnlyPayload>.Ok(new TransferIdOnlyPayload(transferId));
    }
}

public sealed record CancelPayload(string TransferId, string? Reason)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap()
    {
        var m = new List<KeyValuePair<string, JsonValue>>
        {
            new("transferId", new JsonValue.Str(TransferId)),
        };
        if (Reason is not null) m.Add(new("reason", new JsonValue.Str(Reason)));
        return m;
    }

    public static PayloadResult<CancelPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "CANCEL");
        var transferId = r.GetUuid("transferId");
        var reason = r.Contains("reason") ? r.GetString("reason", 0, Protocol.MaxReasonLen) : null;
        var err = r.Error();
        if (err is not null || transferId is null)
            return new PayloadResult<CancelPayload>.Fail(err ?? "CANCEL: invalid payload");
        if (r.Contains("reason") && reason is null)
            return new PayloadResult<CancelPayload>.Fail(err ?? "CANCEL: invalid reason");
        return new PayloadResult<CancelPayload>.Ok(new CancelPayload(transferId, reason));
    }
}

// ---- resume negotiation (contract only; durable behavior is M10) ----

public sealed record ResumeQueryPayload(string TransferId, string FileId, long FileSize)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("transferId", new JsonValue.Str(TransferId)),
        new("fileId", new JsonValue.Str(FileId)),
        new("fileSize", new JsonValue.Num(FileSize)),
    ];

    public static PayloadResult<ResumeQueryPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "RESUME_QUERY");
        var transferId = r.GetUuid("transferId");
        var fileId = r.GetString("fileId", 1, Protocol.MaxFileIdLen);
        var fileSize = r.GetInt("fileSize", 0, long.MaxValue);
        var err = r.Error();
        if (err is not null || transferId is null || fileId is null || fileSize is null)
            return new PayloadResult<ResumeQueryPayload>.Fail(err ?? "RESUME_QUERY: invalid payload");
        return new PayloadResult<ResumeQueryPayload>.Ok(
            new ResumeQueryPayload(transferId, fileId, fileSize.Value));
    }
}

public sealed record ResumeResponsePayload(string TransferId, long ConfirmedOffset, long FileSize)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("transferId", new JsonValue.Str(TransferId)),
        new("confirmedOffset", new JsonValue.Num(ConfirmedOffset)),
        new("fileSize", new JsonValue.Num(FileSize)),
    ];

    public static PayloadResult<ResumeResponsePayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "RESUME_RESPONSE");
        var transferId = r.GetUuid("transferId");
        var confirmedOffset = r.GetInt("confirmedOffset", 0, long.MaxValue);
        var fileSize = r.GetInt("fileSize", 0, long.MaxValue);
        var err = r.Error();
        if (err is not null || transferId is null || confirmedOffset is null || fileSize is null)
            return new PayloadResult<ResumeResponsePayload>.Fail(err ?? "RESUME_RESPONSE: invalid payload");
        return new PayloadResult<ResumeResponsePayload>.Ok(
            new ResumeResponsePayload(transferId, confirmedOffset.Value, fileSize.Value));
    }
}

// ---- completion / verification (contract only; enforcement is M11) ----

public sealed record TransferCompletePayload(string TransferId, long FileSize, string Sha256Hex)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("transferId", new JsonValue.Str(TransferId)),
        new("fileSize", new JsonValue.Num(FileSize)),
        new("sha256Hex", new JsonValue.Str(Sha256Hex)),
    ];

    public static PayloadResult<TransferCompletePayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "TRANSFER_COMPLETE");
        var transferId = r.GetUuid("transferId");
        var fileSize = r.GetInt("fileSize", 0, long.MaxValue);
        var sha = Shapes.Sha256Field(r, "sha256Hex");
        var err = r.Error();
        if (err is not null || transferId is null || fileSize is null ||
            sha is null || !Shapes.Sha256.IsMatch(sha))
            return new PayloadResult<TransferCompletePayload>.Fail(
                err ?? "TRANSFER_COMPLETE.sha256Hex: must be 64 lowercase hex chars");
        return new PayloadResult<TransferCompletePayload>.Ok(
            new TransferCompletePayload(transferId, fileSize.Value, sha));
    }
}

public sealed record VerifyRequestPayload(string TransferId, string Sha256Hex)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("transferId", new JsonValue.Str(TransferId)),
        new("sha256Hex", new JsonValue.Str(Sha256Hex)),
    ];

    public static PayloadResult<VerifyRequestPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "VERIFY_REQUEST");
        var transferId = r.GetUuid("transferId");
        var sha = Shapes.Sha256Field(r, "sha256Hex");
        var err = r.Error();
        if (err is not null || transferId is null || sha is null || !Shapes.Sha256.IsMatch(sha))
            return new PayloadResult<VerifyRequestPayload>.Fail(
                err ?? "VERIFY_REQUEST.sha256Hex: must be 64 lowercase hex chars");
        return new PayloadResult<VerifyRequestPayload>.Ok(new VerifyRequestPayload(transferId, sha));
    }
}

public sealed record VerifyResultPayload(string TransferId, bool Match, string Sha256Hex)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap() =>
    [
        new("transferId", new JsonValue.Str(TransferId)),
        new("match", new JsonValue.Bool(Match)),
        new("sha256Hex", new JsonValue.Str(Sha256Hex)),
    ];

    public static PayloadResult<VerifyResultPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "VERIFY_RESULT");
        var transferId = r.GetUuid("transferId");
        var match = r.GetBool("match");
        var sha = Shapes.Sha256Field(r, "sha256Hex");
        var err = r.Error();
        if (err is not null || transferId is null || match is null ||
            sha is null || !Shapes.Sha256.IsMatch(sha))
            return new PayloadResult<VerifyResultPayload>.Fail(
                err ?? "VERIFY_RESULT.sha256Hex: must be 64 lowercase hex chars");
        return new PayloadResult<VerifyResultPayload>.Ok(
            new VerifyResultPayload(transferId, match.Value, sha));
    }
}

// ---- ERROR ----

public sealed record ErrorPayload(ProtocolError Error, string? RefMessageId)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap()
    {
        var m = new List<KeyValuePair<string, JsonValue>>
        {
            new("code", new JsonValue.Str(ErrorCodes.ToWire(Error.Code))),
            new("message", new JsonValue.Str(Error.Message)),
        };
        if (RefMessageId is not null) m.Add(new("refMessageId", new JsonValue.Str(RefMessageId)));
        return m;
    }

    public static PayloadResult<ErrorPayload> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var r = new FieldReader(map, "ERROR");
        var codeWire = r.GetString("code", 1, 64);
        ErrorCode code = default;
        bool codeOk = codeWire is not null && ErrorCodes.TryParse(codeWire, out code);
        if (codeWire is not null && !codeOk)
            return new PayloadResult<ErrorPayload>.Fail($"ERROR.code: unknown error code '{codeWire}'");
        var message = r.GetString("message", 0, Protocol.MaxErrorMessageLen);
        var refr = r.Contains("refMessageId") ? r.GetUuid("refMessageId") : null;
        var err = r.Error();
        if (err is not null || !codeOk || message is null)
            return new PayloadResult<ErrorPayload>.Fail(err ?? "ERROR: invalid payload");
        if (r.Contains("refMessageId") && refr is null)
            return new PayloadResult<ErrorPayload>.Fail(err ?? "ERROR: invalid refMessageId");
        return new PayloadResult<ErrorPayload>.Ok(
            new ErrorPayload(new ProtocolError(code, message), refr));
    }
}

// ---- discovery record (PROTOCOL v1.0 §3; behavior is M03) ----

public sealed record DiscoveryRecord(
    string DeviceId,
    string DeviceName,
    string DeviceType,
    long ControlPort,
    List<string> Capabilities)
{
    public HelloPayload ToHelloPayload() =>
        new(DeviceId, DeviceName, DeviceType, ControlPort, Capabilities);

    public static PayloadResult<DiscoveryRecord> Parse(IReadOnlyList<KeyValuePair<string, JsonValue>> map)
    {
        var h = HelloPayload.Parse(map);
        return h is PayloadResult<HelloPayload>.Ok ok
            ? new PayloadResult<DiscoveryRecord>.Ok(new DiscoveryRecord(
                ok.Value.DeviceId, ok.Value.DeviceName, ok.Value.DeviceType,
                ok.Value.ControlPort, ok.Value.Capabilities))
            : new PayloadResult<DiscoveryRecord>.Fail(((PayloadResult<HelloPayload>.Fail)h).Detail);
    }
}
