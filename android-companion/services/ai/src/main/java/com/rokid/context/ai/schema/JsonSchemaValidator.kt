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
    fun validate(schema: JsonObject, value: JsonElement): List<String> = mutableListOf<String>().also { check(schema, schema, value, "$", it) }

    internal fun errorsOf(root: JsonObject, schema: JsonObject, value: JsonElement, path: String): List<String> =
        mutableListOf<String>().also { check(root, schema, value, path, it) }

    private fun check(root: JsonObject, node: JsonObject, v: JsonElement, path: String, out: MutableList<String>) {
        val s = SchemaRefs.resolve(root, node)
        val types = SchemaRefs.typeNames(s["type"])
        if (types.isNotEmpty() && types.none { matches(it, v) }) { out += "$path: must be ${types.joinToString(" or ")}"; return }
        s["const"]?.let { if (it != v) out += "$path: must be $it" }
        (s["enum"] as? JsonArray)?.let { allowed -> if (v !in allowed) out += "$path: must be one of ${allowed.joinToString(", ")}" }
        (s["anyOf"] as? JsonArray)?.let { b -> if (b.none { errorsOf(root, it.jsonObject, v, path).isEmpty() }) out += "$path: matches none of the allowed shapes" }
        (s["oneOf"] as? JsonArray)?.let { b ->
            when (b.count { errorsOf(root, it.jsonObject, v, path).isEmpty() }) {
                0 -> out += "$path: matches none of the allowed shapes"
                1 -> Unit
                else -> out += "$path: matches more than one allowed shape"
            }
        }
        (s["allOf"] as? JsonArray)?.forEach { check(root, it.jsonObject, v, path, out) }
        when (v) {
            is JsonObject -> checkObject(root, s, v, path, out)
            is JsonArray -> checkArray(root, s, v, path, out)
            JsonNull -> Unit
            is JsonPrimitive -> if (v.isString) checkString(s, v.content, path, out) else v.doubleOrNull?.let { checkNumber(s, it, path, out) }
        }
    }

    private fun matches(type: String, v: JsonElement): Boolean = when (type) {
        "null" -> v == JsonNull
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v != JsonNull && v.isString
        "boolean" -> v is JsonPrimitive && v != JsonNull && !v.isString && v.booleanOrNull != null
        "number" -> v is JsonPrimitive && v != JsonNull && !v.isString && v.doubleOrNull != null
        "integer" -> v is JsonPrimitive && v != JsonNull && !v.isString && v.doubleOrNull?.let { it.isFinite() && it == floor(it) } == true
        else -> true
    }

    private fun checkObject(root: JsonObject, s: JsonObject, v: JsonObject, path: String, out: MutableList<String>) {
        val props = s["properties"] as? JsonObject ?: JsonObject(emptyMap())
        (s["required"] as? JsonArray)?.forEach { r -> val k = (r as JsonPrimitive).content; if (k !in v) out += "$path: missing '$k'" }
        for ((k, child) in v) {
            val ps = props[k] as? JsonObject
            when {
                ps != null -> check(root, ps, child, "$path.$k", out)
                s["additionalProperties"] is JsonObject -> check(root, s["additionalProperties"]!!.jsonObject, child, "$path.$k", out)
                (s["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false -> out += "$path: unexpected property '$k'"
            }
        }
        int(s, "minProperties")?.let { if (v.size < it) out += "$path: at least $it properties" }
        int(s, "maxProperties")?.let { if (v.size > it) out += "$path: at most $it properties" }
    }

    private fun checkArray(root: JsonObject, s: JsonObject, v: JsonArray, path: String, out: MutableList<String>) {
        (s["items"] as? JsonObject)?.let { item -> v.forEachIndexed { i, e -> check(root, item, e, "$path[$i]", out) } }
        int(s, "minItems")?.let { if (v.size < it) out += "$path: at least $it items" }
        int(s, "maxItems")?.let { if (v.size > it) out += "$path: at most $it items" }
        if ((s["uniqueItems"] as? JsonPrimitive)?.booleanOrNull == true && v.toSet().size != v.size) out += "$path: items must be unique"
    }

    private fun checkString(s: JsonObject, text: String, path: String, out: MutableList<String>) {
        val n = text.codePointCount(0, text.length)
        int(s, "minLength")?.let { if (n < it) out += "$path: at least $it characters" }
        int(s, "maxLength")?.let { if (n > it) out += "$path: at most $it characters" }
        (s["pattern"] as? JsonPrimitive)?.content?.let { p ->
            val ok = runCatching { Regex(p).containsMatchIn(text) }.getOrDefault(false)
            if (!ok) out += "$path: doesn't match the required format"
        }
    }

    private fun checkNumber(s: JsonObject, x: Double, path: String, out: MutableList<String>) {
        num(s, "minimum")?.let { if (x < it) out += "$path: must be ≥ ${fmt(it)}" }
        num(s, "maximum")?.let { if (x > it) out += "$path: must be ≤ ${fmt(it)}" }
        num(s, "exclusiveMinimum")?.let { if (x <= it) out += "$path: must be > ${fmt(it)}" }
        num(s, "exclusiveMaximum")?.let { if (x >= it) out += "$path: must be < ${fmt(it)}" }
        num(s, "multipleOf")?.let { m -> if (m > 0) { val q = x / m; if (abs(q - q.roundToLong()) > 1e-9) out += "$path: must be a multiple of ${fmt(m)}" } }
    }

    private fun int(s: JsonObject, k: String): Int? = (s[k] as? JsonPrimitive)?.intOrNull
    private fun num(s: JsonObject, k: String): Double? = (s[k] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

    internal fun fmt(d: Double): String = if (d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
}
