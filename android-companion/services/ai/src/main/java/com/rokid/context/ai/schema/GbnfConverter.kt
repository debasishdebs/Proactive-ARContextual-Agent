package com.rokid.context.ai.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Inlined wire schema → llama.cpp GBNF (spec §4.4). Properties appear in schema order; all are required on the wire. */
object GbnfConverter {
    private val BASE = listOf(
        """ws ::= [ \t\n]*""",
        """string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ) )* "\"" """.trim(),
        """number ::= "-"? ( [0-9] | [1-9] [0-9]* ) ( "." [0-9]+ )? ( [eE] [-+]? [0-9]+ )?""",
        """integer ::= "-"? ( [0-9] | [1-9] [0-9]* )""",
        """boolean ::= "true" | "false" """.trim(),
        """null ::= "null" """.trim(),
    )

    fun convert(wire: JsonObject): String {
        val rules = LinkedHashMap<String, String>()
        val root = rule(wire, rules)
        return (listOf("root ::= $root") + rules.map { (n, r) -> "$n ::= $r" } + BASE).joinToString("\n")
    }

    private fun rule(s: JsonObject, rules: MutableMap<String, String>): String {
        (s["const"])?.let { return lit(it) }
        (s["enum"] as? JsonArray)?.let { e ->
            require(e.isNotEmpty()) { "An enum needs at least one value" }
            return named(rules, e.joinToString(" | ") { lit(it) })
        }
        (s["anyOf"] as? JsonArray)?.let { b -> return named(rules, b.joinToString(" | ") { rule(it.jsonObject, rules) }) }
        val types = SchemaRefs.typeNames(s["type"]).ifEmpty { if (s["properties"] is JsonObject) listOf("object") else emptyList() }
        if (types.size > 1) return named(rules, types.joinToString(" | ") { t -> single(t, s, rules) })
        return single(types.firstOrNull() ?: "string", s, rules)
    }

    private fun single(type: String, s: JsonObject, rules: MutableMap<String, String>): String = when (type) {
        "object" -> {
            val props = (s["properties"] as? JsonObject).orEmpty()
            val body = props.entries.joinToString(" \",\" ws ") { (k, v) -> "${lit(JsonPrimitive(k))} ws \":\" ws ${rule(v.jsonObject, rules)} ws" }
            named(rules, if (body.isEmpty()) "\"{\" ws \"}\"" else "\"{\" ws $body \"}\"")
        }
        "array" -> {
            val item = (s["items"] as? JsonObject)?.let { rule(it, rules) } ?: "string"
            named(rules, "\"[\" ws ( $item ws ( \",\" ws $item ws )* )? \"]\"")
        }
        "string", "number", "integer", "boolean", "null" -> type
        else -> "string"
    }

    private fun named(rules: MutableMap<String, String>, body: String): String {
        rules.entries.firstOrNull { it.value == body }?.let { return it.key }
        val name = "r${rules.size + 1}"
        rules[name] = body
        return name
    }

    /** The JSON text of [e] as a GBNF string literal. */
    private fun lit(e: JsonElement): String = "\"" + e.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
