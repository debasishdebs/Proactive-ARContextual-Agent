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
}
