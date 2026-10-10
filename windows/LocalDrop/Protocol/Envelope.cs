// M02: common message envelope (shared-protocol/envelope.md).
// Canonical wire key order:
//   protocol, protocolVersion, type, messageId,
//   correlationId?, sessionId?, transferId?, payload
// Optional IDs are omitted (never null) when absent. Validation rejects
// malformed envelopes with INVALID_PROTOCOL — never silently, never by throw.
using System.Text;

namespace LocalDrop.Protocol;

public sealed record LocalDropMessage(
    MessageType Type,
    string MessageId,
    string? CorrelationId,
    string? SessionId,
    string? TransferId,
    TypedPayload Payload)
{
    public List<KeyValuePair<string, JsonValue>> ToJsonMap()
    {
        var m = new List<KeyValuePair<string, JsonValue>>
        {
            new("protocol", new JsonValue.Str(Protocol.ProtocolId)),
            new("protocolVersion", new JsonValue.Num(Protocol.ProtocolVersion)),
            new("type", new JsonValue.Str(MessageTypes.ToWire(Type))),
            new("messageId", new JsonValue.Str(MessageId)),
        };
        if (CorrelationId is not null) m.Add(new("correlationId", new JsonValue.Str(CorrelationId)));
        if (SessionId is not null) m.Add(new("sessionId", new JsonValue.Str(SessionId)));
        if (TransferId is not null) m.Add(new("transferId", new JsonValue.Str(TransferId)));
        m.Add(new("payload", new JsonValue.Obj(Payload.ToJsonMap())));
        return m;
    }
}

// Typed payload union: exactly one variant per MessageType.
public abstract record TypedPayload
{
    public abstract List<KeyValuePair<string, JsonValue>> ToJsonMap();

    public sealed record Hello(HelloPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record HelloAck(HelloAckPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record PairRequest(PairRequestPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record PairResponse(PairResponsePayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record AuthRequest(AuthRequestPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record AuthResponse(AuthResponsePayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record TransferRequest(TransferRequestPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record TransferAccept(TransferAcceptPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record TransferReject(TransferRejectPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record TransferStart(TransferStartPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record TransferProgress(TransferProgressPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record Pause(TransferIdOnlyPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record Resume(TransferIdOnlyPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record Cancel(CancelPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record ResumeQuery(ResumeQueryPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record ResumeResponse(ResumeResponsePayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record TransferComplete(TransferCompletePayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record VerifyRequest(VerifyRequestPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record VerifyResult(VerifyResultPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
    public sealed record Error(ErrorPayload V) : TypedPayload
    {
        public override List<KeyValuePair<string, JsonValue>> ToJsonMap() => V.ToJsonMap();
    }
}

public abstract record DecodeOutcome
{
    public sealed record Success(LocalDropMessage Message) : DecodeOutcome;
    public sealed record Failure(ErrorCode Code, string Detail) : DecodeOutcome;
}

public static class ProtocolCodec
{
    private static readonly Encoding StrictUtf8 = new UTF8Encoding(false, true);

    public static string NewMessageId() => Uuids.Random();

    // Deterministic canonical serialization (byte-identical with Android).
    public static string Encode(LocalDropMessage message) =>
        JsonCanonical.Serialize(new JsonValue.Obj(message.ToJsonMap()));

    public static byte[] EncodeUtf8(LocalDropMessage message) =>
        Encoding.UTF8.GetBytes(Encode(message));

    // Validates before processing; never throws on peer input.
    // Inputs larger than MaxFrameBytes are rejected without parsing
    // (bounds allocation from untrusted lengths).
    public static DecodeOutcome Decode(string text)
    {
        if (Encoding.UTF8.GetByteCount(text) > Protocol.MaxFrameBytes)
            return new DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, "message exceeds size limit");
        var parsed = JsonCanonical.Parse(text);
        if (parsed is JsonParseResult.Err pe)
            return new DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, $"malformed JSON: {pe.Detail}");
        var root = ((JsonParseResult.Ok)parsed).Value;
        if (root is not JsonValue.Obj obj)
            return new DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, "top level must be an object");

        var r = new FieldReader(obj.Entries, "envelope");
        var protocol = r.GetString("protocol", 1, 64);
        if (protocol is not null && protocol != Protocol.ProtocolId)
            return new DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, "envelope.protocol: unknown protocol");
        var version = r.GetInt("protocolVersion", 0, int.MaxValue);
        if (version is not null && version != Protocol.ProtocolVersion)
            return new DecodeOutcome.Failure(
                ErrorCode.INVALID_PROTOCOL, "envelope.protocolVersion: unsupported version");
        var typeWire = r.GetString("type", 1, 64);
        MessageType type = default;
        bool typeOk = typeWire is not null && MessageTypes.TryParse(typeWire, out type);
        if (typeWire is not null && !typeOk)
            return new DecodeOutcome.Failure(
                ErrorCode.INVALID_PROTOCOL, $"envelope.type: unknown message type '{typeWire}'");
        var messageId = r.GetUuid("messageId");
        var correlationId = r.Contains("correlationId") ? r.GetUuid("correlationId") : null;
        var sessionId = r.Contains("sessionId") ? r.GetUuid("sessionId") : null;
        var transferId = r.Contains("transferId") ? r.GetUuid("transferId") : null;
        var payloadMap = r.GetObj("payload");
        var err = r.Error();
        if (err is not null || protocol is null || version is null || !typeOk ||
            messageId is null || payloadMap is null)
            return new DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, err ?? "envelope: invalid message");
        if (r.Contains("correlationId") && correlationId is null)
            return new DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, err ?? "envelope: invalid correlationId");
        if (r.Contains("sessionId") && sessionId is null)
            return new DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, err ?? "envelope: invalid sessionId");
        if (r.Contains("transferId") && transferId is null)
            return new DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, err ?? "envelope: invalid transferId");

        PayloadResult<TypedPayload> payload = type switch
        {
            MessageType.HELLO => Wrap(HelloPayload.Parse(payloadMap), v => new TypedPayload.Hello(v)),
            MessageType.HELLO_ACK => Wrap(HelloAckPayload.Parse(payloadMap), v => new TypedPayload.HelloAck(v)),
            MessageType.PAIR_REQUEST => Wrap(PairRequestPayload.Parse(payloadMap), v => new TypedPayload.PairRequest(v)),
            MessageType.PAIR_RESPONSE => Wrap(PairResponsePayload.Parse(payloadMap), v => new TypedPayload.PairResponse(v)),
            MessageType.AUTH_REQUEST => Wrap(AuthRequestPayload.Parse(payloadMap), v => new TypedPayload.AuthRequest(v)),
            MessageType.AUTH_RESPONSE => Wrap(AuthResponsePayload.Parse(payloadMap), v => new TypedPayload.AuthResponse(v)),
            MessageType.TRANSFER_REQUEST => Wrap(TransferRequestPayload.Parse(payloadMap), v => new TypedPayload.TransferRequest(v)),
            MessageType.TRANSFER_ACCEPT => Wrap(TransferAcceptPayload.Parse(payloadMap), v => new TypedPayload.TransferAccept(v)),
            MessageType.TRANSFER_REJECT => Wrap(TransferRejectPayload.Parse(payloadMap), v => new TypedPayload.TransferReject(v)),
            MessageType.TRANSFER_START => Wrap(TransferStartPayload.Parse(payloadMap), v => new TypedPayload.TransferStart(v)),
            MessageType.TRANSFER_PROGRESS => Wrap(TransferProgressPayload.Parse(payloadMap), v => new TypedPayload.TransferProgress(v)),
            MessageType.PAUSE => Wrap(TransferIdOnlyPayload.Parse(payloadMap, "PAUSE"), v => new TypedPayload.Pause(v)),
            MessageType.RESUME => Wrap(TransferIdOnlyPayload.Parse(payloadMap, "RESUME"), v => new TypedPayload.Resume(v)),
            MessageType.CANCEL => Wrap(CancelPayload.Parse(payloadMap), v => new TypedPayload.Cancel(v)),
            MessageType.RESUME_QUERY => Wrap(ResumeQueryPayload.Parse(payloadMap), v => new TypedPayload.ResumeQuery(v)),
            MessageType.RESUME_RESPONSE => Wrap(ResumeResponsePayload.Parse(payloadMap), v => new TypedPayload.ResumeResponse(v)),
            MessageType.TRANSFER_COMPLETE => Wrap(TransferCompletePayload.Parse(payloadMap), v => new TypedPayload.TransferComplete(v)),
            MessageType.VERIFY_REQUEST => Wrap(VerifyRequestPayload.Parse(payloadMap), v => new TypedPayload.VerifyRequest(v)),
            MessageType.VERIFY_RESULT => Wrap(VerifyResultPayload.Parse(payloadMap), v => new TypedPayload.VerifyResult(v)),
            MessageType.ERROR => Wrap(ErrorPayload.Parse(payloadMap), v => new TypedPayload.Error(v)),
            _ => new PayloadResult<TypedPayload>.Fail("envelope.type: unknown message type"),
        };

        if (payload is PayloadResult<TypedPayload>.Fail fail)
            return new DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, fail.Detail);
        var ok = (PayloadResult<TypedPayload>.Ok)payload;
        // Envelope/payload transferId consistency (contract SHOULD rule).
        var inner = TransferIdOf(ok.Value);
        if (transferId is not null && inner is not null && transferId != inner)
            return new DecodeOutcome.Failure(
                ErrorCode.INVALID_PROTOCOL,
                "envelope.transferId does not match payload transferId");
        return new DecodeOutcome.Success(
            new LocalDropMessage(type, messageId, correlationId, sessionId, transferId, ok.Value));
    }

    public static DecodeOutcome DecodeUtf8(byte[] bytes)
    {
        string text;
        try
        {
            text = StrictUtf8.GetString(bytes);
        }
        catch (DecoderFallbackException)
        {
            return new DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, "bytes are not valid UTF-8");
        }
        return Decode(text);
    }

    private static PayloadResult<TypedPayload> Wrap<T>(
        PayloadResult<T> inner, Func<T, TypedPayload> wrap) =>
        inner is PayloadResult<T>.Ok ok
            ? new PayloadResult<TypedPayload>.Ok(wrap(ok.Value))
            : new PayloadResult<TypedPayload>.Fail(((PayloadResult<T>.Fail)inner).Detail);

    private static string? TransferIdOf(TypedPayload payload) => payload switch
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
