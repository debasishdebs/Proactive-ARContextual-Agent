package com.rokid.context.phone.canvas

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.settings.SettingsRootScreen
import com.rokid.context.phone.ui.settings.SettingsRootUiState
import com.rokid.context.phone.ui.shell.Routes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CanvasSettingsEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowHiddenWhenDevModeOff() {
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState(), live = Routes.LIVE_SETTINGS, onOpen = {}, developerMode = false) } }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Persona & language"))
        compose.onNodeWithText("Experiment canvas").assertDoesNotExist()
    }

    @Test fun rowShownWhenOn() {
        val opened = mutableListOf<String>()
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState(), live = Routes.LIVE_SETTINGS, onOpen = { opened += it }, developerMode = true) } }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Experiment canvas"))
        compose.onNodeWithText("Experiment canvas").assertIsDisplayed()
        compose.onNodeWithText("Experiment canvas").performClick()
        assertEquals(listOf(Routes.SETTINGS_CANVAS), opened)
    }
}
