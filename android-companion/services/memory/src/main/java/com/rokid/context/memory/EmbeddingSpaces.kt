package com.rokid.context.memory

import com.rokid.context.memory.db.EmbedInput
import com.rokid.context.memory.db.EmbeddingDao
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.MemoryVectorEntity
import com.rokid.context.memory.db.SpaceState
import com.rokid.context.memory.retrieval.VectorCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import android.content.Context

data class Coverage(val have: Int, val eligible: Int) { val complete: Boolean get() = have >= eligible }

/** Photos whose image vector can't be rebuilt in a new space: [reduced] stay findable by text, [lost] by nothing (spec §F.2). */
data class SwapImpact(val reduced: Int, val lost: Int) { val any: Boolean get() = reduced + lost > 0 }

/** When each RETIRED space was retired (no Room column: a `space_retired_at` map in SharedPreferences, spaceId → ms). */
interface RetiredClock {
    fun retiredAt(spaceId: String): Long?
    fun markRetired(spaceId: String, at: Long)
    fun forget(spaceId: String)
}

class InMemoryRetiredClock : RetiredClock {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Long>()
    override fun retiredAt(spaceId: String) = map[spaceId]
    override fun markRetired(spaceId: String, at: Long) { map[spaceId] = at }
    override fun forget(spaceId: String) { map.remove(spaceId) }
}

class PrefsRetiredClock(context: Context) : RetiredClock {
    private val prefs = context.applicationContext.getSharedPreferences("space_retired_at", Context.MODE_PRIVATE)
    override fun retiredAt(spaceId: String): Long? = if (prefs.contains(spaceId)) prefs.getLong(spaceId, 0L) else null
    override fun markRetired(spaceId: String, at: Long) { prefs.edit().putLong(spaceId, at).commit() }
    override fun forget(spaceId: String) { prefs.edit().remove(spaceId).commit() }
}

/** spec §4.6 embedding generations. Semantic search reads only [queryable] (ACTIVE); BUILDING is never read. */
class EmbeddingSpaces(
    private val dao: EmbeddingDao,
    private val retired: RetiredClock = InMemoryRetiredClock(),
    private val now: () -> Long = System::currentTimeMillis,
    /** Off in wave 1 (the frame_vector backfill ships in wave 2); then a swap needs text coverage only. */
    private val requireImageCoverage: Boolean = false,
) {
    suspend fun current(): EmbeddingSpaceEntity? = dao.current()
    suspend fun queryable(): EmbeddingSpaceEntity? = dao.current()?.takeIf { it.state == SpaceState.ACTIVE }
    suspend fun building(): EmbeddingSpaceEntity? = dao.building()

    suspend fun startBuilding(fingerprint: String, configJson: String, dim: Int, canaryJson: String, now: Long): EmbeddingSpaceEntity =
        dao.startBuilding(EmbeddingSpaceEntity("space_${UUID.randomUUID()}", fingerprint, configJson, dim, SpaceState.BUILDING, canaryJson, now))

    /** False when the space is gone or no longer writable (RETIRED/STALE); throws on a dimension mismatch. */
    suspend fun put(memoryId: String, spaceId: String, vector: FloatArray): Boolean =
        dao.putChecked(MemoryVectorEntity(memoryId, spaceId, VectorCodec.encode(vector)), vector.size)
    suspend fun missing(spaceId: String, limit: Int): List<EmbedInput> = dao.missing(spaceId, limit)
    suspend fun coverage(spaceId: String): Coverage = Coverage(dao.coveredCount(spaceId), dao.eligibleCount())
    suspend fun swapIfComplete(buildingId: String): Boolean {
        val old = dao.swapRetiring(buildingId, requireImageCoverage) ?: return false
        old.forEach { retired.markRetired(it, now()) }
        return true
    }

    /** The RETIRED space a Roll back would reactivate: the newest one still inside its 7-day grace and still holding vectors. */
    suspend fun rollbackTarget(): EmbeddingSpaceEntity? = dao.observeSpaces().first()
        .filter { it.state == SpaceState.RETIRED && inGrace(it.id) }.maxByOrNull { retired.retiredAt(it.id) ?: 0L }
        ?.takeIf { dao.vectors(it.id).isNotEmpty() }

    fun rollbackAvailable(): Flow<Boolean> = dao.observeSpaces().map { l -> l.any { it.state == SpaceState.RETIRED && inGrace(it.id) } }.distinctUntilChanged()

    private fun inGrace(id: String): Boolean = retired.retiredAt(id)?.let { now() - it <= GRACE_MS } ?: false // no stamp = retired before this build (no vectors kept)

    /** Reactivates [retiredId] within 7 days of its retirement; the space that served meanwhile becomes RETIRED (kept). */
    suspend fun rollback(retiredId: String): Boolean {
        if (!inGrace(retiredId)) return false
        val demoted = dao.rollback(retiredId) ?: return false
        retired.forget(retiredId)
        demoted.forEach { retired.markRetired(it, now()) }
        return true
    }

    /** The daily purge: vectors of spaces retired more than 7 days ago go. A RETIRED space with no stamp has no grace. */
    suspend fun purgeRetired(at: Long = now()) {
        val expired = dao.retiredIds().filter { id ->
            val t = retired.retiredAt(id)
            t == null || at - t > GRACE_MS
        }
        if (expired.isNotEmpty()) dao.purgeVectors(expired)
        expired.forEach(retired::forget)
    }

    /** What the confirmation before a swap counts: deleted photos whose image vectors the current space holds. */
    suspend fun swapImpact(): SwapImpact {
        val id = dao.current()?.id ?: return SwapImpact(0, 0)
        val all = dao.deletedPhotoCount(id)
        val reduced = dao.deletedPhotoReducedCount(id)
        return SwapImpact(reduced, all - reduced)
    }
    suspend fun markStale(id: String) = dao.setState(id, SpaceState.STALE.name)
    suspend fun discard(id: String) = dao.discard(id)
    fun health(): Flow<IndexHealth> = dao.observeSpaces().map(IndexHealth::of).distinctUntilChanged()

    companion object { const val GRACE_MS = 7 * 24 * 60 * 60 * 1000L }
}
