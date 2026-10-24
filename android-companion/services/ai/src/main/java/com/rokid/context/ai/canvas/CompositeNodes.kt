package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The inner flow of a composite (black-box) node: a DESCRIPTION for the editor, never executed. `kind` is a drawing hint
 * (source, op, const, branch). Values live in the outer node's [ParamSchema]; [exposed] says which inner box owns which param.
 */
data class InnerNode(val id: String, val label: String, val kind: String)
data class InnerWire(val from: String, val to: String, val port: String = "")
data class InnerExposed(val param: String, val node: String)
data class InnerFlow(val nodes: List<InnerNode>, val wires: List<InnerWire>, val exposed: List<InnerExposed>)

/**
 * The rule of `gate.local_critic`, parameterised. At the defaults it is exactly `ExtractFollowUp.locallyTrusted(CriticWorker(), ..)`:
 * no facts is trusted; else the MEAN confidence is trusted when it is not below [DEFAULT_CUTOFF] (`CriticWorker` uses this same constant).
 */
object LocalTrust {
    const val DEFAULT_CUTOFF = 0.70
    const val DEFAULT_AGGREGATE = "mean"
    const val DEFAULT_OPERATOR = ">="
    val AGGREGATES = listOf("mean", "min", "max")
    val OPERATORS = listOf(">=", ">")

    data class Settings(val aggregate: String = DEFAULT_AGGREGATE, val operator: String = DEFAULT_OPERATOR, val cutoff: Double = DEFAULT_CUTOFF)

    /** The params of a node; an absent or invalid one falls back to its default (the validator reports invalid ones). */
    fun settings(params: JsonObject): Settings {
        fun str(k: String) = (params[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val cut = (params["cutoff"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        return Settings(
            str("aggregate")?.takeIf { it in AGGREGATES } ?: DEFAULT_AGGREGATE,
            str("operator")?.takeIf { it in OPERATORS } ?: DEFAULT_OPERATOR,
            cut?.takeIf { it.isFinite() } ?: DEFAULT_CUTOFF,
        )
    }

    fun decide(confidences: List<Double>, params: JsonObject): Boolean = decide(confidences, settings(params))

    fun decide(confidences: List<Double>, s: Settings): Boolean {
        if (confidences.isEmpty()) return true
        val c = when (s.aggregate) {
            "min" -> confidences.min()
            "max" -> confidences.max()
            else -> confidences.average()
        }
        // written as the negation of the strict "below": `>=` is today's `!(c < cutoff)` (also for NaN)
        return if (s.operator == ">") !(c <= s.cutoff) else !(c < s.cutoff)
    }

    val schema = ParamSchema(listOf(
        ParamSpec("aggregate", ParamKind.ENUM, label = "Combine the fact confidences by", options = AGGREGATES, default = JsonPrimitive(DEFAULT_AGGREGATE)),
        ParamSpec("operator", ParamKind.ENUM, label = "Trusted when confidence is", options = OPERATORS, default = JsonPrimitive(DEFAULT_OPERATOR)),
        ParamSpec("cutoff", ParamKind.NUMBER, label = "Cutoff", min = 0.0, max = 1.0, step = 0.05, default = JsonPrimitive(DEFAULT_CUTOFF)),
    ), strict = false) // strict stays off: a saved pipeline may carry other keys, and the type had no schema before

    val inner = InnerFlow(
        nodes = listOf(
            InnerNode("facts", "Facts in (none: trusted)", "source"),
            InnerNode("aggregate", "Combine confidences", "op"),
            InnerNode("cutoff", "Cutoff", "const"),
            InnerNode("compare", "Compare", "op"),
            InnerNode("trusted", "trusted", "branch"),
            InnerNode("unsure", "unsure", "branch"),
        ),
        wires = listOf(
            InnerWire("facts", "aggregate"), InnerWire("aggregate", "compare", "value"), InnerWire("cutoff", "compare", "cutoff"),
            InnerWire("compare", "trusted", "yes"), InnerWire("compare", "unsure", "no"),
        ),
        exposed = listOf(InnerExposed("aggregate", "aggregate"), InnerExposed("operator", "compare"), InnerExposed("cutoff", "cutoff")),
    )
}
