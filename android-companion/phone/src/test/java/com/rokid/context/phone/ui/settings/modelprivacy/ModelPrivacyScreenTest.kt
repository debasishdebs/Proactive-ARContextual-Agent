package com.rokid.context.phone.ui.settings.modelprivacy

import android.app.Application
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.ResolveReason
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.settings.SettingsRootScreen
import com.rokid.context.phone.ui.settings.SettingsRootUiState
import com.rokid.context.phone.ui.shell.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ModelPrivacyScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theModelHasOneLabelPerModeAndAOneLineExplanationForEach() {
        assertEquals(ModelPrivacy.entries.size, ModelPrivacyModel.MODE_LABELS.size)
        assertEquals("Automatic (default)", ModelPrivacyModel.MODE_LABELS[ModelPrivacy.AUTO.ordinal])
        for (m in ModelPrivacy.entries) assertTrue(ModelPrivacyModel.modeNote(m), ModelPrivacyModel.modeNote(m).isNotBlank() && !ModelPrivacyModel.modeNote(m).contains('\n'))
        assertEquals("Automatic", ModelPrivacyModel.rowSubtitle(ModelPrivacy.AUTO))
        assertEquals("Offline only", ModelPrivacyModel.rowSubtitle(ModelPrivacy.OFFLINE_ONLY))
    }

    @Test fun thePlaceLineAppearsOnlyUnderOfflineOnlyWithoutALocalModel() {
        val needs = ModelPrivacyUiState(ModelPrivacy.OFFLINE_ONLY, ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL)
        assertEquals(ModelPrivacyModel.PLACE_NEEDS_MODEL, ModelPrivacyModel.placeLine(needs))
        assertTrue(ModelPrivacyModel.PLACE_NEEDS_MODEL.contains("on-device model"))
        assertEquals(null, ModelPrivacyModel.placeLine(ModelPrivacyUiState(ModelPrivacy.AUTO, ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL)))
        assertEquals(null, ModelPrivacyModel.placeLine(ModelPrivacyUiState(ModelPrivacy.OFFLINE_ONLY, null)))
        assertEquals(null, ModelPrivacyModel.placeLine(ModelPrivacyUiState(ModelPrivacy.AUTO, ResolveReason.NO_NETWORK_NO_LOCAL_MODEL)))
    }

    @Test fun theDefaultScreenShowsAutomaticAndNoOfflineWarnings() {
        compose.setContent { PcTheme { ModelPrivacyScreen(ModelPrivacyUiState(), {}, {}) } }
        compose.onNodeWithTag("model_privacy_mode_note").assertTextContains("cloud or home-network models", substring = true)
        compose.onNodeWithTag("model_privacy_effect").assertDoesNotExist()
        compose.onNodeWithTag("model_privacy_place").assertDoesNotExist()
    }

    @Test fun offlineOnlyExplainsWhatWaitsAndShowsTheNeedsAnOnDeviceModelState() {
        compose.setContent { PcTheme { ModelPrivacyScreen(ModelPrivacyUiState(ModelPrivacy.OFFLINE_ONLY, ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL), {}, {}) } }
        compose.onNodeWithTag("model_privacy_mode_note").assertTextContains("Nothing goes to a remote model", substring = true)
        compose.onNodeWithTag("model_privacy_effect").assertTextContains("wait", substring = true)
        compose.onNodeWithTag("model_privacy_place").assertTextContains("needs an on-device model", substring = true)
    }

    @Test fun pickingAModeReportsIt() {
        var picked: ModelPrivacy? = null
        compose.setContent { PcTheme { ModelPrivacyScreen(ModelPrivacyUiState(), {}, { picked = it }) } }
        compose.onNodeWithTag("model_privacy_mode").performClick()
        compose.onNodeWithTag("model_privacy_mode_1").performClick()
        assertEquals(ModelPrivacy.OFFLINE_ONLY, picked)
    }

    @Test fun theSettingsRootHasAModelPrivacyRowNextToDataSaverThatOpensItsRoute() {
        assertTrue(Routes.SETTINGS_MODEL_PRIVACY in Routes.LIVE_SETTINGS)
        var opened: String? = null
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState(modelPrivacy = "Offline only"), Routes.LIVE_SETTINGS, { opened = it }) } }
        compose.onNodeWithTag("settings_list").performScrollToNode(androidx.compose.ui.test.hasText("Model privacy"))
        compose.onNodeWithText("Offline only").assertTextContains("Offline only")
        compose.onNodeWithText("Model privacy").performClick()
        assertEquals(Routes.SETTINGS_MODEL_PRIVACY, opened)
        assertFalse(opened == Routes.SETTINGS_DATA_SAVER)
    }
}
