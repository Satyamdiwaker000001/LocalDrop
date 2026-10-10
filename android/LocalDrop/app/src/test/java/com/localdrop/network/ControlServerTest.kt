package com.localdrop.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// M04 §16/§17/§20: server lifecycle, independent per-peer connections,
// tracking cleanup, rebind after stop.
class ControlServerTest {

    @Test
    fun `server start stop lifecycle is idempotent`() {
        val server = ControlServer(ControlTestSupport.deviceB, port = 0)
        server.start()
        assertTrue(server.isRunning())
        assertTrue(server.localPort() > 0)
        server.start() // no-op, no duplicate accept loop
        assertTrue(server.isRunning())
        server.stop()
        server.stop() // no-op, no crash
    }

    @Test
    fun `multiple peer connections are independent`() {
        val server = ControlServer(ControlTestSupport.deviceB, port = 0)
        server.start()
        try {
            val port = server.localPort()
            val c1 = ControlConnection(ControlTestSupport.deviceA)
            val c2Events = ControlTestSupport.Capture()
            val c2device = com.localdrop.discovery.DeviceInfo(
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "PcC", "windows"
            )
            val c2 = ControlConnection(c2device, c2Events)
            assertTrue(c1.connect("127.0.0.1", port))
            assertTrue(c2.connect("127.0.0.1", port))
            ControlTestSupport.waitFor("both accepted") { server.snapshot().size == 2 }
            // Kill one connection; the other must stay READY and usable.
            c1.disconnect()
            ControlTestSupport.waitFor("one remains") { server.snapshot().size == 1 }
            val survivor = server.snapshot().single()
            assertTrue(survivor.isReady())
            assertTrue(c2.isReady())
            assertTrue(c2.send(ControlTestSupport.pauseMessage("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")))
            c2.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `failed handshake does not affect healthy connections`() {
        val server = ControlServer(ControlTestSupport.deviceB, port = 0)
        server.start()
        try {
            val port = server.localPort()
            val good = ControlConnection(ControlTestSupport.deviceA)
            assertTrue(good.connect("127.0.0.1", port))
            // A raw socket that sends garbage during handshake.
            val raw = java.net.Socket("127.0.0.1", port)
            try {
                raw.getOutputStream().write("garbage-bytes".toByteArray())
                raw.getOutputStream().flush()
                ControlTestSupport.waitFor("bad connection dropped") {
                    server.snapshot().none { it.peerDeviceId == null && !it.isReady() } &&
                        server.snapshot().size == 1
                }
                assertTrue(good.isReady())
                assertTrue(good.send(ControlTestSupport.pauseMessage("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")))
            } finally {
                raw.close()
                good.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `manager tracks rekeys and closes all`() {
        val manager = ControlConnections()
        val c1 = ControlConnection(ControlTestSupport.deviceA)
        val c2 = ControlConnection(ControlTestSupport.deviceB)
        manager.add(c1.id, c1)
        manager.add(c2.id, c2)
        assertEquals(2, manager.size())
        manager.rekey(c1.id, ControlTestSupport.deviceA.deviceId)
        assertEquals(c1, manager.get(ControlTestSupport.deviceA.deviceId))
        manager.remove(c2.id)
        assertEquals(1, manager.size())
        manager.closeAll()
        assertEquals(0, manager.size())
    }

    @Test
    fun `server rebinds after stop`() {
        val first = ControlServer(ControlTestSupport.deviceB, port = 0)
        first.start()
        first.stop()
        val second = ControlServer(ControlTestSupport.deviceB, port = 0)
        second.start()
        try {
            val client = ControlConnection(ControlTestSupport.deviceA)
            assertTrue(client.connect("127.0.0.1", second.localPort()))
            client.disconnect()
        } finally {
            second.stop()
        }
    }
}
