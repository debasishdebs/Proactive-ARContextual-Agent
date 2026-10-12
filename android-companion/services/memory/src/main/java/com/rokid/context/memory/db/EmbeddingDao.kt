package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One memory to embed: only what the embedder needs. */
data class EmbedInput(val id: String, val text: String)

/** One searchable vector with its rowid (the keyset cursor for [EmbeddingDao.livePage]). */
class VectorRow(val rowid: Long, val memoryId: String, val blob: ByteArray)

/**
 * Embedding generations (spec §4.6). "Current" = the newest ACTIVE or STALE space (STALE is never read). At most one
 * BUILDING. Eligible memories = every memory not INVALIDATED. Schema v1 unchanged.
 */
@Dao
abstract class EmbeddingDao {
    @Query("SELECT * FROM embedding_space WHERE state IN ('ACTIVE','STALE') ORDER BY createdAt DESC LIMIT 1") abstract suspend fun current(): EmbeddingSpaceEntity?
    @Query("SELECT * FROM embedding_space WHERE state = 'BUILDING' LIMIT 1") abstract suspend fun building(): EmbeddingSpaceEntity?
    @Query("SELECT * FROM embedding_space WHERE id = :id") abstract suspend fun space(id: String): EmbeddingSpaceEntity?
    @Query("SELECT * FROM embedding_space ORDER BY createdAt ASC") abstract fun observeSpaces(): Flow<List<EmbeddingSpaceEntity>>
    @Query("SELECT * FROM memory_vector WHERE spaceId = :spaceId") abstract suspend fun vectors(spaceId: String): List<MemoryVectorEntity>

    /**
     * One keyset page of searchable vectors in [spaceId], by `memory_vector.rowid` after [afterRowid] (0 = first page),
     * so each page is an index range seek with no sort. Only vectors whose memory still exists, is OK and has at
     * least [minConfidence]: an INVALIDATED, under-review, forgotten or deleted memory's vector (memory_vector has no
     * foreign key) is never a hit.
     */
    @Query(LIVE_PAGE_SQL)
    abstract suspend fun livePage(spaceId: String, afterRowid: Long, minConfidence: Double, limit: Int): List<VectorRow>
    @Upsert abstract suspend fun putVector(v: MemoryVectorEntity)

    @Query(
        "SELECT m.id AS id, m.text AS text FROM memory m WHERE m.reviewState != 'INVALIDATED' AND NOT EXISTS " +
            "(SELECT 1 FROM memory_vector v WHERE v.memoryId = m.id AND v.spaceId = :spaceId) ORDER BY m.createdAt DESC LIMIT :limit",
    )
    abstract suspend fun missing(spaceId: String, limit: Int): List<EmbedInput>

    @Query(
        "SELECT COUNT(*) FROM memory m WHERE m.reviewState != 'INVALIDATED' AND NOT EXISTS " +
            "(SELECT 1 FROM memory_vector v WHERE v.memoryId = m.id AND v.spaceId = :spaceId)",
    )
    abstract suspend fun missingCount(spaceId: String): Int

    @Query("SELECT COUNT(*) FROM memory_vector v JOIN memory m ON m.id = v.memoryId WHERE v.spaceId = :spaceId AND m.reviewState != 'INVALIDATED'")
    abstract suspend fun coveredCount(spaceId: String): Int

    @Query("SELECT COUNT(*) FROM memory WHERE reviewState != 'INVALIDATED'") abstract suspend fun eligibleCount(): Int

    @Query("UPDATE embedding_space SET state = :state WHERE id = :id") abstract suspend fun setState(id: String, state: String)
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertSpace(s: EmbeddingSpaceEntity)
    @Query("DELETE FROM memory_vector WHERE spaceId = :spaceId") protected abstract suspend fun deleteVectors(spaceId: String)
    @Query("DELETE FROM embedding_space WHERE id = :id") protected abstract suspend fun deleteSpace(id: String)
    @Query("SELECT id FROM embedding_space WHERE fingerprint = :fp AND state IN ('RETIRED','STALE')") protected abstract suspend fun unusedWith(fp: String): List<String>
    @Query("SELECT id FROM embedding_space WHERE state IN ('ACTIVE','STALE') AND id != :keep") protected abstract suspend fun servingExcept(keep: String): List<String>

    /**
     * Writes one vector only while its space can still use it (BUILDING or ACTIVE); a late write to a discarded,
     * RETIRED or STALE space is dropped (returns false) so no orphan vectors appear. A vector whose size differs
     * from the space's `dim` is a caller bug and throws: it would count towards coverage yet never be searchable.
     */
    @Transaction
    open suspend fun putChecked(v: MemoryVectorEntity, size: Int): Boolean {
        val s = space(v.spaceId)?.takeIf { it.state == SpaceState.BUILDING || it.state == SpaceState.ACTIVE } ?: return false
        require(size == s.dim) { "vector has $size dimensions, space expects ${s.dim}" }
        putVector(v)
        return true
    }

    /** Resumes a BUILDING space with the same fingerprint, else replaces any other BUILDING one (at most one). */
    @Transaction
    open suspend fun startBuilding(space: EmbeddingSpaceEntity): EmbeddingSpaceEntity {
        building()?.let { b -> if (b.fingerprint == space.fingerprint) return b; discard(b.id) }
        unusedWith(space.fingerprint).forEach { discard(it) } // a STALE/RETIRED row would block the unique fingerprint
        insertSpace(space)
        return space
    }

    /** spec §4.6: only at 100 % coverage; the old current space becomes RETIRED and loses its vectors, all in one transaction. */
    @Transaction
    open suspend fun swap(buildingId: String): Boolean {
        val b = space(buildingId)?.takeIf { it.state == SpaceState.BUILDING } ?: return false
        if (missingCount(buildingId) > 0) return false
        // Every other ACTIVE/STALE row goes, not just the newest: exactly one ACTIVE afterwards, never two.
        servingExcept(b.id).forEach { old -> setState(old, SpaceState.RETIRED.name); deleteVectors(old) }
        setState(b.id, SpaceState.ACTIVE.name)
        return true
    }

    @Transaction
    open suspend fun discard(spaceId: String) { deleteVectors(spaceId); deleteSpace(spaceId) }

    companion object {
        const val LIVE_PAGE_SQL = "SELECT v.rowid AS rowid, v.memoryId AS memoryId, v.blob AS blob FROM memory_vector v " +
            "JOIN memory m ON m.id = v.memoryId WHERE v.spaceId = :spaceId AND v.rowid > :afterRowid AND m.reviewState = 'OK' " +
            "AND m.confidence >= :minConfidence ORDER BY v.rowid LIMIT :limit"
    }
}
