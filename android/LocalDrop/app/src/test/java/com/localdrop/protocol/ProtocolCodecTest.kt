package com.localdrop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// M02 §16: valid serialization, deserialization, round-trip, correlation,
// cross-platform vectors. Offline only: no network, no device, no server.
class ProtocolCodecTest {

    @Test
    fun `valid HELLO serializes to canonical bytes`() {
        assertEquals(Vectors.HELLO, ProtocolCodec.encode(Vectors.helloMessage()))
    }

    @Test
    fun `valid HELLO deserializes to expected object`() {
        val out = ProtocolCodec.decode(Vectors.HELLO)
        assertTrue(out is DecodeOutcome.Success)
        assertEquals(Vectors.helloMessage(), (out as DecodeOutcome.Success).message)
    }

    @Test
    fun `round-trip preserves every message type`() {
        val tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val all: List<LocalDropMessage> = listOf(
            Vectors.helloMessage(),
            LocalDropMessage(MessageType.HELLO_ACK, Vectors.REQUEST_MSG, null, null, null,
                TypedPayload.HelloAck(HelloAckPayload(Vectors.WINDOWS_DEVICE, "PC", "windows", true))),
            LocalDropMessage(MessageType.PAIR_REQUEST, Vectors.REQUEST_MSG, null, null, null,
                TypedPayload.PairRequest(PairRequestPayload(Vectors.ANDROID_DEVICE, "Pixel 8", "482913"))),
            LocalDropMessage(MessageType.PAIR_RESPONSE, Vectors.REQUEST_MSG, Vectors.REQUEST_MSG, null, null,
                TypedPayload.PairResponse(PairResponsePayload(Vectors.WINDOWS_DEVICE, true, null))),
            LocalDropMessage(MessageType.AUTH_REQUEST, Vectors.REQUEST_MSG, null, Vectors.SESSION, null,
                TypedPayload.AuthRequest(AuthRequestPayload(Vectors.ANDROID_DEVICE, "pair-code", "opaque-token"))),
            LocalDropMessage(MessageType.AUTH_RESPONSE, Vectors.REQUEST_MSG, Vectors.REQUEST_MSG, Vectors.SESSION, null,
                TypedPayload.AuthResponse(AuthResponsePayload(Vectors.WINDOWS_DEVICE, false, "denied"))),
            Vectors.transferRequestMessage(),
            Vectors.transferAcceptMessage(),
            LocalDropMessage(MessageType.TRANSFER_REJECT, Vectors.REQUEST_MSG, Vectors.REQUEST_MSG, Vectors.SESSION, tid,
                TypedPayload.TransferReject(TransferRejectPayload(tid, ProtocolError(ErrorCode.TRANSFER_REJECTED, "no")))),
            LocalDropMessage(MessageType.TRANSFER_START, Vectors.REQUEST_MSG, null, Vectors.SESSION, tid,
                TypedPayload.TransferStart(TransferStartPayload(tid, 0))),
            LocalDropMessage(MessageType.TRANSFER_PROGRESS, Vectors.REQUEST_MSG, null, Vectors.SESSION, tid,
                TypedPayload.TransferProgress(TransferProgressPayload(tid, 512, 1024))),
            LocalDropMessage(MessageType.PAUSE, Vectors.REQUEST_MSG, null, Vectors.SESSION, tid,
                TypedPayload.Pause(TransferIdOnlyPayload(tid))),
            LocalDropMessage(MessageType.RESUME, Vectors.REQUEST_MSG, null, Vectors.SESSION, tid,
                TypedPayload.Resume(TransferIdOnlyPayload(tid))),
            LocalDropMessage(MessageType.CANCEL, Vectors.REQUEST_MSG, null, Vectors.SESSION, tid,
                TypedPayload.Cancel(CancelPayload(tid, "user"))),
            LocalDropMessage(MessageType.RESUME_QUERY, Vectors.REQUEST_MSG, null, Vectors.SESSION, tid,
                TypedPayload.ResumeQuery(ResumeQueryPayload(tid, "file-001", 1024))),
            LocalDropMessage(MessageType.RESUME_RESPONSE, Vectors.REQUEST_MSG, Vectors.REQUEST_MSG, Vectors.SESSION, tid,
                TypedPayload.ResumeResponse(ResumeResponsePayload(tid, 512, 1024))),
            LocalDropMessage(MessageType.TRANSFER_COMPLETE, Vectors.REQUEST_MSG, null, Vectors.SESSION, tid,
                TypedPayload.TransferComplete(TransferCompletePayload(tid, 1024, Vectors.SHA256_EMPTY))),
            LocalDropMessage(MessageType.VERIFY_REQUEST, Vectors.REQUEST_MSG, null, Vectors.SESSION, tid,
                TypedPayload.VerifyRequest(VerifyRequestPayload(tid, Vectors.SHA256_EMPTY))),
            LocalDropMessage(MessageType.VERIFY_RESULT, Vectors.REQUEST_MSG, Vectors.REQUEST_MSG, Vectors.SESSION, tid,
                TypedPayload.VerifyResult(VerifyResultPayload(tid, true, Vectors.SHA256_EMPTY))),
            Vectors.errorMessage()
        )
        assertEquals(20, all.size)
        for (m in all) {
            val out = ProtocolCodec.decode(ProtocolCodec.encode(m))
            assertTrue("round-trip failed for ${m.type}", out is DecodeOutcome.Success)
            assertEquals(m, (out as DecodeOutcome.Success).message)
        }
    }

    @Test
    fun `correlationId echoes request messageId`() {
        val out = ProtocolCodec.decode(Vectors.TRANSFER_ACCEPT)
        assertTrue(out is DecodeOutcome.Success)
        val msg = (out as DecodeOutcome.Success).message
        assertEquals(Vectors.REQUEST_MSG, msg.correlationId)
    }

    @Test
    fun `malformed JSON is rejected`() {
        val out = ProtocolCodec.decode("{\"protocol\":")
        assertTrue(out is DecodeOutcome.Failure)
        assertEquals(ErrorCode.INVALID_PROTOCOL, (out as DecodeOutcome.Failure).code)
    }

    @Test
    fun `non-object top level is rejected`() {
        val out = ProtocolCodec.decode("[1,2]")
        assertTrue(out is DecodeOutcome.Failure)
    }

    @Test
    fun `missing payload is rejected`() {
        val out = ProtocolCodec.decode(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"PAUSE\"," +
                "\"messageId\":\"${Vectors.REQUEST_MSG}\"}"
        )
        assertTrue(out is DecodeOutcome.Failure)
    }

    @Test
    fun `invalid protocol version is rejected`() {
        val bad = Vectors.PAUSE.replace("\"protocolVersion\":1", "\"protocolVersion\":2")
        val out = ProtocolCodec.decode(bad)
        assertTrue(out is DecodeOutcome.Failure)
        assertEquals(ErrorCode.INVALID_PROTOCOL, (out as DecodeOutcome.Failure).code)
    }

    @Test
    fun `unknown message type is rejected`() {
        val bad = Vectors.PAUSE.replace("\"type\":\"PAUSE\"", "\"type\":\"TELEPORT\"")
        val out = ProtocolCodec.decode(bad)
        assertTrue(out is DecodeOutcome.Failure)
    }

    @Test
    fun `unknown envelope fields are ignored`() {
        val withExtra = Vectors.PAUSE.replace(
            "\"payload\":",
            "\"futureField\":\"x\",\"payload\":"
        )
        val out = ProtocolCodec.decode(withExtra)
        assertTrue(out is DecodeOutcome.Success)
        assertEquals(Vectors.pauseMessage(), (out as DecodeOutcome.Success).message)
    }

    @Test
    fun `envelope-payload transferId mismatch is rejected`() {
        val bad = Vectors.PAUSE.replace(
            "\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\",\"payload\"",
            "\"transferId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"payload\""
        )
        assertTrue(ProtocolCodec.decode(bad) is DecodeOutcome.Failure)
    }

    @Test
    fun `invalid UTF-8 bytes are rejected`() {
        val out = ProtocolCodec.decodeUtf8(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertTrue(out is DecodeOutcome.Failure)
    }

    @Test
    fun `oversize input is rejected without parsing`() {
        val big = "x".repeat(Protocol.MAX_FRAME_BYTES + 1)
        val out = ProtocolCodec.decode("\"$big\"")
        assertTrue(out is DecodeOutcome.Failure)
    }
}
