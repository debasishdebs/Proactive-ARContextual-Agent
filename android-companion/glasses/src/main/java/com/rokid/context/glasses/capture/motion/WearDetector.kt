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
     * "Not worn (estimated)": [WearEstimate.WINDOW_MS] of quiet seconds (no head micro-motion), observed with fresh IMU samples, and no interaction, while
     * [WearEstimate.applies] (the phone said the system wear check is OFF in this session, and no real wear change was seen). Any meaningful motion, an
     * interaction, a data gap, the sensing stopping, the check not being off, or a session change ends it at once.
     */
    val estimated: StateFlow<Boolean> = _estimated
    /** The glasses' system wear check as the phone said in THIS session (`pc_wear` wearCheck); null = not said (no estimate). [reset] forgets it. */
    @Volatile var wearCheck: Boolean? = null
        private set
    /**
     * The time of the newest real IMU (gyro) sample, from the running classifier ([stopSensing] clears it). A second only counts for the estimate when this
     * advanced since the previous second: a timer tick without samples is not an observation.
     */
    @Volatile var imuSampleAt: (() -> Long?)? = null
    /** A real wear change (worn to not worn or back) seen in this session: the sensor works, so no estimate until the session ends. */
    private var sensorLive = false
    private var quietSinceMs = -1L
    private var quietCovered = 0
    private var uncoveredStreak = 0
    private var lastSecondAt = -1L
    private var lastSampleAt: Long? = null
    /** The quiet run reached the window with enough coverage (the raw estimate, before [WearEstimate.applies]). */
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
        // Coverage: the tick itself must be on time, and the classifier must have had real samples since the last tick.
        val tickGap = lastSecondAt >= 0 && tMs - lastSecondAt > WearEstimate.MAX_GAP_MS
        lastSecondAt = tMs
        val sample = imuSampleAt?.invoke()
        val covered = sample != null && sample != lastSampleAt
        if (sample != null) lastSampleAt = sample
        uncoveredStreak = if (covered) 0 else uncoveredStreak + 1
        val quiet = gyroMax < WearEstimate.QUIET_GYRO_RAD_S && accVariance < WearEstimate.QUIET_ACC_VAR
        val why: String? = when {
            tickGap || uncoveredStreak * 1000L > WearEstimate.MAX_GAP_MS -> { endQuietRun(); "no_imu_data" }
            !covered -> if (quietLong) null else "no_imu_data" // a short batching hole: neither quiet nor motion
            quiet -> {
                if (quietSinceMs < 0) { quietSinceMs = tMs; quietCovered = 0; quietPeakGyro = 0f; quietPeakAcc = 0f }
                quietCovered++
                quietPeakGyro = maxOf(quietPeakGyro, gyroMax); quietPeakAcc = maxOf(quietPeakAcc, accVariance)
                val run = tMs - quietSinceMs + 1000L
                if (!quietLong && run >= WearEstimate.WINDOW_MS && quietCovered * 1000L * 100L >= run * WearEstimate.MIN_COVERAGE_PCT) quietLong = true // covered seconds / run, never more than the run allows
                null
            }
            else -> { endQuietRun(); "motion" }
        }
        // Tuning (device): a park-still run as long as the window that the estimate did not take. Once per still run, with the run's peaks.
        if (!quietLong && !stillLongLogged && stillSinceMs >= 0 && tMs - stillSinceMs + 1000L >= WearEstimate.WINDOW_MS) {
            stillLongLogged = true
            val n = (tMs - stillSinceMs + 1000L) / 1000L
            val gyroPeakMrad = (stillPeakGyro * 1000f).toInt(); val accPeakMicro = (stillPeakAcc * 1_000_000f).toInt()
            log("wear estimate: still $n s but not quiet gyroPeakMrad=$gyroPeakMrad accPeakMicro=$accPeakMicro")
        }
        publish(why, tMs)
    }

    /** Caller holds the monitor. */
    private fun endQuietRun() { quietSinceMs = -1L; quietCovered = 0; quietLong = false }

    /** A tap, swipe, listen or mark on the glasses: the wearer is there. Ends an estimate at once and starts the quiet window again. */
    @Synchronized fun onInteraction() {
        endQuietRun()
        publish("interaction", null)
    }

    /** The IMU feed stopped (Smart capture left): no samples, no estimate; a new feed starts the window again. */
    @Synchronized fun stopSensing() {
        imuSampleAt = null
        endQuietRun(); lastSecondAt = -1L; lastSampleAt = null; uncoveredStreak = 0
        publish("sensing_stopped", null)
    }

    /** `pc_wear` wearCheck from the phone (null = the phone does not know; an old phone never sends it). */
    @Synchronized fun onWearCheck(on: Boolean?) {
        if (on == wearCheck) return
        wearCheck = on
        publish(if (on == true) "wear_check_on" else "wear_check_unknown", null)
    }

    /** Recomputes [estimated]; [reason] names what ended it (logged only on that edge). Caller holds the monitor. */
    private fun publish(reason: String?, tMs: Long?) {
        val now = quietLong && WearEstimate.applies(wearCheck, sensorLive)
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
        synchronized(this) { endQuietRun(); stillLongLogged = false; lastSecondAt = -1L; lastSampleAt = null; uncoveredStreak = 0; publish("restart", null) }
    }

    fun verdictLine(): String = "wear=${_worn.value} known=$known parked=${_parked.value}"

    fun onRemote(worn: Boolean) {
        synchronized(this) {
            if (!worn || (known && worn != _worn.value)) sensorLive = true // a real not-worn or a change: the sensor works
            known = true; _worn.value = worn; publish("real_signal", null)
        }
    }

    /** Session closed (or process start): wear unknown again, and the wear check must be said again in the next session. */
    fun reset() { synchronized(this) { known = false; _worn.value = true; wearCheck = null; sensorLive = false; publish("session_closed", null) } }

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
