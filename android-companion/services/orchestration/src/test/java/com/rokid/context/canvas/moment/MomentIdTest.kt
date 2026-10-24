package com.rokid.context.canvas.moment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentIdTest {
    @Test fun formatsEightLowercaseHexDigitsZeroPadded() {
        assertEquals("00000000", MomentId.format(0))
        assertEquals("0000002a", MomentId.format(42))
        assertEquals("ffffffff", MomentId.format(MomentId.MAX_SECOND))
    }

    @Test fun ofMsFloorsToTheSecond() {
        assertEquals("0000000a", MomentId.ofMs(10_999))
        assertEquals("0000000b", MomentId.ofMs(11_000))
        assertEquals(MomentId.format(1_761_000_000L), MomentId.ofMs(1_761_000_000_123L))
    }

    @Test fun parseRoundTripsAndStartMsIsTheSecondStart() {
        assertEquals(1_761_000_000L, MomentId.parse(MomentId.format(1_761_000_000L)))
        assertEquals(1_761_000_000_000L, MomentId.startMs(MomentId.format(1_761_000_000L)))
    }

    @Test fun parseRejectsAnythingButEightLowercaseHexDigits() {
        for (bad in listOf("", "1", "0000002A", "0000002", "000000002", "0000002g", " 0000002a", "0x00002a", "moment:0000002a", "0000002a\n")) assertNull(bad, MomentId.parse(bad))
    }

    @Test fun boundsAreEnforcedBothWays() {
        fun refused(block: () -> Unit) = try { block(); false } catch (e: IllegalArgumentException) { true }
        assertTrue("before the epoch has no Moment", refused { MomentId.ofMs(-1) })
        assertTrue("past 2106 has no Moment", refused { MomentId.ofMs((MomentId.MAX_SECOND + 1) * 1000) })
        assertTrue(refused { MomentId.format(-1) })
        assertTrue(refused { MomentId.format(MomentId.MAX_SECOND + 1) })
        assertEquals("ffffffff", MomentId.ofMs(MomentId.MAX_SECOND * 1000 + 999))
    }

    @Test fun lexicographicOrderIsChronologicalOrder() {
        val ids = listOf(9L, 10L, 255L, 256L, 70_000L).map { MomentId.format(it) }
        assertEquals(ids, ids.sorted())
    }
}
