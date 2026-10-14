package com.rokid.context.glasses.capture.session

/**
 * Calls [HeatGuard.poll] every [everyMs] (spec P1.5: every 30 s), STOP included: the cool-down needs a reading every
 * 30 s to count "2 min continuously below the limits". The owner calls [tick] about once a second.
 */
class HeatPoller(private val heat: HeatGuard, private val clock: () -> Long, private val everyMs: Long = EVERY_MS) {
    private var lastAtMs: Long? = null

    /** True when this tick polled. A clock that went back polls at once. */
    @Synchronized fun tick(): Boolean {
        val now = clock()
        val last = lastAtMs
        if (last != null && now >= last && now - last < everyMs) return false
        lastAtMs = now
        heat.poll()
        return true
    }

    companion object { const val EVERY_MS = 30_000L }
}
