package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Nudge gate queries: daily cap and 24 h dedupe (R-61), plus the nudge detail read. */
@Dao
interface NudgeGateDao {
    /** Nudges stored at or after [fromMs] (the cap counts from local midnight). */
    @Query("SELECT COUNT(*) FROM nudge WHERE ts >= :fromMs") suspend fun countSince(fromMs: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM nudge WHERE hash = :hash AND ts >= :fromMs)") suspend fun existsHashSince(hash: String, fromMs: Long): Boolean

    /** Newest first; redacted rows are left out (their text is gone). */
    @Query("SELECT * FROM nudge WHERE ts >= :sinceMs AND redacted = 0 ORDER BY ts DESC LIMIT 20") suspend fun lastNudges(sinceMs: Long): List<NudgeEntity>

    @Query("SELECT * FROM nudge WHERE id = :id") fun observe(id: String): Flow<NudgeEntity?>
}
