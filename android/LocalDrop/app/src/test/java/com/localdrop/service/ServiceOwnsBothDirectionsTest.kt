package com.localdrop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// M13 bidirectional audit: the service-owned graph covers BOTH directions
// (incoming control/data/receive-discovery AND outgoing send-discovery).
// Repeated starts and UI-layer activity must never duplicate or tear down
// any component.
class ServiceOwnsBothDirectionsTest {

    private class Fake(val name: String, var failOnStart: Boolean = false) :
        TransferGraph.LifecycleComponent {
        var starts = 0
        var stops = 0
        override fun start() {
            if (failOnStart) throw IllegalStateException("$name failed to start")
            starts++
        }
        override fun stop() { stops++ }
    }

    private fun fourWay(active: () -> Int = { 0 }): TransferGraph {
        val control = Fake("control")
        val data = Fake("data")
        val recvDiscovery = Fake("recvDiscovery")
        val sendDiscovery = Fake("sendDiscovery")
        return TransferGraph(
            listOf(control, data, recvDiscovery, sendDiscovery), active)
    }

    @Test
    fun `duplicate starts initialize each direction exactly once`() {
        val control = Fake("control")
        val data = Fake("data")
        val recvDiscovery = Fake("recvDiscovery")
        val sendDiscovery = Fake("sendDiscovery")
        val g = TransferGraph(listOf(control, data, recvDiscovery, sendDiscovery))
        g.start()
        g.start()
        g.start()
        assertEquals(1, g.startCount)
        assertEquals(1, control.starts)
        assertEquals(1, data.starts)
        assertEquals(1, recvDiscovery.starts)
        assertEquals(1, sendDiscovery.starts) // outgoing discovery: no duplicate loop
        assertTrue(g.isRunning())
    }

    @Test
    fun `stop shuts every direction in reverse order exactly once`() {
        val order = mutableListOf<String>()
        fun comp(name: String) = object : TransferGraph.LifecycleComponent {
            override fun start() {}
            override fun stop() { order.add(name) }
        }
        val g = TransferGraph(
            listOf(comp("control"), comp("data"), comp("recvDiscovery"), comp("sendDiscovery")))
        g.start()
        g.stop()
        g.stop()
        assertEquals(listOf("sendDiscovery", "recvDiscovery", "data", "control"), order)
        assertFalse(g.isRunning())
    }

    @Test
    fun `active count aggregates inbound and outbound`() {
        val g = fourWay(active = { 5 })
        g.start()
        assertEquals(5, g.activeTransferCount())
    }

    @Test
    fun `partial init rolls back both directions already started`() {
        val control = Fake("control")
        val data = Fake("data")
        val recvDiscovery = Fake("recvDiscovery", failOnStart = true)
        val sendDiscovery = Fake("sendDiscovery")
        val g = TransferGraph(listOf(control, data, recvDiscovery, sendDiscovery))
        try {
            g.start()
            fail("expected start failure")
        } catch (e: IllegalStateException) { }
        assertFalse(g.isRunning())
        assertEquals(0, g.startCount)
        assertEquals(1, control.stops) // rolled back
        assertEquals(1, data.stops) // rolled back
        assertEquals(0, sendDiscovery.starts) // never reached
        recvDiscovery.failOnStart = false
        g.start()
        assertTrue(g.isRunning())
        assertEquals(1, g.startCount)
    }

    @Test
    fun `legacy three component wiring still starts once`() {
        val g = fourWay()
        g.start()
        g.start()
        assertEquals(1, g.startCount)
    }
}
