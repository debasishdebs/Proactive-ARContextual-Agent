package com.rokid.context.ai.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A schema we refuse on purpose: its message is written for people and may be shown in the editor (anything else thrown is not). */
class SchemaRejected(message: String) : IllegalArgumentException(message)

/** Throws [SchemaRejected] with [message] unless [ok]. */
inline fun rejectUnless(ok: Boolean, message: () -> String) { if (!ok) throw SchemaRejected(message()) }

/** Shared JSON Schema helpers (local refs only; our schemas never point outside the document). */
object SchemaRefs {
    private const val MAX_HOPS = 16

    /** User text (a `${'$'}ref` or a `${'$'}defs` name) in an error line: at most 64 characters, then "…". */
    fun clip(text: String): String = if (text.length <= 64) text else text.take(64) + "…"

    fun parse(text: String): JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }
        .getOrElse { throw SchemaRejected("Schema isn't valid JSON") }

    fun resolve(root: JsonObject, node: JsonObject): JsonObject {
        var n = node
        repeat(MAX_HOPS) {
            val ref = (n["\$ref"] as? JsonPrimitive)?.content ?: return n
            val name = when {
                ref.startsWith("#/\$defs/") -> ref.removePrefix("#/\$defs/") to "\$defs"
                ref.startsWith("#/definitions/") -> ref.removePrefix("#/definitions/") to "definitions"
                else -> throw SchemaRejected("Only local \$ref is supported: ${clip(ref)}")
            }
            n = (root[name.second] as? JsonObject)?.get(name.first) as? JsonObject
                ?: throw SchemaRejected("Unknown \$ref: ${clip(ref)}")
        }
        throw SchemaRejected("Recursive \$ref isn't supported")
    }

    fun typeNames(type: JsonElement?): List<String> = when (type) {
        is JsonPrimitive -> listOf(type.content)
        is JsonArray -> type.map { (it as JsonPrimitive).content }
        else -> emptyList()
    }

    const val MAX_DEPTH = 64

    /** True when the tree nests deeper than [max]. Iterative, so a hostile tree can't overflow the stack. */
    fun exceedsDepth(root: JsonElement, max: Int = MAX_DEPTH): Boolean {
        val stack = ArrayDeque<Pair<JsonElement, Int>>()
        stack.addLast(root to 1)
        while (stack.isNotEmpty()) {
            val (e, d) = stack.removeLast()
            when (e) {
                is JsonObject -> { if (d > max) return true; e.values.forEach { stack.addLast(it to d + 1) } }
                is JsonArray -> { if (d > max) return true; e.forEach { stack.addLast(it to d + 1) } }
                else -> Unit
            }
        }
        return false
    }
}
