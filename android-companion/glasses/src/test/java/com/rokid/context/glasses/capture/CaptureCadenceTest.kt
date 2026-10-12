package com.rokid.context.glasses.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureCadenceTest {
    @Test fun intervalComesFromSettings() = assertEquals(300_000L, CaptureCadence.nextDelayMs(300, batteryPct = 80, batterySaver = false, failed = false))

    @Test fun lowBatteryOrBatterySaverDoubles() {
        assertEquals(600_000L, CaptureCadence.nextDelayMs(300, batteryPct = 20, batterySaver = false, failed = false))
        assertEquals(600_000L, CaptureCadence.nextDelayMs(300, batteryPct = 80, batterySaver = true, failed = false))
        assertEquals(300_000L, CaptureCadence.nextDelayMs(300, batteryPct = -1, batterySaver = false, failed = false), "unknown battery")
    }

    @Test fun aFailureRetriesSoon() = assertEquals(15_000L, CaptureCadence.nextDelayMs(300, batteryPct = 80, batterySaver = false, failed = true))

    @Test fun tooShortIntervalsAreRaised() = assertEquals(30_000L, CaptureCadence.nextDelayMs(5, batteryPct = 80, batterySaver = false, failed = false))

    /** Bounds the delay so the scheduler can tell a stored time from a clock jump (at most 2 x MAX_INTERVAL_MS). */
    @Test fun veryLongIntervalsAreCapped() {
        assertEquals(CaptureCadence.MAX_INTERVAL_MS, CaptureCadence.nextDelayMs(100_000, batteryPct = 80, batterySaver = false, failed = false))
        assertEquals(CaptureCadence.MAX_DELAY_MS, CaptureCadence.nextDelayMs(100_000, batteryPct = 15, batterySaver = false, failed = false))
    }
}
