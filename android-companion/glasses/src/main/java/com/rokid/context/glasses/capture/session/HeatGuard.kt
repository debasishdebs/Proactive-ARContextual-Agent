package com.rokid.context.glasses.capture.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [thermalStatus] is PowerManager's THERMAL_STATUS_*; [headroom] is recorded only (NaN on this HAL); battery in degrees C. */
data class HeatReading(val thermalStatus: Int, val headroom: Float, val batteryTempC: Float)

/** OK = normal rates, SLOW = every cadence x2, STOP = sampling off (HUD "Cooling"). */
enum class HeatLevel { OK, SLOW, STOP }

/**
 * Heat policy (spec P1.5). The owner calls [poll] every 30 s, STOP included (see [HeatPoller]); [clock] must be
 * SystemClock.elapsedRealtime (a reading that goes backwards restarts the cool-down). MODERATE or battery >= 40 C: SLOW; SEVERE+ or
 * battery >= 44 C: STOP; STOP lifts only after below MODERATE and battery <= 38 C for 2 min without a break.
 * [headroom][HeatReading.headroom] is never used for a decision (NaN here); the last finite value is kept for diagnostics.
 */
class HeatGuard(private val read: () -> HeatReading, private val clock: () -> Long) {
    private val _level = MutableStateFlow(HeatLevel.OK)
    val level: StateFlow<HeatLevel> = _level

    /** Last finite headroom seen, for Diagnostics only; null while the HAL gives NaN. */
    @Volatile var lastHeadroom: Float? = null
        private set

    /** The newest reading as the HAL gave it (headroom NaN included), for the Diagnostics line. */
    @Volatile var lastReading: HeatReading? = null
        private set

    private var coolSinceMs: Long? = null

    @Synchronized
    fun poll() {
        val r = read()
        lastReading = r
        if (!r.headroom.isNaN()) lastHeadroom = r.headroom
        val tempKnown = !r.batteryTempC.isNaN()
        val temp = if (tempKnown) r.batteryTempC else Float.NEGATIVE_INFINITY // an unreadable battery never starts a stop
        val stop = r.thermalStatus >= SEVERE || temp >= STOP_C
        val slow = r.thermalStatus >= MODERATE || temp >= SLOW_C
        val cool = r.thermalStatus < MODERATE && tempKnown && temp <= RESUME_C // no battery temperature: a STOP is held
        when {
            stop -> { coolSinceMs = null; _level.value = HeatLevel.STOP }
            _level.value == HeatLevel.STOP -> {
                if (!cool) { coolSinceMs = null; return }
                val now = clock()
                var since = coolSinceMs ?: now.also { coolSinceMs = it }
                if (now < since) { since = now; coolSinceMs = now } // the clock went back: restart the cool-down
                if (now - since >= RESUME_AFTER_MS) { coolSinceMs = null; _level.value = if (slow) HeatLevel.SLOW else HeatLevel.OK }
            }
            else -> _level.value = if (slow) HeatLevel.SLOW else HeatLevel.OK
        }
    }

    companion object {
        const val MODERATE = 3 // PowerManager.THERMAL_STATUS_MODERATE (plain ints keep this JVM-testable)
        const val SEVERE = 4   // PowerManager.THERMAL_STATUS_SEVERE
        const val SLOW_C = 40f
        const val STOP_C = 44f
        const val RESUME_C = 38f
        const val RESUME_AFTER_MS = 120_000L
    }
}
