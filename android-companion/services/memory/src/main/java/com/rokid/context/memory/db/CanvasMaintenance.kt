package com.rokid.context.memory.db

import java.util.concurrent.atomic.AtomicLong

/**
 * Retention of the canvas live trace (S19, S44): rows older than 7 days, then all but the newest 5,000, pruned at most once an hour
 * (an in-memory stamp, so a new process prunes once at its first call). Called from canvas server start, canvas reads and app start,
 * never from the live path. Debug data only: a failure or a skipped pass loses nothing but history.
 */
class CanvasMaintenance(
    private val dao: CanvasDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastPrune = AtomicLong(NEVER)

    /** Prunes unless a pass ran within the last hour. Returns the rows deleted, or -1 when skipped. */
    suspend fun pruneTraces(now: Long = clock()): Int {
        while (true) {
            val last = lastPrune.get()
            if (last != NEVER && now - last in 0 until MIN_INTERVAL_MS) return -1
            if (lastPrune.compareAndSet(last, now)) break
        }
        return dao.pruneTraces(now - RETENTION_MS, KEEP_ROWS)
    }

    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        const val KEEP_ROWS = 5_000
        const val MIN_INTERVAL_MS = 60L * 60 * 1000
        private const val NEVER = Long.MIN_VALUE
    }
}
