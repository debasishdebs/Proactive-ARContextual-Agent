package com.rokid.context.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** v2.0 T7: `pc_settings.hudHost` is additive and optional. PROTOCOL_VERSION stays 1, an old glasses build ignores it, an old phone never sends it (glasses default PCA). */
class HudHostWireTest {
    /** What a glasses build from before hudHost reads: it names only fields it knows, and the wire's ignoreUnknownKeys drops the rest. */
    @Serializable private data class OldGlassesSettings(val v: Int, val verbosity: Verbosity = Verbosity.MEDIUM, val zoneEnforce: Boolean = false, val popupSeconds: Int = POPUP_DEFAULT_SEC)

    @Test fun defaultIsPcaAndTheVersionIsStillOne() {
        assertEquals(HudHost.PCA, HudSettings().hudHost)
        assertEquals(1, PROTOCOL_VERSION)
    }

    @Test fun theNewFieldRoundTrips() {
        val s = HudSettings(hudHost = HudHost.NEXUS)
        assertEquals(s, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(Wire.encode(s))).value)
        assertEquals("NEXUS", Wire.json.parseToJsonElement(Wire.encode(s)).jsonObject["hudHost"]?.let { (it as JsonPrimitive).content })
    }

    @Test fun oldGlassesDecodeTheNewPcSettings() {
        val s = HudSettings(hudHost = HudHost.NEXUS, verbosity = Verbosity.MAX, zoneEnforce = true, popupSeconds = 9)
        val old = assertIs<Decoded.Ok<OldGlassesSettings>>(Wire.decode<OldGlassesSettings>(Wire.encode(s))).value
        assertEquals(OldGlassesSettings(1, Verbosity.MAX, true, 9), old)
    }

    @Test fun anOldPhoneSendsNoHudHostAndNewGlassesDefaultToPca() {
        val withField = Wire.json.parseToJsonElement(Wire.encode(HudSettings(hudHost = HudHost.NEXUS))).jsonObject
        val oldPhone = JsonObject(withField.filterKeys { it != "hudHost" }).toString()
        assertFalse("hudHost" in oldPhone)
        assertEquals(HudHost.PCA, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(oldPhone)).value.hudHost)
        assertEquals(HudHost.PCA, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("""{"v":1}""")).value.hudHost)
    }

    @Test fun anUnknownHostFromAFutureBuildIsPca() {
        assertEquals(HudHost.PCA, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("""{"v":1,"hudHost":"SOMETHING_NEW"}""")).value.hudHost)
    }

    @Test fun normalizedKeepsTheHost() {
        assertEquals(HudHost.NEXUS, HudSettings(hudHost = HudHost.NEXUS).normalized().hudHost)
    }
}
