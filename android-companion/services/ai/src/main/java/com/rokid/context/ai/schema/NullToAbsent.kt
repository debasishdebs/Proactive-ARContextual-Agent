package com.rokid.context.ai.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Wire schemas make every optional property required-but-nullable (spec §4.4); the canonical schema says absent.
 * Turns a wire reply back into canonical shape: `null` for a non-required property is removed, recursively.
 */
object NullToAbsent {
    fun apply(schema: JsonObject, value: JsonElement): JsonElement =
        if (SchemaRefs.exceedsDepth(value)) value else walk(schema, schema, value, 0)

    private fun walk(root: JsonObject, node: JsonObject, v: JsonElement, depth: Int): JsonElement {
        if (depth > SchemaRefs.MAX_DEPTH) return v
        val s = SchemaRefs.resolve(root, node)
        val branches = (s["anyOf"] ?: s["oneOf"]) as? JsonArray
        if (branches != null && v != JsonNull) {
            val candidates = branches.map { SchemaRefs.resolve(root, it.jsonObject) }
            val normalised = candidates.map { walk(root, it, v, depth + 1) }
            val valid = candidates.indices.firstOrNull { JsonSchemaValidator.errorsOf(root, candidates[it], normalised[it], "$", depth + 1).isEmpty() }
            val byTag = candidates.indices.firstOrNull { discriminatorMatches(candidates[it], v) }
            val chosen = (valid ?: byTag)?.let { normalised[it] } ?: v
            // Keywords beside the union (properties, items) apply as well (Phase 5 T3 carry).
            val siblings = JsonObject(s - "anyOf" - "oneOf")
            return if ("properties" in siblings || "items" in siblings) walk(root, siblings, chosen, depth + 1) else chosen
        }
        return when (v) {
            is JsonObject -> {
                val props = s["properties"] as? JsonObject ?: return v
                val required = (s["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet().orEmpty()
                JsonObject(v.filterNot { (k, e) -> e == JsonNull && k in props && k !in required }
                    .mapValues { (k, e) -> (props[k] as? JsonObject)?.let { walk(root, it, e, depth + 1) } ?: e })
            }
            is JsonArray -> (s["items"] as? JsonObject)?.let { item -> JsonArray(v.map { walk(root, item, it, depth + 1) }) } ?: v
            else -> v
        }
    }

    /** A discriminated branch: its `type` property is a single-value enum or const that equals the value's `type`. */
    private fun discriminatorMatches(branch: JsonObject, v: JsonElement): Boolean {
        val tag = ((v as? JsonObject)?.get("type") as? JsonPrimitive) ?: return false
        val t = (branch["properties"] as? JsonObject)?.get("type") as? JsonObject ?: return false
        return t["const"] == tag || (t["enum"] as? JsonArray)?.singleOrNull() == tag
    }
}
