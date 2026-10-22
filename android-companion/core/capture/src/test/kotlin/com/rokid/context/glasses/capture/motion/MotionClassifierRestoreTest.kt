package com.rokid.context.glasses.capture.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The replay seam: a classifier can be started from the pose and the previous gyro time a trace snapshot recorded (Task 20). */
class MotionClassifierRestoreTest {
    @Test fun restoredPoseAndPreviousGyroTimeDriveTheNextSample() {
        val c = MotionClassifier({ 0L })
        c.restoreForReplay(prevGyroMs = 1000, rx = 0.3f, ry = 0f, rz = 0.4f, walked = true, qx = 0f, qy = 0f, qz = 0f, quietSinceMs = 900)
        assertEquals(0.5f, c.rotationSinceMarkRad(), 1e-6f)
        assertTrue(c.walkedSinceMark())
        c.onGyro(0f, 0f, 1.0f, 1040)                       // dt = 40 ms from the restored previous time: +0.04 rad about z
        assertEquals(Math.sqrt(0.3 * 0.3 + 0.44 * 0.44).toFloat(), c.rotationSinceMarkRad(), 1e-5f)
    }

    @Test fun withoutRestoreTheFirstSampleHasNoDt() {
        val c = MotionClassifier({ 0L })
        c.onGyro(0f, 0f, 1.0f, 1040)
        assertEquals(0f, c.rotationSinceMarkRad(), 1e-9f)
        assertFalse(c.walkedSinceMark())
    }

    @Test fun nullPreviousGyroTimeKeepsTheClassifierUnprimed() {
        val c = MotionClassifier({ 0L })
        c.restoreForReplay(prevGyroMs = null, rx = 0f, ry = 0f, rz = 0f, walked = false, qx = 0f, qy = 0f, qz = 0f, quietSinceMs = null)
        c.onGyro(0f, 0f, 1.0f, 1040)
        assertEquals(0f, c.rotationSinceMarkRad(), 1e-9f)
    }
}
