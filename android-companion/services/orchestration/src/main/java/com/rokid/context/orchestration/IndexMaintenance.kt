package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.embed.Canaries
import com.rokid.context.ai.embed.Embedders
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.SpaceEmbedderProvider
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.SpaceState
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
    override suspend fun bound(): BoundEmbedder? = embedders.forBinding()?.let { BoundEmbedder(it, it.spec.configJson(), it.spec.dim) }
    override suspend fun forSpace(space: EmbeddingSpaceEntity): Embedder? = embedders.forSpace(space)
}

interface IndexClock {
    fun lastCanaryAt(): Long
    fun markCanary(at: Long)
}

class PrefsIndexClock(context: Context) : IndexClock {
    private val prefs = context.applicationContext.getSharedPreferences("index_maintenance", Context.MODE_PRIVATE)
    override fun lastCanaryAt() = prefs.getLong("last_canary_at", 0L)
    override fun markCanary(at: Long) { prefs.edit().putLong("last_canary_at", at).apply() }
}

/**
 * spec §4.6 generations, one bounded run at a time: canary-check the ACTIVE space (≤ once per 24 h, or on Rebuild);
 * start or resume a BUILDING space when the binding's fingerprint differs from the current space or it is STALE;
 * embed missing memories into the ACTIVE space (when it still answers) and into the BUILDING one; swap at 100 %
 * coverage after the new space's canaries still match.
 */
class IndexMaintenance(
    private val spaces: EmbeddingSpaces,
    private val embedders: IndexEmbedders,
    private val clock: IndexClock,
    private val now: () -> Long = System::currentTimeMillis,
    private val batch: Int = 16,
    private val maxPerRun: Int = 256,
) {
    enum class Outcome { IDLE, DONE, MORE }

    suspend fun run(rebuild: Boolean = false): Outcome {
        val bound = embedders.bound() ?: return Outcome.IDLE
        val fp = bound.embedder.fingerprint
        var current = spaces.current()
        if (current != null && current.state == SpaceState.ACTIVE && (rebuild || now() - clock.lastCanaryAt() >= DAY_MS)) {
            val e = embedders.forSpace(current)
            if (e != null && drifted(current, e)) { Log.i(TAG, "canaries drifted: space marked stale"); spaces.markStale(current.id) }
            clock.markCanary(now())
            current = spaces.current()
        }
        if (rebuild && current != null && current.state == SpaceState.ACTIVE && current.fingerprint == fp) {
            spaces.markStale(current.id); current = spaces.current()
        }
        val needsBuild = current == null || current.state == SpaceState.STALE || current.fingerprint != fp
        val building = if (needsBuild) start(bound, fp) else null
        var budget = maxPerRun
        if (current != null && current.state == SpaceState.ACTIVE) {
            val e = if (current.fingerprint == fp) bound.embedder else embedders.forSpace(current)
            if (e != null) budget = try { fill(current, e, budget) } catch (c: CancellationException) { throw c } catch (c: IndexConfigException) { throw c } catch (t: Exception) {
                Log.i(TAG, "old space not reachable; it keeps its vectors: ${SafeLog.describe(t)}"); budget
            }
        }
        if (building == null) return if (budget == 0) Outcome.MORE else Outcome.DONE
        budget = fill(building, bound.embedder, budget)
        if (!spaces.coverage(building.id).complete) return Outcome.MORE
        if (drifted(building, bound.embedder)) { spaces.discard(building.id); return Outcome.MORE }
        return if (spaces.swapIfComplete(building.id)) Outcome.DONE else Outcome.MORE
    }

    private suspend fun start(bound: BoundEmbedder, fp: String): EmbeddingSpaceEntity {
        spaces.building()?.takeIf { it.fingerprint == fp }?.let { return it }
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

    /** True only when fresh canaries disagree; an unreachable endpoint is not evidence of drift. */
    private suspend fun drifted(space: EmbeddingSpaceEntity, embedder: Embedder): Boolean {
        val fresh = try { embedder.embedDocuments(Canaries.TEXTS) } catch (c: CancellationException) { throw c } catch (_: Exception) { return false }
        return Canaries.stale(Canaries.decode(space.canaryJson), fresh)
    }

    private companion object {
        const val TAG = "IndexMaintenance"
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
