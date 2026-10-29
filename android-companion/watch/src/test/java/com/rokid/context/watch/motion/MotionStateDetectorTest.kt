package com.rokid.context.watch.motion

// Provenance: ported from live-fitness-tracker watch/.../motion/MotionStateDetectorTest.kt.

import com.rokid.context.core.model.context.WatchState
import com.rokid.context.core.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MotionStateDetectorTest {
    private val D = MotionStateDetector

    private fun step(m: MotionMemory, e: MotionEvent, now: Long) = D.next(m, e, now)

    private fun steps(count: Long, start: Long, end: Long) = MotionEvent.Steps(count, start, end)

    @Test fun walkingFromStepsThenChangesOnly() {
        val s1 = step(MotionMemory(), steps(100, 0, 60_000), 60_000)
        assertEquals(MotionReport(state = WatchState.WALKING, confidence = 0.8f, changedAtMs = 0), s1.report)
        val s2 = step(s1.memory, steps(100, 60_000, 120_000), 120_000)
        assertNull(s2.report)
    }

    @Test fun runningAndStillFromCadence() {
        assertEquals(WatchState.RUNNING, step(MotionMemory(), steps(150, 0, 60_000), 60_000).report?.state)
        val still = step(MotionMemory(), steps(30, 0, 60_000), 60_000).report
        assertEquals(WatchState.STILL, still?.state)
        assertEquals(0.6f, still?.confidence)
    }

    @Test fun shortHoldProducesNoReport() {
        assertNull(step(MotionMemory(), steps(10, 0, 6_000), 6_000).report)
    }

    @Test fun shortPointsAreSummedUntilFiveSeconds() {
        val a = step(MotionMemory(), steps(5, 0, 3_000), 3_000)
        assertNull(a.report)
        // 5+5 steps over 6 s (0..6000) = 100/min, but only reported once held 30 s
        val b = step(a.memory, steps(5, 3_000, 6_000), 6_000)
        assertEquals(6_000L, b.memory.lastStepEndMs)
        assertEquals(100.0, b.memory.cadence, 0.001)
        // many short points spanning a minute become WALKING
        var m = MotionMemory(); var rep: MotionReport? = null
        for (i in 0 until 20) { val r = step(m, steps(5, i * 3_000L, (i + 1) * 3_000L), (i + 1) * 3_000L); m = r.memory; r.report?.let { rep = it } }
        assertEquals(WatchState.WALKING, rep?.state)
    }

    @Test fun stepsNeverMoveLastStepEndBackwards() {
        val a = step(MotionMemory(), steps(100, 100_000, 160_000), 160_000)
        val b = step(a.memory, steps(100, 0, 60_000), 160_000)
        assertEquals(160_000L, b.memory.lastStepEndMs)
    }

    @Test fun cadenceStateAfterOldActivityStillNeedsHold() {
        var m = step(MotionMemory(), MotionEvent.Activity(UserActivity.EXERCISE, ExerciseKind.BIKE, 0), 100_000).memory
        m = m.copy(cadence = 100.0, lastStepEndMs = 190_000)
        val r = step(m, MotionEvent.Activity(UserActivity.PASSIVE, null, 50_000), 200_000)
        assertNull(r.report) // the old changedAtMs must not skip the 30 s hold of a cadence-derived state
        assertEquals(200_000L, r.memory.candidateSinceMs)
    }

    @Test fun foldEvaluatesTickAtNowAfterStepsBatch() {
        // a delayed batch for a walk that ended minutes ago must not leave WALKING as the last word
        val r = D.fold(MotionMemory(), listOf(steps(100, 0, 60_000)), 600_000)
        assertEquals(WatchState.STILL, r.report?.state)
        assertEquals(180_000L, r.report?.changedAtMs)
    }

    @Test fun foldKeepsOnlyLastReport() {
        val r = D.fold(MotionMemory(), listOf(steps(150, 0, 60_000), steps(100, 60_000, 120_000)), 125_000)
        assertEquals(MotionReport(state = WatchState.WALKING, confidence = 0.8f, changedAtMs = 60_000), r.report)
    }

    @Test fun lateShortBurstIsNotHeldByTheAppendedTick() {
        val r = D.fold(MotionMemory(), listOf(steps(10, 0, 6_000)), 40_000)
        assertNull(r.report)
    }

    @Test fun redeliveredShortPointIsNotCountedTwice() {
        var m = step(MotionMemory(), steps(5, 0, 3_000), 3_000).memory
        m = step(m, steps(5, 0, 3_000), 3_000).memory
        m = step(m, steps(5, 3_000, 6_000), 6_000).memory
        assertEquals(100.0, m.cadence, 0.001)
    }

    @Test fun leavingExerciseWithStaleStepsReportsStillWithoutWaitingForATick() {
        var m = step(MotionMemory(), steps(100, 0, 60_000), 60_000).memory
        val cyc = step(m, MotionEvent.Activity(UserActivity.EXERCISE, ExerciseKind.BIKE, 70_000), 100_000)
        assertEquals(WatchState.CYCLING, cyc.report?.state)
        // ride ends long after the last steps
        val end = step(cyc.memory, MotionEvent.Activity(UserActivity.PASSIVE, null, 1_000_000), 1_000_000)
        assertNull(end.report) // changedAt = now: needs the hold
        assertEquals(WatchState.STILL, end.memory.candidate)
        assertEquals(1_000_000L, end.memory.candidateSinceMs)
        assertEquals(1_030_000L, MotionStateDetector.holdDueAtMs(end.memory))
        val t = step(end.memory, MotionEvent.Tick, 1_030_000)
        assertEquals(WatchState.STILL, t.report?.state)
    }

    @Test fun noHoldDueWhenCandidateIsReported() {
        val r = step(MotionMemory(), steps(100, 0, 60_000), 60_000)
        assertNull(MotionStateDetector.holdDueAtMs(r.memory))
    }

    @Test fun holdDueWhenCandidateDiffersFromReported() {
        val r = step(MotionMemory(), steps(10, 0, 6_000), 6_000)
        assertEquals(30_000L, MotionStateDetector.holdDueAtMs(r.memory))
    }

    @Test fun asleep() {
        val r = step(MotionMemory(), MotionEvent.Activity(UserActivity.ASLEEP, null, 0), 60_000).report
        assertEquals(MotionReport(state = WatchState.ASLEEP, confidence = 0.9f, changedAtMs = 0), r)
    }

    @Test fun exerciseKinds() {
        fun st(k: ExerciseKind?) = step(MotionMemory(), MotionEvent.Activity(UserActivity.EXERCISE, k, 0), 60_000).report
        assertEquals(MotionReport(state = WatchState.CYCLING, confidence = 0.9f, changedAtMs = 0), st(ExerciseKind.BIKE))
        assertEquals(WatchState.RUNNING, st(ExerciseKind.RUN)?.state)
        assertEquals(WatchState.WALKING, st(ExerciseKind.WALK)?.state)
        assertEquals(MotionReport(state = WatchState.EXERCISE, confidence = 0.8f, changedAtMs = 0), st(ExerciseKind.OTHER))
    }

    @Test fun exerciseEndsFallsBackToCadence() {
        var m = step(MotionMemory(), steps(100, 0, 60_000), 60_000).memory
        val cyc = step(m, MotionEvent.Activity(UserActivity.EXERCISE, ExerciseKind.BIKE, 70_000), 100_000)
        assertEquals(WatchState.CYCLING, cyc.report?.state)
        val end = step(cyc.memory, MotionEvent.Activity(UserActivity.PASSIVE, null, 110_000), 110_000)
        assertNull(end.report)
        val held = step(end.memory, MotionEvent.Tick, 140_000)
        assertEquals(MotionReport(state = WatchState.WALKING, confidence = 0.8f, changedAtMs = 110_000), held.report)
    }

    @Test fun staleStepsBecomeStill() {
        val first = step(MotionMemory(), steps(100, 0, 60_000), 60_000)
        assertEquals(WatchState.WALKING, first.report?.state)
        val t = step(first.memory, MotionEvent.Tick, 60_000 + 180_000)
        assertEquals(MotionReport(state = WatchState.STILL, confidence = 0.6f, changedAtMs = 180_000), t.report)
    }

    @Test fun refreshAfterTenMinutes() {
        val first = step(MotionMemory(), steps(100, 0, 60_000), 60_000)
        val m = D.markSent(first.memory, 100_000)
        // keep steps fresh so state stays WALKING
        val m2 = m.copy(lastStepEndMs = 100_000 + 599_999)
        assertNull(step(m2, MotionEvent.Tick, 100_000 + 599_999).report)
        val m3 = m.copy(lastStepEndMs = 100_000 + 600_000)
        val r = step(m3, MotionEvent.Tick, 100_000 + 600_000).report
        assertEquals(MotionReport(state = WatchState.WALKING, confidence = 0.8f, changedAtMs = 0), r)
    }

    @Test fun unsentIsRetriedOnTick() {
        val first = step(MotionMemory(), steps(100, 0, 60_000), 60_000)
        assertNotNull(first.report)
        val t = step(first.memory, MotionEvent.Tick, 61_000)
        assertEquals(first.report, t.report)
    }

    @Test fun noStepsNeverReportsUnknown() {
        assertNull(step(MotionMemory(), MotionEvent.Tick, 1_000_000).report)
    }

    @Test fun memoryRoundTrips() {
        val m = step(MotionMemory(), steps(100, 0, 60_000), 60_000).memory
        assertEquals(m, Wire.json.decodeFromString<MotionMemory>(Wire.encode(m)))
    }
}
