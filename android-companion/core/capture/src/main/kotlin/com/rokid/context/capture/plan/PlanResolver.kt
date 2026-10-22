package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextCapturePolicy
import com.rokid.context.core.model.context.ContextPolicies
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.keyframe.KeyframeGate
import com.rokid.context.glasses.capture.motion.GlanceParams
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Resolution of a plan against today's behaviour (S27, S68, S83). Nothing here clamps. */
object PlanResolver {
    private fun toState(s: PlanState) = ContextState.valueOf(s.name)

    fun rowOf(p: ContextCapturePolicy) = PlanRow(
        p.capPerMin, TimerPhotoGaps(p.minSettledCheckMs, p.minQuietCheckMs, p.minWalkCheckMs, p.minStillCapMs), p.forceSettle, p.nudges,
    )

    /** Maps a plan row back to the glasses policy exactly (a plan never pauses). */
    fun toContextPolicy(row: PlanRow) = ContextCapturePolicy(
        capPerMin = row.capPerMin,
        minSettledCheckMs = row.timerPhotoGapsMs.settledMs,
        minQuietCheckMs = row.timerPhotoGapsMs.quietMs,
        minWalkCheckMs = row.timerPhotoGapsMs.walkMs,
        minStillCapMs = row.timerPhotoGapsMs.stillCapMs,
        forceSettle = row.forceSettle,
        pause = false,
        nudges = row.nudges,
    )

    /** Today's defaults as a plan config: the rows are exactly [ContextPolicies.DEFAULT]. */
    fun defaults(): PlanConfig {
        val g = GlanceParams()
        return PlanConfig(
            glance = GlanceBlock(g.onsetRadS, g.releaseRadS, g.bridgeMs, g.minBoutMs, g.minBoutRad, g.settleMs, g.noveltyWindowMs, g.noveltyMinRad, g.bigTurnRad),
            policy = PlanState.entries.associateWith { rowOf(ContextPolicies.DEFAULT.getValue(toState(it))) },
            gate = GateBlock(KeyframeGate.BIG_CHANGE, KeyframeGate.HAMMING, "all"),
            ctxBound = true,
            phoneFusion = PlanBounds.FUSION_DEFAULTS,
        )
    }

    /** The identity plan: rows, glance and gate equal today's defaults bit for bit (no clamping); the rest comes from [base]. */
    fun identity(base: PlanConfig = defaults()): PlanConfig {
        val d = defaults()
        return base.copy(glance = d.glance, policy = d.policy, gate = d.gate)
    }

    /**
     * Fills every field [partial] (a `config` JSON object) leaves out from [base]. An explicit `null` capPerMin means "unchanged" and
     * is kept. Fails (Result) on unknown keys or non-finite numbers.
     */
    fun resolve(partial: JsonObject, base: PlanConfig): Result<PlanConfig> =
        runCatching { PlanJson.decodeConfig(merge(PlanJson.configJson(base), partial)) }

    private fun merge(base: JsonElement, over: JsonElement): JsonElement =
        if (base is JsonObject && over is JsonObject) JsonObject(base + over.mapValues { (k, v) -> base[k]?.let { merge(it, v) } ?: v })
        else over
}
