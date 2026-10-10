package com.localdrop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// M13 §2: collision-safe notification identity. "Aa" and "BB" share the same
// String.hashCode() (2112) — the old scheme would have merged/cancelled the
// wrong transfer's notification.
class NotificationIdsTest {

    @Test
    fun `hash-colliding transfer ids get distinct notification ids`() {
        assertEquals("Aa".hashCode(), "BB".hashCode()) // premise of the test
        val ids = NotificationIds()
        val a = ids.idFor("Aa")
        val b = ids.idFor("BB")
        assertNotEquals(a, b)
    }

    @Test
    fun `same transfer id returns stable id without duplicates`() {
        val ids = NotificationIds()
        val first = ids.idFor("t-1")
        assertEquals(first, ids.idFor("t-1"))
        assertEquals(1, ids.size())
    }

    @Test
    fun `release routes cancellation to the exact id`() {
        val ids = NotificationIds()
        val a = ids.idFor("Aa")
        val b = ids.idFor("BB")
        assertEquals(a, ids.release("Aa"))
        // The survivor keeps its own id; nothing leaks across transfers.
        assertEquals(b, ids.peek("BB"))
        assertNull(ids.peek("Aa"))
    }

    @Test
    fun `releasing unknown id cancels nothing`() {
        val ids = NotificationIds()
        ids.idFor("live")
        assertNull(ids.release("never-seen"))
        assertTrue(ids.peek("live") != null)
    }
}
