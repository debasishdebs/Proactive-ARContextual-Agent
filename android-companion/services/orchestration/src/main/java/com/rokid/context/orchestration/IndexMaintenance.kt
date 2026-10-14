package com.rokid.context.orchestration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.embed.Canaries
import com.rokid.context.ai.embed.Embedders
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.SpaceEmbedderProvider
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.SpaceState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.coroutines.cancellation.CancellationException

data class BoundEmbedder(val embedder: Embedder, val configJson: String, val dim: Int)

/**
 * A vector whose size differs from its space's dimension (`EmbeddingSpaces.put` refuses it). The endpoint's config no
 * longer matches the space, so retrying cannot help: the worker records it and stops (Task 8 carry).
 */
class IndexConfigException(message: String) : Exception(message)

/** What [IndexMaintenance] needs from `:services:ai`. */
interface IndexEmbedders : SpaceEmbedderProvider {
    /** The EMBED binding, probed (dimension, revision); null = no binding (semantic stays off). */
    suspend fun bound(): BoundEmbedder?
}

class AiIndexEmbedders(private val embedders: Embedders) : IndexEmbedders {
    override suspend fun bound(): BoundEmbedder? = embedders.forBinding()?.let { BoundEmbedder(embedders.gated(it), it.spec.configJson(), it.spec.dim) }
    override suspend fun forSpace(space: EmbeddingSpaceEntity): Embedder? = embedders.forSpace(space)
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
    }
}

/**
 * spec §4.6 generations, one bounded run at a time: canary-check the ACTIVE space (≤ once per 24 h, or on Rebuild);
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
) {
    /** RETRY: a pre-swap canary check could not run; the worker retries with backoff (bounded) and never swaps unchecked. */
    enum class Outcome { IDLE, DONE, MORE, RETRY }

    private enum class Check { MATCH, NOISY, DRIFTED, UNCHECKED }

    /**
     * [rebuild] = the user's Rebuild; [rebuildToken] identifies that request so a re-delivery after it was applied is a
     * normal run. A Rebuild left pending by a pause ([IndexClock.rebuildPending]) counts as one.
     */
    suspend fun run(rebuild: Boolean = false, rebuildToken: String? = null): Outcome {
        val bound = embedders.bound() ?: run {
            // No EMBED model: a drift streak (and "index unstable") belonged to a model that is gone.
            if (clock.lastDiscard() != null) clock.recordDiscard(null)
            return Outcome.IDLE
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
                Check.DRIFTED -> { Log.i(TAG, "canaries drifted: space marked stale"); spaces.markStale(current.id) }
                Check.MATCH, Check.NOISY -> clock.markCanary(now())
                Check.UNCHECKED -> Log.i(TAG, "canary check could not run; trying again next time")
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
        val building = if (needsBuild) start(bound, fp) else null

        var budget = maxPerRun
        var activeLeft = false
        if (current != null && current.state == SpaceState.ACTIVE) {
            val e = if (current.fingerprint == fp) bound.embedder else embedders.forSpace(current)
            if (e != null) {
                try {
                    budget = fill(current, e, budget)
                    activeLeft = spaces.missing(current.id, 1).isNotEmpty() // re-checked so a memory written meanwhile is not left behind
                } catch (c: CancellationException) { throw c } catch (c: IndexConfigException) { throw c } catch (t: Exception) {
                    if (building == null) throw t // nothing else to do: let the worker retry with backoff
                    Log.i(TAG, "old space not reachable; it keeps its vectors: ${SafeLog.describe(t)}")
                }
            }
        }
        if (building == null) return if (activeLeft) Outcome.MORE else Outcome.DONE

        fill(building, bound.embedder, budget)
        if (!spaces.coverage(building.id).complete) return Outcome.MORE
        when (check(building, bound.embedder)) {
            Check.UNCHECKED -> { Log.i(TAG, "pre-swap canary check could not run; not swapping"); return Outcome.RETRY }
            Check.DRIFTED -> {
                spaces.discard(building.id)
                val last = clock.lastDiscard()
                val streak = if (last?.fingerprint == fp) last.streak + 1 else 1
                clock.recordDiscard(DiscardRecord(fp, now(), streak))
                Log.i(TAG, "canaries drifted while building: build discarded (streak $streak)")
                return Outcome.DONE
            }
            Check.NOISY -> Log.i(TAG, "canaries are noisy, not drifted: swapping")
            Check.MATCH -> Unit
        }
        if (!spaces.swapIfComplete(building.id)) return Outcome.MORE
        clock.markCanary(now())
        clock.recordDiscard(null)
        return if (spaces.missing(building.id, 1).isNotEmpty()) Outcome.MORE else Outcome.DONE
    }

    /** Resumes the BUILDING space of this model, or starts one unless drift discards are holding this model back (then null). */
    private suspend fun start(bound: BoundEmbedder, fp: String): EmbeddingSpaceEntity? {
        spaces.building()?.takeIf { it.fingerprint == fp }?.let { return it }
        clock.lastDiscard()?.takeIf { it.fingerprint == fp }?.let { d ->
            if (d.streak >= MAX_DISCARD_STREAK) { Log.i(TAG, "index unstable: auto-rebuild stopped until Rebuild"); return null }
            if (now() - d.at < DAY_MS) { Log.i(TAG, "build held for 24 h after a drift discard"); return null }
        }
        val canaries = bound.embedder.embedDocuments(Canaries.TEXTS)
        return spaces.startBuilding(fp, bound.configJson, bound.dim, Canaries.encode(canaries), now())
    }

    /** Embeds up to [budget] missing memories into [space]; returns what is left of the budget. */
    private suspend fun fill(space: EmbeddingSpaceEntity, embedder: Embedder, budget: Int): Int {
        var left = budget
        while (left > 0) {
            val todo = spaces.missing(space.id, minOf(batch, left))
            if (todo.isEmpty()) break
            val vectors = embedder.embedDocuments(todo.map { it.text })
            var writable = true
            for ((m, v) in todo.zip(vectors)) {
                writable = try { spaces.put(m.id, space.id, v) } catch (e: IllegalArgumentException) {
                    throw IndexConfigException("space ${space.id}: ${e.message}")
                }
                if (!writable) break
            }
            left -= todo.size
            if (!writable) break // discarded, retired or marked stale meanwhile: nothing more to write here
        }
        return left
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
        private const val TAG = "IndexMaintenance"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
