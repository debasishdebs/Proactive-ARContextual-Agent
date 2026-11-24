package com.rokid.context.orchestration.forget

import androidx.room.withTransaction
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.forget.ExpiryMarks

/**
 * The one-shot migration of retention v3 (K8), run by the first retention run of the build under a `canvas_meta` done marker. Nothing here deletes user text.
 *  1. Purges every `lin:` key (the expired-memory lineage of the builds before v3; retention no longer writes or reads it).
 *  2. Marks the photos that expired BEFORE the marker existed: a tombstone with `frame_meta` and no `frame` row is the shape of every earlier photo expiry (a user Forget of a moment deletes its metadata too), so those photos stay
 *     "expired", not "forgotten" (a suggestion that cites one is not withdrawn by this build). A tombstone with no metadata and no row cannot be told apart and stays "forgotten" (fail closed).
 * Counts-only log line.
 */
internal object RetentionMigration {
    const val DONE = "retention_v3_done"
    private const val CHUNK = 400

    suspend fun runOnce(db: ContextDatabase, nowMs: Long) {
        val s3 = db.canvasSlice3Dao()
        if (s3.meta(DONE) != null) return
        val purged = db.withTransaction { s3.deleteMetaLike("lin:%") }
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
        PcLog.milestone("PcRetention", "migration purged=$purged backfilled=$backfilled")
    }
}
