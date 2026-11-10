package com.rokid.context.canvas

import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineHash
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.PipelineValidator
import com.rokid.context.ai.canvas.WatchMotion
import com.rokid.context.canvas.helper.CanvasLogSink
import com.rokid.context.canvas.moment.Moment
import com.rokid.context.canvas.moment.MomentResolver
import com.rokid.context.canvas.moment.markLineage
import com.rokid.context.canvas.moment.MomentId
import com.rokid.context.canvas.moment.SceneRunItem
import com.rokid.context.canvas.moment.SceneRunSource
import com.rokid.context.core.log.Level
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

data class ModelLabel(val nodeId: String, val endpoint: String, val model: String)

/** What a run is expected to cost in tokens, from the history of same-type nodes (per call mean x nominal calls). [basedOn] = owner rows behind it. */
data class TokenEstimate(val promptTokens: Int, val completionTokens: Int, val basedOn: Int)

sealed interface Estimate {
    data class Ok(val photos: Int, val nominal: Int, val max: Int, val models: List<ModelLabel>, val confirmToken: String, val tokens: TokenEstimate? = null,
        /** "photos", or "moments" for a Moment run (then [photos] counts Moments and [photoCount] their photos). */
        val kind: String = "photos", val photoCount: Int = photos,
        /** An A/B estimate: the price of each arm over ITS OWN frames (A first); empty for every other estimate. [photos] then counts the distinct frames of both arms. */
        val arms: List<ArmEstimate> = emptyList(),
    ) : Estimate
    data class Refused(val reasons: List<String>) : Estimate
}

/** One arm of an A/B estimate: its own frame count and price. */
data class ArmEstimate(val photos: Int, val nominal: Int, val max: Int, val models: List<ModelLabel>, val tokens: TokenEstimate?)

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
    /** Count-only lines through the one logger (never ids, tokens or provider text). */
    private val log: CanvasLogSink = CanvasLogSink { _, _, _ -> },
    /** Moment-lite: the read-only resolver a Moment run uses (estimate, start and again right after the fence capture); null = no Moment runs. */
    private val moments: MomentResolver? = null,
    /** The read-only source a Scene run uses (estimate, start and again right after the fence capture); null = no Scene runs. */
    private val scenes: SceneRunSource? = null,
    /** The recent watch values of the phone hub a watch run uses (estimate, start and again right after the fence capture); null = no watch runs. */
    private val watch: WatchRunSource? = null,
) {
    companion object {
        const val MAX_PHOTOS = 50
        const val MAX_NOMINAL_CALLS = 200
        const val KEEP_RUNS = 20
        /** Owner rows behind one per-call token mean (S32). */
        private const val TOKEN_HISTORY = 200
        const val MOMENT_KEY_PREFIX = "moment:"
        const val SCENE_KEY_PREFIX = "scene:"
        /** Lineage token of a member photo of a Moment run (the `batchId` column), so the Forget cascade finds the rows. */
        const val MEMBER_TOKEN = "mf:"
        private val NO_PHOTO = ExtractPhoto("", "", ModelImageInput("image/jpeg", ""))
    }

    class Busy : IllegalStateException("another run is in progress")

    /** A Moment run was estimated, but a Moment (a photo, a memory, a mark) changed or vanished before the start: nothing ran, estimate again. */
    class MomentsMoved : IllegalStateException("moments_moved")

    /** A Scene run was estimated, but a Scene (a member, its text, a hidden field) changed or vanished before the start: nothing ran, estimate again. */
    class ScenesMoved : IllegalStateException("scenes_moved")

    /** An A/B arm has no photo left after its own Photo node settings (typed per arm: "A" or "B"). Nothing ran. */
    class AbArmEmpty(val arm: String) : IllegalStateException("ab_arm_empty")

    /** An A/B arm's own scoped photos are not the set its estimate bound (a mark was added, a photo aged out) (typed per arm). Nothing ran, the token is spent. */
    class AbArmMoved(val arm: String) : IllegalStateException("ab_arm_moved")

    /** A watch run was estimated, but the hub's values of the window changed before the start: nothing ran, estimate again. */
    class WatchMoved : IllegalStateException("watch_moved")

    /** The Moment ids of a Moment run (chronological) and the sorted binding keys the estimate token bound. */
    private class MomentSel(val ids: List<String>, val bound: List<String>)
    private class SceneSel(val ids: List<String>, val bound: List<String>)
    /** The window of a watch run and the binding string its estimate token carried. */
    private class WatchSel(val fromMs: Long, val toMs: Long, val bound: String)

    private class Group(val id: String, val runA: String, val runB: String) {
        @Volatile var cancelled = false
        fun owns(runId: String) = runId == runA || runId == runB
    }

    private class Priced(val nominal: Int, val max: Int, val models: List<ModelLabel>, val tokens: TokenEstimate?)

    private val lock = Mutex()
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active
    private val cancelled = AtomicReference<String?>(null)
    /** The coroutine of the chunk a [HelperExecutor] is suspended in (WP8): [cancel] and [cancelGroup] cancel it so the executor's port ends the helper job instead of waiting its bounded waits out. */
    private val chunkJob = AtomicReference<Deferred<*>?>(null)
    @Volatile private var group: Group? = null
    private val modelsByToken = object : LinkedHashMap<String, List<String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > 32
    }

    /** The frame set each arm of an A/B estimate was priced over, by token: lets a start say WHICH arm moved. */
    private val armsByToken = object : LinkedHashMap<String, Pair<Set<String>, Set<String>>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Set<String>, Set<String>>>) = size > 32
    }

    /** Group rows (A/B) still QUEUED or RUNNING after a restart are CANCELLED (B must never start), plain RUNNING rows are INTERRUPTED. Returns the rows changed. */
    suspend fun recoverAtStart(): Int { val now = clock(); return dao.cancelOpenGroupRuns(now) + dao.interruptRunning(now) }

    /** Cancels [runId]: the running run at its next chunk, or either run of the live A/B group (which cancels the whole group, B included). */
    fun cancel(runId: String): Boolean {
        group?.takeIf { it.owns(runId) }?.let { it.cancelled = true; interruptChunk(); return true }
        if (_active.value != runId) return false
        cancelled.set(runId)
        interruptChunk()
        return true
    }

    /** Cancels the chunk coroutine of a [HelperExecutor], if one is suspended (the flag is always set first: a chunk that starts later sees it). */
    private fun interruptChunk() { chunkJob.get()?.cancel(CancellationException("run cancelled")) }

    /** Cancels the live A/B group [groupId], including between A's end and B's start. False when no such group is live. */
    fun cancelGroup(groupId: String): Boolean {
        val g = group?.takeIf { it.id == groupId && !it.cancelled } ?: return false
        g.cancelled = true
        interruptChunk()
        return true
    }

    /** True for the active run and for either run of the live A/B group (a QUEUED B included). */
    fun isBusy(runId: String): Boolean = _active.value == runId || group?.owns(runId) == true

    private fun isCancelled(runId: String) = cancelled.get() == runId || group?.let { it.owns(runId) && it.cancelled } == true

    /** Count-only, fixed texts: never a run, frame or node id, token, class name or provider text. A sink that throws must never reach the run. */
    private fun line(level: Level, msg: String) { try { log.log(level, "canvas", msg) } catch (_: Throwable) { } }

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

    private fun takesMoments(p: Pipeline) = p.nodes.any { it.type == NodeTypes.INPUT_MOMENTS }
    private fun takesScenes(p: Pipeline) = p.nodes.any { it.type == NodeTypes.INPUT_SCENES }
    private fun takesWatch(p: Pipeline) = p.nodes.any { it.type == NodeTypes.SRC_WATCH_MOTION }

    suspend fun estimate(p: Pipeline, frameIds: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Estimate {
        val ids = frameIds.distinct()
        if (takesMoments(p) || takesScenes(p) || takesWatch(p)) return Estimate.Refused(listOf("pipeline needs moments, scenes or watch values"))
        val c = price(p, ids, labels)
        val reasons = refusals(PipelineValidator.validate(p).isNotEmpty(), ids, c.nominal)
        if (reasons.isNotEmpty()) return Estimate.Refused(reasons)
        val token = tokens.issue(PipelineHash.of(p), ids)
        synchronized(modelsByToken) { modelsByToken[token] = listOf(modelsJson(c.models)) }
        return Estimate.Ok(ids.size, c.nominal, c.max, c.models, token, c.tokens)
    }

    /** [estimateAb] with one frame set for both arms. */
    suspend fun estimateAb(a: Pipeline, b: Pipeline, frameIds: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Estimate = estimateAb(a, b, frameIds, frameIds, labels)

    /**
     * The price of an A/B run: each arm is priced over ITS OWN frames ([idsA], [idsB]: what the arm's Photo node settings let through), the sums are under the same
     * limits as one run (50 photos per arm, 200 nominal calls in total). An arm with no frames is refused by name. One token, bound to both pipeline hashes
     * (A's then B's) and to each arm's frames, starts [startAb] and nothing else.
     */
    suspend fun estimateAb(a: Pipeline, b: Pipeline, idsA: List<String>, idsB: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Estimate {
        val ia = idsA.distinct()
        val ib = idsB.distinct()
        if (takesMoments(a) || takesMoments(b) || takesScenes(a) || takesScenes(b) || takesWatch(a) || takesWatch(b)) return Estimate.Refused(listOf("A/B runs over moments are not supported"))
        val ca = price(a, ia, labels)
        val cb = price(b, ib, labels)
        val problems = PipelineValidator.validate(a).isNotEmpty() || PipelineValidator.validate(b).isNotEmpty()
        val reasons = mutableListOf<String>()
        if (ia.isEmpty()) reasons += "arm A: no photos"
        if (ib.isEmpty()) reasons += "arm B: no photos"
        if (ia.size > MAX_PHOTOS) reasons += "arm A: more than $MAX_PHOTOS photos"
        if (ib.size > MAX_PHOTOS) reasons += "arm B: more than $MAX_PHOTOS photos"
        if (problems) reasons += "pipeline has problems"
        if (ca.nominal + cb.nominal > MAX_NOMINAL_CALLS) reasons += "more than $MAX_NOMINAL_CALLS model calls"
        if (reasons.isNotEmpty()) return Estimate.Refused(reasons)
        val token = tokens.issue(abHash(a, b), armFrames(ia, ib))
        synchronized(modelsByToken) { modelsByToken[token] = listOf(modelsJson(ca.models), modelsJson(cb.models)) }
        synchronized(armsByToken) { armsByToken[token] = ia.toSet() to ib.toSet() }
        val t = if (ca.tokens == null && cb.tokens == null) null else TokenEstimate(
            (ca.tokens?.promptTokens ?: 0) + (cb.tokens?.promptTokens ?: 0), (ca.tokens?.completionTokens ?: 0) + (cb.tokens?.completionTokens ?: 0),
            (ca.tokens?.basedOn ?: 0) + (cb.tokens?.basedOn ?: 0),
        )
        val arms = listOf(ArmEstimate(ia.size, ca.nominal, ca.max, ca.models, ca.tokens), ArmEstimate(ib.size, cb.nominal, cb.max, cb.models, cb.tokens))
        return Estimate.Ok((ia + ib).distinct().size, ca.nominal + cb.nominal, ca.max + cb.max, ca.models + cb.models, token, t, arms = arms)
    }

    /** What an A/B token binds: every frame of A and of B, each tagged with its arm (the same frame may sit in both). */
    private fun armFrames(a: List<String>, b: List<String>): List<String> = a.map { "a:$it" } + b.map { "b:$it" }

    /** The item key of a Moment in run rows (`moment:<id>`, moment-model spec 6.2): never mistaken for a frame id. */
    private fun keyOf(m: Moment) = MOMENT_KEY_PREFIX + m.id

    /** The sorted binding keys (id:rev:hash) the estimate token binds for [res]. */
    private fun bindingOf(res: List<Moment>) = res.map { it.bindingKey }.sorted()

    private fun momentIdsOf(raw: List<String>): List<String> = raw.distinct().also { l -> if (l.any { MomentId.parse(it) == null }) throw IllegalArgumentException("bad_moment_id") }.sorted()

    /**
     * The price of a Moment run. The token binds the pipeline hash, the sorted Moment ids AND each Moment's `contentRev` + `contentHash` as resolved NOW: a late photo,
     * a corrected mark or a Forget of a member before the start changes the binding, so [startMoments] refuses ([MomentsMoved]) instead of running on other data.
     */
    suspend fun estimateMoments(p: Pipeline, momentIds: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Estimate {
        val resolver = moments ?: return Estimate.Refused(listOf("moments are not available"))
        val ids = momentIdsOf(momentIds)
        val res = try { resolver.resolve(ids) } catch (e: com.rokid.context.canvas.moment.MomentTooBig) { return Estimate.Refused(listOf("a Moment has more than ${MomentResolver.FRAME_CAP} photos")) }
        val keys = res.map(::keyOf)
        val c = price(p, keys, labels)
        val reasons = mutableListOf<String>()
        if (!takesMoments(p)) reasons += "pipeline does not take moments"
        if (ids.isEmpty()) reasons += "no moments"
        if (ids.size > MAX_PHOTOS) reasons += "more than $MAX_PHOTOS moments"
        if (res.size != ids.size) reasons += "some moments have no photos any more"
        if (PipelineValidator.validate(p).isNotEmpty()) reasons += "pipeline has problems"
        if (c.nominal > MAX_NOMINAL_CALLS) reasons += "more than $MAX_NOMINAL_CALLS model calls"
        if (reasons.isNotEmpty()) return Estimate.Refused(reasons)
        val token = tokens.issue(PipelineHash.of(p), ids, bindingOf(res).joinToString("\n"))
        synchronized(modelsByToken) { modelsByToken[token] = listOf(modelsJson(c.models)) }
        return Estimate.Ok(res.size, c.nominal, c.max, c.models, token, c.tokens, kind = "moments", photoCount = res.sumOf { it.frames.size })
    }

    /** The item key of a Scene in run rows (`scene:<id>`): never mistaken for a frame id or a Moment key. */
    private fun keyOf(s: SceneRunItem) = SCENE_KEY_PREFIX + s.id
    private fun bindingOfScenes(res: List<SceneRunItem>) = res.map { it.binding }.sorted()
    private fun sceneIdsOf(raw: List<String>): List<String> = raw.distinct().also { l -> if (l.any { it.length < 2 || it[0] != 'S' || MomentId.parse(it.substring(1)) == null }) throw IllegalArgumentException("bad_scene_id") }.sorted()

    /** The price of a Scene run. The token binds the pipeline hash, the sorted Scene ids AND what each Scene would hand a node now (its text, members and member photos). */
    suspend fun estimateScenes(p: Pipeline, sceneIds: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Estimate {
        val source = scenes ?: return Estimate.Refused(listOf("scenes are not available"))
        val ids = sceneIdsOf(sceneIds)
        val res = try { source.resolve(ids) } catch (e: com.rokid.context.canvas.moment.MomentTooBig) { return Estimate.Refused(listOf("a Moment has more than ${MomentResolver.FRAME_CAP} photos")) }
        val c = price(p, res.map(::keyOf), labels)
        val reasons = mutableListOf<String>()
        if (!takesScenes(p)) reasons += "pipeline does not take scenes"
        if (ids.isEmpty()) reasons += "no scenes"
        if (ids.size > MAX_PHOTOS) reasons += "more than $MAX_PHOTOS scenes"
        if (res.size != ids.size) reasons += "some scenes do not exist any more"
        if (PipelineValidator.validate(p).isNotEmpty()) reasons += "pipeline has problems"
        if (c.nominal > MAX_NOMINAL_CALLS) reasons += "more than $MAX_NOMINAL_CALLS model calls"
        if (reasons.isNotEmpty()) return Estimate.Refused(reasons)
        val token = tokens.issue(PipelineHash.of(p), ids, bindingOfScenes(res).joinToString("\n"))
        synchronized(modelsByToken) { modelsByToken[token] = listOf(modelsJson(c.models)) }
        return Estimate.Ok(res.size, c.nominal, c.max, c.models, token, c.tokens, kind = "scenes", photoCount = res.sumOf { it.frameIds.size })
    }

    /** Starts a Scene run: the Moment run's rules, with [ScenesMoved] when anything a Scene would hand a node changed since the estimate. */
    suspend fun startScenes(p: Pipeline, pipelineId: String, version: Int?, sceneIds: List<String>, confirmToken: String, scope: CoroutineScope): String {
        val source = scenes ?: throw IllegalArgumentException("scenes are not available")
        if (!lock.tryLock()) throw Busy()
        var launched = false
        try {
            val ids = sceneIdsOf(sceneIds)
            if (ids.size > MAX_PHOTOS) throw IllegalArgumentException("more than $MAX_PHOTOS scenes")
            if (PipelineValidator.validate(p).isNotEmpty()) throw IllegalArgumentException("invalid_pipeline")
            if (!takesScenes(p)) throw IllegalArgumentException("pipeline does not take scenes")
            val res = try { source.resolve(ids) } catch (e: com.rokid.context.canvas.moment.MomentTooBig) { throw ScenesMoved() }
            val bound = bindingOfScenes(res)
            when (tokens.consumeBound(confirmToken, PipelineHash.of(p), ids, bound.joinToString("\n"))) {
                ConfirmTokens.Verdict.BAD -> throw IllegalArgumentException("bad_token")
                ConfirmTokens.Verdict.MOVED -> { line(Level.I, "scenes moved since the estimate"); throw ScenesMoved() }
                ConfirmTokens.Verdict.OK -> Unit
            }
            if (res.size != ids.size) throw ScenesMoved()
            val models = synchronized(modelsByToken) { modelsByToken.remove(confirmToken) }?.firstOrNull() ?: "[]"
            dao.pruneRuns(KEEP_RUNS - 1)
            val runId = newId()
            val keys = res.map(::keyOf)
            dao.insertRun(runRow(runId, pipelineId, version, p, "RUNNING", keys, models, null))
            cancelled.set(null)
            _active.value = runId
            launched = true
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    execute(runId, p, keys, ssel = SceneSel(ids, bound))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    line(Level.W, "run aborted")
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

    /** The id the token binds: the window itself, so a value that arrives later changes the BINDING (watch_moved), not the id set (bad_token). */
    private fun windowKey(fromMs: Long, toMs: Long) = "watch-window:$fromMs:$toMs"
    private fun watchItems(samples: List<com.rokid.context.core.model.context.WatchSample>) = WatchMotion.items(samples)
    private fun watchKeys(items: List<WatchMotion.Item>) = if (items.isEmpty()) listOf(WatchMotion.NO_DATA_KEY) else items.map { it.key }

    /**
     * The price of a watch run over the hub's values between [fromMs] and [toMs] (a watch run makes no model call: every node of the watch chain is pure). The token binds the pipeline
     * hash, the item keys AND each value's score as read NOW, so a value that arrived since makes [startWatch] refuse ([WatchMoved]). An empty window is a valid run of ONE item that ends
     * in the error NO_DATA (never a zero).
     */
    suspend fun estimateWatch(p: Pipeline, fromMs: Long, toMs: Long, labels: suspend (PipelineNode) -> ModelLabel?): Estimate {
        val source = watch ?: return Estimate.Refused(listOf("watch values are not available"))
        val reasons = mutableListOf<String>()
        if (toMs < fromMs) reasons += "the window ends before it starts"
        val items = if (toMs < fromMs) emptyList() else watchItems(source.recent(fromMs, toMs))
        val keys = watchKeys(items)
        val c = price(p, keys, labels)
        if (!takesWatch(p)) reasons += "pipeline does not take watch values"
        if (items.size > MAX_PHOTOS) reasons += "more than $MAX_PHOTOS watch values"
        if (PipelineValidator.validate(p).isNotEmpty()) reasons += "pipeline has problems"
        if (c.nominal > MAX_NOMINAL_CALLS) reasons += "more than $MAX_NOMINAL_CALLS model calls"
        if (reasons.isNotEmpty()) return Estimate.Refused(reasons)
        val token = tokens.issue(PipelineHash.of(p), listOf(windowKey(fromMs, toMs)), WatchMotion.binding(items))
        synchronized(modelsByToken) { modelsByToken[token] = listOf(modelsJson(c.models)) }
        return Estimate.Ok(items.size, c.nominal, c.max, c.models, token, c.tokens, kind = "watch", photoCount = 0)
    }

    /** Starts a watch run: the Scene run's rules, with [WatchMoved] when the values of the window are not the ones the estimate bound. */
    suspend fun startWatch(p: Pipeline, pipelineId: String, version: Int?, fromMs: Long, toMs: Long, confirmToken: String, scope: CoroutineScope): String {
        val source = watch ?: throw IllegalArgumentException("watch values are not available")
        if (!lock.tryLock()) throw Busy()
        var launched = false
        try {
            if (toMs < fromMs) throw IllegalArgumentException("bad_window")
            if (PipelineValidator.validate(p).isNotEmpty()) throw IllegalArgumentException("invalid_pipeline")
            if (!takesWatch(p)) throw IllegalArgumentException("pipeline does not take watch values")
            val items = watchItems(source.recent(fromMs, toMs))
            if (items.size > MAX_PHOTOS) throw IllegalArgumentException("more than $MAX_PHOTOS watch values")
            val keys = watchKeys(items)
            val bound = WatchMotion.binding(items)
            when (tokens.consumeBound(confirmToken, PipelineHash.of(p), listOf(windowKey(fromMs, toMs)), bound)) {
                ConfirmTokens.Verdict.BAD -> throw IllegalArgumentException("bad_token")
                ConfirmTokens.Verdict.MOVED -> { line(Level.I, "watch values moved since the estimate"); throw WatchMoved() }
                ConfirmTokens.Verdict.OK -> Unit
            }
            val models = synchronized(modelsByToken) { modelsByToken.remove(confirmToken) }?.firstOrNull() ?: "[]"
            dao.pruneRuns(KEEP_RUNS - 1)
            val runId = newId()
            dao.insertRun(runRow(runId, pipelineId, version, p, "RUNNING", keys, models, null))
            cancelled.set(null)
            _active.value = runId
            launched = true
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    execute(runId, p, keys, wsel = WatchSel(fromMs, toMs, bound))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    line(Level.W, "run aborted")
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
            if (takesMoments(p) || takesScenes(p) || takesWatch(p)) throw IllegalArgumentException("pipeline needs moments, scenes or watch values")
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
                    line(Level.W, "run aborted") // never let a canvas error reach the process (it hosts the glasses link)
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
     * Starts a Moment run. Same rules as [start], and the freshness binding: the Moments are re-resolved NOW and their `contentRev` + `contentHash` must equal what the
     * estimate bound, else [MomentsMoved] (typed, nothing runs, the token is spent). The run re-resolves once more right after the fence capture and, if anything moved,
     * ends CANCELLED having sent and written nothing.
     */
    suspend fun startMoments(p: Pipeline, pipelineId: String, version: Int?, momentIds: List<String>, confirmToken: String, scope: CoroutineScope): String {
        val resolver = moments ?: throw IllegalArgumentException("moments are not available")
        if (!lock.tryLock()) throw Busy()
        var launched = false
        try {
            val ids = momentIdsOf(momentIds)
            if (ids.size > MAX_PHOTOS) throw IllegalArgumentException("more than $MAX_PHOTOS moments")
            if (PipelineValidator.validate(p).isNotEmpty()) throw IllegalArgumentException("invalid_pipeline")
            if (!takesMoments(p)) throw IllegalArgumentException("pipeline does not take moments")
            val res = try { resolver.resolve(ids) } catch (e: com.rokid.context.canvas.moment.MomentTooBig) { throw MomentsMoved() }
            val bound = bindingOf(res)
            when (tokens.consumeBound(confirmToken, PipelineHash.of(p), ids, bound.joinToString("\n"))) {
                ConfirmTokens.Verdict.BAD -> throw IllegalArgumentException("bad_token")
                ConfirmTokens.Verdict.MOVED -> { line(Level.I, "moments moved since the estimate"); throw MomentsMoved() }
                ConfirmTokens.Verdict.OK -> Unit
            }
            if (res.size != ids.size) throw MomentsMoved()
            val models = synchronized(modelsByToken) { modelsByToken.remove(confirmToken) }?.firstOrNull() ?: "[]"
            dao.pruneRuns(KEEP_RUNS - 1)
            val runId = newId()
            val keys = res.map(::keyOf)
            dao.insertRun(runRow(runId, pipelineId, version, p, "RUNNING", keys, models, null))
            cancelled.set(null)
            _active.value = runId
            launched = true
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    execute(runId, p, keys, MomentSel(ids, bound))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    line(Level.W, "run aborted")
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
    suspend fun startAb(spec: AbSpec, frameIds: List<String>, confirmToken: String, scope: CoroutineScope): AbStarted = startAb(spec, frameIds, frameIds, confirmToken, scope)

    /** [startAb] with each arm's own frames ([idsA], [idsB]); an empty arm or an arm whose set is not the estimate's is a typed [AbArmEmpty] / [AbArmMoved]. */
    suspend fun startAb(spec: AbSpec, idsA: List<String>, idsB: List<String>, confirmToken: String, scope: CoroutineScope): AbStarted {
        if (!lock.tryLock()) throw Busy()
        var launched = false
        try {
            val ia = idsA.distinct()
            val ib = idsB.distinct()
            val estimated = synchronized(armsByToken) { armsByToken.remove(confirmToken) }
            if (ia.size > MAX_PHOTOS || ib.size > MAX_PHOTOS) throw IllegalArgumentException("more than $MAX_PHOTOS photos")
            val (a, versionA) = spec.a
            val (b, versionB) = spec.b
            if (PipelineValidator.validate(a).isNotEmpty() || PipelineValidator.validate(b).isNotEmpty()) throw IllegalArgumentException("invalid_pipeline")
            if (takesMoments(a) || takesMoments(b) || takesScenes(a) || takesScenes(b) || takesWatch(a) || takesWatch(b)) throw IllegalArgumentException("A/B runs over moments are not supported")
            if (ia.isEmpty()) { tokens.consume(confirmToken, "", emptyList()); throw AbArmEmpty("A") }
            if (ib.isEmpty()) { tokens.consume(confirmToken, "", emptyList()); throw AbArmEmpty("B") }
            if (!tokens.consume(confirmToken, abHash(a, b), armFrames(ia, ib))) {
                if (estimated != null) {
                    if (estimated.first != ia.toSet()) throw AbArmMoved("A")
                    if (estimated.second != ib.toSet()) throw AbArmMoved("B")
                }
                throw IllegalArgumentException("bad_token")
            }
            val models = synchronized(modelsByToken) { modelsByToken.remove(confirmToken) }.orEmpty()
            dao.pruneRuns(KEEP_RUNS - 2) // once for the group: neither of its rows exists yet, so neither can be pruned
            val g = Group(newId(), newId(), newId())
            dao.insertRun(runRow(g.runA, spec.aId, versionA, a, "RUNNING", ia, models.getOrNull(0) ?: "[]", g.id))
            dao.insertRun(runRow(g.runB, spec.bId, versionB, b, "QUEUED", ib, models.getOrNull(1) ?: "[]", g.id))
            cancelled.set(null)
            group = g
            _active.value = g.runA
            launched = true
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    val first = execute(g.runA, a, ia)
                    if (first == "DONE" && !g.cancelled) {
                        // B's row may be gone (a delete that raced): then B must not spend a single model call.
                        if (dao.markStarted(g.runB, clock()) > 0) {
                            _active.value = g.runB
                            execute(g.runB, b, ib)
                        }
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    line(Level.W, "group aborted")
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
    private suspend fun execute(runId: String, p: Pipeline, ids: List<String>, sel: MomentSel? = null, ssel: SceneSel? = null, wsel: WatchSel? = null): String {
        var calls = 0
        var failures = 0
        var status = "DONE"
        try {
            val ctx = context(runId)
            val gen = generation() // captured BEFORE the photos are loaded: every write below is fenced on it (S24)
            // WP8: a pipeline with a helper node also captures the helper fence (wipe generation, deletion epoch, frame-Forget revision) BEFORE the frames load; the run's own
            // write below stays the existing fenced path (insertRunItemsFenced) on the wipe generation and the tombstones.
            val helperFence = p.nodes.firstNotNullOfOrNull { executors[it.type] as? HelperExecutor }?.captureFence()
            // Moment run: re-resolve AFTER the fence capture (a Forget from here on moves the fence); if anything differs from what the estimate bound, nothing is sent or written.
            val momentByKey: Map<String, Moment> = if (sel == null) emptyMap() else {
                val res = moments?.resolve(sel.ids).orEmpty()
                if (bindingOf(res) != sel.bound) { line(Level.I, "moments moved before the run"); return withContext(NonCancellable) { finish(runId, "CANCELLED", calls, failures) } }
                res.associateBy(::keyOf)
            }
            val sceneByKey: Map<String, SceneRunItem> = if (ssel == null) emptyMap() else {
                val res = scenes?.resolve(ssel.ids).orEmpty()
                if (bindingOfScenes(res) != ssel.bound) { line(Level.I, "scenes moved before the run"); return withContext(NonCancellable) { finish(runId, "CANCELLED", calls, failures) } }
                res.associateBy(::keyOf)
            }
            // Watch run: re-read AFTER the fence capture; the hub's values must still be the ones the estimate bound. No photo, no Moment: nothing to Forget-cascade.
            val watchByKey: Map<String, WatchMotion.Item> = if (wsel == null) emptyMap() else {
                val res = watchItems(watch?.recent(wsel.fromMs, wsel.toMs).orEmpty())
                if (WatchMotion.binding(res) != wsel.bound) { line(Level.I, "watch values moved before the run"); return withContext(NonCancellable) { finish(runId, "CANCELLED", calls, failures) } }
                res.associateBy { it.key }
            }
            val members: Map<String, List<String>> = momentByKey.mapValues { (_, m) -> m.frames.map { it.id } } + sceneByKey.mapValues { (_, s) -> s.frameIds }
            // codex r12: what a Moment or Scene item's words come from: its member photos and memories, declared on every model call over it
            val itemSources: Map<String, com.rokid.context.model.PayloadSources> =
                momentByKey.mapValues { (_, m) -> com.rokid.context.model.PayloadSources(m.frames.map { it.id }.toSet(), m.memories.map { it.id }.toSet(), markLineage(m).toSet()) } +
                    sceneByKey.mapValues { (_, s) -> com.rokid.context.model.PayloadSources(s.frameIds.toSet(), s.memoryIds.toSet(), s.markIds.toSet()) }
            val allMembers: Set<String> = members.values.flatten().toSet()
            val photoById: Map<String, ExtractPhoto> = if (sel == null && ssel == null && wsel == null) photos.load(ids).photos.associateBy { it.frameId } else emptyMap()
            val order = ids.withIndex().associate { it.value to it.index }
            /** Codex r13/r14: per item id, the sources its current value carries ([NodeProvenance], set at the execution boundary below). */
            val carried = HashMap<String, com.rokid.context.model.PayloadSources>()
            val outputs = HashMap<String, Map<String, ItemFlow.Out>>()
            val pending = ArrayList<CanvasRunItemEntity>()

            // S25 lineage: a frame's downstream rows (trust, preview...) keep the batch of the model call they derive from, so a Forget of ANY frame of that
            // call reaches them (a fact from photos A+B also sits in B's preview).
            // a Moment's rows derive from every member photo: each member is a lineage token ("mf:<frameId>"), so a Forget of any member deletes them (CanvasDao.deleteCanvasOutputsForFrames)
            val lineage = HashMap<String, LinkedHashSet<String>>() // frame -> every model call whose output its rows derive from (a later per-frame call must not hide a shared one)

            members.forEach { (k, f) -> lineage[k] = LinkedHashSet(f.map { MEMBER_TOKEN + it }) }
            // every mark of a Moment is lineage (a MARK Moment has no photo; a FRAME Moment lists the marks inside it too): a Forget or undo of the mark deletes the rows, see CanvasSlice3Dao.deleteRunRowsOfLabels; a Scene run lists the marks of all its Moments
            val markLineageOf: Map<String, List<String>> = momentByKey.mapValues { (_, m) -> markLineage(m) } + sceneByKey.mapValues { (_, s) -> s.markIds }
            markLineageOf.forEach { (k, ids) -> if (ids.isNotEmpty()) lineage.getOrPut(k) { LinkedHashSet() }.addAll(ids.map { MEMBER_TOKEN + it }) }
            val allMarks: Set<String> = markLineageOf.values.flatten().toSet()

            fun write(nodeId: String, frameId: String, r: NodeResult) {
                val m = r.meta
                val batch = CanvasLineage.compose(lineage.getOrPut(frameId) { LinkedHashSet() }, m?.batchId)
                pending += CanvasRunItemEntity(
                    runId, frameId, nodeId, if (r.error == null) "OK" else "ERROR", r.branch, r.value?.let { CanvasValueJson.encode(it) }, r.error,
                    m?.latencyMs, m?.promptTokens, m?.completionTokens, m?.model, m?.promptVersion, batch, m?.calls,
                )
                if (r.error != null) failures++
                calls += r.calls
            }

            /** The fenced commit of the rows gathered so far. False = a wipe or a Forget happened: nothing was written, the run stops. */
            suspend fun flush(): Boolean {
                if (pending.isEmpty()) return true
                val rows = pending.toList(); pending.clear()
                return dao.insertRunItemsFenced(rows, emptyList(), gen, allMembers, allMarks, generation)
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
                if (node.type == NodeTypes.INPUT_MOMENTS) {
                    for (id in ids) {
                        val m = momentByKey[id]
                        if (m != null) {
                            val v = CanvasValue.Json(momentItem(m))
                            write(node.id, id, NodeResult(id, v))
                            inputs[id] = v
                        } else write(node.id, id, NodeResult(id, null, error = "unreadable"))
                    }
                    outputs[node.id] = inputs.mapValues { ItemFlow.Out(it.value, null) }
                    if (!flush()) { status = "CANCELLED"; break@stop }
                    dao.updateRun(runId, "RUNNING", calls, failures, null)
                    continue
                }
                if (node.type == NodeTypes.INPUT_SCENES) {
                    for (id in ids) {
                        val s = sceneByKey[id]
                        if (s != null) {
                            val v = CanvasValue.Json(s.json)
                            write(node.id, id, NodeResult(id, v))
                            inputs[id] = v
                        } else write(node.id, id, NodeResult(id, null, error = "unreadable"))
                    }
                    outputs[node.id] = inputs.mapValues { ItemFlow.Out(it.value, null) }
                    if (!flush()) { status = "CANCELLED"; break@stop }
                    dao.updateRun(runId, "RUNNING", calls, failures, null)
                    continue
                }
                if (node.type == NodeTypes.SRC_WATCH_MOTION) {
                    for (id in ids) {
                        val w = watchByKey[id]
                        if (w != null) {
                            val v = CanvasValue.Json(w.json)
                            write(node.id, id, NodeResult(id, v))
                            inputs[id] = v
                        } else write(node.id, id, NodeResult(id, null, error = WatchMotion.NO_DATA)) // the empty window's one item: absence is an error, never a zero
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
                val queue = inputs.entries.sortedBy { order[it.key] }.map { CanvasItem(it.key, photoById[it.key] ?: NO_PHOTO, it.value, carried[it.key] ?: itemSources[it.key] ?: com.rokid.context.model.PayloadSources.NONE) }
                val produced = LinkedHashMap<String, ItemFlow.Out>()
                val provenance = NodeProvenance(ex)
                for (chunk in queue.chunked(ex?.chunkSize(node)?.coerceAtLeast(1) ?: Int.MAX_VALUE)) {
                    provenance.consume(chunk)
                    if (isCancelled(runId)) { status = "CANCELLED"; break@stop }
                    if (hot()) { status = "STOPPED_HOT"; break@stop }
                    // Pre-call fence: after a wipe or a Forget the loaded bytes must not be sent again (the commit fence alone only drops the write).
                    if (generation() != gen || dao.tombstonedAmong(chunk.flatMap { members[it.frameId] ?: listOf(it.frameId) }) > 0 || dao.labelsMissing(chunk.flatMap { markLineageOf[it.frameId].orEmpty() }) > 0) { status = "CANCELLED"; break@stop }
                    val ran = if (ex is HelperExecutor && helperFence != null) runInterruptible(runId, ex, node, chunk, ctx, helperFence, members) else if (ex == null) emptyList() else ex.run(node, chunk, ctx) // no executor: per-item no_executor, not a cancel
                    if (ran == null) { status = "CANCELLED"; break@stop } // cancelled while the chunk was suspended in the helper executor
                    val results = ran.associateBy { it.frameId }
                    for (item in chunk) {
                        val r = results[item.frameId] ?: NodeResult(item.frameId, null, error = if (ex == null) "no_executor" else "no_result")
                        // codex r14 RULE A: the union of the whole execution's inputs; r15: its photos also become the row's lineage ("mf:<frameId>"),
                        // so a Forget of any of them deletes the row and a later Save links the saved memory to all of them
                        val c = provenance.of(r.value)
                        lineage.getOrPut(item.frameId) { LinkedHashSet() }.addAll(c.frameIds.map { MEMBER_TOKEN + it })
                        write(node.id, item.frameId, r)
                        r.value?.let { produced[item.frameId] = ItemFlow.Out(it, r.branch); carried[item.frameId] = c }
                    }
                    if (!flush()) { line(Level.I, "write dropped by the wipe/forget fence"); status = "CANCELLED"; break@stop }
                    dao.updateRun(runId, "RUNNING", calls, failures, null)
                    // a Forget voided the helper job (it voids EVERY open job): later nodes would only fail the same way, so the run ends with a status of its own
                    if (results.values.any { it.error == PythonExecutor.FRAME_FORGET }) { status = "STOPPED_FORGET"; break@stop }
                }
                outputs[node.id] = produced
            }
        } catch (c: CancellationException) {
            withContext(NonCancellable) { finish(runId, "CANCELLED", calls, failures) }
            throw c
        } catch (e: Throwable) {
            line(Level.W, "run failed")
            status = "FAILED"
        }
        return withContext(NonCancellable) { finish(runId, status, calls, failures) }
    }

    /**
     * Runs one chunk of a [HelperExecutor] in its own coroutine so [cancel] / [cancelGroup] can interrupt it: the executor's port then ends the helper job. Returns null when this
     * run was cancelled meanwhile (the caller ends the run CANCELLED); a cancellation of the run's own scope is rethrown, as before. The fence the runner captured before the frames
     * loaded travels in the coroutine context ([RunHelperFence]).
     */
    private suspend fun runInterruptible(runId: String, ex: NodeExecutor, node: PipelineNode, chunk: List<CanvasItem>, ctx: RunContext, fence: com.rokid.context.memory.canvas.HelperFence, members: Map<String, List<String>>): List<NodeResult>? =
        coroutineScope {
            val d = async(RunHelperFence(fence) + (if (members.isEmpty()) EmptyCoroutineContext else RunMomentFrames(members)), start = CoroutineStart.LAZY) { ex.run(node, chunk, ctx) }
            chunkJob.set(d)
            try {
                // the flag is set before the chunk job is read by cancel(), and the job is published before the flag is read here: one of the two always sees the other
                if (isCancelled(runId)) { d.cancel(); null } else { d.start(); d.await() }
            } catch (c: CancellationException) {
                if (isActive && isCancelled(runId)) null else throw c
            } finally {
                chunkJob.compareAndSet(d, null)
            }
        }

    /**
     * What the helper receives for one Moment (moment-model spec 9, minus what this slice does not send): no frame id, no image bytes, no file ref. [photos] carries only the
     * capture time of each photo (so its length is the photo count; a MARK Moment has NO photo and says so: `type` MARK, `photos` empty); `marks` carries the time and code (tap, voice,
     * phone_voice, retro, ask_tap) of each mark, never a word; `sensor` is null (no sensor input yet); `text` is the Moment's text object (its timestamp and the visible memory text of
     * its photos, or null when it has none; the voice transcript, intent and summary are separate objects and never in it). Integers and strings only.
     */
    private fun momentItem(m: Moment): JsonObject = JsonObject(mapOf(
        "itemType" to JsonPrimitive("moment"),
        "momentId" to JsonPrimitive(m.id),
        "contentRev" to JsonPrimitive(m.contentRev),
        "contentHash" to JsonPrimitive(m.contentHash),
        "startMs" to JsonPrimitive(m.startMs),
        "type" to JsonPrimitive(m.type.name),
        "photos" to JsonArray(m.frames.map { JsonObject(mapOf("tsMs" to JsonPrimitive(it.ts))) }),
        "marks" to JsonArray(m.marks.map { JsonObject(mapOf("tsMs" to JsonPrimitive(it.atMs), "code" to JsonPrimitive(it.code))) }),
        "sensor" to JsonNull,
        "text" to (m.text?.let { JsonPrimitive(it) } ?: JsonNull),
    ))

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
