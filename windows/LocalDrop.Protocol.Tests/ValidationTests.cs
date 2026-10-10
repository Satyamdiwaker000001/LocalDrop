// M02 §16: invalid field values are rejected, never silently accepted.
namespace LocalDrop.Protocol.Tests;

public sealed class ValidationTests
{
    private static DecodeOutcome DecodeWithPayload(string payload, string type = "PAUSE") =>
        ProtocolCodec.Decode(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"" + type + "\"," +
            $"\"messageId\":\"{Vectors.RequestMsg}\"," +
            $"\"transferId\":\"{Vectors.Transfer}\",\"payload\":{payload}}}");

    [Fact]
    public void BadUuid_IsRejected()
    {
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"PAUSE\"," +
            "\"messageId\":\"not-a-uuid\"," +
            $"\"transferId\":\"{Vectors.Transfer}\"," +
            $"\"payload\":{{\"transferId\":\"{Vectors.Transfer}\"}}}}"));
    }

    [Fact]
    public void FileNameWithSeparator_IsRejected()
    {
        var payload = $"{{\"transferId\":\"{Vectors.Transfer}\"," +
            $"\"senderDeviceId\":\"{Vectors.AndroidDevice}\"," +
            $"\"receiverDeviceId\":\"{Vectors.WindowsDevice}\"," +
            "\"fileId\":\"f\",\"fileName\":\"../evil.zip\",\"fileSize\":10," +
            "\"resumeOffset\":0}";
        Assert.IsType<DecodeOutcome.Failure>(DecodeWithPayload(payload, "TRANSFER_REQUEST"));
    }

    [Fact]
    public void NegativeFileSize_IsRejected()
    {
        var payload = $"{{\"transferId\":\"{Vectors.Transfer}\"," +
            $"\"senderDeviceId\":\"{Vectors.AndroidDevice}\"," +
            $"\"receiverDeviceId\":\"{Vectors.WindowsDevice}\"," +
            "\"fileId\":\"f\",\"fileName\":\"a.zip\",\"fileSize\":-1,\"resumeOffset\":0}";
        Assert.IsType<DecodeOutcome.Failure>(DecodeWithPayload(payload, "TRANSFER_REQUEST"));
    }

    [Fact]
    public void ResumeOffsetBeyondFileSize_IsRejected()
    {
        var payload = $"{{\"transferId\":\"{Vectors.Transfer}\"," +
            $"\"senderDeviceId\":\"{Vectors.AndroidDevice}\"," +
            $"\"receiverDeviceId\":\"{Vectors.WindowsDevice}\"," +
            "\"fileId\":\"f\",\"fileName\":\"a.zip\",\"fileSize\":10,\"resumeOffset\":11}";
        Assert.IsType<DecodeOutcome.Failure>(DecodeWithPayload(payload, "TRANSFER_REQUEST"));
    }

    [Fact]
    public void UppercaseSha256_IsRejected()
    {
        var payload = $"{{\"transferId\":\"{Vectors.Transfer}\"," +
            $"\"fileSize\":10,\"sha256Hex\":\"{Vectors.Sha256Empty.ToUpperInvariant()}\"}}";
        Assert.IsType<DecodeOutcome.Failure>(DecodeWithPayload(payload, "TRANSFER_COMPLETE"));
    }

    [Fact]
    public void TransferredBytesBeyondTotal_IsRejected()
    {
        var payload = $"{{\"transferId\":\"{Vectors.Transfer}\"," +
            "\"transferredBytes\":11,\"totalBytes\":10}";
        Assert.IsType<DecodeOutcome.Failure>(DecodeWithPayload(payload, "TRANSFER_PROGRESS"));
    }

    [Fact]
    public void UnknownErrorCode_IsRejected()
    {
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"ERROR\"," +
            $"\"messageId\":\"{Vectors.RequestMsg}\"," +
            "\"payload\":{\"code\":\"MADE_UP\",\"message\":\"x\"}}"));
    }

    [Fact]
    public void BadDeviceType_IsRejected()
    {
        var bad = Vectors.Hello.Replace("\"deviceType\":\"android\"", "\"deviceType\":\"toaster\"");
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode(bad));
    }

    [Fact]
    public void OversizedDeviceName_IsRejected()
    {
        var bad = Vectors.Hello.Replace("\"deviceName\":\"Pixel 8\"",
            "\"deviceName\":\"" + new string('n', 129) + "\"");
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode(bad));
    }

    [Fact]
    public void FloatWhereIntegerRequired_IsRejected()
    {
        var bad = Vectors.Hello.Replace("\"controlPort\":0", "\"controlPort\":1.5");
        Assert.IsType<DecodeOutcome.Failure>(ProtocolCodec.Decode(bad));
    }

    public static IEnumerable<object[]> AllErrorCodes() =>
        new[]
        {
            "INVALID_PROTOCOL", "AUTH_FAILED", "PAIRING_REQUIRED", "TRANSFER_REJECTED",
            "NOT_ENOUGH_STORAGE", "FILE_NOT_FOUND", "NETWORK_LOST", "RESUME_UNAVAILABLE",
            "CHECKSUM_MISMATCH", "CANCELLED", "TIMEOUT", "INTERNAL_ERROR",
        }.Select(c => new object[] { c });

    [Theory]
    [MemberData(nameof(AllErrorCodes))]
    public void All12ErrorCodes_Parse(string code)
    {
        var outcome = ProtocolCodec.Decode(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"ERROR\"," +
            $"\"messageId\":\"{Vectors.RequestMsg}\"," +
            $"\"payload\":{{\"code\":\"{code}\",\"message\":\"m\"}}}}");
        Assert.IsType<DecodeOutcome.Success>(outcome);
    }

    [Fact]
    public void TwelveErrorCodes_Exist()
    {
        Assert.Equal(12, Enum.GetValues<ErrorCode>().Length);
    }

    [Fact]
    public void Vocabularies_AreExact()
    {
        Assert.Equal(10, Enum.GetValues<TransferState>().Length);
        Assert.True(TransferStates.TryParse("waiting acceptance", out var ts));
        Assert.Equal(TransferState.WAITING_ACCEPTANCE, ts);
        Assert.False(TransferStates.TryParse("flying", out _));
        Assert.Equal(15, Enum.GetValues<SessionState>().Length);
        Assert.True(SessionStates.TryParse("TRANSFER_REQUESTED", out var ss));
        Assert.Equal(SessionState.TRANSFER_REQUESTED, ss);
        Assert.False(SessionStates.TryParse("SLEEPING", out _));
        Assert.Equal(20, Enum.GetValues<MessageType>().Length);
        Assert.False(MessageTypes.TryParse("STATUS", out _));
    }
}
