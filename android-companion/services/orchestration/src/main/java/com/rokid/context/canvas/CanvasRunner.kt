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
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

/** What a run is expected to cost in tokens, from the history of same-type nodes (per call mean x nominal calls). [basedOn] = owner rows behind it. */
data class TokenEstimate(val promptTokens: Int, val completionTokens: Int, val basedOn: Int)

sealed interface Estimate {
    data class Ok(val photos: Int, val nominal: Int, val max: Int, val models: List<ModelLabel>, val confirmToken: String, val tokens: TokenEstimate? = null) : Estimate
    data class Refused(val reasons: List<String>) : Estimate
}

/** The two sides of an A/B run: (pipeline, version) and the pipeline id of each. */
data class AbSpec(val a: Pair<Pipeline, Int?>, val aId: String, val b: Pair<Pipeline, Int?>, val bId: String)

/** Both run ids are reserved up front: A is RUNNING, B is QUEUED (S31). */
data class AbStarted(val group: String, val runA: String, val runB: String)

/**
 * Runs a pipeline over chosen photos, one run at a time. It writes canvas tables only (spec section 3, R10).
 * Errors stored on items are fixed short texts, never provider text.
 *
 * An A/B run ([startAb]) is two ordinary runs in one group that own the runner together: A runs, and when it ends DONE and the group was not
 * cancelled, B runs in the same job (S31). Every photo-derived write goes through the fenced DAO commit with the wipe generation captured
 * before the photos were loaded (S24): after Delete all or a Forget, nothing is written and the run ends CANCELLED.
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
    private val generation: () -> Long = MemoryWipe.generation::get,
) {
    companion object {
        const val MAX_PHOTOS = 50
        const val MAX_NOMINAL_CALLS = 200
        const val KEEP_RUNS = 20
        /** Owner rows behind one per-call token mean (S32). */
        private const val TOKEN_HISTORY = 200
        private const val TAG = "CanvasRunner"
    }

    class Busy : IllegalStateException("another run is in progress")

    private class Group(val id: String, val runA: String, val runB: String) {
        @Volatile var cancelled = false
        fun owns(runId: String) = runId == runA || runId == runB
    }

    private class Priced(val nominal: Int, val max: Int, val models: List<ModelLabel>, val tokens: TokenEstimate?)

    private val lock = Mutex()
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active
    private val cancelled = AtomicReference<String?>(null)
    @Volatile private var group: Group? = null
    private val modelsByToken = object : LinkedHashMap<String, List<String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > 32
    }

    /** Group rows (A/B) still QUEUED or RUNNING after a restart are CANCELLED (B must never start), plain RUNNING rows are INTERRUPTED. Returns the rows changed. */
    suspend fun recoverAtStart(): Int { val now = clock(); return dao.cancelOpenGroupRuns(now) + dao.interruptRunning(now) }

    /** Cancels [runId]: the running run at its next chunk, or either run of the live A/B group (which cancels the whole group, B included). */
    fun cancel(runId: String): Boolean {
        group?.takeIf { it.owns(runId) }?.let { it.cancelled = true; return true }
        if (_active.value != runId) return false
        cancelled.set(runId)
        return true
    }

    /** Cancels the live A/B group [groupId], including between A's end and B's start. False when no such group is live. */
    fun cancelGroup(groupId: String): Boolean {
        val g = group?.takeIf { it.id == groupId && !it.cancelled } ?: return false
        g.cancelled = true
        return true
    }

    /** True for the active run and for either run of the live A/B group (a QUEUED B included). */
    fun isBusy(runId: String): Boolean = _active.value == runId || group?.owns(runId) == true

    private fun isCancelled(runId: String) = cancelled.get() == runId || group?.let { it.owns(runId) && it.cancelled } == true

    private suspend fun price(p: Pipeline, ids: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Priced {
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
        return Priced(nominal, max, models, tokenEstimate(p, ids.size))
    }

    /**
     * Expected tokens (S32): for each node that makes calls, the per-call mean ([CanvasDao.tokenMeans]) of the node's own id and of every node of the
     * same type in the pipelines of the [KEEP_RUNS] kept runs (a node renamed between versions keeps its history), times its nominal calls.
     * Null when no node has any history. [TokenEstimate.basedOn] adds up the owner rows the means were built from.
     */
    private suspend fun tokenEstimate(p: Pipeline, items: Int): TokenEstimate? {
        val past = dao.runs(KEEP_RUNS).mapNotNull { PipelineJson.decode(it.pipelineJson).getOrNull() }
        var prompt = 0.0
        var completion = 0.0
        var based = 0
        for (n in p.nodes) {
            val ex = executors[n.type] ?: continue
            val nom = ex.nominalCalls(n, items)
            if (nom <= 0) continue
            val ids = (listOf(n.id) + past.flatMap { q -> q.nodes.filter { it.type == n.type }.map { it.id } }).distinct()
            val m = dao.tokenMeans(ids, TOKEN_HISTORY)
            val perCall = m.p
            if (m.n == 0 || perCall == null) continue
            prompt += perCall * nom
            completion += (m.c ?: 0.0) * nom
            based += m.n
        }
        return if (based == 0) null else TokenEstimate(Math.round(prompt).toInt(), Math.round(completion).toInt(), based)
    }

    private fun refusals(problems: Boolean, ids: List<String>, nominal: Int): List<String> {
        val reasons = mutableListOf<String>()
        if (ids.isEmpty()) reasons += "no photos"
        if (ids.size > MAX_PHOTOS) reasons += "more than $MAX_PHOTOS photos"
        if (problems) reasons += "pipeline has problems"
        if (nominal > MAX_NOMINAL_CALLS) reasons += "more than $MAX_NOMINAL_CALLS model calls"
        return reasons
    }

    suspend fun estimate(p: Pipeline, frameIds: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Estimate {
        val ids = frameIds.distinct()
        val c = price(p, ids, labels)
        val reasons = refusals(PipelineValidator.validate(p).isNotEmpty(), ids, c.nominal)
        if (reasons.isNotEmpty()) return Estimate.Refused(reasons)
        val token = tokens.issue(PipelineHash.of(p), ids)
        synchronized(modelsByToken) { modelsByToken[token] = listOf(modelsJson(c.models)) }
        return Estimate.Ok(ids.size, c.nominal, c.max, c.models, token, c.tokens)
    }

    /**
     * The price of an A/B run: the sums of both sides, under the same limits as one run (50 photos, 200 nominal calls in total). One token, bound to
     * both pipeline hashes (A's then B's) and the frames, starts [startAb] and nothing else.
     */
    suspend fun estimateAb(a: Pipeline, b: Pipeline, frameIds: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Estimate {
        val ids = frameIds.distinct()
        val ca = price(a, ids, labels)
        val cb = price(b, ids, labels)
        val problems = PipelineValidator.validate(a).isNotEmpty() || PipelineValidator.validate(b).isNotEmpty()
        val reasons = refusals(problems, ids, ca.nominal + cb.nominal)
        if (reasons.isNotEmpty()) return Estimate.Refused(reasons)
        val token = tokens.issue(abHash(a, b), ids)
        synchronized(modelsByToken) { modelsByToken[token] = listOf(modelsJson(ca.models), modelsJson(cb.models)) }
        val t = if (ca.tokens == null && cb.tokens == null) null else TokenEstimate(
            (ca.tokens?.promptTokens ?: 0) + (cb.tokens?.promptTokens ?: 0), (ca.tokens?.completionTokens ?: 0) + (cb.tokens?.completionTokens ?: 0),
            (ca.tokens?.basedOn ?: 0) + (cb.tokens?.basedOn ?: 0),
        )
        return Estimate.Ok(ids.size, ca.nominal + cb.nominal, ca.max + cb.max, ca.models + cb.models, token, t)
    }

    private fun abHash(a: Pipeline, b: Pipeline) = PipelineHash.of(a) + "|" + PipelineHash.of(b)

    private fun modelsJson(models: List<ModelLabel>) = JsonArray(models.map {
        buildJsonObject { put("node", it.nodeId); put("endpoint", it.endpoint); put("model", it.model) }
    }).toString()

    private fun nominalOf(p: Pipeline, n: Int) = p.nodes.sumOf { node -> executors[node.type]?.nominalCalls(node, n) ?: 0 }

    private fun runRow(runId: String, pipelineId: String, version: Int?, p: Pipeline, status: String, ids: List<String>, models: String, abGroup: String?) = CanvasRunEntity(
        runId, pipelineId, version, PipelineHash.of(p), PipelineJson.canonical(p), status, ids.size, nominalOf(p, ids.size), 0, 0, clock(), null, models, abGroup,
    )

    /** Throws [Busy] when a run is active, [IllegalArgumentException] for a bad or expired token or more than [MAX_PHOTOS] photos. Returns the runId; the run continues in [scope]. */
    suspend fun start(p: Pipeline, pipelineId: String, version: Int?, frameIds: List<String>, confirmToken: String, scope: CoroutineScope): String {
        if (!lock.tryLock()) throw Busy()
        var launched = false
        try {
            val ids = frameIds.distinct()
            if (ids.size > MAX_PHOTOS) throw IllegalArgumentException("more than $MAX_PHOTOS photos")
            if (PipelineValidator.validate(p).isNotEmpty()) throw IllegalArgumentException("invalid_pipeline")
            if (!tokens.consume(confirmToken, PipelineHash.of(p), ids)) throw IllegalArgumentException("bad_token")
            val models = synchronized(modelsByToken) { modelsByToken.remove(confirmToken) }?.firstOrNull() ?: "[]"
            dao.pruneRuns(KEEP_RUNS - 1)
            val runId = newId()
            dao.insertRun(runRow(runId, pipelineId, version, p, "RUNNING", ids, models, null))
            cancelled.set(null)
            _active.value = runId
            launched = true
            // ATOMIC: the body runs (and its finally releases the runner) even if the scope is cancelled before the job starts.
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    execute(runId, p, ids)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.w(TAG, "run aborted: ${t.javaClass.simpleName}") // never let a canvas error reach the process (it hosts the glasses link)
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

    /**
     * Starts A now and B right after A ends DONE, in the same job (S31). Both rows exist when this returns: A RUNNING, B QUEUED, one `abGroup`.
     * Throws [Busy] while any run or group is active, [IllegalArgumentException] for a bad token (it is bound to both hashes) or too many photos.
     * A cancelled, failed or stopped A, or a group cancel at any time before B starts, leaves B CANCELLED with no items.
     */
    suspend fun startAb(spec: AbSpec, frameIds: List<String>, confirmToken: String, scope: CoroutineScope): AbStarted {
        if (!lock.tryLock()) throw Busy()
        var launched = false
        try {
            val ids = frameIds.distinct()
            if (ids.size > MAX_PHOTOS) throw IllegalArgumentException("more than $MAX_PHOTOS photos")
            val (a, versionA) = spec.a
            val (b, versionB) = spec.b
            if (PipelineValidator.validate(a).isNotEmpty() || PipelineValidator.validate(b).isNotEmpty()) throw IllegalArgumentException("invalid_pipeline")
            if (!tokens.consume(confirmToken, abHash(a, b), ids)) throw IllegalArgumentException("bad_token")
            val models = synchronized(modelsByToken) { modelsByToken.remove(confirmToken) }.orEmpty()
            dao.pruneRuns(KEEP_RUNS - 2) // once for the group: neither of its rows exists yet, so neither can be pruned
            val g = Group(newId(), newId(), newId())
            dao.insertRun(runRow(g.runA, spec.aId, versionA, a, "RUNNING", ids, models.getOrNull(0) ?: "[]", g.id))
            dao.insertRun(runRow(g.runB, spec.bId, versionB, b, "QUEUED", ids, models.getOrNull(1) ?: "[]", g.id))
            cancelled.set(null)
            group = g
            _active.value = g.runA
            launched = true
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    val first = execute(g.runA, a, ids)
                    if (first == "DONE" && !g.cancelled) {
                        // B's row may be gone (a delete that raced): then B must not spend a single model call.
                        if (dao.markStarted(g.runB, clock()) > 0) {
                            _active.value = g.runB
                            execute(g.runB, b, ids)
                        }
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Log.w(TAG, "group aborted: ${t.javaClass.simpleName}")
                } finally {
                    withContext(NonCancellable) {
                        // B never started (A did not end DONE, the group was cancelled, or the scope died): it must not stay QUEUED.
                        runCatching { if (dao.run(g.runB)?.status == "QUEUED") dao.updateRun(g.runB, "CANCELLED", 0, 0, clock()) }
                        group = null
                        _active.value = null
                        lock.unlock()
                    }
                }
            }
            return AbStarted(g.id, g.runA, g.runB)
        } finally {
            if (!launched) lock.unlock()
        }
    }

    /** Runs one run to a terminal row and returns the status it wrote ("DONE", "CANCELLED", "STOPPED_HOT", "FAILED"). Rethrows cancellation of the scope. */
    private suspend fun execute(runId: String, p: Pipeline, ids: List<String>): String {
        var calls = 0
        var failures = 0
        var status = "DONE"
        try {
            val ctx = context(runId)
            val gen = generation() // captured BEFORE the photos are loaded: every write below is fenced on it (S24)
            val load = photos.load(ids)
            val photoById: Map<String, ExtractPhoto> = load.photos.associateBy { it.frameId }
            val order = ids.withIndex().associate { it.value to it.index }
            val outputs = HashMap<String, Map<String, ItemFlow.Out>>()
            val pending = ArrayList<CanvasRunItemEntity>()

            // S25 lineage: a frame's downstream rows (trust, preview...) keep the batch of the model call they derive from, so a Forget of ANY frame of that
            // call reaches them (a fact from photos A+B also sits in B's preview).
            val lineage = HashMap<String, String>()

            fun write(nodeId: String, frameId: String, r: NodeResult) {
                val m = r.meta
                m?.batchId?.let { lineage[frameId] = it }
                pending += CanvasRunItemEntity(
                    runId, frameId, nodeId, if (r.error == null) "OK" else "ERROR", r.branch, r.value?.let { CanvasValueJson.encode(it) }, r.error,
                    m?.latencyMs, m?.promptTokens, m?.completionTokens, m?.model, m?.promptVersion, m?.batchId ?: lineage[frameId], m?.calls,
                )
                if (r.error != null) failures++
                calls += r.calls
            }

            /** The fenced commit of the rows gathered so far. False = a wipe or a Forget happened: nothing was written, the run stops. */
            suspend fun flush(): Boolean {
                if (pending.isEmpty()) return true
                val rows = pending.toList(); pending.clear()
                return dao.insertRunItemsFenced(rows, emptyList(), gen, emptySet(), generation)
            }

            stop@ for (node in ItemFlow.topological(p)) {
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
                    outputs[node.id] = inputs.mapValues { ItemFlow.Out(it.value, null) }
                    if (!flush()) { status = "CANCELLED"; break@stop }
                    dao.updateRun(runId, "RUNNING", calls, failures, null)
                    continue
                }
                val gathered = ItemFlow.gather(incoming, outputs)
                inputs.putAll(gathered.inputs)
                val conflicts = gathered.conflicts
                for (id in conflicts.sortedBy { order[it] }) write(node.id, id, NodeResult(id, null, error = "merge_conflict"))
                if (!flush()) { status = "CANCELLED"; break@stop }
                val ex = executors[node.type]
                val queue = inputs.entries.sortedBy { order[it.key] }.map { CanvasItem(it.key, photoById.getValue(it.key), it.value) }
                val produced = LinkedHashMap<String, ItemFlow.Out>()
                for (chunk in queue.chunked(ex?.chunkSize(node)?.coerceAtLeast(1) ?: Int.MAX_VALUE)) {
                    if (isCancelled(runId)) { status = "CANCELLED"; break@stop }
                    if (hot()) { status = "STOPPED_HOT"; break@stop }
                    // Pre-call fence: after a wipe or a Forget the loaded bytes must not be sent again (the commit fence alone only drops the write).
                    if (generation() != gen || dao.tombstonedAmong(chunk.map { it.frameId }) > 0) { status = "CANCELLED"; break@stop }
                    val results = ex?.run(node, chunk, ctx)?.associateBy { it.frameId }.orEmpty()
                    for (item in chunk) {
                        val r = results[item.frameId] ?: NodeResult(item.frameId, null, error = if (ex == null) "no_executor" else "no_result")
                        write(node.id, item.frameId, r)
                        r.value?.let { produced[item.frameId] = ItemFlow.Out(it, r.branch) }
                    }
                    if (!flush()) { Log.i(TAG, "write dropped by the wipe/forget fence"); status = "CANCELLED"; break@stop }
                    dao.updateRun(runId, "RUNNING", calls, failures, null)
                }
                outputs[node.id] = produced
            }
        } catch (c: CancellationException) {
            withContext(NonCancellable) { finish(runId, "CANCELLED", calls, failures) }
            throw c
        } catch (e: Throwable) {
            Log.w(TAG, "run failed: ${e.javaClass.simpleName}")
            status = "FAILED"
        }
        return withContext(NonCancellable) { finish(runId, status, calls, failures) }
    }

    /** The row must reach a terminal state even when the DB threw once: retry as FAILED, then give up quietly (recoverAtStart sweeps it). Returns the status written. */
    private suspend fun finish(runId: String, status: String, calls: Int, failures: Int): String {
        val ok = runCatching { dao.updateRun(runId, status, calls, failures, clock()) }.isSuccess
        if (ok) return status
        runCatching { dao.updateRun(runId, "FAILED", calls, failures, clock()) }
        return "FAILED"
    }
}

/**
 * What a finished run cost (S32): sums over the OWNER rows (the row of each provider call that carries its tokens and call count), so a three-photo
 * call counts once and every retry or fallback attempt counts. [medianLatencyMs] is over the owner rows too; null when there are none.
 */
data class RunUsage(val calls: Int, val promptTokens: Int, val completionTokens: Int, val medianLatencyMs: Long?) {
    companion object {
        fun of(items: List<CanvasRunItemEntity>): RunUsage {
            val owners = items.filter { it.calls != null }
            val latencies = owners.mapNotNull { it.latencyMs }.sorted()
            val median = when {
                latencies.isEmpty() -> null
                latencies.size % 2 == 1 -> latencies[latencies.size / 2]
                else -> (latencies[latencies.size / 2 - 1] + latencies[latencies.size / 2]) / 2
            }
            return RunUsage(owners.sumOf { it.calls ?: 0 }, owners.sumOf { it.promptTokens ?: 0 }, owners.sumOf { it.completionTokens ?: 0 }, median)
        }
    }
}
