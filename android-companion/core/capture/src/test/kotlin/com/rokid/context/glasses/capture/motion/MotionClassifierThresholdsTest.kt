package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.signals.ClassifierThresholds
import com.rokid.context.glasses.capture.motion.model.HeuristicMotionModel
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** v0.9.7 A: the classifier reads its thresholds from the config in force; the default config is the shipped behaviour. */
class MotionClassifierThresholdsTest {
    private var now = 0L
    private fun classifier(t: ClassifierThresholds? = null) = MotionClassifier({ now }, GlanceParams.LEGACY).also { c -> t?.let(c::setThresholds) }

    private fun walk(c: MotionClassifier, from: Long, ms: Long, hz: Double, amp: Float) {
        var t = from
        while (t < from + ms) { now = t; c.onLinearAccel(0f, amp * sin(2 * PI * hz * t / 1000.0).toFloat(), 0f, t); t += 20 }
    }

    private fun gyro(c: MotionClassifier, from: Long, ms: Long, mag: Float) {
        var t = from
        while (t < from + ms) { now = t; c.onGyro(mag, 0f, 0f, t); t += 20 }
    }

    @Test fun aSoftStrideWalksAfterTheDefaultHold() {
        val c = classifier()
        walk(c, 0, 3000, 2.0, 1.0f) // variance 0.5: not strict (needs more), relaxed yes
        assertEquals(MotionState.WALKING, c.state.value)
    }

    @Test fun aLongerHoldDelaysWalkingUntilItIsMet() {
        val c = classifier(ClassifierThresholds.from(mapOf("relaxedHoldMs" to 5000.0)))
        walk(c, 0, 3000, 2.0, 1.0f)
        assertEquals(MotionState.STILL, c.state.value)
        walk(c, 3000, 4000, 2.0, 1.0f)
        assertEquals(MotionState.WALKING, c.state.value)
    }

    @Test fun aHigherRelaxedVarianceRefusesTheSoftStride() {
        val c = classifier(ClassifierThresholds.from(mapOf("relaxedVariance" to 0.6)))
        walk(c, 0, 6000, 2.0, 1.0f) // variance 0.5 < 0.6
        assertEquals(MotionState.STILL, c.state.value)
    }

    @Test fun theHeadMovingThresholdMovesTheHeadState() {
        val d = classifier(); gyro(d, 0, 600, 0.7f)
        assertEquals(MotionState.HEAD_MOVING, d.state.value, "0.7 rad/s is over the default 0.6")
        val c = classifier(ClassifierThresholds.from(mapOf("headMovingRadS" to 0.9)))
        gyro(c, 0, 600, 0.7f)
        assertEquals(MotionState.STILL, c.state.value)
    }

    @Test fun theShadowHeuristicReadsItsCutoffsAndDefaultsAreUnchanged() {
        val f = SecondFeatures(0f, 0f, 0f, 0f, 0.3f, 2.0f, 0f)
        val m = HeuristicMotionModel()
        assertEquals(MotionState.WALKING, m.classify(f))
        m.thresholds = ClassifierThresholds.from(mapOf("heurAccVar" to 0.5))
        assertEquals(MotionState.STILL, m.classify(f))
    }

    @Test fun theDefaultConfigClassifiesLikeNoConfig() {
        val a = classifier(); val b = classifier(ClassifierThresholds.from(emptyMap()))
        for (hz in listOf(0.5, 1.4, 2.0, 3.5)) { walk(a, 0, 4000, hz, 1.2f); walk(b, 0, 4000, hz, 1.2f); assertEquals(a.state.value, b.state.value, "hz=$hz") }
    }
}
