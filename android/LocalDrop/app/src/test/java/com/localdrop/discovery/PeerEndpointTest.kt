package com.localdrop.discovery

import com.localdrop.core.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Verification audit F4: discovery carries dialable endpoints so the app can
// connect discovery -> control -> transfer with no manual IP/port entry.
// Covers: source-host recording/preservation, controlPort announcement,
// loopback propagation of both.
class PeerEndpointTest {

    private val self = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val peerA = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private fun helloBytes(deviceId: String = peerA, controlPort: Long = 0L): ByteArray {
        val json = "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"HELLO\"," +
            "\"messageId\":\"11111111-1111-4111-8111-111111111111\"," +
            "\"payload\":{\"deviceId\":\"$deviceId\",\"deviceName\":\"Peer\"," +
            "\"deviceType\":\"android\",\"controlPort\":$controlPort,\"capabilities\":[\"transfer\"]}}"
        return json.toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `source host recorded on discover`() {
        val registry = PeerRegistry(self)
        assertEquals(
            PeerRegistry.HandleResult.Discovered,
            registry.handleDatagram(helloBytes(), helloBytes().size, "192.168.43.10")
        )
        assertEquals("192.168.43.10", registry.snapshot().single().lastSeenHost)
    }

    @Test
    fun `missing host stays null and refresh preserves known host`() {
        val registry = PeerRegistry(self)
        registry.handleDatagram(helloBytes(), helloBytes().size)
        assertNull(registry.snapshot().single().lastSeenHost)
        registry.handleDatagram(helloBytes(), helloBytes().size, "192.168.43.10")
        assertEquals("192.168.43.10", registry.snapshot().single().lastSeenHost)
        // Refresh without source info must not erase the known address.
        registry.handleDatagram(helloBytes(), helloBytes().size)
        assertEquals("192.168.43.10", registry.snapshot().single().lastSeenHost)
        // Newer announcement replaces it.
        registry.handleDatagram(helloBytes(), helloBytes().size, "192.168.43.11")
        assertEquals("192.168.43.11", registry.snapshot().single().lastSeenHost)
    }

    @Test
    fun `blank and oversized hosts normalize to null`() {
        val registry = PeerRegistry(self)
        registry.handleDatagram(helloBytes(), helloBytes().size, "   ")
        assertNull(registry.snapshot().single().lastSeenHost)
        registry.handleDatagram(helloBytes(), helloBytes().size, "x".repeat(300))
        assertNull(registry.snapshot().single().lastSeenHost)
    }

    @Test
    fun `announcement carries configured control port`() {
        val device = DeviceInfo(self, "Me", "android")
        val zero = LanDiscovery.buildAnnouncement(device)
        val zeroText = zero.toString(Charsets.UTF_8)
        assertTrue(zeroText.contains("\"controlPort\":0"))
        val live = LanDiscovery.buildAnnouncement(device, AppConfig.CONTROL_TCP_PORT.toLong())
        assertTrue(live.toString(Charsets.UTF_8).contains("\"controlPort\":48424"))
    }

    @Test
    fun `loopback discovery propagates host and control port`() {
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        val deviceA = DeviceInfo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "A", "android")
        val deviceB = DeviceInfo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "B", "android")
        val a = LanDiscovery(deviceA, port = 0, announceIntervalMs = 100,
            broadcastAddress = loopback)
        a.start()
        try {
            // B announces into A's listener, advertising the live control port.
            val b = LanDiscovery(deviceB, port = 0, announceIntervalMs = 100,
                broadcastAddress = loopback, announcePort = a.localPort(),
                controlPort = AppConfig.CONTROL_TCP_PORT.toLong())
            b.start()
            try {
                val deadline = System.currentTimeMillis() + 8000
                var peer: DiscoveredPeer? = null
                while (System.currentTimeMillis() < deadline) {
                    peer = a.snapshot().firstOrNull { it.record.deviceId == deviceB.deviceId }
                    if (peer != null) break
                    Thread.sleep(50)
                }
                assertTrue("B discovered by A", peer != null)
                assertEquals(AppConfig.CONTROL_TCP_PORT.toLong(), peer!!.record.controlPort)
                assertEquals("127.0.0.1", peer.lastSeenHost)
            } finally {
                b.stop()
            }
        } finally {
            a.stop()
        }
    }
}
