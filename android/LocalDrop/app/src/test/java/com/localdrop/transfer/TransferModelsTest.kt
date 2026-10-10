package com.localdrop.transfer

import com.localdrop.protocol.ErrorCode
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.TypedPayload
import com.localdrop.protocol.TransferIdOnlyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// M06 §24A/B + ADRs 12/15/17: 64-bit progress math, state wires, error
// mapping, transferId routing. Pure logic, no I/O.
class TransferModelsTest {

    @Test
    fun `state wires reuse the frozen M01 vocabulary`() {
        assertEquals("connecting", TransferEngineState.CONNECTING.wire)
        assertEquals("waiting acceptance", TransferEngineState.WAITING_ACCEPTANCE.wire)
        assertEquals("transferring", TransferEngineState.TRANSFERRING.wire)
        assertEquals("completed", TransferEngineState.TRANSPORT_COMPLETE.wire)
        assertEquals("failed", TransferEngineState.FAILED.wire)
        assertEquals("cancelled", TransferEngineState.CANCELLED.wire)
        val frozen = listOf(
            "available", "connecting", "waiting acceptance", "transferring",
            "paused", "reconnecting", "verifying", "completed", "failed", "cancelled"
        )
        assertTrue(frozen.containsAll(TransferEngineState.wires()))
    }

    @Test
    fun `progress math is 64-bit and safe`() {
        val tenGib = 10L * 1024 * 1024 * 1024
        val p = TransferProgress.compute("t", tenGib, tenGib / 4, 2000L, 1_000_000_000.0, TransferEngineState.TRANSFERRING)
        assertEquals(tenGib, p.totalBytes)
        assertEquals(tenGib / 4, p.transferredBytes)
        assertEquals(25.0, p.percent, 1e-9)
        assertEquals(tenGib / 4 * 1000.0 / 2000.0, p.averageBytesPerSec!!, 1.0)
        assertEquals(((tenGib - tenGib / 4) * 1000.0 / 1_000_000_000.0).toLong(), p.etaMs)
        // Near Long.MAX_VALUE: no overflow, percent clamps.
        val huge = TransferProgress.compute("t", Long.MAX_VALUE, Long.MAX_VALUE, 1000L, 1e12, TransferEngineState.TRANSFERRING)
        assertEquals(100.0, huge.percent, 1e-9)
        // Zero-byte file: defined as complete, no division by zero.
        val empty = TransferProgress.compute("t", 0L, 0L, 0L, null, TransferEngineState.TRANSFERRING)
        assertEquals(100.0, empty.percent, 1e-9)
        assertNull(empty.averageBytesPerSec)
        assertNull(empty.etaMs)
        // No recent rate: no ETA claim.
        val blind = TransferProgress.compute("t", 100L, 10L, 100L, null, TransferEngineState.TRANSFERRING)
        assertNull(blind.etaMs)
        // Over-count clamps to total.
        val over = TransferProgress.compute("t", 100L, 150L, 100L, 10.0, TransferEngineState.TRANSFERRING)
        assertEquals(100L, over.transferredBytes)
        assertEquals(100.0, over.percent, 1e-9)
    }

    @Test
    fun `error mapping uses only frozen codes`() {
        assertEquals(ErrorCode.FILE_NOT_FOUND, TransferErrors.sourceUnavailable("x").first)
        assertEquals(ErrorCode.INTERNAL_ERROR, TransferErrors.sourceReadFailed("x").first)
        assertEquals(ErrorCode.NETWORK_LOST, TransferErrors.connectionFailed("x").first)
        assertEquals(ErrorCode.NETWORK_LOST, TransferErrors.connectionLost("x").first)
        assertEquals(ErrorCode.CANCELLED, TransferErrors.cancelled().first)
        assertEquals(ErrorCode.TIMEOUT, TransferErrors.timedOut("x").first)
        assertEquals(ErrorCode.TRANSFER_REJECTED, TransferErrors.rejected("x").first)
        assertEquals(ErrorCode.INVALID_PROTOCOL, TransferErrors.protocolViolation("x").first)
        assertEquals(
            ErrorCode.NOT_ENOUGH_STORAGE,
            TransferErrors.classifyWriteFailure(IOException("No space left on device")).first
        )
        assertEquals(
            ErrorCode.INTERNAL_ERROR,
            TransferErrors.classifyWriteFailure(IOException("weird")).first
        )
    }

    @Test
    fun `transferId routing covers transfer types only`() {
        fun message(type: MessageType, payload: TypedPayload) =
            com.localdrop.protocol.LocalDropMessage(
                type, "11111111-1111-4111-8111-111111111111", null, null, "tid", payload
            )
        val tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        assertEquals(
            tid,
            transferIdOf(message(MessageType.PAUSE, TypedPayload.Pause(TransferIdOnlyPayload(tid))))
        )
        assertEquals(
            tid,
            transferIdOf(
                message(
                    MessageType.TRANSFER_START,
                    TypedPayload.TransferStart(com.localdrop.protocol.TransferStartPayload(tid, 0L))
                )
            )
        )
    }

    @Test
    fun `zeros hash sentinel has the frozen shape`() {
        assertEquals(64, SenderEngine.M06_HASH_NOT_COMPUTED.length)
        assertTrue(SenderEngine.M06_HASH_NOT_COMPUTED.all { it == '0' })
    }

    @Test
    fun `file name validation mirrors M02 rules`() {
        assertEquals(null, SenderEngine.validateFileName("Backup.zip"))
        assertTrue(SenderEngine.validateFileName("") != null)
        assertTrue(SenderEngine.validateFileName("a/b") != null)
        assertTrue(SenderEngine.validateFileName("a\\b") != null)
        assertTrue(SenderEngine.validateFileName(".") != null)
        assertTrue(SenderEngine.validateFileName("..") != null)
        assertTrue(SenderEngine.validateFileName("x".repeat(256)) != null)
    }

}
