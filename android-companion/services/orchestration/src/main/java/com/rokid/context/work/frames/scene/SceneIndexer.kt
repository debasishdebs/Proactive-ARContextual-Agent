package com.rokid.context.work.frames.scene

import android.util.Log
import androidx.room.withTransaction
import com.rokid.context.memory.ImageDecodeException
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.FrameClusterEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.work.budget.BackgroundWork
import com.rokid.context.work.budget.PhonePowerPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** [routed] frames got their routing result; [retryInMs] is when to come back if the indexer held frames back (null: nothing waiting). */
data class RouteResult(val routed: Int, val retryInMs: Long?)

/**
 * Routes each unrouted PENDING keyframe (spec P4.1): image vector -> salience -> semantic dedupe -> scene cluster.
 * With no image vectors (no IMAGE-capable space, or heat) nothing is clustered: frames stay PENDING, per photo, as in wave 1.
 * Logs ids-free counters only; recognised text and captions never reach a log.
 */
class SceneIndexer(
    private val db: ContextDatabase,
    private val images: ImageSpaces,
    private val text: TextPresence,
    private val watch: WatchVectors,
    private val power: PhonePowerPolicy?,
    private val photos: suspend (List<String>) -> Map<String, ByteArray>,
    private val clock: () -> Long,
    private val tuning: () -> SceneTuning = { SceneTuning() },
    private val foreground: () -> Boolean = { true },
) {
    private val lock = Mutex()
    private val failures = HashMap<String, Int>()
    private val ocrInFlight = AtomicInteger(0)
    private var open: OpenState? = null

    /** The one OPEN cluster, in memory: members' vectors give the centroid; [streak] is the run of drifting newest members. */
    private class OpenState(
        val clusterId: String, val spaceId: String, val openedAt: Long, var lastTs: Long, var motion: MotionClass,
        val ids: MutableList<String>, val ts: MutableList<Long>, val vecs: MutableList<FloatArray?>, val streak: MutableList<String>,
    ) {
        /** Mean of the members that are not in the drift streak (those are not yet part of the scene); all of them if that leaves none. */
        fun centroid(): FloatArray? {
            val all = vecs.indices.mapNotNull { i -> vecs[i]?.let { ids[i] to it } }
            val v = all.filter { it.first !in streak }.ifEmpty { all }.map { it.second }
            return if (v.isEmpty()) null else SceneRules.centroid(v)
        }
        fun view() = OpenClusterView(openedAt, lastTs, ids.size, motion, centroid(), streak.toList())
    }

    private class Counts { var fast = 0; var dup = 0; var clustered = 0; val closed = mutableListOf<String>(); var routed = 0 }

    /**
     * Routes up to 8 unrouted PENDING frames, oldest capture first. Returns frames routed and, if it held back, when to come back.
     * [force] (an Ask) skips the background batching wait; heat still applies.
     */
    suspend fun routeBatch(force: Boolean = false): RouteResult = lock.withLock {
        val now = clock()
        val t = tuning()
        if (power?.severe() == true) return RouteResult(0, BLOCKED_RETRY_MS)
        val moderate = power != null && !power.mayRunBackground(BackgroundWork.IMAGE_VECTORS)
        val rows = db.frameMetaDao().unroutedPending(BATCH)
        if (rows.isEmpty()) return RouteResult(0, null)
        var work = rows
        var retry: Long? = null
        if (moderate) {
            work = rows.filter { it.dwell }
            if (work.size < rows.size) retry = BLOCKED_RETRY_MS
            if (work.isEmpty()) return RouteResult(0, retry)
        } else if (!force && !foreground() && rows.none { it.dwell } && rows.size < BATCH) {
            val waited = now - rows.minOf { it.createdAt }
            if (waited < BACKGROUND_WAIT_MS) return RouteResult(0, BACKGROUND_WAIT_MS - waited)
        }

        val counts = Counts()
        val targets = if (moderate) emptyList() else try { images.targets() } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() }
        val active = targets.firstOrNull { it.active }
        val frameTs = db.frameDao().byIds(work.map { it.frameId }).associate { it.id to it.ts }
        fun tsOf(m: FrameMetaEntity) = frameTs[m.frameId] ?: m.createdAt

        if (targets.isEmpty()) {
            for (m in work) fastRoute(m, vector = null, spaceId = null, words = null, terms = null, now, counts, t)
        } else {
            val bytes = try { photos(work.map { it.frameId }) } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyMap() }
            val deferred = HashSet<String>()
            val embedded = embedAll(work.filter { it.frameId in bytes }, bytes, targets, deferred)   // frameId -> per-target vectors (null where failed)
            // The single checked write point of image vectors (shared with the backfill): a wrong width is never stored.
            val vectorWrites = db.embeddingDao()
            targets.forEachIndexed { i, tg ->
                val rows = work.mapNotNull { m -> embedded[m.frameId]?.get(i)?.let { v -> FrameVectorEntity(m.frameId, tg.spaceId, VectorCodec.encode(v)) } }
                if (rows.isNotEmpty()) vectorWrites.putImageVectors(rows, tg.imageDims) // one batched, width-checked transaction per target
            }

            for (m in work) {
                val ts = tsOf(m)
                val vs = embedded[m.frameId]
                val v = active?.let { a -> vs?.get(targets.indexOf(a)) }
                when {
                    m.frameId !in bytes -> fastRoute(m, null, null, null, null, now, counts, t)   // unreadable: the pipeline fails it
                    vs != null && active != null && v == null && m.frameId in deferred -> retry = minOf(retry ?: DEFER_RETRY_MS, DEFER_RETRY_MS) // the embedder failed: try again, never a mark
                    vs != null && active != null && v == null -> {
                        val n = (failures[m.frameId] ?: 0) + 1
                        failures[m.frameId] = n
                        if (n >= UNEMBEDDABLE_AFTER) {
                            failures.remove(m.frameId)
                            fastRoute(m, null, UNEMBEDDABLE, null, null, now, counts, t)
                        } else retry = minOf(retry ?: DEFER_RETRY_MS, DEFER_RETRY_MS)
                    }
                    v == null -> fastRoute(m, null, null, null, null, now, counts, t)            // no ACTIVE image space: wave-1 behaviour
                    else -> routeWithVector(m, ts, v, active!!, bytes[m.frameId]!!, now, counts, t)
                }
            }
        }
        Log.d(TAG, "routed=${counts.routed} fast=${counts.fast} dup=${counts.dup} clustered=${counts.clustered} closed=${counts.closed.joinToString(",").ifEmpty { "-" }}")
        RouteResult(counts.routed, retry)
    }

    /** Closes the open cluster on age (5 min) or gap (2 min); called on every pipeline pass and by the backstop. */
    suspend fun closeAged(): Boolean = lock.withLock {
        // Nothing open: nothing to close, and images.targets() (it may build an embedder) is not worth asking.
        val cur = validOpen() ?: run {
            if (db.frameClusterDao().open() == null) return false
            val active = try { images.targets().firstOrNull { it.active } } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            loadOpen(active)
        } ?: return false
        open = cur
        val reason = SceneRules.closeByTime(cur.view(), clock(), tuning()) ?: return false
        db.withTransaction { closeRow(cur, reason, emptySet(), clock(), tuning()) }
        open = null
        Log.d(TAG, "routed=0 fast=0 dup=0 clustered=0 closed=$reason")
        true
    }

    // ---- embedding ----

    /**
     * Per frame, one vector per target (null where that target failed). One batch call per target; a failed batch is retried frame by frame.
     * Only a typed [ImageDecodeException] is the photo's fault (counted towards `unembeddable`); any other failure, a wrong-count result or a
     * vector of the wrong width is the embedder's: the frame goes in [deferred] and is retried, never marked.
     */
    private suspend fun embedAll(rows: List<FrameMetaEntity>, bytes: Map<String, ByteArray>, targets: List<ImageSpaceTarget>, deferred: MutableSet<String>): Map<String, List<FloatArray?>> {
        if (rows.isEmpty()) return emptyMap()
        val perTarget = targets.map { tg ->
            val batch = tryEmbed(tg, rows.map { bytes[it.frameId]!! })?.takeIf { it.size == rows.size }
            batch ?: rows.map { m ->
                val one = tryEmbed(tg, listOf(bytes[m.frameId]!!))
                val v = one?.singleOrNull()
                if (v == null && (one == null || one.size != 1)) { if (tg.active) deferredIfNotDecode(m, one, deferred) }
                v
            }
        }
        // The width check: a vector of another width is the embedder's fault; it is not written and not routed on.
        val checked = perTarget.mapIndexed { i, vs -> vs.mapIndexed { j, v -> if (v != null && v.size != targets[i].imageDims) { if (targets[i].active) deferred += rows[j].frameId; null } else v } }
        return rows.withIndex().associate { (i, m) -> m.frameId to checked.map { it[i] } }
    }

    /** null = decode failure or a failed call; [lastFailure] tells which. */
    private var lastFailure: Throwable? = null

    private suspend fun tryEmbed(tg: ImageSpaceTarget, jpegs: List<ByteArray>): List<FloatArray>? {
        lastFailure = null
        return try { tg.embedImages(jpegs) } catch (c: CancellationException) { throw c } catch (e: Exception) { lastFailure = e; null }
    }

    private fun deferredIfNotDecode(m: FrameMetaEntity, one: List<FloatArray>?, deferred: MutableSet<String>) {
        // A decode failure counts against the photo; an embedder failure (any other exception, or a wrong-count result) does not.
        if (one != null || lastFailure !is ImageDecodeException) deferred += m.frameId
    }

    // ---- routing ----

    private suspend fun fastRoute(m: FrameMetaEntity, vector: FloatArray?, spaceId: String?, words: Int?, terms: SalienceTerms?, now: Long, c: Counts, t: SceneTuning) {
        val tm = terms ?: SceneRules.salience(m.dwell, words, null, null, t)
        db.frameMetaDao().markRouted(m.frameId, tm.total, tm.json(), null, null, spaceId, now)
        c.routed++; c.fast++
    }

    private suspend fun routeWithVector(m: FrameMetaEntity, ts: Long, v: FloatArray, active: ImageSpaceTarget, jpeg: ByteArray, now: Long, c: Counts, t: SceneTuning) {
        val motion = motionOf(m.motion)
        val dao = db.frameVectorDao()
        val novelty = window(dao.window(active.spaceId, ts - t.noveltyWindowMs, ts), m.frameId, v.size)
        val recent = window(dao.window(active.spaceId, ts - t.dedupeWindowMs, ts), m.frameId, v.size)
        val words = if (m.dwell) null else ocr(jpeg)   // dwell frames are salient anyway
        val maxCos = if (novelty.isEmpty()) 0f else novelty.maxOf { dot(v, it.second) }
        val watchCos = try {
            watch.maxCos(v, watch.vectors(active.fingerprint, v.size, active.embedQueryFull))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val terms = SceneRules.salience(m.dwell, words, maxCos, watchCos, t)
        val input = RouteInput(m.frameId, ts, m.dwell, motion, m.blur, v, words)
        val dup = SceneRules.nearestDup(v, recent, t)
        when (val r = SceneRules.route(input, terms, dup, t)) {
            Route.FastPath -> fastRoute(m, v, active.spaceId, words, terms, now, c, t)
            is Route.NearDup -> {
                val to = if (r.captionQueue) "CAPTION_QUEUED" else "NEAR_DUP"
                db.withTransaction {
                    val moved = db.frameStateDao().move(listOf(m.frameId), "PENDING", to, if (r.captionQueue) null else "semantic_dup", now)
                    db.frameMetaDao().markRouted(m.frameId, terms.total, terms.json(), if (moved > 0) r.of else null, null, active.spaceId, now)
                }
                c.routed++; c.dup++
            }
            Route.Cluster -> placeInCluster(m, input, v, terms, active, now, c, t)
        }
    }

    private suspend fun placeInCluster(m: FrameMetaEntity, input: RouteInput, v: FloatArray, terms: SalienceTerms, active: ImageSpaceTarget, now: Long, c: Counts, t: SceneTuning) {
        val cur = validOpen() ?: loadOpen(active)
        val close = when {
            cur == null -> null
            cur.ids.isNotEmpty() && cur.vecs.all { it == null } -> SceneRules.Close.Yes("no_vectors", emptyList())   // nothing to compare against: close it, reps by the fallback
            cur.spaceId.isNotEmpty() && cur.spaceId != active.spaceId -> SceneRules.Close.Yes("space_change", emptyList())
            else -> SceneRules.shouldClose(cur.view(), input, t).let { it as? SceneRules.Close.Yes }
        }
        var next: OpenState? = null
        var joined = false
        try { db.withTransaction {
            val moved = db.frameStateDao().move(listOf(m.frameId), "PENDING", "IN_CLUSTER", null, now)
            if (moved == 0) {   // an Ask claimed it meanwhile: keep the routing facts, leave the state and the cluster alone
                db.frameMetaDao().markRouted(m.frameId, terms.total, terms.json(), null, null, active.spaceId, now)
                return@withTransaction
            }
            joined = true
            val target: OpenState
            if (close != null && cur != null) {
                val carry = close.carry.toSet()
                closeRow(cur, close.reason, carry, now, t)
                val idx = cur.ids.indices.filter { cur.ids[it] in carry }
                target = OpenState(
                    "cl_${UUID.randomUUID()}", active.spaceId, idx.firstOrNull()?.let { cur.ts[it] } ?: input.ts, input.ts, MotionClass.UNKNOWN,
                    idx.map { cur.ids[it] }.toMutableList(), idx.map { cur.ts[it] }.toMutableList(), idx.map { cur.vecs[it] }.toMutableList(), mutableListOf(),
                )
                target.motion = idx.map { motionOf(db.frameMetaDao().get(cur.ids[it])?.motion) }.firstOrNull { it != MotionClass.UNKNOWN } ?: MotionClass.UNKNOWN
                for (id in carry) db.frameMetaDao().get(id)?.let { cm ->
                    db.frameMetaDao().markRouted(id, cm.salience, cm.salienceTerms, cm.nearDupOf, target.clusterId, cm.imageVectorSpace, cm.routedAt ?: now)
                }
            } else {
                target = cur ?: OpenState("cl_${UUID.randomUUID()}", active.spaceId, input.ts, input.ts, MotionClass.UNKNOWN,
                    mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf())
            }
            if (target.ids.isNotEmpty() && SceneRules.isDrifting(target.centroid(), v, t)) target.streak += m.frameId else target.streak.clear()
            target.ids += m.frameId; target.ts += input.ts; target.vecs += v
            target.lastTs = maxOf(target.lastTs, input.ts)
            if (target.motion == MotionClass.UNKNOWN) target.motion = input.motion
            db.frameClusterDao().upsert(rowOf(target))
            db.frameMetaDao().markRouted(m.frameId, terms.total, terms.json(), null, target.clusterId, active.spaceId, now)
            next = target
        } } catch (e: Exception) { open = null; throw e }   // the cached state may hold the frame already: reload from the database
        if (close != null && joined) c.closed += close.reason
        if (joined) { open = next; c.routed++; c.clustered++ } else { c.routed++ }
    }

    /** Closes [cur]'s row (reps over the members that stay, i.e. not [carry]). Call inside a transaction. */
    private suspend fun closeRow(cur: OpenState, reason: String, carry: Set<String>, now: Long, t: SceneTuning) {
        val keep = cur.ids.indices.filter { cur.ids[it] !in carry }
        val blur = db.frameMetaDao().byIds(keep.map { cur.ids[it] }).associate { it.frameId to it.blur }
        val cands = keep.mapNotNull { i -> cur.vecs[i]?.let { RepCandidate(cur.ids[i], it, blur[cur.ids[i]]) } }
        val reps = if (cands.isNotEmpty()) SceneRules.pickReps(cands, t) else spread(keep.map { cur.ids[it] }, t.maxReps)
        db.frameClusterDao().upsert(
            rowOf(cur, ids = keep.map { cur.ids[it] }, lastTs = keep.maxOfOrNull { cur.ts[it] } ?: cur.lastTs).copy(
                closedAt = now, closeReason = reason, state = "CLOSED", updatedAt = now,
                repFrameIds = org.json.JSONArray(reps).toString(),
            ),
        )
    }

    private fun rowOf(s: OpenState, ids: List<String> = s.ids, lastTs: Long = s.lastTs) = FrameClusterEntity(
        clusterId = s.clusterId, openedAt = s.openedAt, closedAt = null, closeReason = null, repFrameIds = "[]", memoryId = null,
        state = "OPEN", updatedAt = clock(), motionClass = s.motion.takeIf { it != MotionClass.UNKNOWN }?.name, memberCount = ids.size, lastTs = lastTs,
    )

    /** The open cluster from the database (after a restart): members and their vectors rebuild the centroid; the drift streak starts empty. */
    private suspend fun loadOpen(active: ImageSpaceTarget?): OpenState? {
        val row = db.frameClusterDao().open() ?: return null
        val members = db.frameMetaDao().members(row.clusterId)
        val vectors = if (active == null || members.isEmpty()) emptyMap() else
            db.frameVectorDao().forFrames(active.spaceId, members.map { it.frameId }).associate { it.frameId to it.blob }
        val tsById = db.frameDao().byIds(members.map { it.frameId }).associate { it.id to it.ts }
        val s = OpenState(
            row.clusterId, members.firstNotNullOfOrNull { m -> m.imageVectorSpace?.takeIf { it != UNEMBEDDABLE } } ?: (active?.spaceId ?: ""),
            row.openedAt, row.lastTs ?: row.openedAt,
            row.motionClass?.let { runCatching { MotionClass.valueOf(it) }.getOrNull() } ?: MotionClass.UNKNOWN,
            mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf(),
        )
        // Every member counts; a vector only feeds the centroid and the representatives where there is one.
        for (m in members) {
            s.ids += m.frameId; s.ts += tsById[m.frameId] ?: m.createdAt
            s.vecs += vectors[m.frameId]?.let { try { VectorCodec.decode(it) } catch (_: IllegalArgumentException) { null } }
        }
        return s
    }

    // ---- helpers ----

    /** The cached open cluster, unless its row is gone or no longer OPEN (Delete all, forget): then the cache is dropped and nothing stale is reused. */
    private suspend fun validOpen(): OpenState? {
        val cur = open ?: return null
        if (db.frameClusterDao().get(cur.clusterId)?.state == "OPEN") return cur
        open = null
        return null
    }

    private suspend fun ocr(jpeg: ByteArray): Int? {
        if (ocrInFlight.get() >= MAX_OCR_IN_FLIGHT) return null   // bound the OCR work: no text signal rather than a queue
        ocrInFlight.incrementAndGet()
        return try { text.words(jpeg) } catch (c: CancellationException) { throw c } catch (_: Exception) { null } finally { ocrInFlight.decrementAndGet() }
    }

    private fun window(rows: List<FrameVectorEntity>, selfId: String, dims: Int): List<Pair<String, FloatArray>> =
        rows.filter { it.frameId != selfId && it.blob.size == dims * 4 }
            .mapNotNull { r -> try { r.frameId to VectorCodec.decode(r.blob) } catch (_: IllegalArgumentException) { null } }

    private fun spread(ids: List<String>, n: Int): List<String> {
        if (ids.size <= n) return ids
        return (0 until n).map { ids[it * (ids.size - 1) / (n - 1).coerceAtLeast(1)] }.distinct()
    }

    private fun motionOf(s: String?): MotionClass = when (s?.uppercase()) {
        null, "", "UNKNOWN" -> MotionClass.UNKNOWN
        "STILL" -> MotionClass.STILL
        else -> MotionClass.MOVING
    }

    private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]; return s }

    companion object {
        private const val TAG = "PcScene"
        const val BATCH = 8
        const val BACKGROUND_WAIT_MS = 15_000L
        private const val BLOCKED_RETRY_MS = 60_000L
        private const val DEFER_RETRY_MS = 5_000L
        private const val MAX_OCR_IN_FLIGHT = 2
        private const val UNEMBEDDABLE_AFTER = 2
        const val UNEMBEDDABLE = "unembeddable"
    }
}
