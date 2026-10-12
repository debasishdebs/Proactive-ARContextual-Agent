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
    fun apply(schema: JsonObject, value: JsonElement): JsonElement = walk(schema, schema, value)

    private fun walk(root: JsonObject, node: JsonObject, v: JsonElement): JsonElement {
        val s = SchemaRefs.resolve(root, node)
        val branches = (s["anyOf"] ?: s["oneOf"]) as? JsonArray
        if (branches != null && v != JsonNull) {
            val candidates = branches.map { SchemaRefs.resolve(root, it.jsonObject) }
            val normalised = candidates.map { walk(root, it, v) }
            val valid = candidates.indices.firstOrNull { JsonSchemaValidator.errorsOf(root, candidates[it], normalised[it], "$").isEmpty() }
            val byTag = candidates.indices.firstOrNull { discriminatorMatches(candidates[it], v) }
            return (valid ?: byTag)?.let { normalised[it] } ?: v
        }
        return when (v) {
            is JsonObject -> {
                val props = s["properties"] as? JsonObject ?: return v
                val required = (s["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet().orEmpty()
                JsonObject(v.filterNot { (k, e) -> e == JsonNull && k in props && k !in required }
                    .mapValues { (k, e) -> (props[k] as? JsonObject)?.let { walk(root, it, e) } ?: e })
            }
            is JsonArray -> (s["items"] as? JsonObject)?.let { item -> JsonArray(v.map { walk(root, item, it) }) } ?: v
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
