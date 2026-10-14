package com.rokid.context.work.frames

import com.rokid.context.memory.db.FrameStateDao

/**
 * Rebuilds the backlog from `frame_state` (spec §P2.5): a claim older than 5 min (longer than the call timeout) is
 * abandoned, so the frame goes back to PENDING with attempts + 1 (the fifth ends in FAILED_FINAL). PENDING and FAILED rows
 * need no repair: the pipeline claims them as they are. Run at startup, on network return and by the 15-minute workers.
 */
object FrameRecovery {
    const val CLAIM_TIMEOUT_MS = 5 * 60_000L

    /** Returns how many abandoned claims were requeued; [signal] is called afterwards so the pipeline drains what is claimable. */
    suspend fun run(dao: FrameStateDao, nowMs: Long, signal: () -> Unit = {}): Int {
        val n = dao.requeueAbandoned(nowMs, nowMs - CLAIM_TIMEOUT_MS)
        signal()
        return n
    }
}
