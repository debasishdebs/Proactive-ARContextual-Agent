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

    private val heur = HeuristicMotionModel()
    private val walkF = f(accVar = 0.3f, stepHz = 2.0f)

    private fun load(
        feature: IntArray = intArrayOf(0, 4, -2, -2, -2), threshold: FloatArray = floatArrayOf(0.6f, 0.25f, 0f, 0f, 0f),
        left: IntArray = intArrayOf(1, 3, -1, -1, -1), right: IntArray = intArrayOf(2, 4, -1, -1, -1),
        leaf: IntArray = intArrayOf(0, 0, 1, 0, 2), log: (String) -> Unit = {},
    ) = TreeMotionModel.orHeuristic("t", feature, threshold, left, right, leaf, log)

    @Test fun validTreeLoads() {
        assertEquals("t", load().id)
        assertEquals(MotionState.HEAD_MOVING, load().classify(f(gyroMax = 0.9f)))
    }

    private fun assertFallsBack(m: MotionModel, logged: List<String>) {
        assertEquals("heuristic-v1", m.id)
        assertEquals(MotionState.WALKING, m.classify(walkF)) // the tree would say STILL (gyroMax 0)
        assertEquals(1, logged.size)
        assertEquals(true, logged[0].matches(Regex("^[0-9A-Za-z= -]+$")))
    }

    @Test fun badFeatureIndexFallsBack() { val l = mutableListOf<String>(); assertFallsBack(load(feature = intArrayOf(0, 9, -2, -2, -2), log = { l += it }), l) }
    @Test fun childOutOfRangeFallsBack() { val l = mutableListOf<String>(); assertFallsBack(load(right = intArrayOf(2, 7, -1, -1, -1), log = { l += it }), l) }
    @Test fun cycleFallsBack() { val l = mutableListOf<String>(); assertFallsBack(load(left = intArrayOf(1, 0, -1, -1, -1), log = { l += it }), l) }
    @Test fun nanThresholdFallsBack() { val l = mutableListOf<String>(); assertFallsBack(load(threshold = floatArrayOf(Float.NaN, 0.25f, 0f, 0f, 0f), log = { l += it }), l) }
    @Test fun badLeafClassFallsBack() { val l = mutableListOf<String>(); assertFallsBack(load(leaf = intArrayOf(0, 0, 1, 0, 5), log = { l += it }), l) }
    @Test fun truncatedArraysFallBack() { val l = mutableListOf<String>(); assertFallsBack(load(leaf = intArrayOf(0, 0), log = { l += it }), l) }

    @Test fun invalidTreeBuiltDirectlyStillAnswersWithHeuristic() {
        val m = TreeMotionModel("t", intArrayOf(0, 9, -2, -2, -2), floatArrayOf(0.6f, 0.25f, 0f, 0f, 0f),
            intArrayOf(1, 3, -1, -1, -1), intArrayOf(2, 4, -1, -1, -1), intArrayOf(0, 0, 1, 0, 2))
        assertEquals(false, m.valid)
        assertEquals(MotionState.WALKING, m.classify(walkF))
    }
}
