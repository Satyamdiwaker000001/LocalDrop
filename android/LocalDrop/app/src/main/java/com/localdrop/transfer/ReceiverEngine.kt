package com.localdrop.transfer

import com.localdrop.core.AppConfig
import com.localdrop.core.AppLogger
import com.localdrop.discovery.DeviceInfo
import com.localdrop.network.CloseReason
import com.localdrop.network.ControlChannelListener
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.ErrorCode
import com.localdrop.protocol.ErrorPayload
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.ProtocolError
import com.localdrop.protocol.TransferAcceptPayload
import com.localdrop.protocol.TransferCompletePayload
import com.localdrop.protocol.TransferMetadata
import com.localdrop.protocol.TransferRejectPayload
import com.localdrop.protocol.TypedPayload
import com.localdrop.protocol.Uuids
import com.localdrop.security.AuthManager
import com.localdrop.security.AuthorizedSessionStore
import com.localdrop.security.SecurityLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

// M07 Android receiver engine (mirrors Windows ReceiverEngine, M06 ADRs 1-2,
// 9, 11, 13-18, 20-23, reused verbatim for the reverse direction per the M07
// reuse decision A/C). Receives one file over a dedicated TCP data connection
// from an M05-authorized Windows sender. Requires: M04 control channel + M05
// mutual auth + M05 authorized session (all enforced here; the data socket
// additionally proves knowledge of the transferId preamble). Streams socket
// -> fixed reusable buffer -> temp file -> finalize. No second transfer while
// one is active (M12 owns orchestration); no resume (M10); no hashing (M11).
//
// Threading: plain daemon threads like the M04/M06 Android stack (no
// coroutines dependency — ADR 14). Blocking calls must run off the UI thread.
class ReceiverEngine(
    private val local: DeviceInfo,
    private val auth: AuthManager,
    private val sessions: AuthorizedSessionStore,
    private val destination: ReceiverDestination,
    private val checkpoints: com.localdrop.storage.CheckpointDao? = null,
    private val logger: SecurityLogger? = null,
    private val appLogger: AppLogger? = null,
    private val dataPort: Int = AppConfig.DATA_TCP_PORT,
    private val completeTimeoutMs: Long = AppConfig.TRANSFER_COMPLETE_TIMEOUT_MS.toLong(),
    private val progressIntervalMs: Long = AppConfig.TRANSFER_PROGRESS_INTERVAL_MS,
    private val pendingTimeoutMs: Long = AppConfig.DATA_ACCEPT_TIMEOUT_MS
) {
    // Convenience overload for plain directory destinations (JVM tests and
    // app-private dirs on device).
    constructor(
        local: DeviceInfo,
        auth: AuthManager,
        sessions: AuthorizedSessionStore,
        destDir: File,
        checkpoints: com.localdrop.storage.CheckpointDao? = null,
        logger: SecurityLogger? = null,
        appLogger: AppLogger? = null,
        dataPort: Int = AppConfig.DATA_TCP_PORT,
        completeTimeoutMs: Long = AppConfig.TRANSFER_COMPLETE_TIMEOUT_MS.toLong(),
        progressIntervalMs: Long = AppConfig.TRANSFER_PROGRESS_INTERVAL_MS,
        pendingTimeoutMs: Long = AppConfig.DATA_ACCEPT_TIMEOUT_MS
    ) : this(local, auth, sessions, FileReceiverDestination(destDir), checkpoints, logger, appLogger,
        dataPort, completeTimeoutMs, progressIntervalMs, pendingTimeoutMs)

    private data class PendingTransfer(
        val transferId: String,
        val metadata: TransferMetadata,
        val control: ControlConnection,
        val peerDeviceId: String,
        val acceptedAtMs: Long
    )

    private inner class ActiveReceive(
        val transferId: String,
        val metadata: TransferMetadata,
        val control: ControlConnection,
        val peerDeviceId: String,
        var tempFile: File,
        var finalFile: File,
        val dataSocket: Socket
    ) {
        @Volatile var receivedBytes: Long = 0L
        @Volatile var cancelled: Boolean = false
        @Volatile var fileOut: FileOutputStream? = null
        // Set under gate when this transfer's terminal outcome is decided;
        // keeps failure paths from sending duplicate ERROR messages when
        // handler-driven and loop-driven aborts overlap.
        @Volatile var outcomeDelivered: Boolean = false
    }

    private val gate = Any()
    private val waitGate = Any()
    // Serializes the commit step (temp -> final) against abort/failure temp
    // deletion, so a cancel can never slip between "all bytes received" and
    // finalization and leave a final file behind with a Cancelled outcome
    // (or vice versa). Lock order everywhere: finalizeGate -> gate (never
    // the reverse), so no deadlock.
    private val finalizeGate = Any()
    private val outcomeWaiters = ConcurrentHashMap<String, CompletableFuture<TransferOutcome>>()
    // TRANSFER_COMPLETE frames arriving with no matching active transfer
    // (duplicates, strays, or late frames for dead transfers). Bounded: a
    // peer must not grow receiver memory with unmatched frames. Entries for
    // the live transfer are always kept; strays beyond the cap are dropped
    // (fail direction is safe — the live transfer never depends on them).
    private val earlyCompletes = ConcurrentHashMap<String, TransferCompletePayload>()
    // Decided terminal outcomes by transferId (also mirrored to finished).
    // Lets a waiter that registers AFTER the decision still observe it.
    private val terminal = ConcurrentHashMap<String, TransferOutcome>()
    // Terminal outcomes in arrival order. Lets owners without a pre-known
    // transferId observe completion; doubles as the completion feed.
    private val finished = ConcurrentLinkedQueue<TransferOutcome>()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running: Boolean = false
    private val actives = ConcurrentHashMap<String, ActiveReceive>()
    private val pendings = ConcurrentHashMap<String, PendingTransfer>()
    // Temp paths reserved by live (adopted, unterminated) transfers, guarded
    // by gate. Two concurrent transfers for the same fileName must not share
    // one temp file (they would truncate/corrupt each other); the second
    // adopter falls back to a per-transfer temp while the single-transfer
    // `<name>.localdrop-part` contract is preserved whenever uncontended.
    private val reservedTemps = mutableSetOf<String>()
    private val MAX_CONCURRENT_TRANSFERS = 4

    companion object {
        // Cap for unmatched TRANSFER_COMPLETE frames (see earlyCompletes).
        // Normal flows hold at most one; the cap is never hit honestly.
        const val MAX_EARLY_COMPLETES = 16
    }

    var onProgress: ((TransferProgress) -> Unit)? = null

    fun isBusy(): Boolean = synchronized(gate) { actives.size + pendings.size >= MAX_CONCURRENT_TRANSFERS }

    fun activeTransferId(): String? = synchronized(gate) { actives.keys.firstOrNull() ?: pendings.keys.firstOrNull() }

    // M13 service hook: authoritative live-transfer count (actives + pendings)
    // for idle detection. Read-only, no state change.
    fun liveTransferCount(): Int = synchronized(gate) { actives.size + pendings.size }

    fun localPort(): Int = synchronized(gate) { serverSocket?.localPort ?: -1 }

    // Bytes streamed so far by the active transfer (test/diagnostics hook).
    internal fun activeReceivedBytes(): Long = synchronized(gate) { actives.values.firstOrNull()?.receivedBytes ?: -1L }

    // Per-transfer bytes streamed so far (-1 when the transfer is not active).
    // Test/diagnostics hook for multi-session: the no-arg overload above is
    // ambiguous once more than one transfer can be active.
    internal fun activeReceivedBytes(transferId: String): Long =
        synchronized(gate) { actives[transferId]?.receivedBytes ?: -1L }

    // Read-only snapshot of the current inbound transfer for UI display
    // (M07 UI closure). Covers the accepted-but-not-yet-streaming pending
    // slot too, so "incoming request" info appears before bytes flow. Null
    // when idle. No state change, no I/O.
    data class ActiveTransferInfo(
        val transferId: String,
        val fileName: String,
        val fileSize: Long,
        val peerDeviceId: String,
        val streaming: Boolean
    )

    fun activeTransferInfo(): ActiveTransferInfo? = synchronized(gate) {
        actives.values.firstOrNull()?.let {
            ActiveTransferInfo(
                it.transferId, it.metadata.fileName, it.metadata.fileSize,
                it.peerDeviceId, streaming = true)
        } ?: pendings.values.firstOrNull()?.let {
            ActiveTransferInfo(
                it.transferId, it.metadata.fileName, it.metadata.fileSize,
                it.peerDeviceId, streaming = false)
        }
    }


    // Binds the data listener. Idempotent. Must be called before any
    // TRANSFER_ACCEPT can be honored (accept path requires the listener).
    fun start() {
        synchronized(gate) {
            if (running) return
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(java.net.InetSocketAddress(dataPort))
            running = true
            serverSocket = server
            val thread = Thread(::acceptLoop, "localdrop-data-accept")
            thread.isDaemon = true
            acceptThread = thread
            thread.start()
        }
    }

    // Aborts any active/pending transfer (temp cleaned), closes the data
    // listener, releases everything. Idempotent.
    fun stop() {
        val toAbort = mutableListOf<ActiveReceive>()
        val pendingIds = mutableListOf<String>()
        synchronized(gate) {
            if (!running) return
            running = false
            try { serverSocket?.close() } catch (e: Exception) { }
            toAbort.addAll(actives.values)
            pendingIds.addAll(pendings.keys)
            actives.clear()
            pendings.clear()
        }
        try { acceptThread?.join(2000) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(gate) {
            serverSocket = null
            acceptThread = null
        }
        for (a in toAbort) abortActive(a, TransferEngineState.CANCELLED)
        // Pending transfers never reach the stream phase: no stream task will
        // deliver for them, so the terminal outcome is delivered here (waiters
        // must not hang on shutdown).
        for (id in pendingIds) deliverOutcome(id, TransferOutcome.Cancelled(id, 0L))
    }

    fun createHandler(): TransferRequestHandler = TransferRequestHandler(this)

    // Awaits the terminal outcome of one transfer (null on timeout). Sees
    // outcomes decided before the waiter registered (locked against the
    // publish path, so a fast transfer can never slip through).
    fun waitForOutcome(transferId: String, timeoutMs: Long): TransferOutcome? {
        val task: CompletableFuture<TransferOutcome>
        synchronized(waitGate) {
            terminal.remove(transferId)?.let { return it }
            task = outcomeWaiters.getOrPut(transferId) { CompletableFuture() }
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        } finally {
            outcomeWaiters.remove(transferId)
        }
    }

    // Awaits the next terminal outcome of ANY transfer (null on timeout).
    fun waitForAnyOutcome(timeoutMs: Long): TransferOutcome? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            finished.poll()?.let { outcome ->
                outcomeId(outcome)?.let { terminal.remove(it) }
                return outcome
            }
            if (System.currentTimeMillis() >= deadline) {
                return finished.poll()?.also { outcome ->
                    outcomeId(outcome)?.let { terminal.remove(it) }
                }
            }
            try {
                Thread.sleep(25)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    private fun outcomeId(outcome: TransferOutcome): String? = when (outcome) {
        is TransferOutcome.TransportComplete -> outcome.transferId
        is TransferOutcome.Failed -> outcome.transferId
        is TransferOutcome.Cancelled -> outcome.transferId
    }

    // ---- control-plane entry points (called by TransferRequestHandler) ----

    internal fun handleTransferRequest(control: ControlConnection, metadata: TransferMetadata) {
        // 1. Authenticated channel? (M05 mandatory.)
        if (!auth.isAuthenticated(control)) {
            reject(control, metadata.transferId, ErrorCode.PAIRING_REQUIRED, "Authentication required.")
            return
        }
        val peerId = auth.verifiedPeer(control)?.deviceId
        if (peerId == null) {
            reject(control, metadata.transferId, ErrorCode.PAIRING_REQUIRED, "Authentication required.")
            return
        }
        // 2. M05 authorized session for this peer? (peer-level binding: the
        // receiver user authorized THIS sender; the transfer inherits it.)
        if (sessions.sessionsFor(peerId).isEmpty()) {
            try {
                logger?.transactionEvent("rejected", "-", peerId, "transfer without authorized session")
            } catch (e: Exception) { }
            reject(control, metadata.transferId, ErrorCode.TRANSFER_REJECTED, "No authorized session.")
            return
        }
        // 3. Addressed to this receiver?
        if (metadata.receiverDeviceId != local.deviceId) {
            reject(control, metadata.transferId, ErrorCode.TRANSFER_REJECTED, "Not addressed to this receiver.")
            return
        }
        // 4. M10 Resume Support
        val confirmedOffset = checkpoints?.getCheckpointAndMigrate(metadata.senderDeviceId, metadata.fileId, metadata.fileSize, metadata.transferId) ?: 0L
        if (metadata.resumeOffset > confirmedOffset) {
            reject(control, metadata.transferId, ErrorCode.INVALID_PROTOCOL, "Requested resume offset exceeds confirmed checkpoint.")
            return
        }
        // 5. File-name defense in depth (M02 codec already enforces this).
        if (metadata.fileName.contains('/') || metadata.fileName.contains('\\') ||
            metadata.fileName == "." || metadata.fileName == ".."
        ) {
            reject(control, metadata.transferId, ErrorCode.INVALID_PROTOCOL, "Invalid file name.")
            return
        }
        synchronized(gate) {
            if (!running) {
                reject(control, metadata.transferId, ErrorCode.INTERNAL_ERROR, "Receiver not running.")
                return
            }
            pruneStalePendingLocked().let { expired ->
                if (expired.isNotEmpty()) {
                    for (id in expired) deliverOutcome(id, TransferOutcome.Failed(
                        id, ErrorCode.TIMEOUT, "Pending request expired."))
                }
            }
            if (actives.size + pendings.size >= MAX_CONCURRENT_TRANSFERS) {
                reject(control, metadata.transferId, ErrorCode.TRANSFER_REJECTED, "Receiver busy.")
                return
            }
            // Duplicate transferId: never overwrite another transfer's slot.
            if (actives.containsKey(metadata.transferId) || pendings.containsKey(metadata.transferId)) {
                reject(control, metadata.transferId, ErrorCode.TRANSFER_REJECTED, "Duplicate transfer ID.")
                return
            }
            // 6. Advisory free-space precheck (fail-open on uncertainty;
            // write-time failures are still handled mid-stream).
            try {
                val free = destination.freeSpaceBytes()
                if (free != null && free >= 0 && metadata.fileSize > free) {
                    reject(control, metadata.transferId, ErrorCode.NOT_ENOUGH_STORAGE, "Not enough storage.")
                    return
                }
            } catch (e: Exception) {
                // Fail open: the streaming write path reports real failures.
            }
            pendings[metadata.transferId] = PendingTransfer(
                transferId = metadata.transferId,
                metadata = metadata,
                control = control,
                peerDeviceId = peerId,
                acceptedAtMs = System.currentTimeMillis()
            )
        }
        try {
            logger?.transactionEvent("accepted", metadata.transferId, peerId, "size=${metadata.fileSize}")
        } catch (e: Exception) { }
        val accept = LocalDropMessage(
            type = MessageType.TRANSFER_ACCEPT,
            messageId = ProtocolCodec.newMessageId(),
            correlationId = null, sessionId = null, transferId = metadata.transferId,
            payload = TypedPayload.TransferAccept(TransferAcceptPayload(metadata.transferId, confirmedOffset))
        )
        try {
            if (!control.send(accept)) discardPending(metadata.transferId)
        } catch (e: Exception) {
            discardPending(metadata.transferId)
        }
    }

    internal fun handleTransferComplete(control: ControlConnection, done: TransferCompletePayload) {
        rememberComplete(done)
    }

    private fun rememberComplete(done: TransferCompletePayload) {
        val liveId: String? = synchronized(gate) { actives[done.transferId]?.transferId ?: pendings[done.transferId]?.transferId }
        if (done.transferId == liveId) {
            earlyCompletes[done.transferId] = done
            return
        }
        // Stray/duplicate/late frame: keep a bounded number so a chatty or
        // hostile peer cannot grow receiver memory without bound.
        if (earlyCompletes.size >= MAX_EARLY_COMPLETES) return
        earlyCompletes[done.transferId] = done
    }

    internal fun handleCancel(control: ControlConnection, transferId: String) {
        var wasPending = false
        var toAbort: ActiveReceive? = null
        synchronized(gate) {
            toAbort = actives[transferId]
            if (toAbort == null && pendings.containsKey(transferId)) {
                pendings.remove(transferId)
                wasPending = true
            }
        }
        if (toAbort != null) {
            abortActive(toAbort, TransferEngineState.CANCELLED)
            return
        }
        if (wasPending) {
            // Cancelled before the data connection arrived: no stream task
            // will ever run, so the terminal outcome is delivered here.
            deliverOutcome(transferId, TransferOutcome.Cancelled(transferId, 0L))
        }
    }

    internal fun handlePeerError(control: ControlConnection, transferId: String?, error: ProtocolError) {
        // Sender reports its own failure (e.g. source unreadable): fail only
        // the addressed transfer. Failing every transfer on the connection
        // would let one session's failure corrupt an unrelated session
        // multiplexed over the same control channel. A null/empty ID keeps
        // the legacy control-scoped behavior for senders that omit it.
        if (!transferId.isNullOrEmpty()) {
            var toFail: ActiveReceive? = null
            var wasPending = false
            synchronized(gate) {
                val a = actives[transferId]
                if (a != null && a.control == control) {
                    toFail = a
                    actives.remove(transferId)
                } else {
                    val p = pendings[transferId]
                    if (p != null && p.control == control) {
                        pendings.remove(transferId)
                        wasPending = true
                    } else {
                        return // Unknown transfer: touch nothing.
                    }
                }
            }
            if (toFail != null) {
                failActive(toFail, error.code, error.message)
                return
            }
            if (wasPending) deliverOutcome(transferId, TransferOutcome.Failed(transferId, error.code, error.message))
            return
        }
        // Sender reports its own failure (e.g. source unreadable): fail any
        // matching active/pending transfer with the sender's code.
        val toFail = mutableListOf<ActiveReceive>()
        val pendingIds = mutableListOf<String>()
        synchronized(gate) {
            toFail.addAll(actives.values.filter { it.control == control })
            pendingIds.addAll(pendings.values.filter { it.control == control }.map { it.transferId })
            for (a in toFail) actives.remove(a.transferId)
            for (p in pendingIds) pendings.remove(p)
        }
        for (a in toFail) failActive(a, error.code, error.message)
        for (p in pendingIds) deliverOutcome(p, TransferOutcome.Failed(p, error.code, error.message))
    }

    internal fun handleControlClosed(connection: ControlConnection) {
        // Control loss with an active transfer: the sender cannot signal
        // COMPLETE, so fail fast rather than wait indefinitely. Pendings on
        // the lost channel can never reach the stream phase, so their
        // waiters get a terminal outcome here instead of hanging.
        val toFail = mutableListOf<ActiveReceive>()
        val pendingIds = mutableListOf<String>()
        synchronized(gate) {
            toFail.addAll(actives.values.filter { it.control == connection })
            pendingIds.addAll(pendings.values.filter { it.control == connection }.map { it.transferId })
            for (a in toFail) actives.remove(a.transferId)
            for (p in pendingIds) pendings.remove(p)
        }
        for (a in toFail) failActive(a, ErrorCode.NETWORK_LOST, "Control channel lost.")
        for (p in pendingIds) deliverOutcome(p, TransferOutcome.Failed(p, ErrorCode.NETWORK_LOST, "Control channel lost."))
    }

    // ---- data plane ----

    private fun acceptLoop() {
        val server = synchronized(gate) { serverSocket } ?: return
        while (true) {
            val client: Socket = try {
                server.accept()
            } catch (e: Exception) {
                return // closed by stop()
            }
            val thread = Thread({ handleDataClient(client) }, "localdrop-data-client")
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun handleDataClient(client: Socket) {
        client.use { socket ->
            val transferId: String? = try {
                readPreamble(socket)
            } catch (e: Exception) {
                null
            }
            if (transferId == null) {
                try {
                    logger?.transactionEvent("rejected", "-", null, "data connection without valid preamble")
                } catch (e: Exception) { }
                return // using{} closes: unknown/ambiguous sender, no file touched.
            }
            val snapshot: PendingTransfer?
            var expired: List<String> = emptyList()
            synchronized(gate) {
                expired = pruneStalePendingLocked()
                // Read-only match check here: pending is cleared ONLY inside
                // the atomic adoption below (or by a racing CANCEL, which
                // delivers its own outcome). Clearing here would reopen the
                // neither-pending-nor-active gap.
                snapshot = pendings[transferId]
            }
            for (id in expired) deliverOutcome(id, TransferOutcome.Failed(
                id, ErrorCode.TIMEOUT, "Pending request expired."))
            if (snapshot == null) {
                try {
                    logger?.transactionEvent("rejected", transferId, null, "data connection for unknown transfer")
                } catch (e: Exception) { }
                return
            }
            // Build the active record OUTSIDE the lock (filesystem probes),
            // then swap pending->active atomically UNDER the lock: a control
            // CANCEL can only ever observe pending (delivers outcome here)
            // or active (aborts there) — never neither.
            val activeReceive: ActiveReceive
            try {
                activeReceive = ActiveReceive(
                    transferId = transferId,
                    metadata = snapshot.metadata,
                    control = snapshot.control,
                    peerDeviceId = snapshot.peerDeviceId,
                    tempFile = destination.tempFileFor(snapshot.metadata.fileName),
                    finalFile = destination.uniqueFinalFileFor(snapshot.metadata.fileName),
                    dataSocket = socket
                )
            } catch (e: Exception) {
                failPendingNow(snapshot, TransferErrors.destinationUnavailable(e.message ?: "setup failed"))
                return
            }
            var adopted = false
            synchronized(gate) {
                if (pendings.containsKey(transferId)) {
                    // Reserve a per-transfer temp: a concurrent transfer for
                    // the same fileName must stream to its own temp file.
                    var temp = activeReceive.tempFile
                    if (!reservedTemps.add(temp.absolutePath)) {
                        temp = File(temp.parent, "${snapshot.metadata.fileName}.${transferId.take(8)}.localdrop-part")
                        reservedTemps.add(temp.absolutePath)
                    }
                    activeReceive.tempFile = temp
                    pendings.remove(transferId)
                    actives[transferId] = activeReceive
                    adopted = true
                }
            }
            if (!adopted) {
                // Lost a race: a second data socket (active set), or a
                // CANCEL/error consumed pending and delivered its outcome.
                // Never stream.
                return
            }
            receiveBytes(activeReceive)
        }
    }

    // Reads the 37-byte preamble ("<uuid>\n"), capped at 64 bytes.
    internal fun readPreamble(socket: Socket): String? {
        val input = socket.getInputStream()
        val buf = mutableListOf<Byte>()
        while (buf.size < AppConfig.DATA_PREAMBLE_MAX_BYTES) {
            val b = try {
                input.read()
            } catch (e: IOException) {
                return null
            }
            if (b < 0) return null
            if (b == '\n'.code) break
            buf.add(b.toByte())
        }
        if (buf.isEmpty() || buf.size >= AppConfig.DATA_PREAMBLE_MAX_BYTES) return null
        val text = String(buf.toByteArray(), Charsets.US_ASCII)
        if (text.length != 36) return null
        if (!Uuids.isValid(text)) return null
        return text.lowercase()
    }

    private fun receiveBytes(active: ActiveReceive) {
        val metadata = active.metadata
        val sizer = AdaptiveBlockSizer(metadata.fileSize)
        var buffer = ByteArray(sizer.currentBlockSize)
        var received = metadata.resumeOffset
        var lastCheckpointOffset = received
        val startNanos = System.nanoTime()
        var readNanosTotal = 0L
        var writeNanosTotal = 0L
        var stalls = 0L
        var lastEmitMs = 0L
        var windowBytes = 0L
        var windowStartNanos = startNanos
        var recentBps: Double? = null
        emit(active, metadata.fileSize, 0L, 0L, recentBps, TransferEngineState.TRANSFERRING)
        val fileOut: FileOutputStream
        try {
            active.tempFile.parentFile?.mkdirs()
            fileOut = FileOutputStream(active.tempFile, metadata.resumeOffset > 0L)
            if (metadata.resumeOffset > 0L) {
                fileOut.channel.position(metadata.resumeOffset)
            }
            active.fileOut = fileOut
        } catch (e: Exception) {
            failActive(active, TransferErrors.destinationUnavailable(e.message ?: "cannot open").first,
                TransferErrors.destinationUnavailable(e.message ?: "cannot open").second)
            return
        }
        try {
            val socketIn = try {
                active.dataSocket.getInputStream()
            } catch (e: IOException) {
                failActive(active, TransferErrors.connectionLost(e.message ?: "no stream").first,
                    TransferErrors.connectionLost(e.message ?: "no stream").second)
                return
            }
            while (received < metadata.fileSize) {
                if (active.cancelled) {
                    abortActive(active, TransferEngineState.CANCELLED)
                    return
                }
                if (buffer.size != sizer.currentBlockSize) buffer = ByteArray(sizer.currentBlockSize)
                // remaining < buffer.size implies remaining fits Int (buffer
                // <= MAX_BLOCK << Int.MAX_VALUE); the cast is exact, never
                // truncating.
                val remaining = metadata.fileSize - received
                var want = buffer.size
                if (remaining < want) want = remaining.toInt()
                val readStart = System.nanoTime()
                val n: Int
                try {
                    n = socketIn.read(buffer, 0, want)
                } catch (e: IOException) {
                    if (active.cancelled) {
                        abortActive(active, TransferEngineState.CANCELLED)
                    } else {
                        failActive(active, TransferErrors.connectionLost(e.message ?: "read failed").first,
                            TransferErrors.connectionLost(e.message ?: "read failed").second)
                    }
                    return
                }
                val readNanos = System.nanoTime() - readStart
                if (n < 0) {
                    failActive(active, TransferErrors.connectionLost("Sender closed early.").first,
                        TransferErrors.connectionLost("Sender closed early.").second)
                    return
                }
                val writeStart = System.nanoTime()
                try {
                    fileOut.write(buffer, 0, n)
                } catch (e: IOException) {
                    if (active.cancelled) {
                        abortActive(active, TransferEngineState.CANCELLED)
                    } else {
                        val (code, detail) = TransferErrors.classifyWriteFailure(e)
                        failActive(active, code, detail)
                    }
                    return
                }
                val writeNanos = System.nanoTime() - writeStart
                readNanosTotal += readNanos
                writeNanosTotal += writeNanos
                received += n
                active.receivedBytes = received
                if (received - lastCheckpointOffset >= metadata.checkpointSize || received == metadata.fileSize) {
                    try {
                        fileOut.fd.sync()
                        val transfer = com.localdrop.storage.TransferEntity(
                            transferId = active.transferId,
                            fileId = metadata.fileId,
                            sourceDeviceId = metadata.senderDeviceId,
                            destinationDeviceId = "",
                            fileName = metadata.fileName,
                            fileSizeBytes = metadata.fileSize,
                            state = "transferring",
                            updatedEpochMs = System.currentTimeMillis()
                        )
                        val checkpoint = com.localdrop.storage.CheckpointEntity(
                            transferId = active.transferId,
                            confirmedOffsetBytes = received,
                            updatedEpochMs = System.currentTimeMillis()
                        )
                        checkpoints?.saveCheckpoint(transfer, checkpoint)
                        lastCheckpointOffset = received
                    } catch (e: Exception) { }
                }
                val blockNanos = readNanos + writeNanos
                val stalled = blockNanos / 1_000_000L > AppConfig.ADAPT_STALL_THRESHOLD_MS
                if (stalled) stalls++
                sizer.observe(n.toLong(), blockNanos, stalled)
                windowBytes += n
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastEmitMs >= progressIntervalMs) {
                    lastEmitMs = nowMs
                    val windowNanos = System.nanoTime() - windowStartNanos
                    recentBps = if (windowNanos > 0) windowBytes.toDouble() * 1e9 / windowNanos.toDouble() else null
                    windowBytes = 0L
                    windowStartNanos = System.nanoTime()
                    emit(active, metadata.fileSize, received,
                        (System.nanoTime() - startNanos) / 1_000_000L, recentBps,
                        TransferEngineState.TRANSFERRING)
                }
            }
        } finally {
            try { fileOut.close() } catch (e: Exception) { }
            active.fileOut = null
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
        val average = if (elapsedMs > 0) received.toDouble() * 1000.0 / elapsedMs.toDouble() else null
        emit(active, metadata.fileSize, received, elapsedMs, recentBps, TransferEngineState.TRANSFERRING)
        // Byte-count match is necessary but NOT sufficient: the control plane
        // must commit with TRANSFER_COMPLETE (with timeout).
        var done: TransferCompletePayload? = earlyCompletes.remove(metadata.transferId)
        if (done == null) {
            val deadline = System.currentTimeMillis() + completeTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (active.cancelled) {
                    abortActive(active, TransferEngineState.CANCELLED)
                    return
                }
                done = earlyCompletes.remove(metadata.transferId)
                if (done != null) break
                try {
                    Thread.sleep(25)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    abortActive(active, TransferEngineState.CANCELLED)
                    return
                }
            }
        }
        if (done == null) {
            val (code, detail) = TransferErrors.timedOut("No TRANSFER_COMPLETE.")
            failActive(active, code, detail)
            return
        }
        if (done.fileSize != received || done.fileSize != metadata.fileSize) {
            val (code, detail) = TransferErrors.protocolViolation("Completion size mismatch.")
            failActive(active, code, detail)
            return
        }
        val expectedSha = done.sha256Hex.lowercase()
        if (expectedSha == SenderEngine.M06_HASH_NOT_COMPUTED) {
            val (code, detail) = TransferErrors.protocolViolation("SHA-256 hash not provided (M11 required).")
            failActive(active, code, detail)
            return
        }
        val computedSha: String
        try {
            val hasher = java.security.MessageDigest.getInstance("SHA-256")
            val hashBuffer = ByteArray(65536)
            java.io.FileInputStream(active.tempFile).use { fis ->
                while (true) {
                    if (active.cancelled) {
                        abortActive(active, TransferEngineState.CANCELLED)
                        return
                    }
                    val n = fis.read(hashBuffer)
                    if (n < 0) break
                    hasher.update(hashBuffer, 0, n)
                }
            }
            computedSha = hasher.digest().joinToString("") { "%02x".format(it) }.lowercase()
        } catch (e: Exception) {
            val (code, detail) = TransferErrors.destinationUnavailable("Failed to verify SHA-256: ${e.message}")
            failActive(active, code, detail)
            return
        }
        if (computedSha != expectedSha) {
            val (code, detail) = TransferErrors.protocolViolation("SHA-256 mismatch. Expected $expectedSha, got $computedSha")
            try { destination.deleteTempQuietly(active.tempFile) } catch (e: Exception) { }
            try { checkpoints?.clearCheckpoint(metadata.transferId) } catch (e: Exception) { }
            failActive(active, code, detail)
            return
        }
        // Commit under finalizeGate: the decision to finalize, the
        // finalization itself, and the terminal-outcome claim are one atomic
        // step against abort/failure paths. A cancel racing the commit either
        // wins first (no finalize, single Cancelled) or loses (finalize runs
        // fully, single TransportComplete) — never a final file with a
        // Cancelled outcome, and never two outcomes for one transfer.
        // Shape already validated by the frozen M02 codec; the M06 zeros
        // sentinel is explicitly NOT verification (M06 ADR 20, reused).
        synchronized(finalizeGate) {
            if (active.cancelled || !claimOutcome(active)) return
            try {
                // Re-resolve the final name at commit: a concurrent transfer
                // for the same fileName may have landed since adoption. The
                // commit section is serialized by finalizeGate, so the second
                // committer observes the first file and uniquifies per the
                // established collision policy instead of failing or
                // overwriting. (Residual TOCTOU against EXTERNAL actors is
                // accepted per the destination contract.)
                var final = active.finalFile
                if (final.exists()) {
                    final = destination.uniqueFinalFileFor(metadata.fileName)
                    active.finalFile = final
                }
                destination.finalize(active.tempFile, final)
                try { checkpoints?.clearCheckpoint(active.transferId) } catch (e: Exception) { }
            } catch (e: Exception) {
                val (code, detail) = TransferErrors.destinationWriteFailed(e.message ?: "finalize failed")
                completeActiveClaimed(
                    active, TransferOutcome.Failed(active.transferId, code, detail))
                try {
                    logger?.transactionEvent("failed", active.transferId, active.peerDeviceId,
                        "code=${code.wire}")
                } catch (le: Exception) { }
                return
            }
            emit(active, metadata.fileSize, received, elapsedMs, recentBps,
                TransferEngineState.TRANSPORT_COMPLETE)
            completeActiveClaimed(
                active,
                TransferOutcome.TransportComplete(
                    metadata.transferId, received, elapsedMs, average,
                    TransferMetrics(elapsedMs, received, average, readNanosTotal, writeNanosTotal,
                        stalls, sizer.history.toList(), sizer.currentBlockSize)
                )
            )
        }
        try {
            logger?.transactionEvent("transport-complete", metadata.transferId, active.peerDeviceId,
                "bytes=$received elapsedMs=$elapsedMs")
        } catch (e: Exception) { }
    }

    // ---- completion/failure plumbing ----

    private fun emit(
        active: ActiveReceive, total: Long, transferred: Long, elapsedMs: Long,
        recentBps: Double?, state: TransferEngineState
    ) {
        try {
            onProgress?.invoke(
                TransferProgress.compute(active.transferId, total, transferred, elapsedMs, recentBps, state)
            )
        } catch (e: Exception) {
            // Listener bugs must not break the transfer.
        }
    }

    // First-claim-wins terminal outcomes: concurrent terminal paths
    // (stream commit vs racing cancel/failure) yield EXACTLY ONE outcome per
    // transfer. Returns true iff this call owns the outcome.
    private fun claimOutcome(active: ActiveReceive): Boolean {
        synchronized(gate) {
            if (active.outcomeDelivered) return false
            active.outcomeDelivered = true
            return true
        }
    }

    private fun completeActive(active: ActiveReceive, outcome: TransferOutcome) {
        if (!claimOutcome(active)) return
        completeActiveClaimed(active, outcome)
    }

    // Detaches resources and delivers a PRE-CLAIMED outcome (caller holds the
    // claim). Idempotent: safe to invoke on an already-decided transfer.
    private fun completeActiveClaimed(active: ActiveReceive, outcome: TransferOutcome) {
        synchronized(gate) {
            actives.remove(active.transferId)
            reservedTemps.remove(active.tempFile.absolutePath)
        }
        try { active.cancelled = true } catch (e: Exception) { }
        try { active.dataSocket.close() } catch (e: Exception) { }
        deliverOutcome(active.transferId, outcome)
    }

    // Single publishing point for terminal outcomes (active + pending paths):
    // recorded for late waiters, queued for any-order waiters, and pushed to
    // a live waiter — atomically under one gate.
    private fun deliverOutcome(transferId: String, outcome: TransferOutcome) {
        var waiter: CompletableFuture<TransferOutcome>? = null
        synchronized(waitGate) {
            terminal[transferId] = outcome
            finished.offer(outcome)
            outcomeWaiters.remove(transferId)?.let { waiter = it }
        }
        try { waiter?.complete(outcome) } catch (e: Exception) { }
    }

    private fun failActive(active: ActiveReceive, code: ErrorCode, detail: String) {
        active.cancelled = true
        try { active.fileOut?.close() } catch (e: Exception) { }
        try { active.dataSocket.close() } catch (e: Exception) { }
        // M10: Do not delete temp file on failure; retain for resume.
        // Only the first terminal path sends ERROR and delivers; concurrent
        // losers (e.g. a cancel racing this failure) stay silent.
        if (!claimOutcome(active)) return
        try {
            active.control.send(
                LocalDropMessage(
                    type = MessageType.ERROR,
                    messageId = ProtocolCodec.newMessageId(),
                    correlationId = null, sessionId = null, transferId = active.transferId,
                    payload = TypedPayload.Error(ErrorPayload(ProtocolError(code, detail), null))
                )
            )
        } catch (e: Exception) {
            // Best effort; the data socket is already doomed.
        }
        completeActiveClaimed(active, TransferOutcome.Failed(active.transferId, code, detail))
        try {
            logger?.transactionEvent("failed", active.transferId, active.peerDeviceId, "code=${code.wire}")
        } catch (e: Exception) { }
    }

    private fun abortActive(active: ActiveReceive, state: TransferEngineState) {
        active.cancelled = true
        try { active.fileOut?.close() } catch (e: Exception) { }
        try { active.dataSocket.close() } catch (e: Exception) { }
        if (!claimOutcome(active)) return
        if (state == TransferEngineState.CANCELLED) {
            synchronized(finalizeGate) {
                destination.deleteTempQuietly(active.tempFile)
                try { checkpoints?.clearCheckpoint(active.transferId) } catch (e: Exception) { }
            }
            completeActiveClaimed(active, TransferOutcome.Cancelled(active.transferId, active.receivedBytes))
        } else {
            // M10: Do not delete temp file on non-cancellation aborts.
            completeActiveClaimed(active, TransferOutcome.Failed(active.transferId, ErrorCode.INTERNAL_ERROR, "Aborted."))
        }
    }

    // Local cancellation entry point (e.g. user cancels an inbound transfer).
    fun cancelTransfer(transferId: String) {
        var wasPending = false
        var toAbort: ActiveReceive? = null
        synchronized(gate) {
            toAbort = actives[transferId]
            if (toAbort == null && pendings.containsKey(transferId)) {
                pendings.remove(transferId)
                wasPending = true
            }
        }
        if (toAbort != null) {
            abortActive(toAbort, TransferEngineState.CANCELLED)
            return
        }
        if (wasPending) deliverOutcome(transferId, TransferOutcome.Cancelled(transferId, 0L))
    }

    private fun failPendingNow(pending: PendingTransfer, error: Pair<ErrorCode, String>) {
        // The pending slot belongs to this transfer and no stream task will
        // ever run for it: release the slot NOW (not at the 30 s stale
        // prune), or the receiver would wrongly report busy until then.
        synchronized(gate) {
            pendings.remove(pending.transferId)
        }
        try {
            logger?.transactionEvent("failed", pending.transferId, pending.peerDeviceId, "code=${error.first.wire}")
        } catch (e: Exception) { }
        deliverOutcome(pending.transferId, TransferOutcome.Failed(pending.transferId, error.first, error.second))
    }

    private fun discardPending(transferId: String) {
        val removed = synchronized(gate) {
            pendings.remove(transferId) != null
        }
        // The ACCEPT send failed: the sender never learns of this transfer,
        // so no stream task will ever run. Deliver a terminal outcome here
        // instead of leaving waiters hanging until their own timeout.
        if (removed) {
            deliverOutcome(transferId, TransferOutcome.Failed(
                transferId, ErrorCode.NETWORK_LOST, "Failed to send TRANSFER_ACCEPT."))
        }
    }

    // Removes expired pending requests and returns their IDs. Callers deliver
    // a terminal TIMEOUT outcome OUTSIDE the gate (see call sites): expiry
    // must release the slot AND unblock waiters, never silently drop them.
    private fun pruneStalePendingLocked(): List<String> {
        val now = System.currentTimeMillis()
        val stale = pendings.values.filter { now - it.acceptedAtMs >= pendingTimeoutMs }.map { it.transferId }
        for (id in stale) pendings.remove(id)
        return stale
    }

    private fun reject(
        control: ControlConnection, transferId: String, code: ErrorCode, message: String
    ) {
        try {
            logger?.transactionEvent("rejected", transferId, control.peerDeviceId, "code=${code.wire}")
        } catch (e: Exception) { }
        try {
            control.send(
                LocalDropMessage(
                    type = MessageType.TRANSFER_REJECT,
                    messageId = ProtocolCodec.newMessageId(),
                    correlationId = null, sessionId = null, transferId = transferId,
                    payload = TypedPayload.TransferReject(
                        TransferRejectPayload(transferId, ProtocolError(code, message))
                    )
                )
            )
        } catch (e: Exception) {
            // Best effort.
        }
    }

    private fun log(message: String) {
        try {
            appLogger?.i("LocalDropTransfer", message)
        } catch (e: Exception) {
        }
    }
}
