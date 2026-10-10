package com.localdrop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// M02 §16: invalid field values are rejected, never silently accepted.
class ValidationTest {

    private fun decodeWithPayload(payload: String, type: String = "PAUSE"): DecodeOutcome {
        return ProtocolCodec.decode(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"$type\"," +
                "\"messageId\":\"${Vectors.REQUEST_MSG}\"," +
                "\"transferId\":\"${Vectors.TRANSFER}\",\"payload\":$payload}"
        )
    }

    @Test
    fun `bad UUID is rejected`() {
        val out = ProtocolCodec.decode(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"PAUSE\"," +
                "\"messageId\":\"not-a-uuid\"," +
                "\"transferId\":\"${Vectors.TRANSFER}\"," +
                "\"payload\":{\"transferId\":\"${Vectors.TRANSFER}\"}}"
        )
        assertTrue(out is DecodeOutcome.Failure)
    }

    @Test
    fun `fileName with separator is rejected`() {
        val payload = "{\"transferId\":\"${Vectors.TRANSFER}\"," +
            "\"senderDeviceId\":\"${Vectors.ANDROID_DEVICE}\"," +
            "\"receiverDeviceId\":\"${Vectors.WINDOWS_DEVICE}\"," +
            "\"fileId\":\"f\",\"fileName\":\"../evil.zip\",\"fileSize\":10," +
            "\"resumeOffset\":0}"
        assertTrue(decodeWithPayload(payload, "TRANSFER_REQUEST") is DecodeOutcome.Failure)
    }

    @Test
    fun `negative fileSize is rejected`() {
        val payload = "{\"transferId\":\"${Vectors.TRANSFER}\"," +
            "\"senderDeviceId\":\"${Vectors.ANDROID_DEVICE}\"," +
            "\"receiverDeviceId\":\"${Vectors.WINDOWS_DEVICE}\"," +
            "\"fileId\":\"f\",\"fileName\":\"a.zip\",\"fileSize\":-1,\"resumeOffset\":0}"
        assertTrue(decodeWithPayload(payload, "TRANSFER_REQUEST") is DecodeOutcome.Failure)
    }

    @Test
    fun `resumeOffset beyond fileSize is rejected`() {
        val payload = "{\"transferId\":\"${Vectors.TRANSFER}\"," +
            "\"senderDeviceId\":\"${Vectors.ANDROID_DEVICE}\"," +
            "\"receiverDeviceId\":\"${Vectors.WINDOWS_DEVICE}\"," +
            "\"fileId\":\"f\",\"fileName\":\"a.zip\",\"fileSize\":10,\"resumeOffset\":11}"
        assertTrue(decodeWithPayload(payload, "TRANSFER_REQUEST") is DecodeOutcome.Failure)
    }

    @Test
    fun `uppercase sha256 is rejected`() {
        val payload = "{\"transferId\":\"${Vectors.TRANSFER}\"," +
            "\"fileSize\":10,\"sha256Hex\":\"${Vectors.SHA256_EMPTY.uppercase()}\"}"
        assertTrue(decodeWithPayload(payload, "TRANSFER_COMPLETE") is DecodeOutcome.Failure)
    }

    @Test
    fun `transferredBytes beyond totalBytes is rejected`() {
        val payload = "{\"transferId\":\"${Vectors.TRANSFER}\"," +
            "\"transferredBytes\":11,\"totalBytes\":10}"
        assertTrue(decodeWithPayload(payload, "TRANSFER_PROGRESS") is DecodeOutcome.Failure)
    }

    @Test
    fun `unknown error code is rejected`() {
        val out = ProtocolCodec.decode(
            "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"ERROR\"," +
                "\"messageId\":\"${Vectors.REQUEST_MSG}\"," +
                "\"payload\":{\"code\":\"MADE_UP\",\"message\":\"x\"}}"
        )
        assertTrue(out is DecodeOutcome.Failure)
    }

    @Test
    fun `bad deviceType is rejected`() {
        val bad = Vectors.HELLO.replace("\"deviceType\":\"android\"", "\"deviceType\":\"toaster\"")
        assertTrue(ProtocolCodec.decode(bad) is DecodeOutcome.Failure)
    }

    @Test
    fun `oversized deviceName is rejected`() {
        val bad = Vectors.HELLO.replace("\"deviceName\":\"Pixel 8\"", "\"deviceName\":\"${"n".repeat(129)}\"")
        assertTrue(ProtocolCodec.decode(bad) is DecodeOutcome.Failure)
    }

    @Test
    fun `float where integer required is rejected`() {
        val bad = Vectors.HELLO.replace("\"controlPort\":0", "\"controlPort\":1.5")
        assertTrue(ProtocolCodec.decode(bad) is DecodeOutcome.Failure)
    }

    @Test
    fun `all 12 error codes parse, unknown does not`() {
        val codes = listOf(
            "INVALID_PROTOCOL", "AUTH_FAILED", "PAIRING_REQUIRED", "TRANSFER_REJECTED",
            "NOT_ENOUGH_STORAGE", "FILE_NOT_FOUND", "NETWORK_LOST", "RESUME_UNAVAILABLE",
            "CHECKSUM_MISMATCH", "CANCELLED", "TIMEOUT", "INTERNAL_ERROR"
        )
        assertEquals(12, codes.size)
        for (c in codes) {
            val out = ProtocolCodec.decode(
                "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"ERROR\"," +
                    "\"messageId\":\"${Vectors.REQUEST_MSG}\"," +
                    "\"payload\":{\"code\":\"$c\",\"message\":\"m\"}}"
            )
            assertTrue("code $c must parse", out is DecodeOutcome.Success)
        }
    }

    @Test
    fun `transfer and session vocabularies are exact`() {
        assertEquals(10, TransferState.entries.size)
        assertEquals(TransferState.WAITING_ACCEPTANCE, TransferState.fromWire("waiting acceptance"))
        assertEquals(null, TransferState.fromWire("flying"))
        assertEquals(15, SessionState.entries.size)
        assertEquals(SessionState.TRANSFER_REQUESTED, SessionState.fromWire("TRANSFER_REQUESTED"))
        assertEquals(null, SessionState.fromWire("SLEEPING"))
        assertEquals(20, MessageType.entries.size)
        assertEquals(null, MessageType.fromWire("STATUS"))
    }
}
