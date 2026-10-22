package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.CapturePlan
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.context.ContextCapturePolicy
import com.rokid.context.core.model.context.ContextPolicies
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.keyframe.GateParams
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.session.BudgetTier
import com.rokid.context.glasses.capture.session.CaptureProfile

/**
 * One immutable set of everything a plan changes on the glasses (S69): the glance tunables, the gate values and the policy table.
 * It is built once and swapped in whole at a safe boundary, so no decision ever mixes two plans. [SETTINGS] is today's behaviour
 * (no plan); SHADOW and SETTINGS plans map to it, so they cannot change real capture (S67).
 *
 * [table] always holds all six states. UNKNOWN and WALKING are the locked default rows (S32); an editable row the plan leaves out
 * is the UNKNOWN row (unchanged capture), NOT the REST or FLIGHT default. [ctxBound] false = the phone's context state is not
 * applied to capture (the UNKNOWN row is used whatever it says).
 */
data class ActiveConfig(
    val glance: GlanceParams,
    val gate: GateParams,
    val table: Map<ContextState, ContextCapturePolicy>,
    val ctxBound: Boolean,
    val configHash: String? = null,
    /** A plan is always SETTLE wake (spec 3.4, S27): the settings wake is for the settings path only. */
    val forceSettle: Boolean = true,
) {
    fun policyFor(applied: ContextState): ContextCapturePolicy = ContextPolicies.of(if (ctxBound) applied else ContextState.UNKNOWN, table)

    /** S68: the settings tier profile, then the row (minima via maxOf). The session applies `forRest()`, then heat and the battery tier. */
    fun profile(wake: CameraWake, tier: BudgetTier, applied: ContextState): CaptureProfile =
        CaptureProfile.of(if (forceSettle) CameraWake.SETTLE else wake, tier).withContext(policyFor(applied))

    /** The wake the camera really runs now: the composed profile's, which the heartbeat reports (not the settings wake while a plan runs SETTLE). */
    fun effectiveWake(wake: CameraWake, tier: BudgetTier, applied: ContextState): CameraWake = profile(wake, tier, applied).wake

    /** Row `capPerMin` null = null here too: no context cap and no hard flag. */
    fun contextCap(applied: ContextState): Int? = policyFor(applied).capPerMin

    companion object {
        val SETTINGS = ActiveConfig(GlanceParams(), GateParams(), ContextPolicies.DEFAULT, ctxBound = true, forceSettle = false)

        /** The config a LIVE or TRIAL plan applies. Only the plan's glance fields replace [GlanceParams] defaults (caps and sample limits are not plan params). */
        fun of(plan: CapturePlan): ActiveConfig {
            val g = plan.config.glance
            val unknown = ContextPolicies.DEFAULT.getValue(ContextState.UNKNOWN)
            val rows = PlanState.entries.associate { s ->
                ContextState.valueOf(s.name) to (plan.config.policy[s]?.let { PlanResolver.toContextPolicy(it) } ?: unknown)
            }
            return ActiveConfig(
                glance = GlanceParams(
                    onsetRadS = g.onsetRadS, releaseRadS = g.releaseRadS, bridgeMs = g.bridgeMs, minBoutMs = g.minBoutMs, minBoutRad = g.minBoutRad,
                    settleMs = g.settleMs, noveltyWindowMs = g.noveltyWindowMs, noveltyMinRad = g.noveltyMinRad, bigTurnRad = g.bigTurnRad,
                ),
                gate = GateParams(plan.config.gate.hamming, plan.config.gate.bigChange, plan.config.gate.ringCompare == "all"),
                table = mapOf(ContextState.UNKNOWN to unknown, ContextState.WALKING to ContextPolicies.DEFAULT.getValue(ContextState.WALKING)) + rows,
                ctxBound = plan.config.ctxBound,
                configHash = plan.configHash,
            )
        }
    }
}
