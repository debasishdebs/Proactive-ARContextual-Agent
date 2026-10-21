package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionState
import java.util.Locale

/**
 * Fix wave K3: once per second, one count-only line of the IMU features the walking rule looks at (gyro magnitude peak
 * of the last second, linear-acceleration variance, dominant step-frequency estimate, classifier state), so the next
 * run can tune the thresholds from `files/capture_diag.log`. Numbers only, never sensor traces.
 */
class FeatureLogger(
    private val classifier: MotionClassifier,
    private val sink: (String) -> Unit,
    private val clock: () -> Long = { 0L },
    private val verbose: Boolean = false,
) {
    private var lastState: MotionState? = null
    private var lastAt = 0L

    fun tick() = log(classifier.features())

    /** Battery wave A2: on a state change or every [MIN_GAP_MS] only, unless [verbose] (the marker file) keeps one line a second. */
    fun log(f: MotionClassifier.Features) {
        val now = clock()
        if (!verbose && lastState != null && f.state == lastState && now - lastAt < MIN_GAP_MS) return
        lastState = f.state; lastAt = now
        sink(String.format(Locale.US, "features gyroMax=%.2f accVar=%.2f stepHz=%.2f state=%s", f.gyroMax, f.accVariance, f.stepHz, f.state))
    }

    companion object { const val MIN_GAP_MS = 30_000L }
}
