package com.rokid.context.ai.schema

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Models often wrap JSON in fences or prose (Review Focus #1): read it leniently, then validate strictly. */
object ModelJson {
    private val FENCE = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")
    private const val MAX_CANDIDATES = 16

    fun parse(raw: String): JsonElement? {
        val t = raw.trim()
        tryParse(t)?.let { return it }
        for (m in FENCE.findAll(t)) tryParse(m.groupValues[1].trim())?.let { return it }
        var tried = 0
        var from = 0
        while (tried < MAX_CANDIDATES) {
            val a = t.indexOfAny(charArrayOf('{', '['), from)
            if (a < 0) break
            val b = matchingClose(t, a)
            if (b > a) {
                tried++
                val candidate = t.substring(a, b + 1)
                if (tooDeep(candidate)) return null // never fall through to a shallower slice of a too-deep value
                tryParse(candidate)?.let { return it }
            }
            from = a + 1
        }
        return null
    }

    /** Index of the bracket closing the one at [open], skipping string contents; -1 when unbalanced. */
    private fun matchingClose(t: String, open: Int): Int {
        val stack = StringBuilder()
        var inString = false
        var i = open
        while (i < t.length) {
            val c = t[i]
            when {
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '{' -> stack.append('}')
                c == '[' -> stack.append(']')
                c == '}' || c == ']' -> {
                    if (stack.isEmpty() || stack.last() != c) return -1
                    stack.setLength(stack.length - 1)
                    if (stack.isEmpty()) return i
                }
            }
            i++
        }
        return -1
    }

    private fun tooDeep(s: String): Boolean {
        var depth = 0
        var inString = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '{' || c == '[' -> if (++depth > SchemaRefs.MAX_DEPTH) return true
                c == '}' || c == ']' -> if (depth > 0) depth--
            }
            i++
        }
        return false
    }

    private fun tryParse(s: String): JsonElement? {
        if (s.isEmpty() || tooDeep(s)) return null
        return try { Json.parseToJsonElement(s) } catch (_: SerializationException) { null } catch (_: IllegalArgumentException) { null }
    }
}
