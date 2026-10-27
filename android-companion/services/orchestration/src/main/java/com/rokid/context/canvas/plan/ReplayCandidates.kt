package com.rokid.context.canvas.plan

import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.ai.canvas.plan.SignalConfig
import com.rokid.context.ai.canvas.plan.SignalConfigs
import com.rokid.context.ai.canvas.plan.WindowTolerance
import com.rokid.context.core.model.context.graph.WindowRing
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.memory.db.CanvasDao

/**
 * The replay candidate of a config hash, derived from the STORED plan that has that hash (a frozen version's compiled capture half). A replay is labelled with the hash of the
 * configuration it really simulated, so a caller never supplies figures for a hash: an unknown hash resolves to null and the request is refused.
 */
class ReplayCandidates(
    private val canvas: CanvasDao, private val settingsRev: suspend () -> Long = { 0L },
    /** v0.9.7 A: the signal configurations to try for a version that names a derived signal (current, then the saved history). */
    private val signalConfigs: () -> List<SignalConfig> = { listOf(SignalConfigs.current()) + SignalConfigs.history() },
) {
    suspend fun forHash(hash: String): ReplayCandidate? {
        val rev = settingsRev()
        for (p in canvas.pipelines()) for (v in canvas.versions(p.id)) {
            val pipeline = PipelineJson.decode(v.json).getOrNull() ?: continue
            // A window plan holds the tolerance it was compiled with; the global setting may have changed since, so a pipeline with windows is also tried at every tolerance (3d).
            val tolerances = if ("\"win\"" in v.json) listOf(WindowTolerance.current()) + (WindowRing.MIN_VALID_PCT..100) else listOf(WindowTolerance.current())
            // A version without derived signals compiles exactly as it was frozen with NO signal configuration (a later switch-off of a signal must not unresolve it);
            // one that names a derived signal is tried against the current configuration and the saved history, so editing or deleting the definition keeps old hashes resolvable.
            val configs = if (""""d_""" in v.json) (signalConfigs() + SignalConfig()).distinct() else listOf(SignalConfig())
            for (cfg in configs) for (pct in tolerances) {
                val plan = PlanCompiler.compile(pipeline, rev, windowMinValidPct = pct, signals = cfg).plan ?: continue   // a compile can fail at one tolerance only (a duplicate term becoming a fifth)
                if (plan.configHash == hash) return ReplayCandidate.of(plan)
            }
        }
        return null
    }
}
