package com.rokid.context.glasses.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HudSettingsCacheTest {
    private fun cache(name: String) = HudSettingsCache(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE))

    @Test fun neverPushedMeansFixedWithDefaults() {
        val c = cache("hud-never").capture()
        assertEquals(CaptureMode.FIXED, c.captureMode)
        assertEquals(1000, c.quickLookMs); assertEquals(45, c.workMinutes); assertEquals(15, c.restMinutes); assertTrue(c.pauseWhenNotWorn)
    }

    @Test fun phonePushExposesTheCaptureSettingsAfterARestart() {
        cache("hud-push").write(HudSettings(captureMode = CaptureMode.SMART, quickLookMs = 2000, workMinutes = 60, restMinutes = 20, pauseWhenNotWorn = false, captureIntervalSec = 120))
        val c = cache("hud-push").capture() // a new instance: read back from the prefs
        assertEquals(CaptureMode.SMART, c.captureMode); assertEquals(2000, c.quickLookMs); assertEquals(60, c.workMinutes)
        assertEquals(20, c.restMinutes); assertFalse(c.pauseWhenNotWorn); assertEquals(120, c.captureIntervalSec)
    }

    @Test fun wildValuesAreSnappedAndRestIsClamped() {
        val c = cache("hud-wild")
        c.write(HudSettings(captureMode = CaptureMode.SMART, quickLookMs = 1234, workMinutes = 75, restMinutes = 5))
        val s = c.capture()
        assertEquals(1000, s.quickLookMs); assertEquals(75, s.workMinutes); assertEquals(15, s.restMinutes) // W > 45 means R >= 15
    }

    @Test fun theSameInstanceSeesAWriteAtOnce() {
        val c = cache("hud-live")
        assertEquals(CaptureMode.FIXED, c.capture().captureMode)
        c.write(HudSettings(captureMode = CaptureMode.SMART))
        assertEquals(CaptureMode.SMART, c.capture().captureMode)
    }

    @Test fun writeStoresNormalizedValues() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("hud-norm", Context.MODE_PRIVATE)
        HudSettingsCache(prefs).write(HudSettings(quickLookMs = 1234, workMinutes = 75, restMinutes = 5))
        val raw = prefs.getString("hudSettings", "")!!
        assertTrue("\"quickLookMs\":1000" in raw && "\"restMinutes\":15" in raw, raw)
        assertEquals(1000, HudSettingsCache(prefs).read().quickLookMs)
    }
}
