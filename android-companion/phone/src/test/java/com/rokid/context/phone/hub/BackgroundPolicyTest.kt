package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    private fun needed(enabled: Boolean = false, paired: Boolean = false, source: Boolean = false, work: Boolean = false) =
        BackgroundPolicy.hubNeeded(BackgroundPolicy.Needs(enabled, paired, source, work))

    @Test fun theHubStaysForTheGlassesLinkOnlyWhenEnabledAndPaired() {
        assertTrue(needed(enabled = true, paired = true))
        assertFalse(needed(enabled = true, paired = false)); assertFalse(needed(enabled = false, paired = true)); assertFalse(needed())
    }

    @Test fun aContextSourceOrActiveWorkKeepsTheHubWithoutTheGlasses() {
        assertTrue(needed(source = true)); assertTrue(needed(work = true))
        assertTrue(needed(enabled = false, paired = true, source = true))
        assertTrue(needed(enabled = true, paired = false, work = true))
    }

    @Test fun everyPhoneContextSourceIsListed() {
        assertEquals(setOf("watch_app", "phone_activity", "phone_steps", "phone_airplane", "phone_car", "phone_speed", "image_intent"), BackgroundPolicy.CONTEXT_SOURCE_IDS.toSet())
    }
}
