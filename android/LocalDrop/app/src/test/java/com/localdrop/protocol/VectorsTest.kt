package com.localdrop.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// M02 §15/§16: cross-platform protocol vectors. These literals are identical
// to test-assets/protocol-vectors/v1/*.json and to the Windows suite's
// literals: Android serialization MUST equal Windows serialization, and each
// side MUST deserialize the shared bytes to the expected object.
class VectorsTest {

    @Test
    fun `serialize equals shared vector bytes`() {
        assertEquals(Vectors.HELLO, ProtocolCodec.encode(Vectors.helloMessage()))
        assertEquals(
            Vectors.TRANSFER_REQUEST,
            ProtocolCodec.encode(Vectors.transferRequestMessage())
        )
        assertEquals(
            Vectors.TRANSFER_ACCEPT,
            ProtocolCodec.encode(Vectors.transferAcceptMessage())
        )
        assertEquals(Vectors.ERROR, ProtocolCodec.encode(Vectors.errorMessage()))
        assertEquals(Vectors.PAUSE, ProtocolCodec.encode(Vectors.pauseMessage()))
    }

    @Test
    fun `deserialize shared vector bytes to expected objects`() {
        assertEquals(
            Vectors.helloMessage(),
            (ProtocolCodec.decode(Vectors.HELLO) as DecodeOutcome.Success).message
        )
        assertEquals(
            Vectors.transferRequestMessage(),
            (ProtocolCodec.decode(Vectors.TRANSFER_REQUEST) as DecodeOutcome.Success).message
        )
        assertEquals(
            Vectors.transferAcceptMessage(),
            (ProtocolCodec.decode(Vectors.TRANSFER_ACCEPT) as DecodeOutcome.Success).message
        )
        assertEquals(
            Vectors.errorMessage(),
            (ProtocolCodec.decode(Vectors.ERROR) as DecodeOutcome.Success).message
        )
        assertEquals(
            Vectors.pauseMessage(),
            (ProtocolCodec.decode(Vectors.PAUSE) as DecodeOutcome.Success).message
        )
    }

    @Test
    fun `framing prefixes match the vector table`() {
        val cases = mapOf(
            Vectors.HELLO to byteArrayOf(0, 0, 1, 6),
            Vectors.TRANSFER_REQUEST to byteArrayOf(0, 0, 2, 0x4E),
            Vectors.TRANSFER_ACCEPT to byteArrayOf(0, 0, 1, 0x67),
            Vectors.ERROR to byteArrayOf(0, 0, 0, 0xC0.toByte()),
            Vectors.PAUSE to byteArrayOf(0, 0, 0, 0xE2.toByte())
        )
        for ((json, prefix) in cases) {
            val frame = FrameCodec.encode(json.toByteArray(Charsets.UTF_8))
            assertArrayEquals(prefix, frame.copyOfRange(0, 4))
            assertEquals(json.toByteArray(Charsets.UTF_8).size + 4, frame.size)
        }
    }

    @Test
    fun `decoded transfer vector carries 10 GiB fileSize without allocation`() {
        val out = ProtocolCodec.decode(Vectors.TRANSFER_REQUEST)
        assertTrue(out is DecodeOutcome.Success)
        val payload = ((out as DecodeOutcome.Success).message.payload as TypedPayload.TransferRequest).v
        assertEquals(10737418240L, payload.metadata.fileSize)
        assertEquals(8388608L, payload.metadata.checkpointSize)
    }

    @Test
    fun `accept vector correlation matches request vector id`() {
        val accept = ProtocolCodec.decode(Vectors.TRANSFER_ACCEPT) as DecodeOutcome.Success
        val request = ProtocolCodec.decode(Vectors.TRANSFER_REQUEST) as DecodeOutcome.Success
        assertEquals(request.message.messageId, accept.message.correlationId)
        assertEquals(request.message.transferId, accept.message.transferId)
    }
}
