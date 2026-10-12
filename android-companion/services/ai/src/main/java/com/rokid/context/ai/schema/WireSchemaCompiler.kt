package com.rokid.context.ai.schema

import com.rokid.context.ai.EndpointKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Canonical schema → the subset a backend accepts (spec §4.4). The wire schema only steers decoding; validation always
 * uses the canonical one. Every object: `additionalProperties: false`, all properties required, optional ones nullable;
 * `oneOf` → `anyOf`; unsupported constraints are stripped and appended in words to `description`. Only Azure keeps
 * `$defs`/`$ref` (OpenAI-compatible servers vary, so they get refs inlined like Ollama/llama.cpp); Azure and
 * OpenAI-compatible must meet the strict-mode limits. A `$ref` cycle is refused.
 */
object WireSchemaCompiler {
    const val MAX_DEPTH = 5
    const val MAX_PROPERTIES = 100
    const val MAX_ENUM_VALUES = 500
    const val MAX_NODES = 2000
    private const val REF = "\$ref"
    private const val DEFS = "\$defs"
    private val UNSUPPORTED = listOf("allOf", "not", "if", "then", "else", "dependentRequired", "dependentSchemas", "unevaluatedProperties")

    fun compile(canonical: JsonObject, kind: EndpointKind): Result<JsonObject> = runCatching {
        val keepRefs = kind == EndpointKind.AZURE_OPENAI
        val checkLimits = keepRefs || kind == EndpointKind.OPENAI_COMPAT
        require(!SchemaRefs.exceedsDepth(canonical)) { "The schema is nested too deeply" }
        rejectRefCycles(canonical)
        val top = SchemaRefs.resolve(canonical, canonical)
        require("object" in SchemaRefs.typeNames(top["type"]) && "anyOf" !in top && "oneOf" !in top) { "The top level must be an object" }
        val budget = Budget()
        val body = node(canonical, canonical, keepRefs, emptyList(), budget)
        val out = if (keepRefs && canonical[DEFS] is JsonObject) {
            JsonObject(body + (DEFS to JsonObject(canonical[DEFS]!!.jsonObject.mapValues { (_, d) -> node(canonical, d.jsonObject, true, emptyList(), budget) })))
        } else body
        if (checkLimits) checkLimits(out)
        out
    }

    /** Refuses any `$ref` cycle (direct or indirect), so inlining and the limit walk always terminate. */
    private fun rejectRefCycles(root: JsonObject) {
        val done = HashSet<String>()
        fun refsIn(e: JsonElement, out: MutableList<String>) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> if (k == REF && v is JsonPrimitive) out += v.content else refsIn(v, out) }
                is JsonArray -> e.forEach { refsIn(it, out) }
                else -> Unit
            }
        }
        fun visit(ref: String, stack: Set<String>) {
            require(ref !in stack) { "Recursive \$ref isn't supported" }
            if (!done.add(ref)) return
            val target = SchemaRefs.resolve(root, JsonObject(mapOf(REF to JsonPrimitive(ref))))
            val next = ArrayList<String>().also { refsIn(target, it) }
            next.forEach { visit(it, stack + ref) }
        }
        val top = ArrayList<String>()
        root.forEach { (k, v) -> if (k != DEFS && k != "definitions") refsIn(v, top) }
        top.forEach { visit(it, emptySet()) }
        (root[DEFS] as? JsonObject)?.keys?.forEach { visit("#/$DEFS/$it", emptySet()) }
    }

    private class Budget { var used = 0; fun spend() { require(++used <= MAX_NODES) { "The schema is too large" } } }

    private fun node(root: JsonObject, raw: JsonObject, keepRefs: Boolean, stack: List<String>, budget: Budget): JsonObject {
        budget.spend()
        if (REF in raw) {
            if (keepRefs) { SchemaRefs.resolve(root, raw); return JsonObject(mapOf(REF to raw[REF]!!)) }
            val ref = (raw[REF] as JsonPrimitive).content
            require(ref !in stack) { "Recursive \$ref isn't supported" }
            return node(root, SchemaRefs.resolve(root, raw), false, stack + ref, budget)
        }
        UNSUPPORTED.firstOrNull { it in raw }?.let { throw IllegalArgumentException("'$it' isn't supported") }
        val out = LinkedHashMap<String, JsonElement>()
        raw["type"]?.let { out["type"] = it }
        raw["enum"]?.let { out["enum"] = it }
        raw["const"]?.let { out["const"] = it }
        raw["title"]?.let { out["title"] = it }
        val words = words(raw)
        val desc = (raw["description"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        when {
            desc != null && words.isNotEmpty() -> out["description"] = JsonPrimitive("$desc (${words.joinToString("; ")})")
            desc != null -> out["description"] = JsonPrimitive(desc)
            words.isNotEmpty() -> out["description"] = JsonPrimitive(words.joinToString("; "))
        }
        ((raw["anyOf"] ?: raw["oneOf"]) as? JsonArray)?.let { b -> out["anyOf"] = JsonArray(b.map { node(root, it.jsonObject, keepRefs, stack, budget) }) }
        (raw["items"] as? JsonObject)?.let { out["items"] = node(root, it, keepRefs, stack, budget) }
        val props = raw["properties"] as? JsonObject
        if (props != null || "object" in SchemaRefs.typeNames(raw["type"])) {
            val required = (raw["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet().orEmpty()
            val compiled = (props ?: JsonObject(emptyMap())).mapValues { (k, p) ->
                val c = node(root, p.jsonObject, keepRefs, stack, budget)
                if (k in required) c else nullable(c)
            }
            out["properties"] = JsonObject(compiled)
            out["required"] = JsonArray(compiled.keys.map { JsonPrimitive(it) })
            out["additionalProperties"] = JsonPrimitive(false)
        }
        return JsonObject(out)
    }

    private fun nullable(raw: JsonObject): JsonObject {
        // A const can't hold null, so it becomes a one-value enum that can.
        val c = if ("const" in raw && "enum" !in raw) JsonObject(raw - "const" + ("enum" to JsonArray(listOf(raw["const"]!!)))) else raw
        val type = c["type"]
        return when {
            type is JsonPrimitive -> JsonObject(c + ("type" to JsonArray(listOf(type, JsonPrimitive("null")))) + enumWithNull(c))
            type is JsonArray -> if (type.any { (it as JsonPrimitive).content == "null" }) JsonObject(c + enumWithNull(c)) else JsonObject(c + ("type" to JsonArray(type + JsonPrimitive("null"))) + enumWithNull(c))
            c["anyOf"] is JsonArray -> JsonObject(c + ("anyOf" to JsonArray(c["anyOf"] as JsonArray + JsonObject(mapOf("type" to JsonPrimitive("null"))))))
            else -> JsonObject(mapOf("anyOf" to JsonArray(listOf(c, JsonObject(mapOf("type" to JsonPrimitive("null")))))))
        }
    }

    private fun enumWithNull(c: JsonObject): Map<String, JsonElement> =
        (c["enum"] as? JsonArray)?.let { e -> if (JsonNull in e) emptyMap() else mapOf("enum" to JsonArray(e + JsonNull)) } ?: emptyMap()

    private fun words(s: JsonObject): List<String> = buildList {
        range(s, "minLength", "maxLength", "characters")?.let { add(it) }
        (s["pattern"] as? JsonPrimitive)?.content?.let { add("must match $it") }
        (s["format"] as? JsonPrimitive)?.content?.let { add("format $it") }
        val bounds = listOfNotNull(
            num(s, "minimum")?.let { "at least ${JsonSchemaValidator.fmt(it)}" }, num(s, "exclusiveMinimum")?.let { "greater than ${JsonSchemaValidator.fmt(it)}" },
            num(s, "maximum")?.let { "at most ${JsonSchemaValidator.fmt(it)}" }, num(s, "exclusiveMaximum")?.let { "less than ${JsonSchemaValidator.fmt(it)}" },
        )
        if (bounds.isNotEmpty()) add(bounds.joinToString(" and "))
        num(s, "multipleOf")?.let { add("multiple of ${JsonSchemaValidator.fmt(it)}") }
        range(s, "minItems", "maxItems", "items")?.let { add(it) }
        if ((s["uniqueItems"] as? JsonPrimitive)?.booleanOrNull == true) add("items must be unique")
        if ("contains" in s) add("must include a matching item")
        range(s, "minProperties", "maxProperties", "properties")?.let { add(it) }
    }

    private fun range(s: JsonObject, minKey: String, maxKey: String, unit: String): String? {
        val a = num(s, minKey)?.let(JsonSchemaValidator::fmt); val b = num(s, maxKey)?.let(JsonSchemaValidator::fmt)
        return when {
            a != null && b != null -> "$a–$b $unit"
            a != null -> "at least $a $unit"
            b != null -> "at most $b $unit"
            else -> null
        }
    }

    private fun num(s: JsonObject, k: String): Double? = (s[k] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

    private fun checkLimits(wire: JsonObject) {
        var properties = 0
        var enums = 0
        val budget = Budget()
        fun visit(n: JsonObject, depth: Int) {
            budget.spend()
            if (REF in n) { visit(SchemaRefs.resolve(wire, n), depth); return }
            val isObject = n["properties"] is JsonObject
            val d = if (isObject) depth + 1 else depth
            require(d <= MAX_DEPTH) { "Too deeply nested (max $MAX_DEPTH levels)" }
            (n["enum"] as? JsonArray)?.let { enums += it.size }
            (n["properties"] as? JsonObject)?.let { p -> properties += p.size; p.values.forEach { visit(it.jsonObject, d) } }
            (n["items"] as? JsonObject)?.let { visit(it, d) }
            (n["anyOf"] as? JsonArray)?.forEach { visit(it.jsonObject, d) }
        }
        visit(wire, 0)
        require(properties <= MAX_PROPERTIES) { "Too many properties (max $MAX_PROPERTIES)" }
        require(enums <= MAX_ENUM_VALUES) { "Too many enum values (max $MAX_ENUM_VALUES)" }
    }
}
