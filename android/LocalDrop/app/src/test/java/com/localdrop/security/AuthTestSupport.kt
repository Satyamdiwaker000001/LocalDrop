package com.localdrop.security

import com.localdrop.discovery.DeviceInfo
import com.localdrop.discovery.FakeDiscoveryClock
import com.localdrop.network.CloseReason
import com.localdrop.network.ControlChannelListener
import com.localdrop.network.ControlConnection
import com.localdrop.network.ControlServer
import com.localdrop.network.ControlTestSupport
import com.localdrop.protocol.AuthRequestPayload
import com.localdrop.protocol.FrameCodec
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TypedPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// Shared wiring for M05 auth tests: full-duplex M04 channels with AuthManager
// on both ends, plus raw-socket evil peers. Loopback only.
object AuthTestSupport {
    fun deviceFor(store: SoftwareDeviceIdentityStore, name: String): DeviceInfo =
        DeviceInfo(store.identity().deviceId, name, "android")

    class AuthEventsCapture : AuthEvents {
        val authenticated = LinkedBlockingQueue<Pair<String, VerifiedPeer>>()
        val failed = LinkedBlockingQueue<String>()
        override fun onAuthenticated(c: ControlConnection, peer: VerifiedPeer) {
            authenticated.offer(c.id to peer)
        }
        override fun onAuthFailed(c: ControlConnection, reason: String) {
            failed.offer(c.id)
        }
        fun awaitAuthenticated(timeoutMs: Long = 8000): Pair<String, VerifiedPeer> =
            authenticated.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("timed out waiting for authentication")
    }

    class AuthedPair(
        val server: ControlServer,
        val serverAuth: AuthManager,
        val serverEvents: AuthEventsCapture,
        val serverStore: SoftwareDeviceIdentityStore,
        val client: ControlConnection,
        val clientAuth: AuthManager,
        val clientEvents: AuthEventsCapture,
        val clientStore: SoftwareDeviceIdentityStore,
        val serverLog: RecordingSecurityLogger,
        val clientLog: RecordingSecurityLogger
    ) {
        fun close() {
            try { client.disconnect() } catch (e: Exception) { }
            try { server.stop() } catch (e: Exception) { }
        }
    }

    // Establishes M04 both ways but performs NO authentication yet.
    fun connectedPair(
        serverStore: SoftwareDeviceIdentityStore = SoftwareDeviceIdentityStore(),
        clientStore: SoftwareDeviceIdentityStore = SoftwareDeviceIdentityStore(),
        authTimeoutMs: Long = 8000L
    ): AuthedPair {
        val serverLog = RecordingSecurityLogger()
        val clientLog = RecordingSecurityLogger()
        val serverDevice = deviceFor(serverStore, "PcB")
        val clientDevice = deviceFor(clientStore, "PhoneA")
        val serverEvents = AuthEventsCapture()
        val clientEvents = AuthEventsCapture()
        val serverAuth = AuthManager(serverDevice, serverStore, serverLog, authTimeoutMs, events = serverEvents)
        val server = ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
        server.start()
        val clientAuth = AuthManager(clientDevice, clientStore, clientLog, authTimeoutMs, events = clientEvents)
        val client = ControlConnection(clientDevice, clientAuth)
        check(client.connect("127.0.0.1", server.localPort())) { "M04 connect failed" }
        return AuthedPair(server, serverAuth, serverEvents, serverStore, client, clientAuth,
            clientEvents, clientStore, serverLog, clientLog)
    }

    // Raw socket that completed the M04 handshake; caller drives auth bytes.
    class RawPeer(host: String, port: Int, store: SoftwareDeviceIdentityStore, name: String) {
        val socket = Socket(host, port)
        val device = deviceFor(store, name)
        val storeRef = store
        init {
            socket.soTimeout = 8000
            checkNotNull(ControlTestSupport.rawHandshake(socket, device)) { "M04 handshake failed" }
            // rawHandshake consumes the server HELLO but leaves the server
            // HELLO_ACK unread; drain it so auth reads align with auth writes.
            val leftover = readMessage(5000)
            check(leftover?.type == MessageType.HELLO_ACK) { "expected M04 HELLO_ACK, got $leftover" }
        }

        fun sendAuthRequest(token: String, method: String = com.localdrop.core.AppConfig.AUTH_METHOD_ID) {
            val message = LocalDropMessage(
                type = MessageType.AUTH_REQUEST,
                messageId = ProtocolCodec.newMessageId(),
                correlationId = null, sessionId = null, transferId = null,
                payload = TypedPayload.AuthRequest(AuthRequestPayload(device.deviceId, method, token))
            )
            val out = socket.getOutputStream()
            out.write(FrameCodec.encodeMessage(message))
            out.flush()
        }

        fun readMessage(timeoutMs: Long = 5000): LocalDropMessage? {
            val body = ControlTestSupport.readRawFrame(socket.getInputStream(), timeoutMs.toInt())
                ?: return null
            val outcome = ProtocolCodec.decodeUtf8(body)
            return (outcome as? com.localdrop.protocol.DecodeOutcome.Success)?.message
        }

        fun readHelloAndProof(): Pair<LocalDropMessage, LocalDropMessage> {
            val first = readMessage() ?: throw AssertionError("no server hello")
            val second = readMessage() ?: throw AssertionError("no server proof")
            check(first.type == MessageType.AUTH_REQUEST && second.type == MessageType.AUTH_REQUEST)
            return first to second
        }

        fun close() {
            try { socket.close() } catch (e: Exception) { }
        }
    }

    fun awaitOutcome(
        future: java.util.concurrent.CompletableFuture<AuthOutcome>,
        timeoutMs: Long = 10000
    ): AuthOutcome =
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for auth outcome: ${e.message}")
        }

    fun waitFor(description: String, timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description")
    }
}
