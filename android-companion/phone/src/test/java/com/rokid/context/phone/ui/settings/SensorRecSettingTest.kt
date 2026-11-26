package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.7 B item 3: Settings > Diagnostics > Developer: the sensor-recording switch and rates, visible only in Developer mode, with the battery warning naming the rate. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class SensorRecSettingTest {
    @get:Rule val compose = createComposeRule()
    private fun show(dev: Boolean, hz: Int = 0, on: (Int) -> Unit = {}) = compose.setContent {
        PcTheme { DiagnosticsScreen(DiagnosticsUiState(), onBack = {}, onOpenQueues = {}, onOpenTracker = {}, onSummarizeNow = {}, onCaptureNow = {}, onReprocess = {}, onOpenLogs = {}, onExport = {}, onPushSettings = {}, developerMode = dev, sensorRecHz = hz, onSensorRecHz = on) }
    }

    @Test fun hiddenWhileDeveloperModeIsOff() {
        show(dev = false, hz = 10)
        compose.onNodeWithTag("sensor_rec_rate").assertDoesNotExist(); compose.onNodeWithTag("sensor_rec_warning").assertDoesNotExist()
    }

    @Test fun offByDefaultWithTheWarningOnlyWhenRecording() {
        show(dev = true)
        compose.onNodeWithTag("sensor_rec_rate").performScrollTo().assertIsDisplayed() // the Developer rows above it (captions, Maybe tier, import) push it below the fold
        compose.onNodeWithTag("sensor_rec_warning").assertDoesNotExist()
    }

    @Test fun theWarningNamesTheRateAndTheBatteryCost() {
        show(dev = true, hz = 25)
        compose.onNodeWithTag("sensor_rec_warning").performScrollTo().assertIsDisplayed()
        val t = SensorRecCopy.warning(25)
        assertTrue(t, t.contains("25 Hz at most") && t.contains("never faster than the glasses already sample") && t.contains("batches") && t.contains("more battery"))
        assertEquals("records sensor data at 10 Hz at most (never faster than the glasses already sample, and nothing while they save battery, rest or are paused) and saves it in batches on the glasses (not sent to the phone yet; a developer pull reads it). Uses more battery.", SensorRecCopy.warning(10))
    }

    @Test fun choosingARateReportsAPreset() {
        var got = -1
        show(dev = true, on = { got = it })
        compose.onNodeWithTag("sensor_rec_rate").performScrollTo().performClick()
        compose.onNodeWithTag("sensor_rec_rate_2").performScrollTo().performClick()
        assertEquals(10, got)
    }

}
