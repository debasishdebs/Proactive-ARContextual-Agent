package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Gyro + linear-acceleration motion state over a 1 s sliding window (spec P1.1).
 * Everything is decided per sample, so [onset] lags the movement by at most one sensor sample.
 * Thresholds are constants for now; they are expected to be tuned on device.
 */
class MotionClassifier(@Suppress("unused") private val clock: () -> Long) {
    private val _state = MutableStateFlow(MotionState.STILL)
    val state: StateFlow<MotionState> = _state

    private val _dwell = MutableSharedFlow<Long>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val dwell: SharedFlow<Long> = _dwell

    private val _onset = MutableSharedFlow<Long>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val onset: SharedFlow<Long> = _onset

    private val _decelerating = MutableStateFlow(false)
    val decelerating: StateFlow<Boolean> = _decelerating

    /** Synchronous hooks for the same events (tests, and callers without a collector scope). */
    internal var dwellListener: ((Long) -> Unit)? = null
    internal var onsetListener: ((Long) -> Unit)? = null

    private val accT = ArrayDeque<Long>()
    private val accAxes = ArrayDeque<FloatArray>()

    private var lastAboveGyroMs = -1_000_000_000L // newest gyro sample over the threshold
    private var stillSinceMs = 0L                // last time movement was seen (dwell clock)
    private var hadMotion = false                // moved since the last dwell
    private var peak = 0f
    private var featGyroMax = 0f                 // diagnostics: the largest gyro magnitude since the last [features]

    // Cached per accel sample (the window only changes then): the strict rule, and the K3 relaxed rule's run.
    private var strictWalk = false
    private var relaxedSinceMs = -1L
    private var relaxedLastOkMs = 0L
    private var lastVariance = 0f
    private var lastHz = 0.0

    @Synchronized fun onGyro(x: Float, y: Float, z: Float, tMs: Long) {
        val mag = sqrt(x * x + y * y + z * z)
        val above = mag > HEAD_MOVING_RAD_S
        if (mag > featGyroMax) featGyroMax = mag
        if (above) {
            lastAboveGyroMs = tMs
            stillSinceMs = tMs
            hadMotion = true
            if (mag > peak) peak = mag
        }
        // Under the threshold for HEAD_HOLD_MS the turn is over: a stale peak must not outlive it (a walk lasts minutes).
        if (!above && tMs - lastAboveGyroMs >= HEAD_HOLD_MS) peak = 0f
        update(tMs)
        // Only a head turn has a peak to decelerate from; never while walking.
        _decelerating.value = _state.value == MotionState.HEAD_MOVING && peak > 0f && mag < DECEL_FRACTION * peak
        if (_state.value != MotionState.HEAD_MOVING) peak = 0f
        checkDwell(tMs)
    }

    @Synchronized fun onLinearAccel(x: Float, y: Float, z: Float, tMs: Long) {
        accT.addLast(tMs); accAxes.addLast(floatArrayOf(x, y, z))
        while (accT.isNotEmpty() && tMs - accT.first() > WINDOW_MS) { accT.removeFirst(); accAxes.removeFirst() }
        analyseWindow(tMs)
        if (_state.value == MotionState.WALKING || walking()) { stillSinceMs = tMs; hadMotion = true }
        update(tMs)
        checkDwell(tMs)
    }

    private fun update(tMs: Long) {
        val next = when {
            walking() -> MotionState.WALKING
            tMs - lastAboveGyroMs <= HEAD_HOLD_MS -> MotionState.HEAD_MOVING
            else -> MotionState.STILL
        }
        val prev = _state.value
        if (next == prev) return
        if (prev == MotionState.STILL) {
            // peak is not reset here: the onset sample's own magnitude is already in it, so a one-sample jerk arms decelerating
            _onset.tryEmit(tMs); onsetListener?.invoke(tMs)
        }
        _state.value = next
    }

    private fun checkDwell(tMs: Long) {
        if (hadMotion && _state.value == MotionState.STILL && tMs - stillSinceMs >= DWELL_MS) {
            hadMotion = false
            _dwell.tryEmit(tMs); dwellListener?.invoke(tMs)
        }
    }

    /** WALKING by the strict rule or by the provisional relaxed one (see [analyseWindow]). */
    private fun walking(): Boolean = strictWalk || (relaxedSinceMs >= 0 && accT.isNotEmpty() && accT.last() - relaxedSinceMs >= RELAXED_HOLD_MS)

    /**
     * Summed per-axis variance of linear accel and a oscillation frequency on the strongest axis (mean crossings; the
     * magnitude would rectify the swing and double the frequency). Strict rule: variance above [WALK_VARIANCE] and 1.5-2.5 Hz.
     * PROVISIONAL relaxed rule (fix wave K3, retest 1 found walking rarely fired, no traces to tune from): variance above
     * [RELAXED_VARIANCE] with 1.2-2.8 Hz, sustained for [RELAXED_HOLD_MS]. To be tuned from the capture_diag.log feature lines.
     */
    private fun analyseWindow(tMs: Long) {
        strictWalk = false
        val n = accAxes.size
        if (n < 8 || accT.last() - accT.first() < MIN_SPAN_MS) { relaxedSinceMs = -1L; lastVariance = 0f; lastHz = 0.0; return }
        val mean = FloatArray(3)
        for (a in accAxes) for (i in 0..2) mean[i] += a[i] / n
        val v = FloatArray(3)
        for (a in accAxes) for (i in 0..2) v[i] += (a[i] - mean[i]) * (a[i] - mean[i]) / n
        lastVariance = v.sum()
        val k = v.indices.maxByOrNull { v[it] }!!
        var crossings = 0
        var prevSign = 0
        for (a in accAxes) {
            val d = a[k] - mean[k]
            val s = if (abs(d) < 1e-3f) 0 else if (d > 0) 1 else -1
            if (s != 0) { if (prevSign != 0 && s != prevSign) crossings++; prevSign = s }
        }
        val hz = crossings / 2.0 / ((accT.last() - accT.first()) / 1000.0)
        lastHz = hz
        strictWalk = lastVariance > WALK_VARIANCE && hz in WALK_MIN_HZ..WALK_MAX_HZ
        // A 1 s window counts whole crossings (0.5 Hz steps), so a steady 1.4 Hz reads 1.0 / 1.5 alternately: a miss shorter than the grace does not end the run.
        if (lastVariance > RELAXED_VARIANCE && hz in RELAXED_MIN_HZ..RELAXED_MAX_HZ) { if (relaxedSinceMs < 0) relaxedSinceMs = tMs; relaxedLastOkMs = tMs }
        else if (relaxedSinceMs >= 0 && tMs - relaxedLastOkMs > RELAXED_GRACE_MS) relaxedSinceMs = -1L
    }

    /** What the classifier saw since the last call (gyro peak) and now (window variance, step frequency, state). */
    class Features(val gyroMax: Float, val accVariance: Float, val stepHz: Double, val state: MotionState)

    @Synchronized fun features(): Features {
        val f = Features(featGyroMax, lastVariance, lastHz, _state.value)
        featGyroMax = 0f
        return f
    }

    companion object {
        const val HEAD_MOVING_RAD_S = 0.6f
        const val WALK_VARIANCE = 0.5f
        const val WALK_MIN_HZ = 1.5
        const val WALK_MAX_HZ = 2.5
        const val RELAXED_VARIANCE = 0.25f
        const val RELAXED_MIN_HZ = 1.2
        const val RELAXED_MAX_HZ = 2.8
        const val RELAXED_HOLD_MS = 2_000L
        const val RELAXED_GRACE_MS = 500L
        const val WINDOW_MS = 1000L
        const val MIN_SPAN_MS = 800L
        const val HEAD_HOLD_MS = 250L   // bridges the zero crossings inside one head turn
        const val DWELL_MS = 1000L
        const val DECEL_FRACTION = 0.6f
    }
}
