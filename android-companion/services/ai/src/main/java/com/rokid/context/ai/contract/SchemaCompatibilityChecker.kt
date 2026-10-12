package com.rokid.context.ai.contract

import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.schema.SchemaRefs
import com.rokid.context.ai.schema.WireSchemaCompiler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Static editor check (spec §4.4.1): the draft must be a refinement of the role's base schema — descriptions edited,
 * constraints tightened, enums narrowed, optional properties added — and must compile for every bound endpoint that
 * decodes with a schema. Each problem is one short line; Save stays disabled while any exist.
 */
object SchemaCompatibilityChecker {
    private val MIN_KEYS = listOf("minLength", "minItems", "minimum", "exclusiveMinimum", "minProperties")
    private val MAX_KEYS = listOf("maxLength", "maxItems", "maximum", "exclusiveMaximum", "maxProperties")

    fun check(key: PromptKey, draftSchema: String?, boundEndpoints: List<Endpoint>): List<String> {
        val base = DefaultSchemas.forKey(key) ?: return emptyList()
        val text = draftSchema?.takeIf { it.isNotBlank() } ?: return emptyList()
        val draft = runCatching { SchemaRefs.parse(text) }.getOrElse { return listOf("Schema isn't valid JSON") }
        refCycle(draft)?.let { return listOf("Recursive schemas aren't supported (\$defs/$it refers back to itself)") }
        val problems = runCatching { refine(base, base, draft, draft, "") }.getOrElse { listOf(it.message ?: "Schema isn't valid") }.toMutableList()
        if (problems.isEmpty()) {
            boundEndpoints.filter { Capability.JSON_SCHEMA in it.capabilities }.distinctBy { it.id }.forEach { e ->
                WireSchemaCompiler.compile(draft, e.kind).exceptionOrNull()?.let { problems += "${e.name}: ${it.message}" }
            }
        }
        return problems
    }

    /** Name of a `$defs`/`definitions` entry that can reach itself through `$ref`s (direct or indirect), or null. Iterative. */
    private fun refCycle(root: JsonObject): String? {
        val defs = listOf("\$defs", "definitions").flatMap { (root[it] as? JsonObject)?.entries.orEmpty() }.associate { it.key to it.value }
        val edges = defs.mapValues { (_, v) -> refs(v).filter { it in defs }.toSet() }
        for (start in edges.keys) {
            val seen = HashSet<String>()
            val todo = ArrayDeque(edges.getValue(start))
            while (todo.isNotEmpty()) {
                val n = todo.removeLast()
                if (n == start) return start
                if (seen.add(n)) todo.addAll(edges.getValue(n))
            }
        }
        return null
    }

    private fun refs(e: JsonElement): List<String> {
        val out = ArrayList<String>(); val stack = ArrayDeque<JsonElement>().apply { addLast(e) }
        while (stack.isNotEmpty()) when (val n = stack.removeLast()) {
            is JsonObject -> n.forEach { (k, v) ->
                if (k == "\$ref" && v is JsonPrimitive) out += v.content.substringAfterLast('/') else stack.addLast(v)
            }
            is JsonArray -> n.forEach { stack.addLast(it) }
            else -> Unit
        }
        return out
    }

    private fun refine(bRoot: JsonObject, bNode: JsonObject, dRoot: JsonObject, dNode: JsonObject, path: String): List<String> = buildList {
        val b = SchemaRefs.resolve(bRoot, bNode)
        val d = SchemaRefs.resolve(dRoot, dNode)
        val where = path.ifEmpty { "the top level" }
        val bt = SchemaRefs.typeNames(b["type"]); val dt = SchemaRefs.typeNames(d["type"])
        if (bt.isNotEmpty() && dt.toSet() != bt.toSet()) { add("Changed the type of $where"); return@buildList }
        (b["enum"] as? JsonArray)?.let { be -> val de = d["enum"] as? JsonArray; if (de == null || !be.containsAll(de)) add("Widened the allowed values of $where") }
        MIN_KEYS.forEach { k -> num(b, k)?.let { bv -> val dv = num(d, k); if (dv == null || dv < bv) add("Loosened $k of $where") } }
        MAX_KEYS.forEach { k -> num(b, k)?.let { bv -> val dv = num(d, k); if (dv == null || dv > bv) add("Loosened $k of $where") } }
        val bProps = b["properties"] as? JsonObject
        if (bProps != null) {
            val dProps = d["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val bReq = required(b); val dReq = required(d)
            for ((k, bp) in bProps) {
                val p = if (path.isEmpty()) k else "$path.$k"
                val dp = dProps[k] as? JsonObject
                if (dp == null) { add("Removed $p"); continue }
                if (k in bReq && k !in dReq) add("$p must stay required")
                addAll(refine(bRoot, bp.jsonObject, dRoot, dp, p))
            }
            for (k in dProps.keys - bProps.keys) if (k in dReq) add("New property ${if (path.isEmpty()) k else "$path.$k"} must be optional")
        }
        (b["items"] as? JsonObject)?.let { bi ->
            val di = d["items"] as? JsonObject
            if (di == null) add("Removed the item rules of $where") else addAll(refine(bRoot, bi, dRoot, di, "$path[]"))
        }
        val bBranches = (b["oneOf"] ?: b["anyOf"]) as? JsonArray
        if (bBranches != null) {
            val dBranches = (d["oneOf"] ?: d["anyOf"]) as? JsonArray
            if (dBranches == null) { add("Changed the type of $where"); return@buildList }
            val byTag = bBranches.associateBy { tag(SchemaRefs.resolve(bRoot, it.jsonObject)) }
            for (db in dBranches) {
                val dr = SchemaRefs.resolve(dRoot, db.jsonObject)
                val t = tag(dr)
                val match = byTag[t]
                if (t == null || match == null) add("Unknown action type in $where") else addAll(refine(bRoot, match.jsonObject, dRoot, dr, "$path[$t]"))
            }
        }
    }

    private fun tag(branch: JsonObject): String? {
        val t = (branch["properties"] as? JsonObject)?.get("type") as? JsonObject ?: return null
        return ((t["enum"] as? JsonArray)?.singleOrNull() as? JsonPrimitive)?.content ?: (t["const"] as? JsonPrimitive)?.content
    }

    private fun required(s: JsonObject): Set<String> = (s["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet().orEmpty()
    private fun num(s: JsonObject, k: String): Double? = (s[k] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
}
