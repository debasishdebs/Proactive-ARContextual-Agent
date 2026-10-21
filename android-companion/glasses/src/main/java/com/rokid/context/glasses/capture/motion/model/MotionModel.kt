package com.rokid.context.glasses.capture.motion.model

import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.motion.GlanceParams

/** One second of IMU features, in the order the trained tree's feature indices refer to. */
data class SecondFeatures(
    val gyroMax: Float, val gyroMean: Float, val gyroRms: Float, val aboveOnFrac: Float,
    val accVar: Float, val stepHz: Float, val rotRad: Float,
) {
    fun toArray(): FloatArray = floatArrayOf(gyroMax, gyroMean, gyroRms, aboveOnFrac, accVar, stepHz, rotRad)
}

/** Classifies a second of features. Runs in shadow in wave A3 (logged, never driving capture). */
interface MotionModel {
    val id: String
    fun classify(f: SecondFeatures): MotionState
}

class HeuristicMotionModel(private val p: GlanceParams = GlanceParams()) : MotionModel {
    override val id = "heuristic-v1"
    override fun classify(f: SecondFeatures): MotionState = when {
        f.accVar > 0.25f && f.stepHz in 1.2f..2.8f -> MotionState.WALKING
        f.aboveOnFrac >= 0.3f && f.rotRad >= p.minBoutRad -> MotionState.HEAD_MOVING
        else -> MotionState.STILL
    }
}

/**
 * A decision tree in flat arrays (the output of tools/motion-train). Leaves have `left == -1`; classes index [STILL, HEAD_MOVING, WALKING].
 * The whole tree is validated at construction ([valid]); an invalid tree, or any runtime failure, answers with [fallback]
 * so the shadow never throws and never loses a CSV row. Use [orHeuristic] to load untrusted arrays.
 */
class TreeMotionModel(
    override val id: String,
    private val feature: IntArray, private val threshold: FloatArray,
    private val left: IntArray, private val right: IntArray, private val leafClass: IntArray,
    private val fallback: MotionModel = HeuristicMotionModel(),
) : MotionModel {
    init {
        val n = feature.size
        require(threshold.size == n && left.size == n && right.size == n && leafClass.size == n) { "tree arrays must have equal sizes" }
    }

    /** Feature and child indices in range, children strictly forward (so acyclic, depth <= size), classes known, thresholds finite. */
    val valid: Boolean = run {
        val n = feature.size
        n > 0 && (0 until n).all { i ->
            if (left[i] == -1) leafClass[i] in 0..2 && right[i] == -1
            else feature[i] in 0 until FEATURES && threshold[i].isFinite() &&
                left[i] in (i + 1) until n && right[i] in (i + 1) until n
        }
    }

    override fun classify(f: SecondFeatures): MotionState {
        if (!valid) return fallback.classify(f)
        return try {
            val x = f.toArray()
            var node = 0
            while (left[node] != -1) node = if (x[feature[node]] <= threshold[node]) left[node] else right[node]
            CLASSES[leafClass[node]]
        } catch (_: Throwable) {
            fallback.classify(f)
        }
    }

    companion object {
        private const val FEATURES = 7
        private val CLASSES = listOf(MotionState.STILL, MotionState.HEAD_MOVING, MotionState.WALKING)

        /** The tree when the arrays form a valid tree, else the heuristic; [log] gets one count-only line when rejected. */
        fun orHeuristic(
            id: String, feature: IntArray, threshold: FloatArray, left: IntArray, right: IntArray, leafClass: IntArray,
            log: (String) -> Unit = {},
        ): MotionModel {
            val t = runCatching { TreeMotionModel(id, feature, threshold, left, right, leafClass) }.getOrNull()
            if (t != null && t.valid) return t
            runCatching { log("motion tree rejected nodes=${feature.size} using heuristic") }
            return HeuristicMotionModel()
        }
    }
}
