package com.rokid.context.orchestration

import com.rokid.context.ai.embed.PowerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexLogTest {
    private var t = 0L

    @Test fun firstReportLogsThenWaitsForIntervalOrTenPercent() {
        val l = ProgressLimiter({ t })
        assertTrue(l.due("text:s1", 16, 3400))      // first
        t += 1_000
        assertFalse(l.due("text:s1", 32, 3400))     // 1 s later, same 10% bucket
        t += 28_000
        assertFalse(l.due("text:s1", 48, 3400))     // 29 s after the first, same bucket
        t += 1_000
        assertTrue(l.due("text:s1", 64, 3400))      // 30 s since the last line
        t += 1_000
        assertFalse(l.due("text:s1", 100, 3400))
        assertTrue(l.due("text:s1", 340, 3400))     // 10% bucket crossed, only 1 s later
    }

    @Test fun aFastBuildWritesAboutTenLines() {
        val l = ProgressLimiter({ t })
        var lines = 0
        for (done in 16..3400 step 16) { t += 50; if (l.due("text:s1", done, 3400)) lines++ } // 3400 items in ~10 s
        assertTrue("lines=$lines", lines in 10..12)
    }

    @Test fun aSlowBuildWritesOneLinePerInterval() {
        val l = ProgressLimiter({ t })
        var lines = 0
        for (i in 1..100) { t += 10_000; if (l.due("text:s1", 5 + i / 10, 3400)) lines++ } // 1000 s, below 1% in total
        assertTrue("lines=$lines", lines in 33..35)
    }

    @Test fun keysAreIndependentAndForgettable() {
        val l = ProgressLimiter({ t })
        assertTrue(l.due("text:s1", 1, 100)); assertTrue(l.due("image:s1", 1, 100)); assertFalse(l.due("text:s1", 2, 100))
        l.forget("text:") // prefix match
        assertTrue(l.due("text:s1", 3, 100)); assertFalse(l.due("image:s1", 2, 100))
    }

    @Test fun forgetClearsTheKeysAsTheyAreBuiltForASpace() {
        val l = ProgressLimiter({ t })
        assertTrue(l.due("text:space_x", 1, 100)); assertTrue(l.due("image:space_x", 1, 100))
        assertEquals(2, l.tracked())
        l.forget("text:space_x"); l.forget("image:space_x")
        assertEquals(0, l.tracked())
        assertTrue("a cleared key reports again at once", l.due("text:space_x", 2, 100))
    }

    @Test fun mayLogIsTheCheapTimeGateBeforeAnyCount() {
        val l = ProgressLimiter({ t })
        assertTrue(l.mayLog("k"))                   // never logged: a count may be taken
        assertTrue(l.due("k", 1, 100))
        t += 5_000; assertFalse(l.mayLog("k"))      // inside the interval: no count, no line
        t += 25_000; assertTrue(l.mayLog("k"))
    }

    @Test fun changedLogsOnlyAChange() {
        val s = IndexLogState({ t })
        assertTrue(s.changed("sp:swapwait", "true:2"))
        repeat(20) { assertFalse(s.changed("sp:swapwait", "true:2")) }   // N identical paused runs: one line
        assertTrue(s.changed("sp:swapwait", "true:1"))
        assertTrue(s.changed("sp:swapwait", "true:2"))
        s.resetReady("sp")
        assertTrue(s.changed("sp:swapwait", "true:2"))                    // a finished space starts afresh
    }

    @Test fun gateReasonIsObservationalAndNeverReadsAgain() {
        var powerReads = 0; var thermalReads = 0
        val g = BulkGate({ powerReads++; PowerState(40, false) }, { thermalReads++; false })
        assertFalse(g.allowed())
        assertEquals(1, powerReads); assertEquals("battery closed: the thermal policy is not consulted", 0, thermalReads)
        repeat(5) { assertEquals("battery 40% and not charging (needs a charger or battery above 50%)", g.reason()) } // the default level (Settings > Capture)
        assertEquals(1, powerReads); assertEquals(0, thermalReads)           // formatting read nothing
        val warm = BulkGate({ PowerState(90, false) }, { thermalReads++; false })
        assertFalse(warm.allowed()); assertEquals(1, thermalReads)
        assertEquals("thermal/background policy (phone too warm)", warm.reason()); assertEquals(1, thermalReads)
        assertEquals("before any decision", "bulk gate closed", BulkGate({ error("no") }, { error("no") }).reason())
    }

    @Test fun formattingTheReasonDoesNotChangeThermalGating() {
        // The real policy: SEVERE latches in mayRunBackground. A closed battery gate short-circuits before it; the reason must not consult it afterwards.
        var status = com.rokid.context.work.budget.PhoneThermal.SEVERE; var reads = 0
        val policy = com.rokid.context.work.budget.PhonePowerPolicy({ t }, { true }, { reads++; status })
        val g = BulkGate({ PowerState(30, false) }, { policy.mayRunBackground(com.rokid.context.work.budget.BackgroundWork.BACKFILL) })
        assertFalse(g.allowed()); g.reason(); g.reason()
        assertEquals("the thermal policy was never read", 0, reads)
        status = com.rokid.context.work.budget.PhoneThermal.NONE            // cooled before the next extraction
        assertTrue("no latch was set by the log", policy.mayRunBackground(com.rokid.context.work.budget.BackgroundWork.EXTRACTION))
    }

    @Test fun nothingToDoAndAClockThatWentBackwards() {
        val l = ProgressLimiter({ t })
        assertFalse(l.due("k", 0, 0))
        t = 100_000; assertTrue(l.due("k", 1, 100))
        t = 5_000; assertTrue(l.due("k", 2, 100)) // a clock set back is not "less than 30 s since"
    }

    @Test fun percentIsClamped() {
        assertEquals(35, IndexLogFormat.percent(1200, 3400))
        assertEquals(100, IndexLogFormat.percent(3, 3))
        assertEquals(100, IndexLogFormat.percent(5, 0))
        assertEquals(100, IndexLogFormat.percent(9, 5)) // never above 100
        assertEquals(0, IndexLogFormat.percent(-1, 5))
    }

    @Test fun durationFormat() {
        assertEquals("850 ms", IndexLogFormat.duration(850))
        assertEquals("42 s", IndexLogFormat.duration(42_000))
        assertEquals("3 min 05 s", IndexLogFormat.duration(185_000))
        assertEquals("2 h 07 min", IndexLogFormat.duration((2 * 3600 + 7 * 60) * 1000L))
        assertEquals("0 ms", IndexLogFormat.duration(-5))
    }

    @Test fun modelLabelCutsHashesAndKeepsReadableIds() {
        assertEquals("0123456789ab", IndexLogFormat.model("0123456789abcdef".repeat(4)))
        assertEquals("nomic-embed-text-v1.5|d=768", IndexLogFormat.model("nomic-embed-text-v1.5|d=768"))
        assertEquals(96, IndexLogFormat.model("x".repeat(300)).length)
    }

    @Test fun healthLogsOnlyChanges() {
        val s = IndexLogState({ t })
        s.health("NEEDED")!!.let { assertNull(it.before); assertEquals("NEEDED", it.state) }
        assertNull(s.health("NEEDED"))
        s.health("RUNNING")!!.let { assertEquals("NEEDED", it.before); assertEquals("RUNNING", it.state) }
        s.health("NONE")!!.let { assertEquals("RUNNING", it.before); assertEquals("NONE", it.state) }
        s.health("UNSTABLE")!!.let { assertEquals("NONE", it.before); assertEquals("UNSTABLE", it.state) }
    }

    @Test fun pauseIsLoggedOnceAndResumeReportsTheWait() {
        val s = IndexLogState({ t })
        assertNull(s.resume("images"))                 // never paused: no resume line
        assertTrue(s.pause("images", "battery 40% and not charging"))
        assertFalse(s.pause("images", "again"))        // repeated pause is silent
        t += 125_000
        assertEquals("battery 40% and not charging" to 125_000L, s.resume("images"))
        assertNull(s.resume("images"))                 // one resume per pause
        assertTrue(s.pause("images", "thermal"))       // a later pause logs again
    }

    @Test fun readyIsOncePerKey() {
        val s = IndexLogState({ t })
        assertTrue(s.firstReady("sp:text")); assertFalse(s.firstReady("sp:text")); assertTrue(s.firstReady("sp:full"))
        s.resetReady("sp"); assertTrue(s.firstReady("sp:text"))
    }

    @Test fun gateReasonNamesTheBlocker() {
        val saved = com.rokid.context.ai.embed.LocalModelPower.minBatteryPct
        com.rokid.context.ai.embed.LocalModelPower.minBatteryPct = { 70 }
        try { gateReasonAtSeventy() } finally { com.rokid.context.ai.embed.LocalModelPower.minBatteryPct = saved }
    }

    private fun gateReasonAtSeventy() {
        assertEquals("battery 55% and not charging (needs a charger or battery above 70%)", BulkGateReason.of(PowerState(55, false), true))
        assertEquals("battery unknown and not charging (needs a charger or battery above 70%)", BulkGateReason.of(PowerState(-1, false), true))
        assertEquals("thermal/background policy (phone too warm)", BulkGateReason.of(PowerState(90, false), false))
        assertEquals("bulk gate closed", BulkGateReason.of(PowerState(30, true), true))
    }

    @Test fun theLimiterAndTheStateAreHardBounded() {
        val l = ProgressLimiter({ t })
        repeat(500) { l.due("text:space_$it", 1, 100) }
        assertEquals(ProgressLimiter.MAX_KEYS, l.tracked())
        assertFalse("the oldest were evicted, the newest kept", l.mayLog("text:space_499"))
        assertTrue(l.mayLog("text:space_0"))
        val s = IndexLogState({ t })
        repeat(500) { s.firstReady("space_$it:full"); s.changed("space_$it:build", "seen") }
        assertEquals(2 * IndexLogState.MAX_ENTRIES, s.remembered())
        assertFalse("the newest record survives", s.firstReady("space_499:full"))
    }
}
