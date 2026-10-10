package com.localdrop.discovery

import com.localdrop.protocol.DecodeOutcome
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TypedPayload
import com.localdrop.protocol.Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// M03 §23 (items 1-12, 17): registry validation, lifecycle states, shared
// vector compatibility. Pure logic, fake clock, no sockets, no Internet.
class PeerRegistryTest {

    private val self = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val peerA = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val peerB = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    private fun helloBytes(
        deviceId: String = peerA,
        deviceName: String = "TestPeer",
        deviceType: String = "windows"
    ): ByteArray {
        val json = "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"HELLO\"," +
            "\"messageId\":\"11111111-1111-4111-8111-111111111111\"," +
            "\"payload\":{\"deviceId\":\"$deviceId\",\"deviceName\":\"$deviceName\"," +
            "\"deviceType\":\"$deviceType\",\"controlPort\":0,\"capabilities\":[\"transfer\"]}}"
        return json.toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `valid discovery record accepted`() {
        val registry = PeerRegistry(self)
        assertEquals(
            PeerRegistry.HandleResult.Discovered,
            registry.handleDatagram(helloBytes(), helloBytes().size)
        )
        val peers = registry.snapshot()
        assertEquals(1, peers.size)
        assertEquals(peerA, peers[0].record.deviceId)
        assertEquals("TestPeer", peers[0].record.deviceName)
    }

    @Test
    fun `malformed discovery record rejected`() {
        val registry = PeerRegistry(self)
        val bad = "{not json".toByteArray(Charsets.UTF_8)
        assertEquals(PeerRegistry.HandleResult.Ignored, registry.handleDatagram(bad, bad.size))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `unsupported protocol version rejected`() {
        val registry = PeerRegistry(self)
        val bad = Vectors.HELLO.replace("\"protocolVersion\":1", "\"protocolVersion\":2")
            .toByteArray(Charsets.UTF_8)
        assertEquals(PeerRegistry.HandleResult.Ignored, registry.handleDatagram(bad, bad.size))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `oversized discovery packet rejected`() {
        val registry = PeerRegistry(self)
        val big = ByteArray(4097) { 'x'.code.toByte() }
        assertEquals(PeerRegistry.HandleResult.Ignored, registry.handleDatagram(big, big.size))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `invalid device ID rejected`() {
        val registry = PeerRegistry(self)
        val bad = helloBytes(deviceId = "not-a-uuid")
        assertEquals(PeerRegistry.HandleResult.Ignored, registry.handleDatagram(bad, bad.size))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `non-HELLO message ignored`() {
        val registry = PeerRegistry(self)
        val pause = Vectors.PAUSE.toByteArray(Charsets.UTF_8)
        assertEquals(PeerRegistry.HandleResult.Ignored, registry.handleDatagram(pause, pause.size))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `self-discovery filtered by device ID`() {
        val registry = PeerRegistry(self)
        val me = helloBytes(deviceId = self, deviceName = "Myself", deviceType = "android")
        assertEquals(PeerRegistry.HandleResult.Ignored, registry.handleDatagram(me, me.size))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `repeated announcement updates existing peer without duplicates`() {
        val registry = PeerRegistry(self)
        val first = helloBytes()
        assertEquals(PeerRegistry.HandleResult.Discovered, registry.handleDatagram(first, first.size))
        val second = helloBytes(deviceName = "Renamed")
        assertEquals(PeerRegistry.HandleResult.Refreshed, registry.handleDatagram(second, second.size))
        val peers = registry.snapshot()
        assertEquals(1, peers.size)
        assertEquals("Renamed", peers[0].record.deviceName)
    }

    @Test
    fun `multiple peers maintained independently`() {
        val registry = PeerRegistry(self)
        val a = helloBytes(deviceId = peerA, deviceName = "A")
        val b = helloBytes(deviceId = peerB, deviceName = "B")
        registry.handleDatagram(a, a.size)
        registry.handleDatagram(b, b.size)
        assertEquals(2, registry.snapshot().size)
    }

    @Test
    fun `peer expires after timeout`() {
        val clock = FakeDiscoveryClock()
        val registry = PeerRegistry(self, clock, peerTimeoutMs = 10_000L)
        val bytes = helloBytes()
        registry.handleDatagram(bytes, bytes.size)
        assertEquals(1, registry.snapshot().size)
        clock.advance(10_001L)
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `peer refresh prevents premature expiry`() {
        val clock = FakeDiscoveryClock()
        val registry = PeerRegistry(self, clock, peerTimeoutMs = 10_000L)
        val bytes = helloBytes()
        registry.handleDatagram(bytes, bytes.size)
        clock.advance(9_000L)
        registry.handleDatagram(bytes, bytes.size) // refresh
        clock.advance(9_000L)
        assertEquals(1, registry.snapshot().size) // 18s total, refreshed at 9s
        clock.advance(1_001L)
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `peer can reappear after expiry`() {
        val clock = FakeDiscoveryClock()
        val registry = PeerRegistry(self, clock, peerTimeoutMs = 10_000L)
        val bytes = helloBytes()
        registry.handleDatagram(bytes, bytes.size)
        clock.advance(11_000L)
        assertTrue(registry.snapshot().isEmpty())
        assertEquals(
            PeerRegistry.HandleResult.Discovered,
            registry.handleDatagram(bytes, bytes.size)
        )
        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun `announcement builder emits valid HELLO for local identity`() {
        val local = DeviceInfo(self, "Pixel 8", "android", listOf("transfer"))
        val bytes = LanDiscovery.buildAnnouncement(local)
        val outcome = ProtocolCodec.decodeUtf8(bytes)
        assertTrue(outcome is DecodeOutcome.Success)
        val message = (outcome as DecodeOutcome.Success).message
        assertEquals(com.localdrop.protocol.MessageType.HELLO, message.type)
        val hello = message.payload as TypedPayload.Hello
        assertEquals(self, hello.v.deviceId)
        assertEquals("Pixel 8", hello.v.deviceName)
    }

    @Test
    fun `shared M02 HELLO vector is accepted as a discovery announcement`() {
        // Android/Windows shared discovery vector compatibility: the exact
        // M02 test-vector bytes must decode to the expected nearby peer.
        val registry = PeerRegistry("ffffffff-ffff-4fff-8fff-ffffffffffff")
        val bytes = Vectors.HELLO.toByteArray(Charsets.UTF_8)
        assertEquals(PeerRegistry.HandleResult.Discovered, registry.handleDatagram(bytes, bytes.size))
        val peers = registry.snapshot()
        assertEquals(1, peers.size)
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", peers[0].record.deviceId)
        assertEquals("Pixel 8", peers[0].record.deviceName)
        assertEquals("android", peers[0].record.deviceType)
    }
}
