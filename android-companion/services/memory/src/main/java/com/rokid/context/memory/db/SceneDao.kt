package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Scene rows and their membership edges. The shrink of a Scene when a Moment goes lives in [DerivedStaleDao.shrinkScenesHolding] (called by `MomentDao.remove`, inside the removal's
 * own transaction). Callers chunk id lists at 500.
 */
@Dao
abstract class SceneDao : DerivedStaleDao() {
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertScene(s: SceneEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertParts(p: List<ScenePartEntity>)
    @Query("SELECT * FROM scene WHERE sceneId = :id") abstract suspend fun scene(id: String): SceneEntity?
    @Query("SELECT * FROM scene ORDER BY startMs, sceneId") abstract suspend fun allScenes(): List<SceneEntity>
    /** Scenes that overlap `[fromMs, toMs]`, oldest first. */
    @Query("SELECT * FROM scene WHERE endMs >= :fromMs AND startMs <= :toMs ORDER BY startMs, sceneId LIMIT :limit") abstract suspend fun scenesBetween(fromMs: Long, toMs: Long, limit: Int): List<SceneEntity>
    @Query("SELECT * FROM scene WHERE sceneId IN (:ids) ORDER BY startMs, sceneId") abstract suspend fun scenesByIds(ids: List<String>): List<SceneEntity>
    @Query("SELECT * FROM scene WHERE hourStartMs >= :fromHour AND hourStartMs <= :toHour ORDER BY startMs, sceneId") abstract suspend fun scenesInHours(fromHour: Long, toHour: Long): List<SceneEntity>
    @Query("SELECT * FROM scene_part WHERE sceneId = :id ORDER BY partTimeMs, kind, refId") abstract suspend fun parts(id: String): List<ScenePartEntity>
    @Query("SELECT * FROM scene_part WHERE sceneId IN (:ids) ORDER BY sceneId, partTimeMs, kind, refId") abstract suspend fun partsOf(ids: List<String>): List<ScenePartEntity>
    @Query("SELECT COUNT(*) FROM scene") abstract suspend fun sceneCount(): Int
    @Query("SELECT COUNT(*) FROM scene_part") abstract suspend fun partCount(): Int
    @Query("DELETE FROM scene_part WHERE sceneId IN (:ids)") abstract suspend fun deleteParts(ids: List<String>)

    /** Replaces one Scene and all its parts in the caller's transaction. */
    @Transaction open suspend fun replace(s: SceneEntity, parts: List<ScenePartEntity>) {
        deleteParts(listOf(s.sceneId))
        upsertScene(s)
        insertParts(parts)
    }

    /** Removes these Scenes with their parts, audit rows and derived fields (an object goes with everything that hangs on it). */
    @Transaction open suspend fun remove(ids: Collection<String>) = ids.toList().chunked(500).forEach { deleteParts(it); purgeScenes(it) }

    /**
     * Forget lifecycle F3 (`forgetV2`): a Scene that lost Moments is REBUILT from the surviving ones: its derived fields (SUMMARY, CAPTION, EMBEDDING) were built from a part that is gone, so the rows (and the old text in
     * them) are ERASED now, not just hidden. The Scene and its surviving parts stay; the derive pass writes the fields again from the survivors.
     */
    @Transaction open suspend fun eraseDerived(ids: Collection<String>) = ids.toList().chunked(500).forEach { deleteSceneDerived(it) }

    /** Fail-closed fallback of F3 when a plan could not be built: every HIDDEN Scene field (its text is still stored) is erased too. Regenerable by the derive pass. */
    @Query("DELETE FROM object_derived WHERE objectKind = 'SCENE' AND staleSince IS NOT NULL") protected abstract suspend fun eraseStaleSceneDerivedRows(): Int
    @Transaction open suspend fun eraseStaleSceneDerived(): Int { val n = eraseStaleSceneDerivedRows(); deleteGoneDerivedAttribution(); return n }

    // --- Activity > Scenes list (read only: names, ids, times and the fresh summary text; no schema change) ---
    /** One keyset page, newest first by start time, strictly after `(afterKey, afterId)`; the first page passes `Cursor.head`. [toMs] is exclusive. */
    @Query("SELECT * FROM scene WHERE startMs >= :fromMs AND startMs < :toMs AND (startMs < :afterKey OR (startMs = :afterKey AND sceneId < :afterId)) ORDER BY startMs DESC, sceneId DESC LIMIT :n")
    abstract suspend fun pageIn(fromMs: Long, toMs: Long, afterKey: Long, afterId: String, n: Int): List<SceneEntity>
    /** The start time of every Scene starting in `[fromMs, toMs)`, newest first: the counts of the period and of the broken-down view, live. */
    @Query("SELECT startMs FROM scene WHERE startMs >= :fromMs AND startMs < :toMs ORDER BY startMs DESC") abstract fun observeStarts(fromMs: Long, toMs: Long): Flow<List<Long>>
    @Query("SELECT startMs FROM scene WHERE startMs >= :fromMs AND startMs < :toMs ORDER BY startMs DESC") abstract suspend fun startsIn(fromMs: Long, toMs: Long): List<Long>
    /** The number of Moment members of each of these Scenes. */
    @Query("SELECT sceneId, COUNT(*) AS parts FROM scene_part WHERE kind = 'MOMENT' AND sceneId IN (:ids) GROUP BY sceneId") abstract suspend fun momentCounts(ids: List<String>): List<ScenePartCount>
    /** The fresh (not hidden) SUMMARY and CAPTION rows of these Scenes. A hidden field is never returned. */
    @Query("SELECT * FROM object_derived WHERE objectKind = 'SCENE' AND objectId IN (:ids) AND field IN ('SUMMARY', 'CAPTION') AND staleSince IS NULL") abstract suspend fun freshText(ids: List<String>): List<ObjectDerivedEntity>
    /** The input revision of each of these Scenes (see [inputSeq]): a derived field shows only while it was built from this revision. */
    @Query("SELECT objectId AS sceneId, COALESCE(MAX(seq), 0) AS seq FROM object_audit WHERE objectKind = 'SCENE' AND objectId IN (:ids) AND op NOT IN ('DERIVED_BUILT', 'DERIVED_STALE', 'DERIVED_HIDDEN', 'OUTBOX_QUEUED') GROUP BY objectId")
    abstract suspend fun inputSeqs(ids: List<String>): List<SceneSeq>
}

/** A Scene id with the number of Moments it holds. */
data class ScenePartCount(val sceneId: String, val parts: Int)

/** A Scene id with its input revision. */
data class SceneSeq(val sceneId: String, val seq: Int)
