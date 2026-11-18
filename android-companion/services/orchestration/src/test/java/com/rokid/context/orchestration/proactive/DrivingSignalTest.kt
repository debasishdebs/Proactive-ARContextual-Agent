package com.rokid.context.orchestration.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingSignalTest {
    /** The MONOTONIC clock; [wall] is a wall clock the tracker must never read (it is not even passed in). */
    private var t = 10_000_000_000L
    private var wall = 1_790_000_000_000L
    private val tr = DrivingTracker { t }
    private val min = 60_000L

    /** Real time passes: both clocks advance. */
    private fun tick(ms: Long) { t += ms; wall += ms }
    /** Hub observed continuously for [ms], a heartbeat every 10 s. */
    private fun monitor(ms: Long, suspected: Boolean = false) { var n = 0L; while (n < ms) { tr.heartbeat(suspected); tick(10_000); n += 10_000 } }
    private fun state() = tr.assess().state
    private fun twoCalm() { tr.calm(CalmSource.PHONE_STEPS, Calm.WALKING); tr.calm(CalmSource.GLASSES, Calm.STILL) }

    @Test fun processStartWithNoDataIsUnknownAndBlocks() {
        val a = tr.assess()
        assertEquals(DrivingState.UNKNOWN, a.state); assertTrue(a.blocks)
        assertEquals("Unknown: no recent motion data: suggestions are paused", a.reason)
    }

    @Test fun twoFreshReadingsAreUnknownUntilTenMinutesAreObserved() {
        monitor(2 * min); twoCalm()
        assertEquals(DrivingState.UNKNOWN, state()); assertTrue(tr.assess().reason.contains("only just started"))
    }

    @Test fun oneSourceAloneIsNeverNotDriving() {
        monitor(10 * min)
        for ((s, k) in listOf(CalmSource.GLASSES to Calm.STILL, CalmSource.GLASSES to Calm.WALKING, CalmSource.PHONE_STEPS to Calm.WALKING, CalmSource.SPEED to Calm.SLOW, CalmSource.WATCH to Calm.WALKING)) {
            val x = DrivingTracker { t }; repeat(61) { x.heartbeat(false); tick(10_000) }
            x.calm(s, k)
            assertEquals("$s $k", "Unknown: only one motion source: suggestions are paused", x.assess().reason)
            assertTrue(x.assess().blocks)
        }
    }

    @Test fun theSameSourceRepeatedIsStillOneSource() {
        monitor(10 * min); tr.calm(CalmSource.GLASSES, Calm.WALKING); tr.calm(CalmSource.GLASSES, Calm.STILL); tr.calm(CalmSource.GLASSES, Calm.WALKING)
        assertEquals(DrivingState.UNKNOWN, state())
    }

    @Test fun twoIndependentFreshSourcesAreNotDriving() {
        monitor(10 * min); twoCalm()
        val a = tr.assess()
        assertEquals(DrivingState.NOT_DRIVING, a.state); assertFalse(a.blocks)
        assertEquals("Not driving: 2 motion sources agree (still and walking)", a.reason)
    }

    @Test fun aStillHintPlusStepsAndSlowSpeedPlusWatchBothQualify() {
        monitor(10 * min); tr.calm(CalmSource.GLASSES, Calm.STILL); tr.calm(CalmSource.PHONE_STEPS, Calm.WALKING)
        assertEquals(DrivingState.NOT_DRIVING, state())
        val x = DrivingTracker { t }; repeat(61) { x.heartbeat(false); tick(10_000) }
        x.speed(1.0f); x.calm(CalmSource.WATCH, Calm.WALKING)
        assertEquals("Not driving: 2 motion sources agree (walking and moving slowly)", x.assess().reason)
    }

    @Test fun onlyStillReadingsFromTwoSourcesProveNothing() {
        monitor(10 * min); tr.calm(CalmSource.GLASSES, Calm.STILL); tr.calm(CalmSource.PHONE_ACTIVITY, Calm.STILL)
        assertEquals("Unknown: only still readings: suggestions are paused", tr.assess().reason)
    }

    @Test fun aMidSpeedSaysNothing() { monitor(10 * min); tr.speed(4f); tr.calm(CalmSource.GLASSES, Calm.STILL); assertEquals(DrivingState.UNKNOWN, state()) }

    @Test fun staleCalmDataIsUnknown() {
        monitor(10 * min); twoCalm()
        monitor(2 * min); assertEquals("2 min old is still fresh", DrivingState.NOT_DRIVING, state())
        monitor(10_000); assertEquals(DrivingState.UNKNOWN, state())
    }

    @Test fun oneSourceGoingStaleLeavesOneSource() {
        monitor(10 * min); tr.calm(CalmSource.GLASSES, Calm.STILL); monitor(90_000); tr.calm(CalmSource.PHONE_STEPS, Calm.WALKING)
        assertEquals(DrivingState.NOT_DRIVING, state())
        monitor(40_000); assertEquals("the still reading is 130 s old", "Unknown: only one motion source: suggestions are paused", tr.assess().reason)
    }

    @Test fun aReadingWithAnAgeIsBackdated() {
        monitor(10 * min); tr.calm(CalmSource.GLASSES, Calm.STILL, ageMs = 119_000); tr.calm(CalmSource.PHONE_STEPS, Calm.WALKING)
        assertEquals(DrivingState.NOT_DRIVING, state())
        tick(2_000); assertEquals(DrivingState.UNKNOWN, state())
        tr.calm(CalmSource.GLASSES, Calm.STILL, ageMs = 121_000); tr.calm(CalmSource.PHONE_STEPS, Calm.WALKING, ageMs = 121_000)
        assertEquals("already stale on arrival", DrivingState.UNKNOWN, state())
    }

    @Test fun fastSpeedIsDrivingAtAndAboveSevenMetresPerSecond() {
        monitor(10 * min); tr.speed(6.9f); assertEquals(DrivingState.UNKNOWN, state())
        tr.speed(7f); assertEquals(DrivingState.DRIVING, state()); assertTrue(tr.assess().blocks)
    }

    @Test fun conflictingSignalsTheVehicleWins() {
        monitor(10 * min); twoCalm(); tr.vehicle()
        assertEquals(DrivingState.DRIVING, state())
        tr.calm(CalmSource.WATCH, Calm.WALKING); assertEquals(DrivingState.DRIVING, state())
    }

    @Test fun theTenMinuteBoundary() {
        monitor(10 * min); tr.vehicle(ageMs = 10 * min); twoCalm()
        assertEquals("exactly 10 min is still driving", DrivingState.DRIVING, state())
        tick(1); tr.heartbeat(false); twoCalm()
        assertEquals("just over 10 min: the calm readings rule", DrivingState.NOT_DRIVING, state())
    }

    @Test fun aSuspectedVehicleOnTheHeartbeatIsDrivingNowAndFor10MinutesAfter() {
        monitor(30_000, suspected = true)
        assertEquals("Driving: a vehicle is suspected now: suggestions are paused", tr.assess().reason)
        tick(5 * min)
        assertEquals("Driving: a vehicle was detected 5 min ago: suggestions are paused", tr.assess().reason)
    }

    @Test fun aGapInTheHeartbeatRestartsTheCoverage() {
        monitor(10 * min); twoCalm(); assertEquals(DrivingState.NOT_DRIVING, state())
        tick(90_000); tr.heartbeat(false); twoCalm()
        assertEquals(DrivingState.UNKNOWN, state())
        assertTrue(tr.assess().reason.contains("only just started"))
    }

    @Test fun noHeartbeatMeansTrackingIsNotRunning() {
        monitor(10 * min); twoCalm()
        tick(61_000); twoCalm()
        assertEquals("Unknown: motion tracking is not running: suggestions are paused", tr.assess().reason)
    }

    @Test fun nonFiniteSpeedsAreIgnored() { tr.speed(Float.NaN); tr.speed(-1f); tr.speed(Float.POSITIVE_INFINITY); assertEquals(DrivingState.UNKNOWN, state()) }

    // Codex v3 r3 P1-3: only the monotonic clock counts
    @Test fun aWallClockJumpForwardCannotShortenVehicleProtection() {
        monitor(10 * min); tr.vehicle(); monitor(9 * min + 40_000); twoCalm()
        wall += 30_000 // a 30 s forward wall jump: the tracker has no access to it
        assertEquals("9m40s of real time: still driving", DrivingState.DRIVING, state())
        monitor(10_000); twoCalm(); assertEquals("9m50s", DrivingState.DRIVING, state())
        monitor(10_000); twoCalm(); assertEquals("exactly 10 real minutes", DrivingState.DRIVING, state())
        monitor(10_000); twoCalm(); assertEquals("past 10 real minutes", DrivingState.NOT_DRIVING, state())
    }

    @Test fun aWallClockJumpBackCannotExtendOrBreakTheWindows() {
        monitor(10 * min); twoCalm(); wall -= 3_600_000
        assertEquals(DrivingState.NOT_DRIVING, state())
        tick(10_000); assertEquals(DrivingState.NOT_DRIVING, state()); tr.heartbeat(false)
    }

    @Test fun aMonotonicClockThatMovedBackRestartsTheCoverage() {
        monitor(10 * min); twoCalm(); t -= 5_000
        tr.heartbeat(false); twoCalm()
        assertEquals(DrivingState.UNKNOWN, state())
    }

    // Codex v3 r3 P1-2
    @Test fun theVersionMovesOnEveryUpdate() {
        var v = tr.version
        fun bumped(f: () -> Unit) { f(); assertTrue(tr.version > v); v = tr.version }
        bumped { tr.heartbeat(false) }; bumped { tr.vehicle() }; bumped { tr.calm(CalmSource.WATCH, Calm.WALKING) }; bumped { tr.speed(1f) }; bumped { tr.speed(5f) }; bumped { tr.speed(Float.NaN) }
        tr.assess(); assertEquals("a read changes nothing", v, tr.version)
    }

    @Test fun theCommitFenceWritesOnlyForTheSameVersionAndNotDriving() {
        monitor(10 * min); twoCalm()
        var wrote = 0
        assertTrue(tr.commitIfNotDriving(tr.version) { wrote++ }); assertEquals(1, wrote)
        assertFalse("an old version", tr.commitIfNotDriving(tr.version - 1) { wrote++ })
        val v = tr.version; tr.heartbeat(false)
        assertFalse("any update after the read", tr.commitIfNotDriving(v) { wrote++ })
        val v2 = tr.version; tr.vehicle()
        assertFalse(tr.commitIfNotDriving(tr.version) { wrote++ }); assertFalse(tr.commitIfNotDriving(v2) { wrote++ })
        assertEquals(1, wrote)
    }

    @Test fun theProcessWideReadFailsClosedOnAnException() {
        val prior = DrivingSignal.tracker
        try {
            DrivingSignal.tracker = DrivingTracker { throw IllegalStateException("clock") }
            assertEquals(DrivingState.UNKNOWN, DrivingSignal.assess().state); assertTrue(DrivingSignal.blocks())
            assertFalse(DrivingSignal.commitIfNotDriving(DrivingSignal.version()) { throw AssertionError("must not write") })
        } finally { DrivingSignal.tracker = prior }
    }
}
