package com.rokid.context.core.model

/**
 * v1.0 wear fallback (user ruling 2026-11-12): while the Rokid "Wear detection" setting is off (the system then reports "worn" for glasses on a sofa), the glasses
 * infer "not worn (estimated)" from the IMU: [WINDOW_MS] of no meaningful head micro-motion (every second below [QUIET_GYRO_RAD_S] and [QUIET_ACC_VAR]) with no
 * wearer interaction (tap, swipe, listen, mark) in that time. Any such motion or interaction ends it at once. Orientation and charging are NOT used (ruling).
 *
 * The thresholds are half the park ones (WearDetector STILL_GYRO 0.05 rad/s, STILL_ACC_VAR 0.01): a very still wearer (reading, watching TV) may still park capture
 * (accepted in battery wave A, resumes on the first head move) but almost always shows a second above these within 10 minutes, so the estimate (which can stop the
 * phone hub) stays "worn". They are provisional and need device tuning: the glasses log the run's peaks when the estimate starts and when a still run reaches the
 * window without it.
 *
 * Missing, failed or stalled IMU data is never quiet time: a second counts only with fresh samples ([MAX_GAP_MS], [MIN_COVERAGE_PCT]).
 *
 * Wire: additive optional fields only ([MotionHint.wearEst] glasses to phone, [WearMessage.wearCheck] phone to glasses); [PROTOCOL_VERSION] is unchanged.
 */
object WearEstimate {
    /** No meaningful motion for this long (and no interaction) = "not worn (estimated)". */
    const val WINDOW_MS = 600_000L
    /** A second whose gyro peak (rad/s) is at or above this is meaningful motion. */
    const val QUIET_GYRO_RAD_S = 0.025f
    /** A second whose linear-acceleration variance ((m/s^2)^2) is at or above this is meaningful motion. */
    const val QUIET_ACC_VAR = 0.005f
    /** The status word (AutoStop note, logs). */
    const val WORD = "not worn (estimated)"
    /** A tick later than this, or this long without a fresh IMU sample, ends the quiet run (missing data is never quiet time). */
    const val MAX_GAP_MS = 3_000L
    /** The window must have real IMU samples in at least this share of its seconds (short batching holes are allowed). */
    const val MIN_COVERAGE_PCT = 90L
    /** A gyro or acceleration sample older than this at the second's tick is not fresh evidence (a stalled or batched delivery). */
    const val MAX_SAMPLE_AGE_MS = 5_000L
    /** Coverage is judged over the last minute, continuously: it must stay at [MIN_COVERAGE_PCT] or the quiet run ends. */
    const val COVERAGE_WINDOW_S = 60
    /** Phone side: an estimate not re-sent by the glasses within this long expires (the glasses re-send every 60 s while it holds). */
    const val FRESH_MS = 180_000L

    /**
     * The estimate may stand only when the phone positively said, in the CURRENT session, that the system wear check is off ([wearCheck] false), and no real
     * wear change (worn to not worn or back) was seen in it ([sensorLive]: the sensor works after all). Unknown (null) or on = no estimate: the real signal wins.
     */
    fun applies(wearCheck: Boolean?, sensorLive: Boolean): Boolean = wearCheck == false && !sensorLive
}
