package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * `proc.motion_model` (Process, phone side): the TUNABLE heuristic of the watch branch. It turns one number per item (the `value` of an `agg.window`, or the `score` of a watch value)
 * into a decision, `moving` or `still`. Pure and deterministic: no model, no clock, no randomness. The tuning lives in the params:
 *  - `threshold`: moving when the number is at least this;
 *  - `hysteresis`: once moving, it stays moving until the number falls BELOW `threshold - hysteresis` (0 = off, the plain rule);
 *  - `kind`: which kind of model; only `heuristic` exists today. A learned model would be a second kind, with its own params, behind the same input and output.
 * Items are decided in run order (time order) with ONE state per run that starts `still`. An item with no usable number is an error and leaves the state as it was.
 * Output item: `label` (`moving`|`still`), `value` (1.0 or 0.0, so `gate.compare` can branch on it), `score` (the number read) and the thresholds used. `route.switch` reads `label`.
 */
object MotionModel {
    const val KIND_HEURISTIC = "heuristic"
    val KINDS = listOf(KIND_HEURISTIC)
    const val DEFAULT_THRESHOLD = 0.3
    const val DEFAULT_HYSTERESIS = 0.0
    const val LIMIT = 1_000.0
    const val MOVING = "moving"
    const val STILL = "still"
    val LABELS = listOf(MOVING, STILL)

    data class Settings(val kind: String = KIND_HEURISTIC, val threshold: Double = DEFAULT_THRESHOLD, val hysteresis: Double = DEFAULT_HYSTERESIS) {
        /** The number below which a moving state ends. */
        val exit: Double get() = threshold - hysteresis
    }

    /** The params of a node; an absent or invalid one falls back to its default (the validator reports invalid ones, and a hysteresis above the threshold). */
    fun settings(params: JsonObject): Settings {
        fun num(k: String) = (params[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..LIMIT }
        val kind = (params["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in KINDS } ?: KIND_HEURISTIC
        val threshold = num("threshold") ?: DEFAULT_THRESHOLD
        return Settings(kind, threshold, (num("hysteresis") ?: DEFAULT_HYSTERESIS).coerceAtMost(threshold))
    }

    /** The number of an item: the `score` of a watch value, else the `value` of an aggregate; null when it has none (or it is not finite). */
    fun numberOf(item: JsonElement): Double? {
        val o = item as? JsonObject ?: return null
        val key = if ((o["itemType"] as? JsonPrimitive)?.content == "watch") "score" else "value"
        return (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    }

    /** One decision per number, in order; a null number gives a null decision and leaves the state alone. The state starts `still`. */
    fun decide(numbers: List<Double?>, s: Settings): List<Boolean?> {
        var moving = false
        return numbers.map { v ->
            if (v == null) return@map null
            moving = if (moving) v >= s.exit else v >= s.threshold
            moving
        }
    }

    val schema = ParamSchema(listOf(
        ParamSpec("kind", ParamKind.ENUM, label = "Kind of model", options = KINDS, default = JsonPrimitive(KIND_HEURISTIC)),
        ParamSpec("threshold", ParamKind.NUMBER, label = "Moving when the number is at least", min = 0.0, max = LIMIT, step = 0.05, default = JsonPrimitive(DEFAULT_THRESHOLD), sweep = true),
        ParamSpec("hysteresis", ParamKind.NUMBER, label = "Stays moving until it falls below threshold minus", min = 0.0, max = LIMIT, step = 0.05, default = JsonPrimitive(DEFAULT_HYSTERESIS), sweep = true),
    ))

    val inner = InnerFlow(
        nodes = listOf(
            InnerNode("number", "Number in (aggregate or watch score)", "source"),
            InnerNode("kind", "Kind of model", "const"),
            InnerNode("compare", "At least the threshold?", "op"),
            InnerNode("hysteresis", "Already moving? keep it above threshold minus hysteresis", "op"),
            InnerNode("moving", "moving", "branch"),
            InnerNode("still", "still", "branch"),
        ),
        wires = listOf(
            InnerWire("number", "compare"), InnerWire("kind", "compare"), InnerWire("compare", "hysteresis", "value"),
            InnerWire("hysteresis", "moving", "yes"), InnerWire("hysteresis", "still", "no"),
        ),
        exposed = listOf(InnerExposed("kind", "kind"), InnerExposed("threshold", "compare"), InnerExposed("hysteresis", "hysteresis")),
        note = listOf(
            "Items are decided in time order; the state starts still at the beginning of a run.",
            "Only the heuristic kind exists today; a learned model would be another kind behind the same number in and label out.",
        ),
    )
}
