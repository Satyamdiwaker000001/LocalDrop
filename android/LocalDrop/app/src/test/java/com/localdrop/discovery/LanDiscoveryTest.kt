package com.localdrop.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// M03 §23 (items 13-16): real loopback UDP sockets (127.0.0.1, ephemeral
// ports). No Internet, no hardware, no broadcast storm: instances
// cross-target each other over loopback with short intervals.
class LanDiscoveryTest {

    private val deviceA = DeviceInfo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "PhoneA", "android")
    private val deviceB = DeviceInfo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "PcB", "windows")
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun waitFor(
        description: String,
        timeoutMs: Long = 5000,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description")
    }

    @Test
    fun `start and stop lifecycle`() {
        val discovery = LanDiscovery(deviceA, port = 0, broadcastAddress = loopback)
        assertFalse(discovery.isRunning())
        discovery.start()
        assertTrue(discovery.isRunning())
        assertTrue(discovery.localPort() > 0)
        // Second start is a no-op, not a duplicate listener.
        discovery.start()
        assertTrue(discovery.isRunning())
        discovery.stop()
        assertFalse(discovery.isRunning())
        // Second stop is a no-op.
        discovery.stop()
    }

    @Test
    fun `restart binds cleanly without address-in-use`() {
        val discovery = LanDiscovery(deviceA, port = 0, broadcastAddress = loopback)
        discovery.start()
        discovery.stop()
        discovery.start()
        assertTrue(discovery.isRunning())
        discovery.stop()
        assertFalse(discovery.isRunning())
    }

    @Test
    fun `peer discovered over loopback UDP`() {
        val a = LanDiscovery(
            deviceA, port = 0, announceIntervalMs = 100,
            broadcastAddress = loopback
        )
        a.start()
        try {
            val portA = a.localPort()
            // B announces into A's listener; B's own listener sits idle.
            val b = LanDiscovery(
                deviceB, port = 0, announceIntervalMs = 100,
                broadcastAddress = loopback, announcePort = portA
            )
            b.start()
            try {
                waitFor("A discovers B") {
                    a.snapshot().any { it.record.deviceId == deviceB.deviceId }
                }
                // B's registry holds nobody (A announces to its own port).
                assertTrue(b.snapshot().none { it.record.deviceId == deviceA.deviceId })
                val peer = a.snapshot().first { it.record.deviceId == deviceB.deviceId }
                assertEquals("PcB", peer.record.deviceName)
                assertEquals("windows", peer.record.deviceType)
            } finally {
                b.stop()
            }
        } finally {
            a.stop()
        }
    }

    @Test
    fun `self announcements never appear as nearby peer`() {
        val a = LanDiscovery(
            deviceA, port = 0, announceIntervalMs = 100,
            broadcastAddress = loopback
        )
        // Announce to self: loopback copy of own announcements arrives, but
        // device-ID filtering must drop them.
        a.start()
        try {
            Thread.sleep(600)
            assertTrue(a.snapshot().none { it.record.deviceId == deviceA.deviceId })
        } finally {
            a.stop()
        }
    }

    @Test
    fun `malformed UDP input does not crash discovery`() {
        val a = LanDiscovery(deviceA, port = 0, broadcastAddress = loopback)
        a.start()
        try {
            val sender = DatagramSocket()
            try {
                val target = a.localPort()
                val garbage = listOf(
                    "{not json".toByteArray(),
                    ByteArray(5000) { 'z'.code.toByte() },
                    byteArrayOf(0xFF.toByte(), 0x00, 0x01),
                    "{\"protocol\":\"localdrop\",\"protocolVersion\":99}".toByteArray()
                )
                for (g in garbage) {
                    sender.send(DatagramPacket(g, g.size, loopback, target))
                }
                Thread.sleep(400)
                assertTrue(a.isRunning())
                assertTrue(a.snapshot().isEmpty())
            } finally {
                sender.close()
            }
        } finally {
            a.stop()
        }
    }

    @Test
    fun `stop cancels blocking receive promptly`() {
        val a = LanDiscovery(deviceA, port = 0, broadcastAddress = loopback)
        a.start()
        val start = System.currentTimeMillis()
        a.stop()
        val elapsed = System.currentTimeMillis() - start
        assertTrue("stop took ${elapsed}ms, expected prompt shutdown", elapsed < 4000)
        assertFalse(a.isRunning())
    }
}
