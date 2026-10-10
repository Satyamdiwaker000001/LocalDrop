package com.localdrop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// M13 §1: service-ownership lifecycle. Repeated starts and recreation must
// not create duplicate engines, listeners, discovery loops, or workers;
// partial initialization rolls back; zero transfers keeps listening.
class TransferGraphTest {

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

    private fun graph(active: () -> Int = { 0 }): Triple<TransferGraph, Fake, Fake> {
        val control = Fake("control")
        val data = Fake("data")
        val discovery = Fake("discovery")
        // Keep handles via a holder triple: control/data are asserted through
        // the graph's startCount plus the fakes captured below.
        val g = TransferGraph(control, data, discovery, active)
        return Triple(g, control, data)
    }

    @Test
    fun `repeated starts create no duplicate engines listeners or loops`() {
        val control = Fake("control")
        val data = Fake("data")
        val discovery = Fake("discovery")
        val g = TransferGraph(control, data, discovery)
        g.start()
        g.start()
        g.start()
        assertEquals(1, g.startCount)
        assertEquals(1, control.starts)
        assertEquals(1, data.starts)
        assertEquals(1, discovery.starts)
        assertTrue(g.isRunning())
    }

    @Test
    fun `stop is idempotent and reverse ordered`() {
        val order = mutableListOf<String>()
        fun comp(name: String) = object : TransferGraph.LifecycleComponent {
            override fun start() {}
            override fun stop() { order.add(name) }
        }
        val g = TransferGraph(comp("control"), comp("data"), comp("discovery"))
        g.start()
        g.stop()
        g.stop()
        assertEquals(listOf("discovery", "data", "control"), order)
        assertFalse(g.isRunning())
    }

    @Test
    fun `partial initialization rolls back started components`() {
        val control = Fake("control")
        val data = Fake("data", failOnStart = true)
        val discovery = Fake("discovery")
        val g = TransferGraph(control, data, discovery)
        try {
            g.start()
            fail("expected start failure")
        } catch (e: IllegalStateException) { }
        assertFalse(g.isRunning())
        assertEquals(0, g.startCount)
        assertEquals(1, control.starts)
        assertEquals(1, control.stops) // rolled back
        assertEquals(0, discovery.starts) // never reached
        // Recovery: a later start works cleanly.
        data.failOnStart = false
        g.start()
        assertTrue(g.isRunning())
        assertEquals(1, g.startCount)
    }

    @Test
    fun `zero active transfers keeps the graph listening`() {
        val g = TransferGraph(Fake("c"), Fake("d"), Fake("n")) { 0 }
        g.start()
        assertEquals(0, g.activeTransferCount())
        assertTrue(g.isRunning()) // idle != stopped; only stop() shuts down
        g.stop()
    }

    @Test
    fun `active count is reported through`() {
        val g = TransferGraph(Fake("c"), Fake("d"), Fake("n")) { 3 }
        g.start()
        assertEquals(3, g.activeTransferCount())
    }

    @Test
    fun `helper triple wires three distinct components`() {
        val (g, control, data) = graph()
        g.start()
        assertEquals(1, control.starts)
        assertEquals(1, data.starts)
    }
}
