// M02 §16: valid serialization, deserialization, round-trip, correlation,
// cross-platform vectors. Offline only: no network, no device, no server.
namespace LocalDrop.Protocol.Tests;

public sealed class ProtocolCodecTests
{
    [Fact]
    public void ValidHello_SerializesToCanonicalBytes()
    {
        Assert.Equal(Vectors.Hello, ProtocolCodec.Encode(Vectors.HelloMessage()));
    }

    [Fact]
    public void ValidHello_DeserializesToExpectedObject()
    {
        var outcome = ProtocolCodec.Decode(Vectors.Hello);
        var success = Assert.IsType<DecodeOutcome.Success>(outcome);
        // Canonical re-encode equality (record payloads hold List<string>,
        // which is reference-compared; canonical bytes are the contract).
        Assert.Equal(Vectors.Hello, ProtocolCodec.Encode(success.Message));
        var hello = Assert.IsType<TypedPayload.Hello>(success.Message.Payload);
        Assert.Equal("Pixel 8", hello.V.DeviceName);
        Assert.Equal(["transfer"], hello.V.Capabilities);
    }

    public static IEnumerable<object[]> AllMessages()
    {
        const string tid = Vectors.Transfer;
        yield return new object[] { Vectors.HelloMessage() };
        yield return new object[] { new LocalDropMessage(MessageType.HELLO_ACK, Vectors.RequestMsg, null, null, null,
            new TypedPayload.HelloAck(new HelloAckPayload(Vectors.WindowsDevice, "PC", "windows", true))) };
        yield return new object[] { new LocalDropMessage(MessageType.PAIR_REQUEST, Vectors.RequestMsg, null, null, null,
            new TypedPayload.PairRequest(new PairRequestPayload(Vectors.AndroidDevice, "Pixel 8", "482913"))) };
        yield return new object[] { new LocalDropMessage(MessageType.PAIR_RESPONSE, Vectors.RequestMsg, Vectors.RequestMsg, null, null,
            new TypedPayload.PairResponse(new PairResponsePayload(Vectors.WindowsDevice, true, null))) };
        yield return new object[] { new LocalDropMessage(MessageType.AUTH_REQUEST, Vectors.RequestMsg, null, Vectors.Session, null,
            new TypedPayload.AuthRequest(new AuthRequestPayload(Vectors.AndroidDevice, "pair-code", "opaque-token"))) };
        yield return new object[] { new LocalDropMessage(MessageType.AUTH_RESPONSE, Vectors.RequestMsg, Vectors.RequestMsg, Vectors.Session, null,
            new TypedPayload.AuthResponse(new AuthResponsePayload(Vectors.WindowsDevice, false, "denied"))) };
        yield return new object[] { Vectors.TransferRequestMessage() };
        yield return new object[] { Vectors.TransferAcceptMessage() };
        yield return new object[] { new LocalDropMessage(MessageType.TRANSFER_REJECT, Vectors.RequestMsg, Vectors.RequestMsg, Vectors.Session, tid,
            new TypedPayload.TransferReject(new TransferRejectPayload(tid, new ProtocolError(ErrorCode.TRANSFER_REJECTED, "no")))) };
        yield return new object[] { new LocalDropMessage(MessageType.TRANSFER_START, Vectors.RequestMsg, null, Vectors.Session, tid,
            new TypedPayload.TransferStart(new TransferStartPayload(tid, 0))) };
        yield return new object[] { new LocalDropMessage(MessageType.TRANSFER_PROGRESS, Vectors.RequestMsg, null, Vectors.Session, tid,
            new TypedPayload.TransferProgress(new TransferProgressPayload(tid, 512, 1024))) };
        yield return new object[] { new LocalDropMessage(MessageType.PAUSE, Vectors.RequestMsg, null, Vectors.Session, tid,
            new TypedPayload.Pause(new TransferIdOnlyPayload(tid))) };
        yield return new object[] { new LocalDropMessage(MessageType.RESUME, Vectors.RequestMsg, null, Vectors.Session, tid,
            new TypedPayload.Resume(new TransferIdOnlyPayload(tid))) };
        yield return new object[] { new LocalDropMessage(MessageType.CANCEL, Vectors.RequestMsg, null, Vectors.Session, tid,
            new TypedPayload.Cancel(new CancelPayload(tid, "user"))) };
        yield return new object[] { new LocalDropMessage(MessageType.RESUME_QUERY, Vectors.RequestMsg, null, Vectors.Session, tid,
            new TypedPayload.ResumeQuery(new ResumeQueryPayload(tid, "file-001", 1024))) };
        yield return new object[] { new LocalDropMessage(MessageType.RESUME_RESPONSE, Vectors.RequestMsg, Vectors.RequestMsg, Vectors.Session, tid,
            new TypedPayload.ResumeResponse(new ResumeResponsePayload(tid, 512, 1024))) };
        yield return new object[] { new LocalDropMessage(MessageType.TRANSFER_COMPLETE, Vectors.RequestMsg, null, Vectors.Session, tid,
            new TypedPayload.TransferComplete(new TransferCompletePayload(tid, 1024, Vectors.Sha256Empty))) };
        yield return new object[] { new LocalDropMessage(MessageType.VERIFY_REQUEST, Vectors.RequestMsg, null, Vectors.Session, tid,
            new TypedPayload.VerifyRequest(new VerifyRequestPayload(tid, Vectors.Sha256Empty))) };
        yield return new object[] { new LocalDropMessage(MessageType.VERIFY_RESULT, Vectors.RequestMsg, Vectors.RequestMsg, Vectors.Session, tid,
            new TypedPayload.VerifyResult(new VerifyResultPayload(tid, true, Vectors.Sha256Empty))) };
        yield return new object[] { Vectors.ErrorMessage() };
    }

    [Theory]
    [MemberData(nameof(AllMessages))]
    public void RoundTrip_PreservesEveryMessageType(LocalDropMessage message)
    {
        var outcome = ProtocolCodec.Decode(ProtocolCodec.Encode(message));
        var success = Assert.IsType<DecodeOutcome.Success>(outcome);
        // Canonical re-encode equality (see ValidHello test note).
        Assert.Equal(ProtocolCodec.Encode(message), ProtocolCodec.Encode(success.Message));
    }

    [Fact]
    public void RoundTrip_CoversAll20Types()
    {
        Assert.Equal(20, AllMessages().Count());
    }

    [Fact]
    public void CorrelationId_EchoesRequestMessageId()
    {
        var success = Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.Decode(Vectors.TransferAccept));
        Assert.Equal(Vectors.RequestMsg, success.Message.CorrelationId);
    }

    [Fact]
    public void MalformedJson_IsRejected()
    {
        var failure = Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode("{\"protocol\":"));
        Assert.Equal(ErrorCode.INVALID_PROTOCOL, failure.Code);
    }

    [Fact]
    public void NonObjectTopLevel_IsRejected()
    {
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode("[1,2]"));
    }

    [Fact]
    public void MissingPayload_IsRejected()
    {
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"PAUSE\"," +
            $"\"messageId\":\"{Vectors.RequestMsg}\"}}"));
    }

    [Fact]
    public void InvalidProtocolVersion_IsRejected()
    {
        var bad = Vectors.Pause.Replace("\"protocolVersion\":1", "\"protocolVersion\":2");
        var failure = Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode(bad));
        Assert.Equal(ErrorCode.INVALID_PROTOCOL, failure.Code);
    }

    [Fact]
    public void UnknownMessageType_IsRejected()
    {
        var bad = Vectors.Pause.Replace("\"type\":\"PAUSE\"", "\"type\":\"TELEPORT\"");
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode(bad));
    }

    [Fact]
    public void UnknownEnvelopeFields_AreIgnored()
    {
        var withExtra = Vectors.Pause.Replace("\"payload\":", "\"futureField\":\"x\",\"payload\":");
        var success = Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.Decode(withExtra));
        Assert.Equal(Vectors.PauseMessage(), success.Message);
    }

    [Fact]
    public void EnvelopePayloadTransferIdMismatch_IsRejected()
    {
        var bad = Vectors.Pause.Replace(
            "\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\",\"payload\"",
            "\"transferId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"payload\"");
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode(bad));
    }

    [Fact]
    public void InvalidUtf8Bytes_AreRejected()
    {
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.DecodeUtf8([0xFF, 0xFE]));
    }

    [Fact]
    public void OversizeInput_IsRejectedWithoutParsing()
    {
        var big = "\"" + new string('x', Protocol.MaxFrameBytes + 1) + "\"";
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode(big));
    }
}
