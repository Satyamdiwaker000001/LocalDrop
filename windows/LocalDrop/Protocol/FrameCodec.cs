// M02: framing foundation (shared-protocol/framing-resume-integrity.md).
//   frame := 4-byte big-endian unsigned length N || N bytes UTF-8 JSON
// with 0 < N <= MaxFrameBytes (1 MiB).
// NO sockets, NO streams, NO connections here: pure byte transformation.
// The future M04 TCP code must feed stream bytes through Decoder, which
// handles partial reads/writes, split prefixes, and multiple messages.
// The decoder never buffers more than 4 + MaxFrameBytes bytes and never
// allocates from a declared length before validating it.
namespace LocalDrop.Protocol;

public static class FrameCodec
{
    public static byte[] Encode(byte[] jsonUtf8)
    {
        ArgumentNullException.ThrowIfNull(jsonUtf8);
        if (jsonUtf8.Length == 0) throw new ArgumentException("empty frame", nameof(jsonUtf8));
        if (jsonUtf8.Length > Protocol.MaxFrameBytes)
            throw new ArgumentException("frame exceeds size limit", nameof(jsonUtf8));
        int n = jsonUtf8.Length;
        var outBuf = new byte[4 + n];
        outBuf[0] = (byte)(n >> 24);
        outBuf[1] = (byte)(n >> 16);
        outBuf[2] = (byte)(n >> 8);
        outBuf[3] = (byte)n;
        Buffer.BlockCopy(jsonUtf8, 0, outBuf, 4, n);
        return outBuf;
    }

    public static byte[] EncodeMessage(LocalDropMessage message) =>
        Encode(System.Text.Encoding.UTF8.GetBytes(ProtocolCodec.Encode(message)));

    public abstract record FrameEvent
    {
        // Exactly one complete frame decoded; JsonUtf8 is the N JSON bytes.
        public sealed record Message(byte[] JsonUtf8) : FrameEvent;
        // Frame or stream violation. The decoder resets to fresh state;
        // detail maps to INVALID_PROTOCOL.
        public sealed record Failure(string Detail) : FrameEvent;
    }

    // Incremental decoder: feed() stream bytes in, get zero or more events out.
    // No events means NeedMoreData (incomplete frame held internally).
    public sealed class Decoder
    {
        private byte[] _buffer = [];

        public List<FrameEvent> Feed(byte[] chunk, int offset = 0, int length = -1)
        {
            ArgumentNullException.ThrowIfNull(chunk);
            if (length < 0) length = chunk.Length - offset;
            ArgumentOutOfRangeException.ThrowIfNegative(offset);
            ArgumentOutOfRangeException.ThrowIfNegative(length);
            if (offset + length > chunk.Length) throw new ArgumentException("offset/length out of range");
            if (length == 0) return [];
            if (_buffer.Length + length > 4 + Protocol.MaxFrameBytes)
            {
                _buffer = [];
                return [new FrameEvent.Failure("frame exceeds size limit")];
            }
            var grown = new byte[_buffer.Length + length];
            Buffer.BlockCopy(_buffer, 0, grown, 0, _buffer.Length);
            Buffer.BlockCopy(chunk, offset, grown, _buffer.Length, length);
            _buffer = grown;

            var events = new List<FrameEvent>();
            while (true)
            {
                if (_buffer.Length < 4) return events; // split prefix: wait for more
                int n = (_buffer[0] << 24) | (_buffer[1] << 16) | (_buffer[2] << 8) | _buffer[3];
                if (n <= 0)
                {
                    _buffer = [];
                    events.Add(new FrameEvent.Failure($"invalid frame length {n}"));
                    return events;
                }
                if (n > Protocol.MaxFrameBytes)
                {
                    _buffer = [];
                    // Rejected WITHOUT allocating n bytes.
                    events.Add(new FrameEvent.Failure($"frame length {n} exceeds size limit"));
                    return events;
                }
                if (_buffer.Length - 4 < n) return events; // incomplete body: wait
                var body = new byte[n];
                Buffer.BlockCopy(_buffer, 4, body, 0, n);
                events.Add(new FrameEvent.Message(body));
                var rest = new byte[_buffer.Length - 4 - n];
                Buffer.BlockCopy(_buffer, 4 + n, rest, 0, rest.Length);
                _buffer = rest;
                if (_buffer.Length == 0) return events;
            }
        }

        public void Reset() => _buffer = [];
    }
}
