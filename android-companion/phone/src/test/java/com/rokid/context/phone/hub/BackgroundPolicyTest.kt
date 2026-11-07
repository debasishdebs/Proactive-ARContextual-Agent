package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import com.rokid.context.phone.hub.BackgroundPolicy.Gate as G

/** v0.9.9: the one pure background policy: gate matrix, back-off ladder (5, 15, 30, 60 min cap) and reset, and what keeps the hub service. */
class BackgroundPolicyTest {
    private fun gate(enabled: Boolean, paired: Boolean, link: LinkState) = BackgroundPolicy.gate(enabled, paired, link)

    @Test fun noPairMeansNothingWhateverTheSettingOrTheLink() {
        for (on in listOf(true, false)) for (l in LinkState.values()) assertEquals("$on $l", G.NO_PAIR, gate(on, false, l))
    }

    @Test fun pairedButSwitchedOffIsDisabledWhateverTheLink() {
        for (l in LinkState.values()) assertEquals(l.name, G.DISABLED, gate(false, true, l))
    }

    @Test fun enabledAndPairedIsActiveWhenTheGlassesAreThere() {
        for (l in listOf(LinkState.CONNECTED, LinkState.MINIMISED, LinkState.CLOSED_ON_GLASSES, LinkState.VERSION_MISMATCH)) assertEquals(l.name, G.ACTIVE, gate(true, true, l))
    }

    @Test fun enabledAndPairedIsAwayWhenTheyAreNot() {
        for (l in listOf(LinkState.OFFLINE, LinkState.PAUSED_BT, LinkState.NOT_AUTHORIZED, LinkState.CONNECTING)) assertEquals(l.name, G.AWAY, gate(true, true, l))
    }

    @Test fun linkMachineryNeedsTheSettingAndAPair() {
        assertTrue(BackgroundPolicy.linkMachineryAllowed(true, true))
        assertFalse(BackgroundPolicy.linkMachineryAllowed(true, false)); assertFalse(BackgroundPolicy.linkMachineryAllowed(false, true)); assertFalse(BackgroundPolicy.linkMachineryAllowed(false, false))
    }

    @Test fun theLadderIsFiveFifteenThirtySixtyAndStaysAtSixty() {
        assertEquals(listOf(5, 15, 30, 60).map { it * 60_000L }, BackgroundPolicy.BACKOFF_MS)
        var b = BackgroundPolicy.reset(); var t = 1_000L
        val waits = ArrayList<Long>()
        repeat(7) { assertTrue("due at the start of step ${b.step}", b.due(t)); b = BackgroundPolicy.attempted(b, t); waits += b.nextAtMs - t; t = b.nextAtMs }
        assertEquals(listOf(5L, 15L, 30L, 60L, 60L, 60L, 60L).map { it * 60_000L }, waits)
    }

    @Test fun anAttemptIsNotDueBeforeItsBackoffEnds() {
        val b = BackgroundPolicy.attempted(BackgroundPolicy.reset(), 0L)
        assertFalse(b.due(299_999L)); assertTrue(b.due(300_000L))
    }

    @Test fun aPositiveSignalResetsTheLadderToDueNow() {
        var b = BackgroundPolicy.reset(); repeat(5) { b = BackgroundPolicy.attempted(b, 0L) }
        assertEquals(60 * 60_000L, b.nextAtMs)
        b = BackgroundPolicy.reset()
        assertTrue(b.due(0L)); assertEquals(0, b.step)
        assertEquals("the ladder starts again at 5 min", 5 * 60_000L, BackgroundPolicy.attempted(b, 0L).nextAtMs)
    }

    @Test fun theWatchdogSleepsLongWithNothingToDoAndUntilTheBackoffEndsWhenAway() {
        val b = BackgroundPolicy.attempted(BackgroundPolicy.reset(), 1_000L) // next at 301_000
        assertEquals(BackgroundPolicy.IDLE_RECHECK_MS, BackgroundPolicy.sleepMs(G.NO_PAIR, 150_000L, b, 1_000L))
        assertEquals(BackgroundPolicy.IDLE_RECHECK_MS, BackgroundPolicy.sleepMs(G.DISABLED, 150_000L, b, 1_000L))
        assertEquals(150_000L, BackgroundPolicy.sleepMs(G.ACTIVE, 150_000L, b, 1_000L))
        assertEquals(300_000L, BackgroundPolicy.sleepMs(G.AWAY, 150_000L, b, 1_000L))
        assertEquals("never below the tick", 150_000L, BackgroundPolicy.sleepMs(G.AWAY, 150_000L, b, 300_000L))
    }

    // ---- what keeps the hub foreground service ----
    private fun needed(enabled: Boolean = false, paired: Boolean = false, work: Boolean = false, autoStopped: Boolean = false, manual: Boolean = false) =
        BackgroundPolicy.hubNeeded(BackgroundPolicy.Needs(enabled, paired, work, autoStopped, manual))

    @Test fun theHubStaysForTheGlassesLinkOnlyWhenEnabledAndPaired() {
        assertTrue(needed(enabled = true, paired = true))
        assertFalse(needed(enabled = true, paired = false)); assertFalse(needed(enabled = false, paired = true)); assertFalse(needed())
    }

    @Test fun activeWorkKeepsTheHubWithoutTheGlassesLink() {
        assertTrue(needed(work = true)); assertTrue(needed(enabled = true, paired = false, work = true))
    }

    @Test fun aStoppedHubStaysStoppedUntilAUserStart() {
        assertFalse("auto stopped", needed(enabled = true, paired = true, autoStopped = true))
        assertFalse("manual stop wins", needed(enabled = true, paired = true, manual = true))
        assertTrue("a tapped Reconnect (active work) is a user start", needed(enabled = true, paired = true, work = true, autoStopped = true, manual = true))
    }

    @Test fun everyPhoneContextSourceIsListed() {
        assertEquals(setOf("watch_app", "phone_activity", "phone_steps", "phone_airplane", "phone_car", "phone_speed", "image_intent"), BackgroundPolicy.CONTEXT_SOURCE_IDS.toSet())
    }

    // ---- AutoStop ----
    private val L = com.rokid.context.core.model.LinkState.values()
    private val MIN = 60_000L
    private fun input(now: Long, away: BackgroundPolicy.Away? = BackgroundPolicy.Away.NOT_WORN, since: Long? = 0L, minutes: Int = BackgroundPolicy.AUTO_STOP_DEFAULT_MIN,
                      front: Long = 0L, inFront: Boolean = false, busy: Boolean = false, run: Boolean = false) =
        BackgroundPolicy.autoStop(BackgroundPolicy.AutoStopInput(now, minutes, since, away, front, inFront, busy, run))

    @Test fun theDefaultIsTwentyMinutesWithinFiveToOneHundredTwentyAndNever() {
        assertEquals(20, BackgroundPolicy.AUTO_STOP_DEFAULT_MIN)
        assertEquals(5, BackgroundPolicy.cleanAutoStopMin(1)); assertEquals(5, BackgroundPolicy.cleanAutoStopMin(-3)); assertEquals(120, BackgroundPolicy.cleanAutoStopMin(9_999))
        assertEquals(45, BackgroundPolicy.cleanAutoStopMin(45)); assertEquals("Never stays Never", BackgroundPolicy.AUTO_STOP_NEVER, BackgroundPolicy.cleanAutoStopMin(0))
        assertTrue(BackgroundPolicy.AUTO_STOP_PRESETS.all { it == 0 || it in 5..120 }); assertTrue(0 in BackgroundPolicy.AUTO_STOP_PRESETS)
    }

    @Test fun awayFollowsTheLinkAndTheWearSignal() {
        val up = listOf(LinkState.CONNECTED, LinkState.MINIMISED, LinkState.CLOSED_ON_GLASSES, LinkState.VERSION_MISMATCH)
        val down = listOf(LinkState.OFFLINE, LinkState.PAUSED_BT, LinkState.NOT_AUTHORIZED, LinkState.CONNECTING)
        for (l in up) { assertNull("$l worn", BackgroundPolicy.away(l, true)); assertNull("$l wear unknown", BackgroundPolicy.away(l, null)); assertEquals("$l off head", BackgroundPolicy.Away.NOT_WORN, BackgroundPolicy.away(l, false)) }
        for (l in down) for (w in listOf(true, false, null)) assertEquals("$l $w", BackgroundPolicy.Away.DISCONNECTED, BackgroundPolicy.away(l, w))
        assertEquals(L.size, up.size + down.size)
    }

    @Test fun theHubStopsOnlyAfterNMinutesAway() {
        assertFalse(input(now = 19 * MIN).stop); assertFalse(input(now = 20 * MIN - 1).stop)
        val at = input(now = 20 * MIN); assertTrue(at.stop); assertEquals(BackgroundPolicy.Away.NOT_WORN, at.reason)
        assertTrue(input(now = 21 * MIN).stop)
        assertEquals("wait exactly until due", 1 * MIN, input(now = 19 * MIN).recheckInMs)
        assertTrue(input(now = 20 * MIN, away = BackgroundPolicy.Away.DISCONNECTED).reason == BackgroundPolicy.Away.DISCONNECTED)
    }

    @Test fun theConfiguredMinutesApply() {
        assertFalse(input(now = 29 * MIN, minutes = 30).stop); assertTrue(input(now = 30 * MIN, minutes = 30).stop)
        assertTrue("5 min", input(now = 5 * MIN, minutes = 5).stop); assertFalse(input(now = 4 * MIN, minutes = 5).stop)
        assertFalse("out of range 1 is held at 5", input(now = 4 * MIN, minutes = 1).stop); assertTrue(input(now = 5 * MIN, minutes = 1).stop)
    }

    @Test fun neverKeepsTheOldBehaviour() {
        val d = input(now = 48 * 60 * MIN, minutes = BackgroundPolicy.AUTO_STOP_NEVER); assertFalse(d.stop); assertNull(d.recheckInMs)
    }

    @Test fun presentGlassesNeverStop() {
        assertFalse(input(now = 999 * MIN, away = null, since = null).stop); assertNull(input(now = 999 * MIN, away = null, since = null).recheckInMs)
        assertFalse("away but the clock has not started", input(now = 999 * MIN, since = null).stop)
    }

    @Test fun anAskInFlightOrARunOrTheFrontHoldsTheStop() {
        assertFalse(input(now = 60 * MIN, busy = true).stop); assertEquals(BackgroundPolicy.BUSY_RECHECK_MS, input(now = 60 * MIN, busy = true).recheckInMs)
        assertFalse(input(now = 60 * MIN, run = true).stop)
        assertFalse("in front", input(now = 60 * MIN, inFront = true).stop)
        assertEquals(BackgroundPolicy.FRONT_HOLD_MS, input(now = 60 * MIN, inFront = true).recheckInMs)
    }

    @Test fun aTappedReconnectOrAppOpenKeepsTheHubForMMinutes() {
        val m = BackgroundPolicy.FRONT_HOLD_MS
        assertFalse("front 5 min ago", input(now = 60 * MIN, front = 55 * MIN).stop)
        assertEquals(m - 5 * MIN, input(now = 60 * MIN, front = 55 * MIN).recheckInMs)
        assertTrue("front left M ago", input(now = 60 * MIN, front = 60 * MIN - m).stop)
        assertTrue("front long ago", input(now = 60 * MIN, front = 1L).stop)
    }

    @Test fun theBannerSaysWhenAndWhy() {
        assertEquals("Stopped: glasses not worn since 14:05. Tap to start", BackgroundPolicy.stoppedNote(BackgroundPolicy.Away.NOT_WORN, "14:05"))
        assertEquals("Stopped: glasses not connected since 09:30. Tap to start", BackgroundPolicy.stoppedNote(BackgroundPolicy.Away.DISCONNECTED, "09:30"))
        assertEquals("auto_stop_not_worn", BackgroundPolicy.Away.NOT_WORN.token)
    }

    @Test fun aBackgroundWakeNeedsTheExemptionAndEveryOtherCondition() {
        fun due(exempt: Boolean = true, stopped: Boolean = true, manual: Boolean = false, min: Int = 20, running: Boolean = false, allowed: Boolean = true, now: Long = 100L, next: Long = 100L) =
            BackgroundPolicy.wakeDue(exempt, stopped, manual, min, running, allowed, now, next)
        assertTrue(due())
        assertFalse("no exemption: nothing starts in the background", due(exempt = false)); assertFalse("never stopped itself", due(stopped = false))
        assertFalse("manual stop wins", due(manual = true)); assertFalse("Never", due(min = 0)); assertFalse("already running", due(running = true))
        assertFalse("link not allowed", due(allowed = false)); assertFalse("ladder not due", due(now = 99L))
    }

    @Test fun theWakeLadderIsAnHourThenTwoThenFourAndNeverShorter() {
        assertEquals(listOf(60L, 120L, 240L), (0..2).map { BackgroundPolicy.wakeDelayMs(it) / MIN }); assertEquals(240L, BackgroundPolicy.wakeDelayMs(50) / MIN)
        assertEquals(60L, BackgroundPolicy.wakeDelayMs(-1) / MIN)
    }
}
