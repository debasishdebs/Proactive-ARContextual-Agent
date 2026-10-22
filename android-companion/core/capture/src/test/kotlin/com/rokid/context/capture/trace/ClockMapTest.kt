package com.rokid.context.capture.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockMapTest {
    // start: elapsed 1000 <-> glasses wall 1_700_000_001_000 (offset 1_699_999_999_000); end: elapsed 12000 <-> wall ..._012_010 (offset +10 ms of drift)
    private val m = TraceFixtures.manifest()          // phoneOffsetMs = 250
    private val map = ClockMap.of(m)
    private val boot = "boot-42"
    private val off0 = 1_700_000_001_000 - 1000L

    @Test fun clockMapStartMidEnd() {
        assertEquals(1_700_000_001_000, map.glassesWall(1000, boot))                       // start sample
        assertEquals(1_700_000_012_010, map.glassesWall(12_000, boot))                     // end sample
        assertEquals(off0 + 6_500 + 5, map.glassesWall(6_500, boot))                       // midpoint: half the 10 ms drift
        assertEquals(1_700_000_001_000 + 250, map.phoneWall(1000, boot))                   // the phone offset is added
        assertEquals(1_700_000_012_010 + 250, map.phoneWall(12_000, boot))
        assertEquals(off0 + 6_500 + 5 + 250, map.phoneWall(6_500, boot))
    }

    @Test fun holdsTheNearestOffsetForASmallOvershootOnly() {
        assertEquals(1_700_000_001_000 - 500, map.glassesWall(500, boot))                  // 500 ms before the start: the start offset
        assertEquals(1_700_000_012_010 + 5_000, map.glassesWall(17_000, boot))             // 5 s after the end: the end offset
    }

    @Test fun refusesFarOutsideTheTrace() {
        assertNull(map.glassesWall(12_000 + ClockMap.SLACK_MS + 1, boot))
        assertEquals(1_700_000_012_010 + ClockMap.SLACK_MS, map.glassesWall(12_000 + ClockMap.SLACK_MS, boot))
        assertNull(map.phoneWall(12_000 + ClockMap.SLACK_MS + 1, boot))
        assertNull(map.glassesWall(-1, boot))
    }

    @Test fun rebootEpochIsNeverMapped() {
        assertNull(map.glassesWall(6_500, "boot-43"))
        assertNull(map.phoneWall(6_500, "boot-43"))
        // two traces on either side of a reboot: elapsed restarts, so the same elapsed value maps through the map of its own boot only
        val after = ClockMap.of(m.copy(bootId = "boot-43", startWallMs = 1_700_100_000_000, endWallMs = 1_700_100_011_000))
        assertEquals(1_700_100_000_000, after.glassesWall(1000, "boot-43"))
        assertEquals(1_700_000_001_000, map.glassesWall(1000, boot))
        assertEquals(after.glassesWall(1000, "boot-43"), ClockMap.resolve(listOf(map, after), "boot-43", 1000)?.glassesWall(1000, "boot-43"))
        assertEquals(1_700_000_001_000, ClockMap.resolve(listOf(map, after), boot, 1000)?.glassesWall(1000, boot))
        assertNull(ClockMap.resolve(listOf(map, after), "boot-44", 1000))
    }

    @Test fun noPhoneOffsetMeansNoPhoneTime() {
        val unsynced = ClockMap.of(m.copy(phoneOffsetMs = null, offsetUncertaintyMs = null))
        assertEquals(1_700_000_001_000, unsynced.glassesWall(1000, boot))
        assertNull(unsynced.phoneWall(1000, boot))
        assertNull(unsynced.uncertaintyMs)
        assertEquals(40L + 10L, map.uncertaintyMs)     // phone sync uncertainty plus the drift between the two samples
    }

    @Test fun zeroLengthTraceUsesTheStartOffset() {
        val z = ClockMap.of(m.copy(endElapsedMs = 1000, endWallMs = 1_700_000_001_000))
        assertEquals(1_700_000_001_005, z.glassesWall(1005, boot))
    }
}
