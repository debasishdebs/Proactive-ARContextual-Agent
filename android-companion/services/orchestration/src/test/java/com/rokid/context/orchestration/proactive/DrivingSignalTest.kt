package com.rokid.context.orchestration.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingSignalTest {
    private var t = 10_000_000_000L
    private val tr = DrivingTracker { t }
    private val min = 60_000L

    /** Hub observed continuously for [ms], a heartbeat every 10 s. */
    private fun monitor(ms: Long, suspected: Boolean = false) { var n = 0L; while (n < ms) { tr.heartbeat(suspected); t += 10_000; n += 10_000 } }
    private fun state() = tr.assess().state

    @Test fun processStartWithNoDataIsUnknownAndBlocks() {
        val a = tr.assess()
        assertEquals(DrivingState.UNKNOWN, a.state); assertTrue(a.blocks)
        assertEquals("Unknown: no recent motion data: suggestions are paused", a.reason)
    }

    @Test fun aFreshWalkingReadingAloneIsUnknownUntilTenMinutesAreObserved() {
        monitor(2 * min); tr.calm(t, Calm.WALKING)
        assertEquals(DrivingState.UNKNOWN, state()); assertTrue(tr.assess().reason.contains("only just started"))
    }

    @Test fun aRunOfWalkingReadingsReportsItsLength() {
        monitor(10 * min)
        repeat(3) { tr.calm(t, Calm.WALKING); monitor(60_000); tr.heartbeat(false) }
        tr.calm(t, Calm.WALKING)
        val a = tr.assess()
        assertEquals(DrivingState.NOT_DRIVING, a.state); assertFalse(a.blocks)
        assertEquals("Not driving: walking for 3 min", a.reason)
    }

    @Test fun stillAndSlowSpeedAreCalmToo() {
        monitor(10 * min); tr.calm(t, Calm.STILL)
        assertEquals(DrivingState.NOT_DRIVING, state()); assertTrue(tr.assess().reason.startsWith("Not driving: still"))
        monitor(3 * min); tr.speed(t, 1.0f)
        assertEquals("Not driving: moving slowly for 1 min", tr.assess().reason)
    }

    @Test fun aMidSpeedSaysNothing() { monitor(10 * min); tr.speed(t, 4f); assertEquals(DrivingState.UNKNOWN, state()) }

    @Test fun staleCalmDataIsUnknown() {
        monitor(10 * min); tr.calm(t, Calm.WALKING)
        monitor(2 * min); assertEquals("2 min old is still fresh", DrivingState.NOT_DRIVING, state())
        monitor(10_000); assertEquals(DrivingState.UNKNOWN, state())
    }

    @Test fun aFutureStampedReadingCountsAsNow() { monitor(10 * min); tr.calm(t + 5 * min, Calm.WALKING); assertEquals(DrivingState.NOT_DRIVING, state()) }

    @Test fun fastSpeedIsDrivingAtAndAboveSevenMetresPerSecond() {
        monitor(10 * min); tr.speed(t, 6.9f); assertEquals(DrivingState.UNKNOWN, state())
        tr.speed(t, 7f); assertEquals(DrivingState.DRIVING, state()); assertTrue(tr.assess().blocks)
    }

    @Test fun conflictingSignalsTheVehicleWins() {
        monitor(10 * min); tr.calm(t, Calm.WALKING); tr.vehicle(t)
        assertEquals(DrivingState.DRIVING, state())
        tr.calm(t, Calm.STILL); assertEquals(DrivingState.DRIVING, state())
    }

    @Test fun theTenMinuteBoundary() {
        monitor(10 * min); tr.vehicle(t - 10 * min); tr.calm(t, Calm.WALKING)
        assertEquals("exactly 10 min is still driving", DrivingState.DRIVING, state())
        t += 1; tr.heartbeat(false); tr.calm(t, Calm.WALKING)
        assertEquals("just over 10 min: the walking reading rules", DrivingState.NOT_DRIVING, state())
    }

    @Test fun aSuspectedVehicleOnTheHeartbeatIsDrivingNowAndFor10MinutesAfter() {
        monitor(30_000, suspected = true)
        assertEquals("Driving: a vehicle is suspected now: suggestions are paused", tr.assess().reason)
        t += 5 * min
        assertEquals("Driving: a vehicle was detected 5 min ago: suggestions are paused", tr.assess().reason)
    }

    @Test fun aGapInTheHeartbeatRestartsTheCoverage() {
        monitor(10 * min); tr.calm(t, Calm.WALKING); assertEquals(DrivingState.NOT_DRIVING, state())
        t += 90_000; tr.heartbeat(false); tr.calm(t, Calm.WALKING)
        assertEquals(DrivingState.UNKNOWN, state())
        assertTrue(tr.assess().reason.contains("only just started"))
    }

    @Test fun noHeartbeatMeansTrackingIsNotRunning() {
        monitor(10 * min); tr.calm(t, Calm.WALKING)
        t += 61_000; tr.calm(t, Calm.WALKING)
        assertEquals("Unknown: motion tracking is not running: suggestions are paused", tr.assess().reason)
    }

    @Test fun nonFiniteSpeedsAreIgnored() { tr.speed(t, Float.NaN); tr.speed(t, -1f); tr.speed(t, Float.POSITIVE_INFINITY); assertEquals(DrivingState.UNKNOWN, state()) }

    @Test fun theProcessWideReadFailsClosedOnAnException() {
        val prior = DrivingSignal.tracker
        try {
            DrivingSignal.tracker = DrivingTracker { throw IllegalStateException("clock") }
            assertEquals(DrivingState.UNKNOWN, DrivingSignal.assess().state); assertTrue(DrivingSignal.blocks())
        } finally { DrivingSignal.tracker = prior }
    }
}
