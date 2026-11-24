package com.rokid.context.orchestration.forget

import androidx.room.withTransaction
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.ContextDatabase

/**
 * The one-shot migration of retention v3, run by the first retention run of the build under a `canvas_meta` done marker. Nothing here deletes user text and nothing here marks a photo as expired.
 *  1. Purges any stray `lin:` key (the expired-memory lineage of a branch no installed build ever ran: expected 0). The legacy lineage machinery is gone (Codex v3 r2 P1-2..P1-4), so a leftover key would only be dead weight.
 *  2. Writes NO expiry marker for an existing tombstone (Codex v3 r2 P1-1): a tombstone without an `rx:` marker is FORGOTTEN, ambiguity fails closed. Only an expiry written by this build carries a marker, in the same
 *     transaction as its tombstone.
 * Counts-only log line.
 */
internal object RetentionMigration {
    const val DONE = "retention_v3_done"
    private const val CHUNK = 400

    suspend fun runOnce(db: ContextDatabase, nowMs: Long) {
        val s3 = db.canvasSlice3Dao()
        if (s3.meta(DONE) != null) return
        var purged = 0
        while (true) {
            val n = db.withTransaction {
                val keys = s3.metasBetween("lin:", "lin;", CHUNK).map { it.key }
                if (keys.isNotEmpty()) s3.deleteMetaKeys(keys)
                keys.size
            }
            if (n == 0) break
            purged += n
        }
        s3.putMeta(DONE, nowMs.toString())
        PcLog.milestone("PcRetention", "migration strayLineagePurged=$purged")
    }
}
