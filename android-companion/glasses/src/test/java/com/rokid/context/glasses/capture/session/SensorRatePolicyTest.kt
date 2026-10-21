package com.rokid.context.glasses.capture.session

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
}
