package com.rokid.context.glasses.capture.motion

import java.util.Locale

/**
 * Fix wave K3: once per second, one count-only line of the IMU features the walking rule looks at (gyro magnitude peak
 * of the last second, linear-acceleration variance, dominant step-frequency estimate, classifier state), so the next
 * run can tune the thresholds from `files/capture_diag.log`. Numbers only, never sensor traces.
 */
class FeatureLogger(private val classifier: MotionClassifier, private val sink: (String) -> Unit) {
    fun tick() = log(classifier.features())

    fun log(f: MotionClassifier.Features) {
        sink(String.format(Locale.US, "features gyroMax=%.2f accVar=%.2f stepHz=%.2f state=%s", f.gyroMax, f.accVariance, f.stepHz, f.state))
    }
}
