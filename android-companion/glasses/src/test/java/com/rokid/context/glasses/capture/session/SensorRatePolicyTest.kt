package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.motion.SensorRate
import kotlin.test.Test
import kotlin.test.assertEquals

class SensorRatePolicyTest {
    private val p = SensorRatePolicy()

    @Test fun lowOnlyAfterTenSecondsInABlockingReason() {
        assertEquals(SensorRate.FULL, p.update("not_worn", 0))
        assertEquals(SensorRate.FULL, p.update("not_worn", 9_999))
        assertEquals(SensorRate.LOW, p.update("not_worn", 10_000))
    }

    @Test fun nullAndOtherReasonsResetToFull() {
        p.update("rest", 0)
        assertEquals(SensorRate.LOW, p.update("rest", 10_000))
        assertEquals(SensorRate.FULL, p.update(null, 10_001))
        assertEquals(SensorRate.FULL, p.update("rest", 10_002))
        assertEquals(SensorRate.FULL, p.update("rest", 20_001))
        assertEquals(SensorRate.LOW, p.update("rest", 20_002))
        assertEquals(SensorRate.FULL, p.update("mode", 20_003))
    }

    @Test fun changeWithinTheSetKeepsTheTimer() {
        p.update("not_worn", 0)
        assertEquals(SensorRate.LOW, p.update("parked", 10_000))
    }

    @Test fun allBlockingReasonsCount() {
        for (r in listOf("not_worn", "parked", "paused", "rest", "battery_budget", "battery", "phone_in_use", "heat")) {
            val q = SensorRatePolicy(); q.update(r, 0)
            assertEquals(SensorRate.LOW, q.update(r, 10_000), r)
        }
    }

    @Test fun runningAndStillGoesStillAfterTenSeconds() {
        assertEquals(SensorRate.FULL, p.update(null, 0, MotionState.STILL))
        assertEquals(SensorRate.FULL, p.update(null, 9_999, MotionState.STILL))
        assertEquals(SensorRate.STILL, p.update(null, 10_000, MotionState.STILL))
    }

    @Test fun headMovingGoesFullAtOnceAndRestartsTheTimer() {
        p.update(null, 0, MotionState.STILL)
        assertEquals(SensorRate.STILL, p.update(null, 10_000, MotionState.STILL))
        assertEquals(SensorRate.FULL, p.update(null, 10_001, MotionState.HEAD_MOVING))
        assertEquals(SensorRate.FULL, p.update(null, 10_002, MotionState.STILL))
        assertEquals(SensorRate.FULL, p.update(null, 20_001, MotionState.STILL))
        assertEquals(SensorRate.STILL, p.update(null, 20_002, MotionState.STILL))
    }

    @Test fun aNullStateIsAlwaysFull() {
        assertEquals(SensorRate.FULL, p.update(null, 0))
        assertEquals(SensorRate.FULL, p.update(null, 50_000))
        assertEquals(SensorRate.FULL, p.update(null, 100_000, null))
    }

    @Test fun notWornForTenSecondsIsLowWhateverTheState() {
        for (s in listOf(null, MotionState.STILL, MotionState.WALKING)) {
            val q = SensorRatePolicy()
            q.update("not_worn", 0, s)
            assertEquals(SensorRate.LOW, q.update("not_worn", 10_000, s), "state $s")
        }
    }

    @Test fun unblockedToStillIsFullUntilTenMoreSeconds() {
        p.update("not_worn", 0, MotionState.STILL)
        assertEquals(SensorRate.LOW, p.update("not_worn", 10_000, MotionState.STILL))
        assertEquals(SensorRate.FULL, p.update(null, 10_001, MotionState.STILL))
        assertEquals(SensorRate.FULL, p.update(null, 20_000, MotionState.STILL))
        assertEquals(SensorRate.STILL, p.update(null, 20_001, MotionState.STILL))
    }
}
