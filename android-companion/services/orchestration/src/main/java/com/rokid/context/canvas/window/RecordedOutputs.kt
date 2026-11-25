package com.rokid.context.canvas.window

import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.ExecutorKind
import com.rokid.context.canvas.CanvasItem
import com.rokid.context.canvas.CanvasValue
import com.rokid.context.canvas.CanvasValueJson
import com.rokid.context.canvas.NodeExecutor
import com.rokid.context.canvas.NodeResult
import com.rokid.context.canvas.RunContext
import com.rokid.context.memory.db.CanvasRunItemEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Canvas v2.1 P3 (design section 1.6), the REPLAYED level: serve a model node's output from an earlier run's own rows when the INPUT KEY matches, with `calls = 0`. No new column: the key is recomputed
 * from the source run's rows (the node's recorded promptVersion and model label, and the upstream node's recorded output for the same frame). Deterministic nodes are never wrapped and re-execute for real.
 * A changed param, prompt version, model label or upstream value is a miss. A miss is `not_recorded` (default) or a live call (per-run choice, the call then runs under the run's usual confirmToken and privacy gates).
 */
enum class ReplayLevel { LIVE, SEEDED, REPLAYED }

/** What the source run holds for one (frame, node), already joined with its upstream row. */
data class RecordedRow(val row: CanvasRunItemEntity, val upstreamOutputJson: String?, val nodeType: String, val nodeParams: JsonObject)

interface RecordedSource {
    /** False when the source run was pruned or Forgotten: replay of it is refused (`replay_source_gone`). */
    suspend fun available(): Boolean
    suspend fun recorded(frameId: String, nodeId: String): RecordedRow?
}

object InputKey {
    /** A fixed-order text of a JSON element (object keys sorted), so equal params give equal keys. */
    fun canonical(e: JsonElement?): String = when (e) {
        null -> "null"
        is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${canonical(it.value)}" }
        is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> e.toString()
    }

    fun of(type: String, params: JsonObject, promptVersion: Int?, modelLabel: String?, upstreamOutputJson: String?): String {
        val text = listOf(type, canonical(params), promptVersion?.toString() ?: "-", modelLabel ?: "-", upstreamOutputJson ?: "-").joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

class RecordedOutputsExecutor(
    private val inner: NodeExecutor,
    private val source: RecordedSource,
    private val onMissLive: Boolean,
    /** The prompt version and fixed model label this run would use for [node] (never a user string). */
    private val currentLabel: suspend (PipelineNode) -> Pair<Int?, String?>,
) : NodeExecutor {
    override val type: String get() = inner.type
    override val chunkLocal: Boolean get() = inner.chunkLocal
    override fun chunkSize(node: PipelineNode): Int = inner.chunkSize(node)
    /** A hit costs nothing, so the estimate stays the inner executor's (an upper bound). */
    override fun nominalCalls(node: PipelineNode, items: Int): Int = inner.nominalCalls(node, items)
    override fun maxCalls(node: PipelineNode, items: Int): Int = inner.maxCalls(node, items)

    override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> {
        if (!source.available()) return items.map { NodeResult(it.frameId, null, error = "replay_source_gone") }
        val (pv, label) = currentLabel(node)
        val out = arrayOfNulls<NodeResult>(items.size)
        val misses = ArrayList<Int>()
        for ((i, it) in items.withIndex()) {
            val rec = source.recorded(it.frameId, node.id)
            val upstream = if (it.value === CanvasValue.Photo) null else CanvasValueJson.encode(it.value)
            val want = InputKey.of(node.type, node.params, pv, label, upstream)
            val hit = rec != null && rec.row.status == "OK" &&
                InputKey.of(rec.nodeType, rec.nodeParams, rec.row.promptVersion, rec.row.model, rec.upstreamOutputJson) == want
            val value = if (hit) decode(rec!!.row.outputJson) else null
            if (hit && value != null) out[i] = NodeResult(it.frameId, value, rec!!.row.branch, calls = 0) else misses += i
        }
        if (misses.isNotEmpty()) {
            if (onMissLive) {
                val live = inner.run(node, misses.map { items[it] }, ctx)
                for ((k, i) in misses.withIndex()) out[i] = live[k]
            } else for (i in misses) out[i] = NodeResult(items[i].frameId, null, error = "not_recorded")
        }
        return out.map { it!! }
    }

    private fun decode(json: String?): CanvasValue? {
        if (json == null) return null
        val o = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        return when ((o["kind"] as? JsonPrimitive)?.content) {
            "photo" -> CanvasValue.Photo
            "extraction" -> CanvasValueJson.decodeExtraction(json)
            "json" -> o["value"]?.let { CanvasValue.Json(it) }
            else -> null
        }
    }

    companion object {
        /** Only nodes that call a model are wrapped; everything else (aggregate, compare, switch, expr, rules) re-executes for real. */
        fun wrapsType(type: String): Boolean = NodeTypes.byType(type)?.let { it.executor == ExecutorKind.PROMPT } == true
    }
}

/** The determinism report of a REPLAYED or SEEDED run against its source run: fixed words and counts, never a value. */
object DeterminismReport {
    data class Report(val items: Int, val identical: Int, val driftedNodes: Map<String, Int>) {
        val line: String get() = if (driftedNodes.isEmpty()) "$identical/$items items identical" else "$identical/$items items identical; ${items - identical} drifted at node ${driftedNodes.entries.sortedByDescending { it.value }.first().key}"
    }

    /** [nodeOrder] is the pipeline's topological node order: the first node whose row differs is where an item drifted. */
    fun of(source: List<CanvasRunItemEntity>, replayed: List<CanvasRunItemEntity>, nodeOrder: List<String>): Report {
        val s = source.associateBy { it.frameId to it.nodeId }
        val r = replayed.associateBy { it.frameId to it.nodeId }
        val frames = (source.map { it.frameId } + replayed.map { it.frameId }).toSortedSet()
        var same = 0
        val drift = java.util.TreeMap<String, Int>()
        for (f in frames) {
            val first = nodeOrder.firstOrNull { n -> val a = s[f to n]; val b = r[f to n]; !(a?.status == b?.status && a?.branch == b?.branch && a?.outputJson == b?.outputJson) }
            if (first == null) same++ else drift.merge(first, 1, Int::plus)
        }
        return Report(frames.size, same, drift)
    }
}
