package com.rokid.context.canvas.helper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The phone's side of the helper result envelope (decision D1, 2026-10-24): the helper posts `resultCanonical`, a STRING (Python
 * `json.dumps(result, sort_keys=True, separators=(",",":"), ensure_ascii=False)`), and `resultHash`, the lowercase hex SHA-256 of that exact string as UTF-8. No float formatter is shared
 * between the languages: the phone never prints a result number, it verifies the hash over the string AS RECEIVED, and only then parses it.
 *
 * [check] runs, in this order: the size limit (`maxResultBytes` measured on the UTF-8 bytes of the string), the hash over the received string, the strict parse (RFC 8259 grammar; a duplicate object key at any depth,
 * invalid JSON, an unpaired surrogate or nesting over [MAX_PARSE_DEPTH] is refused; every number keeps its source text, so `0.730` stays `0.730`), and that the value is an object. The shape and the per-member type
 * check against the job's `outSchema` (floats pass `number`, fail `integer`) are [HelperProtocol.validateResult], run by the caller on [Checked.Ok.result].
 * Phone-built payloads and inputs stay integer-only ([BundleHash]); nothing here touches them.
 */
object ResultWire {
    enum class Kind { TOO_LARGE, HASH, JSON, DUPLICATE_KEY, NOT_OBJECT }

    sealed class Checked {
        /** [result] is parsed from [text]; [text] is the string exactly as received (what the store keeps). */
        class Ok(val result: JsonObject, val text: String) : Checked()
        /** [reason] is a fixed text, never an echo of the content. */
        class Rejected(val kind: Kind, val reason: String) : Checked()
    }

    /** A hard stop against a stack overflow: the envelope (3 levels) plus an instance (at most [HelperProtocol.MAX_INSTANCE_DEPTH]) fit far below it. */
    const val MAX_PARSE_DEPTH = 32

    fun check(text: String, claimedHash: String): Checked {
        // characters never outnumber UTF-8 bytes, so a longer string is over the limit without encoding it
        if (text.length.toLong() > HelperProtocol.MAX_RESULT_BYTES || text.toByteArray(Charsets.UTF_8).size > HelperProtocol.MAX_RESULT_BYTES) return Checked.Rejected(Kind.TOO_LARGE, "result too large")
        // an unpaired surrogate cannot be UTF-8 encoded: it would be hashed as a replacement byte, so it is refused before the hash
        if (hasUnpairedSurrogate(text)) return Checked.Rejected(Kind.JSON, "result text is not valid unicode")
        if (BundleHash.ofCanonical(text) != claimedHash) return Checked.Rejected(Kind.HASH, "result hash does not match the result text")
        val parsed = try {
            Parser(text).parseDocument()
        } catch (e: ParseFailure) {
            return Checked.Rejected(e.kind, if (e.kind == Kind.DUPLICATE_KEY) "result has a duplicate key" else "result is not valid JSON")
        }
        val obj = parsed as? JsonObject ?: return Checked.Rejected(Kind.NOT_OBJECT, "result must be an object")
        return Checked.Ok(obj, text)
    }

    private fun hasUnpairedSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) { if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) i++ else return true }
            else if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }

    private class ParseFailure(val kind: Kind) : RuntimeException(null, null, false, false)

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): JsonElement {
            skipWs()
            val v = value(0)
            skipWs()
            if (i != s.length) fail()
            return v
        }

        private fun fail(kind: Kind = Kind.JSON): Nothing = throw ParseFailure(kind)

        private fun skipWs() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }

        private fun value(depth: Int): JsonElement {
            if (depth > MAX_PARSE_DEPTH) fail()
            if (i >= s.length) fail()
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonPrimitive(str())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') number() else fail()
            }
        }

        private fun literal(word: String, v: JsonElement): JsonElement {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return v
        }

        private fun obj(depth: Int): JsonElement {
            i++ // {
            val m = LinkedHashMap<String, JsonElement>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return JsonObject(m) }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail()
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') fail()
                i++
                skipWs()
                val v = value(depth + 1)
                if (m.put(k, v) != null) fail(Kind.DUPLICATE_KEY)
                skipWs()
                if (i >= s.length) fail()
                when (s[i++]) { ',' -> Unit; '}' -> return JsonObject(m); else -> fail() }
            }
        }

        private fun arr(depth: Int): JsonElement {
            i++ // [
            val l = ArrayList<JsonElement>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return JsonArray(l) }
            while (true) {
                skipWs()
                l += value(depth + 1)
                skipWs()
                if (i >= s.length) fail()
                when (s[i++]) { ',' -> Unit; ']' -> return JsonArray(l); else -> fail() }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when {
                    c == '"' -> { val out = sb.toString(); if (hasUnpairedSurrogate(out)) fail(); return out }
                    c < ' ' -> fail()
                    c == '\\' -> {
                        if (i >= s.length) fail()
                        when (s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                var code = 0
                                for (k in 0 until 4) { val d = Character.digit(s[i + k], 16); if (d < 0 || s[i + k].code > 0x7f) fail(); code = code * 16 + d }
                                i += 4
                                sb.append(code.toChar())
                            }
                            else -> fail()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): JsonElement {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail()
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ } else fail()
            if (i < s.length && s[i] == '.') { i++; digits() }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) { i++; if (i < s.length && (s[i] == '+' || s[i] == '-')) i++; digits() }
            // the source text of the token is kept (`0.730`, `1E-5`, `-0.0`): kotlinx keeps an unquoted number literal verbatim
            return Json.parseToJsonElement(s.substring(start, i))
        }

        private fun digits() {
            val from = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == from) fail()
        }
    }
}
