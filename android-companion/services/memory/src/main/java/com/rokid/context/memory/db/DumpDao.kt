package com.rokid.context.memory.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

/** One stored vector as the Developer dump reads it: the insertion [rowid] (the paging key), the owner [id], its space and the little-endian float32 [blob]. */
class DumpVectorRow(@ColumnInfo(name = "rowid") val rowid: Long, val id: String, val spaceId: String, val blob: ByteArray)

/** Moment id and its mark (a MARK part of a Moment). */
data class DumpMarkMoment(val refId: String, val momentId: String)

/**
 * Developer dump API (design 2026-11-26): READ-ONLY queries only (no insert, update or delete, so a test can pin that). Every query leaves out what Forget removed: a tombstoned Moment (`moment_tombstone`) and a
 * tombstoned photo (`forget_tombstone`) never come back, and a memory of a tombstoned photo is left out. No schema change: these are new SELECTs over existing tables.
 */
@Dao
abstract class DumpDao {
    // --- counts and ranges (manifest) ---
    @Query("SELECT COUNT(*) FROM moment WHERE momentId NOT IN (SELECT momentId FROM moment_tombstone)") abstract suspend fun momentCount(): Int
    @Query("SELECT MIN(momentId) FROM moment WHERE momentId NOT IN (SELECT momentId FROM moment_tombstone)") abstract suspend fun firstMomentId(): String?
    @Query("SELECT MAX(momentId) FROM moment WHERE momentId NOT IN (SELECT momentId FROM moment_tombstone)") abstract suspend fun lastMomentId(): String?
    @Query("SELECT COUNT(*) FROM scene") abstract suspend fun sceneCount(): Int
    @Query("SELECT COUNT(*) FROM canvas_label WHERE kind = 'MOMENT' AND labelId NOT LIKE '%:i'") abstract suspend fun markCount(): Int
    @Query("SELECT COUNT(DISTINCT p.refId) FROM moment_part p JOIN frame f ON f.id = p.refId WHERE p.kind = 'FRAME' AND p.momentId NOT IN (SELECT momentId FROM moment_tombstone) AND p.refId NOT IN (SELECT frameId FROM forget_tombstone)")
    abstract suspend fun momentFrameCount(): Int
    @Query("SELECT COUNT(*) FROM memory_vector v JOIN memory m ON m.id = v.memoryId WHERE v.spaceId = :spaceId AND (m.frameId IS NULL OR m.frameId NOT IN (SELECT frameId FROM forget_tombstone))")
    abstract suspend fun memoryVectorCount(spaceId: String): Int
    @Query("SELECT COUNT(*) FROM frame_vector v JOIN frame_meta m ON m.frameId = v.frameId WHERE v.spaceId = :spaceId AND v.frameId NOT IN (SELECT frameId FROM forget_tombstone)")
    abstract suspend fun frameVectorCount(spaceId: String): Int
    @Query("SELECT DISTINCT spaceId FROM memory_vector ORDER BY spaceId") abstract suspend fun memoryVectorSpaces(): List<String>
    @Query("SELECT DISTINCT spaceId FROM frame_vector ORDER BY spaceId") abstract suspend fun frameVectorSpaces(): List<String>
    @Query("SELECT * FROM embedding_space ORDER BY createdAt") abstract suspend fun spaces(): List<EmbeddingSpaceEntity>

    // --- Moments ---
    @Query("SELECT * FROM moment WHERE momentId >= :fromId AND momentId <= :toId AND momentId > :afterId AND momentId NOT IN (SELECT momentId FROM moment_tombstone) ORDER BY momentId LIMIT :limit")
    abstract suspend fun momentsPage(fromId: String, toId: String, afterId: String, limit: Int): List<MomentEntity>
    @Query("SELECT * FROM moment_part WHERE momentId IN (:ids) ORDER BY momentId, kind, refId") abstract suspend fun partsOfMoments(ids: List<String>): List<MomentPartEntity>
    @Query("SELECT * FROM memory WHERE id IN (:ids) AND (frameId IS NULL OR frameId NOT IN (SELECT frameId FROM forget_tombstone)) ORDER BY id") abstract suspend fun memoriesByIds(ids: List<String>): List<MemoryEntity>
    @Query("SELECT * FROM frame WHERE id IN (:ids) AND id NOT IN (SELECT frameId FROM forget_tombstone) ORDER BY id") abstract suspend fun framesByIds(ids: List<String>): List<FrameEntity>
    @Query("SELECT * FROM frame_meta WHERE frameId IN (:ids) ORDER BY frameId") abstract suspend fun frameMetaByIds(ids: List<String>): List<FrameMetaEntity>
    @Query("SELECT * FROM object_derived WHERE objectKind = :kind AND objectId IN (:ids) ORDER BY objectId, field") abstract suspend fun derivedOf(kind: String, ids: List<String>): List<ObjectDerivedEntity>
    @Query("SELECT * FROM place_card_log WHERE frameId IN (:frameIds) ORDER BY at, id") abstract suspend fun cardLogOfFrames(frameIds: List<String>): List<PlaceCardLogEntity>
    @Query("SELECT * FROM scene_part WHERE kind = 'MOMENT' AND refId IN (:momentIds)") abstract suspend fun scenePartsHolding(momentIds: List<String>): List<ScenePartEntity>
    @Query("SELECT * FROM frame_cluster WHERE clusterId IN (:ids) ORDER BY clusterId") abstract suspend fun clustersByIds(ids: List<String>): List<FrameClusterEntity>

    // --- Scenes ---
    @Query("SELECT * FROM scene WHERE startMs >= :fromMs AND startMs <= :toMs AND (startMs > :afterKey OR (startMs = :afterKey AND sceneId > :afterId)) ORDER BY startMs, sceneId LIMIT :limit")
    abstract suspend fun scenesPage(fromMs: Long, toMs: Long, afterKey: Long, afterId: String, limit: Int): List<SceneEntity>

    // --- Marks (kind MOMENT, never the `:i` intent row) ---
    @Query("SELECT * FROM canvas_label WHERE kind = 'MOMENT' AND labelId NOT LIKE '%:i' AND atMs >= :fromMs AND atMs <= :toMs AND (atMs > :afterKey OR (atMs = :afterKey AND labelId > :afterId)) ORDER BY atMs, labelId LIMIT :limit")
    abstract suspend fun marksPage(fromMs: Long, toMs: Long, afterKey: Long, afterId: String, limit: Int): List<CanvasLabelEntity>
    @Query("SELECT * FROM canvas_label WHERE labelId IN (:ids) ORDER BY labelId") abstract suspend fun labelsByIds(ids: List<String>): List<CanvasLabelEntity>
    @Query("SELECT refId AS refId, momentId AS momentId FROM moment_part WHERE kind = 'MARK' AND refId IN (:labelIds) AND momentId NOT IN (SELECT momentId FROM moment_tombstone)")
    abstract suspend fun momentsOfMarks(labelIds: List<String>): List<DumpMarkMoment>
    @Query("SELECT momentId FROM moment_tombstone WHERE momentId IN (:ids)") abstract suspend fun tombstonedMoments(ids: List<String>): List<String>

    // --- Vectors (paged by rowid) ---
    @Query("SELECT v.rowid AS rowid, v.memoryId AS id, v.spaceId AS spaceId, v.blob AS blob FROM memory_vector v JOIN memory m ON m.id = v.memoryId WHERE v.rowid > :afterRowid AND (m.frameId IS NULL OR m.frameId NOT IN (SELECT frameId FROM forget_tombstone)) ORDER BY v.rowid LIMIT :limit")
    abstract suspend fun memoryVectorsPage(afterRowid: Long, limit: Int): List<DumpVectorRow>
    @Query("SELECT v.rowid AS rowid, v.frameId AS id, v.spaceId AS spaceId, v.blob AS blob FROM frame_vector v JOIN frame_meta m ON m.frameId = v.frameId WHERE v.rowid > :afterRowid AND v.frameId NOT IN (SELECT frameId FROM forget_tombstone) AND ((:siglip = 1 AND v.spaceId LIKE 'siglip2%') OR (:siglip = 0 AND v.spaceId NOT LIKE 'siglip2%')) ORDER BY v.rowid LIMIT :limit")
    abstract suspend fun frameVectorsPage(afterRowid: Long, siglip: Int, limit: Int): List<DumpVectorRow>
}
