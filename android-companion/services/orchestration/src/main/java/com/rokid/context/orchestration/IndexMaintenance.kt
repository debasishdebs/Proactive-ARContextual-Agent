package com.rokid.context.orchestration

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.embed.Canaries
import com.rokid.context.ai.embed.Embedders
import com.rokid.context.ai.embed.LocalModelPausedException
import com.rokid.context.memory.Coverage
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.IndexHealth
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.ImageDecodeException
import com.rokid.context.memory.Modality
import com.rokid.context.memory.SpaceEmbedderProvider
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.ImagePut
import com.rokid.context.memory.db.ImageSwapHealth
import com.rokid.context.memory.db.SpaceState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException

data class BoundEmbedder(val embedder: Embedder, val configJson: String, val dim: Int)

/**
 * A vector whose size differs from its space's dimension (`EmbeddingSpaces.put` refuses it). The endpoint's config no
 * longer matches the space, so retrying cannot help: the worker records it and stops (Task 8 carry).
 */
class IndexConfigException(message: String) : Exception(message)

/** The image embedder misbehaved (a wrong-count result): retryable, and never a reason to mark a photo. */
class ImageEmbedderException(message: String) : Exception(message)

/** What [IndexMaintenance] needs from `:services:ai`. */
interface IndexEmbedders : SpaceEmbedderProvider {
    /** The EMBED binding, probed (dimension, revision); null = no binding (semantic stays off). */
    suspend fun bound(): BoundEmbedder?
}

class AiIndexEmbedders(private val embedders: Embedders) : IndexEmbedders {
    override suspend fun bound(): BoundEmbedder? = embedders.forBinding()?.let { BoundEmbedder(embedders.gated(it), it.spec.configJson(), it.spec.dim) }
    override suspend fun forSpace(space: EmbeddingSpaceEntity): Embedder? = embedders.forSpace(space)
}

/** One photo as the backfill sees it: the bytes, a file that is gone (the frame row or JPEG is missing), or one that could not be opened just now (read or Keystore-unseal failure). */
sealed interface PhotoRead {
    class Bytes(val data: ByteArray) : PhotoRead
    object Missing : PhotoRead
    object Unreadable : PhotoRead
}

/** Counts failed reads per photo; attempts less than [IndexMaintenance.READ_RETRY_GAP_MS] apart count once. */
interface ReadAttempts {
    /** Records a failed read at [at]; returns how many attempts (spaced apart) the photo has now. */
    fun record(frameId: String, at: Long): Int
    fun forget(frameId: String)
}

class InMemoryReadAttempts : ReadAttempts {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    override fun record(frameId: String, at: Long): Int = map.compute(frameId) { _, v ->
        if (v == null) 1 to at else if (at - v.second >= IndexMaintenance.READ_RETRY_GAP_MS) (v.first + 1) to at else v
    }!!.first
    override fun forget(frameId: String) { map.remove(frameId) }
}

class PrefsReadAttempts(context: Context) : ReadAttempts {
    private val prefs = context.applicationContext.getSharedPreferences("image_read_attempts", Context.MODE_PRIVATE)
    @Synchronized override fun record(frameId: String, at: Long): Int {
        val v = prefs.getString(frameId, null)?.split(',')?.takeIf { it.size == 2 }?.let { (it[0].toIntOrNull() ?: 0) to (it[1].toLongOrNull() ?: 0L) }
        val next = if (v == null) 1 to at else if (at - v.second >= IndexMaintenance.READ_RETRY_GAP_MS) (v.first + 1) to at else v
        prefs.edit().putString(frameId, "${next.first},${next.second}").commit()
        return next.first
    }
    @Synchronized override fun forget(frameId: String) { prefs.edit().remove(frameId).commit() }
}

/** The last pre-swap drift discard: which model, when, and how many in a row for that model. */
data class DiscardRecord(val fingerprint: String, val at: Long, val streak: Int)

/** Small durable state of the index job (what must survive a process death or a pause). */
interface IndexClock {
    fun lastCanaryAt(): Long
    fun markCanary(at: Long)
    /** A Rebuild asked for while the pipeline was paused; honoured by the first run after resume. */
    fun rebuildPending(): Boolean
    fun setRebuildPending(pending: Boolean)
    /** The last Rebuild request already applied, so a re-delivered request does not rebuild twice. */
    fun consumedRebuild(): String?
    fun consumeRebuild(token: String)
    /** The request id of the pending Rebuild (Phase 5 T11 carry): the run that honours it records it as consumed. */
    fun pendingRebuildToken(): String? = null
    fun setPendingRebuildToken(token: String?) = Unit
    fun lastDiscard(): DiscardRecord?
    /** null clears it (a successful swap, or the user's Rebuild). */
    fun recordDiscard(record: DiscardRecord?)
    /** "Index unstable": auto-rebuild stopped after repeated drift discards; only a manual Rebuild resumes it. */
    fun unstable(): Boolean = (lastDiscard()?.streak ?: 0) >= IndexMaintenance.MAX_DISCARD_STREAK
}

class PrefsIndexClock(context: Context) : IndexClock {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    override fun lastCanaryAt() = prefs.getLong(K_CANARY, 0L)
    override fun markCanary(at: Long) { prefs.edit().putLong(K_CANARY, at).apply() }
    override fun rebuildPending() = prefs.getBoolean(K_PENDING, false)
    override fun setRebuildPending(pending: Boolean) { prefs.edit().putBoolean(K_PENDING, pending).commit() }
    override fun consumedRebuild(): String? = prefs.getString(K_TOKEN, null)
    override fun consumeRebuild(token: String) { prefs.edit().putString(K_TOKEN, token).commit() }
    override fun pendingRebuildToken(): String? = prefs.getString(K_PENDING_TOKEN, null)
    override fun setPendingRebuildToken(token: String?) { prefs.edit().apply { if (token == null) remove(K_PENDING_TOKEN) else putString(K_PENDING_TOKEN, token) }.commit() }
    override fun lastDiscard(): DiscardRecord? {
        val fp = prefs.getString(K_DISCARD_FP, null) ?: return null
        return DiscardRecord(fp, prefs.getLong(K_DISCARD_AT, 0L), prefs.getInt(K_DISCARD_STREAK, 0))
    }
    override fun recordDiscard(record: DiscardRecord?) {
        prefs.edit().apply {
            if (record == null) { remove(K_DISCARD_FP); remove(K_DISCARD_AT); remove(K_DISCARD_STREAK) }
            else { putString(K_DISCARD_FP, record.fingerprint); putLong(K_DISCARD_AT, record.at); putInt(K_DISCARD_STREAK, record.streak) }
        }.commit()
    }

    /** Consecutive runs that ended with work left (MORE); the worker's continuation delay grows with it. */
    fun moreStreak(): Int = prefs.getInt(K_MORE_STREAK, 0)
    fun setMoreStreak(n: Int) { if (moreStreak() != n) prefs.edit().putInt(K_MORE_STREAK, n).apply() }

    /** Gaps G6: on-device index work is waiting for a charger (banner "On-device search paused to save battery"). */
    fun batteryPaused(): Boolean = prefs.getBoolean(K_BATTERY_PAUSED, false)
    fun setBatteryPaused(paused: Boolean) { if (batteryPaused() != paused) prefs.edit().putBoolean(K_BATTERY_PAUSED, paused).commit() }

    fun observeBatteryPaused(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(batteryPaused()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(batteryPaused())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** For the T19 banner: true while the index is unstable (see [IndexClock.unstable]). */
    fun observeUnstable(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(unstable()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(unstable())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private companion object {
        const val PREFS = "index_maintenance"
        const val K_CANARY = "last_canary_at"
        const val K_PENDING = "rebuild_pending"
        const val K_TOKEN = "consumed_rebuild"
        const val K_PENDING_TOKEN = "rebuild_pending_token"
        const val K_DISCARD_FP = "discard_fingerprint"
        const val K_DISCARD_AT = "discard_at"
        const val K_DISCARD_STREAK = "discard_streak"
        const val K_BATTERY_PAUSED = "battery_paused"
        const val K_MORE_STREAK = "more_streak"
    }
}

/**
 * spec §4.6 generations, one bounded run at a time: purge the vectors of spaces retired over 7 days ago; canary-check the ACTIVE space (≤ once per 24 h, or on Rebuild);
 * start or resume a BUILDING space when the binding's fingerprint differs from the current space or it is STALE;
 * embed missing memories into the ACTIVE space (when it still answers) and into the BUILDING one; swap at 100 %
 * coverage after the new space's canaries still match.
 *
 * Canary drift is bounded so an unstable embedder can never re-embed the store in a loop: a mismatch is confirmed
 * with a second probe (back on the stored set = a noisy embedder, not drift); a real drift
 * discard holds new builds of that model for 24 h; after [MAX_DISCARD_STREAK] discards in a row auto-rebuild stops
 * ("index unstable") until the user's Rebuild.
 */
class IndexMaintenance(
    private val spaces: EmbeddingSpaces,
    private val embedders: IndexEmbedders,
    private val clock: IndexClock,
    private val now: () -> Long = System::currentTimeMillis,
    private val batch: Int = 16,
    private val maxPerRun: Int = 256,
    /** The stored JPEG bytes of the given photo ids (sealed "PCAF" ones decrypted in memory only); a photo that cannot be read is left out. */
    private val photos: suspend (List<String>) -> Map<String, PhotoRead> = { emptyMap() },
    /** Persisted across runs: how often a photo could not be read (transient Keystore or I/O faults); only repeated failures hours apart mark it. */
    private val readAttempts: ReadAttempts = InMemoryReadAttempts(),
    /** Ruling 18: image backfill is bulk work (charging or battery above 70 %, thermal below MODERATE). Live per-frame embedding is not gated here. */
    private val bulkAllowed: () -> Boolean = { true },
    private val imageBatch: Int = 8,
    private val imageMaxPerRun: Int = 160,
    /** Log only: why [bulkAllowed] is closed right now (a fixed phrase, never content). */
    private val bulkReason: () -> String = { "bulk gate closed" },
    /** Log only: the rate limit of the progress lines and what was already said (shared by the process; tests pass their own). */
    private val progress: ProgressLimiter = IndexLogShared.progress,
    private val logState: IndexLogState = IndexLogShared.state,
) {
    /** RETRY: a pre-swap canary check could not run; the worker retries with backoff (bounded) and never swaps unchecked. */
    enum class Outcome { IDLE, DONE, MORE, RETRY,
        /** Only image-vector backfill is left (paused, deferred photos, or the swap waiting on them): the worker continues after a growing delay, not at once. */
        BACKFILL }

    private enum class Check { MATCH, NOISY, DRIFTED, UNCHECKED }

    /**
     * [rebuild] = the user's Rebuild; [rebuildToken] identifies that request so a re-delivery after it was applied is a
     * normal run. A Rebuild left pending by a pause ([IndexClock.rebuildPending]) counts as one.
     */
    suspend fun run(rebuild: Boolean = false, rebuildToken: String? = null): Outcome {
        spaces.purgeRetired(now()) // spec §F.2: retired spaces keep their vectors 7 days for Roll back, then go (cheap; runs with the daily job)
        val bound = embedders.bound() ?: run {
            // No EMBED model: a drift streak (and "index unstable") belonged to a model that is gone.
            if (clock.lastDiscard() != null) clock.recordDiscard(null)
            logPause("model", "no EMBED model bound (semantic search stays off)")
            return Outcome.IDLE
        }
        logResume("model", "an EMBED model is bound again")
        if (Modality.IMAGE in bound.embedder.modalities && bound.embedder.imageDims == null) {
            throw IndexConfigException("image embedder must declare imageDims") // before anything is purged or written
        }
        val fp = bound.embedder.fingerprint
        // The drift streak (and so "index unstable") belongs to one model: binding another one starts afresh.
        clock.lastDiscard()?.takeIf { it.fingerprint != fp }?.let { clock.recordDiscard(null) }
        val pending = clock.rebuildPending()
        val pendingToken = if (pending) clock.pendingRebuildToken() else null
        val requested = rebuild && (rebuildToken == null || rebuildToken != clock.consumedRebuild())
        val manual = requested || pending
        var current = spaces.current()
        if (current != null && current.state == SpaceState.ACTIVE && !(manual && current.fingerprint == fp) &&
            (manual || now() - clock.lastCanaryAt() >= DAY_MS)
        ) {
            val e = embedders.forSpace(current)
            when (if (e == null) Check.UNCHECKED else check(current, e)) {
                Check.DRIFTED -> { PcLog.i(TAG, "canaries drifted: space marked stale"); spaces.markStale(current.id) }
                Check.MATCH, Check.NOISY -> clock.markCanary(now())
                Check.UNCHECKED -> PcLog.i(TAG, "canary check could not run; trying again next time")
            }
            current = spaces.current()
        }
        if (manual) {
            // F26: a same-model Rebuild marks the space STALE first, because the fingerprint is unique.
            if (current != null && current.state == SpaceState.ACTIVE && current.fingerprint == fp) { spaces.markStale(current.id); current = spaces.current() }
            if (rebuild && rebuildToken != null) clock.consumeRebuild(rebuildToken)
            if (pending) {
                pendingToken?.let(clock::consumeRebuild) // that request's own run is now a normal one (Phase 5 T11 carry)
                clock.setRebuildPending(false)
                clock.setPendingRebuildToken(null)
            }
            clock.recordDiscard(null) // the user's Rebuild resets the drift streak
        }
        val needsBuild = current == null || current.state == SpaceState.STALE || current.fingerprint != fp
        val why = when { current == null -> "no index yet"; manual -> "rebuild requested"; current.state == SpaceState.STALE -> "space stale"; else -> "model changed" }
        val resumed = needsBuild && quietly { spaces.building()?.fingerprint == fp } == true
        val building = if (needsBuild) start(bound, fp) else null
        if (building != null) logBuildStart(building, bound, why, resumed)
        syncHealth(when {
            building != null -> if (resumed) "build resumed: $why" else "build started: $why"
            needsBuild -> if (clock.unstable()) "auto-rebuild stopped after $MAX_DISCARD_STREAK drift discards; only Rebuild resumes" else "build held 24 h after a drift discard"
            else -> "no rebuild needed"
        })

        var budget = maxPerRun
        val images = ImageProgress(imageMaxPerRun)
        var activeLeft = false
        var activeImagesLeft = false
        var activeChecked = false
        var activeImageCapable = false
        if (current != null && current.state == SpaceState.ACTIVE) {
            val e = if (current.fingerprint == fp) bound.embedder else embedders.forSpace(current)
            if (e != null) {
                try {
                    budget = fill(current, e, budget, "active")
                    fillImages(current, e, images, "active") // catch-up after downtime; the ACTIVE space is read for routing, so its photos come first
                    activeLeft = spaces.missing(current.id, 1).isNotEmpty() // re-checked so a memory written meanwhile is not left behind
                    activeImagesLeft = Modality.IMAGE in e.modalities && !images.paused && spaces.missingImages(current.id, 1).isNotEmpty()
                    activeChecked = true
                    activeImageCapable = Modality.IMAGE in e.modalities
                } catch (c: CancellationException) { throw c } catch (c: IndexConfigException) { throw c } catch (t: Exception) {
                    if (building == null) throw t // nothing else to do: let the worker retry with backoff
                    PcLog.i(TAG, "old space not reachable; it keeps its vectors: ${SafeLog.describe(t)}")
                }
            }
        }
        if (building == null) {
            if (activeChecked && !activeLeft && current != null) logReady(current, imagesDone = !activeImagesLeft && !images.paused, imageCapable = activeImageCapable)
            return if (activeLeft) Outcome.MORE else if (activeImagesLeft) Outcome.BACKFILL else Outcome.DONE
        }

        fill(building, bound.embedder, budget, "building")
        fillImages(building, bound.embedder, images, "building")
        if (!spaces.coverage(building.id).complete) return Outcome.MORE
        when (check(building, bound.embedder)) {
            Check.UNCHECKED -> { PcLog.i(TAG, "pre-swap canary check could not run; not swapping"); return Outcome.RETRY }
            Check.DRIFTED -> {
                spaces.discard(building.id)
                val last = clock.lastDiscard()
                val streak = if (last?.fingerprint == fp) last.streak + 1 else 1
                clock.recordDiscard(DiscardRecord(fp, now(), streak))
                PcLog.i(TAG, "canaries drifted while building: build discarded (streak $streak)")
                progress.forget(building.id)
                syncHealth(if (clock.unstable()) "build discarded after canary drift (streak $streak): auto-rebuild stopped until Rebuild" else "build discarded after canary drift (streak $streak)")
                return Outcome.DONE
            }
            Check.NOISY -> PcLog.i(TAG, "canaries are noisy, not drifted: swapping")
            Check.MATCH -> Unit
        }
        // Ruling 15: image coverage is required per space, iff the building embedder declares IMAGE. Paused backfill waits for the next trigger (no spin).
        val imageSpace = Modality.IMAGE in bound.embedder.modalities
        if (imageSpace && spaces.missingImageCount(building.id) == 0) {
            // Every photo is covered or marked: with no image vector at all (or a mostly-marked library) that is a broken decoder, not a finished build.
            val health = spaces.imageSwapHealth(building.id)
            if (health != ImageSwapHealth.OK) {
                PcLog.w(TAG, "image backfill unhealthy ($health): not swapping")
                throw IndexConfigException("image backfill unhealthy: $health")
            }
        }
        if (!spaces.swapIfComplete(building.id, requireImages = imageSpace)) {
            safeLog {
                val missingPhotos = spaces.missingImageCount(building.id); val paused = images.paused; val spaceId = building.id
                PcLog.milestone(TAG, "index swap waiting: text complete, $missingPhotos photo vectors still missing (photo backfill paused=$paused); space=$spaceId")
            }
            return if (images.paused) Outcome.DONE else Outcome.BACKFILL
        }
        clock.markCanary(now())
        clock.recordDiscard(null)
        val textLeft = spaces.missing(building.id, 1).isNotEmpty()
        logSwapped(building, previous = current, bound = bound, imageSpace = imageSpace)
        syncHealth("swap complete: space ${building.id} is ACTIVE")
        if (!textLeft) logReady(building, imagesDone = true, imageCapable = imageSpace)
        return if (textLeft) Outcome.MORE else Outcome.DONE
    }

    /** Resumes the BUILDING space of this model, or starts one unless drift discards are holding this model back (then null). */
    private suspend fun start(bound: BoundEmbedder, fp: String): EmbeddingSpaceEntity? {
        spaces.building()?.takeIf { it.fingerprint == fp }?.let { return it }
        clock.lastDiscard()?.takeIf { it.fingerprint == fp }?.let { d ->
            if (d.streak >= MAX_DISCARD_STREAK) { PcLog.i(TAG, "index unstable: auto-rebuild stopped until Rebuild"); return null }
            if (now() - d.at < DAY_MS) { PcLog.i(TAG, "build held for 24 h after a drift discard"); return null }
        }
        val canaries = bound.embedder.embedDocuments(Canaries.TEXTS)
        return spaces.startBuilding(fp, bound.configJson, bound.dim, Canaries.encode(canaries), now(), bound.embedder.imageDims)
    }

    /** Embeds up to [budget] missing memories into [space]; returns what is left of the budget. */
    private suspend fun fill(space: EmbeddingSpaceEntity, embedder: Embedder, budget: Int, role: String): Int {
        var left = budget
        var cov: Coverage? = null // log only: the space's coverage before this run's first write
        var stored = 0
        while (left > 0) {
            val todo = spaces.missing(space.id, minOf(batch, left))
            if (todo.isEmpty()) break
            if (cov == null) cov = quietly { spaces.coverage(space.id) }
            val vectors = embedder.embedDocuments(todo.map { it.text })
            var writable = true
            for ((m, v) in todo.zip(vectors)) {
                writable = try { spaces.put(m.id, space.id, v) } catch (e: IllegalArgumentException) {
                    throw IndexConfigException("space ${space.id}: ${e.message}")
                }
                if (!writable) break
                stored++
            }
            left -= todo.size
            cov?.let { c ->
                val total = maxOf(c.eligible, c.have + stored)
                val done = c.have + stored; val pct = IndexLogFormat.percent(done, total); val spaceId = space.id
                if (progress.due("text:$spaceId", done, total)) PcLog.i(TAG, "text index ($role): $done/$total embedded ($pct%) space=$spaceId")
            }
            if (!writable) break // discarded, retired or marked stale meanwhile: nothing more to write here
        }
        return left
    }

    private class ImageProgress(var budget: Int, var paused: Boolean = false)

    /**
     * Image backfill into [space]: stored photos with no vector, newest first, in batches of [imageBatch] with a yield between
     * them, at most [ImageProgress.budget] per run. Vectors only: no salience, no clusters, no AI calls (Ruling 1 covers new frames).
     * Gated per batch by [bulkAllowed]. Does nothing for a TEXT-only embedder.
     *
     * A photo is marked `unembeddable` only for a definite failure: its file is gone (confirmed by a second look), it fails to decode
     * ([ImageDecodeException]) twice, or it was unreadable on [UNREADABLE_MARK_AFTER] attempts at least [READ_RETRY_GAP_MS] apart.
     * Every other embedder exception (model, runtime, endpoint, battery) propagates and marks nothing; a photo skipped this run (transient
     * read fault, wrong-width vector) stays on the list for a later run.
     */
    private suspend fun fillImages(space: EmbeddingSpaceEntity, embedder: Embedder, p: ImageProgress, role: String) {
        if (Modality.IMAGE !in embedder.modalities) return
        // The width comes only from the embedder (never from a stored vector or the text dim); rows of another width are dropped.
        val dims = embedder.imageDims ?: throw IndexConfigException("image embedder must declare imageDims") // no purge, no write
        val purged = spaces.purgeWrongWidthImages(space.id, dims)
        if (purged > 0) PcLog.w(TAG, "image backfill: dropped $purged vectors of the wrong width")
        val skip = HashSet<String>() // skipped this run: not re-listed, or they would starve the photos behind them
        var cov: Coverage? = null // log only: photo-vector coverage before this run's first write
        var storedTotal = 0
        while (p.budget > 0) {
            if (!bulkAllowed()) {
                p.paused = true; PcLog.i(TAG, "image backfill paused by the bulk gate")
                logPause("images", bulkReason())
                return
            }
            logResume("images", "the bulk gate is open again")
            val want = minOf(imageBatch, p.budget)
            val ids = spaces.missingImages(space.id, want + skip.size).filter { it !in skip }.take(want)
            if (ids.isEmpty()) return
            if (cov == null) cov = quietly { spaces.imageCoverage(space.id) }
            yield()
            p.budget -= ids.size
            val first = photos(ids)
            val readable = ids.filter { first[it] is PhotoRead.Bytes }
            val batch = readable.takeIf { it.isNotEmpty() }
                ?.let { r -> tryImages(embedder, r.map { (first.getValue(it) as PhotoRead.Bytes).data })?.let { r.zip(it).toMap() } }
            var transient = 0; var rejected = 0; var stored = 0
            val toMark = ArrayList<String>()
            for (id in ids) {
                val v = batch?.get(id) ?: when (val r = embedOne(embedder, id, first[id])) {
                    is One.Vector -> r.v
                    One.Skip -> { skip += id; transient++; continue }
                    One.Unembeddable -> { toMark += id; continue }
                    One.DecodeFailed -> { toMark += id; continue }
                }
                when (spaces.putImage(id, space.id, v, dims)) {
                    ImagePut.STORED -> { stored++; readAttempts.forget(id) }
                    ImagePut.WIDTH_MISMATCH -> { skip += id; rejected++ } // never stored, never marked
                    ImagePut.DROPPED -> if (spaces.queryable()?.id != space.id && spaces.building()?.id != space.id) return // discarded, retired or marked stale meanwhile
                }
            }
            // A decoder that fails everything shows as no image vector at all: the swap refuses (see imageSwapHealth) and a new build clears the marks.
            toMark.forEach { spaces.markUnembeddable(it); readAttempts.forget(it) }
            PcLog.i(TAG, "image backfill: ${ids.size} photos, $stored stored, ${toMark.size} unembeddable, $transient deferred, $rejected wrong width")
            storedTotal += stored
            cov?.let { c ->
                val total = maxOf(c.eligible, c.have + storedTotal)
                val done = c.have + storedTotal; val pct = IndexLogFormat.percent(done, total); val spaceId = space.id
                if (progress.due("image:$spaceId", done, total)) PcLog.i(TAG, "image index ($role): $done/$total embedded ($pct%) space=$spaceId")
            }
            if (rejected > 0 && stored == 0 && rejected == ids.size - toMark.size - transient) {
                throw IndexConfigException("space ${space.id}: image vectors have the wrong width")
            }
        }
    }

    // ---- Index log lines (logging only: counts, space and model ids, durations, fixed reasons; never memory text, captions, queries or photos) ----

    private suspend fun safeLog(block: suspend () -> Unit) { try { block() } catch (c: CancellationException) { throw c } catch (_: Exception) { /* a log line never changes the run */ } }
    private suspend fun <T> quietly(block: suspend () -> T): T? = try { block() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }

    private fun logPause(key: String, reason: String) { if (logState.pause(key, reason)) PcLog.milestone(TAG, "index paused ($key): $reason") }
    private fun logResume(key: String, what: String) {
        val (reason, ms) = logState.resume(key) ?: return
        val waited = IndexLogFormat.duration(ms)
        PcLog.milestone(TAG, "index resumed ($key): $what; was paused for \"$reason\" for $waited")
    }

    /** NONE / NEEDED / RUNNING ([IndexHealth]) or UNSTABLE (auto-rebuild stopped); a line only when it changes. */
    private suspend fun syncHealth(reason: String) = safeLog {
        val base = IndexHealth.of(listOfNotNull(spaces.current(), spaces.building()))
        val state = if (base != IndexHealth.RUNNING && clock.unstable()) "UNSTABLE" else base.name
        val change = logState.health(state) ?: return@safeLog
        val before = change.before
        if (before == null) PcLog.milestone(TAG, "index health: $state ($reason)") else PcLog.milestone(TAG, "index health: $before -> $state ($reason)")
    }

    private suspend fun logBuildStart(b: EmbeddingSpaceEntity, bound: BoundEmbedder, why: String, resumed: Boolean) = safeLog {
        val text = spaces.coverage(b.id)
        val images = if (Modality.IMAGE in bound.embedder.modalities) spaces.imageCoverage(b.id) else null
        val verb = if (resumed) "resumed" else "start"
        val spaceId = b.id; val model = IndexLogFormat.model(b.fingerprint)
        val textTotal = text.eligible; val textTodo = (text.eligible - text.have).coerceAtLeast(0)
        val imagesTotal = images?.eligible?.toString() ?: "n/a"; val imagesTodo = images?.let { it.eligible - it.have }?.toString() ?: "n/a"
        PcLog.milestone(TAG, "index build $verb: space=$spaceId model=$model why=$why text_total=$textTotal text_todo=$textTodo images_total=$imagesTotal images_todo=$imagesTodo")
    }

    private suspend fun logSwapped(b: EmbeddingSpaceEntity, previous: EmbeddingSpaceEntity?, bound: BoundEmbedder, imageSpace: Boolean) = safeLog {
        val text = spaces.coverage(b.id)
        val images = if (imageSpace) spaces.imageCoverage(b.id) else null
        val spaceId = b.id; val model = IndexLogFormat.model(b.fingerprint); val prev = previous?.id ?: "none"
        val textHave = text.have; val textTotal = text.eligible
        val imageCounts = images?.let { "${it.have}/${it.eligible}" } ?: "n/a"
        val unembeddable = if (imageSpace) spaces.unembeddableImages() else 0
        val duration = IndexLogFormat.duration(now() - b.createdAt)
        PcLog.milestone(TAG, "index build complete, swapped in: space=$spaceId model=$model previous=$prev text=$textHave/$textTotal images=$imageCounts unembeddable=$unembeddable duration=$duration")
        progress.forget(b.id)
        logState.resetReady(b.id)
    }

    /** One summary line when [space] can answer semantic search: once per process per space, and again when its photo backfill completes. */
    private suspend fun logReady(space: EmbeddingSpaceEntity, imagesDone: Boolean, imageCapable: Boolean) = safeLog {
        if (!logState.firstReady("${space.id}:${if (imagesDone) "full" else "text"}")) return@safeLog
        val text = spaces.coverage(space.id)
        val images = if (imageCapable) spaces.imageCoverage(space.id) else null
        val spaceId = space.id; val model = IndexLogFormat.model(space.fingerprint)
        val textHave = text.have; val textTotal = text.eligible
        val imageCounts = images?.let { "${it.have}/${it.eligible}" } ?: "n/a"
        val tail = (if (imageCapable && !imagesDone) " (photo backfill pending)" else "") + (if (imageCapable) " unembeddable=${spaces.unembeddableImages()}" else "")
        PcLog.milestone(TAG, "semantic search ready: space=$spaceId model=$model text=$textHave/$textTotal images=$imageCounts$tail")
    }

    private sealed interface One {
        class Vector(val v: FloatArray) : One
        object Skip : One
        object Unembeddable : One
        object DecodeFailed : One
    }

    /** The photo on its own after a failed batch (or one that could not be read for the batch): see [fillImages] for what marks it. */
    private suspend fun embedOne(embedder: Embedder, id: String, read: PhotoRead?): One {
        var r: PhotoRead = read ?: photos(listOf(id))[id] ?: return One.Skip
        if (r === PhotoRead.Missing) r = photos(listOf(id))[id] ?: return One.Skip // a gone file is confirmed by a second look
        if (r === PhotoRead.Missing) return One.Unembeddable
        if (r === PhotoRead.Unreadable) return if (readAttempts.record(id, now()) >= UNREADABLE_MARK_AFTER) One.Unembeddable else One.Skip
        val bytes = (r as PhotoRead.Bytes).data
        tryImages(embedder, listOf(bytes))?.single()?.let { return One.Vector(it) }
        // Failed to decode once: a second attempt on a fresh read.
        val again = (photos(listOf(id))[id] as? PhotoRead.Bytes)?.data ?: return One.Skip
        return tryImages(embedder, listOf(again))?.single()?.let { One.Vector(it) } ?: One.DecodeFailed
    }

    /**
     * null = a definite decode failure ([ImageDecodeException]); any other exception is the embedder's, not the photo's, and propagates.
     * A result that is not one vector per photo (empty, short, long) is also the embedder's failure: [ImageEmbedderException], never a mark.
     */
    private suspend fun tryImages(embedder: Embedder, jpegs: List<ByteArray>): List<FloatArray>? {
        val out = try { embedder.embedImages(jpegs) } catch (_: ImageDecodeException) { return null }
        if (out.size != jpegs.size) throw ImageEmbedderException("embedder returned ${out.size} vectors for ${jpegs.size} photos")
        return out
    }

    /**
     * Re-embeds the canaries (the embedder applies the space's document prefix) against the stored set. A mismatch is
     * confirmed by a second probe: NOISY only if that one matches the stored set again, otherwise DRIFTED (whether
     * or not the two fresh sets agree). An unreachable endpoint is UNCHECKED, never evidence of drift.
     */
    private suspend fun check(space: EmbeddingSpaceEntity, embedder: Embedder): Check = try {
        val stored = Canaries.decode(space.canaryJson)
        val first = embedder.embedDocuments(Canaries.TEXTS)
        when {
            !Canaries.stale(stored, first) -> Check.MATCH
            // Noise only when the second probe is back on the stored set; two sets that both miss it are a changed model.
            !Canaries.stale(stored, embedder.embedDocuments(Canaries.TEXTS)) -> Check.NOISY
            else -> Check.DRIFTED
        }
    } catch (c: CancellationException) { throw c } catch (_: Exception) { Check.UNCHECKED }

    companion object {
        const val MAX_DISCARD_STREAK = 3
        /** An unreadable photo (Keystore, I/O) is marked only after this many failed reads, each at least [READ_RETRY_GAP_MS] after the last. */
        const val UNREADABLE_MARK_AFTER = 3
        const val READ_RETRY_GAP_MS = 60 * 60 * 1000L
        private const val TAG = "IndexMaintenance"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
