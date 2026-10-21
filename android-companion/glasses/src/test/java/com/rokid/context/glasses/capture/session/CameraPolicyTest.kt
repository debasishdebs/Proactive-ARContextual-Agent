package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.MotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPolicyTest {
    private fun d(
        state: MotionState = MotionState.STILL, decel: Boolean = false, sinceEnd: Long? = null, sinceKf: Long = 0,
        look: Int = 1000, low: Boolean = false, heat: Boolean = false, now: Long = 100_000, lastBrief: Long = 100_000,
        profile: CaptureProfile = CaptureProfile.DEFAULT,
    ) = CameraPolicy.decide(state, decel, sinceEnd, sinceKf, look, low, heat, now, lastBrief, profile = profile)

    private val settleT0 = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0)
    private val settleT2 = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T2)

    @Test fun motionWithDefaultProfileEqualsToday() {
        val r = d(sinceEnd = 4000)
        assertFalse(r.open); assertEquals(105_000L, r.nextBriefCheckAtMs); assertEquals(333L, r.analysisIntervalMs)
        assertEquals(115_000L, d(sinceKf = 10 * 60_000L + 1).nextBriefCheckAtMs)
        assertTrue(d(state = MotionState.WALKING).open)
    }

    @Test fun settleMovingClosesWithNoCheck() {
        val r = d(state = MotionState.HEAD_MOVING, profile = settleT0)
        assertFalse(r.open); assertNull(r.nextBriefCheckAtMs); assertEquals(333L, r.analysisIntervalMs)
    }

    @Test fun settleWalkingChecksEvery10s() {
        val r = d(state = MotionState.WALKING, profile = settleT0)
        assertFalse(r.open); assertEquals(110_000L, r.nextBriefCheckAtMs)
    }

    @Test fun settleSettledChecksEvery10sQuietEvery30s() {
        assertEquals(110_000L, d(profile = settleT0, sinceEnd = 500).nextBriefCheckAtMs)
        assertEquals(130_000L, d(profile = settleT0, sinceKf = 10 * 60_000L + 1).nextBriefCheckAtMs)
        val r = d(profile = settleT0, sinceEnd = 500)
        assertFalse("no hold and no motion-end check", r.open)
    }

    @Test fun settleIgnoresDeceleration() {
        val r = d(decel = true, state = MotionState.HEAD_MOVING, profile = settleT0)
        assertFalse(r.open); assertNull(r.nextBriefCheckAtMs)
        assertFalse(d(decel = true, profile = settleT0).open)
    }

    @Test fun settleLowPowerTier2Is20s() {
        assertEquals(120_000L, d(low = true, profile = settleT2).nextBriefCheckAtMs)
        assertEquals(110_000L, d(low = true, profile = settleT0).nextBriefCheckAtMs)
        assertEquals(666L, d(profile = settleT2).analysisIntervalMs)
    }

    @Test fun settleHeatSlowDoubles() {
        assertEquals(120_000L, d(heat = true, profile = settleT0).nextBriefCheckAtMs)
        assertEquals(120_000L, d(heat = true, state = MotionState.WALKING, profile = settleT0).nextBriefCheckAtMs)
        assertEquals(666L, d(heat = true, profile = settleT0).analysisIntervalMs)
    }

    @Test fun openWhileMoving() {
        for (s in listOf(MotionState.HEAD_MOVING, MotionState.WALKING)) {
            val r = d(state = s)
            assertTrue(r.open); assertEquals(333L, r.analysisIntervalMs); assertNull(r.nextBriefCheckAtMs)
        }
        assertEquals(666L, d(state = MotionState.WALKING, heat = true).analysisIntervalMs)
    }

    @Test fun holdPerQuickLook() {
        for ((look, h) in listOf(1000 to 4000L, 1500 to 2000L, 2000 to 1000L, 3000 to 0L)) {
            assertEquals(h, CameraPolicy.holdMs(look, false))
            if (h > 0) assertTrue("look $look just inside", d(look = look, sinceEnd = h - 1).open)
            assertFalse("look $look at H", d(look = look, sinceEnd = h).open)
        }
    }

    @Test fun opensEarlyOnDeceleration() {
        assertTrue(d(decel = true).open)
        assertTrue(d(decel = true, state = MotionState.WALKING, look = 3000).open)
    }

    @Test fun closedWhenSettled() {
        val r = d(sinceEnd = 4000)
        assertFalse(r.open); assertEquals(105_000L, r.nextBriefCheckAtMs)
        assertFalse(d(sinceEnd = null).open)
    }

    @Test fun briefCheckEvery5sThen15s() {
        assertEquals(105_000L, d(sinceKf = 60_000).nextBriefCheckAtMs)
        assertEquals(115_000L, d(sinceKf = 10 * 60_000L + 1).nextBriefCheckAtMs)
        assertEquals(110_000L, d(heat = true).nextBriefCheckAtMs)
        assertEquals(110_000L, d(low = true).nextBriefCheckAtMs)
    }

    @Test fun immediateCheckAtMotionEndPlusHWhenShorterThanLook() {
        // look 2 s, H 1 s: motion ended 1.5 s ago -> check was due at end+H = now-500
        val r = d(look = 2000, sinceEnd = 1500, now = 100_000, lastBrief = 99_000)
        assertEquals(99_500L, r.nextBriefCheckAtMs)
        // already checked since: back to the regular cadence
        assertEquals(104_800L, d(look = 2000, sinceEnd = 1500, lastBrief = 99_800).nextBriefCheckAtMs)
        // H >= look: no extra check
        assertEquals(105_000L, d(look = 1000, sinceEnd = 4500, lastBrief = 100_000).nextBriefCheckAtMs)
    }

    @Test fun burstModeAt3s() {
        val r = d(state = MotionState.WALKING, look = 3000, lastBrief = 98_000)
        assertFalse(r.open); assertEquals(100_000L, r.nextBriefCheckAtMs)
    }

    @Test fun lowPowerHalvesHold() {
        assertEquals(2000L, CameraPolicy.holdMs(1000, true))
        assertTrue(d(low = true, sinceEnd = 1999).open)
        assertFalse(d(low = true, sinceEnd = 2000).open)
    }
}
