package com.localdrop.protocol

// M02: framing foundation (shared-protocol/framing-resume-integrity.md).
//   frame := 4-byte big-endian unsigned length N || N bytes UTF-8 JSON
// with 0 < N <= MAX_FRAME_BYTES (1 MiB).
// NO sockets, NO streams, NO connections here: pure byte transformation.
// The future M04 TCP code must feed stream bytes through Decoder, which
// handles partial reads/writes, split prefixes, and multiple messages.
// The decoder never buffers more than 4 + MAX_FRAME_BYTES bytes and never
// allocates from a declared length before validating it.
object FrameCodec {
    fun encode(jsonUtf8: ByteArray): ByteArray {
        require(jsonUtf8.isNotEmpty()) { "empty frame" }
        require(jsonUtf8.size <= Protocol.MAX_FRAME_BYTES) { "frame exceeds size limit" }
        val n = jsonUtf8.size
        val out = ByteArray(4 + n)
        out[0] = (n ushr 24).toByte()
        out[1] = (n ushr 16).toByte()
        out[2] = (n ushr 8).toByte()
        out[3] = n.toByte()
        jsonUtf8.copyInto(out, 4)
        return out
    }

    fun encodeMessage(message: LocalDropMessage): ByteArray =
        encode(ProtocolCodec.encodeUtf8(message))

    sealed interface FrameEvent {
        // Exactly one complete frame decoded; `jsonUtf8` is the N JSON bytes.
        data class Message(val jsonUtf8: ByteArray) : FrameEvent
        // Frame or stream violation. The decoder resets to fresh state;
        // detail maps to INVALID_PROTOCOL.
        data class Failure(val detail: String) : FrameEvent
    }

    // Incremental decoder: feed() stream bytes in, get zero or more events out.
    // Absence of events means NeedMoreData (incomplete frame held internally).
    class Decoder {
        private var buffer = ByteArray(0)

        fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<FrameEvent> {
            require(offset >= 0 && length >= 0 && offset + length <= chunk.size)
            if (length == 0) return emptyList()
            if (buffer.size + length > 4 + Protocol.MAX_FRAME_BYTES) {
                buffer = ByteArray(0)
                return listOf(FrameEvent.Failure("frame exceeds size limit"))
            }
            val grown = ByteArray(buffer.size + length)
            buffer.copyInto(grown)
            chunk.copyInto(grown, buffer.size, offset, offset + length)
            buffer = grown

            val events = ArrayList<FrameEvent>()
            while (true) {
                if (buffer.size < 4) return events // split prefix: wait for more
                val n = ((buffer[0].toInt() and 0xFF) shl 24) or
                    ((buffer[1].toInt() and 0xFF) shl 16) or
                    ((buffer[2].toInt() and 0xFF) shl 8) or
                    (buffer[3].toInt() and 0xFF)
                if (n <= 0) {
                    buffer = ByteArray(0)
                    events.add(FrameEvent.Failure("invalid frame length $n"))
                    return events
                }
                if (n > Protocol.MAX_FRAME_BYTES) {
                    buffer = ByteArray(0)
                    // Rejected WITHOUT allocating n bytes.
                    events.add(FrameEvent.Failure("frame length $n exceeds size limit"))
                    return events
                }
                if (buffer.size - 4 < n) return events // incomplete body: wait
                events.add(FrameEvent.Message(buffer.copyOfRange(4, 4 + n)))
                buffer = buffer.copyOfRange(4 + n, buffer.size)
                if (buffer.isEmpty()) return events
            }
        }

        fun reset() {
            buffer = ByteArray(0)
        }
    }
}
