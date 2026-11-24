package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * The optional saved location of one Moment (v18 build spec section 2). Never exported, logged, prompted or asked. No FK and no trigger: deletion is by the Forget choke points (WP2).
 * [anchorKind] is `FRAME` (`frame_meta.frameId`) or `MARK` (`canvas_label.labelId`, never a `:i` row). [source] is `ZONE_STREAM`, `PLACE_GUIDE`, `LAST_KNOWN` or `MEDIA_BACKFILL`.
 * [speedClass] (`STATIONARY`, `WALKING`, `VEHICLE`, `UNKNOWN`) and [method] (`INTERPOLATED`, `NEAREST`) are null on live rows. [fixAgeMs] is the gap |fix - capture|.
 */
@Entity(tableName = "moment_location", indices = [Index("atMs"), Index("anchorId")])
data class MomentLocationEntity(
    @PrimaryKey val momentId: String,
    val atMs: Long,
    val anchorKind: String,
    val anchorId: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val fixAgeMs: Long,
    val source: String,
    val speedClass: String?,
    val method: String?,
    val savedAt: Long,
)

/** An anchor the recorder may attach a location to: a photo (`frame_meta.frameId`) or a mark (`canvas_label.labelId`) and its capture time. */
data class MomentAnchor(val anchorId: String, val atMs: Long)

@Dao
abstract class MomentLocationDao {
    @Query("SELECT * FROM moment_location WHERE momentId IN (:ids)") protected abstract suspend fun ofChunk(ids: List<String>): List<MomentLocationEntity>
    @Query("SELECT COUNT(*) FROM moment_location") abstract suspend fun count(): Int

    // --- the live recorder (WP4): candidates and the in-transaction guards. Read-only; no coordinates leave these rows. ---
    /** Photos captured at or after [from] (phone clock). A frame without `capturedAtMs` is never a candidate. */
    @Query("SELECT frameId AS anchorId, capturedAtMs AS atMs FROM frame_meta WHERE capturedAtMs IS NOT NULL AND capturedAtMs >= :from ORDER BY capturedAtMs, frameId")
    abstract suspend fun framesSince(from: Long): List<MomentAnchor>
    /** Marks (kind MOMENT, never the `:i` intent row of a mark) at or after [from]. */
    @Query("SELECT labelId AS anchorId, atMs AS atMs FROM canvas_label WHERE kind = 'MOMENT' AND labelId NOT LIKE '%:i' AND atMs >= :from ORDER BY atMs, labelId")
    abstract suspend fun marksSince(from: Long): List<MomentAnchor>
    @Query("SELECT COUNT(*) FROM frame_meta WHERE frameId = :id") abstract suspend fun frameAnchorExists(id: String): Int
    @Query("SELECT COUNT(*) FROM canvas_label WHERE labelId = :id AND kind = 'MOMENT'") abstract suspend fun markAnchorExists(id: String): Int
    @Query("SELECT COUNT(*) FROM forget_tombstone WHERE frameId = :id") abstract suspend fun frameTombstoned(id: String): Int
    @Query("SELECT COUNT(*) FROM moment_tombstone WHERE momentId = :id") abstract suspend fun momentTombstoned(id: String): Int

    /** INSERT OR IGNORE: the first writer wins and a backfill never overwrites a row. Returns the new rowid, or -1 when the row was already there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertIfAbsent(row: MomentLocationEntity): Long

    @Query("DELETE FROM moment_location WHERE anchorId IN (:ids)") protected abstract suspend fun deleteAnchorChunk(ids: List<String>): Int
    @Query("DELETE FROM moment_location WHERE momentId IN (:ids)") protected abstract suspend fun deleteMomentChunk(ids: List<String>): Int

    /** Inclusive on both ends, on [MomentLocationEntity.atMs]. */
    @Query("DELETE FROM moment_location WHERE atMs BETWEEN :from AND :to") abstract suspend fun deleteBetween(from: Long, to: Long): Int
    @Query("DELETE FROM moment_location") abstract suspend fun deleteAllMomentLocations(): Int

    @Transaction open suspend fun of(ids: Collection<String>): List<MomentLocationEntity> = ids.distinct().chunked(CHUNK).flatMap { ofChunk(it) }
    @Transaction open suspend fun deleteForAnchors(ids: Collection<String>): Int = ids.distinct().chunked(CHUNK).sumOf { deleteAnchorChunk(it) }
    @Transaction open suspend fun deleteForMoments(ids: Collection<String>): Int = ids.distinct().chunked(CHUNK).sumOf { deleteMomentChunk(it) }

    private companion object { const val CHUNK = 500 }
}
