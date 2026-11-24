package com.rokid.context.memory.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * One row of the phone-only place card history (spec 2026-11-24 B1), now in context.db (v18). TEXT and numbers only: no coordinates and no card line (the line is re-read from the pack or the pin by [placeId]).
 * [delivery] is one of `memory.place.CardDelivery`. [frameId] is the photo link: user Forget deletes the row, photo retention only sets [photoGone] = 1 and keeps the id as private lineage. Never exported, never backed up.
 * Copied unchanged from the places.db entity; the places.db copy goes away in WP3.
 */
@Entity(tableName = "place_card_log", indices = [Index("at"), Index("frameId")])
data class PlaceCardLogEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val placeId: String,
    val title: String,
    val kind: String,
    /** Cluster member ids, comma-separated; null for a single place. */
    val members: String?,
    val frameId: String?,
    /** POSSIBLE rows only. */
    val confPct: Int?,
    val cardId: String?,
    val delivery: String,
    @ColumnInfo(defaultValue = "0") val photoGone: Int = 0,
)

@Dao
abstract class PlaceCardLogDao {
    /** ABORT, not REPLACE: a duplicate id is a bug, and a REPLACE would silently resurrect a row a Forget deleted. */
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun put(row: PlaceCardLogEntity)
    @Query("SELECT * FROM place_card_log ORDER BY at DESC, id DESC LIMIT :limit") abstract suspend fun recentCardLog(limit: Int): List<PlaceCardLogEntity>
    @Query("SELECT * FROM place_card_log WHERE at >= :since ORDER BY at DESC, id DESC") abstract suspend fun cardLogSince(since: Long): List<PlaceCardLogEntity>
    @Query("SELECT * FROM place_card_log WHERE id = :id") abstract suspend fun cardLog(id: String): PlaceCardLogEntity?
    @Query("SELECT * FROM place_card_log WHERE cardId = :cardId LIMIT 1") abstract suspend fun cardLogByCard(cardId: String): PlaceCardLogEntity?
    @Query("SELECT COUNT(*) FROM place_card_log") abstract suspend fun cardLogCount(): Int
    @Query("UPDATE place_card_log SET delivery = :state WHERE cardId = :cardId") abstract suspend fun setCardDelivery(cardId: String, state: String): Int
    @Query("UPDATE place_card_log SET delivery = :state WHERE cardId = :cardId AND delivery = 'PENDING'") abstract suspend fun setCardDeliveryIfPending(cardId: String, state: String): Int
    @Query("DELETE FROM place_card_log WHERE id = :id") abstract suspend fun deleteCardLog(id: String): Int
    /** Quota eviction (K6): the photos a card of the history is about (explicit importance). */
    @Query("SELECT DISTINCT frameId FROM place_card_log WHERE frameId IS NOT NULL") abstract suspend fun cardLogFrameIds(): List<String>
    @Query("DELETE FROM place_card_log WHERE frameId IN (:frameIds)") abstract suspend fun deleteCardLogForFrames(frameIds: List<String>): Int
    /** Photo retention (retention v3, M3): only `photoGone = 1`. The `frameId` stays as PRIVATE lineage, so a later user Forget of the photo still matches [deleteCardLogForFrames]; a renderer must not load a photo when `photoGone` is 1. */
    @Query("UPDATE place_card_log SET photoGone = 1 WHERE frameId IN (:frameIds)") abstract suspend fun markCardLogPhotoGone(frameIds: List<String>): Int
    @Query("DELETE FROM place_card_log WHERE at BETWEEN :from AND :to") abstract suspend fun deleteCardLogBetween(from: Long, to: Long): Int
    @Query("DELETE FROM place_card_log") abstract suspend fun deleteAllCardLog(): Int
    /** Rows of one pin: its own rows and the cluster rows that list it. */
    @Query("DELETE FROM place_card_log WHERE placeId = :pinId OR (members IS NOT NULL AND instr(',' || members || ',', ',' || :pinId || ',') > 0)") abstract suspend fun deleteCardLogForPin(pinId: String): Int
    @Query("DELETE FROM place_card_log WHERE at < :cutoff") abstract suspend fun deleteCardLogBefore(cutoff: Long): Int
    @Query("DELETE FROM place_card_log WHERE id IN (SELECT id FROM place_card_log ORDER BY at DESC, id DESC LIMIT -1 OFFSET :keep)") abstract suspend fun trimCardLogTo(keep: Int): Int

    /** The [deleteCardLogForPin] predicate per id, all inside one transaction. Returns the rows deleted. */
    @Transaction open suspend fun deleteCardLogForPins(pinIds: Collection<String>): Int = pinIds.sumOf { deleteCardLogForPin(it) }
}
