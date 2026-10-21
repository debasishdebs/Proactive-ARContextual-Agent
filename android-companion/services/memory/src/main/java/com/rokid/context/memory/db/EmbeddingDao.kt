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
/** Why an IMAGE-capable swap may not proceed on its image evidence (see [EmbeddingDao.imageSwapHealth]). */
enum class ImageSwapHealth { OK, NO_VECTORS, SYSTEMIC }

const val SYSTEMIC_MIN_PHOTOS = 20

/** What [EmbeddingDao.putImageChecked] did with one image vector. */
enum class ImagePut { STORED, DROPPED, WIDTH_MISMATCH }

/** Unrouted PENDING frames: the scene indexer owns them (it embeds them into every target when it routes them), so backfill and the swap count leave them out. */
private const val INDEXER_OWNS = "AND NOT EXISTS (SELECT 1 FROM frame_meta m2 JOIN frame_state s ON s.frameId = m2.frameId WHERE m2.frameId = f.id AND m2.routedAt IS NULL AND s.state = 'PENDING')"

@Dao
abstract class EmbeddingDao {
    @Query("SELECT * FROM embedding_space WHERE state IN ('ACTIVE','STALE') ORDER BY createdAt DESC LIMIT 1") abstract suspend fun current(): EmbeddingSpaceEntity?
    @Query("SELECT * FROM embedding_space WHERE state = 'BUILDING' LIMIT 1") abstract suspend fun building(): EmbeddingSpaceEntity?
    @Query("SELECT * FROM embedding_space WHERE id = :id") abstract suspend fun space(id: String): EmbeddingSpaceEntity?
    @Query("SELECT * FROM embedding_space ORDER BY createdAt ASC") abstract fun observeSpaces(): Flow<List<EmbeddingSpaceEntity>>
    @Query("SELECT COUNT(*) FROM memory_vector WHERE spaceId = :spaceId") abstract suspend fun vectorCount(spaceId: String): Int
    @Query("SELECT * FROM memory_vector WHERE spaceId = :spaceId") abstract suspend fun vectors(spaceId: String): List<MemoryVectorEntity>

    /**
     * One keyset page of searchable vectors in [spaceId], by `memory_vector.rowid` after [afterRowid] (0 = first page),
     * so each page is an index range seek with no sort. Only vectors whose memory still exists, is OK and has at
     * least [minConfidence]: an INVALIDATED, under-review, forgotten or deleted memory's vector (memory_vector has no
     * foreign key) is never a hit.
     */
    @Query(LIVE_PAGE_SQL)
    abstract suspend fun livePage(spaceId: String, afterRowid: Long, minConfidence: Double, limit: Int): List<VectorRow>
    @Query("SELECT COUNT(*) FROM memory WHERE id = :id AND reviewState != 'INVALIDATED'") protected abstract suspend fun liveMemory(id: String): Int
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
     * from the space's `dim` is a caller bug and throws. A missing or INVALIDATED memory gets no vector (returns false): it would count towards coverage yet never be searchable.
     */
    @Transaction
    open suspend fun putChecked(v: MemoryVectorEntity, size: Int): Boolean {
        val s = space(v.spaceId)?.takeIf { it.state == SpaceState.BUILDING || it.state == SpaceState.ACTIVE } ?: return false
        // Phase 7 (fix round 1 I5): a vector computed before a Forget never lands for a deleted or INVALIDATED memory.
        if (liveMemory(v.memoryId) == 0) return false
        require(size == s.dim) { "vector has $size dimensions, space expects ${s.dim}" }
        putVector(v)
        return true
    }

    /** Resumes a BUILDING space with the same fingerprint, else replaces any other BUILDING one (at most one). */
    @Transaction
    open suspend fun startBuilding(space: EmbeddingSpaceEntity, imageDims: Int? = null): EmbeddingSpaceEntity {
        building()?.let { b ->
            if (b.fingerprint == space.fingerprint) { imageDims?.let { purgeWrongWidthImages(b.id, it) }; return b } // resuming drops rows of a wrong width
            discard(b.id)
        }
        clearUnembeddable() // a new build: earlier decode failures (maybe a broken decoder's) are retried; resuming a build, above, keeps its marks
        unusedWith(space.fingerprint).forEach { discard(it) } // a STALE/RETIRED row would block the unique fingerprint
        insertSpace(space)
        return space
    }

    /** Stored photos with no image vector in [spaceId]; a photo marked `unembeddable` (failed to decode twice) is not counted: it never blocks a swap (Ruling 15). Unrouted PENDING frames are not counted either: the scene indexer owns them and writes their vectors into every target at routing time. */
    @Query(
        "SELECT COUNT(*) FROM frame f WHERE NOT EXISTS (SELECT 1 FROM frame_vector v WHERE v.frameId = f.id AND v.spaceId = :spaceId) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_meta m WHERE m.frameId = f.id AND m.imageVectorSpace IN ('unembeddable','expired')) " + INDEXER_OWNS,
    )
    abstract suspend fun missingImageCount(spaceId: String): Int

    /** The backfill's work list, newest first: exactly the photos [missingImageCount] counts. */
    @Query(
        "SELECT f.id FROM frame f WHERE NOT EXISTS (SELECT 1 FROM frame_vector v WHERE v.frameId = f.id AND v.spaceId = :spaceId) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_meta m WHERE m.frameId = f.id AND m.imageVectorSpace IN ('unembeddable','expired')) " + INDEXER_OWNS +
            " ORDER BY f.ts DESC, f.id LIMIT :limit",
    )
    abstract suspend fun missingImageIds(spaceId: String, limit: Int): List<String>

    /** `unembeddable` marks belong to a space build: a build for a new fingerprint starts without them (a fixed decoder gets a fresh chance). */
    @Query("UPDATE frame_meta SET imageVectorSpace = NULL WHERE imageVectorSpace = 'unembeddable'")
    abstract suspend fun clearUnembeddable(): Int

    @Query("SELECT COUNT(*) FROM frame") protected abstract suspend fun storedPhotoCount(): Int
    @Query("SELECT COUNT(*) FROM frame f JOIN frame_meta m ON m.frameId = f.id WHERE m.imageVectorSpace = 'unembeddable'") protected abstract suspend fun unembeddablePhotoCount(): Int
    @Query("SELECT COUNT(*) FROM frame_vector v WHERE v.spaceId = :spaceId AND EXISTS (SELECT 1 FROM frame f WHERE f.id = v.frameId)") protected abstract suspend fun storedPhotoVectorCount(spaceId: String): Int

    /** Drops the image vectors of [spaceId] whose width is not [dims] (a rebuild must not keep rows a bad write left behind). Returns how many. */
    @Query("DELETE FROM frame_vector WHERE spaceId = :spaceId AND length(blob) != :dims * 4")
    abstract suspend fun purgeWrongWidthImages(spaceId: String, dims: Int): Int

    /**
     * Whether a swap into an IMAGE-capable space may proceed on its image evidence: a library with photos needs at least one image vector
     * in [spaceId] (every photo marked `unembeddable` is a broken decoder, not a library without images), and when more than half of at
     * least [SYSTEMIC_MIN_PHOTOS] photos are marked the backfill is treated as a systemic failure.
     */
    open suspend fun imageSwapHealth(spaceId: String): ImageSwapHealth {
        val photos = storedPhotoCount()
        if (photos == 0) return ImageSwapHealth.OK
        if (storedPhotoVectorCount(spaceId) == 0) return ImageSwapHealth.NO_VECTORS
        if (photos >= SYSTEMIC_MIN_PHOTOS && unembeddablePhotoCount() * 2 > photos) return ImageSwapHealth.SYSTEMIC
        return ImageSwapHealth.OK
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun putFrameVector(v: FrameVectorEntity)
    @Query("SELECT COUNT(*) FROM frame WHERE id = :frameId") protected abstract suspend fun frameRows(frameId: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertMetaIfAbsent(m: FrameMetaEntity)
    @Query("UPDATE frame_meta SET imageVectorSpace = 'unembeddable' WHERE frameId = :frameId") protected abstract suspend fun setUnembeddable(frameId: String)

    /**
     * THE write point of image vectors (the scene indexer and the backfill both use it): a vector whose width differs from [expectedDims]
     * (what the embedder declares for this space) is never stored, so cosine never sees two widths in one space. Writes without
     * checking the space or the photo: callers that need those use [putImageChecked].
     */
    @Transaction
    open suspend fun putImageVector(frameId: String, spaceId: String, blob: ByteArray, expectedDims: Int): ImagePut {
        if (blob.size != expectedDims * 4) return ImagePut.WIDTH_MISMATCH
        putFrameVector(FrameVectorEntity(frameId, spaceId, blob))
        return ImagePut.STORED
    }

    /** [putImageVector] for a batch in one transaction; returns how many were stored (wrong widths are skipped, never stored). */
    @Transaction
    open suspend fun putImageVectors(rows: List<FrameVectorEntity>, expectedDims: Int): Int =
        rows.count { putImageVector(it.frameId, it.spaceId, it.blob, expectedDims) == ImagePut.STORED }

    /** [putImageVector], only into a BUILDING or ACTIVE space and only for a photo that still exists (a late write after expiry or Forget is dropped). */
    @Transaction
    open suspend fun putImageChecked(frameId: String, spaceId: String, blob: ByteArray, expectedDims: Int): ImagePut {
        space(spaceId)?.takeIf { it.state == SpaceState.BUILDING || it.state == SpaceState.ACTIVE } ?: return ImagePut.DROPPED
        if (frameRows(frameId) == 0) return ImagePut.DROPPED
        return putImageVector(frameId, spaceId, blob, expectedDims)
    }

    /** Marks a photo that cannot be decoded; a legacy frame without a `frame_meta` row gets a minimal one. */
    @Transaction
    open suspend fun markUnembeddable(frameId: String, at: Long) {
        if (frameRows(frameId) == 0) return // the photo expired meanwhile: no orphan meta row
        insertMetaIfAbsent(FrameMetaEntity(frameId, null, null, "UNKNOWN", false, null, null, null, null, null, null, null, null, null, at, at))
        setUnembeddable(frameId)
    }

    @Query("SELECT id FROM embedding_space WHERE state = 'RETIRED'") abstract suspend fun retiredIds(): List<String>
    @Query("DELETE FROM frame_vector WHERE spaceId = :spaceId") protected abstract suspend fun deleteFrameVectors(spaceId: String)

    /** Photos already deleted whose image vector sits in [spaceId]: they can't be rebuilt in a new space (spec §F.2). */
    @Query("SELECT COUNT(DISTINCT v.frameId) FROM frame_vector v WHERE v.spaceId = :spaceId AND NOT EXISTS (SELECT 1 FROM frame f WHERE f.id = v.frameId)")
    abstract suspend fun deletedPhotoCount(spaceId: String): Int

    /** Of those, the ones that stay findable by text: an own caption, a surviving frame_source memory, or a cluster summary. */
    @Query(
        "SELECT COUNT(DISTINCT v.frameId) FROM frame_vector v WHERE v.spaceId = :spaceId AND NOT EXISTS (SELECT 1 FROM frame f WHERE f.id = v.frameId) AND (" +
            "EXISTS (SELECT 1 FROM memory m WHERE m.frameId = v.frameId AND m.reviewState != 'INVALIDATED') OR " +
            "EXISTS (SELECT 1 FROM frame_source s JOIN memory m ON m.id = s.memoryId WHERE s.frameId = v.frameId AND m.reviewState != 'INVALIDATED') OR " +
            "EXISTS (SELECT 1 FROM frame_meta fm JOIN frame_cluster c ON c.clusterId = fm.clusterId JOIN memory m ON m.id = c.memoryId " +
            "WHERE fm.frameId = v.frameId AND m.reviewState != 'INVALIDATED'))",
    )
    abstract suspend fun deletedPhotoReducedCount(spaceId: String): Int

    /**
     * spec §4.6 / §F.2: only at 100 % coverage of live memories (and, with [requireImageCoverage], of the frames that still
     * have a stored photo and are not `unembeddable`: required per space, iff the building embedder declares IMAGE). The old serving spaces become RETIRED but KEEP their
     * `memory_vector` and `frame_vector` rows for the 7-day rollback, all in one transaction. Returns the ids retired
     * (the caller stamps `retiredAt`), or null when the swap is refused.
     */
    @Transaction
    open suspend fun swapRetiring(buildingId: String, requireImageCoverage: Boolean = false): List<String>? {
        val b = space(buildingId)?.takeIf { it.state == SpaceState.BUILDING } ?: return null
        if (missingCount(buildingId) > 0) return null
        if (requireImageCoverage && (missingImageCount(buildingId) > 0 || imageSwapHealth(buildingId) != ImageSwapHealth.OK)) return null
        // Every other ACTIVE/STALE row goes, not just the newest: exactly one ACTIVE afterwards, never two.
        val old = servingExcept(b.id)
        old.forEach { setState(it, SpaceState.RETIRED.name) }
        setState(b.id, SpaceState.ACTIVE.name)
        return old
    }

    open suspend fun swap(buildingId: String, requireImageCoverage: Boolean = false): Boolean = swapRetiring(buildingId, requireImageCoverage) != null

    /** A RETIRED space becomes ACTIVE again and whatever served meanwhile becomes RETIRED (kept). Returns those ids, or null if [retiredId] isn't RETIRED. */
    @Transaction
    open suspend fun rollback(retiredId: String): List<String>? {
        space(retiredId)?.takeIf { it.state == SpaceState.RETIRED } ?: return null
        val current = servingExcept(retiredId)
        current.forEach { setState(it, SpaceState.RETIRED.name) }
        setState(retiredId, SpaceState.ACTIVE.name)
        return current
    }

    /** Drops the vectors (memory and image) of the RETIRED spaces in [expiredIds]; the space rows stay. */
    @Transaction
    open suspend fun purgeVectors(expiredIds: List<String>) {
        expiredIds.forEach { id -> if (space(id)?.state == SpaceState.RETIRED) { deleteVectors(id); deleteFrameVectors(id) } }
    }

    @Transaction
    open suspend fun discard(spaceId: String) { deleteVectors(spaceId); deleteFrameVectors(spaceId); deleteSpace(spaceId) }

    companion object {
        const val LIVE_PAGE_SQL = "SELECT v.rowid AS rowid, v.memoryId AS memoryId, v.blob AS blob FROM memory_vector v " +
            "JOIN memory m ON m.id = v.memoryId WHERE v.spaceId = :spaceId AND v.rowid > :afterRowid AND m.reviewState = 'OK' " +
            "AND m.confidence >= :minConfidence ORDER BY v.rowid LIMIT :limit"
    }
}
