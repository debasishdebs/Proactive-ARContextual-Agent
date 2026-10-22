package com.rokid.context.glasses.capture.motion.model

import com.rokid.context.core.model.MotionState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Loads the checked-in output of tools/motion-train/train.py through the real loader. */
class MotionModelTrainedTreeTest {
    private fun ints(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
    private fun floats(s: String) = s.split(",").map { it.trim().removeSuffix("f") }.filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray()

    @Test fun emittedTreeIsAcceptedNotFallback() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tools/testdata/motion-train/TrainedMotionModel.kt") }.first { it.exists() }
        val m = Regex("""TreeMotionModel\("([^"]+)",\s*intArrayOf\(([^)]*)\),\s*floatArrayOf\(([^)]*)\),\s*intArrayOf\(([^)]*)\),\s*intArrayOf\(([^)]*)\),\s*intArrayOf\(([^)]*)\)\)""")
            .find(file.readText())!!
        val g = m.groupValues
        val model = TreeMotionModel(g[1], ints(g[2]), floats(g[3]), ints(g[4]), ints(g[5]), ints(g[6]))
        assertTrue(model.valid, "trainer output rejected by the loader")
        val loaded = TreeMotionModel.orHeuristic(g[1], ints(g[2]), floats(g[3]), ints(g[4]), ints(g[5]), ints(g[6]))
        assertTrue(loaded is TreeMotionModel, "fell back to the heuristic")
        assertEquals(MotionState.STILL, loaded.classify(SecondFeatures(0.05f, 0.02f, 0.02f, 0f, 0.02f, 0f, 0.01f)))
        assertEquals(MotionState.HEAD_MOVING, loaded.classify(SecondFeatures(1.8f, 0.9f, 1.0f, 0.7f, 0.1f, 0.2f, 0.8f)))
        assertEquals(MotionState.WALKING, loaded.classify(SecondFeatures(0.4f, 0.2f, 0.2f, 0.1f, 0.45f, 1.8f, 0.1f)))
    }
}
