// M02 §15/§16: cross-platform protocol vectors. These literals are identical
// to test-assets/protocol-vectors/v1/*.json and to the Android suite's
// literals: Windows serialization MUST equal Android serialization, and each
// side MUST deserialize the shared bytes to the expected object.
using System.Text;

namespace LocalDrop.Protocol.Tests;

public sealed class VectorsTests
{
    [Fact]
    public void Serialize_EqualsSharedVectorBytes()
    {
        Assert.Equal(Vectors.Hello, ProtocolCodec.Encode(Vectors.HelloMessage()));
        Assert.Equal(Vectors.TransferRequest, ProtocolCodec.Encode(Vectors.TransferRequestMessage()));
        Assert.Equal(Vectors.TransferAccept, ProtocolCodec.Encode(Vectors.TransferAcceptMessage()));
        Assert.Equal(Vectors.Error, ProtocolCodec.Encode(Vectors.ErrorMessage()));
        Assert.Equal(Vectors.Pause, ProtocolCodec.Encode(Vectors.PauseMessage()));
    }

    [Fact]
    public void Deserialize_SharedVectorBytes_ToExpectedObjects()
    {
        // Canonical re-encode equality (record payloads hold List<string>,
        // which is reference-compared; canonical bytes are the contract).
        Assert.Equal(Vectors.Hello,
            ProtocolCodec.Encode(Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.Decode(Vectors.Hello)).Message));
        Assert.Equal(Vectors.TransferRequest,
            ProtocolCodec.Encode(Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.Decode(Vectors.TransferRequest)).Message));
        Assert.Equal(Vectors.TransferAccept,
            ProtocolCodec.Encode(Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.Decode(Vectors.TransferAccept)).Message));
        Assert.Equal(Vectors.Error,
            ProtocolCodec.Encode(Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.Decode(Vectors.Error)).Message));
        Assert.Equal(Vectors.Pause,
            ProtocolCodec.Encode(Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.Decode(Vectors.Pause)).Message));
    }

    [Theory]
    [InlineData(Vectors.Hello, 262, new byte[] { 0, 0, 1, 6 })]
    [InlineData(Vectors.TransferRequest, 590, new byte[] { 0, 0, 2, 0x4E })]
    [InlineData(Vectors.TransferAccept, 359, new byte[] { 0, 0, 1, 0x67 })]
    [InlineData(Vectors.Error, 192, new byte[] { 0, 0, 0, 0xC0 })]
    [InlineData(Vectors.Pause, 226, new byte[] { 0, 0, 0, 0xE2 })]
    public void FramingPrefixes_MatchVectorTable(string json, int byteCount, byte[] prefix)
    {
        var raw = Encoding.UTF8.GetBytes(json);
        Assert.Equal(byteCount, raw.Length);
        var frame = FrameCodec.Encode(raw);
        Assert.Equal(prefix, frame[..4]);
        Assert.Equal(raw.Length + 4, frame.Length);
    }

    [Fact]
    public void DecodedTransferVector_Carries10GiBFileSize_WithoutAllocation()
    {
        var success = Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.Decode(Vectors.TransferRequest));
        var payload = Assert.IsType<TypedPayload.TransferRequest>(success.Message.Payload);
        Assert.Equal(10737418240L, payload.V.Metadata.FileSize);
        Assert.Equal(8388608L, payload.V.Metadata.CheckpointSize);
    }

    [Fact]
    public void AcceptVectorCorrelation_MatchesRequestVectorId()
    {
        var accept = Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.Decode(Vectors.TransferAccept));
        var request = Assert.IsType<DecodeOutcome.Success>(ProtocolCodec.Decode(Vectors.TransferRequest));
        Assert.Equal(request.Message.MessageId, accept.Message.CorrelationId);
        Assert.Equal(request.Message.TransferId, accept.Message.TransferId);
    }
}
