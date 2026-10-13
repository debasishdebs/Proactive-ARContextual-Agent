package com.rokid.context.phone.ui.settings.connectors

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.actions.ActionSettingsStore
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.phone.ui.actions.CalendarAccess
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActionsSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val store = ActionSettingsStore(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t_actions_ui", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
    )

    private var openedAppSettings = 0
    private fun show(grant: Boolean, forever: Boolean = false) = compose.setContent {
        val result = when { grant -> CalendarAccess.GRANTED; forever -> CalendarAccess.DENIED_FOREVER; else -> CalendarAccess.DENIED }
        PcTheme { ActionsSettings(store, requestCalendar = { cb -> cb(result) }, back = {}, openShopping = {}, openAppSettings = { openedAppSettings++ }) }
    }

    @Test fun calendarDeniedStaysOff() {
        show(grant = false)
        compose.onNodeWithTag("enabled_CALENDAR_EVENT").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("enabled_CALENDAR_EVENT").assertIsOff()
        compose.onNodeWithTag("settings_actions").performScrollToNode(hasTestTag("calendar_denied"))
        compose.onNodeWithText("Calendar permission needed").assertIsDisplayed()
        assertFalse(store.toggle(ActionType.CALENDAR_EVENT).enabled)
    }

    @Test fun calendarDeniedForeverOffersAppSettings() {
        show(grant = false, forever = true)
        compose.onNodeWithTag("enabled_CALENDAR_EVENT").performScrollTo().performClick()
        compose.onNodeWithTag("settings_actions").performScrollToNode(hasTestTag("open_app_settings"))
        compose.onNodeWithTag("open_app_settings").performClick()
        assertEquals(1, openedAppSettings)
        assertFalse(store.toggle(ActionType.CALENDAR_EVENT).enabled)
    }

    @Test fun calendarDeniedOnceOffersNoAppSettings() {
        show(grant = false)
        compose.onNodeWithTag("enabled_CALENDAR_EVENT").performScrollTo().performClick()
        compose.onNodeWithTag("open_app_settings").assertDoesNotExist()
    }

    @Test fun calendarGrantedTurnsOn() {
        show(grant = true)
        compose.onNodeWithTag("enabled_CALENDAR_EVENT").performScrollTo().performClick()
        compose.onNodeWithTag("enabled_CALENDAR_EVENT").assertIsOn()
        assertEquals(true, store.toggle(ActionType.CALENDAR_EVENT).enabled)
    }

    @Test fun alwaysConfirmDisabledWhenTypeOff() {
        show(grant = true)
        compose.onNodeWithTag("confirm_CALENDAR_EVENT").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("confirm_REMINDER").performScrollTo().assertIsOn().performClick()
        assertFalse(store.toggle(ActionType.REMINDER).alwaysConfirm)
    }

    @Test fun mcpRowDisabledComingInV2() {
        show(grant = true)
        compose.onNodeWithTag("settings_actions").performScrollToNode(hasTestTag("mcp_servers"))
        compose.onNodeWithTag("mcp_servers").assertIsNotEnabled()
        compose.onNodeWithText("Coming in v2").assertIsDisplayed()
    }
}
