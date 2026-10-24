package com.rokid.context.capture.plan

/**
 * What a value program (3c E2a) may tune: ONE field of ONE editable row, the capture cap or one of the four timer photo gaps. The rows are [PlanState] (REST,
 * VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT): WALKING and UNKNOWN are not in it, so they can never be a target, and the walking veto and the reserved
 * timer attempt are not fields at all. Whatever the program computes is clamped here to the row's existing bounds ([PlanBounds]); the program carries no bound of its own.
 * Field names are the canvas rate table's.
 */
object ExprValues {
    val FIELDS = listOf("capPerMin", "settledGapS", "quietGapS", "walkGapS", "stillCapGapS")
    const val CAP = 0

    /** The row of [name] among the editable rows, or -1 (WALKING, UNKNOWN and unknown names are refused). */
    fun rowIndex(name: String): Int = PlanState.entries.indexOfFirst { it.name == name }

    fun fieldIndex(name: String): Int = FIELDS.indexOf(name)

    /** `capPerMin`: the nearest whole number inside [PlanBounds.CAP_PER_MIN]. */
    fun clampCap(v: Double): Int = Math.round(v).coerceIn(PlanBounds.CAP_PER_MIN.first.toLong(), PlanBounds.CAP_PER_MIN.last.toLong()).toInt()

    /** A gap given in seconds, as milliseconds inside [PlanBounds.GAP_MIN_MS]..[PlanBounds.GAP_MAX_MS] (never 0 = inherit). */
    fun clampGapMs(seconds: Double): Long = Math.round(seconds * 1000.0).coerceIn(PlanBounds.GAP_MIN_MS, PlanBounds.GAP_MAX_MS)
}
