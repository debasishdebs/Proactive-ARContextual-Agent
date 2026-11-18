package com.rokid.context.phone.ui.settings.suggestions

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.orchestration.proactive.ProactiveFamily
import com.rokid.context.orchestration.proactive.SuggestionRecord
import com.rokid.context.orchestration.proactive.TriggerKind
import com.rokid.context.orchestration.proactive.generators.RenderedCard
import com.rokid.context.orchestration.proactive.generators.RenderedSource
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
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SuggestionsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val ts = 1_799_000_000_000L
    private val rec = SuggestionRecord("sg_1", ts, "f2:wd:Q1", ProactiveFamily.PLACE_RECALL, TriggerKind.PLACE_ARRIVAL, 0.72, "wd:Q1", listOf("mem:m", "pin:user:p"), listOf("arrived", "visits=3", "pin_note", "marks=2"))
    private val card = RenderedCard("Golconda: last visit 12 Nov. Your note: “sunset call”", "from 2 items · 12 Nov", listOf(RenderedSource("memory", ts - 6 * 86_400_000L), RenderedSource("pin", null), RenderedSource("mark", ts - 6 * 86_400_000L)))
    private val row = ShadowRowUi(rec.id, rec.key, rec.ts, card.text, card.sourceLabel, SuggestionsModel.why(rec, card, zone))

    @Test fun whyIsBuiltFromTokensAndSourceTimesOnly() {
        val w = row.why
        assertEquals("Place recall", w.family); assertEquals("You arrived at a place you have been before", w.trigger)
        assertTrue(w.sources.any { it.startsWith("Place memory · ") }); assertTrue("Your pin note" in w.sources); assertTrue(w.sources.any { it.startsWith("A mark you made · ") })
        assertTrue("You have looked at it 3 times before." in w.reasons); assertTrue("You made 2 marks on earlier visits." in w.reasons); assertTrue(w.reasons.any { it.startsWith("Score 0.72") })
        assertFalse((w.sources + w.reasons).joinToString().contains("sunset"))
    }

    @Test fun theMasterSwitchIsOffByDefaultAndTheSafetyNoticeIsThere() {
        var master = true
        compose.setContent { PcTheme { SuggestionsScreen(SuggestionsUiState(), {}, { master = it }, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("suggestions_master").assertIsOff()
        compose.onNodeWithTag("suggestions_safety").assertTextContains("Do not rely on them for safety", substring = true)
        compose.onNodeWithTag("suggestions_settings").performScrollToNode(hasTestTag("suggestions_empty"))
        compose.onNodeWithTag("suggestions_empty").assertTextEquals(SuggestionsModel.EMPTY)
        compose.onNodeWithTag("suggestions_settings").performScrollToNode(hasTestTag("suggestions_master"))
        compose.onNodeWithTag("suggestions_master").performClick()
        assertTrue("clicking asks for ON", master)
    }

    @Test fun theScreenShowsTheLiveDrivingLineAndWhenStorageIsDown() {
        var state by androidx.compose.runtime.mutableStateOf(SuggestionsUiState())
        compose.setContent { PcTheme { SuggestionsScreen(state, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("suggestions_settings").performScrollToNode(hasTestTag("suggestions_driving"))
        compose.onNodeWithTag("suggestions_driving").assertTextContains("suggestions are paused", substring = true)
        compose.onAllNodes(hasTestTag("suggestions_storage_down")).assertCountEquals(0)
        state = SuggestionsUiState(drivingLine = "Unknown: calm evidence needs two phone motion sources: suggestions are paused", storageUnavailable = true)
        compose.onNodeWithTag("suggestions_driving").assertTextEquals("Unknown: calm evidence needs two phone motion sources: suggestions are paused")
        compose.onNodeWithTag("suggestions_storage_down").assertTextContains("nothing is recorded", substring = true)
    }

    @Test fun theLogShowsTextSourcesAndCountersAndWhyOpensTheSheetWithNever() {
        var never: String? = null; var dismissed: String? = null
        compose.setContent { PcTheme { SuggestionsScreen(SuggestionsUiState(true, true, false, listOf(row), 2, listOf("Would have shown" to 1)), {}, {}, {}, {}, { dismissed = it }, { never = it }, {}) } }
        compose.onNodeWithTag("suggestions_master").assertIsOn()
        compose.onNodeWithTag("suggestions_settings").performScrollToNode(hasTestTag("suggestions_text_0"))
        compose.onNodeWithTag("suggestions_text_0").assertTextContains("Golconda", substring = true)
        compose.onNodeWithTag("suggestions_source_0").assertTextEquals(card.sourceLabel)
        compose.onNodeWithTag("suggestions_settings").performScrollToNode(hasTestTag("suggestions_counter_0"))
        compose.onNodeWithTag("suggestions_counter_0").assertTextEquals("Would have shown: 1")
        compose.onNodeWithTag("suggestions_never_count").assertTextEquals("Never suggest list: 2")
        compose.onNodeWithTag("suggestions_why_0").performClick()
        compose.onNodeWithTag("why_family").assertTextEquals("Kind: Place recall")
        compose.onNodeWithTag("why_trigger").assertTextContains("arrived", substring = true)
        compose.onNodeWithTag("why_source_0").assertTextContains("Place memory", substring = true)
        compose.onNodeWithTag("why_never").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals("sg_1", never); assertEquals(null, dismissed)
    }

    @Test fun theRowIsDeveloperOnlyInSettings() {
        var opened: String? = null
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState(), Routes.LIVE_SETTINGS, { opened = it }, developerMode = false) } }
        assertTrue(Routes.SETTINGS_SUGGESTIONS in Routes.LIVE_SETTINGS)
        compose.onAllNodes(androidx.compose.ui.test.hasText("Suggestions (shadow)")).assertCountEquals(0)
        assertEquals(null, opened)
    }
}
