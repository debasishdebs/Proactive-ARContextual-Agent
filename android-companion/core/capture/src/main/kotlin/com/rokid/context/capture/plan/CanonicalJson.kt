package com.rokid.context.capture.plan

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical JSON: keys sorted by UTF-16 order, no whitespace, numbers in plain normalized decimal form (1.0 and 1 are both "1"),
 * strings escape only quote, backslash and C0 controls (as \u00xx lower-case hex), all other characters raw (UTF-8 on the wire).
 * Non-finite numbers throw. A phone-side compiler produces byte-identical text.
 */
object CanonicalJson {
    fun encode(e: JsonElement): String = StringBuilder().also { write(it, e) }.toString()

    private fun write(sb: StringBuilder, e: JsonElement) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) quote(sb, e.content) else sb.append(primitive(e.content))
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                e.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    quote(sb, k); sb.append(':'); write(sb, e.getValue(k))
                }
                sb.append('}')
            }
        }
    }

    private fun primitive(c: String): String = when (c) {
        "true", "false" -> c
        else -> normalizeNumber(c)
    }

    fun normalizeNumber(c: String): String {
        require(c.length <= 40) { "number literal too long" }
        val d = c.toDoubleOrNull()
        require(d != null && d.isFinite()) { "not a finite number" }
        val bd = BigDecimal(c)
        return if (bd.signum() == 0) "0" else bd.stripTrailingZeros().toPlainString()
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch.code < 0x20 -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}
