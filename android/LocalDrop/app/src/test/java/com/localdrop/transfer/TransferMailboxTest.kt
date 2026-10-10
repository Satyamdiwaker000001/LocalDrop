package com.localdrop.transfer

import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TypedPayload
import com.localdrop.protocol.TransferProgressPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mailbox ordering + per-transfer bound (audit hardening).
class TransferMailboxTest {

    private fun progress(tid: String, n: Long): LocalDropMessage = LocalDropMessage(
        type = MessageType.TRANSFER_PROGRESS,
        messageId = ProtocolCodec.newMessageId(),
        correlationId = null, sessionId = null, transferId = tid,
        payload = TypedPayload.TransferProgress(TransferProgressPayload(tid, n, 100L))
    )

    @Test
    fun `await skips non-matching types preserving order`() {
        val mailbox = TransferMailbox()
        val tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        mailbox.onMessage(mockConnection(), progress(tid, 1L))
        mailbox.onMessage(mockConnection(), progress(tid, 2L))
        // No START ever arrives: timeout, queue intact.
        assertNull(mailbox.awaitResponse(tid, setOf(MessageType.TRANSFER_START), 200L))
        assertEquals(2, mailbox.pendingCount(tid))
        // Matching consume leaves the rest ordered.
        val first = mailbox.awaitResponse(tid, setOf(MessageType.TRANSFER_PROGRESS), 200L)
        assertEquals(1L, ((first!!.payload as TypedPayload.TransferProgress).v.transferredBytes))
        assertEquals(1, mailbox.pendingCount(tid))
    }

    @Test
    fun `per-transfer queue is bounded keeping newest`() {
        val mailbox = TransferMailbox()
        val tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        repeat(200) { mailbox.onMessage(mockConnection(), progress(tid, it.toLong())) }
        assertEquals(TransferMailbox.MAX_QUEUED_PER_TRANSFER, mailbox.pendingCount(tid))
        // Newest (199) retained: drop-oldest direction verified by draining.
        val all = mailbox.drain(tid)
        assertEquals(TransferMailbox.MAX_QUEUED_PER_TRANSFER, all.size)
        assertEquals(
            199L,
            (((all.last().payload) as TypedPayload.TransferProgress).v.transferredBytes)
        )
        assertTrue(all.map {
            ((it.payload) as TypedPayload.TransferProgress).v.transferredBytes
        }.zipWithNext().all { (a, b) -> a < b })
    }

    private fun mockConnection(): com.localdrop.network.ControlConnection {
        // Listener-only use: the connection reference is unused by the box.
        return com.localdrop.network.ControlConnection(
            com.localdrop.discovery.DeviceInfo(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "T", "android"
            ),
            null
        )
    }
}
