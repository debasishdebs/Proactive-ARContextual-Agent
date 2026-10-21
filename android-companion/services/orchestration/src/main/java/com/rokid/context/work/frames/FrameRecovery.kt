package com.rokid.context.work.frames

import com.rokid.context.memory.db.FrameClusterDao
import com.rokid.context.memory.db.FrameStateDao

/**
 * Rebuilds the backlog from `frame_state` (spec §P2.5): a claim older than [CLAIM_TIMEOUT_MS] is abandoned, so the frame
 * goes back to PENDING with attempts + 1 (the fifth ends in FAILED_FINAL). PENDING and FAILED rows need no repair: the pipeline claims them as they are. Run at startup, on network return and by the 15-minute workers.
 */
object FrameRecovery {
    /**
     * Claims are stamped with the wall clock (they must survive a process death, so elapsed-realtime would not do), while calls and
     * budget waits run on the uptime clock. Instead of 5 min the margin covers the worst live claim: a 90 s call timeout for the
     * batch call plus its three single-photo fallbacks (6 min), the critic call, and budget waits (review I5). The foreground
     * service holds a wake lock for the extraction, so the uptime clock does not stall inside that window.
     */
    const val CLAIM_TIMEOUT_MS = 15 * 60_000L

    /**
     * First thing in a new process, before the first drain: every EXTRACTING row is released with no attempt (see
     * [FrameStateDao.releaseAllExtracting]); the [CLAIM_TIMEOUT_MS] sweep stays only as a backstop. Returns the rows released.
     * Fix wave I5: only claims older than [processStartMs] are a dead process's; a claim made since by a worker of this process
     * (the legacy backstop claims without the pipeline) is live and stays.
     */
    suspend fun releaseAtProcessStart(dao: FrameStateDao, nowMs: Long, processStartMs: Long = ProcessStart.wallMs, clusters: FrameClusterDao? = null): Int =
        dao.releaseAllExtracting(nowMs, claimedBeforeMs = processStartMs) + (clusters?.releaseAllSummarizing(nowMs, processStartMs) ?: 0) // a SUMMARIZING cluster goes back to CLOSED, attempts unchanged

    /** Returns how many abandoned claims (frames and, with [clusters], scene clusters) were requeued; [signal] is called afterwards so the pipeline drains what is claimable. */
    suspend fun run(dao: FrameStateDao, nowMs: Long, clusters: FrameClusterDao? = null, signal: () -> Unit = {}): Int {
        val n = dao.requeueAbandoned(nowMs, nowMs - CLAIM_TIMEOUT_MS) + (clusters?.requeueAbandoned(nowMs, nowMs - CLAIM_TIMEOUT_MS) ?: 0)
        signal()
        return n
    }
}

/** Wall clock at which this process began (the first touch is [com.rokid.context.ContextApp.onCreate], before any worker can claim). */
object ProcessStart {
    val wallMs: Long = System.currentTimeMillis()
}
