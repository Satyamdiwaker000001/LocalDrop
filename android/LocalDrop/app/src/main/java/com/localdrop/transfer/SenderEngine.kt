package com.localdrop.transfer

import com.localdrop.core.AppConfig
import com.localdrop.core.AppLogger
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.ErrorCode
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.ProtocolError
import com.localdrop.protocol.TransferMetadata
import com.localdrop.protocol.TransferProgressPayload
import com.localdrop.protocol.TransferRequestPayload
import com.localdrop.protocol.TransferStartPayload
import com.localdrop.protocol.CancelPayload
import com.localdrop.protocol.TypedPayload
import com.localdrop.protocol.Uuids
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest

// M06 Android sender engine (ADRs 1-2, 9-11, 13-18, 20-23). Streams one file
// over a dedicated TCP data connection to an M05-authorized Windows
// receiver. Requires: established M04 control channel + completed M05
// authorization (the receiver enforces both; this engine only uses the
// channel). Blocking calls — invoke off the UI thread. Single transfer per
// engine instance at a time (M06 scope; M12 owns orchestration).
class SenderEngine(
    private val localDeviceId: String,
    private val control: ControlConnection,
    private val mailbox: TransferMailbox,
    private val dataPort: Int = AppConfig.DATA_TCP_PORT,
    private val requestTimeoutMs: Long = AppConfig.TRANSFER_REQUEST_TIMEOUT_MS.toLong(),
    private val dataConnectTimeoutMs: Int = AppConfig.DATA_CONNECT_TIMEOUT_MS,
    private val progressIntervalMs: Long = AppConfig.TRANSFER_PROGRESS_INTERVAL_MS,
    private val verifyTimeoutMs: Long = AppConfig.TRANSFER_VERIFY_TIMEOUT_MS,
    private val logger: AppLogger? = null
) {
    data class SendRequest(
        val source: TransferSource,
        val receiverDeviceId: String,
        val transferId: String = Uuids.random(),
        val fileId: String? = null,
        // Null = derive from the control connection's remote address (the
        // receiver we are already talking to; ADR: control-derived endpoint).
        val dataHost: String? = null,
        val isCancelled: () -> Boolean = { false },
        val onProgress: (TransferProgress) -> Unit = {}
    )

    fun send(req: SendRequest): TransferOutcome {
        val transferId = req.transferId
        val fileId = req.fileId ?: run {
            val md = MessageDigest.getInstance("SHA-256")
            val hashBytes = md.digest("${req.source.displayName}|${req.source.sizeBytes}|${req.source.lastModifiedMs}".toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        }
        var state = TransferEngineState.CONNECTING
        emitProgress(req, state, 0L, req.source.sizeBytes, 0L, null)
        // 1. Local source validation (fail before touching the network).
        val nameError = validateFileName(req.source.displayName)
        if (nameError != null) {
            return fail(transferId, ErrorCode.INTERNAL_ERROR, nameError)
        }
        if (req.source.sizeBytes < 0L) {
            return fail(transferId, ErrorCode.INTERNAL_ERROR, "Negative source size.")
        }
        // 2. TRANSFER_REQUEST over control; await ACCEPT/REJECT/ERROR.
        state = TransferEngineState.WAITING_ACCEPTANCE
        val metadata = TransferMetadata(
            transferId = transferId,
            senderDeviceId = localDeviceId,
            receiverDeviceId = req.receiverDeviceId,
            fileId = fileId,
            fileName = req.source.displayName,
            fileSize = req.source.sizeBytes,
            checkpointSize = AppConfig.DEFAULT_CHECKPOINT_BYTES,
            sha256Hex = null, // M11 owns hashing; omitted per M02 ("not yet computed").
            resumeOffset = 0L // M06 always starts at 0; nonzero resume is M10.
        )
        val request = LocalDropMessage(
            type = MessageType.TRANSFER_REQUEST,
            messageId = ProtocolCodec.newMessageId(),
            correlationId = null, sessionId = null, transferId = transferId,
            payload = TypedPayload.TransferRequest(TransferRequestPayload(metadata))
        )
        log("request transfer=$transferId size=${req.source.sizeBytes} name=${req.source.displayName}")
        if (!control.send(request)) {
            return fail(transferId, ErrorCode.NETWORK_LOST, "Control send failed.")
        }
        val response = mailbox.awaitResponse(
            transferId,
            setOf(MessageType.TRANSFER_ACCEPT, MessageType.TRANSFER_REJECT, MessageType.ERROR),
            requestTimeoutMs
        ) ?: return fail(transferId, ErrorCode.TIMEOUT, "No transfer response.")
        var resumeOffset = 0L
        when (response.type) {
            MessageType.TRANSFER_REJECT -> {
                val error = (response.payload as? TypedPayload.TransferReject)?.v?.error
                mailbox.forget(transferId)
                return TransferOutcome.Failed(
                    transferId, error?.code ?: ErrorCode.TRANSFER_REJECTED,
                    error?.message ?: "Transfer rejected."
                )
            }
            MessageType.ERROR -> {
                val error = (response.payload as? TypedPayload.Error)?.v?.error
                mailbox.forget(transferId)
                return TransferOutcome.Failed(
                    transferId, error?.code ?: ErrorCode.INTERNAL_ERROR,
                    error?.message ?: "Transfer error."
                )
            }
            MessageType.TRANSFER_ACCEPT -> {
                val accept = (response.payload as? TypedPayload.TransferAccept)?.v
                if (accept == null || accept.transferId != transferId) {
                    mailbox.forget(transferId)
                    return fail(transferId, ErrorCode.INVALID_PROTOCOL, "Mismatched TRANSFER_ACCEPT.")
                }
                resumeOffset = accept.resumeOffset
            }
            else -> {
                mailbox.forget(transferId)
                return fail(transferId, ErrorCode.INVALID_PROTOCOL, "Unexpected transfer response.")
            }
        }
        // 3. Dedicated data connection (never bulk bytes on control, ADR 9).
        state = TransferEngineState.TRANSFERRING
        val host = req.dataHost ?: deriveControlPeerHost(control)
            ?: return fail(transferId, ErrorCode.NETWORK_LOST, "Cannot resolve receiver host.")
        val dataSocket = Socket()
        try {
            dataSocket.connect(InetSocketAddress(host, dataPort), dataConnectTimeoutMs)
        } catch (e: SocketTimeoutException) {
            return fail(transferId, ErrorCode.TIMEOUT, "Data connect timed out.")
        } catch (e: IOException) {
            return fail(transferId, ErrorCode.NETWORK_LOST, "Data connect failed: ${e.message}")
        }
        try {
            // 4. Data-plane preamble binds this socket to the authorized
            // transfer (37 bytes: transferId + '\n'; receiver rejects unknown).
            try {
                val preamble = (transferId + "\n").toByteArray(Charsets.US_ASCII)
                dataSocket.getOutputStream().write(preamble)
                dataSocket.getOutputStream().flush()
            } catch (e: IOException) {
                return fail(transferId, ErrorCode.NETWORK_LOST, "Preamble write failed: ${e.message}")
            }
            // 5. TRANSFER_START marker, then the byte stream.
            val start = LocalDropMessage(
                type = MessageType.TRANSFER_START,
                messageId = ProtocolCodec.newMessageId(),
                correlationId = null, sessionId = null, transferId = transferId,
                payload = TypedPayload.TransferStart(TransferStartPayload(transferId, resumeOffset))
            )
            if (!control.send(start)) {
                return fail(transferId, ErrorCode.NETWORK_LOST, "Control lost before streaming.")
            }
            return streamBytes(req, transferId, resumeOffset, dataSocket)
        } finally {
            mailbox.forget(transferId)
            try {
                dataSocket.close()
            } catch (e: Exception) {
                // Best effort; outcome already decided.
            }
        }
    }

    private fun streamBytes(req: SendRequest, transferId: String, resumeOffset: Long, dataSocket: Socket): TransferOutcome {
        val hasher = MessageDigest.getInstance("SHA-256")
        val total = req.source.sizeBytes
        val sizer = AdaptiveBlockSizer(total)
        var buffer = ByteArray(sizer.currentBlockSize)
        var transferred = resumeOffset
        val startNanos = System.nanoTime()
        var readNanosTotal = 0L
        var writeNanosTotal = 0L
        var stalls = 0L
        var lastProgressEmitMs = 0L
        var windowBytes = 0L
        var windowStartNanos = startNanos
        var recentBps: Double? = null
        val input: InputStream
        try {
            input = req.source.openStream()
            if (resumeOffset > 0L) {
                var remainingPrefix = resumeOffset
                while (remainingPrefix > 0L) {
                    if (req.isCancelled()) {
                        sendCancelBestEffort(transferId)
                        return TransferOutcome.Cancelled(transferId, 0L)
                    }
                    val toRead = minOf(remainingPrefix, buffer.size.toLong()).toInt()
                    val n = input.read(buffer, 0, toRead)
                    if (n < 0) {
                        val (code, detail) = TransferErrors.internal("Source shrunk during prefix hash (expected $resumeOffset prefix bytes).")
                        sendErrorBestEffort(transferId, code, detail)
                        return fail(transferId, code, detail)
                    }
                    hasher.update(buffer, 0, n)
                    remainingPrefix -= n
                }
            }
        } catch (e: FileNotFoundException) {
            val (code, detail) = TransferErrors.sourceUnavailable(e.message ?: "not found")
            sendErrorBestEffort(transferId, code, detail)
            return fail(transferId, code, detail)
        } catch (e: IOException) {
            val (code, detail) = TransferErrors.sourceUnavailable(e.message ?: "cannot open")
            sendErrorBestEffort(transferId, code, detail)
            return fail(transferId, code, detail)
        } catch (e: SecurityException) {
            val (code, detail) = TransferErrors.sourceUnavailable(e.message ?: "access denied")
            sendErrorBestEffort(transferId, code, detail)
            return fail(transferId, code, detail)
        }
        input.use { stream ->
            val out = try {
                dataSocket.getOutputStream()
            } catch (e: IOException) {
                val (code, detail) = TransferErrors.connectionLost(e.message ?: "no stream")
                sendErrorBestEffort(transferId, code, detail)
                return fail(transferId, code, detail)
            }
            while (true) {
                if (req.isCancelled()) {
                    sendCancelBestEffort(transferId)
                    log("cancelled transfer=$transferId at $transferred/$total")
                    return TransferOutcome.Cancelled(transferId, transferred)
                }
                if (buffer.size != sizer.currentBlockSize) {
                    buffer = ByteArray(sizer.currentBlockSize)
                }
                // Bounded single-block read (never the whole file, ADR 1).
                val readStart = System.nanoTime()
                val n: Int
                try {
                    n = stream.read(buffer, 0, buffer.size)
                } catch (e: IOException) {
                    val (code, detail) = TransferErrors.sourceReadFailed(e.message ?: "read failed")
                    sendErrorBestEffort(transferId, code, detail)
                    return fail(transferId, code, detail)
                }
                val readNanos = System.nanoTime() - readStart
                readNanosTotal += readNanos
                if (n < 0) break // source EOF
                hasher.update(buffer, 0, n)
                if (transferred + n > total) {
                    val (code, detail) = TransferErrors.internal(
                        "Source grew during transfer (declared $total)."
                    )
                    sendErrorBestEffort(transferId, code, detail)
                    return fail(transferId, code, detail)
                }
                // Bounded single-block write; blocking write IS the
                // backpressure mechanism (ADR 11) — no queues anywhere.
                val writeStart = System.nanoTime()
                try {
                    var written = 0
                    while (written < n) {
                        out.write(buffer, written, n - written)
                        written = n // OutputStream.write blocks until done or throws
                    }
                } catch (e: IOException) {
                    val (code, detail) = TransferErrors.connectionLost(e.message ?: "write failed")
                    sendErrorBestEffort(transferId, code, detail)
                    return fail(transferId, code, detail)
                }
                val writeNanos = System.nanoTime() - writeStart
                writeNanosTotal += writeNanos
                transferred += n
                val blockNanos = readNanos + writeNanos
                val stalled = blockNanos / 1_000_000L > AppConfig.ADAPT_STALL_THRESHOLD_MS
                if (stalled) stalls++
                sizer.observe(n.toLong(), blockNanos, stalled)
                windowBytes += n
                // Throttled progress: local snapshot + peer TRANSFER_PROGRESS.
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastProgressEmitMs >= progressIntervalMs) {
                    lastProgressEmitMs = nowMs
                    val windowNanos = System.nanoTime() - windowStartNanos
                    recentBps = if (windowNanos > 0) {
                        windowBytes.toDouble() * 1e9 / windowNanos.toDouble()
                    } else null
                    windowBytes = 0L
                    windowStartNanos = System.nanoTime()
                    emitProgress(req, TransferEngineState.TRANSFERRING, transferred, total,
                        (System.nanoTime() - startNanos) / 1_000_000L, recentBps)
                    if (!sendProgressBestEffort(transferId, transferred, total)) {
                        return fail(transferId, ErrorCode.NETWORK_LOST, "Control lost mid-transfer.")
                    }
                }
            }
        }
        if (transferred != total) {
            val (code, detail) = TransferErrors.internal(
                "Source truncated (declared $total, read $transferred)."
            )
            sendErrorBestEffort(transferId, code, detail)
            return fail(transferId, code, detail)
        }
        // 6. Transport completion: final progress + TRANSFER_COMPLETE with the
        // real whole-stream SHA-256 (M11). Delivery of this frame is
        // necessary but NOT sufficient for success — see step 7.
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
        val average = if (elapsedMs > 0) transferred.toDouble() * 1000.0 / elapsedMs.toDouble() else null
        emitProgress(req, TransferEngineState.TRANSPORT_COMPLETE, transferred, total, elapsedMs, recentBps)
        val sha256Hex = hasher.digest().joinToString("") { "%02x".format(it) }
        val complete = LocalDropMessage(
            type = MessageType.TRANSFER_COMPLETE,
            messageId = ProtocolCodec.newMessageId(),
            correlationId = null, sessionId = null, transferId = transferId,
            payload = TypedPayload.TransferComplete(
                com.localdrop.protocol.TransferCompletePayload(
                    transferId, transferred, sha256Hex
                )
            )
        )
        if (!control.send(complete)) {
            return fail(transferId, ErrorCode.NETWORK_LOST, "Completion signal lost.")
        }
        // 7. Receiver verification gate (audit fix): TransportComplete is
        // reported ONLY after the receiver confirms hash verification AND
        // finalization via the frozen VERIFY_REQUEST/VERIFY_RESULT contract.
        // Bytes on the wire are necessary but NOT sufficient for success.
        return awaitReceiverVerification(req, transferId, transferred, sha256Hex, elapsedMs, average,
            readNanosTotal, writeNanosTotal, stalls, sizer)
    }

    // Sends VERIFY_REQUEST and awaits the receiver's VERIFY_RESULT verdict.
    // Fail-closed: mismatch, timeout, control loss, and receiver errors all
    // map to Failed — never to success. Cancellation is polled in slices so
    // a stuck verification does not hang teardown.
    private fun awaitReceiverVerification(
        req: SendRequest, transferId: String, transferred: Long, sha256Hex: String,
        elapsedMs: Long, average: Double?, readNanosTotal: Long, writeNanosTotal: Long,
        stalls: Long, sizer: AdaptiveBlockSizer
    ): TransferOutcome {
        val verify = LocalDropMessage(
            type = MessageType.VERIFY_REQUEST,
            messageId = ProtocolCodec.newMessageId(),
            correlationId = null, sessionId = null, transferId = transferId,
            payload = TypedPayload.VerifyRequest(
                com.localdrop.protocol.VerifyRequestPayload(transferId, sha256Hex)
            )
        )
        if (!control.send(verify)) {
            return fail(transferId, ErrorCode.NETWORK_LOST,
                "Sent $transferred bytes but the verification request could not be delivered.")
        }
        // M15: the wait scales with the transferred size. The receiver
        // verifies by re-reading the finished file from storage, so a fixed
        // timeout produced a FALSE failure for large files (measured: a
        // 20 GiB transfer could not be verified inside the fixed 60 s).
        val scaledVerifyTimeoutMs = maxOf(verifyTimeoutMs, AppConfig.transferVerifyTimeoutMsFor(transferred))
        val deadline = System.currentTimeMillis() + scaledVerifyTimeoutMs
        var verdict: LocalDropMessage? = null
        while (verdict == null) {
            if (req.isCancelled()) {
                sendCancelBestEffort(transferId)
                return TransferOutcome.Cancelled(transferId, transferred)
            }
            if (!control.isReady()) break
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            verdict = mailbox.awaitResponse(
                transferId,
                setOf(MessageType.VERIFY_RESULT, MessageType.ERROR),
                minOf(remaining, AppConfig.TRANSFER_VERIFY_POLL_MS))
        }
        if (verdict == null) {
            // Distinguish a dead channel from a slow verifier so the UI can
            // say "bytes sent, unconfirmed" vs "connection lost".
            return if (!control.isReady()) {
                fail(transferId, ErrorCode.NETWORK_LOST,
                    "Control lost during verification after sending $transferred bytes.")
            } else {
                fail(transferId, ErrorCode.TIMEOUT,
                    "Sent $transferred bytes but the receiver did not confirm verification.")
            }
        }
        when (verdict.type) {
            MessageType.VERIFY_RESULT -> {
                val result = (verdict.payload as? TypedPayload.VerifyResult)?.v
                if (result == null || result.transferId != transferId) {
                    return fail(transferId, ErrorCode.INVALID_PROTOCOL, "Mismatched VERIFY_RESULT.")
                }
                if (!result.match || !result.sha256Hex.equals(sha256Hex, ignoreCase = true)) {
                    return fail(transferId, ErrorCode.CHECKSUM_MISMATCH,
                        "Receiver reported SHA-256 mismatch. The file must not be treated as received.")
                }
                log("verified transfer=$transferId bytes=$transferred elapsedMs=$elapsedMs")
                return TransferOutcome.TransportComplete(
                    transferId, transferred, elapsedMs, average,
                    TransferMetrics(
                        elapsedMs, transferred, average, readNanosTotal, writeNanosTotal,
                        stalls, sizer.history, sizer.currentBlockSize
                    )
                )
            }
            else -> {
                val error = (verdict.payload as? TypedPayload.Error)?.v?.error
                return TransferOutcome.Failed(
                    transferId, error?.code ?: ErrorCode.INTERNAL_ERROR,
                    error?.message ?: "Receiver verification failed."
                )
            }
        }
    }

    private fun sendProgressBestEffort(transferId: String, transferred: Long, total: Long): Boolean {
        val progress = LocalDropMessage(
            type = MessageType.TRANSFER_PROGRESS,
            messageId = ProtocolCodec.newMessageId(),
            correlationId = null, sessionId = null, transferId = transferId,
            payload = TypedPayload.TransferProgress(
                TransferProgressPayload(transferId, transferred, total)
            )
        )
        return try {
            control.send(progress)
        } catch (e: Exception) {
            false
        }
    }

    private fun sendCancelBestEffort(transferId: String) {
        try {
            control.send(
                LocalDropMessage(
                    type = MessageType.CANCEL,
                    messageId = ProtocolCodec.newMessageId(),
                    correlationId = null, sessionId = null, transferId = transferId,
                    payload = TypedPayload.Cancel(CancelPayload(transferId, "Sender cancelled."))
                )
            )
        } catch (e: Exception) {
        }
    }

    private fun sendErrorBestEffort(transferId: String, code: ErrorCode, detail: String) {
        try {
            control.send(
                LocalDropMessage(
                    type = MessageType.ERROR,
                    messageId = ProtocolCodec.newMessageId(),
                    correlationId = null, sessionId = null, transferId = transferId,
                    payload = TypedPayload.Error(
                        com.localdrop.protocol.ErrorPayload(ProtocolError(code, detail), null)
                    )
                )
            )
        } catch (e: Exception) {
        }
    }

    private fun emitProgress(
        req: SendRequest, state: TransferEngineState, transferred: Long, total: Long,
        elapsedMs: Long, recentBps: Double?
    ) {
        try {
            req.onProgress(
                TransferProgress.compute(req.transferId, total, transferred, elapsedMs, recentBps, state)
            )
        } catch (e: Exception) {
            // Listener bugs must not break the transfer.
        }
    }

    private fun fail(transferId: String?, code: ErrorCode, detail: String): TransferOutcome.Failed {
        log("failed transfer=$transferId code=$code detail=$detail")
        return TransferOutcome.Failed(transferId, code, detail)
    }

    private fun log(message: String) {
        try {
            logger?.i("LocalDropTransfer", message)
        } catch (e: Exception) {
        }
    }

    companion object {
        // 64 zeros = "hash not computed (M11 pending)". Satisfies the frozen
        // M02 64-hex shape; documented meaning, never verification (ADR 20).
        const val M06_HASH_NOT_COMPUTED =
            "0000000000000000000000000000000000000000000000000000000000000000"

        fun validateFileName(name: String): String? {
            if (name.isEmpty() || name.length > 255) return "Invalid file name length."
            if (name.contains('/') || name.contains('\\') || name == "." || name == "..") {
                return "File name must be a base name."
            }
            return null
        }

        // Derives the data-connection host from the control connection we
        // already hold to the receiver (ADR: control-derived endpoint — no
        // discovery changes, no manual IP entry). Parses "/host:port" and
        // "[v6]:port" forms; null when unparseable (caller fails cleanly).
        fun deriveControlPeerHost(control: ControlConnection): String? {
            val raw = control.remoteAddress.trim()
            if (raw.isEmpty() || raw == "(none)") return null
            val noSlash = if (raw.startsWith("/")) raw.substring(1) else raw
            val hostPort = noSlash.substringAfterLast("/", noSlash)
            val host = hostPort.substringBeforeLast(":", hostPort)
            if (host.isEmpty()) return null
            return host.removePrefix("[").removeSuffix("]")
        }
    }
}
