package com.rokid.context.core.model.zone

import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.Wire
import com.rokid.context.core.model.normalized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Pause zones: the only wire changes are one additive optional `HudSettings` field and one new mark-photo reason token. */
class ZoneWireTest {
    @Test fun protocolVersionIsUnchanged() { assertEquals(1, PROTOCOL_VERSION) }

    @Test fun zoneApproachDefaultsToFalseAndAnOldPhonePushDecodesAsFalse() {
        assertFalse(HudSettings().zoneApproach)
        val old = Wire.encode(HudSettings()).replace(Regex(",?\"zoneApproach\":(true|false)"), "")
        assertFalse("zoneApproach" in old, old)
        val d = assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(old))
        assertFalse(d.value.zoneApproach)
    }

    @Test fun zoneApproachRoundTripsAndNewGlassesIgnoreNothingElse() {
        val s = HudSettings(zoneApproach = true)
        val d = assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(Wire.encode(s)))
        assertTrue(d.value.zoneApproach); assertEquals(s, d.value)
        assertTrue(s.normalized().zoneApproach, "normalized keeps the flag")
    }

    @Test fun anOldGlassesIgnoresTheFlagWhenItIsPresent() {
        // an old build decodes with unknown keys ignored: the field is simply extra there (Wire.json ignoreUnknownKeys)
        val d = assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("{\"v\":1,\"zoneApproach\":true,\"someFutureKey\":3}"))
        assertTrue(d.value.zoneApproach)
    }

    @Test fun thePauseZoneReasonIsAShortCleanToken() {
        assertEquals("pause_zone", MarkPhotoReason.PAUSE_ZONE); assertEquals("pause_zone", MarkPhotoReason.clean(MarkPhotoReason.PAUSE_ZONE))
    }
}
