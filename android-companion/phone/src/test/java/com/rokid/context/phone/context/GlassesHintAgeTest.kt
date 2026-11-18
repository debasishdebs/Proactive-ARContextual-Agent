package com.rokid.context.phone.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesHintAgeTest {
    private val a = GlassesHintAge()
    private val s = 777L

    @Test fun noSessionIsUnknown() { assertNull(a.observe(0, 1_000, 5_000)); assertNull(a.observe(0, 2_000, 6_000)) }

    @Test fun theFirstHintOfASessionHasAnUnknownAgeThenAgesAreExcessDelay() {
        assertNull(a.observe(s, 1_000_000, 50_000))
        assertEquals(0L, a.observe(s, 1_060_000, 110_000))
        assertEquals("delayed 20 s", 20_000L, a.observe(s, 1_120_000, 190_000))
        assertEquals(0L, a.observe(s, 1_180_000, 230_000))
    }

    @Test fun aHintDelayedPastTwoMinutesIsUnknown() {
        a.observe(s, 1_000_000, 50_000); a.observe(s, 1_060_000, 110_000)
        assertNull(a.observe(s, 1_120_000, 110_000 + 60_000 + 121_000))
        assertEquals(119_000L, a.observe(s, 1_180_000, 230_000 + 119_000))
    }

    @Test fun aReorderedOrRepeatedHintIsUnknown() {
        a.observe(s, 1_000_000, 50_000); a.observe(s, 1_060_000, 110_000)
        assertNull(a.observe(s, 1_030_000, 111_000)); assertNull(a.observe(s, 1_060_000, 112_000))
    }

    @Test fun aHintThatLooksFromTheFutureRebaselinesAndIsUnknown() {
        a.observe(s, 1_000_000, 50_000)
        assertNull("its stamp is 40 s ahead of anything the baseline allows", a.observe(s, 1_100_000, 70_000))
        assertEquals(0L, a.observe(s, 1_160_000, 130_000))
    }

    @Test fun aNewSessionStartsOver() {
        a.observe(s, 1_000_000, 50_000); a.observe(s, 1_060_000, 110_000)
        assertNull("the glasses clock may restart", a.observe(s + 1, 10_000, 120_000))
        assertEquals(0L, a.observe(s + 1, 70_000, 180_000))
    }
}
