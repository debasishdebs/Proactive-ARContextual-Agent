package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionState
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureLoggerTest {
    private fun f(state: MotionState) = MotionClassifier.Features(0.5f, 1.0f, 1.5, state)

    @Test fun firstCallLogs() {
        val lines = mutableListOf<String>()
        FeatureLogger(MotionClassifier { 0L }, { lines += it }, { 0L }).log(f(MotionState.STILL))
        assertEquals(1, lines.size)
    }

    @Test fun sameStateLogsOnlyAfterThirtySeconds() {
        val lines = mutableListOf<String>()
        var t = 0L
        val l = FeatureLogger(MotionClassifier { 0L }, { lines += it }, { t })
        l.log(f(MotionState.STILL))
        t = 29_000; l.log(f(MotionState.STILL))
        assertEquals(1, lines.size)
        t = 30_000; l.log(f(MotionState.STILL))
        assertEquals(2, lines.size)
    }

    @Test fun stateChangeLogsImmediately() {
        val lines = mutableListOf<String>()
        var t = 0L
        val l = FeatureLogger(MotionClassifier { 0L }, { lines += it }, { t })
        l.log(f(MotionState.STILL))
        t = 1_000; l.log(f(MotionState.WALKING))
        assertEquals(2, lines.size)
    }

    @Test fun verboseLogsEveryCall() {
        val lines = mutableListOf<String>()
        val l = FeatureLogger(MotionClassifier { 0L }, { lines += it }, { 0L }, verbose = true)
        repeat(3) { l.log(f(MotionState.STILL)) }
        assertEquals(3, lines.size)
    }
}
