package com.rokid.context.ai.canvas

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CronSpecTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun spec(s: String) = CronSpec.parse(s)!!

    @Test fun nextOccurrenceIsStrictlyAfter() {
        val daily = spec("0 9 * * *")
        assertEquals(ms(2026, 10, 28, 9, 0), daily.nextAfter(ms(2026, 10, 28, 8, 59), zone))
        assertEquals(ms(2026, 10, 29, 9, 0), daily.nextAfter(ms(2026, 10, 28, 9, 0), zone))
    }

    @Test fun listsRangesStepsAndWeekdays() {
        // 2026-10-28 is a Wednesday; weekdays only, 08:30 and 17:30
        val s = spec("30 8,17 * * 1-5")
        assertEquals(ms(2026, 10, 28, 17, 30), s.nextAfter(ms(2026, 10, 28, 9, 0), zone))
        assertEquals(ms(2026, 10, 30, 8, 30), s.nextAfter(ms(2026, 10, 29, 17, 30), zone)) // Thursday 17:30 -> Friday
        assertEquals(ms(2026, 11, 2, 8, 30), s.nextAfter(ms(2026, 10, 30, 17, 30), zone)) // Friday -> Monday
        assertEquals(ms(2026, 10, 28, 10, 15), spec("15,45 */5 * * *").nextAfter(ms(2026, 10, 28, 10, 0), zone))
        assertEquals(spec("0 9 * * 0").nextAfter(1L, zone), spec("0 9 * * 7").nextAfter(1L, zone)) // 0 and 7 are both Sunday
    }

    @Test fun theFloorIsFifteenMinutesAcrossMidnightToo() {
        assertNotNull(CronSpec.parse("*/15 * * * *"))
        assertNull(CronSpec.parse("*/14 * * * *"))
        assertNull(CronSpec.parse("0,5 0 * * *"))
        assertNotNull(CronSpec.parse("0 23,0 * * *")) // 23:00 and 00:00 are an hour apart
        assertNotNull(CronSpec.parse("55 23 * * *")) // one a day
        assertNull(CronSpec.parse("55,59 23 * * *")) // 4 minutes apart
    }

    @Test fun badExpressionsAreRefused() {
        for (bad in listOf("", "* * * *", "* * * * * *", "60 * * * *", "0 24 * * *", "0 9 0 * *", "0 9 * 13 *", "0 9 * * 8", "a b c d e", "5-2 9 * * *", "0 9 */0 * *", "0 9 * * * " + "x".repeat(70)))
            assertNull(bad, CronSpec.parse(bad))
    }

    @Test fun anImpossibleDateParsesButNeverComes() {
        assertNull(spec("0 9 31 2 *").nextAfter(ms(2026, 10, 28, 9, 0), zone))
    }
}
