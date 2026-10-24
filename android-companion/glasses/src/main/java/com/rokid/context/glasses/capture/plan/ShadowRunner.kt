package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.ExprRunner
import com.rokid.context.capture.plan.ExprSignals
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.GlanceDetector
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.motion.SampleTap
import com.rokid.context.glasses.capture.session.GlanceAdmission
import kotlin.math.sqrt

/** Counterfactual glance opens and skips since the previous [ShadowRunner.take]. */
data class ShadowCounts(val open: Int, val skip: Int, val drop: Int = 0, val exprErr: Int = 0, val exprDiv0: Int = 0)

/**
 * SHADOW (S67): a second glance detector on the same sensor samples (through [SampleTap]), configured by the SHADOW plan, that only COUNTS
 * what the plan would have done with a glance: would-open, would-skip (novelty or cap). It holds no camera, session or owner, so it cannot
 * change real capture. It assumes each would-open becomes a keep (a point estimate, spec L1). Runs on the sensor thread under the
 * classifier's lock; every field is touched only there, except [take] (own lock).
 *
 * [config] is the SHADOW plan's config, or null when none is in force (nothing is counted and the detector is dropped). A change of
 * config starts from a clean detector, so no glance spans two plans.
 */
class ShadowRunner(
    private val config: () -> ActiveConfig?,
    private val applied: () -> ContextState,
    private val resting: () -> Boolean = { false },
    /** 3c X3: the features the guards read and the context age; a SHADOW guard only COUNTS would-drops (S67), it never acts. */
    private val signals: ExprSignals = ExprSignals(),
    private val ctxAgeS: () -> Long = { 0L },
) : SampleTap {
    private val lock = Any()
    private var cfg: ActiveConfig? = null
    private var detector: GlanceDetector? = null
    private var open = 0
    private var skip = 0
    private var drop = 0
    private var runner: ExprRunner? = null

    private var lastGyroMs = -1L
    private var lastKeyAt = 0L
    private val keptAt = ArrayDeque<Long>()
    private var rx = 0f; private var ry = 0f; private var rz = 0f
    private var qx = 0f; private var qy = 0f; private var qz = 0f
    private var quietSince = -1L
    private var walked = false

    fun take(): ShadowCounts = synchronized(lock) {
        val c = runner?.takeCounts()
        ShadowCounts(open, skip, drop, c?.get(0) ?: 0, c?.get(1) ?: 0).also { open = 0; skip = 0; drop = 0 }
    }

    private fun sync(tMs: Long): ActiveConfig? {
        val c = config()
        if (c !== cfg) {
            cfg = c; runner = c?.exprRunner
            runner?.bind(signals)   // 3c C1b: a SHADOW plan's windows start empty at its apply
            detector = c?.let { GlanceDetector(it.glance) }
            lastGyroMs = -1L; lastKeyAt = tMs; keptAt.clear(); resetPose(); quietSince = -1L
        }
        return c
    }

    private fun resetPose() { rx = 0f; ry = 0f; rz = 0f; qx = 0f; qy = 0f; qz = 0f; walked = false }

    override fun onGyro(x: Float, y: Float, z: Float, tMs: Long, state: MotionState) = synchronized(lock) {
        val c = sync(tMs) ?: return@synchronized
        val d = detector ?: return@synchronized
        val g = c.glance
        val mag = sqrt(x * x + y * y + z * z)
        val dt = if (lastGyroMs < 0) 0L else maxOf(0L, tMs - lastGyroMs)
        lastGyroMs = maxOf(lastGyroMs, tMs)
        val dtS = dt.coerceAtMost(g.maxDtMs) / 1000f
        if (mag >= g.deadbandRadS) { rx += x * dtS; ry += y * dtS; rz += z * dtS }
        // the classifier's own gyro-bias drift bound: a long quiet spell drops what the pose gained since
        if (quietSince < 0 || mag > g.releaseRadS) { quietSince = tMs; qx = rx; qy = ry; qz = rz }
        else if (tMs - quietSince >= POSE_QUIET_RESET_MS) { rx = qx; ry = qy; rz = qz; quietSince = tMs }
        if (state == MotionState.WALKING) walked = true
        if (d.onGyro(mag, tMs, dt, state == MotionState.STILL)) fired(c, tMs)
    }

    override fun onAccel(x: Float, y: Float, z: Float, tMs: Long, gyroNewestMs: Long, state: MotionState) = synchronized(lock) {
        val c = sync(tMs) ?: return@synchronized
        val d = detector ?: return@synchronized
        if (state == MotionState.WALKING) { walked = true; d.onWalking(tMs) }
        if (d.onTime(gyroNewestMs, state == MotionState.STILL)) fired(c, gyroNewestMs)
    }

    /** A glance fired under the plan's detector: the same free IMU admission test the session runs, with the plan's cap row. */
    private fun fired(c: ActiveConfig, now: Long) {
        val g = c.glance
        while (keptAt.isNotEmpty() && now - keptAt.first() >= 60_000L) keptAt.removeFirst()
        val rowCap = c.contextCap(applied())
        val cap = minOf(if (resting()) g.restCapPerMin else g.capPerMin, rowCap ?: Int.MAX_VALUE)
        val turned = sqrt(rx * rx + ry * ry + rz * rz)
        val keepGuard = c.exprRunner?.keep(now, signals, applied(), ctxAgeS()) ?: true
        when (GlanceAdmission.decide(now - lastKeyAt, keptAt.size, cap, resting() || rowCap != null, turned, walked, g, keepGuard)) {
            GlanceAdmission.Decision.GUARD_DROP -> drop++
            GlanceAdmission.Decision.OPEN -> { open++; lastKeyAt = now; keptAt.addLast(now); resetPose() }
            else -> skip++
        }
    }

    private companion object { const val POSE_QUIET_RESET_MS = 4_000L }
}
