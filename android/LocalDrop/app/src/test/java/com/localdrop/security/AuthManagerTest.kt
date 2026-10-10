package com.localdrop.security

import com.localdrop.core.AppConfig
import com.localdrop.network.ControlServer
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.TypedPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

// M05 tests 3-4 (+22/23 cleanup): mutual auth success/failure over live M04
// channels, fail-closed behavior, timeouts, cancellation.
class AuthManagerTest {

    @Test
    fun `mutual authentication succeeds both directions`() {
        val pair = AuthTestSupport.connectedPair()
        try {
            val future = pair.clientAuth.beginAuth(pair.client)
            // Server side authenticates automatically (responder path).
            val clientOutcome = AuthTestSupport.awaitOutcome(future)
            assertTrue(clientOutcome is AuthOutcome.Authenticated)
            val clientPeer = (clientOutcome as AuthOutcome.Authenticated).peer
            assertEquals(pair.serverStore.identity().deviceId, clientPeer.deviceId)
            assertTrue(clientPeer.publicKeySpki.contentEquals(pair.serverStore.identity().publicKeySpki))

            val (serverConnId, serverPeer) = pair.serverEvents.awaitAuthenticated()
            assertEquals(pair.clientStore.identity().deviceId, serverPeer.deviceId)

            assertTrue(pair.clientAuth.isAuthenticated(pair.client))
            assertEquals(clientPeer, pair.clientAuth.verifiedPeer(pair.client))
            // Server-side connection handle resolves from the event.
            val serverConn = pair.server.snapshot().first { it.id == serverConnId }
            assertTrue(pair.serverAuth.isAuthenticated(serverConn))
        } finally {
            pair.close()
        }
    }

    @Test
    fun `forged proof signature is rejected and connection dropped`() {
        val serverStore = SoftwareDeviceIdentityStore()
        val forgerStore = SoftwareDeviceIdentityStore() // valid binding, wrong key below
        val otherKey = SoftwareDeviceIdentityStore()
        val serverLog = RecordingSecurityLogger()
        val serverEvents = AuthTestSupport.AuthEventsCapture()
        val serverDevice = AuthTestSupport.deviceFor(serverStore, "PcB")
        val serverAuth = AuthManager(serverDevice, serverStore, serverLog, events = serverEvents)
        val server = ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
        server.start()
        try {
            val raw = AuthTestSupport.RawPeer("127.0.0.1", server.localPort(), forgerStore, "Evil")
            try {
                // Valid hello (own true identity), then a proof signed with a
                // DIFFERENT key over the correct transcript.
                val myId = forgerStore.identity()
                val myNonce = AuthProtocol.newNonce()
                raw.sendAuthRequest(AuthProtocol.helloToken(myId.deviceId, myId.publicKeySpki, myNonce))
                val (serverHello, _) = raw.readHelloAndProof()
                val serverHelloPayload =
                    (serverHello.payload as TypedPayload.AuthRequest).v
                val serverParsed = AuthProtocol.parseHello(serverHelloPayload.token)
                assertTrue(serverParsed is com.localdrop.protocol.PayloadResult.Success)
                val serverNonce = (serverParsed as com.localdrop.protocol.PayloadResult.Success).value.nonce
                val transcript = AuthProtocol.buildTranscript(
                    AppConfig.AUTH_METHOD_ID, myId.deviceId, myNonce,
                    serverStore.identity().deviceId, serverNonce
                )
                val forged = otherKey.sign(transcript) // wrong key!
                raw.sendAuthRequest(AuthProtocol.proofToken(forged))
                // Server must reject (AUTH_RESPONSE false) and drop the channel.
                val response = raw.readMessage()
                assertTrue(response != null && response.type == MessageType.AUTH_RESPONSE)
                val accepted = (response!!.payload as TypedPayload.AuthResponse).v.accepted
                assertFalse(accepted)
                AuthTestSupport.waitFor("server drops forger") { server.snapshot().isEmpty() }
                // Nothing was ever verified on the server side.
                assertTrue(
                    serverEvents.authenticated.poll(300, TimeUnit.MILLISECONDS) == null
                )
            } finally {
                raw.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `unknown auth method is rejected`() {
        val serverStore = SoftwareDeviceIdentityStore()
        val evilStore = SoftwareDeviceIdentityStore()
        val serverAuth = AuthManager(
            AuthTestSupport.deviceFor(serverStore, "PcB"), serverStore, RecordingSecurityLogger()
        )
        val server = ControlServer(
            AuthTestSupport.deviceFor(serverStore, "PcB"), port = 0, listenerFactory = { serverAuth }
        )
        server.start()
        try {
            val raw = AuthTestSupport.RawPeer("127.0.0.1", server.localPort(), evilStore, "Evil")
            try {
                val myId = evilStore.identity()
                raw.sendAuthRequest(
                    AuthProtocol.helloToken(myId.deviceId, myId.publicKeySpki, AuthProtocol.newNonce()),
                    method = "bogus-method"
                )
                val response = raw.readMessage()
                assertTrue(response != null && response.type == MessageType.AUTH_RESPONSE)
                assertFalse((response!!.payload as TypedPayload.AuthResponse).v.accepted)
                AuthTestSupport.waitFor("server drops unknown method") { server.snapshot().isEmpty() }
            } finally {
                raw.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `deviceId binding mismatch is rejected`() {
        val serverStore = SoftwareDeviceIdentityStore()
        val evilStore = SoftwareDeviceIdentityStore()
        val serverDevice = AuthTestSupport.deviceFor(serverStore, "PcB")
        val serverAuth = AuthManager(serverDevice, serverStore, RecordingSecurityLogger())
        val server = ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
        server.start()
        try {
            val raw = AuthTestSupport.RawPeer("127.0.0.1", server.localPort(), evilStore, "Evil")
            try {
                val myId = evilStore.identity()
                // Claim a deviceId that is NOT derived from our public key.
                val fakeId = "ffffffff-ffff-4fff-8fff-ffffffffffff"
                raw.sendAuthRequest(AuthProtocol.helloToken(fakeId, myId.publicKeySpki, AuthProtocol.newNonce()))
                val response = raw.readMessage()
                assertTrue(response != null && response.type == MessageType.AUTH_RESPONSE)
                assertFalse((response!!.payload as TypedPayload.AuthResponse).v.accepted)
                AuthTestSupport.waitFor("server drops binding mismatch") { server.snapshot().isEmpty() }
            } finally {
                raw.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `duplicate hello is rejected`() {
        val serverStore = SoftwareDeviceIdentityStore()
        val evilStore = SoftwareDeviceIdentityStore()
        val serverDevice = AuthTestSupport.deviceFor(serverStore, "PcB")
        val serverAuth = AuthManager(serverDevice, serverStore, RecordingSecurityLogger())
        val server = ControlServer(serverDevice, port = 0, listenerFactory = { serverAuth })
        server.start()
        try {
            val raw = AuthTestSupport.RawPeer("127.0.0.1", server.localPort(), evilStore, "Evil")
            try {
                val myId = evilStore.identity()
                val token = AuthProtocol.helloToken(myId.deviceId, myId.publicKeySpki, AuthProtocol.newNonce())
                raw.sendAuthRequest(token)
                raw.readHelloAndProof() // drain server hello+proof
                raw.sendAuthRequest(token) // duplicate
                val response = raw.readMessage()
                assertTrue(response != null && response.type == MessageType.AUTH_RESPONSE)
                assertFalse((response!!.payload as TypedPayload.AuthResponse).v.accepted)
                AuthTestSupport.waitFor("server drops duplicate") { server.snapshot().isEmpty() }
            } finally {
                raw.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `silent peer triggers auth timeout and cleanup`() {
        // Server completes M04 but never answers AUTH (no AuthManager wired).
        val serverDevice = AuthTestSupport.deviceFor(SoftwareDeviceIdentityStore(), "PcB")
        val server = ControlServer(serverDevice, port = 0, listenerFactory = { null })
        server.start()
        try {
            val clientStore = SoftwareDeviceIdentityStore()
            val clientDevice = AuthTestSupport.deviceFor(clientStore, "PhoneA")
            val log = RecordingSecurityLogger()
            val clientAuth = AuthManager(clientDevice, clientStore, log, authTimeoutMs = 1000L)
            val client = com.localdrop.network.ControlConnection(clientDevice, clientAuth)
            assertTrue(client.connect("127.0.0.1", server.localPort()))
            val outcome = AuthTestSupport.awaitOutcome(clientAuth.beginAuth(client), 8000)
            assertTrue(outcome is AuthOutcome.Failed)
            AuthTestSupport.waitFor("client channel cleaned up") {
                client.state == com.localdrop.network.ControlState.DISCONNECTED ||
                    client.state == com.localdrop.network.ControlState.FAILED
            }
            assertFalse(clientAuth.isAuthenticated(client))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `disconnect during auth completes the future and cleans up`() {
        val pair = AuthTestSupport.connectedPair(authTimeoutMs = 15000L)
        try {
            // Server will never answer: remove its auth handling by stopping
            // the server mid-handshake right after our hello goes out.
            val future = pair.clientAuth.beginAuth(pair.client)
            pair.server.stop()
            val outcome = AuthTestSupport.awaitOutcome(future, 10000)
            assertTrue(outcome is AuthOutcome.Failed)
            assertFalse(pair.clientAuth.isAuthenticated(pair.client))
        } finally {
            pair.close()
        }
    }
}
