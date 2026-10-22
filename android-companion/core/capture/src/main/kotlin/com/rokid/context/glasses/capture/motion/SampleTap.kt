package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionState

/**
 * A read-only second consumer of the classifier's input samples (slice 3a: SHADOW runs a second glance detector on the same
 * stream). Called on the sensor thread, under the classifier's lock, AFTER the classifier has handled the sample: keep it cheap and
 * never call back into the classifier. A throw is swallowed by the classifier, so a tap can never change real capture.
 * [state] is the classifier's motion state after that sample.
 */
interface SampleTap {
    fun onGyro(x: Float, y: Float, z: Float, tMs: Long, state: MotionState)
    fun onAccel(x: Float, y: Float, z: Float, tMs: Long, gyroNewestMs: Long, state: MotionState)
}
