package com.rokid.context.glasses.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameGuardTest {
    private fun check(battery: Int = 80, charging: Boolean = false, luma: Double = 90.0) = FrameGuard.checkBatteryAndDark(battery, charging, luma)

    @Test fun aNormalFrameIsKept() = assertNull(check())

    @Test fun lowBatteryWithoutChargingIsSkipped() {
        assertEquals(SkipReason.LOW_BATTERY, check(battery = 10))
        assertNull(check(battery = 10, charging = true))
        assertNull(check(battery = 11))
    }

    @Test fun darkFramesAreSkipped() {
        assertEquals(SkipReason.DARK, check(luma = 7.9))
        assertNull(check(luma = 8.0))
    }

    @Test fun batteryAndDarkSkipsStandAlone() {
        assertEquals(SkipReason.LOW_BATTERY, FrameGuard.checkBatteryAndDark(10, false, 90.0))
        assertEquals(SkipReason.DARK, FrameGuard.checkBatteryAndDark(80, false, 7.9))
        assertNull(FrameGuard.checkBatteryAndDark(80, false, 8.0))
    }

    /** One rule for CaptureService's early skip (before the camera opens) and the guard; unknown battery (-1) fails open. */
    @Test fun lowBatteryRule() {
        assertTrue(FrameGuard.lowBattery(10, charging = false))
        assertFalse(FrameGuard.lowBattery(10, charging = true))
        assertFalse(FrameGuard.lowBattery(11, charging = false))
        assertFalse(FrameGuard.lowBattery(-1, charging = false))
    }
}
