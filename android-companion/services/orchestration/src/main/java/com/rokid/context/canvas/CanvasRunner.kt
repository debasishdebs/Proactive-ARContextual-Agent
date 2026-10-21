package com.rokid.context.canvas

import android.util.Log
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineHash
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.PipelineValidator
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.orchestration.ExtractPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

data class ModelLabel(val nodeId: String, val endpoint: String, val model: String)

sealed interface Estimate {
    data class Ok(val photos: Int, val nominal: Int, val max: Int, val models: List<ModelLabel>, val confirmToken: String) : Estimate
    data class Refused(val reasons: List<String>) : Estimate
}

/**
 * Runs a pipeline over chosen photos, one run at a time. It writes canvas tables only (spec section 3, R10).
 * Errors stored on items are fixed short texts, never provider text.
 */
class CanvasRunner(
    private val dao: CanvasDao,
    private val photos: PhotoSource,
    private val executors: Map<String, NodeExecutor>,
    private val context: (runId: String) -> RunContext,
    private val tokens: ConfirmTokens,
    private val hot: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    companion object {
        const val MAX_PHOTOS = 50
        const val MAX_NOMINAL_CALLS = 200
        const val KEEP_RUNS = 20
        private const val TAG = "CanvasRunner"
    }

    class Busy : IllegalStateException("busy")

    private val lock = Mutex()
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active
    private val cancelled = AtomicReference<String?>(null)
    private val modelsByToken = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 32
    }

    suspend fun recoverAtStart(): Int = dao.interruptRunning(clock())

    fun cancel(runId: String): Boolean {
        if (_active.value != runId) return false
        cancelled.set(runId)
        return true
    }

    suspend fun estimate(p: Pipeline, frameIds: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Estimate {
        val ids = frameIds.distinct()
        val reasons = mutableListOf<String>()
        if (ids.isEmpty()) reasons += "no photos"
        if (ids.size > MAX_PHOTOS) reasons += "more than $MAX_PHOTOS photos"
        if (PipelineValidator.validate(p).isNotEmpty()) reasons += "pipeline has problems"
        var nominal = 0
        var max = 0
        val models = mutableListOf<ModelLabel>()
        for (n in p.nodes) {
            val ex = executors[n.type] ?: continue
            val nom = ex.nominalCalls(n, ids.size)
            nominal += nom
            max += ex.maxCalls(n, ids.size)
            if (nom > 0) labels(n)?.let { models += it }
        }
        if (nominal > MAX_NOMINAL_CALLS) reasons += "more than $MAX_NOMINAL_CALLS model calls"
        if (reasons.isNotEmpty()) return Estimate.Refused(reasons)
        val token = tokens.issue(PipelineHash.of(p), ids)
        synchronized(modelsByToken) { modelsByToken[token] = modelsJson(models) }
        return Estimate.Ok(ids.size, nominal, max, models, token)
    }

    private fun modelsJson(models: List<ModelLabel>) = JsonArray(models.map {
        buildJsonObject { put("node", it.nodeId); put("endpoint", it.endpoint); put("model", it.model) }
    }).toString()

    /** Throws [Busy] when a run is active, [IllegalArgumentException] for a bad or expired token. Returns the runId; the run continues in [scope]. */
    suspend fun start(p: Pipeline, pipelineId: String, version: Int?, frameIds: List<String>, confirmToken: String, scope: CoroutineScope): String {
        if (!lock.tryLock()) throw Busy()
        var launched = false
        try {
            if (PipelineValidator.validate(p).isNotEmpty()) throw IllegalArgumentException("invalid_pipeline")
            val ids = frameIds.distinct()
            val hash = PipelineHash.of(p)
            if (!tokens.consume(confirmToken, hash, ids)) throw IllegalArgumentException("bad_token")
            val models = synchronized(modelsByToken) { modelsByToken.remove(confirmToken) } ?: "[]"
            val nominal = p.nodes.sumOf { n -> executors[n.type]?.nominalCalls(n, ids.size) ?: 0 }
            dao.pruneRuns(KEEP_RUNS - 1)
            val runId = newId()
            dao.insertRun(
                CanvasRunEntity(
                    runId, pipelineId, version, hash, PipelineJson.canonical(p), "RUNNING", ids.size, nominal, 0, 0, clock(), null, models,
                ),
            )
            cancelled.set(null)
            _active.value = runId
            launched = true
            scope.launch {
                try {
                    execute(runId, p, ids)
                } finally {
                    _active.value = null
                    lock.unlock()
                }
            }
            return runId
        } finally {
            if (!launched) lock.unlock()
        }
    }

    private class Out(val value: CanvasValue, val branch: String?)

    private suspend fun execute(runId: String, p: Pipeline, ids: List<String>) {
        var calls = 0
        var failures = 0
        var status = "DONE"
        try {
            val ctx = context(runId)
            val load = photos.load(ids)
            val photoById: Map<String, ExtractPhoto> = load.photos.associateBy { it.frameId }
            val order = ids.withIndex().associate { it.value to it.index }
            val outputs = HashMap<String, Map<String, Out>>()

            suspend fun write(nodeId: String, frameId: String, r: NodeResult) {
                val m = r.meta
                dao.upsertItem(
                    CanvasRunItemEntity(
                        runId, frameId, nodeId, if (r.error == null) "OK" else "ERROR", r.branch, r.value?.let { CanvasValueJson.encode(it) }, r.error,
                        m?.latencyMs, m?.promptTokens, m?.completionTokens, m?.model, m?.promptVersion, m?.batchId,
                    ),
                )
                if (r.error != null) failures++
                calls += r.calls
            }

            stop@ for (node in topological(p)) {
                val incoming = p.edges.filter { it.to == node.id }
                val inputs = LinkedHashMap<String, CanvasValue>()
                if (node.type == NodeTypes.INPUT_PHOTOS) {
                    for (id in ids) {
                        if (id in photoById) {
                            write(node.id, id, NodeResult(id, CanvasValue.Photo))
                            inputs[id] = CanvasValue.Photo
                        } else {
                            write(node.id, id, NodeResult(id, null, error = "unreadable"))
                        }
                    }
                    outputs[node.id] = inputs.mapValues { Out(it.value, null) }
                    dao.updateRun(runId, "RUNNING", calls, failures, null)
                    continue
                }
                val conflicts = LinkedHashSet<String>()
                for (e in incoming) {
                    for ((id, o) in outputs[e.from].orEmpty()) {
                        if (e.`when` != null && e.`when` != o.branch) continue
                        if (inputs.containsKey(id) || id in conflicts) { inputs.remove(id); conflicts += id } else inputs[id] = o.value
                    }
                }
                for (id in conflicts.sortedBy { order[it] }) write(node.id, id, NodeResult(id, null, error = "merge_conflict"))
                val ex = executors[node.type]
                val queue = inputs.entries.sortedBy { order[it.key] }.map { CanvasItem(it.key, photoById.getValue(it.key), it.value) }
                val produced = LinkedHashMap<String, Out>()
                for (chunk in queue.chunked(ex?.chunkSize(node)?.coerceAtLeast(1) ?: Int.MAX_VALUE)) {
                    if (cancelled.get() == runId) { status = "CANCELLED"; break@stop }
                    if (hot()) { status = "STOPPED_HOT"; break@stop }
                    val results = ex?.run(node, chunk, ctx)?.associateBy { it.frameId }.orEmpty()
                    for (item in chunk) {
                        val r = results[item.frameId] ?: NodeResult(item.frameId, null, error = if (ex == null) "no_executor" else "no_result")
                        write(node.id, item.frameId, r)
                        r.value?.let { produced[item.frameId] = Out(it, r.branch) }
                    }
                    dao.updateRun(runId, "RUNNING", calls, failures, null)
                }
                outputs[node.id] = produced
            }
        } catch (c: CancellationException) {
            withContext(NonCancellable) { dao.updateRun(runId, "CANCELLED", calls, failures, clock()) }
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "run failed: ${e.javaClass.simpleName}")
            status = "FAILED"
        }
        withContext(NonCancellable) { dao.updateRun(runId, status, calls, failures, clock()) }
    }

    /** Kahn's algorithm; ties go to the node that comes first in the JSON. */
    private fun topological(p: Pipeline): List<PipelineNode> {
        val remaining = p.nodes.toMutableList()
        val done = HashSet<String>()
        val out = ArrayList<PipelineNode>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { n -> p.edges.none { it.to == n.id && it.from !in done } } ?: remaining.first()
            remaining.remove(next); done += next.id; out += next
        }
        return out
    }
}
