package com.rokid.context.ai.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Validates a value against the canonical (full) schema — the only schema validation ever uses (spec §4.4). Every
 * stripped wire constraint is enforced here. Messages name the path and the rule, never echo the value.
 */
object JsonSchemaValidator {
    private const val MAX_ERRORS = 20
    private const val MAX_KEY = 32
    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex?>()

    fun validate(schema: JsonObject, value: JsonElement): List<String> {
        if (SchemaRefs.exceedsDepth(value)) return listOf("$: nested too deeply")
        return mutableListOf<String>().also { check(schema, schema, value, "$", it, 0) }
    }

    /** Errors of [value] against one branch. The caller has already bounded the value's depth. */
    internal fun errorsOf(root: JsonObject, schema: JsonObject, value: JsonElement, path: String, depth: Int = 0): List<String> =
        mutableListOf<String>().also { check(root, schema, value, path, it, depth) }

    private fun MutableList<String>.report(msg: String) { if (size < MAX_ERRORS) add(msg) }

    /** A model-controlled key, shortened so messages and paths stay bounded. */
    private fun key(k: String): String = if (k.length > MAX_KEY) k.take(MAX_KEY) + "…" else k

    private fun check(root: JsonObject, node: JsonObject, v: JsonElement, path: String, out: MutableList<String>, depth: Int) {
        // Bounds both value nesting and schema recursion (a oneOf/anyOf cycle through $ref).
        if (depth > SchemaRefs.MAX_DEPTH) { out.report("$path: nested too deeply"); return }
        if (out.size >= MAX_ERRORS) return
        val s = SchemaRefs.resolve(root, node)
        val types = SchemaRefs.typeNames(s["type"])
        if (types.isNotEmpty() && types.none { matches(it, v) }) { out.report("$path: must be ${types.joinToString(" or ")}"); return }
        s["const"]?.let { if (it != v) out.report("$path: must be $it") }
        (s["enum"] as? JsonArray)?.let { allowed -> if (v !in allowed) out.report("$path: must be one of ${allowed.joinToString(", ")}") }
        (s["anyOf"] as? JsonArray)?.let { b ->
            if (b.none { errorsOf(root, it.jsonObject, v, path, depth + 1).isEmpty() }) out.report("$path: matches none of the allowed shapes")
        }
        (s["oneOf"] as? JsonArray)?.let { b ->
            when (b.count { errorsOf(root, it.jsonObject, v, path, depth + 1).isEmpty() }) {
                0 -> out.report("$path: matches none of the allowed shapes")
                1 -> Unit
                else -> out.report("$path: matches more than one allowed shape")
            }
        }
        (s["allOf"] as? JsonArray)?.forEach { check(root, it.jsonObject, v, path, out, depth + 1) }
        when (v) {
            is JsonObject -> checkObject(root, s, v, path, out, depth)
            is JsonArray -> checkArray(root, s, v, path, out, depth)
            JsonNull -> Unit
            is JsonPrimitive -> if (v.isString) checkString(s, v.content, path, out) else v.doubleOrNull?.let { checkNumber(s, it, path, out) }
        }
    }

    private const val MAX_SAFE_INTEGER = 9007199254740992.0
    private const val MAX_SAFE_LONG = 9007199254740992L

    private fun matches(type: String, v: JsonElement): Boolean = when (type) {
        "null" -> v == JsonNull
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v != JsonNull && v.isString
        "boolean" -> v is JsonPrimitive && v != JsonNull && !v.isString && v.booleanOrNull != null
        "number" -> v is JsonPrimitive && v != JsonNull && !v.isString && v.doubleOrNull?.isFinite() == true
        "integer" -> v is JsonPrimitive && v != JsonNull && !v.isString &&
            (v as JsonPrimitive).let { p ->
                // A plain integer literal is judged exactly (a double rounds 2^53+1 down to 2^53).
                p.content.toLongOrNull()?.let { abs(it) <= MAX_SAFE_LONG }
                    ?: (p.doubleOrNull?.let { it.isFinite() && it == floor(it) && abs(it) <= MAX_SAFE_INTEGER } == true)
            }
        else -> true
    }

    private fun checkObject(root: JsonObject, s: JsonObject, v: JsonObject, path: String, out: MutableList<String>, depth: Int) {
        val props = s["properties"] as? JsonObject ?: JsonObject(emptyMap())
        (s["required"] as? JsonArray)?.forEach { r -> val k = (r as JsonPrimitive).content; if (k !in v) out.report("$path: missing '$k'") }
        for ((k, child) in v) {
            val ps = props[k] as? JsonObject
            when {
                ps != null -> check(root, ps, child, "$path.${key(k)}", out, depth + 1)
                s["additionalProperties"] is JsonObject -> check(root, s["additionalProperties"]!!.jsonObject, child, "$path.${key(k)}", out, depth + 1)
                (s["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false -> out.report("$path: unexpected property '${key(k)}'")
            }
        }
        int(s, "minProperties")?.let { if (v.size < it) out.report("$path: at least $it properties") }
        int(s, "maxProperties")?.let { if (v.size > it) out.report("$path: at most $it properties") }
    }

    private fun checkArray(root: JsonObject, s: JsonObject, v: JsonArray, path: String, out: MutableList<String>, depth: Int) {
        (s["items"] as? JsonObject)?.let { item -> v.forEachIndexed { i, e -> if (out.size < MAX_ERRORS) check(root, item, e, "$path[$i]", out, depth + 1) } }
        int(s, "minItems")?.let { if (v.size < it) out.report("$path: at least $it items") }
        int(s, "maxItems")?.let { if (v.size > it) out.report("$path: at most $it items") }
        if ((s["uniqueItems"] as? JsonPrimitive)?.booleanOrNull == true && v.toSet().size != v.size) out.report("$path: items must be unique")
    }

    private fun checkString(s: JsonObject, text: String, path: String, out: MutableList<String>) {
        val n = text.codePointCount(0, text.length)
        var lengthOk = true
        int(s, "minLength")?.let { if (n < it) { lengthOk = false; out.report("$path: at least $it characters") } }
        int(s, "maxLength")?.let { if (n > it) { lengthOk = false; out.report("$path: at most $it characters") } }
        if (!lengthOk) return
        (s["pattern"] as? JsonPrimitive)?.content?.let { p ->
            val re = patterns.getOrPut(p) { runCatching { Regex(p) }.getOrNull() }
            if (re == null || !re.containsMatchIn(text)) out.report("$path: doesn't match the required format")
        }
    }

    private fun checkNumber(s: JsonObject, x: Double, path: String, out: MutableList<String>) {
        num(s, "minimum")?.let { if (x < it) out.report("$path: must be ≥ ${fmt(it)}") }
        num(s, "maximum")?.let { if (x > it) out.report("$path: must be ≤ ${fmt(it)}") }
        num(s, "exclusiveMinimum")?.let { if (x <= it) out.report("$path: must be > ${fmt(it)}") }
        num(s, "exclusiveMaximum")?.let { if (x >= it) out.report("$path: must be < ${fmt(it)}") }
        num(s, "multipleOf")?.let { m -> if (m > 0) { val q = x / m; if (abs(q - q.roundToLong()) > 1e-9) out.report("$path: must be a multiple of ${fmt(m)}") } }
    }

    private fun int(s: JsonObject, k: String): Int? = (s[k] as? JsonPrimitive)?.intOrNull
    private fun num(s: JsonObject, k: String): Double? = (s[k] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

    internal fun fmt(d: Double): String = if (d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
}
