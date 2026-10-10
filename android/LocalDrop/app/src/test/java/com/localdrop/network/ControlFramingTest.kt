package com.localdrop.network

import com.localdrop.core.AppConfig
import com.localdrop.protocol.FrameCodec
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TypedPayload
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// M04 §24 (items 5, 6, 9-13, 17-raw): handshake rejection paths and TCP
// stream edge cases against raw sockets. Loopback only.
class ControlFramingTest {

    private val tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test
    fun `incompatible protocol version rejected`() {
        val events = ControlTestSupport.Capture()
        val server = ControlServer(ControlTestSupport.deviceB, port = 0) { events }
        server.start()
        try {
            val raw = Socket("127.0.0.1", server.localPort())
            try {
                // Well-formed frame, but protocolVersion 2.
                val bad = "{\"protocol\":\"localdrop\",\"protocolVersion\":2," +
                    "\"type\":\"HELLO\",\"messageId\":\"11111111-1111-4111-8111-111111111111\"," +
                    "\"payload\":{\"deviceId\":\"${ControlTestSupport.deviceA.deviceId}\"," +
                    "\"deviceName\":\"X\",\"deviceType\":\"android\"," +
                    "\"controlPort\":0,\"capabilities\":[]}}"
                val frame = FrameCodec.encode(bad.toByteArray(Charsets.UTF_8))
                raw.getOutputStream().write(frame)
                raw.getOutputStream().flush()
                val close = events.awaitClose()
                assertEquals(CloseReason.PROTOCOL_VIOLATION, close.reason)
            } finally {
                raw.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `malformed handshake bytes rejected without crash`() {
        val events = ControlTestSupport.Capture()
        val server = ControlServer(ControlTestSupport.deviceB, port = 0) { events }
        server.start()
        try {
            val raw = Socket("127.0.0.1", server.localPort())
            try {
                raw.getOutputStream().write("definitely not a frame".toByteArray())
                raw.getOutputStream().flush()
                val close = events.awaitClose()
                assertEquals(CloseReason.PROTOCOL_VIOLATION, close.reason)
                assertTrue(server.isRunning())
            } finally {
                raw.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `handshake timeout when peer stays silent`() {
        val acceptor = ServerSocket(0)
        try {
            val clientEvents = ControlTestSupport.Capture()
            val client = ControlConnection(
                ControlTestSupport.deviceA, clientEvents,
                handshakeTimeoutMs = 1000L
            )
            val connector = Thread({ client.connect("127.0.0.1", acceptor.localPort) }, "test-connector")
            connector.isDaemon = true
            connector.start()
            // Accept but never speak: client must time out, not hang.
            val peer = acceptor.accept()
            try {
                connector.join(10000)
                assertFalse(connector.isAlive)
                assertFalse(client.isReady())
                val close = clientEvents.awaitClose()
                assertEquals(CloseReason.HANDSHAKE_TIMEOUT, close.reason)
            } finally {
                peer.close()
            }
        } finally {
            acceptor.close()
        }
    }

    @Test
    fun `partial TCP frame reassembled`() {
        val acceptor = ServerSocket(0)
        try {
            val clientEvents = ControlTestSupport.Capture()
            val client = ControlConnection(ControlTestSupport.deviceA, clientEvents)
            val connector = Thread({ client.connect("127.0.0.1", acceptor.localPort) }, "test-conn")
            connector.isDaemon = true
            connector.start()
            val raw = acceptor.accept()
            try {
                raw.soTimeout = 8000
                val hello = ControlTestSupport.rawHandshake(raw, ControlTestSupport.deviceB)
                assertTrue(hello != null)
                connector.join(8000)
                assertTrue(client.isReady())
                // Now deliver one frame one byte at a time.
                val message = ControlTestSupport.pauseMessage(tid)
                val frame = FrameCodec.encodeMessage(message)
                val out = raw.getOutputStream()
                for (b in frame) {
                    out.write(byteArrayOf(b))
                    out.flush()
                    Thread.sleep(2)
                }
                val received = clientEvents.awaitMessage(8000)
                assertEquals(MessageType.PAUSE, received.type)
                assertEquals(tid, (received.payload as TypedPayload.Pause).v.transferId)
            } finally {
                raw.close()
                client.disconnect()
            }
        } finally {
            acceptor.close()
        }
    }

    @Test
    fun `multiple frames in one TCP read all received`() {
        val acceptor = ServerSocket(0)
        try {
            val clientEvents = ControlTestSupport.Capture()
            val client = ControlConnection(ControlTestSupport.deviceA, clientEvents)
            val connector = Thread({ client.connect("127.0.0.1", acceptor.localPort) }, "test-conn")
            connector.isDaemon = true
            connector.start()
            val raw = acceptor.accept()
            try {
                raw.soTimeout = 8000
                assertTrue(ControlTestSupport.rawHandshake(raw, ControlTestSupport.deviceB) != null)
                connector.join(8000)
                assertTrue(client.isReady())
                // Three frames concatenated in a single write.
                val out = raw.getOutputStream()
                val combined = FrameCodec.encodeMessage(ControlTestSupport.pauseMessage(tid)) +
                    FrameCodec.encodeMessage(ControlTestSupport.cancelMessage(tid)) +
                    FrameCodec.encodeMessage(ControlTestSupport.pauseMessage(tid))
                out.write(combined)
                out.flush()
                val types = (1..3).map { clientEvents.awaitMessage(8000).type }
                assertEquals(
                    listOf(MessageType.PAUSE, MessageType.CANCEL, MessageType.PAUSE), types
                )
            } finally {
                raw.close()
                client.disconnect()
            }
        } finally {
            acceptor.close()
        }
    }

    @Test
    fun `oversized frame rejected without allocation`() {
        val events = ControlTestSupport.Capture()
        val server = ControlServer(ControlTestSupport.deviceB, port = 0) { events }
        server.start()
        try {
            val raw = Socket("127.0.0.1", server.localPort())
            try {
                // Declare >1MiB; send almost no body. Decoder must fail on
                // the prefix without allocating the declared length.
                val claim = com.localdrop.protocol.Protocol.MAX_FRAME_BYTES + 1
                val prefix = byteArrayOf(
                    (claim ushr 24).toByte(), (claim ushr 16).toByte(),
                    (claim ushr 8).toByte(), claim.toByte()
                )
                raw.getOutputStream().write(prefix + ByteArray(16))
                raw.getOutputStream().flush()
                val close = events.awaitClose()
                assertEquals(CloseReason.PROTOCOL_VIOLATION, close.reason)
            } finally {
                raw.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `control port differs from discovery port`() {
        assertTrue(AppConfig.CONTROL_TCP_PORT != AppConfig.DISCOVERY_UDP_PORT)
        assertEquals(48424, AppConfig.CONTROL_TCP_PORT)
    }
}
