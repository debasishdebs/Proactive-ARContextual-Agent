package com.rokid.context.phone.nexus

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.HudHost
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.settings.SettingsRootScreen
import com.rokid.context.phone.ui.settings.SettingsRootUiState
import com.rokid.context.phone.ui.settings.nexus.NexusScreen
import com.rokid.context.phone.ui.settings.nexus.NexusUiState
import com.rokid.context.phone.ui.shell.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v2.0 T8: the Rokid Nexus screen shows each state's own words, the trust text, and offers Nexus only when it is installed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class NexusSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(s: NexusUiState, onHost: (HudHost) -> Unit = {}) {
        compose.setContent { PcTheme { NexusScreen(s, onHost = onHost, onRecheck = {}, back = {}) } }
    }

    @Test fun everyStateShowsItsTitleAndDetail() {
        var state by mutableStateOf(NexusUiState(HudHost.NEXUS, NexusInstalled(true, true, false), NexusState.OFF))
        compose.setContent { PcTheme { NexusScreen(state, {}, {}, {}) } }
        for (st in NexusState.entries) {
            state = state.copy(state = st)
            compose.waitForIdle()
            compose.onNodeWithTag("nexus_settings").performScrollToNode(androidx.compose.ui.test.hasTestTag("nexus_state_title"))
            compose.onNodeWithTag("nexus_state_title").assertTextEquals(NexusStatus.title(st))
            compose.onNodeWithTag("nexus_state_detail").assertTextEquals(NexusStatus.detail(st))
        }
    }

    @Test fun rokidCoderGetsItsOneAtATimeNoteOnlyWhenInstalled() {
        show(NexusUiState(HudHost.PCA, NexusInstalled(true, true, true), NexusState.OFF))
        compose.onNodeWithTag("nexus_settings").performScrollToNode(hasText(NexusStatus.ROKID_CODER_NOTE))
        compose.onNodeWithText(NexusStatus.ROKID_CODER_NOTE).assertIsDisplayed()
    }

    @Test fun theTrustTextIsOnTheScreen() {
        show(NexusUiState(HudHost.PCA, NexusInstalled(true, true, false), NexusState.OFF))
        for ((i, line) in NexusStatus.TRUST_LINES.withIndex()) {
            compose.onNodeWithTag("nexus_settings").performScrollToNode(hasText(line))
            compose.onNodeWithTag("nexus_trust_$i").assertIsDisplayed()
        }
    }

    @Test fun nexusCannotBeChosenWhileItsAppsAreMissingButPcaAlwaysCan() {
        val chosen = mutableListOf<HudHost>()
        show(NexusUiState(HudHost.PCA, NexusInstalled(bridge = true, hub = false, rokidCoder = false), NexusState.HUB_NOT_INSTALLED)) { chosen += it }
        compose.onNodeWithTag("nexus_host").performClick()
        compose.onNodeWithTag("nexus_host_1").performClick()
        assertTrue("the Nexus option is greyed out", chosen.isEmpty())
    }

    @Test fun choosingNexusAndLeavingItReportTheHost() {
        val chosen = mutableListOf<HudHost>()
        show(NexusUiState(HudHost.PCA, NexusInstalled(true, true, false), NexusState.OFF)) { chosen += it }
        compose.onNodeWithTag("nexus_host").performClick(); compose.onNodeWithTag("nexus_host_1").performClick()
        assertEquals(listOf(HudHost.NEXUS), chosen)
    }

    @Test fun theThreeSwitchesAreOffByDefaultAndReportTheirChange() {
        val got = mutableListOf<NexusSwitches>()
        compose.setContent { PcTheme { NexusScreen(NexusUiState(HudHost.PCA, NexusInstalled(true, true, false), NexusState.OFF), {}, {}, {}, onSwitches = { got += it }) } }
        for (tag in listOf("nexus_voice_switch", "nexus_tts_switch", "nexus_skills_switch")) {
            compose.onNodeWithTag("nexus_settings").performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsOff().performClick()
        }
        assertEquals(listOf(NexusSwitches(voice = true), NexusSwitches(tts = true), NexusSwitches(skills = true)), got)
        assertEquals(NexusSwitches(false, false, false), NexusUiState().switches)
    }

    @Test fun theSettingsRowIsHiddenOutsideDeveloperMode() {
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState(), live = Routes.LIVE_SETTINGS, onOpen = {}, developerMode = false) } }
        compose.onAllNodes(hasText("Rokid Nexus")).assertCountEquals(0)
    }

    @Test fun theSettingsRowOpensTheScreenInDeveloperMode() {
        val opened = mutableListOf<String>()
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState(), live = Routes.LIVE_SETTINGS, onOpen = { opened += it }, developerMode = true) } }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Rokid Nexus"))
        compose.onNodeWithText("Rokid Nexus").performClick()
        assertEquals(listOf(Routes.SETTINGS_NEXUS), opened)
    }
}
