package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionState
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionClassifierTest {
    private var now = 0L
    private val c = MotionClassifier { now }
    private val dwells = mutableListOf<Long>()
    private val onsets = mutableListOf<Long>()

    init {
        c.dwellListener = { dwells += it }
        c.onsetListener = { onsets += it }
    }

    /** 50 Hz gyro samples of constant magnitude [mag] (rad/s) for [ms], starting at [from]. */
    private fun gyro(from: Long, ms: Long, mag: Float) {
        var t = from
        while (t < from + ms) { now = t; c.onGyro(mag, 0f, 0f, t); t += 20 }
    }

    private fun walk(from: Long, ms: Long, hz: Double, amp: Float) {
        var t = from
        while (t < from + ms) {
            now = t
            c.onLinearAccel(0f, amp * sin(2 * PI * hz * t / 1000.0).toFloat(), 0f, t); t += 20
        }
    }

    @Test fun stillTrace() {
        gyro(0, 3000, 0.02f)
        assertEquals(MotionState.STILL, c.state.value)
        assertTrue(onsets.isEmpty() && dwells.isEmpty())
    }

    @Test fun headTurnTrace() {
        gyro(0, 500, 0.02f)
        gyro(500, 600, 1.5f)
        assertEquals(MotionState.HEAD_MOVING, c.state.value)
    }

    @Test fun walkingTraceAt2Hz() {
        walk(0, 2000, 2.0, 2.0f)
        assertEquals(MotionState.WALKING, c.state.value)
    }

    @Test fun slowSwayIsNotWalking() {
        walk(0, 2000, 0.5, 2.0f)
        assertEquals(MotionState.STILL, c.state.value)
    }

    @Test fun dwellAfterOneSecondStill() {
        gyro(0, 500, 1.5f)
        gyro(500, 900, 0.02f)
        assertTrue(dwells.isEmpty())
        gyro(1400, 300, 0.02f)
        assertEquals(1, dwells.size)
        gyro(1700, 2000, 0.02f)
        assertEquals(1, dwells.size)
    }

    @Test fun noDwellWithoutPriorMotion() {
        gyro(0, 5000, 0.02f)
        assertTrue(dwells.isEmpty())
    }

    @Test fun onsetEmitsOnce() {
        gyro(0, 500, 0.02f)
        gyro(500, 1500, 1.5f)
        assertEquals(1, onsets.size)
    }

    @Test fun onsetWithinOneSample() {
        gyro(0, 500, 0.02f)
        now = 500; c.onGyro(1.5f, 0f, 0f, 500)
        assertEquals(listOf(500L), onsets)
        assertEquals(MotionState.HEAD_MOVING, c.state.value)
    }

    @Test fun deceleratingTracksPeak() {
        gyro(0, 300, 0.02f)
        gyro(300, 300, 1.0f)
        assertFalse(c.decelerating.value)
        gyro(600, 100, 3.0f)
        assertFalse(c.decelerating.value)
        gyro(700, 40, 2.5f)
        assertFalse(c.decelerating.value)
        gyro(740, 40, 1.5f) // < 60 % of the 3.0 peak
        assertTrue(c.decelerating.value)
        gyro(780, 40, 3.5f)
        assertFalse(c.decelerating.value)
        gyro(2000, 1500, 0.02f)
        assertFalse(c.decelerating.value)
    }

    /** Walking accel at [hz] with a constant gyro magnitude [mag] under the head-turn threshold, 50 Hz each. */
    private fun walkWithGyro(from: Long, ms: Long, hz: Double, mag: Float) {
        var t = from
        while (t < from + ms) {
            now = t
            c.onLinearAccel(0f, 2.0f * sin(2 * PI * hz * t / 1000.0).toFloat(), 0f, t)
            c.onGyro(mag, 0f, 0f, t); t += 20
        }
    }

    @Test fun deceleratingStaysFalseInAWalk() {
        // a head turn at the start of a walk leaves a high peak behind, but a walk is not a head turn
        walkWithGyro(0, 600, 2.0, 0.1f)
        gyro(600, 100, 2.0f) // spike; accel keeps arriving below
        var t = 700L
        while (t < 8_000) {
            walkWithGyro(t, 20, 2.0, 0.2f)
            if (t >= 1_500) {
                assertEquals(MotionState.WALKING, c.state.value, "t=$t")
                assertFalse(c.decelerating.value, "t=$t")
            }
            t += 20
        }
    }

    @Test fun deceleratingGoesTrueAtTheEndOfAHeadTurn() {
        gyro(0, 500, 0.02f)
        gyro(500, 400, 3.0f)
        assertFalse(c.decelerating.value)
        gyro(900, 20, 0.5f) // under the threshold, under 60 % of the peak, still inside the hold
        assertEquals(MotionState.HEAD_MOVING, c.state.value)
        assertTrue(c.decelerating.value)
        gyro(920, 400, 0.02f) // hold over, STILL again
        assertEquals(MotionState.STILL, c.state.value)
        assertFalse(c.decelerating.value)
    }

    @Test fun onsetSampleSeedsThePeak() {
        gyro(0, 500, 0.02f)
        now = 500; c.onGyro(2.0f, 0f, 0f, 500) // a one-sample jerk
        assertEquals(listOf(500L), onsets)
        now = 520; c.onGyro(0.3f, 0f, 0f, 520)
        assertEquals(MotionState.HEAD_MOVING, c.state.value)
        assertTrue(c.decelerating.value)
    }

    // ---- fix wave K3: provisional relaxed walking rule and the per-second feature line ----

    @Test fun softerStrideNeedsTwoSecondsOfSustainedPeriodicity() {
        walk(0, 1500, 1.4, 0.9f) // variance 0.405: under the 0.5 strict rule, over the relaxed 0.25
        assertEquals(MotionState.STILL, c.state.value)
        walk(1500, 2500, 1.4, 0.9f)
        assertEquals(MotionState.WALKING, c.state.value)
    }

    @Test fun relaxedRuleStillWantsAStepFrequencyAndEnoughVariance() {
        walk(0, 5000, 0.5, 2.0f) // a slow sway
        assertEquals(MotionState.STILL, c.state.value)
        walk(5000, 5000, 1.4, 0.4f) // variance 0.08
        assertEquals(MotionState.STILL, c.state.value)
        walk(10_000, 5000, 3.5, 1.0f) // too fast for steps
        assertEquals(MotionState.STILL, c.state.value)
    }

    @Test fun featureSnapshotReportsGyroPeakVarianceFrequencyAndResetsThePeak() {
        gyro(0, 400, 0.9f)
        walk(400, 2000, 2.0, 2.0f)
        val f = c.features()
        assertEquals(0.9f, f.gyroMax, 0.01f)
        assertEquals(2.0f, f.accVariance, 0.2f)
        assertEquals(2.0, f.stepHz, 0.3)
        assertEquals(MotionState.WALKING, f.state)
        assertEquals(0f, c.features().gyroMax)
    }

    @Test fun featureLoggerWritesOneCountOnlyLinePerTick() {
        walk(0, 2000, 2.0, 2.0f)
        val lines = mutableListOf<String>()
        val l = FeatureLogger(c, { lines += it }, verbose = true)
        l.tick(); l.tick()
        assertEquals(2, lines.size)
        assertTrue(Regex("""features gyroMax=\d+\.\d+ accVar=\d+\.\d+ stepHz=\d+\.\d+ state=WALKING""").matches(lines[0]), lines[0])
    }

    // ---- battery wave A2: STILL IMU rate delivers 20 Hz in 500 ms batches ----

    /**
     * 20 Hz traces (50 ms step) delivered the way the batched sensor FIFO does: per 500 ms batch, all gyro samples then
     * all accel samples, or the reverse (there is no cross-sensor order). A single shared newest-timestamp clock would
     * give a false second onset (gyro-first) or lose a short glance (accel-first); the classifier keeps one clock per
     * sensor, so both orders give the same onset and dwell times.
     */
    @Test fun batchedHeadTurnEmitsOneOnsetAndAnExactDwell() {
        for (first in listOf(true, false)) {
            onsets.clear(); dwells.clear()
            val k = MotionClassifier { 0L }
            k.onsetListener = { onsets += it }; k.dwellListener = { dwells += it }
            var batch = 0L
            while (batch < 4000) {
                val ts = (batch until batch + 500 step 50).toList()
                val g = { for (t in ts) k.onGyro(if (t in 2000..2350) 2f else 0f, 0f, 0f, t) }
                val a = { for (t in ts) k.onLinearAccel(0f, 0f, 0f, t) }
                if (first) { g(); a() } else { a(); g() }
                batch += 500
            }
            assertEquals(listOf(2000L), onsets, "gyroFirst=$first")
            assertEquals(listOf(3350L), dwells, "gyroFirst=$first")
            assertEquals(MotionState.STILL, k.state.value, "gyroFirst=$first")
        }
    }

    @Test fun batchedShortGlanceIsDetectedInEitherOrder() {
        for (first in listOf(true, false)) {
            onsets.clear(); dwells.clear()
            val k = MotionClassifier { 0L }
            k.onsetListener = { onsets += it }; k.dwellListener = { dwells += it }
            var batch = 0L
            while (batch < 4000) {
                val ts = (batch until batch + 500 step 50).toList()
                val g = { for (t in ts) k.onGyro(if (t in 2000..2100) 2f else 0f, 0f, 0f, t) } // 150 ms glance
                val a = { for (t in ts) k.onLinearAccel(0f, 0f, 0f, t) }
                if (first) { g(); a() } else { a(); g() }
                batch += 500
            }
            assertEquals(listOf(2000L), onsets, "gyroFirst=$first")
            assertEquals(1, dwells.size, "gyroFirst=$first")
        }
    }

    @Test fun walkingDetectedAt20HzInBatchesInEitherOrder() {
        for (first in listOf(true, false)) {
            val k = MotionClassifier { 0L }
            var detectedAt = -1L
            var batch = 0L
            while (batch < 4000 && detectedAt < 0) {
                val ts = (batch until batch + 500 step 50).toList()
                val g = { for (t in ts) k.onGyro(0f, 0f, 0f, t) }
                val a = { for (t in ts) { k.onLinearAccel(0f, 2.0f * sin(2 * PI * 2.0 * t / 1000.0).toFloat(), 0f, t); if (detectedAt < 0 && k.state.value == MotionState.WALKING) detectedAt = t } }
                if (first) { g(); a() } else { a(); g() }
                batch += 500
            }
            assertTrue(detectedAt in 0..3000, "gyroFirst=$first walking detected at $detectedAt")
        }
    }

    @Test fun walkingDetectedAt20Hz() {
        var t = 0L
        var detectedAt = -1L
        while (t < 4000 && detectedAt < 0) {
            now = t
            c.onLinearAccel(0f, 2.0f * sin(2 * PI * 2.0 * t / 1000.0).toFloat(), 0f, t)
            if (c.state.value == MotionState.WALKING) detectedAt = t
            t += 50
        }
        assertTrue(detectedAt in 0..3000, "walking detected at $detectedAt")
    }
}
