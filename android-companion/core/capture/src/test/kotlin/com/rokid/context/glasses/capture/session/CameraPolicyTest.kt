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
        profile: CaptureProfile = CaptureProfile.DEFAULT, quiet: Boolean = false, step: Int = 0,
    ) = CameraPolicy.decide(state, decel, sinceEnd, sinceKf, look, low, heat, now, lastBrief, quiet = quiet, profile = profile, stillStep = step)

    private val settleT0 = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T0)
    private val settleT2 = CaptureProfile.of(CameraWake.SETTLE, BudgetTier.T2)

    @Test fun motionWithDefaultProfileEqualsToday() {
        val r = d(sinceEnd = 4000)
        assertFalse(r.open); assertEquals(105_000L, r.nextBriefCheckAtMs); assertEquals(333L, r.analysisIntervalMs)
        assertEquals(115_000L, d(sinceKf = 10 * 60_000L + 1).nextBriefCheckAtMs)
        assertTrue(d(state = MotionState.WALKING).open)
    }

    @Test fun stillGapDoublesToTheCap() {
        val exp = listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L)
        for (i in 0..5) assertEquals(exp[i], CameraPolicy.stillGap(5_000, i, 60_000))
        assertEquals(60_000L, CameraPolicy.stillGap(20_000, 2, 60_000))
        assertEquals(90_000L, CameraPolicy.stillGap(90_000, 3, 60_000))
    }

    @Test fun motionSettledBacksOff() {
        assertEquals(105_000L, d(lastBrief = 100_000, step = 0).nextBriefCheckAtMs)
        assertEquals(140_000L, d(lastBrief = 100_000, step = 3).nextBriefCheckAtMs)
        assertEquals(160_000L, d(lastBrief = 100_000, step = 5).nextBriefCheckAtMs)
        assertEquals(120_000L, d(lastBrief = 100_000, low = true, step = 1).nextBriefCheckAtMs)
        assertEquals(115_000L, d(lastBrief = 100_000, quiet = true, step = 0).nextBriefCheckAtMs)
        assertEquals(120_000L, d(lastBrief = 100_000, quiet = true, step = 2).nextBriefCheckAtMs)
        assertEquals(220_000L, d(lastBrief = 100_000, heat = true, step = 4).nextBriefCheckAtMs)
    }

    @Test fun settleSettledBacksOff() {
        val exp = listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L)
        for (i in 0..4) assertEquals(100_000L + exp[i], d(lastBrief = 100_000, profile = settleT0, step = i).nextBriefCheckAtMs)
        assertEquals(140_000L, d(lastBrief = 100_000, profile = settleT2, step = 1).nextBriefCheckAtMs)
    }

    @Test fun settleWalkingAndHeadMovingIgnoreTheBackoff() {
        assertEquals(110_000L, d(lastBrief = 100_000, state = MotionState.WALKING, profile = settleT0, step = 3).nextBriefCheckAtMs)
        assertNull(d(state = MotionState.HEAD_MOVING, profile = settleT0, step = 3).nextBriefCheckAtMs)
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

    @Test fun forRestSlowsTheTimerChecksAndKeepsGlanceCapture() {
        val r = CaptureProfile.DEFAULT.forRest()
        assertEquals(CameraWake.SETTLE, r.wake)
        assertEquals(30_000L, r.settledCheckMs); assertEquals(60_000L, r.quietCheckMs)
        assertEquals(30_000L, r.walkCheckMs); assertEquals(120_000L, r.stillCapMs)
        val t2 = settleT2.forRest()
        assertEquals(30_000L, t2.settledCheckMs); assertEquals(60_000L, t2.quietCheckMs)
        assertEquals(30_000L, t2.walkCheckMs); assertEquals(120_000L, t2.stillCapMs)
    }

    @Test fun withContextUnknownRowIsIdentityAndWalkingToo() {
        val p = com.rokid.context.core.model.context.ContextPolicies
        assertEquals(CaptureProfile.DEFAULT, CaptureProfile.DEFAULT.withContext(p.of(com.rokid.context.core.model.context.ContextState.UNKNOWN)))
        assertEquals(CaptureProfile.DEFAULT, CaptureProfile.DEFAULT.withContext(p.of(com.rokid.context.core.model.context.ContextState.WALKING)))
    }

    @Test fun withContextVehicleRowsDoNotReduceCapture() {
        for (st in listOf(com.rokid.context.core.model.context.ContextState.VEHICLE_PASSENGER, com.rokid.context.core.model.context.ContextState.VEHICLE_DRIVING)) {
            val pol = com.rokid.context.core.model.context.ContextPolicies.of(st)
            assertEquals(st.name, CaptureProfile.DEFAULT, CaptureProfile.DEFAULT.withContext(pol))
            assertTrue(st.name, pol.capPerMin == null)
        }
    }

    @Test fun withContextRestKeepsTheLargerT2Gaps() {
        val pol = com.rokid.context.core.model.context.ContextPolicies.of(com.rokid.context.core.model.context.ContextState.REST)
        val big = settleT2.copy(settledCheckMs = 90_000, quietCheckMs = 200_000, walkCheckMs = 95_000, stillCapMs = 400_000)
        assertEquals(big, big.withContext(pol))
        val r = settleT2.forRest().withContext(pol)
        assertEquals(settleT2.forRest(), r)
        val small = settleT2.copy(settledCheckMs = 5_000, quietCheckMs = 5_000, walkCheckMs = 5_000, stillCapMs = 5_000)
        assertEquals(30_000L, small.withContext(pol).settledCheckMs); assertEquals(120_000L, small.withContext(pol).stillCapMs)
    }
}
