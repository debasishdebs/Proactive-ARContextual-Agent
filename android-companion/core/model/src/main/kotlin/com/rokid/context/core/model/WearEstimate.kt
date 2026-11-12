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

    /**
     * The estimate may stand only while the system wear check is not on: [wearCheck] false (the "worn" the SDK reports is pinned, not real), or unknown with no
     * wear value at all ([realWearKnown] false). With the check on, or a wear value while the check state is unknown (an old phone), the real signal wins.
     */
    fun applies(wearCheck: Boolean?, realWearKnown: Boolean): Boolean = when (wearCheck) {
        true -> false
        false -> true
        null -> !realWearKnown
    }
}
