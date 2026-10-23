package com.rokid.context.canvas.helper

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * The shared schema subset (core plan section 6): `type, required, properties, additionalProperties:false, enum, items, minimum, maximum, minLength, maxLength,
 * minItems, maxItems`. NO `pattern` (bounded-regex machinery is the 3d hardening slice, LB4): a schema containing it is refused before a job is created.
 * The Python mirror (`schema_lite.py`, WP13) reads the same rows (tools/canvas-helper/protocol/schema-subset-rows.json). Error texts are fixed, never an echo.
 */
object SchemaSubset {
    val KEYWORDS: Set<String> = setOf(
        "type", "required", "properties", "additionalProperties", "enum", "items", "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems",
    )
    private val TYPES = setOf("object", "array", "string", "integer", "number", "boolean", "null")
    private val INT = Regex("-?(0|[1-9][0-9]*)")
    private const val MAX_SCHEMA_DEPTH = 16

    /** Empty = the schema is inside the subset. */
    fun validateContract(schema: JsonElement): List<String> {
        val errs = LinkedHashSet<String>()
        contract(schema, 0, errs)
        return errs.toList()
    }

    private fun contract(s: JsonElement, depth: Int, errs: MutableSet<String>) {
        if (depth > MAX_SCHEMA_DEPTH) { errs += "schema nested too deep"; return }
        if (s !is JsonObject) { errs += "schema must be an object"; return }
        for (k in s.keys) if (k !in KEYWORDS) errs += if (k == "pattern") "pattern is unsupported" else "unsupported keyword"
        val type = s["type"]
        if (type != null && !(type is JsonPrimitive && type.isString && type.content in TYPES)) errs += "unknown type"
        s["required"]?.let { r -> if (r !is JsonArray || r.any { !(it is JsonPrimitive && it.isString) }) errs += "required must list names" }
        s["properties"]?.let { p -> if (p !is JsonObject) errs += "properties must be an object" else p.values.forEach { contract(it, depth + 1, errs) } }
        s["items"]?.let { contract(it, depth + 1, errs) }
        s["enum"]?.let { if (it !is JsonArray || it.isEmpty()) errs += "enum must be a non-empty list" }
        for (k in listOf("minimum", "maximum")) s[k]?.let { if (!isNumber(it)) errs += "$k must be a number" }
        for (k in listOf("minLength", "maxLength", "minItems", "maxItems")) s[k]?.let { if (!(it is JsonPrimitive && !it.isString && INT.matches(it.content) && !it.content.startsWith("-"))) errs += "$k must be a non-negative integer" }
        val ap = s["additionalProperties"]
        if (ap != null && !(ap is JsonPrimitive && !ap.isString && ap.content == "false")) errs += "additionalProperties must be false"
        val objectSchema = (type is JsonPrimitive && type.content == "object") || s.containsKey("properties")
        if (objectSchema && ap == null) errs += "object schema needs additionalProperties false"
    }

    fun validate(schema: JsonElement, instance: JsonElement): Boolean = depthOf(instance) <= HelperProtocol.MAX_INSTANCE_DEPTH && check(schema, instance)

    private fun depthOf(e: JsonElement): Int = when (e) {
        is JsonArray -> 1 + (e.maxOfOrNull { depthOf(it) } ?: 0)
        is JsonObject -> 1 + (e.values.maxOfOrNull { depthOf(it) } ?: 0)
        else -> 0
    }

    private fun isNumber(e: JsonElement) = e is JsonPrimitive && !e.isString && e.content != "true" && e.content != "false" && e !is JsonNull && e.content.toBigDecimalOrNull() != null

    private fun check(s: JsonElement, v: JsonElement): Boolean {
        if (s !is JsonObject) return false
        s["type"]?.let { if (!typeOk((it as JsonPrimitive).content, v)) return false }
        s["enum"]?.let { en -> if ((en as JsonArray).none { sameValue(it, v) }) return false }
        if (v is JsonPrimitive && !v.isString && isNumber(v)) {
            val n = BigDecimal(v.content)
            s["minimum"]?.let { if (n < BigDecimal((it as JsonPrimitive).content)) return false }
            s["maximum"]?.let { if (n > BigDecimal((it as JsonPrimitive).content)) return false }
        }
        if (v is JsonPrimitive && v.isString) {
            val len = v.content.codePointCount(0, v.content.length)
            s["minLength"]?.let { if (len < intOf(it)) return false }
            s["maxLength"]?.let { if (len > intOf(it)) return false }
        }
        if (v is JsonArray) {
            s["minItems"]?.let { if (v.size < intOf(it)) return false }
            s["maxItems"]?.let { if (v.size > intOf(it)) return false }
            s["items"]?.let { sub -> if (v.any { !check(sub, it) }) return false }
        }
        if (v is JsonObject) {
            (s["required"] as? JsonArray)?.let { r -> if (r.any { !v.containsKey((it as JsonPrimitive).content) }) return false }
            val props = s["properties"] as? JsonObject
            props?.forEach { (k, sub) -> v[k]?.let { if (!check(sub, it)) return false } }
            if (s["additionalProperties"]?.let { (it as JsonPrimitive).content == "false" } == true && v.keys.any { props == null || !props.containsKey(it) }) return false
        }
        return true
    }

    private fun intOf(e: JsonElement) = (e as JsonPrimitive).content.toInt()

    private fun typeOk(t: String, v: JsonElement): Boolean = when (t) {
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v.isString
        "boolean" -> v is JsonPrimitive && !v.isString && (v.content == "true" || v.content == "false")
        "null" -> v is JsonNull
        "integer" -> v is JsonPrimitive && !v.isString && INT.matches(v.content)
        "number" -> isNumber(v)
        else -> false
    }

    private fun sameValue(a: JsonElement, b: JsonElement): Boolean =
        if (isNumber(a) && isNumber(b)) BigDecimal((a as JsonPrimitive).content).compareTo(BigDecimal((b as JsonPrimitive).content)) == 0 else a == b
}
