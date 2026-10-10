package com.localdrop.security

import com.localdrop.discovery.FakeDiscoveryClock
import com.localdrop.network.ControlConnection
import com.localdrop.network.ControlServer
import com.localdrop.network.ControlTestSupport
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.PairRequestPayload
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TypedPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

// M05 tests 8, 15-18, 20, 22-23: the full receiver/sender authorization flow
// over live M04 channels: auth + code + binding matrix.
class AuthorizationFlowTest {

    // Full receiver stack: server -> serverAuth -> authorizer; sender stack
    // symmetric. Closes everything on done.
    class Flow(
        val receiverStore: SoftwareDeviceIdentityStore,
        val senderStore: SoftwareDeviceIdentityStore,
        val clock: FakeDiscoveryClock,
        val receiverLog: RecordingSecurityLogger,
        val senderLog: RecordingSecurityLogger,
        val receiverAuth: AuthManager,
        val senderAuth: AuthManager,
        val txns: TransactionManager,
        val sessions: AuthorizedSessionStore,
        val authorizer: TransactionAuthorizer,
        val server: ControlServer,
        val client: ControlConnection
    ) {
        val receiverDevice = AuthTestSupport.deviceFor(receiverStore, "PcB")
        val senderDevice = AuthTestSupport.deviceFor(senderStore, "PhoneA")

        fun authenticate(): VerifiedPeer {
            val future = senderAuth.beginAuth(client)
            val outcome = AuthTestSupport.awaitOutcome(future)
            assertTrue("sender auth failed: $outcome", outcome is AuthOutcome.Authenticated)
            return (outcome as AuthOutcome.Authenticated).peer
        }

        fun close() {
            try { client.disconnect() } catch (e: Exception) { }
            try { server.stop() } catch (e: Exception) { }
        }
    }

    private fun newFlow(
        lifetimeMs: Long = 300_000L,
        authTimeoutMs: Long = 8000L
    ): Flow {
        val receiverStore = SoftwareDeviceIdentityStore()
        val senderStore = SoftwareDeviceIdentityStore()
        val clock = FakeDiscoveryClock()
        val receiverLog = RecordingSecurityLogger()
        val senderLog = RecordingSecurityLogger()
        val receiverDevice = AuthTestSupport.deviceFor(receiverStore, "PcB")
        val senderDevice = AuthTestSupport.deviceFor(senderStore, "PhoneA")
        val receiverAuth = AuthManager(receiverDevice, receiverStore, receiverLog, authTimeoutMs)
        val senderAuth = AuthManager(senderDevice, senderStore, senderLog, authTimeoutMs)
        val txns = TransactionManager(clock, lifetimeMs, receiverLog)
        val sessions = AuthorizedSessionStore()
        val authorizer = TransactionAuthorizer(
            receiverAuth, txns, sessions, receiverLog, receiverDevice.deviceId
        )
        receiverAuth.inner = authorizer
        val server = ControlServer(receiverDevice, port = 0, listenerFactory = { receiverAuth })
        server.start()
        val client = ControlConnection(senderDevice, senderAuth)
        check(client.connect("127.0.0.1", server.localPort())) { "M04 connect failed" }
        return Flow(receiverStore, senderStore, clock, receiverLog, senderLog,
            receiverAuth, senderAuth, txns, sessions, authorizer, server, client)
    }

    @Test
    fun `authorized session requires authentication plus correct code`() {
        val flow = newFlow()
        try {
            val verified = flow.authenticate()
            assertEquals(flow.receiverStore.identity().deviceId, verified.deviceId)

            val txn = flow.txns.createTransaction()
            val outcome = flow.senderAuth.senderSubmitCode(flow.client, txn.code)
            assertTrue("submit failed: $outcome", outcome is PairSubmitOutcome.Accepted)
            assertTrue(flow.sessions.isAuthorized(flow.senderDevice.deviceId, txn.id))
            val session = flow.sessions.sessionsFor(flow.senderDevice.deviceId).single()
            assertEquals(txn.id, session.transactionId)
            assertTrue(session.authorizedAtMillis > 0)
            // Binding evidence: no other peer is authorized for anything.
            assertTrue(flow.sessions.sessionsFor(flow.receiverDevice.deviceId).isEmpty())
        } finally {
            flow.close()
        }
    }

    @Test
    fun `wrong code rejected, correct code still works`() {
        val flow = newFlow()
        try {
            flow.authenticate()
            val txn = flow.txns.createTransaction()
            val wrong = txn.code.dropLast(1) + if (txn.code.last() == '0') '1' else '0'
            val rejected = flow.senderAuth.senderSubmitCode(flow.client, wrong)
            assertTrue(rejected is PairSubmitOutcome.Rejected)
            assertEquals(
                TransactionAuthorizer.GENERIC_REJECT_REASON,
                (rejected as PairSubmitOutcome.Rejected).reason
            )
            assertFalse(flow.sessions.isAuthorized(flow.senderDevice.deviceId, txn.id))
            // The correct code was NOT consumed by the wrong attempt.
            val accepted = flow.senderAuth.senderSubmitCode(flow.client, txn.code)
            assertTrue(accepted is PairSubmitOutcome.Accepted)
            assertTrue(flow.sessions.isAuthorized(flow.senderDevice.deviceId, txn.id))
        } finally {
            flow.close()
        }
    }

    @Test
    fun `unauthenticated channel cannot authorize even with the right code`() {
        val receiverStore = SoftwareDeviceIdentityStore()
        val clock = FakeDiscoveryClock()
        val receiverLog = RecordingSecurityLogger()
        val receiverDevice = AuthTestSupport.deviceFor(receiverStore, "PcB")
        val receiverAuth = AuthManager(receiverDevice, receiverStore, receiverLog)
        val txns = TransactionManager(clock, 300_000L, receiverLog)
        val sessions = AuthorizedSessionStore()
        val authorizer = TransactionAuthorizer(
            receiverAuth, txns, sessions, receiverLog, receiverDevice.deviceId
        )
        receiverAuth.inner = authorizer
        val server = ControlServer(receiverDevice, port = 0, listenerFactory = { receiverAuth })
        server.start()
        try {
            // Plain M04 connection: M04 handshake only, NO M05 auth.
            val senderDevice = AuthTestSupport.deviceFor(SoftwareDeviceIdentityStore(), "PhoneA")
            val capture = ControlTestSupport.Capture()
            val raw = ControlConnection(senderDevice, capture)
            assertTrue(raw.connect("127.0.0.1", server.localPort()))
            try {
                val txn = txns.createTransaction()
                val request = com.localdrop.protocol.LocalDropMessage(
                    type = MessageType.PAIR_REQUEST,
                    messageId = ProtocolCodec.newMessageId(),
                    correlationId = null, sessionId = null, transferId = null,
                    payload = TypedPayload.PairRequest(
                        PairRequestPayload(senderDevice.deviceId, senderDevice.deviceName, txn.code)
                    )
                )
                assertTrue(raw.send(request))
                val response = capture.awaitMessage()
                assertEquals(MessageType.PAIR_RESPONSE, response.type)
                val payload = (response.payload as TypedPayload.PairResponse).v
                assertFalse(payload.accepted)
                assertEquals(TransactionAuthorizer.GENERIC_REJECT_REASON, payload.reason)
                assertTrue(sessions.sessionsFor(senderDevice.deviceId).isEmpty())
            } finally {
                raw.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `expired code rejected over an authenticated channel`() {
        val flow = newFlow()
        try {
            flow.authenticate()
            val txn = flow.txns.createTransaction()
            flow.clock.advance(300_001L)
            val outcome = flow.senderAuth.senderSubmitCode(flow.client, txn.code)
            assertTrue(outcome is PairSubmitOutcome.Rejected)
            assertFalse(flow.sessions.isAuthorized(flow.senderDevice.deviceId, txn.id))
        } finally {
            flow.close()
        }
    }

    @Test
    fun `replay of a consumed code is rejected`() {
        val flow = newFlow()
        try {
            flow.authenticate()
            val txn = flow.txns.createTransaction()
            assertTrue(flow.senderAuth.senderSubmitCode(flow.client, txn.code) is PairSubmitOutcome.Accepted)
            val replay = flow.senderAuth.senderSubmitCode(flow.client, txn.code)
            assertTrue(replay is PairSubmitOutcome.Rejected)
            assertEquals(1, flow.sessions.sessionsFor(flow.senderDevice.deviceId).size)
        } finally {
            flow.close()
        }
    }

    @Test
    fun `one receiver code cannot authorize another receivers transaction`() {
        // Receiver A + Receiver B are independent servers with isolated state.
        val storeA = SoftwareDeviceIdentityStore()
        val storeB = SoftwareDeviceIdentityStore()
        val senderStore = SoftwareDeviceIdentityStore()
        val deviceA = AuthTestSupport.deviceFor(storeA, "PcA")
        val deviceB = AuthTestSupport.deviceFor(storeB, "PcB")
        val senderDevice = AuthTestSupport.deviceFor(senderStore, "PhoneA")

        fun receiver(device: com.localdrop.discovery.DeviceInfo, store: SoftwareDeviceIdentityStore): ReceiverStack {
            val auth = AuthManager(device, store, RecordingSecurityLogger())
            val txns = TransactionManager(FakeDiscoveryClock(), 300_000L, RecordingSecurityLogger())
            val sessions = AuthorizedSessionStore()
            auth.inner = TransactionAuthorizer(auth, txns, sessions, RecordingSecurityLogger(), device.deviceId)
            val server = ControlServer(device, port = 0, listenerFactory = { auth })
            server.start()
            return ReceiverStack(server, txns, sessions)
        }
        val stackA = receiver(deviceA, storeA)
        val stackB = receiver(deviceB, storeB)
        try {
            val txnA = stackA.txns.createTransaction()
            // Sender authenticates with B, then submits A's code to B.
            val senderAuth = AuthManager(senderDevice, senderStore, RecordingSecurityLogger())
            val client = ControlConnection(senderDevice, senderAuth)
            assertTrue(client.connect("127.0.0.1", stackB.server.localPort()))
            try {
                val outcome = AuthTestSupport.awaitOutcome(senderAuth.beginAuth(client))
                assertTrue(outcome is AuthOutcome.Authenticated)
                val rejected = senderAuth.senderSubmitCode(client, txnA.code)
                assertTrue("cross-receiver code must fail: $rejected", rejected is PairSubmitOutcome.Rejected)
                assertTrue(stackB.sessions.sessionsFor(senderDevice.deviceId).isEmpty())
                assertTrue(stackA.sessions.sessionsFor(senderDevice.deviceId).isEmpty())
            } finally {
                client.disconnect()
            }
        } finally {
            stackA.server.stop()
            stackB.server.stop()
        }
    }

    @Test
    fun `failed authentication leaves no session and channel is dropped`() {
        // Raw peer sends garbage auth; then a good client must still work
        // (server survives failures) and no session exists for the attacker.
        val receiverStore = SoftwareDeviceIdentityStore()
        val receiverDevice = AuthTestSupport.deviceFor(receiverStore, "PcB")
        val receiverLog = RecordingSecurityLogger()
        val receiverAuth = AuthManager(receiverDevice, receiverStore, receiverLog)
        val txns = TransactionManager(FakeDiscoveryClock(), 300_000L, receiverLog)
        val sessions = AuthorizedSessionStore()
        receiverAuth.inner = TransactionAuthorizer(
            receiverAuth, txns, sessions, receiverLog, receiverDevice.deviceId
        )
        val server = ControlServer(receiverDevice, port = 0, listenerFactory = { receiverAuth })
        server.start()
        try {
            val evilStore = SoftwareDeviceIdentityStore()
            val raw = AuthTestSupport.RawPeer("127.0.0.1", server.localPort(), evilStore, "Evil")
            try {
                val myId = evilStore.identity()
                // Unknown method straight away.
                raw.sendAuthRequest("{}", method = "bogus")
                AuthTestSupport.waitFor("attacker dropped") { server.snapshot().isEmpty() }
                assertTrue(sessions.sessionsFor(myId.deviceId).isEmpty())
            } finally {
                raw.close()
            }
            // Server still healthy for a legitimate client.
            val senderStore = SoftwareDeviceIdentityStore()
            val senderDevice = AuthTestSupport.deviceFor(senderStore, "PhoneA")
            val senderAuth = AuthManager(senderDevice, senderStore, RecordingSecurityLogger())
            val client = ControlConnection(senderDevice, senderAuth)
            assertTrue(client.connect("127.0.0.1", server.localPort()))
            try {
                val outcome = AuthTestSupport.awaitOutcome(senderAuth.beginAuth(client))
                assertTrue(outcome is AuthOutcome.Authenticated)
            } finally {
                client.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `sender submit times out cleanly with no authorizer reply`() {
        // Authenticated channel, but the server never answers PAIR_REQUEST.
        val pair = AuthTestSupport.connectedPair()
        // Wire NO authorizer: raw AuthManager only.
        try {
            val outcome = AuthTestSupport.awaitOutcome(pair.clientAuth.beginAuth(pair.client))
            assertTrue(outcome is AuthOutcome.Authenticated)
            val txnLike = "123456"
            val result = pair.clientAuth.senderSubmitCode(pair.client, txnLike, timeoutMs = 1500L)
            assertTrue(result is PairSubmitOutcome.Failed)
        } finally {
            pair.close()
        }
    }

    private data class ReceiverStack(
        val server: ControlServer,
        val txns: TransactionManager,
        val sessions: AuthorizedSessionStore
    )
}
