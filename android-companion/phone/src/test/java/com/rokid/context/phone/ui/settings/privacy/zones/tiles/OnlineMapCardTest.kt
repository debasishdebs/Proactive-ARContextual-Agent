package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.privacy.zones.FakeZoneEditor
import com.rokid.context.phone.ui.settings.privacy.zones.OnlineMapPanel
import com.rokid.context.phone.ui.settings.privacy.zones.ZonesCallbacks
import com.rokid.context.phone.ui.settings.privacy.zones.ZonesScreen
import com.rokid.context.phone.ui.settings.privacy.zones.ZonesViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class OnlineMapCardTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val vms = mutableListOf<androidx.lifecycle.ViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }
    private val calls = mutableListOf<String>()

    private fun show(available: Boolean = true): OnlineMapModel {
        val zones = ZonesViewModel(FakeZoneEditor(), elapsed = { 1_000L }, newId = { "z" }, work = main.dispatcher).also { vms += it }
        val model = OnlineMapModel(OnlineMapStore(), available) { calls += "purge" }
        compose.setContent {
            val s by zones.state.collectAsStateWithLifecycle()
            val ui by model.ui.collectAsStateWithLifecycle()
            val panel = OnlineMapPanel(ui, null, true, null, false, null, model::requestEnabled, model::confirmFirst, model::cancelFirst, { calls += "centre" }, {})
            PcTheme { ZonesScreen(s, ZonesCallbacks.of(zones, back = {}), null, panel) }
        }
        return model
    }

    private fun scrollTo(tag: String) { compose.onNodeWithTag("zones_screen").performScrollToNode(hasTestTag(tag)) }

    @Test fun theSwitchIsOffByDefault_andCentreOnMeIsOffered() {
        show()
        scrollTo("online_switch"); compose.onNodeWithTag("online_switch").assertIsOff()
        compose.onNodeWithTag("online_first_dialog").assertDoesNotExist()
        compose.onNodeWithTag("zone_map_attribution").assertDoesNotExist()
        scrollTo("zone_centre"); compose.onNodeWithTag("zone_centre").performClick()
        assertEquals(listOf("centre"), calls)
    }

    @Test fun theFirstTurnOnShowsTheDialog_cancelLeavesTheSwitchOff() {
        val m = show()
        scrollTo("online_switch"); compose.onNodeWithTag("online_switch").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("online_first_dialog").assertExists()
        compose.onNodeWithTag("online_first_cancel").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("online_first_dialog").assertDoesNotExist()
        compose.onNodeWithTag("online_switch").assertIsOff()
        assertEquals(false, m.ui.value.enabled)
    }

    @Test fun confirmingTurnsItOnAndTheSwitchOffPurges() {
        val m = show()
        scrollTo("online_switch"); compose.onNodeWithTag("online_switch").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("online_first_confirm").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("online_switch").assertIsOn()
        assertEquals(true, m.ui.value.enabled)
        compose.onNodeWithTag("online_switch").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("online_switch").assertIsOff()
        assertEquals(listOf("purge"), calls)
    }

    @Test fun aBuildWithoutAKeyShowsTheNoticeAndNoSwitch() {
        show(available = false)
        scrollTo("online_unavailable"); compose.onNodeWithTag("online_unavailable").assertExists()
        compose.onNodeWithTag("online_switch").assertDoesNotExist()
    }
}
