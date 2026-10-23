package com.rokid.context.canvas.helper

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * `bundleHash` = lowercase hex SHA-256 of the canonical JSON of the whole payload. Canonical form (identical to Python
 * `json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False).encode("utf-8")`): object keys sorted by Unicode code point (NOT by UTF-16 unit),
 * no whitespace, only `"` `\` and control characters below 0x20 escaped, integers only (a float or non-finite number is refused so both languages cannot disagree on its text; integer `-0` is written `0`, as Python parses it), and an unpaired surrogate in a key or string is refused (Python cannot encode it).
 * Both languages must pass tools/canvas-helper/protocol/hash-vectors.json.
 */
object BundleHash {
    private val INT = Regex("-?(0|[1-9][0-9]*)")

    fun canonical(payload: JsonElement): String = StringBuilder().also { write(payload, it) }.toString()

    fun of(payload: JsonElement): String = ofCanonical(canonical(payload))

    fun ofCanonical(canonical: String): String =
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun write(e: JsonElement, sb: StringBuilder) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> when {
                e.isString -> string(e.content, sb)
                e.content == "true" || e.content == "false" -> sb.append(e.content)
                INT.matches(e.content) -> sb.append(if (e.content == "-0") "0" else e.content)
                else -> throw IllegalArgumentException("non-integer number")
            }
            is JsonArray -> { sb.append('['); e.forEachIndexed { i, v -> if (i > 0) sb.append(','); write(v, sb) }; sb.append(']') }
            is JsonObject -> {
                sb.append('{')
                e.keys.sortedWith(CODE_POINT_ORDER).forEachIndexed { i, k -> if (i > 0) sb.append(','); string(k, sb); sb.append(':'); write(e.getValue(k), sb) }
                sb.append('}')
            }
        }
    }

    private val CODE_POINT_ORDER = Comparator<String> { a, b ->
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i); val y = b.codePointAt(j)
            if (x != y) return@Comparator x.compareTo(y)
            i += Character.charCount(x); j += Character.charCount(y)
        }
        (a.length - i).compareTo(b.length - j)
    }

    /** Python refuses to encode an unpaired surrogate; so must we (no replacement bytes get hashed). */
    private fun requirePaired(s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) { if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) i++ else throw IllegalArgumentException("unpaired surrogate") }
            else if (Character.isLowSurrogate(c)) throw IllegalArgumentException("unpaired surrogate")
            i++
        }
    }

    private fun string(s: String, sb: StringBuilder) {
        requirePaired(s)
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000c' -> sb.append("\\f")
            c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}
