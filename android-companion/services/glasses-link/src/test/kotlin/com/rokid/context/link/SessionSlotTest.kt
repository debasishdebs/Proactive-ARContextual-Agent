package com.rokid.context.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Phase 8 C4: every path that drops a CXR session closes it exactly once. */
class SessionSlotTest {
    private class S(val name: String) { var closes = 0 }
    private val logs = mutableListOf<String>()
    private val slot = SessionSlot<S>(close = { it.closes++ }, log = { logs += it })

    @Test fun aSetupThrowClosesTheCreatedSession() {
        val s = S("s")
        slot.replace(s)
        slot.fail(s)
        assertEquals(1, s.closes)
        assertNull(slot.current)
    }

    @Test fun aFailedConnectClosesTheSession() {
        val s = S("s")
        slot.replace(s)
        assertTrue(slot.clearIf(s))
        assertEquals(1, s.closes)
        assertNull(slot.current)
    }

    @Test fun replacingClosesTheOldSession() {
        val a = S("a"); val b = S("b")
        slot.replace(a)
        slot.replace(b)
        assertEquals(1, a.closes)
        assertEquals(0, b.closes)
        assertSame(b, slot.current)
    }

    @Test fun aStaleSessionIsNotClosedTwice() {
        val a = S("a"); val b = S("b")
        slot.replace(a)
        slot.replace(b)
        assertFalse(slot.clearIf(a))
        slot.fail(a)
        assertEquals(1, a.closes)
        assertSame(b, slot.current)
    }

    @Test fun aThrowingCloseIsSwallowed() {
        val throwing = SessionSlot<S>(close = { throw IllegalStateException("sdk") }, log = { logs += it })
        val s = S("s")
        throwing.replace(s)
        throwing.replace(null)
        assertNull(throwing.current)
        assertEquals(1, logs.size)
        assertTrue("IllegalStateException" in logs.single())
    }

    @Test fun replacingWithTheSameSessionKeepsIt() {
        val s = S("s")
        slot.replace(s); slot.replace(s)
        assertEquals(0, s.closes)
        assertSame(s, slot.current)
    }
}
