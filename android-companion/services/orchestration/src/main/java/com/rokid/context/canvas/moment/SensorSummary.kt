package com.rokid.context.canvas.moment

import com.rokid.context.memory.db.MomentSensorEntity
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The compact sensor summary a reader gets for a Moment (one second: mean equals max) or a Scene (its member seconds), only when `moment_sensor` rows exist: the mean and the maximum of the
 * gyro magnitude, of the acceleration variance and of the step frequency, the most frequent motion state and context code (a tie goes to the smaller name). Numbers and enum names only; it
 * is never part of a content hash.
 */
data class SensorSummary(
    val seconds: Int,
    val gyroMean: Double, val gyroMax: Double, val accVarMean: Double, val accVarMax: Double, val stepHzMean: Double, val stepHzMax: Double,
    val state: String?, val context: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("seconds", seconds)
        put("gyroMean", gyroMean); put("gyroMax", gyroMax); put("accVarMean", accVarMean); put("accVarMax", accVarMax); put("stepHzMean", stepHzMean); put("stepHzMax", stepHzMax)
        put("state", state?.let { JsonPrimitive(it) } ?: JsonNull); put("context", context?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        /** The summary of [rows], or null when there are none. */
        fun of(rows: List<MomentSensorEntity>): SensorSummary? {
            if (rows.isEmpty()) return null
            fun mode(values: List<String>) = values.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key
            return SensorSummary(
                rows.size,
                rows.map { it.gyroMean }.average(), rows.maxOf { it.gyroMax }, rows.map { it.accVar }.average(), rows.maxOf { it.accVar }, rows.map { it.stepHz }.average(), rows.maxOf { it.stepHz },
                mode(rows.map { it.motionState }), mode(rows.mapNotNull { it.contextCode }),
            )
        }
    }
}
