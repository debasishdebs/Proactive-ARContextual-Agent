package com.rokid.context.glasses.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameGuardTest {
    private fun check(
        battery: Int = 80, charging: Boolean = false, luma: Double = 90.0, hash: Long = 0L, last: Long? = null,
        now: Long = 10_000, lastKept: Long = 0, skipDedupe: Boolean = false,
    ) = FrameGuard.check(battery, charging, luma, hash, last, now, lastKept, skipDedupe)

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

    @Test fun nearDuplicatesAreSkipped() {
        assertEquals(SkipReason.DUPLICATE, check(hash = 0b1111L, last = 0L)) // 4 bits apart
        assertNull(check(hash = 0b11111L, last = 0L)) // 5 bits apart
    }

    @Test fun oneKeptFramePer2500Ms() {
        assertEquals(SkipReason.THROTTLED, check(now = 12_499, lastKept = 10_000))
        assertNull(check(now = 12_500, lastKept = 10_000))
    }

    /** Debug adb checks may bypass dedupe and throttle, never the battery or dark-frame guards. */
    @Test fun skipDedupeBypassesOnlyDedupeAndThrottle() {
        assertNull(check(hash = 1, last = 1, now = 1, lastKept = 0, skipDedupe = true))
        assertEquals(SkipReason.DARK, check(luma = 1.0, skipDedupe = true))
        assertEquals(SkipReason.LOW_BATTERY, check(battery = 5, skipDedupe = true))
    }
}
