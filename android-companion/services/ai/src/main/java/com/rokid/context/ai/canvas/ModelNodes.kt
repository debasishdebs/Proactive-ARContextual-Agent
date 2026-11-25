package com.rokid.context.ai.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Canvas v2.2 (design 2026-11-24, section 2.2): the model, classifier, router and rule node types. They are registered ONLY while `NodeTypes.modelNodesEnabled()` is true (Developer flag `canvasModelNodes`,
 * default OFF, fail closed): with the flag off a graph that holds one is "unknown type" for the validator and the palette. Every one is `liveable = false` (a test or an experiment, never published).
 * Built here: `model.classify`, `model.route`, `rule.table`. Parked, not registered (no executor): `model.text`, `model.vision`, `embed.match`, `rule.when`, `rule.all/any/not`, `rule.debounce/rate`.
 */
object ModelNodeTypes {
    const val CLASSIFY = "model.classify"
    const val ROUTE = "model.route"
    const val RULE_TABLE = "rule.table"

    const val ELSE = "other"

    private val modelInputs = setOf(PortType.PHOTOS, PortType.TEXT, PortType.JSON, PortType.EXTRACTION, PortType.SCENES, PortType.MOMENTS)

    /** Params shared by the model nodes: backend (auto | online | offline), what to do when no backend is available (error | skip) and a latency ceiling. */
    private val modelParams = ParamSchema(listOf(
        ParamSpec("labels", ParamKind.TEXT, label = "Labels (comma separated, 2 to 12)", maxLen = 400),
        ParamSpec("instruction", ParamKind.TEXT, label = "Instruction (optional)", maxLen = 400),
        ParamSpec("threshold", ParamKind.NUMBER, label = "Minimum confidence", min = 0.0, max = 1.0, step = 0.05, default = JsonPrimitive(0.6), sweep = true),
        ParamSpec("images", ParamKind.BOOL, label = "Send the photo", default = JsonPrimitive(false)),
        ParamSpec("backend", ParamKind.ENUM, label = "Backend", options = listOf("auto", "online", "offline"), default = JsonPrimitive("auto")),
        ParamSpec("onUnavailable", ParamKind.ENUM, label = "When no backend", options = listOf("error", "skip"), default = JsonPrimitive("error")),
        ParamSpec("maxLatencyMs", ParamKind.INT, label = "Max latency (ms)", min = 1_000.0, max = 120_000.0, step = 1_000.0, default = JsonPrimitive(30_000)),
    ))

    val ruleTableSchema = ParamSchema(listOf(ParamSpec("rows", ParamKind.TEXT, label = "Rows (JSON list)", maxLen = 4_000)))

    val all: List<NodeType> = listOf(
        NodeType(CLASSIFY, "Classify", modelInputs, PortType.JSON, branches = { labelBranches(it) }, sinceSchema = 2, category = Category.AI_CLASSIFY, executor = ExecutorKind.PROMPT,
            short = "classify", params = modelParams, liveable = false, cost = CostSpec(modelCalls = 1), family = Family.CONDITION,
            hint = "One label per item with a confidence; below the minimum the label is none. Never names people."),
        NodeType(ROUTE, "Route by model", modelInputs, PortType.JSON, branches = { labelBranches(it) }, sinceSchema = 2, category = Category.ROUTING, executor = ExecutorKind.PROMPT,
            short = "mroute", params = modelParams, liveable = false, cost = CostSpec(modelCalls = 1), family = Family.CONDITION,
            hint = "Like Classify, and the item travels on in the output next to the label."),
        NodeType(RULE_TABLE, "Rule table", setOf(PortType.JSON, PortType.NUMBER), PortType.JSON, branches = { RuleTable.branches(it) }, sinceSchema = 2, category = Category.CHECKS,
            executor = ExecutorKind.TABLE, short = "rules", params = ruleTableSchema, liveable = false, family = Family.CONDITION,
            hint = "First matching row wins; no model, no network."),
    )

    /** The labels of a classify or route node plus `none` (always present). Invalid or missing labels give just `none`; the validator reports the problem. */
    fun labelBranches(params: JsonObject): List<String> = ClassifySpec.labels(params).let { if (it.size >= ClassifySpec.MIN_LABELS) it + ClassifySpec.NONE else listOf(ClassifySpec.NONE) }

    /** Extra param problems of the types above (the generic bounds are checked by `ParamCheck`). */
    fun paramProblems(id: String, n: PipelineNode): List<String> = when (n.type) {
        CLASSIFY, ROUTE -> {
            val raw = ClassifySpec.rawLabels(n.params)
            val ok = ClassifySpec.labels(n.params)
            if (raw.size != ok.size || ok.size !in ClassifySpec.MIN_LABELS..ClassifySpec.MAX_LABELS) listOf("node $id: labels must be 2..12 distinct short words (not none)") else emptyList()
        }
        RULE_TABLE -> if (RuleTable.parse(n.params) == null) listOf("node $id: rows must be 1..24 valid rules") else emptyList()
        else -> emptyList()
    }
}

/** Pure parts of a classification: the label set, the output schema, the prompt text and the normalisation of the model's answer. No identity: the verdict holds a label and a confidence and nothing else. */
object ClassifySpec {
    const val NONE = "none"
    const val MIN_LABELS = 2
    const val MAX_LABELS = 12
    const val MAX_LABEL_LEN = 24
    const val DEFAULT_THRESHOLD = 0.6
    private val LABEL = Regex("[a-z0-9][a-z0-9 _-]{0,23}")

    fun rawLabels(params: JsonObject): List<String> =
        ((params["labels"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** Valid, distinct labels in order (a bad or repeated one is dropped; `none` is reserved). */
    fun labels(params: JsonObject): List<String> = rawLabels(params).filter { LABEL.matches(it) && it != NONE }.distinct()

    fun threshold(params: JsonObject): Double = ((params["threshold"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: DEFAULT_THRESHOLD).coerceIn(0.0, 1.0)

    fun schema(labels: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("label", buildJsonObject { put("type", "string"); put("enum", JsonArray((labels + NONE).map { JsonPrimitive(it) })) })
            put("confidence", buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1) })
        })
        put("required", buildJsonArray { add(JsonPrimitive("label")); add(JsonPrimitive("confidence")) })
        put("additionalProperties", false)
    }

    /** The fixed text of the request. The item itself is added by the executor inside a data block. Never asks for, or accepts, a name. */
    fun instruction(labels: List<String>, extra: String?): String =
        "Classify the item into exactly one of these labels: ${labels.joinToString(", ")}, or ${NONE} when none fits. Answer with JSON {\"label\": ..., \"confidence\": 0..1}. " +
            "Describe the kind of thing only: never name, identify or guess who a person is." + (extra?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

    data class Verdict(val label: String, val confidence: Double, val belowThreshold: Boolean)

    /** Normalises the parsed answer: an unknown label or a missing or non-numeric confidence becomes `none`; below [threshold] becomes `none` (and says so). Null when the answer is not an object. */
    fun normalize(answer: JsonElement?, labels: List<String>, threshold: Double): Verdict? {
        val o = answer as? JsonObject ?: return null
        val label = (o["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()
        val conf = ((o["confidence"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull)?.takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0)
        if (label == null || conf == null || label !in labels) return Verdict(NONE, conf ?: 0.0, false)
        if (conf < threshold) return Verdict(NONE, conf, true)
        return Verdict(label, conf, false)
    }

    fun toJson(v: Verdict): JsonObject = buildJsonObject { put("label", v.label); put("confidence", v.confidence) }
}

/**
 * `rule.table`: rows of `{field, op, value, branch}`; the FIRST row that matches the item's JSON decides the branch, no match gives `other`. Fields are dotted paths into the item (`a.b`, `list.0`). Pure, no model, no network.
 * Ops: == != > >= < <= in contains exists. This is a table, not a second expression language (the glasses `ExprProgram` is untouched).
 */
object RuleTable {
    const val MAX_ROWS = 24
    private val OPS = setOf("==", "!=", ">", ">=", "<", "<=", "in", "contains", "exists")
    private val BRANCH = Regex("[A-Za-z][A-Za-z0-9_]{0,23}")
    data class Row(val field: String, val op: String, val value: JsonElement?, val branch: String)

    fun parse(params: JsonObject): List<Row>? {
        val text = (params["rows"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val arr = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        if (arr.size !in 1..MAX_ROWS) return null
        return arr.map { e ->
            val o = e as? JsonObject ?: return null
            val field = (o["field"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
            val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in OPS } ?: return null
            val branch = (o["branch"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { BRANCH.matches(it) && it != ModelNodeTypes.ELSE } ?: return null
            val value = o["value"]
            if (op != "exists" && (value == null || value is JsonNull)) return null
            if (op == "in" && value !is JsonArray) return null
            Row(field, op, value, branch)
        }
    }

    fun branches(params: JsonObject): List<String> = (parse(params)?.map { it.branch }?.distinct() ?: emptyList()) + ModelNodeTypes.ELSE

    fun pick(rows: List<Row>, item: JsonElement): String = rows.firstOrNull { matches(it, item) }?.branch ?: ModelNodeTypes.ELSE

    fun path(item: JsonElement, field: String): JsonElement? {
        var cur: JsonElement? = item
        for (part in field.split('.')) {
            cur = when (cur) {
                is JsonObject -> cur[part]
                is JsonArray -> part.toIntOrNull()?.let { cur.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return cur
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun txt(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun same(a: JsonElement?, b: JsonElement?): Boolean = when {
        a == null || b == null -> false
        num(a) != null && num(b) != null -> num(a) == num(b)
        (a as? JsonPrimitive)?.booleanOrNull != null || (b as? JsonPrimitive)?.booleanOrNull != null -> (a as? JsonPrimitive)?.booleanOrNull == (b as? JsonPrimitive)?.booleanOrNull
        else -> txt(a) != null && txt(a) == txt(b)
    }

    fun matches(r: Row, item: JsonElement): Boolean {
        val v = path(item, r.field)
        return when (r.op) {
            "exists" -> v != null && v !is JsonNull
            "==" -> same(v, r.value)
            "!=" -> v != null && !same(v, r.value)
            ">" -> num(v)?.let { a -> num(r.value)?.let { b -> a > b } } == true
            ">=" -> num(v)?.let { a -> num(r.value)?.let { b -> a >= b } } == true
            "<" -> num(v)?.let { a -> num(r.value)?.let { b -> a < b } } == true
            "<=" -> num(v)?.let { a -> num(r.value)?.let { b -> a <= b } } == true
            "in" -> v != null && (r.value as? JsonArray)?.any { same(v, it) } == true
            "contains" -> txt(v)?.let { s -> txt(r.value)?.let { t -> s.contains(t, ignoreCase = true) } } == true
            else -> false
        }
    }
}
