package com.rokid.context.glasses.capture.diag

/**
 * Throttles the per-second `status` diagnostics line: log on a change of the state [key], or when the line text changed
 * and [MIN_GAP_SEC] passed since the last logged one, or when forced (the minute heartbeat).
 */
class StatusLogThrottle {
    private var lastKey: String? = null
    private var lastLine: String? = null
    private var lastTick = 0

    fun shouldLog(key: String, line: String, tickSec: Int, force: Boolean = false): Boolean {
        val log = force || lastKey == null || key != lastKey || (line != lastLine && tickSec - lastTick >= MIN_GAP_SEC)
        if (log) { lastKey = key; lastLine = line; lastTick = tickSec }
        return log
    }

    companion object { const val MIN_GAP_SEC = 30 }
}
