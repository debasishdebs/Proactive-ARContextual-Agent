package com.rokid.context.glasses.capture.motion.source

import com.rokid.context.core.model.MotionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MotionFusionTest {
    private fun g(s: MotionState, at: Long = 10_000, c: Float = 1f) = MotionEvidence(at, s, c, "glasses_imu")
    private fun o(s: MotionState, c: Float, at: Long = 10_000) = MotionEvidence(at, s, c, "phone_motion")
    private val now = 10_000L

    @Test fun glassesOnlyIsIdentity() {
        for (s in listOf(MotionState.STILL, MotionState.HEAD_MOVING, MotionState.WALKING)) {
            assertEquals(g(s), MotionFusion.fuse(listOf(g(s)), now))
        }
    }

    @Test fun staleOtherIsIgnored() {
        val r = MotionFusion.fuse(listOf(g(MotionState.STILL), o(MotionState.WALKING, 0.9f, at = now - 3_001)), now)
        assertEquals(MotionState.STILL, r?.state)
    }

    @Test fun glassesStillPlusOtherWalkingStrongLiftsToWalking() {
        val r = MotionFusion.fuse(listOf(g(MotionState.STILL), o(MotionState.WALKING, 0.8f)), now)
        assertEquals(MotionState.WALKING, r?.state)
        assertEquals("phone_motion", r?.sourceId)
    }

    @Test fun weakOtherWalkingDoesNotLift() {
        val r = MotionFusion.fuse(listOf(g(MotionState.STILL), o(MotionState.WALKING, 0.6f)), now)
        assertEquals(MotionState.STILL, r?.state)
    }

    @Test fun glassesHeadMovingIsAuthoritative() {
        val r = MotionFusion.fuse(listOf(g(MotionState.HEAD_MOVING), o(MotionState.WALKING, 0.9f)), now)
        assertEquals(MotionState.HEAD_MOVING, r?.state)
    }

    @Test fun lowConfidenceDropped() {
        val r = MotionFusion.fuse(listOf(g(MotionState.STILL), o(MotionState.WALKING, 0.4f)), now)
        assertEquals(MotionState.STILL, r?.state)
        assertNull(MotionFusion.fuse(listOf(o(MotionState.WALKING, 0.4f)), now))
    }

    @Test fun emptyIsNull() { assertNull(MotionFusion.fuse(emptyList(), now)) }

    @Test fun noGlassesPicksHighestConfidenceOther() {
        val r = MotionFusion.fuse(listOf(o(MotionState.STILL, 0.6f), MotionEvidence(now, MotionState.WALKING, 0.9f, "watch_livefit")), now)
        assertEquals("watch_livefit", r?.sourceId)
    }
}
