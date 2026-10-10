package com.localdrop.network

import com.localdrop.core.AppConfig
import com.localdrop.discovery.DeviceInfo
import com.localdrop.protocol.DecodeOutcome
import com.localdrop.protocol.FrameCodec
import com.localdrop.protocol.HelloAckPayload
import com.localdrop.protocol.HelloPayload
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TypedPayload
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// M04: one direct-TCP control connection to one peer (Complete Spec §5.2,
// PROTOCOL v1.0 §2). Transports frozen M02 frames over TCP:
//
//   M02 Message -> M02 serialization -> M02 frame -> TCP
//
// and back through the M02 incremental decoder (partial reads, coalesced
// frames handled; never assumes one read == one message).
// Out of scope (later milestones): file transfer (M06+), pairing/auth (M05).
// No Compose/Activity coupling: blocking calls are documented as such and
// MUST be invoked off the UI thread; the read loop owns a daemon thread.
class ControlConnection(
    private val local: DeviceInfo,
    private val listener: ControlChannelListener? = null,
    private val connectTimeoutMs: Int = AppConfig.CONTROL_CONNECT_TIMEOUT_MS,
    private val handshakeTimeoutMs: Long = AppConfig.CONTROL_HANDSHAKE_TIMEOUT_MS.toLong()
) {
    @Volatile var state: ControlState = ControlState.DISCONNECTED
        private set

    // Stable per-instance key for ControlConnections tracking.
    val id: String = java.util.UUID.randomUUID().toString()

    // Peer's validated HELLO payload once received (null before handshake).
    // Identity for display/correlation only: NOT trust (M05 owns trust).
    @Volatile var peerHello: HelloPayload? = null
        private set

    val peerDeviceId: String? get() = peerHello?.deviceId
    val remoteAddress: String get() = socket?.remoteSocketAddress?.toString() ?: "(none)"

    private var socket: Socket? = null
    private var readThread: Thread? = null
    private val sendLock = Any()
    private val decoder = FrameCodec.Decoder()
    private val closedNotified = AtomicBoolean(false)
    @Volatile private var shutdownInitiated = false
    private val pending = ConcurrentHashMap<String, CompletableFuture<LocalDropMessage>>()

    // Outbound dial. BLOCKING (connect + handshake, bounded by the two
    // timeouts). Returns true iff the channel reached READY.
    @Synchronized
    fun connect(host: String, port: Int): Boolean {
        if (state != ControlState.DISCONNECTED && state != ControlState.FAILED) return state == ControlState.READY
        closedNotified.set(false)
        shutdownInitiated = false
        state = ControlState.CONNECTING
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            closeSocketQuietly(s)
            return fail(CloseReason.CONNECT_FAILED, "connect failed: ${e.message}")
        }
        return beginOnSocket(s)
    }

    // Inbound side, used by ControlServer for an accepted socket. BLOCKING
    // (handshake, bounded by handshake timeout). Returns true iff READY.
    @Synchronized
    fun accept(inbound: Socket): Boolean {
        if (state != ControlState.DISCONNECTED && state != ControlState.FAILED) return state == ControlState.READY
        closedNotified.set(false)
        shutdownInitiated = false
        state = ControlState.NEGOTIATING
        return beginOnSocket(inbound)
    }

    private fun beginOnSocket(s: Socket): Boolean {
        socket = s
        state = ControlState.NEGOTIATING
        val loop = Thread(::readLoop, "localdrop-control-read")
        loop.isDaemon = true
        readThread = loop
        loop.start()
        // Symmetric handshake: send HELLO immediately, then wait until the
        // read loop has seen both the peer HELLO and the matching HELLO_ACK.
        // All per-cycle handshake state resets here so reconnects start clean.
        val helloId = ProtocolCodec.newMessageId()
        peerHello = null
        sawHello = false
        sawAck = false
        pendingHandshakeId = helloId
        handshakeDone = CountDownLatch(1)
        if (!sendNow(helloMessage(helloId))) {
            return fail(CloseReason.IO_ERROR, "failed to send HELLO")
        }
        return try {
            if (handshakeDone.await(handshakeTimeoutMs, TimeUnit.MILLISECONDS)) {
                state == ControlState.READY
            } else {
                fail(CloseReason.HANDSHAKE_TIMEOUT, "handshake timed out")
                false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(CloseReason.IO_ERROR, "handshake wait interrupted")
            false
        }
    }

    // Guarded handshake bookkeeping, touched by both connect() and readLoop.
    @Volatile private var pendingHandshakeId: String? = null
    @Volatile private var handshakeDone: CountDownLatch = CountDownLatch(0)
    @Volatile private var sawHello = false
    @Volatile private var sawAck = false

    // Sends one M02 message as a single complete frame. Thread-safe:
    // concurrent senders serialize so frame bytes can never interleave.
    // Returns false if the channel is not usable. No unbounded queue.
    fun send(message: LocalDropMessage): Boolean {
        val s = socket ?: return false
        if (s.isClosed) return false
        val frame = try {
            FrameCodec.encodeMessage(message)
        } catch (e: IllegalArgumentException) {
            return false
        }
        synchronized(sendLock) {
            return try {
                val out = s.getOutputStream()
                out.write(frame)
                out.flush()
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    // Sends a request and awaits the correlated response (M02 correlationId
    // == request messageId). BLOCKING up to timeoutMs; call off the UI
    // thread and never from inside onMessage. Returns null on timeout,
    // cancellation, send failure, or channel close.
    fun sendAndReceive(message: LocalDropMessage, timeoutMs: Long): LocalDropMessage? {
        val future = CompletableFuture<LocalDropMessage>()
        pending[message.messageId] = future
        try {
            if (!send(message)) {
                pending.remove(message.messageId)
                return null
            }
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                pending.remove(message.messageId)
                null
            }
        } finally {
            pending.remove(message.messageId)
        }
    }

    // Graceful, idempotent disconnect: stops the read loop, closes the
    // socket, cancels pending requests, notifies LOCAL_DISCONNECT exactly
    // once per cycle. Calling twice never crashes.
    @Synchronized
    fun disconnect() {
        if (state == ControlState.DISCONNECTED) return
        state = ControlState.DISCONNECTING
        shutdownInitiated = true
        val s = socket
        socket = null
        closeSocketQuietly(s)
        failPendingAll()
        try {
            readThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        readThread = null
        state = ControlState.DISCONNECTED
        notifyClosed(CloseReason.LOCAL_DISCONNECT, "local disconnect")
    }

    fun isReady(): Boolean = state == ControlState.READY

    // ---- internals ----

    private fun helloMessage(helloId: String): LocalDropMessage = LocalDropMessage(
        type = MessageType.HELLO,
        messageId = helloId,
        correlationId = null,
        sessionId = null,
        transferId = null,
        payload = TypedPayload.Hello(
            HelloPayload(
                deviceId = local.deviceId,
                deviceName = local.deviceName,
                deviceType = local.deviceType,
                controlPort = AppConfig.CONTROL_TCP_PORT.toLong(),
                capabilities = local.capabilities
            )
        )
    )

    private fun sendNow(message: LocalDropMessage): Boolean = send(message)

    private fun readLoop() {
        val s = socket ?: return
        val buf = ByteArray(8192)
        try {
            val input = s.getInputStream()
            while (!shutdownInitiated) {
                val n = try {
                    input.read(buf)
                } catch (e: SocketException) {
                    break // closed locally or reset
                } catch (e: IOException) {
                    break
                }
                if (n < 0) break // orderly peer shutdown
                if (n == 0) continue
                for (event in decoder.feed(buf, 0, n)) {
                    when (event) {
                        is FrameCodec.FrameEvent.Failure -> {
                            fail(CloseReason.PROTOCOL_VIOLATION, "framing: ${event.detail}")
                            return
                        }
                        is FrameCodec.FrameEvent.Message -> handleFrame(event.jsonUtf8)
                    }
                    if (shutdownInitiated || state == ControlState.FAILED) return
                }
            }
        } finally {
            if (!shutdownInitiated) {
                // Peer went away (or I/O broke) without a local disconnect.
                state = ControlState.FAILED
                closeSocketQuietly(socket)
                socket = null
                failPendingAll()
                state = ControlState.DISCONNECTED
                notifyClosed(CloseReason.REMOTE_CLOSE, "peer closed connection")
            }
        }
    }

    private fun handleFrame(jsonUtf8: ByteArray) {
        val outcome = try {
            ProtocolCodec.decodeUtf8(jsonUtf8)
        } catch (e: Exception) {
            fail(CloseReason.PROTOCOL_VIOLATION, "decode threw")
            return
        }
        if (outcome !is DecodeOutcome.Success) {
            fail(CloseReason.PROTOCOL_VIOLATION, "invalid message: ${outcome}")
            return
        }
        val message = outcome.message
        if (state == ControlState.NEGOTIATING) {
            handleHandshakeMessage(message)
            return
        }
        if (state != ControlState.READY) return // closing/closed: drop
        // Post-handshake: complete correlation waiters, then deliver.
        message.correlationId?.let { pending.remove(it)?.complete(message) }
        try {
            listener?.onMessage(this, message)
        } catch (e: Exception) {
            // Listener bugs must not kill the read loop.
        }
    }

    private fun handleHandshakeMessage(message: LocalDropMessage) {
        when (message.type) {
            MessageType.HELLO -> {
                val hello = (message.payload as? TypedPayload.Hello)?.v
                if (hello == null) {
                    fail(CloseReason.PROTOCOL_VIOLATION, "bad HELLO payload")
                    return
                }
                peerHello = hello
                sawHello = true
                val ack = LocalDropMessage(
                    type = MessageType.HELLO_ACK,
                    messageId = ProtocolCodec.newMessageId(),
                    correlationId = message.messageId,
                    sessionId = null,
                    transferId = null,
                    payload = TypedPayload.HelloAck(
                        HelloAckPayload(local.deviceId, local.deviceName, local.deviceType, true)
                    )
                )
                if (!sendNow(ack)) {
                    fail(CloseReason.IO_ERROR, "failed to send HELLO_ACK")
                    return
                }
                maybeHandshakeComplete()
            }
            MessageType.HELLO_ACK -> {
                val ack = (message.payload as? TypedPayload.HelloAck)?.v
                if (ack == null || !ack.accepted || ack.correlationIdMismatch(message)) {
                    fail(CloseReason.PROTOCOL_VIOLATION, "bad HELLO_ACK")
                    return
                }
                sawAck = true
                maybeHandshakeComplete()
            }
            else -> {
                fail(CloseReason.PROTOCOL_VIOLATION, "unexpected ${message.type} during handshake")
            }
        }
    }

    private fun HelloAckPayload.correlationIdMismatch(message: LocalDropMessage): Boolean =
        message.correlationId != pendingHandshakeId

    private fun maybeHandshakeComplete() {
        if (sawHello && sawAck && state == ControlState.NEGOTIATING) {
            state = ControlState.READY
            handshakeDone.countDown()
        }
    }

    // Transitions to FAILED + notifies exactly once. Returns false so callers
    // can `return fail(...)` directly.
    private fun fail(reason: CloseReason, detail: String): Boolean {
        shutdownInitiated = true
        val s = socket
        socket = null
        closeSocketQuietly(s)
        failPendingAll()
        if (state != ControlState.DISCONNECTED) state = ControlState.FAILED
        try {
            if (Thread.currentThread() != readThread) readThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        notifyClosed(reason, detail)
        return false
    }

    private fun notifyClosed(reason: CloseReason, detail: String) {
        if (closedNotified.compareAndSet(false, true)) {
            try {
                listener?.onClosed(this, reason, detail)
            } catch (e: Exception) {
                // Listener bugs must not propagate.
            }
        }
    }

    private fun failPendingAll() {
        for ((_, future) in pending) future.completeExceptionally(IOException("connection closed"))
        pending.clear()
    }

    private fun closeSocketQuietly(s: Socket?) {
        try {
            s?.close()
        } catch (e: Exception) {
            // Best effort during shutdown.
        }
    }
}
