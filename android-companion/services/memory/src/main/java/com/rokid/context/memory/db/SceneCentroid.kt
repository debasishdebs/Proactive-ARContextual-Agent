package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * The centroid of one Scene's member embeddings in one embedding space (v18 build spec WP10; owner decision 2026-11-26: the centroid is Scene metadata). A side table like `moment_location`: no foreign key,
 * no trigger; the rows go with the Scene (every Scene deletion) and with a user Forget (WP10 choke points), never by user input. One row per (scene, space), replaced on rebuild.
 * [vector] is the L2-normalised mean of the member frames' embeddings, [dim] floats little-endian (4 bytes each), the same layout as `frame_vector.vector`. It is stale when [membershipRev] differs from the
 * Scene row's `membershipRev`; a stale row is never read as current. Never exported, logged, prompted or asked.
 */
@Entity(tableName = "scene_centroid", primaryKeys = ["sceneId", "space"])
class SceneCentroidEntity(
    val sceneId: String,
    val space: String,
    val membershipRev: Int,
    val dim: Int,
    val vector: ByteArray,
    val memberCount: Int,
    val builtAt: Long,
) {
    override fun equals(other: Any?) = other is SceneCentroidEntity && sceneId == other.sceneId && space == other.space && membershipRev == other.membershipRev && dim == other.dim &&
        vector.contentEquals(other.vector) && memberCount == other.memberCount && builtAt == other.builtAt
    override fun hashCode() = 31 * (31 * sceneId.hashCode() + space.hashCode()) + vector.contentHashCode()
}

/** The primary key `(sceneId, space)` is a prefix index on `sceneId`, so no extra index is declared. No producer yet: a later WP computes the rows. */
@Dao
abstract class SceneCentroidDao {
    /** Replaces the row of `(sceneId, space)` (a rebuild). */
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsert(row: SceneCentroidEntity)
    @Query("SELECT * FROM scene_centroid WHERE sceneId = :sceneId AND space = :space") abstract suspend fun get(sceneId: String, space: String): SceneCentroidEntity?
    @Query("SELECT * FROM scene_centroid WHERE sceneId = :sceneId ORDER BY space") abstract suspend fun getForScene(sceneId: String): List<SceneCentroidEntity>
    @Query("SELECT COUNT(*) FROM scene_centroid") abstract suspend fun count(): Int

    @Query("DELETE FROM scene_centroid WHERE sceneId IN (:ids)") protected abstract suspend fun deleteChunk(ids: List<String>): Int
    @Query("DELETE FROM scene_centroid WHERE sceneId = :sceneId") abstract suspend fun deleteForScene(sceneId: String): Int
    @Query("DELETE FROM scene_centroid") abstract suspend fun deleteAll(): Int

    /**
     * User Forget only (WP10): deletes every centroid that is no longer current: its Scene row is gone or has another `membershipRev` (a user Forget shrinks a Scene and so moves its revision). Order independent: run it
     * after the Moments of the Forget were removed. Retention, the sweep and the purge never call it.
     */
    @Query("DELETE FROM scene_centroid WHERE NOT EXISTS (SELECT 1 FROM scene s WHERE s.sceneId = scene_centroid.sceneId AND s.membershipRev = scene_centroid.membershipRev)")
    abstract suspend fun deleteStale(): Int

    @Transaction open suspend fun deleteForScenes(ids: Collection<String>): Int = ids.distinct().chunked(CHUNK).sumOf { deleteChunk(it) }

    private companion object { const val CHUNK = 500 }
}
