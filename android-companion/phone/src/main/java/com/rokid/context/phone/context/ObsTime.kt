package com.rokid.context.phone.context

import android.os.SystemClock

/**
 * Time translation for sources whose callbacks carry the time of the observation, not of the delivery. The hub's fusion runs on
 * the wall clock; an observation made at [SystemClock.elapsedRealtime] `t` happened `elapsed() - t` ago, so its fusion time is
 * `wall() - (elapsed() - t)`. A wall-clock change between the observation and the callback therefore cannot move it.
 */
class ObsTime(
    private val wall: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    fun nowElapsed(): Long = elapsed()
    fun toWall(obsElapsedMs: Long): Long = wall() - (elapsed() - obsElapsedMs)
}

/**
 * Admits observations of one run: never before the run began (no replaying buffered history), never older than the last admitted
 * one (out of order), never older than [maxAgeMs]; a time in the future is clamped to now. Not thread-safe: the owner locks.
 */
class ObsGate(private val time: ObsTime, private val maxAgeMs: Long) {
    var startMs = 0L; private set
    private var lastMs = 0L

    fun begin() { startMs = time.nowElapsed(); lastMs = startMs }

    /** The accepted observation time (elapsed ms), or null when stale. */
    fun admit(obsElapsedMs: Long?): Long? {
        val now = time.nowElapsed()
        val obs = minOf(obsElapsedMs ?: now, now)
        if (obs < startMs || obs < lastMs || now - obs > maxAgeMs) return null
        lastMs = obs
        return obs
    }
}
