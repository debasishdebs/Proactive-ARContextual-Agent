package com.rokid.context.ai.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Models often wrap JSON in fences or prose (Review Focus #1): read it leniently, then validate strictly. */
object ModelJson {
    private val FENCE = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")

    fun parse(raw: String): JsonElement? {
        val t = raw.trim()
        tryParse(t)?.let { return it }
        FENCE.find(t)?.groupValues?.get(1)?.trim()?.let { inner -> tryParse(inner)?.let { return it } }
        for ((open, close) in listOf('{' to '}', '[' to ']')) {
            val a = t.indexOf(open); val b = t.lastIndexOf(close)
            if (a in 0 until b) tryParse(t.substring(a, b + 1))?.let { return it }
        }
        return null
    }

    private fun tryParse(s: String): JsonElement? = if (s.isEmpty()) null else runCatching { Json.parseToJsonElement(s) }.getOrNull()
}
