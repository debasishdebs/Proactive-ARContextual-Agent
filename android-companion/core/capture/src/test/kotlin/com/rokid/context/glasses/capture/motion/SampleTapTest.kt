package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tap hands every classifier input sample to a second consumer (SHADOW). It must never change the classifier. */
class SampleTapTest {
    private class Rec : SampleTap {
        var gyro = 0; var accel = 0
        val states = ArrayList<MotionState>()
        override fun onGyro(x: Float, y: Float, z: Float, tMs: Long, state: MotionState) { gyro++; states += state }
        override fun onAccel(x: Float, y: Float, z: Float, tMs: Long, gyroNewestMs: Long, state: MotionState) { accel++ }
    }

    private fun feed(c: MotionClassifier, n: Int) {
        for (i in 0 until n) { c.onGyro(0.01f, 0f, 0f, i * 20L); c.onLinearAccel(0f, 0.01f, 0f, i * 20L) }
    }

    @Test fun everyInputSampleReachesTheTap() {
        val c = MotionClassifier({ 0L })
        val t = Rec(); c.addTap(t)
        feed(c, 50)
        assertEquals(50, t.gyro); assertEquals(50, t.accel)
        assertTrue(t.states.all { it == MotionState.STILL })
    }

    @Test fun aRemovedTapSeesNothingMore() {
        val c = MotionClassifier({ 0L })
        val t = Rec(); c.addTap(t); feed(c, 5); c.removeTap(t); feed(c, 5)
        assertEquals(5, t.gyro)
    }

    @Test fun aThrowingTapNeverChangesTheClassifier() {
        val plain = MotionClassifier({ 0L }); val tapped = MotionClassifier({ 0L })
        tapped.addTap(object : SampleTap {
            override fun onGyro(x: Float, y: Float, z: Float, tMs: Long, state: MotionState) = throw IllegalStateException("boom")
            override fun onAccel(x: Float, y: Float, z: Float, tMs: Long, gyroNewestMs: Long, state: MotionState) = throw IllegalStateException("boom")
        })
        val dw = ArrayList<Long>(); tapped.dwellListener = { dw += it }
        for (i in 0 until 400) {
            val g = if (i in 50..80) 1.5f else 0.01f
            plain.onGyro(g, 0f, 0f, i * 20L); tapped.onGyro(g, 0f, 0f, i * 20L)
            plain.onLinearAccel(0f, 0.01f, 0f, i * 20L); tapped.onLinearAccel(0f, 0.01f, 0f, i * 20L)
        }
        assertEquals(plain.state.value, tapped.state.value)
        assertEquals(plain.features().rotRad, tapped.features().rotRad)
        assertTrue(dw.isNotEmpty())
    }
}
