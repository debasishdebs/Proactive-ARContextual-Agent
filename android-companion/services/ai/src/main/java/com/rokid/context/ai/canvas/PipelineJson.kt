package com.rokid.context.ai.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

object PipelineJson {
    const val SCHEMA = 1
    /** Schema 2 adds the capture-half types; its canonical form names each node as `type@version` (S41). */
    const val SCHEMA_V2 = 2
    const val MAX_BYTES = 64 * 1024
    /** Nesting deeper than this is refused before any recursive walk (toString, canonical, hash). */
    const val MAX_DEPTH = 32

    private val pretty = Json { prettyPrint = true }

    /** Failure messages are fixed texts: never parser output, which could echo content. */
    fun decode(text: String): Result<Pipeline> {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return fail("too large")
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return fail("not JSON")
        if (tooDeep(root)) return fail("too deep")
        val schema = (root["schema"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (schema != SCHEMA && schema != SCHEMA_V2) return fail("unsupported schema")
        val id = str(root["id"]) ?: return fail("missing id")
        val name = str(root["name"]) ?: return fail("missing name")
        val nodesJson = root["nodes"] as? JsonArray ?: return fail("missing nodes")
        val edgesJson = root["edges"] as? JsonArray ?: return fail("missing edges")
        val nodes = nodesJson.map { e ->
            val o = e as? JsonObject ?: return fail("bad node")
            PipelineNode(
                id = str(o["id"]) ?: return fail("bad node"),
                type = str(o["type"]) ?: return fail("bad node"),
                title = o["title"]?.let { str(it) ?: return fail("bad node") } ?: "",
                params = when (val p = o["params"]) { null, JsonNull -> JsonObject(emptyMap()); is JsonObject -> p; else -> return fail("bad node") },
            )
        }
        val edges = edgesJson.map { e ->
            val o = e as? JsonObject ?: return fail("bad edge")
            PipelineEdge(
                from = str(o["from"]) ?: return fail("bad edge"),
                to = str(o["to"]) ?: return fail("bad edge"),
                `when` = when (val w = o["when"]) { null, JsonNull -> null; else -> str(w) ?: return fail("bad edge") },
            )
        }
        return Result.success(Pipeline(schema, id, name, nodes, edges))
    }

    fun encode(p: Pipeline, pretty: Boolean = false): String {
        val el = toElement(p)
        return if (pretty) this.pretty.encodeToString(JsonElement.serializer(), el) else el.toString()
    }

    /** True when [e] nests deeper than [max] levels of objects/arrays. Iterative, so it is safe on any input. */
    fun tooDeep(e: JsonElement, max: Int = MAX_DEPTH): Boolean {
        val stack = ArrayDeque<Pair<JsonElement, Int>>()
        stack.addLast(e to 1)
        while (stack.isNotEmpty()) {
            val (el, d) = stack.removeLast()
            val kids = when (el) { is JsonObject -> el.values; is JsonArray -> el; else -> continue }
            if (kids.isNotEmpty() && d >= max) return true
            for (k in kids) stack.addLast(k to d + 1)
        }
        return false
    }

    /** Keys sorted at every level, no whitespace. Array order is kept. */
    fun canonical(p: Pipeline): String = sorted(toElement(p, versioned = true)).toString()

    private fun toElement(p: Pipeline, versioned: Boolean = false): JsonObject = JsonObject(
        linkedMapOf(
            "schema" to JsonPrimitive(p.schema),
            "id" to JsonPrimitive(p.id),
            "name" to JsonPrimitive(p.name),
            "nodes" to JsonArray(p.nodes.map {
                JsonObject(linkedMapOf("id" to JsonPrimitive(it.id), "type" to JsonPrimitive(if (versioned) typeName(p.schema, it.type) else it.type), "title" to JsonPrimitive(it.title), "params" to it.params))
            }),
            "edges" to JsonArray(p.edges.map { e ->
                val m = linkedMapOf<String, JsonElement>("from" to JsonPrimitive(e.from), "to" to JsonPrimitive(e.to))
                e.`when`?.let { m["when"] = JsonPrimitive(it) }
                JsonObject(m)
            }),
        ),
    )

    /** Schema 2 hashes `type@version`; schema 1 keeps the bare type, so stored hashes are never recomputed. */
    private fun typeName(schema: Int, type: String) =
        if (schema >= SCHEMA_V2) NodeTypes.resolve(type, schema)?.let { "$type@${it.version}" } ?: type else type

    private fun sorted(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(e.map { sorted(it) })
        else -> e
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun fail(msg: String): Result<Pipeline> = Result.failure(IllegalArgumentException(msg))
}
