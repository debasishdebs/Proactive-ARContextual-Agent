package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Settings → Memory › Delete all (spec §6.1.1: Forget moment for every frame + all derived memories, asks, nudges,
 * vectors and tombstones). Endpoints, bindings, prompts, pauses, shopping list and notes are settings/user lists: kept.
 */
@Dao
abstract class WipeDao {
    @Query("DELETE FROM memory_source") protected abstract suspend fun sources()
    @Query("DELETE FROM memory_vector") protected abstract suspend fun vectors()
    @Query("DELETE FROM embedding_space") protected abstract suspend fun spaces()
    @Query("DELETE FROM memory") protected abstract suspend fun memories()
    @Query("DELETE FROM frame") protected abstract suspend fun frames()
    @Query("DELETE FROM forget_tombstone") protected abstract suspend fun tombstones()
    @Query("DELETE FROM ask") protected abstract suspend fun asks()
    @Query("DELETE FROM nudge") protected abstract suspend fun nudges()
    @Query("DELETE FROM proposal WHERE state IN ('PENDING','SUPPRESSED','EXPIRED','DECLINED')") protected abstract suspend fun openProposals()
    @Query("DELETE FROM summary_trace") protected abstract suspend fun traces()
    @Query("SELECT path FROM frame") abstract suspend fun framePaths(): List<String>

    @Transaction
    open suspend fun wipeMemory() { sources(); vectors(); spaces(); memories(); frames(); tombstones(); asks(); nudges(); openProposals(); traces() }
}
