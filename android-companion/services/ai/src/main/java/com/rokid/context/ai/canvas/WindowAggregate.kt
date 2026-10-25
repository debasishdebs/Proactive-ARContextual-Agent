package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * `agg.window` (Aggregate family): one number per item, the mean, min, max or count of ONE sensor feature over the last [Settings.window] items (this one and the ones before it,
 * in run order) of a Scenes, Moments or watch input. The values are read from each item's `sensor` summary (numbers only, never text); an item without one adds nothing. Pure and
 * deterministic: no model, no clock. `gate.compare` is the Condition that reads its number.
 */
object WindowAggregate {
    const val DEFAULT_FEATURE = "gyroMean"
    const val DEFAULT_OP = "mean"
    const val DEFAULT_WINDOW = 10
    /** A run holds at most 50 items (`CanvasRunner.MAX_PHOTOS`), so a larger window could not differ. */
    const val MAX_WINDOW = 50
    /** The numeric fields of a `SensorSummary` (the Scene's sensor summary served to a run item), and `watchMotion` (the score of a `src.watch_motion` item). */
    val FEATURES = listOf("seconds", "gyroMean", "gyroMax", "accVarMean", "accVarMax", "stepHzMean", "stepHzMax", "watchMotion")
    val OPS = listOf("mean", "min", "max", "count")

    data class Settings(val feature: String = DEFAULT_FEATURE, val op: String = DEFAULT_OP, val window: Int = DEFAULT_WINDOW)

    /** The params of a node; an absent or invalid one falls back to its default (the validator reports invalid ones). */
    fun settings(params: JsonObject): Settings {
        fun str(k: String) = (params[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val w = (params["window"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        return Settings(
            str("feature")?.takeIf { it in FEATURES } ?: DEFAULT_FEATURE,
            str("op")?.takeIf { it in OPS } ?: DEFAULT_OP,
            w?.takeIf { it in 1..MAX_WINDOW } ?: DEFAULT_WINDOW,
        )
    }

    /** The finite number [feature] of an item's `sensor` summary, or null (no summary, no such field, not a number). */
    fun featureOf(item: JsonElement, feature: String): Double? {
        val sensor = (item as? JsonObject)?.get("sensor") as? JsonObject ?: return null
        return (sensor[feature] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    }

    /**
     * For each index, [op] over the values of the last [window] items up to and including it, skipping missing (null or not finite) ones. `count` is the number of values (0.0 for an empty
     * window); mean, min and max are null for an empty window.
     */
    fun over(values: List<Double?>, op: String, window: Int): List<Double?> = values.indices.map { i ->
        val w = values.subList(maxOf(0, i - window + 1), i + 1).filterNotNull().filter { it.isFinite() }
        when (op) {
            "count" -> w.size.toDouble()
            "min" -> w.minOrNull()
            "max" -> w.maxOrNull()
            else -> if (w.isEmpty()) null else w.average()
        }
    }

    val schema = ParamSchema(listOf(
        ParamSpec("feature", ParamKind.ENUM, label = "Sensor feature", options = FEATURES, default = JsonPrimitive(DEFAULT_FEATURE)),
        ParamSpec("op", ParamKind.ENUM, label = "Over the window, take the", options = OPS, default = JsonPrimitive(DEFAULT_OP)),
        ParamSpec("window", ParamKind.INT, label = "Window (items, this one and the ones before)", min = 1.0, max = MAX_WINDOW.toDouble(), step = 1.0, default = JsonPrimitive(DEFAULT_WINDOW)),
    ))
}

/** `gate.compare` (Condition): the number of an aggregate against a cutoff; the branch is `yes` when `number <operator> cutoff` holds. A number that is not finite is `no`. */
object NumberCompare {
    const val DEFAULT_OPERATOR = ">="
    const val DEFAULT_CUTOFF = 0.5
    const val LIMIT = 1_000_000.0
    val OPERATORS = listOf(">=", ">", "<=", "<")

    data class Settings(val operator: String = DEFAULT_OPERATOR, val cutoff: Double = DEFAULT_CUTOFF)

    fun settings(params: JsonObject): Settings {
        val op = (params["operator"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val cut = (params["cutoff"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        return Settings(op?.takeIf { it in OPERATORS } ?: DEFAULT_OPERATOR, cut?.takeIf { it.isFinite() && it in -LIMIT..LIMIT } ?: DEFAULT_CUTOFF)
    }

    fun holds(value: Double, s: Settings): Boolean = value.isFinite() && when (s.operator) {
        ">" -> value > s.cutoff
        "<=" -> value <= s.cutoff
        "<" -> value < s.cutoff
        else -> value >= s.cutoff
    }

    val schema = ParamSchema(listOf(
        ParamSpec("operator", ParamKind.ENUM, label = "Yes when the number is", options = OPERATORS, default = JsonPrimitive(DEFAULT_OPERATOR)),
        ParamSpec("cutoff", ParamKind.NUMBER, label = "Cutoff", min = -LIMIT, max = LIMIT, step = 0.05, default = JsonPrimitive(DEFAULT_CUTOFF)),
    ))
}
