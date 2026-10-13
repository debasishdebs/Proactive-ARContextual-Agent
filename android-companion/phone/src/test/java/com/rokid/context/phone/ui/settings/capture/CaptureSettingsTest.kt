package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.HudSettings
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.FakeHudSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CaptureSettingsTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun intervalIsClampedToThirtySecondsToOneHour() {
        assertEquals(30, CaptureIntervals.clamp(0)); assertEquals(30, CaptureIntervals.clamp(-5))
        assertEquals(3_600, CaptureIntervals.clamp(7_200)); assertEquals(420, CaptureIntervals.clamp(420))
        assertEquals("30 s", CaptureIntervals.label(30)); assertEquals("5 min", CaptureIntervals.label(300)); assertEquals("1 h", CaptureIntervals.label(3_600))
        assertEquals((CaptureIntervals.PRESETS_SEC + 420).sorted(), CaptureIntervals.options(420))
    }

    @Test fun anOutOfRangeStoredIntervalShowsAndSavesClamped() = runBlocking {
        val hud = FakeHudSettings(HudSettings(captureIntervalSec = 7_200))
        val vm = CaptureSettingsViewModel(hud)
        assertEquals(3_600, withTimeout(5_000) { vm.state.first() }.intervalSec)
        vm.setInterval(5); assertEquals(30, hud.settings.value.captureIntervalSec)
        vm.setInterval(600); assertEquals(600, hud.settings.value.captureIntervalSec)
        vm.setBurst(false); assertFalse(hud.settings.value.burstOnChange)
        vm.cancelScope()
    }
}
