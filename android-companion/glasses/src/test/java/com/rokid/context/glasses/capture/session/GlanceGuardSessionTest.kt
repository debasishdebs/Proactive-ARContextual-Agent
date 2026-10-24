package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.GlanceParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** 3c X3 in the real SamplingSession: the guard drops glance candidates only; the reserved timer photo attempt (S83/S84) still runs. */
class GlanceGuardSessionTest {
    private fun glanceRig(t: TestScope, keep: Boolean) =
        Rig(t, HudSettings(captureMode = CaptureMode.SMART), glance = GlanceParams())
            .apply { profile = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0); glanceKeep = { keep } }

    private suspend fun Rig.gyro(ms: Long, z: Float) { val end = clock() + ms; while (clock() < end) { motion.onGyro(0f, 0f, z, clock()); delay(20) } }

    private suspend fun dwellOpensAfterGlance(keep: Boolean, t: TestScope): Pair<Int, Int> {
        val r = glanceRig(t, keep)
        r.session.start()
        r.gyro(21_500, 0f); r.session.diagSnapshot()
        r.gyro(300, 1f); r.gyro(300, -1f); r.gyro(3_000, 0f)
        val d = r.session.diagSnapshot()
        r.session.stop("test")
        return d.glances to (d.wakes["dwell"] ?: 0)
    }

    @Test fun GuardDropsGlanceCandidateOnly() = runTest {
        assertEquals(1 to 1, dwellOpensAfterGlance(true, this), "a keeping guard changes nothing")
        assertEquals(1 to 0, dwellOpensAfterGlance(false, this), "a dropping guard: the glance is seen, no open")
    }

    @Test fun ReservedTimerUnaffectedByGuardTest() = runTest {
        // the S83 setup (restrictive plan, every timer gap beyond the bound) with a guard that drops every glance
        val r = Rig(this, HudSettings(captureMode = CaptureMode.SMART)).apply {
            applied = ContextState.UNKNOWN; profile = CaptureProfile(CameraWake.SETTLE, 900_000, 900_000, 900_000, 333, false, stillCapMs = 900_000); glanceKeep = { false }
        }
        r.startSensors(); r.gyroMag = 0.01f
        r.session.start()
        delay(10.minutes.inWholeMilliseconds)
        val attempts = listOf(r.createdAt) + r.camera.openTimes
        for (m in 0 until 10) {
            val n = attempts.count { it in (r.createdAt + m * 60_000L)..(r.createdAt + (m + 1) * 60_000L + 1_000L) }
            assertTrue(n >= 1, "minute $m has $n eligible attempts: $attempts")
        }
        assertTrue((r.session.diagSnapshot().wakes["resv"] ?: 0) >= 8)
        r.session.stop("test")
    }
}
