// M02 §16: framing encode/decode, incomplete frames, invalid frames,
// boundary conditions, size limits. No sockets involved.
using System.Text;

namespace LocalDrop.Protocol.Tests;

public sealed class FrameCodecTests
{
    [Fact]
    public void Encode_ProducesLengthPrefixPlusBytes()
    {
        var body = Encoding.UTF8.GetBytes("hello");
        var frame = FrameCodec.Encode(body);
        Assert.Equal(9, frame.Length);
        Assert.Equal(new byte[] { 0, 0, 0, 5 }, frame[..4]);
        Assert.Equal(body, frame[4..]);
    }

    [Fact]
    public void Decode_SingleCompleteFrame()
    {
        var frame = FrameCodec.EncodeMessage(Vectors.PauseMessage());
        var events = new FrameCodec.Decoder().Feed(frame);
        Assert.Single(events);
        var msg = Assert.IsType<FrameCodec.FrameEvent.Message>(events[0]);
        Assert.Equal(Vectors.Pause, Encoding.UTF8.GetString(msg.JsonUtf8));
    }

    [Fact]
    public void TwoFramesInOneFeed_DecodeAsTwoMessages()
    {
        var a = FrameCodec.EncodeMessage(Vectors.PauseMessage());
        var b = FrameCodec.EncodeMessage(Vectors.ErrorMessage());
        var events = new FrameCodec.Decoder().Feed([.. a, .. b]);
        Assert.Equal(2, events.Count);
        Assert.All(events, e => Assert.IsType<FrameCodec.FrameEvent.Message>(e));
    }

    [Fact]
    public void IncompleteFrame_WaitsForMoreData()
    {
        var frame = FrameCodec.EncodeMessage(Vectors.PauseMessage());
        var decoder = new FrameCodec.Decoder();
        // Split prefix: 2 bytes first.
        Assert.Empty(decoder.Feed(frame[..2]));
        int mid = frame.Length / 2;
        Assert.Empty(decoder.Feed(frame[2..mid]));
        var events = decoder.Feed(frame[mid..]);
        Assert.Single(events);
        var msg = Assert.IsType<FrameCodec.FrameEvent.Message>(events[0]);
        Assert.Equal(Vectors.Pause, Encoding.UTF8.GetString(msg.JsonUtf8));
    }

    [Fact]
    public void ByteAtATimeFeed_ReconstructsFrame()
    {
        var frame = FrameCodec.EncodeMessage(Vectors.HelloMessage());
        var decoder = new FrameCodec.Decoder();
        var collected = new List<FrameCodec.FrameEvent>();
        foreach (byte b in frame) collected.AddRange(decoder.Feed([b]));
        Assert.Single(collected);
        var msg = Assert.IsType<FrameCodec.FrameEvent.Message>(collected[0]);
        Assert.Equal(Vectors.Hello, Encoding.UTF8.GetString(msg.JsonUtf8));
    }

    [Fact]
    public void ZeroLengthFrame_Fails()
    {
        var events = new FrameCodec.Decoder().Feed([0, 0, 0, 0]);
        var failure = Assert.Single(events);
        Assert.IsType<FrameCodec.FrameEvent.Failure>(failure);
    }

    [Fact]
    public void OversizeDeclaration_FailsWithoutAllocation()
    {
        // Declares Max+1 but sends almost no body: must fail on the prefix.
        int n = Protocol.MaxFrameBytes + 1;
        var events = new FrameCodec.Decoder().Feed(
        [
            (byte)(n >> 24), (byte)(n >> 16), (byte)(n >> 8), (byte)n,
            .. new byte[16],
        ]);
        var failure = Assert.Single(events);
        Assert.IsType<FrameCodec.FrameEvent.Failure>(failure);
    }

    [Fact]
    public void ExactlyMaxFrame_Accepted_MaxPlusOneEncode_Refused()
    {
        var max = Encoding.ASCII.GetBytes(new string(' ', Protocol.MaxFrameBytes));
        var frame = FrameCodec.Encode(max);
        Assert.Equal(4 + Protocol.MaxFrameBytes, frame.Length);
        var events = new FrameCodec.Decoder().Feed(frame);
        Assert.IsType<FrameCodec.FrameEvent.Message>(Assert.Single(events));
        Assert.Throws<ArgumentException>(() => FrameCodec.Encode(new byte[Protocol.MaxFrameBytes + 1]));
    }

    [Fact]
    public void BufferOverrunWithoutCompletingFrame_Fails()
    {
        var decoder = new FrameCodec.Decoder();
        // Claim MaxFrameBytes, then overfill the buffer cap without completing.
        Assert.Empty(decoder.Feed([0, 0x10, 0, 0]));
        var chunk = new byte[600 * 1024];
        Array.Fill(chunk, (byte)1);
        Assert.Empty(decoder.Feed(chunk));
        var events = decoder.Feed(chunk); // 4 + 1200 KiB > 4 + Max cap
        Assert.IsType<FrameCodec.FrameEvent.Failure>(Assert.Single(events));
    }

    [Fact]
    public void FramedBytes_DecodeBackToMessage()
    {
        var frame = FrameCodec.EncodeMessage(Vectors.TransferRequestMessage());
        var events = new FrameCodec.Decoder().Feed(frame);
        var msg = Assert.IsType<FrameCodec.FrameEvent.Message>(Assert.Single(events));
        var outcome = ProtocolCodec.DecodeUtf8(msg.JsonUtf8);
        var success = Assert.IsType<DecodeOutcome.Success>(outcome);
        Assert.Equal(Vectors.TransferRequestMessage(), success.Message);
    }
}
