package com.localdrop.service

import com.localdrop.protocol.ErrorCode
import com.localdrop.transfer.TransferOutcome
import com.localdrop.transfer.TransferProgress
import com.localdrop.transfer.TransferSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// M13 bidirectional audit: service-owned outgoing-transfer executions.
//
// The service (not any Activity/ViewModel) owns every send: each startSend
// runs the blocking SendCoordinator.sendFile on a service daemon thread,
// tracked under a handle with its own cooperative-cancel flag. Cancelling
// one send never touches another (per-handle flags; proven by test).
// UI destruction only detaches observers — tracked sends run to their
// terminal outcome regardless. shutdown() (service teardown) cancels all
// in-flight sends and joins every worker.
class OutgoingTransfers(
    private val sender: (
        peerDeviceId: String,
        source: TransferSource,
        code: String,
        isCancelled: () -> Boolean,
        onProgress: (TransferProgress) -> Unit
    ) -> TransferOutcome,
    private val maxConcurrent: Int = MAX_CONCURRENT_SENDS
) {
    companion object {
        // Mirrors the M12 receive-side bound (4 concurrent): bounded socket/
        // thread/file-handle growth. A 5th concurrent send is rejected with
        // TRANSFER_REJECTED instead of queueing unboundedly.
        const val MAX_CONCURRENT_SENDS = 4
    }

    data class SendHandle(val id: Long)

    // Authoritative per-send display row for widgets/notifications.
    data class SendSnapshot(
        val handleId: Long,
        val fileName: String,
        val progress: TransferProgress?
    )

    private val gate = Any()
    private var nextId = 1L
    private val cancelled = mutableMapOf<Long, AtomicBoolean>()
    private val threads = mutableMapOf<Long, Thread>()
    private val terminal = ConcurrentHashMap<Long, TransferOutcome>()
    private val waiters = ConcurrentHashMap<Long, CompletableFuture<TransferOutcome>>()
    private val lastProgress = ConcurrentHashMap<Long, TransferProgress>()
    private val fileNames = ConcurrentHashMap<Long, String>()

    // Transition feeds for notifications/widgets (service wires these).
    // Invoked exactly once per send for the terminal outcome; progress may
    // be invoked many times (callers throttle for battery).
    var onOutcome: ((handleId: Long, outcome: TransferOutcome) -> Unit)? = null
    var onProgress: ((handleId: Long, progress: TransferProgress) -> Unit)? = null

    // Starts one tracked send. Returns null when at capacity (caller maps to
    // a TRANSFER_REJECTED failure; never queues unboundedly). externalCancel
    // lets a submitter (e.g. the UI controller's cancel flag) abort the send
    // through the same cooperative path as cancel(id).
    fun startSend(
        peerDeviceId: String,
        source: TransferSource,
        code: String,
        onProgress: (TransferProgress) -> Unit = {},
        externalCancel: () -> Boolean = { false }
    ): SendHandle? {
        val flag = AtomicBoolean(false)
        val id: Long
        fun safeExternalCancel(): Boolean = try { externalCancel() } catch (e: Exception) { false }
        synchronized(gate) {
            if (cancelled.size >= maxConcurrent) return null
            id = nextId++
            cancelled[id] = flag
            fileNames[id] = source.displayName
            val t = Thread({
                val outcome = try {
                    sender(peerDeviceId, source, code, { flag.get() || safeExternalCancel() }) { p ->
                        lastProgress[id] = p
                        try { onProgress(p) } catch (e: Exception) { }
                        try { this.onProgress?.invoke(id, p) } catch (e: Exception) { }
                    }
                } catch (e: Exception) {
                    TransferOutcome.Failed(null, ErrorCode.INTERNAL_ERROR, "Unexpected error: ${e.message}")
                }
                publishOutcome(id, outcome)
                synchronized(gate) {
                    threads.remove(id)
                    (gate as java.lang.Object).notifyAll()
                }
            }, "localdrop-outgoing-$id")
            t.isDaemon = true
            threads[id] = t
            t.start()
        }
        return SendHandle(id)
    }

    // Cooperative cancel of exactly one send. False when the id is unknown
    // or already terminal (never touches other sends).
    fun cancel(id: Long): Boolean {
        synchronized(gate) {
            return cancelled[id]?.getAndSet(true) == false
        }
    }

    fun awaitOutcome(id: Long, timeoutMs: Long): TransferOutcome? {
        terminal[id]?.let { return it }
        val task = waiters.getOrPut(id) { CompletableFuture() }
        terminal[id]?.let { return it }
        val got = try {
            task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }
        // Race cover: outcome published after the second check but before
        // the future was observed — terminal always has the final word.
        return got ?: terminal[id]
    }

    fun activeCount(): Int = synchronized(gate) { cancelled.size }

    // Last progress snapshot per live send (service builds widget rows).
    fun snapshotProgress(): Map<Long, TransferProgress> = lastProgress.toMap()

    // Authoritative display rows for live sends (file name + progress).
    fun snapshotSends(): List<SendSnapshot> {
        val names = fileNames.toMap()
        val progs = lastProgress.toMap()
        return names.map { (id, name) -> SendSnapshot(id, name, progs[id]) }
    }

    // Service teardown: cancel everything, join every worker, unblock every
    // waiter. Idempotent.
    fun shutdown() {
        val toJoin: List<Thread>
        synchronized(gate) {
            for (flag in cancelled.values) {
                try { flag.set(true) } catch (e: Exception) { }
            }
            toJoin = threads.values.toList()
        }
        for (t in toJoin) {
            try { t.join(5000) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun publishOutcome(id: Long, outcome: TransferOutcome) {
        terminal[id] = outcome
        lastProgress.remove(id)
        fileNames.remove(id)
        synchronized(gate) { cancelled.remove(id) }
        try { waiters.remove(id)?.complete(outcome) } catch (e: Exception) { }
        try { onOutcome?.invoke(id, outcome) } catch (e: Exception) { }
    }
}
