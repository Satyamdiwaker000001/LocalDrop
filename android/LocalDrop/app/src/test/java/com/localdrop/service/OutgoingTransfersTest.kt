package com.localdrop.service

import com.localdrop.protocol.ErrorCode
import com.localdrop.transfer.ByteArrayTransferSource
import com.localdrop.transfer.TransferEngineState
import com.localdrop.transfer.TransferMetrics
import com.localdrop.transfer.TransferOutcome
import com.localdrop.transfer.TransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// M13 bidirectional audit: service-owned outgoing executions — concurrent
// sends, exact per-send cancellation isolation, capacity bound, and full
// cleanup. Deterministic: a scripted fake sender, no network.
class OutgoingTransfersTest {

    private fun metrics() = TransferMetrics(10L, 100L, 10.0, 0L, 0L, 0, emptyList(), 65536)

    private fun complete(id: String, bytes: Long = 100L) =
        TransferOutcome.TransportComplete(id, bytes, 10L, 10.0, metrics())

    private fun source(name: String = "a.bin") = ByteArrayTransferSource(name, ByteArray(10))

    private fun mustStart(
        tracker: OutgoingTransfers,
        name: String,
        externalCancel: () -> Boolean = { false }
    ): OutgoingTransfers.SendHandle {
        val h = tracker.startSend("p", source(name), "123456", externalCancel = externalCancel)
        assertNotNull(h)
        return h!!
    }

    @Test
    fun `two concurrent sends both complete`() {
        val tracker = OutgoingTransfers(sender = { _, _, _, isCancelled, onProgress ->
            onProgress(TransferProgress.compute("t", 100L, 50L, 10L, null, TransferEngineState.TRANSFERRING))
            complete("t")
        })
        val a = mustStart(tracker, "a.bin")
        val b = mustStart(tracker, "b.bin")
        assertEquals(2, tracker.activeCount())
        val oa = tracker.awaitOutcome(a.id, 5000)
        val ob = tracker.awaitOutcome(b.id, 5000)
        assertTrue(oa is TransferOutcome.TransportComplete)
        assertTrue(ob is TransferOutcome.TransportComplete)
        assertEquals(0, tracker.activeCount())
    }

    @Test
    fun `cancelling one send leaves the other running`() {
        val enteredB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val tracker = OutgoingTransfers(sender = { _, src, _, isCancelled, _ ->
            if (src.displayName == "b.bin") {
                enteredB.countDown()
                // Cooperative sender: poll until cancelled or released.
                while (!isCancelled()) {
                    if (releaseB.await(50, TimeUnit.MILLISECONDS)) break
                }
                if (isCancelled()) TransferOutcome.Cancelled("tb", 10L) else complete("tb")
            } else {
                enteredB.countDown()
                complete("ta")
            }
        })
        val a = tracker.startSend("p", source("a.bin"), "123456")!!
        // Hold b in flight while a finishes (a may finish first; b waits).
        val b = tracker.startSend("p", source("b.bin"), "123456")!!
        assertTrue(enteredB.await(5, TimeUnit.SECONDS))
        Thread.sleep(200) // let a settle
        assertTrue(tracker.cancel(b.id)) // cancel ONLY b
        val ob = tracker.awaitOutcome(b.id, 5000)
        assertTrue("expected Cancelled, got $ob", ob is TransferOutcome.Cancelled)
        releaseB.countDown()
        val oa = tracker.awaitOutcome(a.id, 5000)
        assertTrue("expected completion, got $oa", oa is TransferOutcome.TransportComplete)
        assertEquals(0, tracker.activeCount())
    }

    @Test
    fun `cancel unknown or settled id touches nothing`() {
        val done = CountDownLatch(1)
        val tracker = OutgoingTransfers(sender = { _, _, _, _, _ ->
            done.countDown()
            complete("t")
        })
        assertFalse(tracker.cancel(999L))
        val h = tracker.startSend("p", source(), "123456")!!
        assertTrue(tracker.awaitOutcome(h.id, 5000) is TransferOutcome.TransportComplete)
        assertFalse(tracker.cancel(h.id)) // already terminal
        assertEquals(0, tracker.activeCount())
        assertEquals(0L, done.count) // sender ran exactly once
    }

    @Test
    fun `fifth concurrent send rejected at capacity then accepted after release`() {
        val gate = CountDownLatch(1)
        val tracker = OutgoingTransfers(sender = { _, _, _, isCancelled, _ ->
            while (!isCancelled()) {
                if (gate.await(50, TimeUnit.MILLISECONDS)) break
            }
            if (isCancelled()) TransferOutcome.Cancelled("t", 0L) else complete("t")
        })
        val handles = (1..OutgoingTransfers.MAX_CONCURRENT_SENDS).map {
            mustStart(tracker, "f$it.bin")
        }
        assertNull(tracker.startSend("p", source("extra.bin"), "123456")) // at cap: rejected
        assertEquals(OutgoingTransfers.MAX_CONCURRENT_SENDS, tracker.activeCount())
        assertTrue(tracker.cancel(handles[0].id))
        // The cancelled send must publish (freeing its slot) before retry.
        assertTrue(tracker.awaitOutcome(handles[0].id, 5000) is TransferOutcome.Cancelled)
        mustStart(tracker, "extra.bin") // slot freed
        gate.countDown()
        // Every started send settles (no waiter left hanging).
        for (h in handles.drop(1)) {
            assertNotNull(tracker.awaitOutcome(h.id, 5000))
        }
    }

    @Test
    fun `external cancel aborts through the cooperative path`() {
        val uiCancel = AtomicBoolean(false)
        var sawCancel = false
        val tracker = OutgoingTransfers(sender = { _, _, _, isCancelled, _ ->
            while (!isCancelled()) Thread.sleep(20)
            sawCancel = true
            TransferOutcome.Cancelled("t", 0L)
        })
        val h = tracker.startSend("p", source(), "123456", externalCancel = { uiCancel.get() })!!
        Thread.sleep(100)
        uiCancel.set(true) // UI Cancel button path
        val o = tracker.awaitOutcome(h.id, 5000)
        assertTrue(o is TransferOutcome.Cancelled)
        assertTrue(sawCancel)
    }

    @Test
    fun `shutdown cancels all in flight and settles waiters`() {
        val tracker = OutgoingTransfers(sender = { _, _, _, isCancelled, _ ->
            while (!isCancelled()) Thread.sleep(20)
            TransferOutcome.Cancelled("t", 0L)
        })
        val a = tracker.startSend("p", source("a.bin"), "123456")!!
        val b = tracker.startSend("p", source("b.bin"), "123456")!!
        assertEquals(2, tracker.activeCount())
        tracker.shutdown()
        tracker.shutdown() // idempotent
        assertTrue(tracker.awaitOutcome(a.id, 5000) is TransferOutcome.Cancelled)
        assertTrue(tracker.awaitOutcome(b.id, 5000) is TransferOutcome.Cancelled)
        assertEquals(0, tracker.activeCount())
    }

    @Test
    fun `await sees pre decided outcome and progress rows carry file names`() {
        var progCount = 0
        val tracker = OutgoingTransfers(sender = { _, _, _, _, onProgress ->
            onProgress(TransferProgress.compute("t-9", 100L, 100L, 5L, null, TransferEngineState.TRANSFERRING))
            complete("t-9")
        })
        tracker.onProgress = { _, _ -> progCount++ }
        val h = tracker.startSend("p", source("named.bin"), "123456")!!
        assertTrue(tracker.awaitOutcome(h.id, 5000) is TransferOutcome.TransportComplete)
        assertTrue(progCount >= 1)
        // Terminal sends leave no live rows (stale actives never shown).
        assertTrue(tracker.snapshotSends().isEmpty())
        // ...but a live send does show its authoritative file row.
        val gate = CountDownLatch(1)
        val tracker2 = OutgoingTransfers(sender = { _, _, _, isCancelled, onProgress ->
            onProgress(TransferProgress.compute("t-10", 100L, 10L, 5L, null, TransferEngineState.TRANSFERRING))
            gate.await(5, TimeUnit.SECONDS)
            complete("t-10")
        })
        val h2 = tracker2.startSend("p", source("live.bin"), "123456")!!
        var rows = tracker2.snapshotSends()
        val deadline = System.currentTimeMillis() + 5000
        while (rows.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            rows = tracker2.snapshotSends()
        }
        assertEquals(1, rows.size)
        assertEquals("live.bin", rows[0].fileName)
        gate.countDown()
        assertTrue(tracker2.awaitOutcome(h2.id, 5000) is TransferOutcome.TransportComplete)
    }

    @Test
    fun `sender programming error maps to failed never hangs`() {
        val tracker = OutgoingTransfers(sender = { _, _, _, _, _ ->
            throw IllegalStateException("boom")
        })
        val h = tracker.startSend("p", source(), "123456")!!
        val o = tracker.awaitOutcome(h.id, 5000)
        assertTrue(o is TransferOutcome.Failed)
        assertEquals(ErrorCode.INTERNAL_ERROR, (o as TransferOutcome.Failed).code)
    }
}
