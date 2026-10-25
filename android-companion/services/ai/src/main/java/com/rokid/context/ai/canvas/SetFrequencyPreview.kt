package com.rokid.context.ai.canvas

import com.rokid.context.core.model.context.ContextPolicies
import com.rokid.context.core.model.context.ContextState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * `out.set_frequency_preview` ("Set frequency (preview)", Outcome): what the pipeline WOULD set the capture rate to, as a run result. PREVIEW ONLY, like Save memory (preview): it never
 * publishes a plan, never touches the glasses capture policy and is never LIVE (no publish path accepts a graph that holds it, see `PlanShape.tier3Nodes`).
 * Per item it reads the `label` of a `proc.motion_model` (`moving` or `still`) and records: the capture row it would target, the cap per minute it would set for that label, what that
 * row has today (null: the row is "unchanged"), and whether that would raise, lower or keep it. The values are limited to what the capture table accepts (1..4 per minute).
 */
object SetFrequencyPreview {
    val ROWS = CaptureParams.policyRows
    const val DEFAULT_ROW = "REST"
    const val DEFAULT_MOVING = 4
    const val DEFAULT_STILL = 2
    const val MIN_CAP = 1
    const val MAX_CAP = 4

    data class Settings(val row: String = DEFAULT_ROW, val movingCapPerMin: Int = DEFAULT_MOVING, val stillCapPerMin: Int = DEFAULT_STILL)

    fun settings(params: JsonObject): Settings {
        fun int(k: String) = (params[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in MIN_CAP..MAX_CAP }
        val row = (params["row"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in ROWS } ?: DEFAULT_ROW
        return Settings(row, int("movingCapPerMin") ?: DEFAULT_MOVING, int("stillCapPerMin") ?: DEFAULT_STILL)
    }

    /** The cap per minute the capture policy has today for [row] (null = the row leaves the cap unchanged). */
    fun today(row: String): Int? = ContextPolicies.DEFAULT[ContextState.valueOf(row)]?.capPerMin

    /** The preview of one label, or null when [label] is neither `moving` nor `still`. */
    fun preview(label: String, s: Settings): JsonObject? {
        val value = when (label) { MotionModel.MOVING -> s.movingCapPerMin; MotionModel.STILL -> s.stillCapPerMin; else -> return null }
        val today = today(s.row)
        val change = when { today == null -> "set"; value > today -> "raise"; value < today -> "lower"; else -> "same" }
        val line = "${label}: would set ${s.row} to $value capture${if (value == 1) "" else "s"} per minute (" + (today?.let { "now $it" } ?: "now unchanged") + "; $change). Preview only, nothing applied."
        return buildJsonObject {
            put("preview", true); put("applied", false)
            put("label", label); put("targetRow", s.row); put("field", "capPerMin"); put("value", value)
            put("today", today?.let { JsonPrimitive(it) } ?: JsonNull); put("change", change)
            put("facts", JsonArray(listOf(JsonPrimitive(line))))
        }
    }

    val schema = ParamSchema(listOf(
        ParamSpec("row", ParamKind.ENUM, label = "Capture row it would change", options = ROWS, default = JsonPrimitive(DEFAULT_ROW)),
        ParamSpec("movingCapPerMin", ParamKind.INT, label = "Captures per minute when moving", min = MIN_CAP.toDouble(), max = MAX_CAP.toDouble(), step = 1.0, default = JsonPrimitive(DEFAULT_MOVING)),
        ParamSpec("stillCapPerMin", ParamKind.INT, label = "Captures per minute when still", min = MIN_CAP.toDouble(), max = MAX_CAP.toDouble(), step = 1.0, default = JsonPrimitive(DEFAULT_STILL)),
    ))
}
