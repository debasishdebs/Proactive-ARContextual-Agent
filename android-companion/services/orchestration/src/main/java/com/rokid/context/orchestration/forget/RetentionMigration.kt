package com.rokid.context.orchestration.forget

import androidx.room.withTransaction
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.forget.ExpiryMarks

/**
 * The one-shot migration of retention v3 (K8), run by the first retention run of the build under a `canvas_meta` done marker. Nothing here deletes user text.
 *  1. KEEPS every `lin:` key (the expired-memory lineage of the builds before v3): a later user Forget reads it (ExpiredLineage.reach) until no dependent of it survives.
 *  2. Marks the photos that PROVABLY expired BEFORE the marker existed: a tombstone with `frame_meta`, no `frame` row and a memory that still cites it (a photo whose last description was forgotten later has the same
 *     tombstone and metadata but nothing cites it, so it cannot be told from a Forget). Those photos stay "expired", not "forgotten". Every ambiguous tombstone (no metadata, or nothing cites it) stays "forgotten" (fail closed).
 * Counts-only log line.
 */
internal object RetentionMigration {
    const val DONE = "retention_v3_done"
    private const val CHUNK = 400

    suspend fun runOnce(db: ContextDatabase, nowMs: Long) {
        val s3 = db.canvasSlice3Dao()
        if (s3.meta(DONE) != null) return
        // Codex v3 r1 P1-3: the legacy `lin:` lineage is NOT purged here: while an answer, nudge or summary that depends on an expired memory survives, a later user Forget still needs it (ExpiredLineage.prune drops it when none is left)
        val lineageKept = s3.countMetaBetween("lin:", "lin;")
        var backfilled = 0
        var after = ""
        while (true) {
            val ids = db.forgetDao().legacyExpiredFrameIds(after, CHUNK)
            if (ids.isEmpty()) break
            db.withTransaction { ids.forEach { id -> ExpiryMarks.mark(s3, id, nowMs, db.forgetDao().captureTsOf(id)) } }
            backfilled += ids.size
            after = ids.last()
        }
        s3.putMeta(DONE, nowMs.toString())
        PcLog.milestone("PcRetention", "migration lineageKept=$lineageKept backfilled=$backfilled")
    }
}
