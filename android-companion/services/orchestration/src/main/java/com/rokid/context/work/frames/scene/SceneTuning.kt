package com.rokid.context.work.frames.scene

/**
 * Every threshold of the scene-grouping rules in one injectable value (spec P4.1 plus Wave 2 rulings 5 and 7).
 * Pure Kotlin: no Android and no DB types, so a canvas runner can reuse it with overrides.
 */
data class SceneTuning(
    val dedupeCos: Float = 0.93f,
    val dedupeWindowMs: Long = 10 * 60_000L,
    val noveltyWindowMs: Long = 30 * 60_000L,
    val watchCos: Float = 0.25f,
    val wDwell: Float = 0.4f,
    val wText: Float = 0.3f,
    val wNovelty: Float = 0.3f,
    val wWatch: Float = 0.3f,
    val textMinWords: Int = 4,
    val salientAt: Float = 0.5f,
    val driftCos: Float = 0.25f,
    val driftRun: Int = 2,
    val maxMembers: Int = 100,
    val maxAgeMs: Long = 5 * 60_000L,
    val gapMs: Long = 2 * 60_000L,
    val outOfOrderMs: Long = 60_000L,
    val maxReps: Int = 3,
    val callMaxClusters: Int = 3,
    val callMaxPhotos: Int = 6,
    val companionWaitMs: Long = 60_000L,
)
