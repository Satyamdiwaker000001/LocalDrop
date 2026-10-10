package com.localdrop.transfer

import com.localdrop.network.CloseReason
import com.localdrop.network.ControlChannelListener
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

// M06 control-plane mailbox: terminal listener in the M04/M05 chain that
// collects transfer-scoped messages per transferId so request/response
// exchanges (REQUEST->ACCEPT/REJECT, START, PROGRESS, COMPLETE, CANCEL,
// ERROR) can be awaited with timeouts. Chain: AuthManager(inner =
// TransactionAuthorizer(inner = TransferMailbox)). Order-preserving: a wait
// that skips non-matching messages leaves the queue exactly as found minus
// the consumed match. Forwards nothing (terminal); callers use await/poll.
class TransferMailbox : ControlChannelListener {
    private val queues = ConcurrentHashMap<String, ArrayDeque<LocalDropMessage>>()
    private val unscoped = ArrayDeque<LocalDropMessage>()

    companion object {
        // Per-transfer control-message cap. A malicious peer must not grow
        // listener memory without bound; beyond the cap the OLDEST message
        // is dropped (fail direction is safe: awaited responses time out
        // into clean failure, never corruption). Normal flows hold a
        // handful of messages; the cap is never hit honestly. The unscoped
        // queue (messages without a transferId, e.g. ERROR) carries the
        // same bound.
        const val MAX_QUEUED_PER_TRANSFER = 128
        const val MAX_QUEUED_UNSCOPED = 128
    }

    private fun queueFor(transferId: String): ArrayDeque<LocalDropMessage> =
        queues.getOrPut(transferId) { ArrayDeque() }

    override fun onMessage(connection: ControlConnection, message: LocalDropMessage) {
        val id = transferIdOf(message)
        if (id != null) {
            val queue = queueFor(id)
            synchronized(queue) {
                queue.addLast(message)
                while (queue.size > MAX_QUEUED_PER_TRANSFER) queue.removeFirst()
                (queue as java.lang.Object).notifyAll()
            }
        } else {
            synchronized(unscoped) {
                unscoped.addLast(message)
                while (unscoped.size > MAX_QUEUED_UNSCOPED) unscoped.removeFirst()
                (unscoped as java.lang.Object).notifyAll()
            }
        }
    }

    override fun onClosed(connection: ControlConnection, reason: CloseReason, detail: String) {
        // No-op: await loops poll with deadlines and observe closure via the
        // engine's own socket handling. Queues are drained per transfer.
    }

    // Waits for the next message for `transferId` whose type is in `types`,
    // preserving the order of all other queued messages. Null on timeout.
    fun awaitResponse(
        transferId: String,
        types: Set<MessageType>,
        timeoutMs: Long
    ): LocalDropMessage? {
        val queue = queueFor(transferId)
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(queue) {
            while (true) {
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    val message = iterator.next()
                    if (message.type in types) {
                        iterator.remove()
                        return message
                    }
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                try {
                    (queue as java.lang.Object).wait(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    fun pendingCount(transferId: String): Int {
        val queue = queues[transferId] ?: return 0
        synchronized(queue) {
            return queue.size
        }
    }

    fun drain(transferId: String): List<LocalDropMessage> {
        val queue = queues[transferId] ?: return emptyList()
        synchronized(queue) {
            val out = queue.toList()
            queue.clear()
            return out
        }
    }

    fun forget(transferId: String) {
        queues.remove(transferId)
    }
}
