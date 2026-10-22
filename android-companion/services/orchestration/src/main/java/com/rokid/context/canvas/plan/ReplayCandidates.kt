package com.rokid.context.canvas.plan

import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.memory.db.CanvasDao

/**
 * The replay candidate of a config hash, derived from the STORED plan that has that hash (a frozen version's compiled capture half). A replay is labelled with the hash of the
 * configuration it really simulated, so a caller never supplies figures for a hash: an unknown hash resolves to null and the request is refused.
 */
class ReplayCandidates(private val canvas: CanvasDao, private val settingsRev: suspend () -> Long = { 0L }) {
    suspend fun forHash(hash: String): ReplayCandidate? {
        val rev = settingsRev()
        for (p in canvas.pipelines()) for (v in canvas.versions(p.id)) {
            val pipeline = PipelineJson.decode(v.json).getOrNull() ?: continue
            val plan = PlanCompiler.compile(pipeline, rev).plan ?: continue
            if (plan.configHash == hash) return ReplayCandidate.of(plan)
        }
        return null
    }
}
