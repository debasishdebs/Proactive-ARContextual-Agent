package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * A pointer from a user place (a pin id `user:<uuid>`) to a Moment whose card named it (place object design S1). Place -> Moment only: a Moment never points at a Place. No coordinates, no name, no text.
 * No FK and no trigger: rows are deleted at the Forget choke points (the `moment_location` pattern). [atMs] is the Moment's second times 1000; [anchorId] is the frame id of the photo whose card made the link.
 * [role] is `CARD` in v18 (`USER` is reserved). Never exported, logged with ids, prompted or asked.
 */
@Entity(tableName = "place_link", primaryKeys = ["placeId", "momentId"], indices = [Index("momentId"), Index("atMs"), Index("anchorId")])
data class PlaceLinkEntity(
    val placeId: String,
    val momentId: String,
    val atMs: Long,
    val anchorId: String,
    val role: String,
    val linkedAt: Long,
)

@Dao
abstract class PlaceLinkDao {
    /** INSERT OR IGNORE: one link per (place, Moment); a second photo of the same Moment does nothing. Returns the new rowid, or -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertIfAbsent(row: PlaceLinkEntity): Long
    @Query("SELECT COUNT(*) FROM place_link") abstract suspend fun count(): Int
    @Query("SELECT momentId FROM place_link WHERE placeId = :placeId ORDER BY atMs, momentId") abstract suspend fun momentIdsOf(placeId: String): List<String>
    /** The capture time of a photo (phone clock), or null when the photo row or its time is absent. */
    @Query("SELECT capturedAtMs FROM frame_meta WHERE frameId = :frameId") abstract suspend fun capturedAtOf(frameId: String): Long?

    @Query("SELECT COUNT(*) FROM moment_tombstone WHERE momentId = :id") abstract suspend fun momentTombstoned(id: String): Int

    @Query("DELETE FROM place_link WHERE anchorId IN (:ids)") protected abstract suspend fun deleteAnchorChunk(ids: List<String>): Int
    @Query("DELETE FROM place_link WHERE momentId IN (:ids)") protected abstract suspend fun deleteMomentChunk(ids: List<String>): Int
    @Query("DELETE FROM place_link WHERE placeId IN (:ids)") protected abstract suspend fun deletePlaceChunk(ids: List<String>): Int
    /** Inclusive on both ends, on [PlaceLinkEntity.atMs]. */
    @Query("DELETE FROM place_link WHERE atMs BETWEEN :from AND :to") abstract suspend fun deleteBetween(from: Long, to: Long): Int
    @Query("DELETE FROM place_link") abstract suspend fun deleteAllPlaceLinks(): Int

    @Transaction open suspend fun deleteForAnchors(ids: Collection<String>): Int = ids.distinct().chunked(CHUNK).sumOf { deleteAnchorChunk(it) }
    @Transaction open suspend fun deleteForMoments(ids: Collection<String>): Int = ids.distinct().chunked(CHUNK).sumOf { deleteMomentChunk(it) }
    /** A removed pin takes its links (called inside the transaction that deletes its card history). */
    @Transaction open suspend fun deleteLinksForPlaces(ids: Collection<String>): Int = ids.distinct().chunked(CHUNK).sumOf { deletePlaceChunk(it) }

    private companion object { const val CHUNK = 500 }
}
