package com.rokid.context.core.model.signals

import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.SENSOR_REC_PRESETS_HZ
import com.rokid.context.core.model.Wire
import com.rokid.context.core.model.normalized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** v0.9.7 B item 3: the additive sensor-recording rate of the HUD settings (developer diagnostics; 0 = off). */
class SensorRecordingSettingTest {
    @Test fun offByDefaultAndAnOldPhoneSendsNone() {
        assertEquals(0, HudSettings().sensorRecHz)
        assertEquals(0, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("""{"v":1}""")).value.sensorRecHz)
        assertEquals(1, PROTOCOL_VERSION)
    }

    @Test fun onlyTheThreePresetsSurviveNormalizeAnythingElseIsOff() {
        for (hz in SENSOR_REC_PRESETS_HZ) assertEquals(hz, HudSettings(sensorRecHz = hz).normalized().sensorRecHz)
        assertEquals(listOf(5, 10, 25), SENSOR_REC_PRESETS_HZ)
        for (bad in listOf(-1, 1, 7, 26, 50, 100, Int.MAX_VALUE)) assertEquals(0, HudSettings(sensorRecHz = bad).normalized().sensorRecHz, "$bad")
    }

    @Test fun theWireFieldRoundTripsAndANormalDefaultIsNotEncodedDifferently() {
        val s = HudSettings(sensorRecHz = 10)
        assertEquals(s, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(Wire.encode(s))).value)
        assertEquals(HudSettings().normalized(), HudSettings(sensorRecHz = 3).normalized())
    }
}
