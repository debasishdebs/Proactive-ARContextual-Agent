package com.rokid.context.glasses.ui

import androidx.compose.ui.Alignment
import com.rokid.context.core.model.HudPosition
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun countdownIsMinutesAndSeconds() {
        assertEquals("4:12", formatCountdown(nextAtMs = 252_000, nowMs = 0))
        assertEquals("0:01", formatCountdown(nextAtMs = 1, nowMs = 0))
        assertEquals("--:--", formatCountdown(nextAtMs = null, nowMs = 0))
        assertEquals("--:--", formatCountdown(nextAtMs = 5, nowMs = 5))
    }

    /** Times are shown in the glasses' zone at display time (master Review Focus 4). */
    @Test fun clockUsesTheGivenZone() {
        val t = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()
        assertEquals("01:30", formatClock(t, ZoneId.of("Europe/London")))
        assertEquals("06:00", formatClock(t, ZoneId.of("Asia/Kolkata")))
    }

    @Test fun intervalIsShort() {
        assertEquals("5 min", formatInterval(300))
        assertEquals("45 s", formatInterval(45))
    }

    @Test fun positionsMapToAlignments() {
        assertEquals(Alignment.BottomCenter, HudPosition.BOTTOM_CENTER.alignment())
        assertEquals(Alignment.TopStart, HudPosition.TOP_LEFT.alignment())
        assertEquals(Alignment.CenterEnd, HudPosition.CENTER_RIGHT.alignment())
    }
}
