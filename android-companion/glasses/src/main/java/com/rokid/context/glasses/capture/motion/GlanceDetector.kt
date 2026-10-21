package com.rokid.context.glasses.capture.motion

/**
 * QUIET / BOUT / SETTLING machine of the glance model (spec A3 section 3). Pure and not thread-safe: the classifier calls
 * it under its own lock, always on gyro time. A glance is a real head bout (long and wide enough) followed by [GlanceParams.settleMs]
 * without a sample over the onset threshold; shorter bouts are micro-motion, which never arms a glance but postpones a pending one.
 *
 * Bout length does not depend on the sample rate: a sample at time t stands for the period (t - dt, t], so a bout is measured
 * from its first sample's time minus one sample period (that sample's own dt, clamped to maxDtMs) to the last sample over the
 * release threshold. A real 300 ms look is therefore a bout at 20 Hz (6 samples, 250 ms apart first-to-last) as at 50 Hz.
 */
class GlanceDetector(private val p: GlanceParams) {
    private enum class Phase { QUIET, BOUT, SETTLING }

    private var phase = Phase.QUIET
    private var start = 0L
    private var lastAbove = 0L
    private var turned = 0f
    private var armed = false
    private var since = 0L

    /** Glances fired and micro bouts seen; the classifier reads and resets them. */
    var glances = 0
    var micro = 0

    /** One gyro sample of magnitude [mag] at [tMs]; [dtMs] since the previous gyro sample. True when a glance fires now. */
    fun onGyro(mag: Float, tMs: Long, dtMs: Long, still: Boolean): Boolean {
        if (phase == Phase.BOUT && tMs - lastAbove > p.bridgeMs) endBout()
        when (phase) {
            Phase.BOUT -> {
                turned += mag * dtMs.coerceAtMost(p.maxDtMs) / 1000f
                if (mag > p.releaseRadS) lastAbove = tMs
                return false
            }
            Phase.QUIET -> if (mag > p.onsetRadS) beginBout(mag, tMs, dtMs, armed = false)
            Phase.SETTLING -> if (mag > p.onsetRadS) beginBout(mag, tMs, dtMs, armed) else return settled(tMs, still)
        }
        return false
    }

    /** The settle test alone (accel callbacks, with gyro time). */
    fun onTime(tMs: Long, still: Boolean): Boolean = phase == Phase.SETTLING && settled(tMs, still)

    /** Walking: arriving (walk end then stillness) is a glance. */
    fun onWalking(tMs: Long) {
        phase = Phase.SETTLING; since = tMs; armed = true
    }

    private fun beginBout(mag: Float, tMs: Long, dtMs: Long, armed: Boolean) {
        phase = Phase.BOUT; start = tMs - dtMs.coerceIn(0L, p.maxDtMs); lastAbove = tMs
        turned = mag * dtMs.coerceAtMost(p.maxDtMs) / 1000f
        this.armed = armed
    }

    private fun endBout() {
        val end = lastAbove
        if (end - start >= p.minBoutMs && turned >= p.minBoutRad) {
            phase = Phase.SETTLING; since = end; armed = true
        } else {
            micro++
            if (armed) { phase = Phase.SETTLING; since = end } else phase = Phase.QUIET
        }
    }

    private fun settled(tMs: Long, still: Boolean): Boolean {
        if (!armed || !still || tMs - since < p.settleMs) return false
        phase = Phase.QUIET; armed = false; glances++
        return true
    }
}
