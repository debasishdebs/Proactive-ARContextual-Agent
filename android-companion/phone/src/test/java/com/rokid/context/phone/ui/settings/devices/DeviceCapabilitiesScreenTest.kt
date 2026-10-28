package com.rokid.context.phone.ui.settings.devices

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.settings.capture.DeviceCapabilityStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.8 slice D: Settings > Mic and display: four switches over the store. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class DeviceCapabilitiesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fourSwitchesShowAndAClickWritesTheStore() {
        val store = DeviceCapabilityStore()
        val vm = DeviceCapabilitiesViewModel(store)
        compose.setContent { PcTheme { DeviceCapabilitiesScreen(store.capabilities.collectAsState().value.list(), onBack = {}, onChange = vm::set) } }
        for (d in listOf("phone", "glasses")) for (c in listOf("mic", "display")) compose.onNodeWithTag("cap_${d}_$c").assertIsDisplayed()
        assertTrue(store.capabilities.value.glassesDisplay)
        compose.onNodeWithTag("cap_glasses_display").performClick(); compose.waitForIdle()
        assertFalse(store.capabilities.value.glassesDisplay); assertTrue(store.capabilities.value.phoneDisplay)
    }
}
