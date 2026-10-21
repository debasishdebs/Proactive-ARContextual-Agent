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

/** A decision tree in flat arrays (the output of tools/motion-train). Leaves have `left == -1`; classes index [STILL, HEAD_MOVING, WALKING]. */
class TreeMotionModel(
    override val id: String,
    private val feature: IntArray, private val threshold: FloatArray,
    private val left: IntArray, private val right: IntArray, private val leafClass: IntArray,
) : MotionModel {
    init {
        val n = feature.size
        require(threshold.size == n && left.size == n && right.size == n && leafClass.size == n) { "tree arrays must have equal sizes" }
    }

    override fun classify(f: SecondFeatures): MotionState {
        if (feature.isEmpty()) return MotionState.STILL
        val x = f.toArray()
        var node = 0
        var hops = 0
        while (left[node] != -1 && hops++ <= feature.size) {
            node = if (x[feature[node]] <= threshold[node]) left[node] else right[node]
        }
        return CLASSES.getOrElse(leafClass[node]) { MotionState.STILL }
    }

    private companion object { val CLASSES = listOf(MotionState.STILL, MotionState.HEAD_MOVING, MotionState.WALKING) }
}
