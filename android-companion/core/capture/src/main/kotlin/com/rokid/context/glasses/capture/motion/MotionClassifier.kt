package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.signals.ClassifierThresholds
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/** Glances and micro-motion bouts counted since the previous take. */
data class GlanceCounts(val glances: Int, val micro: Int)

/**
 * Gyro + linear-acceleration motion state over a 1 s sliding window (spec P1.1).
 * Everything is decided per sample, so [onset] lags the movement by at most one sensor sample.
 * Thresholds are constants for now; they are expected to be tuned on device.
 */
class MotionClassifier(@Suppress("unused") private val clock: () -> Long, params: GlanceParams = GlanceParams()) {
    /** The glance tunables in force; every read goes through here (slice 3a: a plan swaps the whole set at once, see [setConfig]). */
    @Volatile var config: GlanceParams = params
        private set

    /** v0.9.7 A: the editable walking / head-moving thresholds in force (defaults = the shipped constants below). Read once per sample; swapped whole. */
    @Volatile var thresholds: ClassifierThresholds = ClassifierThresholds.DEFAULT
        private set

    fun setThresholds(t: ClassifierThresholds) { thresholds = t }

    /** Swaps every glance tunable at once (the classifier's own reads and the detector's). The caller picks the safe moment: [quiet], or [abortBout] first. */
    @Synchronized fun setConfig(p: GlanceParams) { config = p; detector.p = p }

    /** No bout running and no glance pending: a plan may swap the config now. */
    @Synchronized fun quiet(): Boolean = detector.isQuiet()

    /** Drops the running bout and any pending glance (the 10 s forced swap). */
    @Synchronized fun abortBout() { detector.abort() }

    private val taps = java.util.concurrent.CopyOnWriteArrayList<SampleTap>()

    /** Registers a read-only consumer of every input sample ([SampleTap]). */
    fun addTap(t: SampleTap) { taps.addIfAbsent(t) }
    fun removeTap(t: SampleTap) { taps.remove(t) }

    /** Keeps the trailing-lambda form `MotionClassifier { now }` (default [GlanceParams]). */
    constructor(clock: () -> Long) : this(clock, GlanceParams())

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

    /**
     * Newest sample time per sensor. Batched delivery (STILL rate) gives no cross-sensor order, so the two streams are
     * never mixed on one clock: head-turn onset/hold and the dwell run on gyro time, the walking window on accel time.
     * The result is the same whichever sensor's batch arrives first.
     */
    private var gyroNewestMs = 0L
    private var accelNewestMs = 0L

    private var lastAboveGyroMs = -1_000_000_000L // newest gyro sample over the threshold
    private val detector = GlanceDetector(params)
    private var lastGyroMs = -1L                 // previous gyro sample time (dt source), -1 before the first
    private var peak = 0f
    private var featGyroMax = 0f                 // diagnostics: the largest gyro magnitude since the last [features]

    // Per-second accumulators since the last [features]
    private var featN = 0
    private var featSum = 0f
    private var featSumSq = 0f
    private var featAbove = 0
    private var featTravelled = 0f
    private var featGlances = 0
    private var featMicro = 0
    // Per-heartbeat glance counters ([takeGlanceCounts])
    private var hbGlances = 0
    private var hbMicro = 0

    // Head pose since the last [markPose]: integrated angular velocity (rad) and whether we walked
    private var rx = 0f
    private var ry = 0f
    private var rz = 0f
    private var walked = false
    // Gyro-bias drift bound: the pose as it was when the head last moved (a sample over the release threshold). After
    // POSE_QUIET_RESET_MS without such a sample, what the pose gained since is bias drift and is discarded, so a bias
    // at or above the deadband cannot grow the pose to the novelty threshold while the head is still.
    private var quietSinceMs = -1L
    private var qx = 0f
    private var qy = 0f
    private var qz = 0f

    // Cached per accel sample (the window only changes then): the strict rule, and the K3 relaxed rule's run.
    private var strictWalk = false
    private var relaxedSinceMs = -1L
    private var relaxedLastOkMs = 0L
    private var lastVariance = 0f
    private var lastHz = 0.0

    @Synchronized fun onGyro(x: Float, y: Float, z: Float, tMs: Long) {
        gyroNewestMs = maxOf(gyroNewestMs, tMs); val t = gyroNewestMs
        val mag = sqrt(x * x + y * y + z * z)
        val above = mag > thresholds.headMovingRadS
        if (mag > featGyroMax) featGyroMax = mag
        val dt = if (lastGyroMs < 0) 0L else maxOf(0L, tMs - lastGyroMs)
        lastGyroMs = maxOf(lastGyroMs, tMs)
        val dtS = dt.coerceAtMost(config.maxDtMs) / 1000f
        featN++; featSum += mag; featSumSq += mag * mag; featTravelled += mag * dtS
        if (mag > config.onsetRadS) featAbove++
        if (mag >= config.deadbandRadS) { rx += x * dtS; ry += y * dtS; rz += z * dtS }
        trackQuiet(mag, tMs)
        if (above) {
            lastAboveGyroMs = maxOf(lastAboveGyroMs, tMs)
            if (mag > peak) peak = mag
        }
        // Under the threshold for HEAD_HOLD_MS the turn is over: a stale peak must not outlive it (a walk lasts minutes).
        if (!above && t - lastAboveGyroMs >= HEAD_HOLD_MS) peak = 0f
        update(t)
        // Only a head turn has a peak to decelerate from; never while walking.
        _decelerating.value = _state.value == MotionState.HEAD_MOVING && peak > 0f && mag < DECEL_FRACTION * peak
        if (_state.value != MotionState.HEAD_MOVING) peak = 0f
        if (_state.value == MotionState.WALKING) walked = true
        val fired = detector.onGyro(mag, t, dt, _state.value == MotionState.STILL)
        drainDetector()
        if (fired) emitDwell(t)
        if (taps.isNotEmpty()) for (tap in taps) try { tap.onGyro(x, y, z, tMs, _state.value) } catch (e: Exception) { /* a tap never affects capture */ }
    }

    @Synchronized fun onLinearAccel(x: Float, y: Float, z: Float, tMs: Long) {
        accelNewestMs = maxOf(accelNewestMs, tMs); val t = accelNewestMs
        accT.addLast(tMs); accAxes.addLast(floatArrayOf(x, y, z))
        while (accT.isNotEmpty() && tMs - accT.first() > WINDOW_MS) { accT.removeFirst(); accAxes.removeFirst() }
        analyseWindow(tMs)
        update(t)
        if (_state.value == MotionState.WALKING) { walked = true; detector.onWalking(tMs) }
        val fired = detector.onTime(gyroNewestMs, _state.value == MotionState.STILL)
        drainDetector()
        if (fired) emitDwell(gyroNewestMs)
        if (taps.isNotEmpty()) for (tap in taps) try { tap.onAccel(x, y, z, tMs, gyroNewestMs, _state.value) } catch (e: Exception) { /* a tap never affects capture */ }
    }

    /** [tMs] stamps an onset: the own time of the sample that caused it. The head-hold test always runs on gyro time. */
    private fun update(tMs: Long) {
        val next = when {
            walking() -> MotionState.WALKING
            gyroNewestMs - lastAboveGyroMs <= HEAD_HOLD_MS -> MotionState.HEAD_MOVING
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

    private fun emitDwell(tMs: Long) {
        _dwell.tryEmit(tMs); dwellListener?.invoke(tMs)
    }

    private fun drainDetector() {
        featGlances += detector.glances; hbGlances += detector.glances
        featMicro += detector.micro; hbMicro += detector.micro
        detector.glances = 0; detector.micro = 0
    }

    /** Caller holds the lock. Snapshots the pose at each moving sample; restores it after a long quiet spell (bias drift). */
    private fun trackQuiet(mag: Float, tMs: Long) {
        if (quietSinceMs < 0 || mag > config.releaseRadS) {
            quietSinceMs = tMs; qx = rx; qy = ry; qz = rz
        } else if (tMs - quietSinceMs >= POSE_QUIET_RESET_MS) {
            rx = qx; ry = qy; rz = qz
            quietSinceMs = tMs
        }
    }

    /** Net head turn (rad) since the last [markPose]: a look away and back cancels. */
    @Synchronized fun rotationSinceMarkRad(): Float = sqrt(rx * rx + ry * ry + rz * rz)

    @Synchronized fun walkedSinceMark(): Boolean = walked

    /**
     * Replay seam (slice 3a Task 20): starts the classifier from what a trace's start snapshot recorded, so the first replayed sample has the same `dt` and
     * the pose the glance admission reads is the live one. [prevGyroMs] and [quietSinceMs] null = -1 (none yet). Internal: only the simulator calls it.
     */
    @Synchronized internal fun restoreForReplay(
        prevGyroMs: Long?, rx: Float, ry: Float, rz: Float, walked: Boolean, qx: Float, qy: Float, qz: Float, quietSinceMs: Long?,
    ) {
        lastGyroMs = prevGyroMs ?: -1L
        this.rx = rx; this.ry = ry; this.rz = rz; this.walked = walked
        this.qx = qx; this.qy = qy; this.qz = qz
        this.quietSinceMs = quietSinceMs ?: -1L
    }

    /** The head pose accumulated since the last [markPose] (the IMU trace's start snapshot). */
    @Synchronized fun poseSnapshot(): com.rokid.context.capture.trace.PoseSnapshot =
        com.rokid.context.capture.trace.PoseSnapshot(rx, ry, rz, walked, qx, qy, qz, quietSinceMs.takeIf { it >= 0 })

    /** The previous gyro sensor time (the `dt` source of the next sample), or null before any. */
    @Synchronized fun prevGyroMs(): Long? = lastGyroMs.takeIf { it >= 0 }
    /** The time of the newest linear-acceleration sample (null before any): with [prevGyroMs] it tells the wear estimate whether the IMU is delivering. */
    @Synchronized fun accelNewestMs(): Long? = accelNewestMs.takeIf { it > 0 }

    @Synchronized fun markPose() { rx = 0f; ry = 0f; rz = 0f; walked = false; qx = 0f; qy = 0f; qz = 0f }

    /** Glances and micro bouts since the last call (the heartbeat). */
    @Synchronized fun takeGlanceCounts(): GlanceCounts {
        val c = GlanceCounts(hbGlances, hbMicro); hbGlances = 0; hbMicro = 0
        return c
    }

    /** WALKING by the strict rule or by the provisional relaxed one (see [analyseWindow]). */
    private fun walking(): Boolean = strictWalk || (relaxedSinceMs >= 0 && accT.isNotEmpty() && accT.last() - relaxedSinceMs >= thresholds.relaxedHoldMs)

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
        val th = thresholds
        strictWalk = lastVariance > th.walkVariance && hz in th.walkMinHz..th.walkMaxHz
        // A 1 s window counts whole crossings (0.5 Hz steps), so a steady 1.4 Hz reads 1.0 / 1.5 alternately: a miss shorter than the grace does not end the run.
        if (lastVariance > th.relaxedVariance && hz in th.relaxedMinHz..th.relaxedMaxHz) { if (relaxedSinceMs < 0) relaxedSinceMs = tMs; relaxedLastOkMs = tMs }
        else if (relaxedSinceMs >= 0 && tMs - relaxedLastOkMs > RELAXED_GRACE_MS) relaxedSinceMs = -1L
    }

    /** What the classifier saw since the last call (gyro peak) and now (window variance, step frequency, state). */
    class Features(
        val gyroMax: Float, val accVariance: Float, val stepHz: Double, val state: MotionState,
        val gyroMean: Float = 0f, val gyroRms: Float = 0f, val aboveOnFrac: Float = 0f, val rotRad: Float = 0f,
        val glances: Int = 0, val micro: Int = 0,
    )

    @Synchronized fun features(): Features {
        val n = featN
        val f = Features(
            featGyroMax, lastVariance, lastHz, _state.value,
            gyroMean = if (n > 0) featSum / n else 0f,
            gyroRms = if (n > 0) sqrt(featSumSq / n) else 0f,
            aboveOnFrac = if (n > 0) featAbove.toFloat() / n else 0f,
            rotRad = featTravelled, glances = featGlances, micro = featMicro,
        )
        featGyroMax = 0f; featN = 0; featSum = 0f; featSumSq = 0f; featAbove = 0; featTravelled = 0f
        featGlances = 0; featMicro = 0
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
        /** No head movement (nothing over the release threshold) for this long: the pose gained since is gyro-bias drift and is dropped. */
        const val POSE_QUIET_RESET_MS = 4_000L
        const val HEAD_HOLD_MS = 250L   // bridges the zero crossings inside one head turn
        /** Legacy; see [GlanceParams.LEGACY]. */
        const val DWELL_MS = 1000L
        const val DECEL_FRACTION = 0.6f
    }
}
