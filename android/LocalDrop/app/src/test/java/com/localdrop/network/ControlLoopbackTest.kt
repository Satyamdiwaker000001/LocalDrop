package com.localdrop.network

import com.localdrop.protocol.MessageType
import com.localdrop.protocol.TypedPayload
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// M04 §24 (items 1-4, 7, 8, 14-19, 21-loopback): connection, handshake,
// message exchange, correlation, concurrency, disconnect, cancel, restart.
class ControlLoopbackTest {

    private val tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test
    fun `connection succeeds and handshake completes both sides`() {
        val serverEvents = ControlTestSupport.Capture()
        val server = ControlServer(ControlTestSupport.deviceB, port = 0) { serverEvents }
        server.start()
        try {
            val clientEvents = ControlTestSupport.Capture()
            val client = ControlConnection(ControlTestSupport.deviceA, clientEvents)
            assertTrue(client.connect("127.0.0.1", server.localPort()))
            assertTrue(client.isReady())
            assertEquals(ControlState.READY, client.state)
            // Server side reached READY with the client's identity.
            ControlTestSupport.waitFor("server side ready") {
                server.snapshot().any { it.isReady() }
            }
            val accepted = server.snapshot().first { it.isReady() }
            assertEquals(ControlTestSupport.deviceA.deviceId, accepted.peerDeviceId)
            assertEquals(ControlTestSupport.deviceB.deviceId, client.peerDeviceId)
            client.disconnect()
            val close = clientEvents.awaitClose()
            assertEquals(CloseReason.LOCAL_DISCONNECT, close.reason)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `connection refused fails cleanly`() {
        val probe = ServerSocket(0)
        val closedPort = probe.localPort
        probe.close()
        val events = ControlTestSupport.Capture()
        val client = ControlConnection(ControlTestSupport.deviceA, events)
        val start = System.currentTimeMillis()
        assertFalse(client.connect("127.0.0.1", closedPort))
        val elapsed = System.currentTimeMillis() - start
        assertTrue("refused should fail fast, took ${elapsed}ms", elapsed < 15000)
        val close = events.awaitClose()
        assertEquals(CloseReason.CONNECT_FAILED, close.reason)
        assertEquals(ControlState.FAILED, client.state)
    }

    @Test
    fun `connection timeout is bounded`() {
        // TEST-NET-1 (RFC 5737): must not route anywhere; connect() must
        // give up within budget instead of blocking forever.
        val events = ControlTestSupport.Capture()
        val client = ControlConnection(
            ControlTestSupport.deviceA, events,
            connectTimeoutMs = 1500
        )
        val start = System.currentTimeMillis()
        assertFalse(client.connect("192.0.2.1", 48424))
        val elapsed = System.currentTimeMillis() - start
        assertTrue("connect must be bounded, took ${elapsed}ms", elapsed < 20000)
        val close = events.awaitClose()
        assertEquals(CloseReason.CONNECT_FAILED, close.reason)
    }

    @Test
    fun `valid message transmission and reception both directions`() {
        val serverEvents = ControlTestSupport.Capture()
        val server = ControlServer(ControlTestSupport.deviceB, port = 0) { serverEvents }
        server.start()
        try {
            val clientEvents = ControlTestSupport.Capture()
            val client = ControlConnection(ControlTestSupport.deviceA, clientEvents)
            assertTrue(client.connect("127.0.0.1", server.localPort()))
            try {
                assertTrue(client.send(ControlTestSupport.pauseMessage(tid)))
                val atServer = serverEvents.awaitMessage()
                assertEquals(MessageType.PAUSE, atServer.type)
                assertEquals(tid, (atServer.payload as TypedPayload.Pause).v.transferId)
                val accepted = server.snapshot().first { it.isReady() }
                assertTrue(accepted.send(ControlTestSupport.cancelMessage(tid)))
                val atClient = clientEvents.awaitMessage()
                assertEquals(MessageType.CANCEL, atClient.type)
            } finally {
                client.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `request response correlation matches`() {
        val serverEvents = ControlTestSupport.Capture()
        val server = ControlServer(ControlTestSupport.deviceB, port = 0) { serverEvents }
        server.start()
        try {
            val client = ControlConnection(ControlTestSupport.deviceA)
            assertTrue(client.connect("127.0.0.1", server.localPort()))
            try {
                val request = ControlTestSupport.pauseMessage(tid)
                // Server side: reply with correlated ERROR once request lands.
                val replier = Thread({
                    val incoming = serverEvents.awaitMessage(8000)
                    val accepted = server.snapshot().first { it.isReady() }
                    accepted.send(ControlTestSupport.errorReply(incoming,
                        com.localdrop.protocol.ErrorCode.TRANSFER_REJECTED, "nope"))
                }, "test-replier")
                replier.isDaemon = true
                replier.start()
                val response = client.sendAndReceive(request, 8000)
                assertNotNull(response)
                assertEquals(MessageType.ERROR, response!!.type)
                assertEquals(request.messageId, response.correlationId)
                val error = (response.payload as TypedPayload.Error).v
                assertEquals(com.localdrop.protocol.ErrorCode.TRANSFER_REJECTED, error.error.code)
            } finally {
                client.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `concurrent sends cannot interleave frames`() {
        val serverEvents = ControlTestSupport.Capture()
        val server = ControlServer(ControlTestSupport.deviceB, port = 0) { serverEvents }
        server.start()
        try {
            val client = ControlConnection(ControlTestSupport.deviceA)
            assertTrue(client.connect("127.0.0.1", server.localPort()))
            try {
                val threads = 8
                val perThread = 25
                val sent = AtomicInteger(0)
                val latch = CountDownLatch(threads)
                repeat(threads) { t ->
                    Thread({
                        repeat(perThread) { i ->
                            val transferId = UUID(0L, (t * 1000L + i).toLong()).toString()
                            if (client.send(ControlTestSupport.pauseMessage(transferId))) sent.incrementAndGet()
                        }
                        latch.countDown()
                    }, "sender-$t").apply { isDaemon = true; start() }
                }
                assertTrue(latch.await(20, TimeUnit.SECONDS))
                assertEquals(threads * perThread, sent.get())
                val seen = mutableSetOf<String>()
                val deadline = System.currentTimeMillis() + 15000
                while (seen.size < threads * perThread && System.currentTimeMillis() < deadline) {
                    val message = serverEvents.messages.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    seen.add((message.payload as TypedPayload.Pause).v.transferId)
                }
                assertEquals(threads * perThread, seen.size)
            } finally {
                client.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `graceful disconnect is idempotent`() {
        val server = ControlServer(ControlTestSupport.deviceB, port = 0)
        server.start()
        try {
            val events = ControlTestSupport.Capture()
            val client = ControlConnection(ControlTestSupport.deviceA, events)
            assertTrue(client.connect("127.0.0.1", server.localPort()))
            client.disconnect()
            client.disconnect() // second call must not crash
            assertEquals(ControlState.DISCONNECTED, client.state)
            val close = events.awaitClose()
            assertEquals(CloseReason.LOCAL_DISCONNECT, close.reason)
            assertTrue(events.closes.poll(300, TimeUnit.MILLISECONDS) == null)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `remote disconnect is reported`() {
        val serverEvents = ControlTestSupport.Capture()
        val server = ControlServer(ControlTestSupport.deviceB, port = 0) { serverEvents }
        server.start()
        try {
            val client = ControlConnection(ControlTestSupport.deviceA)
            assertTrue(client.connect("127.0.0.1", server.localPort()))
            // Abrupt close from the client side (no graceful message).
            client.disconnect()
            val close = serverEvents.awaitClose()
            assertTrue(
                close.reason == CloseReason.REMOTE_CLOSE || close.reason == CloseReason.PROTOCOL_VIOLATION
            )
            ControlTestSupport.waitFor("server drops the connection") {
                server.snapshot().isEmpty()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `cancellation unblocks a pending request`() {
        val server = ControlServer(ControlTestSupport.deviceB, port = 0) { null }
        // Listener factory returns null: server never replies.
        server.start()
        try {
            val client = ControlConnection(ControlTestSupport.deviceA)
            assertTrue(client.connect("127.0.0.1", server.localPort()))
            try {
                val request = ControlTestSupport.pauseMessage(tid)
                val canceller = Thread({
                    Thread.sleep(400)
                    client.disconnect()
                }, "test-canceller")
                canceller.isDaemon = true
                canceller.start()
                val start = System.currentTimeMillis()
                assertNull(client.sendAndReceive(request, 10000))
                val elapsed = System.currentTimeMillis() - start
                assertTrue("cancel should unblock promptly, took ${elapsed}ms", elapsed < 8000)
            } finally {
                client.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `control channel restarts and reconnects`() {
        var server = ControlServer(ControlTestSupport.deviceB, port = 0)
        server.start()
        val port = server.localPort()
        val client = ControlConnection(ControlTestSupport.deviceA)
        assertTrue(client.connect("127.0.0.1", port))
        client.disconnect()
        server.stop()
        server = ControlServer(ControlTestSupport.deviceB, port = 0)
        server.start()
        try {
            val client2 = ControlConnection(ControlTestSupport.deviceA)
            assertTrue(client2.connect("127.0.0.1", server.localPort()))
            assertTrue(client2.isReady())
            client2.disconnect()
        } finally {
            server.stop()
        }
    }
}
