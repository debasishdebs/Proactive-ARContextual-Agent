package com.rokid.context.glasses.capture.motion

import kotlinx.coroutines.flow.MutableStateFlow
import com.rokid.context.core.model.WearEstimate
import com.rokid.context.core.model.normalized
import kotlinx.coroutines.flow.StateFlow

/**
 * The wear state. Fix wave H: the only real signal is the phone's `pc_wear` (the Rokid SDK's wearingStatus, driven by the
 * glasses' own sensor), via [onRemote]. Unknown counts as worn: [worn] is false only after a real `worn=false` in this
 * session; [reset] (session closed, process start) goes back to unknown. The Android TYPE_PROXIMITY sensor reads a
 * constant "far" on these glasses, so [onProximity] only records what it saw for the diagnostic log and never changes [worn].
 */
class WearDetector(
    @Suppress("UNUSED_PARAMETER") clock: () -> Long = { 0L },
    /** Milestone lines of the v1.0 wear estimate (fixed words and counts only). */
    private val log: (String) -> Unit = {},
) {
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

    // ---- v1.0 wear estimate ([WearEstimate]): guarded by this object's monitor (the sensor thread's second, the main thread's interactions, the link's wear values) ----
    private val _estimated = MutableStateFlow(false)
    /**
     * "Not worn (estimated)": [WearEstimate.WINDOW_MS] of quiet seconds (no head micro-motion) with no interaction, while [WearEstimate.applies] (the system wear
     * check is not on). Any meaningful motion, an interaction, the check turning on or a real wear value it must yield to ends it at once.
     */
    val estimated: StateFlow<Boolean> = _estimated
    /** The glasses' system wear check as the phone last read it (`pc_wear` wearCheck); null = not known. Kept across sessions (a device setting). */
    @Volatile var wearCheck: Boolean? = null
        private set
    private var quietSinceMs = -1L
    /** The quiet run reached the window (the raw estimate, before [WearEstimate.applies]). */
    private var quietLong = false
    private var quietPeakGyro = 0f
    private var quietPeakAcc = 0f
    private var stillPeakGyro = 0f
    private var stillPeakAcc = 0f
    private var stillLongLogged = false

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
        estimateSecond(gyroMax, accVariance, tMs)
    }

    @Synchronized private fun estimateSecond(gyroMax: Float, accVariance: Float, tMs: Long) {
        if (stillSinceMs < 0) { stillPeakGyro = 0f; stillPeakAcc = 0f; stillLongLogged = false }
        else { stillPeakGyro = maxOf(stillPeakGyro, gyroMax); stillPeakAcc = maxOf(stillPeakAcc, accVariance) }
        if (gyroMax < WearEstimate.QUIET_GYRO_RAD_S && accVariance < WearEstimate.QUIET_ACC_VAR) {
            if (quietSinceMs < 0) { quietSinceMs = tMs; quietPeakGyro = 0f; quietPeakAcc = 0f }
            quietPeakGyro = maxOf(quietPeakGyro, gyroMax); quietPeakAcc = maxOf(quietPeakAcc, accVariance)
            if (!quietLong && tMs - quietSinceMs + 1000L >= WearEstimate.WINDOW_MS) quietLong = true
        } else {
            quietSinceMs = -1L
            quietLong = false
        }
        // Tuning (device): a park-still run as long as the window that the estimate did not take. Once per still run, with the run's peaks.
        if (!quietLong && !stillLongLogged && stillSinceMs >= 0 && tMs - stillSinceMs + 1000L >= WearEstimate.WINDOW_MS) {
            stillLongLogged = true
            val n = (tMs - stillSinceMs + 1000L) / 1000L
            val gyroPeakMrad = (stillPeakGyro * 1000f).toInt(); val accPeakMicro = (stillPeakAcc * 1_000_000f).toInt()
            log("wear estimate: still $n s but not quiet gyroPeakMrad=$gyroPeakMrad accPeakMicro=$accPeakMicro")
        }
        publish(if (quietLong) null else "motion", tMs)
    }

    /** A tap, swipe, listen or mark on the glasses: the wearer is there. Ends an estimate at once and starts the quiet window again. */
    @Synchronized fun onInteraction() {
        quietSinceMs = -1L
        quietLong = false
        publish("interaction", null)
    }

    /** `pc_wear` wearCheck from the phone (null = the phone does not know; an old phone never sends it). */
    @Synchronized fun onWearCheck(on: Boolean?) {
        if (on == wearCheck) return
        wearCheck = on
        publish(if (on == true) "wear_check_on" else "real_signal", null)
    }

    /** Recomputes [estimated]; [reason] names what ended it (logged only on that edge). Caller holds the monitor. */
    private fun publish(reason: String?, tMs: Long?) {
        val now = quietLong && WearEstimate.applies(wearCheck, known)
        if (now == _estimated.value) return
        _estimated.value = now
        if (now) {
            val n = if (tMs != null && quietSinceMs >= 0) (tMs - quietSinceMs + 1000L) / 1000L else WearEstimate.WINDOW_MS / 1000L
            val gyroPeakMrad = (quietPeakGyro * 1000f).toInt(); val accPeakMicro = (quietPeakAcc * 1_000_000f).toInt()
            log("wear estimate: ${WearEstimate.WORD} after $n s without head motion gyroPeakMrad=$gyroPeakMrad accPeakMicro=$accPeakMicro")
        } else log("wear estimate: worn again (${reason ?: "real_signal"})")
    }

    /** Forget a still run carried over from before a session restart (an unbroken run must be seen by this session). The estimate's quiet run too. */
    fun resetStillRun() {
        stillSinceMs = -1L; _parked.value = false
        synchronized(this) { quietSinceMs = -1L; quietLong = false; stillLongLogged = false; publish("restart", null) }
    }

    fun verdictLine(): String = "wear=${_worn.value} known=$known parked=${_parked.value}"

    fun onRemote(worn: Boolean) { synchronized(this) { known = true; _worn.value = worn; publish("real_signal", null) } }

    fun reset() { synchronized(this) { known = false; _worn.value = true; publish("real_signal", null) } }

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
