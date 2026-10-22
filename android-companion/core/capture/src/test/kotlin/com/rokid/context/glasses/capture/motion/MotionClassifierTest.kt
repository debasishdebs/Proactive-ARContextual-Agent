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
    private val c = MotionClassifier({ now }, GlanceParams.LEGACY)
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
            val k = MotionClassifier({ 0L }, GlanceParams.LEGACY)
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
            val k = MotionClassifier({ 0L }, GlanceParams.LEGACY)
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
            val k = MotionClassifier({ 0L }, GlanceParams.LEGACY)
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

    // ---- battery wave A3: glance model, head pose, per-second features ----

    private fun defaults() = MotionClassifier({ now }).also { k ->
        k.dwellListener = { dwells += it }
    }

    private fun gyroOn(k: MotionClassifier, from: Long, ms: Long, mag: Float, z: Float = 0f) {
        var t = from
        while (t < from + ms) { now = t; if (z != 0f) k.onGyro(0f, 0f, z, t) else k.onGyro(mag, 0f, 0f, t); t += 20 }
    }

    @Test fun defaultsIgnoreMicroMotion() {
        val k = defaults()
        for (i in 0 until 10) { gyroOn(k, i * 2000L, 60, 1.5f); gyroOn(k, i * 2000L + 60, 1940, 0f) }
        gyroOn(k, 20_000, 1000, 0f)
        assertTrue(dwells.isEmpty(), "dwells=$dwells")
        assertEquals(10, k.takeGlanceCounts().micro)
        assertEquals(GlanceCounts(0, 0), k.takeGlanceCounts())
    }

    @Test fun defaultsDwellAfterRealTurnAndOneAndAHalfSeconds() {
        val k = defaults()
        gyroOn(k, 0, 400, 1.5f) // last above sample at 380
        gyroOn(k, 400, 4000, 0f)
        assertEquals(listOf(380L + 1_500), dwells)
        assertEquals(GlanceCounts(1, 0), k.takeGlanceCounts())
    }

    @Test fun poseIntegratesNetTurn() {
        val k = defaults()
        gyroOn(k, 0, 500, 0f, z = 1f)
        assertEquals(0.5f, k.rotationSinceMarkRad(), 0.03f)
        gyroOn(k, 500, 500, 0f, z = -1f)
        assertEquals(0f, k.rotationSinceMarkRad(), 0.05f)
        gyroOn(k, 1000, 500, 0f, z = 1f)
        k.markPose()
        assertEquals(0f, k.rotationSinceMarkRad(), 1e-6f)
    }

    @Test fun deadbandSkipsBias() {
        val k = defaults()
        gyroOn(k, 0, 60_000, 0.03f)
        assertTrue(k.rotationSinceMarkRad() < 0.01f)
    }

    @Test fun walkedSinceMarkAfterWalking() {
        val k = defaults()
        assertFalse(k.walkedSinceMark())
        var t = 0L
        while (t < 2000) { k.onLinearAccel(0f, 2.0f * sin(2 * PI * 2.0 * t / 1000.0).toFloat(), 0f, t); t += 20 }
        assertEquals(MotionState.WALKING, k.state.value)
        assertTrue(k.walkedSinceMark())
        k.markPose()
        assertFalse(k.walkedSinceMark())
    }

    @Test fun featuresCarryPerSecondStats() {
        val k = defaults()
        gyroOn(k, 0, 1000, 1.0f)
        val f = k.features()
        assertEquals(1.0f, f.gyroMean, 0.01f)
        assertEquals(1.0f, f.gyroRms, 0.01f)
        assertEquals(1.0f, f.aboveOnFrac, 1e-6f)
        assertEquals(1.0f, f.rotRad, 0.05f)
        val g = k.features()
        assertEquals(0f, g.gyroMean); assertEquals(0f, g.aboveOnFrac); assertEquals(0f, g.rotRad)
        assertEquals(0, g.glances); assertEquals(0, g.micro)
    }

    @Test fun defaultsAtBatchedStillRateAreOrderIndependent() {
        for (first in listOf(true, false)) {
            val k = MotionClassifier({ 0L }); val d = mutableListOf<Long>()
            k.dwellListener = { d += it }
            var batch = 0L
            while (batch < 5000) {
                val ts = (batch until batch + 500 step 50).toList()
                val g = { for (t in ts) k.onGyro(if (t in 2000..2350) 2f else 0f, 0f, 0f, t) }
                val a = { for (t in ts) k.onLinearAccel(0f, 0f, 0f, t) }
                if (first) { g(); a() } else { a(); g() }
                batch += 500
            }
            assertEquals(listOf(3850L), d, "gyroFirst=$first")
        }
    }

    // ---- battery wave A3 task 3: default-params classifier paths ----

    private fun accelWalk(k: MotionClassifier, t: Long) = k.onLinearAccel(0f, 2.0f * sin(2 * PI * 2.0 * t / 1000.0).toFloat(), 0f, t)

    @Test fun defaultsWalkEndThenStillIsADwell() {
        val k = defaults()
        // 50 Hz gyro (quiet) and accel (a 2 Hz stride for 3 s, then none) on one clock, gyro first within each tick
        var t = 0L
        while (t < 9_000) {
            now = t
            k.onGyro(0f, 0f, 0f, t)
            if (t < 3_000) accelWalk(k, t) else k.onLinearAccel(0f, 0f, 0f, t)
            t += 20
        }
        assertEquals(1, dwells.size, "dwells=$dwells")
        assertTrue(dwells[0] in 4_000..7_500, "arrived-glance after the walk, was ${dwells[0]}")
        assertTrue(k.walkedSinceMark())
    }

    @Test fun defaultsFullRateGyroMixedWithAccelDoesNotChangeTheGlance() {
        val k = defaults()
        var t = 0L
        while (t < 6_000) {
            now = t
            k.onGyro(if (t in 1_000 until 1_400) 1.5f else 0f, 0f, 0f, t)
            k.onLinearAccel(0f, 0f, 0f, t)
            t += 20
        }
        assertEquals(listOf(1_380L + 1_500), dwells)
        assertEquals(GlanceCounts(1, 0), k.takeGlanceCounts())
    }

    @Test fun defaultsAMicroBoutPostponesAPendingGlance() {
        val k = defaults()
        gyroOn(k, 0, 400, 1.5f)       // real bout, last above 380
        gyroOn(k, 400, 800, 0f)
        gyroOn(k, 1_200, 60, 1.5f)    // micro jerk, last above 1 240 (< 1 500 since the bout: still pending)
        gyroOn(k, 1_260, 4_000, 0f)
        assertEquals(listOf(1_240L + 1_500), dwells, "dwells=$dwells")
        assertEquals(GlanceCounts(1, 1), k.takeGlanceCounts())
    }

    @Test fun aRealLookAt20HzCountsAsABout() {
        for (lookMs in listOf(300L, 349L)) {
            dwells.clear()
            val k = defaults()
            var t = 0L
            while (t < 8_000) { now = t; k.onGyro(if (t in 1_000 until 1_000 + lookMs) 1.2f else 0f, 0f, 0f, t); t += 50 }
            assertEquals(1, dwells.size, "look=$lookMs dwells=$dwells")
            assertEquals(GlanceCounts(1, 0), k.takeGlanceCounts())
        }
    }

    @Test fun biasAboveTheDeadbandCannotGrowThePoseWhileStill() {
        val k = defaults()
        var maxPose = 0f
        var t = 0L
        while (t < 120_000) { now = t; k.onGyro(0f, 0f, 0.07f, t); maxPose = maxOf(maxPose, k.rotationSinceMarkRad()); t += 20 }
        assertTrue(maxPose < 0.30f, "bias drift stays under the novelty threshold, max=$maxPose")
    }

    @Test fun aRealTurnSurvivesTheStillnessResetOfALaterBias() {
        val k = defaults()
        gyroOn(k, 0, 1_000, 0f, z = 1f)               // a 1 rad turn
        gyroOn(k, 1_000, 60_000, 0f, z = 0.07f)       // then bias only
        val r = k.rotationSinceMarkRad()
        assertTrue(r in 0.95f..1.35f, "turn kept, drift bounded, was $r")
    }

    // ---- slice 3a Task 11: one swappable config ----

    @Test fun setConfigSwapsTheSettleTimeOfTheNextGlance() {
        val k = defaults()
        k.setConfig(GlanceParams(settleMs = 3_000))
        gyroOn(k, 0, 400, 1.5f)
        gyroOn(k, 400, 6000, 0f)
        assertEquals(listOf(380L + 3_000), dwells)
    }

    @Test fun setConfigOnsetAboveTheTurnMeansNoGlance() {
        val k = defaults()
        k.setConfig(GlanceParams(onsetRadS = 2.0f))
        gyroOn(k, 0, 400, 1.5f)
        gyroOn(k, 400, 6000, 0f)
        assertTrue(dwells.isEmpty(), "dwells=$dwells")
        assertEquals(GlanceCounts(0, 0), k.takeGlanceCounts())
        assertEquals(2.0f, k.config.onsetRadS)
    }

    @Test fun quietIsFalseInABoutAndWhileAGlanceIsPendingAndTrueAfterwards() {
        val k = defaults()
        assertTrue(k.quiet())
        gyroOn(k, 0, 400, 1.5f)
        assertFalse(k.quiet(), "mid bout")
        gyroOn(k, 400, 600, 0f)
        assertFalse(k.quiet(), "settling: a glance is pending")
        gyroOn(k, 1000, 3000, 0f)
        assertTrue(k.quiet())
    }

    @Test fun abortBoutDropsTheBoutAndItsGlance() {
        val k = defaults()
        gyroOn(k, 0, 400, 1.5f)
        assertFalse(k.quiet())
        k.abortBout()
        assertTrue(k.quiet())
        gyroOn(k, 400, 6000, 0f)
        assertTrue(dwells.isEmpty(), "dwells=$dwells")
    }
}
