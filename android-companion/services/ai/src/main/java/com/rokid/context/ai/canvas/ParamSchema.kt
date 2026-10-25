package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** The param kinds of the v2 descriptors; [wire] is the `kind` the editor reads (int, bool and text are the slice 2 kinds). */
enum class ParamKind(val wire: String) { INT("int"), NUMBER("number"), ENUM("enum"), BOOL("bool"), TEXT("text"), TABLE("table"), GRAPH("graph") }

/**
 * One table column. [blankable] accepts null or absent ("unchanged"); [allowZero] accepts 0 ("inherit") next to the [min]..[max] range, and null too (a blank gap is 0, S68).
 * [group] is the heading shared by several columns.
 */
data class ColSpec(
    val name: String, val label: String, val kind: ParamKind, val min: Double? = null, val max: Double? = null,
    val blankable: Boolean = false, val allowZero: Boolean = false, val group: String? = null,
)

data class TableSpec(val rows: List<String>, val cols: List<ColSpec>)

data class ParamSpec(
    val name: String, val kind: ParamKind, val label: String = "", val min: Double? = null, val max: Double? = null, val step: Double? = null,
    val default: JsonPrimitive? = null, val options: List<String> = emptyList(), val maxLen: Int = 0, val sweep: Boolean = false, val table: TableSpec? = null,
)

/** The declared params of a type. [strict] refuses a param that is not declared; slice 2 types keep their own checks and are not strict. */
data class ParamSchema(val specs: List<ParamSpec>, val strict: Boolean = true) {
    companion object { val NONE = ParamSchema(emptyList(), strict = false) }
}

/** The capture-half descriptors: bounds from slice 3 section 8, defaults from the shipped `GlanceParams` and `KeyframeGate`. */
object CaptureParams {
    private fun num(name: String, min: Double, max: Double, default: Double, step: Double, label: String = "") =
        ParamSpec(name, ParamKind.NUMBER, label = label, min = min, max = max, step = step, default = JsonPrimitive(default), sweep = true)

    private fun int(name: String, min: Int, max: Int, default: Int, step: Int, label: String = "") =
        ParamSpec(name, ParamKind.INT, label = label, min = min.toDouble(), max = max.toDouble(), step = step.toDouble(), default = JsonPrimitive(default), sweep = true)

    // the labels are what the expanded (composite) cards and the inspector show; the units are the compiler's own (rad/s, ms, bits of 64)
    val headMotion = ParamSchema(listOf(
        num("onsetRadS", 0.4, 1.0, 0.6, 0.05, "Starts above (rad/s)"), num("releaseRadS", 0.2, 1.0, 0.3, 0.05, "Holds above (rad/s)"),
        int("bridgeMs", 100, 500, 250, 25, "Bridge gaps (ms)"), int("minBoutMs", 100, 800, 300, 25, "Min length (ms)"), num("minBoutRad", 0.1, 0.8, 0.26, 0.02, "Min turn (rad)"),
    ))
    val settle = ParamSchema(listOf(int("settleMs", 1_000, 3_000, 1_500, 100, "Quiet for (ms)")))
    val novelty = ParamSchema(listOf(int("noveltyWindowMs", 5_000, 60_000, 20_000, 1_000, "Window (ms)"), num("noveltyMinRad", 0.1, 0.8, 0.3, 0.02, "Min turn (rad)")))
    val rateCap = ParamSchema(listOf(int("bigChange", 16, 32, 22, 1, "Big change (bits)"), num("bigTurnRad", 0.5, 1.5, 0.8, 0.05, "Big turn (rad)")))
    val captureImage = ParamSchema(listOf(
        int("hamming", 6, 16, 10, 1, "Different above (bits)"),
        ParamSpec("ringCompare", ParamKind.ENUM, label = "Compare with", options = listOf("last", "all"), default = JsonPrimitive("all"), sweep = true),
    ))

    /** Gate features and the one comparison each allows (S78: onset is strict, bouts are inclusive). */
    val thresholdOps = mapOf("gyroPeak" to ">", "boutMs" to ">=", "boutRad" to ">=")
    val thresholdMax = mapOf("gyroPeak" to 10.0, "boutMs" to 5_000.0, "boutRad" to 10.0)
    val threshold = ParamSchema(listOf(
        ParamSpec("feature", ParamKind.ENUM, options = thresholdOps.keys.toList(), default = JsonPrimitive("gyroPeak")),
        ParamSpec("op", ParamKind.ENUM, options = listOf(">", ">="), default = JsonPrimitive(">")),
        ParamSpec("value", ParamKind.NUMBER, min = 0.0, max = 5_000.0, default = JsonPrimitive(0.6), sweep = true),
    ))

    /** Hub fusion has no tunables in slice 3a-CORE (the compiler says so); the descriptor accepts and ignores nothing it does not check. */
    val hub = ParamSchema(emptyList(), strict = false)

    private fun signalParams(domain: String) = ParamSchema(listOf(
        ParamSpec("name", ParamKind.ENUM, options = listOf("ctx", "intent"), default = JsonPrimitive("ctx")),
        ParamSpec("domain", ParamKind.ENUM, options = NodeTypes.SIGNAL_DOMAINS.map { it.name }, default = JsonPrimitive(domain)),
    ))
    val signalIn = signalParams("RT_GLASSES")
    val signalOut = signalParams("RT_PHONE")

    /** Editable rows only: WALKING and UNKNOWN are locked (S32), and there is no pause row. */
    val policyRows = listOf("REST", "VEHICLE_PASSENGER", "VEHICLE_DRIVING", "FLIGHT")

    /** The shared heading of the four gap columns (S84 (3)): these are timer photo attempts only. */
    const val GAP_GROUP = "timer photo gap (settled/quiet/walk/still cap)"

    private fun gap(name: String, label: String) =
        ColSpec(name, "timer photo gap ($label)", ParamKind.INT, min = 5.0, max = 300.0, allowZero = true, group = GAP_GROUP)

    val captureRate = ParamSchema(listOf(
        ParamSpec("rows", ParamKind.TABLE, label = "Capture frequency by activity", table = TableSpec(policyRows, listOf(
            ColSpec("capPerMin", "cap per minute", ParamKind.INT, min = 1.0, max = 4.0, blankable = true),
            gap("settledGapS", "settled"), gap("quietGapS", "quiet"), gap("walkGapS", "walk"), gap("stillCapGapS", "still cap"),
            ColSpec("forceSettle", "force settle", ParamKind.BOOL), ColSpec("nudges", "nudges", ParamKind.BOOL),
        ))),
    ))
}

/** The expression node descriptor: one structured `graph` param, no per-node generated form (X5 draws its own editor). */
object ExprParams {
    val guard = ParamSchema(listOf(ParamSpec("graph", ParamKind.GRAPH, label = "Expression graph")))

    /** A value program (3c E2a): the graph ends in a number that tunes ONE field of ONE editable row (never WALKING or UNKNOWN), clamped to the row's own bounds; an error or a warming window keeps the row default. */
    val value = ParamSchema(
        listOf(
            ParamSpec("graph", ParamKind.GRAPH, label = "Expression graph"),
            ParamSpec("row", ParamKind.ENUM, options = com.rokid.context.capture.plan.PlanState.entries.map { it.name }, default = JsonPrimitive("REST"), label = "Row"),
            ParamSpec("field", ParamKind.ENUM, options = com.rokid.context.capture.plan.ExprValues.FIELDS, default = JsonPrimitive("capPerMin"), label = "Tunes"),
        ),
    )
}

/** Validates a node's params against its [ParamSchema]. Problems are fixed texts naming the node id, the param and the bound. */
object ParamCheck {
    fun problems(id: String, t: NodeType, params: JsonObject): List<String> {
        val schema = t.params
        if (schema.specs.isEmpty() && !schema.strict) return emptyList()
        val out = mutableListOf<String>()
        val byName = schema.specs.associateBy { it.name }
        if (schema.strict) params.keys.filter { it !in byName }.forEach { out += "node $id: unknown param ${shown(it)}" }
        for (spec in schema.specs) {
            val v = params[spec.name] ?: continue
            // a signal node's domain has its own text (see PipelineValidator)
            if (t.domainFromParams && spec.name == "domain") continue
            out += if (spec.kind == ParamKind.GRAPH) graph(id, t, v) else one(id, spec, v)
        }
        if (out.isEmpty()) cross(id, t, params, out)
        return out
    }

    private fun graph(id: String, t: NodeType, v: JsonElement): List<String> {
        val role = if (t.type == NodeTypes.EXPR_VALUE) com.rokid.context.ai.canvas.plan.ExprRole.VALUE else com.rokid.context.ai.canvas.plan.ExprRole.GUARD
        val (p, bad) = com.rokid.context.ai.canvas.plan.ExprProgram.parse(role, v)
        return (bad + (p?.check()?.problems ?: emptyList())).map { "node $id: $it" }
    }

    private fun one(id: String, s: ParamSpec, v: JsonElement): List<String> = when (s.kind) {
        ParamKind.INT -> {
            val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.toDouble()
            if (n == null || !inRange(n, s.min, s.max)) listOf("node $id: ${s.name} must be between ${fmt(s.min)} and ${fmt(s.max)}") else emptyList()
        }
        ParamKind.NUMBER -> {
            val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
            if (n == null || !inRange(n, s.min, s.max)) listOf("node $id: ${s.name} must be between ${fmt(s.min)} and ${fmt(s.max)}") else emptyList()
        }
        ParamKind.ENUM -> {
            val c = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (c == null || c !in s.options) listOf("node $id: ${s.name} must be one of ${s.options.joinToString(", ")}") else emptyList()
        }
        ParamKind.BOOL -> if (isBool(v)) emptyList() else listOf("node $id: ${s.name} must be true or false")
        ParamKind.TEXT -> {
            val c = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (c == null || (s.maxLen > 0 && c.length > s.maxLen)) listOf("node $id: ${s.name} too long") else emptyList()
        }
        ParamKind.TABLE -> table(id, s, v)
        ParamKind.GRAPH -> emptyList()   // checked with the node type in graph()
    }

    private fun table(id: String, s: ParamSpec, v: JsonElement): List<String> {
        val spec = s.table ?: return emptyList()
        val rows = v as? JsonObject ?: return listOf("node $id: ${s.name} must be an object")
        val out = mutableListOf<String>()
        val cols = spec.cols.associateBy { it.name }
        for ((rowName, rowV) in rows) {
            if (rowName !in spec.rows) { out += "node $id: unknown row ${shown(rowName)}"; continue }
            val row = rowV as? JsonObject
            if (row == null) { out += "node $id: $rowName must be an object"; continue }
            for ((colName, cell) in row) {
                val c = cols[colName]
                if (c == null) { out += "node $id: $rowName unknown column ${shown(colName)}"; continue }
                out += cell(id, rowName, c, cell)
            }
            if (rowName == "VEHICLE_DRIVING" && (row["nudges"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true") {
                out += "node $id: VEHICLE_DRIVING nudges are locked off"
            }
        }
        return out
    }

    private fun cell(id: String, row: String, c: ColSpec, v: JsonElement): List<String> {
        val head = "node $id: $row ${c.name} must be"
        // a blank gap (null) is 0 = inherit (S68); only a cell that is neither blankable nor a gap must be set
        if (v is JsonNull) return if (c.blankable || c.allowZero) emptyList() else listOf("$head set")
        return when (c.kind) {
            ParamKind.BOOL -> if (isBool(v)) emptyList() else listOf("$head true or false")
            else -> {
                val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                val ok = n != null && ((c.allowZero && n == 0) || inRange(n.toDouble(), c.min, c.max))
                if (ok) emptyList()
                else listOf("$head " + (if (c.blankable) "blank or " else if (c.allowZero) "0 or " else "") + "between ${fmt(c.min)} and ${fmt(c.max)}")
            }
        }
    }

    private fun cross(id: String, t: NodeType, params: JsonObject, out: MutableList<String>) {
        when (t.type) {
            NodeTypes.EXPR_GUARD, NodeTypes.EXPR_VALUE -> if ("graph" !in params) out += "node $id: ${com.rokid.context.ai.canvas.plan.ExprCode.EXPR_NO_GRAPH}: graph is missing"
            NodeTypes.IMU_HEAD_MOTION -> {
                val onset = dbl(params, "onsetRadS", 0.6)
                if (dbl(params, "releaseRadS", 0.3) > onset) out += "node $id: releaseRadS must not exceed onsetRadS"
            }
            NodeTypes.GATE_THRESHOLD -> {
                val feature = str(params, "feature", "gyroPeak")
                if (str(params, "op", ">") != CaptureParams.thresholdOps[feature]) out += "node $id: op not allowed for feature $feature"
                else if (dbl(params, "value", 0.6) > (CaptureParams.thresholdMax[feature] ?: 0.0)) out += "node $id: value out of range for feature $feature"
            }
        }
    }

    private fun dbl(p: JsonObject, k: String, d: Double) = (p[k] as? JsonPrimitive)?.doubleOrNull ?: d
    private fun str(p: JsonObject, k: String, d: String) = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: d
    private fun inRange(n: Double, min: Double?, max: Double?) = (min == null || n >= min) && (max == null || n <= max)
    private fun isBool(v: JsonElement) = (v as? JsonPrimitive)?.takeIf { !it.isString }?.content in listOf("true", "false")
    private fun fmt(d: Double?) = if (d == null) "-" else if (d == Math.floor(d)) d.toLong().toString() else d.toString()
    private fun shown(s: String) = if (s.length > 40) s.take(40) + "..." else s
}
