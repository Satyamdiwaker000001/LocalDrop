package com.localdrop.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// M02 §16: framing encode/decode, incomplete frames, invalid frames,
// boundary conditions, size limits. No sockets involved.
class FrameCodecTest {

    @Test
    fun `encode produces length prefix plus bytes`() {
        val body = "hello".toByteArray(Charsets.UTF_8)
        val frame = FrameCodec.encode(body)
        assertEquals(9, frame.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 5), frame.copyOfRange(0, 4))
        assertArrayEquals(body, frame.copyOfRange(4, 9))
    }

    @Test
    fun `decode single complete frame`() {
        val frame = FrameCodec.encodeMessage(Vectors.pauseMessage())
        val events = FrameCodec.Decoder().feed(frame)
        assertEquals(1, events.size)
        assertTrue(events[0] is FrameCodec.FrameEvent.Message)
        val body = (events[0] as FrameCodec.FrameEvent.Message).jsonUtf8
        assertArrayEquals(Vectors.PAUSE.toByteArray(Charsets.UTF_8), body)
    }

    @Test
    fun `two frames in one feed decode as two messages`() {
        val a = FrameCodec.encodeMessage(Vectors.pauseMessage())
        val b = FrameCodec.encodeMessage(Vectors.errorMessage())
        val events = FrameCodec.Decoder().feed(a + b)
        assertEquals(2, events.size)
        assertTrue(events.all { it is FrameCodec.FrameEvent.Message })
    }

    @Test
    fun `incomplete frame waits for more data`() {
        val frame = FrameCodec.encodeMessage(Vectors.pauseMessage())
        val decoder = FrameCodec.Decoder()
        // Split prefix: 2 bytes first.
        assertTrue(decoder.feed(frame.copyOfRange(0, 2)).isEmpty())
        // Rest of prefix + half body.
        val mid = frame.size / 2
        assertTrue(decoder.feed(frame.copyOfRange(2, mid)).isEmpty())
        val events = decoder.feed(frame.copyOfRange(mid, frame.size))
        assertEquals(1, events.size)
        assertArrayEquals(
            Vectors.PAUSE.toByteArray(Charsets.UTF_8),
            (events[0] as FrameCodec.FrameEvent.Message).jsonUtf8
        )
    }

    @Test
    fun `byte-at-a-time feed reconstructs the frame`() {
        val frame = FrameCodec.encodeMessage(Vectors.helloMessage())
        val decoder = FrameCodec.Decoder()
        val collected = mutableListOf<FrameCodec.FrameEvent>()
        for (byte in frame) collected += decoder.feed(byteArrayOf(byte))
        assertEquals(1, collected.size)
        assertArrayEquals(
            Vectors.HELLO.toByteArray(Charsets.UTF_8),
            (collected[0] as FrameCodec.FrameEvent.Message).jsonUtf8
        )
    }

    @Test
    fun `zero-length frame fails`() {
        val events = FrameCodec.Decoder().feed(byteArrayOf(0, 0, 0, 0))
        assertEquals(1, events.size)
        assertTrue(events[0] is FrameCodec.FrameEvent.Failure)
    }

    @Test
    fun `oversize declaration fails without allocation`() {
        // Declares MAX+1 but sends almost no body: must fail on the prefix.
        val n = Protocol.MAX_FRAME_BYTES + 1
        val prefix = byteArrayOf(
            (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
        )
        val events = FrameCodec.Decoder().feed(prefix + ByteArray(16))
        assertEquals(1, events.size)
        assertTrue(events[0] is FrameCodec.FrameEvent.Failure)
    }

    @Test
    fun `exactly-max frame is accepted, max-plus-one encode is refused`() {
        val max = ByteArray(Protocol.MAX_FRAME_BYTES) { ' '.code.toByte() }
        val frame = FrameCodec.encode(max)
        assertEquals(4 + Protocol.MAX_FRAME_BYTES, frame.size)
        val events = FrameCodec.Decoder().feed(frame)
        assertEquals(1, events.size)
        assertTrue(events[0] is FrameCodec.FrameEvent.Message)
        try {
            FrameCodec.encode(ByteArray(Protocol.MAX_FRAME_BYTES + 1))
            throw AssertionError("expected refusal")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `buffer overrun without completing a frame fails`() {
        val decoder = FrameCodec.Decoder()
        // Claim MaxFrameBytes, then overfill the buffer cap without completing.
        var events = decoder.feed(byteArrayOf(0, 0x10.toByte(), 0, 0))
        assertTrue(events.isEmpty())
        val chunk = ByteArray(600 * 1024) { 1 }
        events = decoder.feed(chunk)
        assertTrue(events.isEmpty())
        events = decoder.feed(chunk) // 4 + 1200 KiB > 4 + Max cap
        assertEquals(1, events.size)
        assertTrue(events[0] is FrameCodec.FrameEvent.Failure)
    }

    @Test
    fun `framed bytes decode back to the message`() {
        val frame = FrameCodec.encodeMessage(Vectors.transferRequestMessage())
        val events = FrameCodec.Decoder().feed(frame)
        val body = (events[0] as FrameCodec.FrameEvent.Message).jsonUtf8
        val out = ProtocolCodec.decodeUtf8(body)
        assertTrue(out is DecodeOutcome.Success)
        assertEquals(Vectors.transferRequestMessage(), (out as DecodeOutcome.Success).message)
    }
}
