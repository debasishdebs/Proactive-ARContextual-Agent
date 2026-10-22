package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextState

const val PLAN_SCHEMA = 1
const val PLAN_MAX_BYTES = 8_192

/** S83: fixed in Kotlin, never plan params. */
const val RESERVED_ACTIVE_MS = 60_000L
const val RESERVED_REST_MS = 300_000L

/** Editable rows only (S32): WALKING and UNKNOWN are never in a plan. */
enum class PlanState { REST, VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT }

/** Each value is 0 (inherit) or 5_000..300_000 ms (S83). */
data class TimerPhotoGaps(val settledMs: Long, val quietMs: Long, val walkMs: Long, val stillCapMs: Long) {
    fun all(): List<Long> = listOf(settledMs, quietMs, walkMs, stillCapMs)
}

data class PlanRow(val capPerMin: Int?, val timerPhotoGapsMs: TimerPhotoGaps, val forceSettle: Boolean, val nudges: Boolean)

data class GlanceBlock(
    val onsetRadS: Float, val releaseRadS: Float, val bridgeMs: Long, val minBoutMs: Long, val minBoutRad: Float,
    val settleMs: Long, val noveltyWindowMs: Long, val noveltyMinRad: Float, val bigTurnRad: Float,
)

/** [ringCompare] is "last" or "all". */
data class GateBlock(val bigChange: Int, val hamming: Int, val ringCompare: String)

data class PlanConfig(
    val glance: GlanceBlock,
    val policy: Map<PlanState, PlanRow>,
    val gate: GateBlock,
    val ctxBound: Boolean,
    val phoneFusion: Map<String, Double>,
)

data class PlanMeta(val name: String, val settingsRev: Long, val expectOpensP95PerMin: Map<String, Double>? = null)

data class CapturePlan(val planSchema: Int = PLAN_SCHEMA, val configHash: String, val config: PlanConfig, val meta: PlanMeta)

/**
 * The reserved timer photo attempt bound (S83): 300 s in REST, VEHICLE_*, FLIGHT, or when WorkRest is resting while the state is not
 * WALKING; otherwise 60 s (UNKNOWN, WALKING). Must stay identical to the golden recorder's rule (see ReservedBoundParityTest).
 */
fun reservedBoundMs(state: ContextState, resting: Boolean): Long =
    if (state in setOf(ContextState.REST, ContextState.VEHICLE_PASSENGER, ContextState.VEHICLE_DRIVING, ContextState.FLIGHT) ||
        (resting && state != ContextState.WALKING)
    ) RESERVED_REST_MS else RESERVED_ACTIVE_MS
