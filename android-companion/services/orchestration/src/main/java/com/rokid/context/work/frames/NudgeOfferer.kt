package com.rokid.context.work.frames

import com.rokid.context.orchestration.SavedMemory

/**
 * Feeds the fast-path nudge evaluator (spec P2.1): a memory is eligible when it comes from a dwell keyframe (wave 1; salience
 * arrives in wave 2) or from a new place (the frame's place label differs from the last one seen). Captions are never offered.
 */
class NudgeOfferer(private val offer: (memoryId: String, eligible: Boolean, captureMs: Long?) -> Unit) {
    private var lastPlace: String? = null

    fun onExtracted(frames: List<KeyframeCandidate>, memories: List<SavedMemory>, places: Map<String, String?>) {
        val byId = frames.associateBy { it.frameId }
        for (m in memories) {
            if (m.index == null) continue
            val src = m.frameIds.mapNotNull(byId::get)
            val place = m.frameIds.firstNotNullOfOrNull { places[it] }
            val newPlace = place != null && place != lastPlace
            if (place != null) lastPlace = place
            offer(m.memoryId, src.any { it.dwell } || newPlace, src.minOfOrNull { it.ts })
        }
    }
}
