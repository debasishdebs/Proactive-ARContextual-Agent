package com.rokid.context.memory

import com.rokid.context.memory.db.EmbedInput
import com.rokid.context.memory.db.EmbeddingDao
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.ImagePut
import com.rokid.context.memory.db.ImageSwapHealth
import com.rokid.context.memory.db.MemoryVectorEntity
import com.rokid.context.memory.db.SpaceState
import com.rokid.context.memory.retrieval.VectorCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
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
) {
    suspend fun current(): EmbeddingSpaceEntity? = dao.current()
    suspend fun queryable(): EmbeddingSpaceEntity? = dao.current()?.takeIf { it.state == SpaceState.ACTIVE }
    suspend fun building(): EmbeddingSpaceEntity? = dao.building()

    suspend fun startBuilding(fingerprint: String, configJson: String, dim: Int, canaryJson: String, now: Long, imageDims: Int? = null): EmbeddingSpaceEntity =
        dao.startBuilding(EmbeddingSpaceEntity("space_${UUID.randomUUID()}", fingerprint, configJson, dim, SpaceState.BUILDING, canaryJson, now), imageDims)

    /** False when the space is gone or no longer writable (RETIRED/STALE); throws on a dimension mismatch. */
    suspend fun put(memoryId: String, spaceId: String, vector: FloatArray): Boolean =
        dao.putChecked(MemoryVectorEntity(memoryId, spaceId, VectorCodec.encode(vector)), vector.size)
    suspend fun missing(spaceId: String, limit: Int): List<EmbedInput> = dao.missing(spaceId, limit)
    /** Stored photos with no image vector in [spaceId], newest first, `unembeddable` ones excluded. */
    suspend fun missingImages(spaceId: String, limit: Int): List<String> = dao.missingImageIds(spaceId, limit)
    suspend fun missingImageCount(spaceId: String): Int = dao.missingImageCount(spaceId)
    /** [ImagePut.DROPPED] when the space is no longer writable or the photo is gone; [ImagePut.WIDTH_MISMATCH] (never stored) when the width differs from [expectedDims] or the space's stored image width. */
    suspend fun putImage(frameId: String, spaceId: String, vector: FloatArray, expectedDims: Int): ImagePut =
        dao.putImageChecked(frameId, spaceId, VectorCodec.encode(vector), expectedDims)
    suspend fun purgeWrongWidthImages(spaceId: String, dims: Int): Int = dao.purgeWrongWidthImages(spaceId, dims)
    suspend fun imageSwapHealth(spaceId: String): ImageSwapHealth = dao.imageSwapHealth(spaceId)
    /** Log-only counts: stored photos holding an image vector in [spaceId] (have) out of those plus the ones still missing (eligible). */
    suspend fun imageCoverage(spaceId: String): Coverage { val have = dao.storedPhotoVectorCount(spaceId); return Coverage(have, have + dao.missingImageCount(spaceId)) }
    /** Log-only: photos currently marked `unembeddable`. */
    suspend fun unembeddableImages(): Int = dao.unembeddablePhotoCount()
    suspend fun markUnembeddable(frameId: String) = dao.markUnembeddable(frameId, now())
    suspend fun coverage(spaceId: String): Coverage = Coverage(dao.coveredCount(spaceId), dao.eligibleCount())
    /** [requireImages]: the building embedder declares IMAGE, so every stored photo (bar `unembeddable` ones) needs a vector in [buildingId] (Ruling 15). */
    suspend fun swapIfComplete(buildingId: String, requireImages: Boolean = false): Boolean {
        val old = dao.swapRetiring(buildingId, requireImages) ?: return false
        old.forEach { retired.markRetired(it, now()) }
        return true
    }

    /** The RETIRED space a Roll back would reactivate: the newest one still inside its 7-day grace and still holding vectors. */
    suspend fun rollbackTarget(): EmbeddingSpaceEntity? = dao.observeSpaces().first()
        .filter { it.state == SpaceState.RETIRED && inGrace(it.id) }.maxByOrNull { retired.retiredAt(it.id) ?: 0L }
        ?.takeIf { dao.vectorCount(it.id) > 0 }

    /**
     * The 7-day window is checked against the clock on every read ([TICK_MS] ticks), not only when the database changes, so
     * Roll back disappears when the grace ends; it also needs the retained vectors (as [rollbackTarget] does).
     */
    fun rollbackAvailable(): Flow<Boolean> = combine(dao.observeSpaces(), ticker()) { l, _ -> l }
        .map { l -> l.any { it.state == SpaceState.RETIRED && inGrace(it.id) && dao.vectorCount(it.id) > 0 } }.distinctUntilChanged()

    private fun ticker(): Flow<Unit> = flow { while (true) { emit(Unit); delay(TICK_MS) } }

    private fun inGrace(id: String): Boolean = retired.retiredAt(id)?.let { now() - it <= GRACE_MS } ?: false // no stamp = retired before this build (no vectors kept)

    /** Reactivates [retiredId] within 7 days of its retirement; the space that served meanwhile becomes RETIRED (kept). */
    suspend fun rollback(retiredId: String): Boolean {
        if (!inGrace(retiredId)) return false
        val demoted = dao.rollback(retiredId) ?: return false
        retired.forget(retiredId)
        demoted.forEach { retired.markRetired(it, now()) }
        return true
    }

    /** The daily purge: vectors of spaces retired more than 7 days ago go. */
    suspend fun purgeRetired(at: Long = now()) {
        // An unstamped RETIRED space (a crash between the swap commit and markRetired) gets its grace from now, never an instant purge.
        val expired = dao.retiredIds().filter { id ->
            val t = retired.retiredAt(id)
            if (t == null) { retired.markRetired(id, at); false } else at - t > GRACE_MS
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

    companion object {
        const val GRACE_MS = 7 * 24 * 60 * 60 * 1000L
        const val UNEMBEDDABLE = "unembeddable"
        private const val TICK_MS = 60_000L
    }
}
