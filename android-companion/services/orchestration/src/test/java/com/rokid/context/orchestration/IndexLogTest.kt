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

    @Test fun nothingToDoAndAClockThatWentBackwards() {
        val l = ProgressLimiter({ t })
        assertFalse(l.due("k", 0, 0))
        t = 100_000; assertTrue(l.due("k", 1, 100))
        t = 5_000; assertTrue(l.due("k", 2, 100)) // a clock set back is not "less than 30 s since"
    }

    @Test fun progressLineFormat() {
        assertEquals("text index (building): 1200/3400 embedded (35%) space=space_a", IndexLogFormat.progress("text", "building", 1200, 3400, "space_a"))
        assertEquals("image index (active): 3/3 embedded (100%) space=space_b", IndexLogFormat.progress("image", "active", 3, 3, "space_b"))
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
        assertEquals("index health: NEEDED (space stale)", s.health("NEEDED", "space stale"))
        assertNull(s.health("NEEDED", "again"))
        assertEquals("index health: NEEDED -> RUNNING (build started)", s.health("RUNNING", "build started"))
        assertEquals("index health: RUNNING -> NONE (swap complete)", s.health("NONE", "swap complete"))
        assertEquals("index health: NONE -> UNSTABLE (3 discards)", s.health("UNSTABLE", "3 discards"))
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
        assertEquals("battery 55% and not charging (needs a charger or battery above 70%)", BulkGateReason.of(PowerState(55, false), true))
        assertEquals("battery unknown and not charging (needs a charger or battery above 70%)", BulkGateReason.of(PowerState(-1, false), true))
        assertEquals("thermal/background policy (phone too warm)", BulkGateReason.of(PowerState(90, false), false))
        assertEquals("bulk gate closed", BulkGateReason.of(PowerState(30, true), true))
    }
}
