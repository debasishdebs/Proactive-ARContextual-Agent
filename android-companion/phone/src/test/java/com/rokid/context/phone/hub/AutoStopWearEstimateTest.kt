package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.WearEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.0 wear fallback: "not worn (estimated)" feeds AutoStop with a longer wait than a real off-head event, and the real signal always wins. */
class AutoStopWearEstimateTest {
    private val min = 60_000L

    private class Rig {
        var t = 1_000_000L; var minutes = 20; var running = true; var link = LinkState.CONNECTED; var worn: Boolean? = true; var est = false
        var stops = 0; var note: String? = null; val logs = mutableListOf<String>(); var noteSinceMs = -1L
        val c = AutoStopController(
            now = { t }, minutes = { minutes }, store = InMemoryAutoStopStore(), hubRunning = { running }, link = { link }, worn = { worn }, inFront = { false }, lastFrontMs = { 0L },
            busy = { false }, runActive = { false }, stopHub = { stops++; running = false }, wakeHub = { false }, exempt = { false }, linkAllowed = { true },
            publishNote = { note = it }, timeText = { noteSinceMs = it; "14:05" }, log = { logs += it }, executor = SerialStateMachine.DIRECT, wornEstimated = { est },
        )
        fun advance(ms: Long) { t += ms }
    }

    @Test fun awayIsEstimatedOnlyWithTheLinkUpAndNoRealOffHead() {
        assertEquals(BackgroundPolicy.Away.NOT_WORN_EST, BackgroundPolicy.away(LinkState.CONNECTED, true, estimated = true))
        assertEquals(BackgroundPolicy.Away.NOT_WORN_EST, BackgroundPolicy.away(LinkState.MINIMISED, null, estimated = true))
        assertEquals("a real off-head wins", BackgroundPolicy.Away.NOT_WORN, BackgroundPolicy.away(LinkState.CONNECTED, false, estimated = true))
        assertEquals("a dropped link wins", BackgroundPolicy.Away.DISCONNECTED, BackgroundPolicy.away(LinkState.OFFLINE, true, estimated = true))
        assertNull(BackgroundPolicy.away(LinkState.CONNECTED, true, estimated = false))
        assertNull("the old two-argument call is unchanged", BackgroundPolicy.away(LinkState.CONNECTED, true))
    }

    @Test fun theEstimateStopsNMinutesAfterItBeganSoWindowPlusNAfterTheLastHeadMotion() {
        val r = Rig()
        val lastMotion = r.t
        r.c.evaluate(); r.advance(WearEstimate.WINDOW_MS) // the glasses need their 10 quiet minutes before they say anything
        r.est = true
        assertEquals("N counts from the estimate", 20 * min, r.c.evaluate())
        r.advance(20 * min - 1); r.c.evaluate(); assertEquals(0, r.stops)
        r.advance(1); r.c.evaluate()
        assertEquals(1, r.stops)
        assertEquals("window + N after the last head motion", WearEstimate.WINDOW_MS + 20 * min, r.t - lastMotion)
        assertTrue("auto_stop_not_worn_est" in r.logs)
        assertEquals("Stopped: glasses not worn (estimated) since 14:05. Tap to start", r.note)
        assertEquals("the note's time is when the glasses went still, not when the estimate came in", lastMotion, r.noteSinceMs)
    }

    @Test fun aRealOffHeadIsTheShorterWait() {
        val real = Rig().apply { worn = false }
        real.c.evaluate(); real.advance(20 * min); real.c.evaluate()
        assertEquals("a real off-head: N after the event", 1, real.stops)
        assertTrue("auto_stop_not_worn" in real.logs)
    }

    @Test fun movementOrInteractionEndsTheEstimateAndTheClockStartsAgain() {
        val r = Rig().apply { est = true }
        r.c.evaluate(); r.advance(15 * min)
        r.est = false; assertNull("worn again", r.c.evaluate())
        r.advance(5 * min); r.est = true; r.c.evaluate()
        r.advance(19 * min); r.c.evaluate(); assertEquals(0, r.stops)
        r.advance(1 * min); r.c.evaluate(); assertEquals(1, r.stops)
    }

    @Test fun noEstimateNoStop() {
        val r = Rig()
        r.c.evaluate(); r.advance(500 * min); r.c.evaluate()
        assertEquals(0, r.stops)
        assertFalse(r.logs.any { it.startsWith("auto_stop") })
    }
}
