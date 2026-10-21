package com.rokid.context.core.model.context

/** How the glasses capture in a [ContextState] (spec 2026-10-21 §9). [capPerMin] null = unchanged (A3 cap). */
data class ContextCapturePolicy(
    val capPerMin: Int?,
    val minSettledCheckMs: Long,
    val minQuietCheckMs: Long,
    val minWalkCheckMs: Long,
    val minStillCapMs: Long,
    val forceSettle: Boolean,
    val pause: Boolean,
    val nudges: Boolean,
)

object ContextPolicies {
    private val UNCHANGED = ContextCapturePolicy(null, 0, 0, 0, 0, forceSettle = false, pause = false, nudges = true)

    val DEFAULT: Map<ContextState, ContextCapturePolicy> = mapOf(
        ContextState.UNKNOWN to UNCHANGED,
        ContextState.WALKING to UNCHANGED,
        ContextState.REST to ContextCapturePolicy(2, 30_000, 60_000, 30_000, 120_000, forceSettle = true, pause = false, nudges = true),
        // user ruling: vehicle classification never pauses or throttles capture by itself (same as UNKNOWN); only DRIVING drops nudges
        ContextState.VEHICLE_PASSENGER to UNCHANGED,
        ContextState.VEHICLE_DRIVING to UNCHANGED.copy(nudges = false),
        ContextState.FLIGHT to ContextCapturePolicy(1, 120_000, 300_000, 120_000, 300_000, forceSettle = true, pause = false, nudges = true),
    )

    /** A missing key gives the UNKNOWN row. */
    fun of(s: ContextState, table: Map<ContextState, ContextCapturePolicy> = DEFAULT): ContextCapturePolicy =
        table[s] ?: table[ContextState.UNKNOWN] ?: DEFAULT.getValue(ContextState.UNKNOWN)
}
