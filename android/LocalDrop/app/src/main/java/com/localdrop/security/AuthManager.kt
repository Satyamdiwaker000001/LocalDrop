package com.localdrop.security

import com.localdrop.core.AppConfig
import com.localdrop.discovery.DeviceInfo
import com.localdrop.discovery.DiscoveryClock
import com.localdrop.discovery.SystemDiscoveryClock
import com.localdrop.network.CloseReason
import com.localdrop.network.ControlChannelListener
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.AuthRequestPayload
import com.localdrop.protocol.AuthResponsePayload
import com.localdrop.protocol.JsonCanonical
import com.localdrop.protocol.JsonParseResult
import com.localdrop.protocol.JsonValue
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TypedPayload
import java.security.Signature
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// M05: mutual peer authentication over an M04 control connection
// (shared-protocol/authentication.md §§2-5). Wraps a connection listener:
// AUTH_REQUEST/AUTH_RESPONSE are consumed here, everything else is forwarded
// to `inner`, so frozen M04 behavior is untouched.
// Both sides send auth-hello (no leader election; per-direction FIFO keeps
// the state machine deterministic). Failure fails closed: the control
// connection is dropped and nothing is authorized. Handshake success proves
// identity ONLY, never trust or authorization.

data class VerifiedPeer(val deviceId: String, val publicKeySpki: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VerifiedPeer) return false
        return deviceId == other.deviceId && publicKeySpki.contentEquals(other.publicKeySpki)
    }
    override fun hashCode(): Int = 31 * deviceId.hashCode() + publicKeySpki.contentHashCode()
}

sealed interface AuthOutcome {
    data class Authenticated(val peer: VerifiedPeer) : AuthOutcome
    data class Failed(val reason: String) : AuthOutcome // generic text, never secrets
}

interface AuthEvents {
    fun onAuthenticated(connection: ControlConnection, peer: VerifiedPeer)
    fun onAuthFailed(connection: ControlConnection, reason: String)
}

private enum class AuthPhase { HELLO_SENT, PEER_KNOWN, PROOF_SENT, AWAIT_RESULT, DONE, FAILED }

private class AuthHandshake(
    val myNonce: ByteArray,
    var helloSent: Boolean = false,
    var peerHello: AuthProtocol.ParsedHello? = null,
    var phase: AuthPhase = AuthPhase.HELLO_SENT,
    val future: CompletableFuture<AuthOutcome> = CompletableFuture(),
    val deadlineMillis: Long = 0L
)

// Receiver-side PAIR submit result (sender perspective).
sealed interface PairSubmitOutcome {
    data object Accepted : PairSubmitOutcome
    data class Rejected(val reason: String) : PairSubmitOutcome
    data class Failed(val reason: String) : PairSubmitOutcome
}

class AuthManager(
    private val local: DeviceInfo,
    private val store: DeviceIdentityStore,
    private val logger: SecurityLogger,
    private val authTimeoutMs: Long = AppConfig.AUTH_TIMEOUT_MS.toLong(),
    private val clock: DiscoveryClock = SystemDiscoveryClock,
    // Next listener in the chain (receives non-AUTH messages). A var so the
    // receiver wiring (AuthManager -> TransactionAuthorizer, which itself
    // needs the AuthManager reference) can be assembled after construction.
    var inner: ControlChannelListener? = null,
    private val events: AuthEvents? = null
) : ControlChannelListener {
    private val handshakes = ConcurrentHashMap<String, AuthHandshake>()
    private val verified = ConcurrentHashMap<String, VerifiedPeer>()
    private val pairPending = ConcurrentHashMap<String, CompletableFuture<PairSubmitOutcome>>()

    var senderObserver: ((connectionId: String, state: SenderAuthUiState) -> Unit)? = null

    // Starts (or joins) mutual authentication on a READY control connection.
    // Returns a future completing with Authenticated/Failed (bounded by the
    // auth timeout; connection loss also completes it). Never throws.
    fun beginAuth(connection: ControlConnection): CompletableFuture<AuthOutcome> {
        handshakes[connection.id]?.let { return it.future }
        val me = try {
            store.identity()
        } catch (e: Exception) {
            return CompletableFuture.completedFuture(AuthOutcome.Failed("local identity unavailable"))
        }
        val hs = AuthHandshake(
            myNonce = AuthProtocol.newNonce(),
            deadlineMillis = clock.nowMillis() + authTimeoutMs
        )
        // Atomic: a responder-side creation on the read thread may race us.
        // The loser joins the winner so exactly one hello goes on the wire.
        val prev = handshakes.putIfAbsent(connection.id, hs)
        if (prev != null) return prev.future
        publishSender(connection, SenderPhase.AUTHENTICATING, null)
        if (!sendHello(connection, hs, me)) {
            failHandshake(connection, hs, "send failed")
            return hs.future
        }
        startWatchdog(connection, hs)
        return hs.future
    }

    fun isAuthenticated(connection: ControlConnection): Boolean = verified.containsKey(connection.id)

    fun verifiedPeer(connection: ControlConnection): VerifiedPeer? = verified[connection.id]

    // Submits a receiver-displayed code over this connection and awaits the
    // PAIR_RESPONSE (one outstanding per connection; concurrent attempts get
    // Failed("busy")). Returns Accepted / Rejected(reason) / Failed.
    fun senderSubmitCode(
        connection: ControlConnection,
        code: String,
        timeoutMs: Long = 10000L
    ): PairSubmitOutcome {
        val normalized = AuthUiState.normalizeInput(code)
        if (normalized.length != AppConfig.TRANSACTION_CODE_DIGITS) {
            return PairSubmitOutcome.Failed("code must be 6 digits")
        }
        val me = try {
            store.identity()
        } catch (e: Exception) {
            return PairSubmitOutcome.Failed("local identity unavailable")
        }
        val future = CompletableFuture<PairSubmitOutcome>()
        if (pairPending.putIfAbsent(connection.id, future) != null) {
            return PairSubmitOutcome.Failed("busy")
        }
        publishSender(connection, SenderPhase.SUBMITTING, null)
        try {
            val request = LocalDropMessage(
                type = MessageType.PAIR_REQUEST,
                messageId = ProtocolCodec.newMessageId(),
                correlationId = null, sessionId = null, transferId = null,
                payload = TypedPayload.PairRequest(
                    com.localdrop.protocol.PairRequestPayload(me.deviceId, local.deviceName, normalized)
                )
            )
            if (!connection.send(request)) {
                pairPending.remove(connection.id)
                publishSender(connection, SenderPhase.FAILED, "send failed")
                return PairSubmitOutcome.Failed("send failed")
            }
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                pairPending.remove(connection.id)
                publishSender(connection, SenderPhase.FAILED, "no response")
                PairSubmitOutcome.Failed("no response")
            }
        } finally {
            pairPending.remove(connection.id)
        }
    }

    // ---- ControlChannelListener (wrapping) ----

    override fun onMessage(connection: ControlConnection, message: LocalDropMessage) {
        when (message.type) {
            MessageType.AUTH_REQUEST -> handleAuthRequest(connection, message)
            MessageType.AUTH_RESPONSE -> handleAuthResponse(connection, message)
            MessageType.PAIR_RESPONSE -> {
                val pending = pairPending.remove(connection.id)
                if (pending != null) {
                    val payload = (message.payload as? TypedPayload.PairResponse)?.v
                    if (payload != null && payload.accepted) {
                        publishSender(connection, SenderPhase.AUTHENTICATED, null)
                        publishSender(connection, SenderPhase.AUTHORIZED, null)
                        pending.complete(PairSubmitOutcome.Accepted)
                    } else {
                        val reason = payload?.reason ?: "rejected"
                        publishSender(connection, SenderPhase.FAILED, reason)
                        pending.complete(PairSubmitOutcome.Rejected(reason))
                    }
                } else {
                    inner?.onMessage(connection, message)
                }
            }
            else -> inner?.onMessage(connection, message)
        }
    }

    override fun onClosed(connection: ControlConnection, reason: CloseReason, detail: String) {
        handshakes.remove(connection.id)?.future?.complete(AuthOutcome.Failed("connection closed"))
        pairPending.remove(connection.id)?.complete(PairSubmitOutcome.Failed("connection closed"))
        verified.remove(connection.id)
        try {
            inner?.onClosed(connection, reason, detail)
        } catch (e: Exception) {
            // Listener bugs must not propagate.
        }
    }

    // ---- handshake internals ----

    private fun sendHello(
        connection: ControlConnection, hs: AuthHandshake, me: DeviceIdentity
    ): Boolean {
        val hello = LocalDropMessage(
            type = MessageType.AUTH_REQUEST,
            messageId = ProtocolCodec.newMessageId(),
            correlationId = null, sessionId = null, transferId = null,
            payload = TypedPayload.AuthRequest(
                AuthRequestPayload(
                    me.deviceId,
                    AppConfig.AUTH_METHOD_ID,
                    AuthProtocol.helloToken(me.deviceId, me.publicKeySpki, hs.myNonce)
                )
            )
        )
        if (!connection.send(hello)) return false
        hs.helloSent = true
        return true
    }

    private fun sendProof(
        connection: ControlConnection, hs: AuthHandshake, me: DeviceIdentity
    ): Boolean {
        val peer = hs.peerHello ?: return false
        val transcript = AuthProtocol.buildTranscript(
            AppConfig.AUTH_METHOD_ID, me.deviceId, hs.myNonce, peer.deviceId, peer.nonce
        )
        val signature = try {
            store.sign(transcript)
        } catch (e: Exception) {
            return false
        }
        val proof = LocalDropMessage(
            type = MessageType.AUTH_REQUEST,
            messageId = ProtocolCodec.newMessageId(),
            correlationId = null, sessionId = null, transferId = null,
            payload = TypedPayload.AuthRequest(
                AuthRequestPayload(me.deviceId, AppConfig.AUTH_METHOD_ID, AuthProtocol.proofToken(signature))
            )
        )
        if (!connection.send(proof)) return false
        hs.phase = AuthPhase.PROOF_SENT
        return true
    }

    private fun handleAuthRequest(connection: ControlConnection, message: LocalDropMessage) {
        val payload = (message.payload as? TypedPayload.AuthRequest)?.v
        if (payload == null) {
            rejectAndClose(connection, "malformed auth request")
            return
        }
        if (payload.method != AppConfig.AUTH_METHOD_ID) {
            rejectAndClose(connection, "unknown auth method")
            return
        }
        var hs = handshakes[connection.id]
        if (hs == null) {
            // Responder path: peer initiated, we have no state yet. Atomic
            // for the same race in reverse (a test-thread beginAuth may win;
            // the loser joins the winner).
            val created = AuthHandshake(
                myNonce = AuthProtocol.newNonce(),
                deadlineMillis = clock.nowMillis() + authTimeoutMs
            )
            val raced = handshakes.putIfAbsent(connection.id, created)
            hs = raced ?: created
            if (raced == null) {
                publishSender(connection, SenderPhase.AUTHENTICATING, null)
                startWatchdog(connection, hs)
            }
        }
        val handshake = hs
        synchronized(handshake) {
            if (handshake.phase == AuthPhase.DONE || handshake.phase == AuthPhase.FAILED) return
            when (peekStep(payload.token)) {
                AuthProtocol.STEP_HELLO -> {
                    if (handshake.peerHello != null) {
                        rejectAndClose(connection, "duplicate hello")
                        return
                    }
                    val me = localIdentityOrReject(connection, handshake) ?: return
                    val parsed = when (val r = AuthProtocol.parseHello(payload.token)) {
                        is com.localdrop.protocol.PayloadResult.Success -> r.value
                        is com.localdrop.protocol.PayloadResult.Failure -> {
                            rejectAndClose(connection, "bad hello token")
                            return
                        }
                    }
                    if (!checkBinding(parsed)) {
                        rejectAndClose(connection, "deviceId binding mismatch")
                        return
                    }
                    handshake.peerHello = parsed
                    handshake.phase = AuthPhase.PEER_KNOWN
                    if (!handshake.helloSent && !sendHello(connection, handshake, me)) {
                        rejectAndClose(connection, "send failed")
                        return
                    }
                    if (!sendProof(connection, handshake, me)) {
                        rejectAndClose(connection, "send failed")
                    }
                }
                AuthProtocol.STEP_PROOF -> {
                    val peer = handshake.peerHello
                    if (peer == null) {
                        rejectAndClose(connection, "proof before hello")
                        return
                    }
                    val proof = when (val r = AuthProtocol.parseProof(payload.token)) {
                        is com.localdrop.protocol.PayloadResult.Success -> r.value
                        is com.localdrop.protocol.PayloadResult.Failure -> {
                            rejectAndClose(connection, "bad proof token")
                            return
                        }
                    }
                    val me = localIdentityOrReject(connection, handshake) ?: return
                    val transcript = AuthProtocol.buildTranscript(
                        AppConfig.AUTH_METHOD_ID, me.deviceId, handshake.myNonce,
                        peer.deviceId, peer.nonce
                    )
                    if (!verifySignature(transcript, proof.rawSignature, peer.publicKeySpki)) {
                        rejectAndClose(connection, "bad proof signature")
                        return
                    }
                    val response = LocalDropMessage(
                        type = MessageType.AUTH_RESPONSE,
                        messageId = ProtocolCodec.newMessageId(),
                        correlationId = null, sessionId = null, transferId = null,
                        payload = TypedPayload.AuthResponse(
                            AuthResponsePayload(me.deviceId, true, null)
                        )
                    )
                    if (!connection.send(response)) {
                        rejectAndClose(connection, "send failed")
                        return
                    }
                    handshake.phase = AuthPhase.AWAIT_RESULT
                }
                else -> rejectAndClose(connection, "unexpected auth step")
            }
        }
    }

    private fun handleAuthResponse(connection: ControlConnection, message: LocalDropMessage) {
        val hs = handshakes[connection.id] ?: return // stray result: ignore, stay fail-closed
        synchronized(hs) {
            if (hs.phase != AuthPhase.AWAIT_RESULT) {
                rejectAndClose(connection, "unexpected auth result")
                return
            }
            val payload = (message.payload as? TypedPayload.AuthResponse)?.v
            if (payload == null || !payload.accepted) {
                rejectAndClose(connection, "peer rejected authentication")
                return
            }
            val peer = hs.peerHello ?: run {
                rejectAndClose(connection, "no peer hello")
                return
            }
            hs.phase = AuthPhase.DONE
            val verifiedPeer = VerifiedPeer(peer.deviceId, peer.publicKeySpki)
            verified[connection.id] = verifiedPeer
            publishSender(connection, SenderPhase.AUTHENTICATED, null)
            logger.authEvent(true, peer.deviceId, "mutual authentication complete")
            try {
                events?.onAuthenticated(connection, verifiedPeer)
            } catch (e: Exception) {
                // Listener bugs must not corrupt auth state.
            }
            hs.future.complete(AuthOutcome.Authenticated(verifiedPeer))
        }
    }

    private fun localIdentityOrReject(
        connection: ControlConnection, hs: AuthHandshake
    ): DeviceIdentity? {
        return try {
            store.identity()
        } catch (e: Exception) {
            rejectAndClose(connection, "local failure")
            null
        }
    }

    private fun checkBinding(hello: AuthProtocol.ParsedHello): Boolean {
        return try {
            DeviceIdentityCrypto.deriveDeviceId(hello.publicKeySpki) == hello.deviceId
        } catch (e: Exception) {
            false
        }
    }

    private fun verifySignature(transcript: ByteArray, rawSignature: ByteArray, spki: ByteArray): Boolean {
        return try {
            val der = DeviceIdentityCrypto.rawToDer(rawSignature)
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(DeviceIdentityCrypto.publicKeyFromSpki(spki))
                update(transcript)
            }.verify(der)
        } catch (e: Exception) {
            false
        }
    }

    private fun peekStep(token: String): String? {
        return when (val parsed = JsonCanonical.parse(token)) {
            is JsonParseResult.Ok -> ((parsed.value as? JsonValue.Obj)
                ?.entries?.get("step") as? JsonValue.Str)?.value
            is JsonParseResult.Err -> null
        }
    }

    private fun rejectAndClose(connection: ControlConnection, logDetail: String) {
        val hs = handshakes[connection.id]
        if (hs != null) {
            synchronized(hs) {
                if (hs.phase == AuthPhase.DONE || hs.phase == AuthPhase.FAILED) return
                hs.phase = AuthPhase.FAILED
            }
        }
        val peerId = hs?.peerHello?.deviceId
        logger.authEvent(false, peerId, logDetail)
        publishSender(connection, SenderPhase.FAILED, "Authentication failed.")
        try {
            val me = store.identity()
            connection.send(
                LocalDropMessage(
                    type = MessageType.AUTH_RESPONSE,
                    messageId = ProtocolCodec.newMessageId(),
                    correlationId = null, sessionId = null, transferId = null,
                    payload = TypedPayload.AuthResponse(
                        AuthResponsePayload(me.deviceId, false, "Authentication failed.")
                    )
                )
            )
        } catch (e: Exception) {
            // Best effort; the disconnect below is the real enforcement.
        }
        handshakes.remove(connection.id)
        hs?.future?.complete(AuthOutcome.Failed("Authentication failed."))
        try {
            events?.onAuthFailed(connection, "Authentication failed.")
        } catch (e: Exception) {
        }
        try {
            connection.disconnect()
        } catch (e: Exception) {
        }
    }

    // Returns false so callers can `return failHandshake(...)` directly.
    private fun failHandshake(
        connection: ControlConnection, hs: AuthHandshake, logDetail: String
    ): Boolean {
        synchronized(hs) {
            if (hs.phase == AuthPhase.DONE || hs.phase == AuthPhase.FAILED) return false
            hs.phase = AuthPhase.FAILED
        }
        handshakes.remove(connection.id)
        logger.authEvent(false, hs.peerHello?.deviceId, logDetail)
        publishSender(connection, SenderPhase.FAILED, "Authentication failed.")
        hs.future.complete(AuthOutcome.Failed("Authentication failed."))
        try {
            events?.onAuthFailed(connection, "Authentication failed.")
        } catch (e: Exception) {
        }
        try {
            connection.disconnect()
        } catch (e: Exception) {
        }
        return false
    }

    private fun startWatchdog(connection: ControlConnection, hs: AuthHandshake) {
        val thread = Thread({
            val remaining = hs.deadlineMillis - clock.nowMillis()
            if (remaining > 0) {
                try {
                    Thread.sleep(remaining)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
            synchronized(hs) {
                if (hs.phase == AuthPhase.DONE || hs.phase == AuthPhase.FAILED) return@Thread
            }
            failHandshake(connection, hs, "auth timeout")
        }, "localdrop-auth-watchdog")
        thread.isDaemon = true
        thread.start()
    }

    private fun publishSender(connection: ControlConnection, phase: SenderPhase, error: String?) {
        try {
            senderObserver?.invoke(
                connection.id,
                SenderAuthUiState(connection.peerDeviceId, phase, error)
            )
        } catch (e: Exception) {
        }
    }
}
