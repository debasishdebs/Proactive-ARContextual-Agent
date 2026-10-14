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
}
