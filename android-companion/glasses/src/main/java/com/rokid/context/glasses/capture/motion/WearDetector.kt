package com.rokid.context.glasses.capture.motion

import kotlinx.coroutines.flow.MutableStateFlow
import com.rokid.context.core.model.normalized
import kotlinx.coroutines.flow.StateFlow

/**
 * The wear state. Fix wave H: the only real signal is the phone's `pc_wear` (the Rokid SDK's wearingStatus, driven by the
 * glasses' own sensor), via [onRemote]. Unknown counts as worn: [worn] is false only after a real `worn=false` in this
 * session; [reset] (session closed, process start) goes back to unknown. The Android TYPE_PROXIMITY sensor reads a
 * constant "far" on these glasses, so [onProximity] only records what it saw for the diagnostic log and never changes [worn].
 */
class WearDetector(@Suppress("UNUSED_PARAMETER") clock: () -> Long = { 0L }) {
    /** The active parking thresholds, read once a second (the cached settings; a new phone push applies at the next second). */
    @Volatile var config: () -> WearParkConfig = { WearParkConfig.DEFAULT }

    private val _worn = MutableStateFlow(true)
    val worn: StateFlow<Boolean> = _worn

    /** True once a real `pc_wear` arrived in this session. */
    @Volatile var known = false
        private set
    /** Diagnostic only: the last Android proximity verdict (true = near), null before any. */
    @Volatile var lastProximityNear: Boolean? = null
        private set

    private val _parked = MutableStateFlow(false)
    /** True after a long still run (W1 fallback when no `pc_wear` is trusted). Untouched by [reset] and [onRemote]. */
    val parked: StateFlow<Boolean> = _parked
    private var stillSinceMs = -1L

    /** One call per second with that second's gyro peak and linear-acceleration variance. */
    fun onSecond(gyroMax: Float, accVariance: Float, tMs: Long) {
        if (gyroMax < STILL_GYRO && accVariance < STILL_ACC_VAR) {
            if (stillSinceMs < 0) stillSinceMs = tMs
            val run = tMs - stillSinceMs + 1000L // the run includes the second just ended
            val c = config()
            _parked.value = c.enabled && run >= (if (known && _worn.value) c.wornMs else c.unknownMs)
        } else {
            stillSinceMs = -1L
            _parked.value = false
        }
    }

    /** Forget a still run carried over from before a session restart (an unbroken run must be seen by this session). */
    fun resetStillRun() { stillSinceMs = -1L; _parked.value = false }

    fun verdictLine(): String = "wear=${_worn.value} known=$known parked=${_parked.value}"

    fun onRemote(worn: Boolean) { known = true; _worn.value = worn }

    fun reset() { known = false; _worn.value = true }

    @Suppress("UNUSED_PARAMETER")
    fun onProximity(near: Boolean, tMs: Long = 0L) { lastProximityNear = near }

    /** Kept for the sensor feed's once-a-second call; nothing to age any more. */
    fun tick() = Unit

    companion object {
        const val STILL_GYRO = 0.05f
        const val STILL_ACC_VAR = 0.01f
        const val PARK_UNKNOWN_MS = 120_000L
        const val PARK_WORN_MS = 600_000L
    }
}

/** Park-when-still thresholds: [enabled] false never parks by stillness; [unknownMs] while the wear signal is unknown, [wornMs] while Rokid says worn. */
data class WearParkConfig(val enabled: Boolean, val unknownMs: Long, val wornMs: Long) {
    companion object {
        val DEFAULT = WearParkConfig(true, WearDetector.PARK_UNKNOWN_MS, WearDetector.PARK_WORN_MS)
        fun of(s: com.rokid.context.core.model.HudSettings) = s.normalized().let { WearParkConfig(it.wearParkEnabled, it.wearParkUnknownSec * 1000L, it.wearParkWornSec * 1000L) }
    }
}
