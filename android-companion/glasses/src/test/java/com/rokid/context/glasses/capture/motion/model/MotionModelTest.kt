package com.rokid.context.glasses.capture.motion.model

import com.rokid.context.core.model.MotionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MotionModelTest {
    private fun f(gyroMax: Float = 0f, aboveOnFrac: Float = 0f, accVar: Float = 0f, stepHz: Float = 0f, rotRad: Float = 0f) =
        SecondFeatures(gyroMax, 0f, 0f, aboveOnFrac, accVar, stepHz, rotRad)

    @Test fun heuristic() {
        val m = HeuristicMotionModel()
        assertEquals("heuristic-v1", m.id)
        assertEquals(MotionState.WALKING, m.classify(f(accVar = 0.3f, stepHz = 2.0f)))
        assertEquals(MotionState.HEAD_MOVING, m.classify(f(aboveOnFrac = 0.5f, rotRad = 0.4f)))
        assertEquals(MotionState.STILL, m.classify(f(aboveOnFrac = 0.5f, rotRad = 0.1f)))
        assertEquals(MotionState.STILL, m.classify(f()))
    }

    @Test fun toArrayOrder() {
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f), SecondFeatures(1f, 2f, 3f, 4f, 5f, 6f, 7f).toArray().toList())
    }

    private fun tree() = TreeMotionModel(
        id = "t", feature = intArrayOf(0, 4, -2, -2, -2), threshold = floatArrayOf(0.6f, 0.25f, 0f, 0f, 0f),
        left = intArrayOf(1, 3, -1, -1, -1), right = intArrayOf(2, 4, -1, -1, -1), leafClass = intArrayOf(0, 0, 1, 0, 2),
    )

    @Test fun treeClassifies() {
        val m = tree()
        assertEquals("t", m.id)
        assertEquals(MotionState.STILL, m.classify(f(gyroMax = 0.2f, accVar = 0.1f)))
        assertEquals(MotionState.WALKING, m.classify(f(gyroMax = 0.2f, accVar = 0.4f)))
        assertEquals(MotionState.HEAD_MOVING, m.classify(f(gyroMax = 0.9f)))
    }

    @Test fun mismatchedSizesRejected() {
        assertFailsWith<IllegalArgumentException> {
            TreeMotionModel("t", intArrayOf(0), floatArrayOf(0f, 1f), intArrayOf(-1), intArrayOf(-1), intArrayOf(0))
        }
    }
}
