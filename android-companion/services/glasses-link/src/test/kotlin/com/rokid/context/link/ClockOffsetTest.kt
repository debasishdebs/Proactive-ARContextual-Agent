package com.rokid.context.link

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockOffsetTest {
    private val twoHours = 2 * 3_600_000L

    /** Phase 0: the glasses wall clock ran ~107–144 ms ahead of the phone. */
    @Test fun helloMeasuresGlassesAhead() {
        val o = ClockOffset.fromHello(glassesNowMs = 10_140, phoneReceivedAtMs = 10_000)
        assertEquals(140, o.glassesMinusPhoneMs)
        assertEquals(10_000, o.toPhone(10_140))
        assertEquals(10_140, o.toGlasses(10_000))
    }

    /** Review Focus 1: a glasses clock 2 h off still expires a 30-min pause at the same real instant. */
    @Test fun timedGlassesPauseExpiresAtTheSamePhoneInstant() {
        val o = ClockOffset(twoHours)
        val g0 = 1_793_000_000_000L
        val onGlasses = PauseRecord("p", PauseOrigin.GLASSES, createdAtMs = g0, untilMs = g0 + 30 * 60_000)
        val onPhone = o.recordToPhone(onGlasses)
        assertEquals(g0 - twoHours, onPhone.createdAtMs)
        assertTrue(onPhone.isActiveAt(g0 - twoHours + 30 * 60_000 - 1))
        assertFalse(onPhone.isActiveAt(g0 - twoHours + 30 * 60_000))
        assertEquals(onGlasses, o.recordToGlasses(onPhone), "round trip")
    }

    /** 2026-10-25 01:00 UTC: Europe/London falls back an hour. Epoch instants are untouched by it. */
    @Test fun dstChangeDoesNotMoveAnExpiry() {
        val start = Instant.parse("2026-10-25T00:45:00Z").toEpochMilli()
        val r = ClockOffset(0).recordToPhone(PauseRecord("p", PauseOrigin.PHONE, start, start + 30 * 60_000))
        assertEquals(Instant.parse("2026-10-25T01:15:00Z").toEpochMilli(), r.untilMs)
    }

    @Test fun anchoringKeepsTheDurationWithoutAnOffset() {
        val r = ClockOffset.anchoredAt(PauseRecord("p", PauseOrigin.GLASSES, createdAtMs = 5_000, untilMs = 65_000), nowMs = 1_000_000)
        assertEquals(1_000_000, r.createdAtMs)
        assertEquals(1_060_000, r.untilMs)
        assertNull(ClockOffset.anchoredAt(PauseRecord("q", PauseOrigin.GLASSES, 5_000, null), nowMs = 9).untilMs)
    }

    @Test fun conversionsSaturateInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, ClockOffset(-5).toPhone(Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, ClockOffset(5).toPhone(Long.MIN_VALUE))
        assertEquals(Long.MAX_VALUE, ClockOffset(5).toGlasses(Long.MAX_VALUE))
    }
}
