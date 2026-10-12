package com.rokid.context.ai.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Shared JSON Schema helpers (local refs only; our schemas never point outside the document). */
object SchemaRefs {
    private const val MAX_HOPS = 16

    fun parse(text: String): JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }
        .getOrElse { throw IllegalArgumentException("Schema isn't valid JSON") }

    fun resolve(root: JsonObject, node: JsonObject): JsonObject {
        var n = node
        repeat(MAX_HOPS) {
            val ref = (n["\$ref"] as? JsonPrimitive)?.content ?: return n
            val name = when {
                ref.startsWith("#/\$defs/") -> ref.removePrefix("#/\$defs/") to "\$defs"
                ref.startsWith("#/definitions/") -> ref.removePrefix("#/definitions/") to "definitions"
                else -> throw IllegalArgumentException("Only local \$ref is supported: $ref")
            }
            n = (root[name.second] as? JsonObject)?.get(name.first) as? JsonObject
                ?: throw IllegalArgumentException("Unknown \$ref: $ref")
        }
        throw IllegalArgumentException("Recursive \$ref isn't supported")
    }

    fun typeNames(type: JsonElement?): List<String> = when (type) {
        is JsonPrimitive -> listOf(type.content)
        is JsonArray -> type.map { (it as JsonPrimitive).content }
        else -> emptyList()
    }
}
