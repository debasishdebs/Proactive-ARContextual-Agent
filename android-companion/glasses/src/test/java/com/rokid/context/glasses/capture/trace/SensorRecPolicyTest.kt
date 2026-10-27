package com.rokid.context.glasses.capture.trace

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** v0.9.7 B item 3: recording honours the existing pause / park / heat / battery policies. Each blocker alone stops it; there is NO exception (not even for a mark). */
class SensorRecPolicyTest {
    private fun ok(hz: Int = 10, session: Boolean = true, captureAllowed: Boolean = true, parked: Boolean = false, heatStop: Boolean = false, lowBattery: Boolean = false, saver: Boolean = false) =
        SensorRecPolicy.rateFor(hz, session, captureAllowed, parked, heatStop, lowBattery, saver)

    @Test fun recordsAtTheChosenRateWhenNothingBlocks() = assertEquals(10, ok())
    @Test fun offStaysOff() = assertEquals(0, ok(hz = 0))
    @Test fun noRunningSessionStops() = assertEquals(0, ok(session = false))
    @Test fun aPauseStops() = assertEquals(0, ok(captureAllowed = false))
    @Test fun parkedGlassesStop() = assertEquals(0, ok(parked = true))
    @Test fun heatStopStops() = assertEquals(0, ok(heatStop = true))
    @Test fun lowBatteryStops() = assertEquals(0, ok(lowBattery = true))
    @Test fun batterySaverStops() = assertEquals(0, ok(saver = true))
    @Test fun aNonPresetRateIsOff() { assertEquals(0, ok(hz = 7)); assertTrue(listOf(5, 10, 25).all { ok(hz = it) == it }); assertFalse(ok(hz = 50) > 0) }
}
