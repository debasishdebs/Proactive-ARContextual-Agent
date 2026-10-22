package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The heartbeat's `wake` is the session's REAL wake: the composed profile after WorkRest's forRest() (SETTLE while resting), not a helper's guess. */
class EffectiveWakeSessionTest {
    @Test fun resting_runsSettleEvenWhenTheProfileIsMotion() = runTest {
        val r = Rig(this, HudSettings(captureMode = CaptureMode.SMART, workRestEnabled = true), work = 1, rest = 30).apply { profile = CaptureProfile.of(CameraWake.MOTION, BudgetTier.T0) }
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(30_000)
        assertEquals(CameraWake.MOTION, r.session.effectiveWake(), "working: the profile's own wake")
        delay(90_000)
        assertEquals(CameraWake.SETTLE, r.session.effectiveWake(), "resting: forRest() composes SETTLE")
        r.session.stop("test")
    }
}
