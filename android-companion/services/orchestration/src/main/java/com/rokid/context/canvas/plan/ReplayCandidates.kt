package com.rokid.context.canvas.plan

import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.ai.canvas.plan.WindowTolerance
import com.rokid.context.core.model.context.graph.WindowRing
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
            // A window plan holds the tolerance it was compiled with; the global setting may have changed since, so a pipeline with windows is also tried at every tolerance (3d).
            val tolerances = if ("\"win\"" in v.json) listOf(WindowTolerance.current()) + (WindowRing.MIN_VALID_PCT..100) else listOf(WindowTolerance.current())
            for (pct in tolerances) {
                val plan = PlanCompiler.compile(pipeline, rev, windowMinValidPct = pct).plan ?: continue   // a compile can fail at one tolerance only (a duplicate term becoming a fifth)
                if (plan.configHash == hash) return ReplayCandidate.of(plan)
            }
        }
        return null
    }
}
