package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Proposal compare-and-set writes and the action log (spec §5.3). Callers wrap these in one transaction. */
@Dao
interface ActionDao {
    @Query("UPDATE proposal SET state = :to, resolvedBy = :by WHERE id = :id AND state = :from")
    suspend fun cas(id: String, from: String, to: String, by: String?): Int

    @Insert suspend fun log(e: ActionLogEntity)

    @Query("SELECT * FROM action_log WHERE proposalId = :id ORDER BY id")
    suspend fun logFor(id: String): List<ActionLogEntity>

    /** CAS that keeps `resolvedBy` (EXECUTING → FAILED). */
    @Query("UPDATE proposal SET state = :to WHERE id = :id AND state = :from")
    suspend fun casState(id: String, from: String, to: String): Int

    /** CAS EXECUTING → EXECUTED that records what the executor created. */
    @Query("UPDATE proposal SET state = :to, createdRef = :createdRef WHERE id = :id AND state = :from")
    suspend fun casCreated(id: String, from: String, to: String, createdRef: String): Int

    @Query("SELECT * FROM proposal WHERE id = :id")
    suspend fun proposal(id: String): ProposalEntity?

    @Query("SELECT * FROM proposal WHERE state = :state ORDER BY createdAt")
    suspend fun inState(state: String): List<ProposalEntity>

    @Query("SELECT id FROM proposal WHERE state = :state AND expiresAt <= :nowMs")
    suspend fun dueIds(state: String, nowMs: Long): List<String>

    /** Callers chunk [originIds] (SQLite bound-parameter limit). */
    @Query("SELECT id FROM proposal WHERE state = :state AND originId IN (:originIds)")
    suspend fun idsForOrigins(state: String, originIds: List<String>): List<String>

    /** Every proposal of [originIds], any state (Forget: their notifications are cancelled). Callers chunk. */
    @Query("SELECT id FROM proposal WHERE originId IN (:originIds)")
    suspend fun allIdsForOrigins(originIds: List<String>): List<String>

    /** Forget redaction (Stream F): callers chunk [originIds]. */
    @Query("UPDATE proposal SET paramsJson = :params WHERE originId IN (:originIds)")
    suspend fun scrubParamsForOrigins(originIds: List<String>, params: String): Int

    @Query("UPDATE action_log SET detail = '' WHERE proposalId IN (SELECT id FROM proposal WHERE originId IN (:originIds))")
    suspend fun scrubLogsForOrigins(originIds: List<String>): Int

    /** Retention (final review C1): proposals of [originIds] not in [keepStates] (the accepted ones). Callers chunk. */
    @Query("SELECT id FROM proposal WHERE originId IN (:originIds) AND state NOT IN (:keepStates)")
    suspend fun idsForOriginsExcept(originIds: List<String>, keepStates: List<String>): List<String>

    /** Callers chunk [ids]. */
    @Query("UPDATE proposal SET paramsJson = :params WHERE id IN (:ids)")
    suspend fun scrubParams(ids: List<String>, params: String): Int

    @Query("UPDATE action_log SET detail = '' WHERE proposalId IN (:ids)")
    suspend fun scrubLogs(ids: List<String>): Int

    /** Origin fence (P7xP8 I2): the ask / nudge is still there and not redacted. */
    @Query("SELECT EXISTS(SELECT 1 FROM ask WHERE id = :id AND redacted = 0)")
    suspend fun askLive(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM nudge WHERE id = :id AND redacted = 0)")
    suspend fun nudgeLive(id: String): Boolean

    @Query("SELECT * FROM proposal WHERE state = :state ORDER BY createdAt DESC")
    fun observeInState(state: String): Flow<List<ProposalEntity>>
}

@Dao
interface ShoppingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(items: List<ShoppingItemEntity>): List<Long>
    @Query("DELETE FROM shopping_item WHERE proposalId = :proposalId") suspend fun deleteForProposal(proposalId: String): Int
    @Query("SELECT * FROM shopping_item ORDER BY done ASC, createdAt DESC") fun observeAll(): Flow<List<ShoppingItemEntity>>
    @Query("SELECT * FROM shopping_item WHERE done = 0 ORDER BY createdAt DESC LIMIT :limit") suspend fun open(limit: Int): List<ShoppingItemEntity>
    @Query("UPDATE shopping_item SET done = :done WHERE id = :id") suspend fun setDone(id: String, done: Boolean): Int
    @Query("DELETE FROM shopping_item WHERE id = :id") suspend fun delete(id: String): Int
}

/** User notes ("note that…"). Rows from NoteExecutor are `"<proposalId>#0"`, so a retry inserts nothing and undo finds its row. */
@Dao
interface NoteDao {
    @Query("SELECT * FROM note ORDER BY createdAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<NoteEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(note: NoteEntity): Long
    @Query("DELETE FROM note WHERE id = :id") suspend fun delete(id: String): Int
    @Query("SELECT * FROM note ORDER BY createdAt DESC, id DESC") fun observeAll(): Flow<List<NoteEntity>>
}
