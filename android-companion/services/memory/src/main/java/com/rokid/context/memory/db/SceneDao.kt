package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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
    @Query("DELETE FROM object_derived WHERE objectKind = 'SCENE' AND staleSince IS NOT NULL") abstract suspend fun eraseStaleSceneDerived(): Int
}
