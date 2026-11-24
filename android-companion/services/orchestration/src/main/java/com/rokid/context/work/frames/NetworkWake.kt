package com.rokid.context.work.frames

/**
 * WP9: what a VALIDATED network does. Every event nudges the WorkManager queues ([nudge]); the revival of frames that ended in FAILED_FINAL only because of timeouts or 5xx/429
 * ([revive], bounded to [batch] frames) runs at most once per [minIntervalMs], so a flapping network cannot spin it. Returns how many frames were revived.
 */
class NetworkWake(
    private val revive: suspend (nowMs: Long, limit: Int) -> Int,
    private val nudge: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val batch: Int = BATCH,
) {
    @Volatile private var lastReviveAt = Long.MIN_VALUE

    suspend fun onValidated(): Int {
        runCatching { nudge() }
        val now = clock()
        synchronized(this) {
            if (lastReviveAt != Long.MIN_VALUE && now - lastReviveAt < minIntervalMs) return 0
            lastReviveAt = now
        }
        return runCatching { revive(now, batch) }.getOrDefault(0)
    }

    companion object {
        const val MIN_INTERVAL_MS = 5 * 60_000L
        const val BATCH = 50
    }
}
