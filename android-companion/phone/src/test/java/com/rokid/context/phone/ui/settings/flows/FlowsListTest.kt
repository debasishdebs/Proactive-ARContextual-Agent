package com.rokid.context.phone.ui.settings.flows

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.Role
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.settings.SettingsRootScreen
import com.rokid.context.phone.ui.settings.SettingsRootUiState
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Test fixture standing in for the AgentFlows registry (stream A). */
private val FIXTURE = object : FlowSummaries {
    override fun summaries() = listOf(
        FlowSummary("capture", "Capture", "Turns glasses photos and things you say into memories.", 7,
            listOf(Role.VISION_EXTRACT, Role.CRITIC, Role.SUMMARIZE, Role.EMBED, Role.EXPLICIT_REMEMBER)),
        FlowSummary("ask", "Ask", "Answers your questions from your memories.", 2, listOf(Role.EMBED, Role.ANSWER)),
        FlowSummary("nudge", "Nudge", "Suggests something useful after new memories land.", 1, listOf(Role.NUDGE)),
    )
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FlowsListTest {
    @get:Rule val compose = createComposeRule()

    private fun rows(models: Map<Role, String>) = runBlocking { FlowsListSource(FIXTURE, { models[it] }, {}).load() }

    @Test fun subtitleCountsAiStepsAndDistinctModels() {
        val models = mapOf(
            Role.VISION_EXTRACT to "llava", Role.CRITIC to "gpt-4o", Role.SUMMARIZE to "gpt-4o", Role.EMBED to "nomic-embed",
            Role.EXPLICIT_REMEMBER to "gpt-4o", Role.ANSWER to "gpt-4o", Role.NUDGE to "llama3",
        )
        val byId = rows(models).associateBy { it.id }
        assertEquals(listOf("capture", "ask", "nudge"), rows(models).map { it.id })
        assertEquals("Turns glasses photos and things you say into memories.", byId.getValue("capture").subtitle)
        assertEquals("7 AI steps \u00b7 llava, gpt-4o +1", byId.getValue("capture").trailingText)
        assertEquals("2 AI steps \u00b7 nomic-embed, gpt-4o", byId.getValue("ask").trailingText)
        assertEquals("1 AI step \u00b7 llama3", byId.getValue("nudge").trailingText)
    }

    @Test fun unboundRoleShowsNotSetOnce() {
        val byId = rows(mapOf(Role.EMBED to "nomic-embed")).associateBy { it.id }
        assertEquals("7 AI steps \u00b7 Not set, nomic-embed", byId.getValue("capture").trailingText)
        assertEquals("2 AI steps \u00b7 nomic-embed, Not set", byId.getValue("ask").trailingText)
        assertEquals("1 AI step \u00b7 Not set", byId.getValue("nudge").trailingText)
    }

    @Test fun settingsAiSectionListsAgentFlowsFirst() {
        val opened = mutableListOf<String>()
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState("Linked", "Every 5 min"), live = Routes.LIVE_SETTINGS, onOpen = { opened += it }) } }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("Agent flows"))
        compose.onNodeWithText("See and tune each step of every agent").assertExists()
        val label = compose.onNodeWithText("AI").getUnclippedBoundsInRoot().top
        val flows = compose.onNodeWithText("Agent flows").getUnclippedBoundsInRoot().top
        val endpoints = compose.onNodeWithText("Model endpoints").getUnclippedBoundsInRoot().top
        assertTrue("Agent flows sits under the AI label", flows > label)
        assertTrue("Agent flows is above Model endpoints", flows < endpoints)
        compose.onNodeWithText("Agent flows").performClick()
        assertEquals(listOf(Routes.SETTINGS_FLOWS), opened)
    }

    @Test fun rowTapOpensThatFlowAlone() {
        val routes = mutableListOf<String>()
        compose.setContent { PcTheme { FlowsListRoute(FIXTURE, { null }, onBack = {}, navigate = { routes += it }) } }
        compose.onNodeWithText("Ask").performClick()
        compose.waitForIdle()
        assertEquals(listOf("settings/flows/view?ids=ask"), routes)
    }

    @Test fun showSelectedOpensViewWithIds() {
        val routes = mutableListOf<String>()
        compose.setContent { PcTheme { FlowsListRoute(FIXTURE, { null }, onBack = {}, navigate = { routes += it }) } }
        compose.onNodeWithTag("list_select_ask").performClick()
        compose.onNodeWithTag("list_select_capture").performClick()
        compose.onNodeWithText("Show selected (2)").assertExists()
        compose.onNodeWithTag("list_show_selected").performClick()
        assertEquals(listOf("settings/flows/view?ids=capture,ask"), routes)
        assertEquals("settings/flows/view?ids=capture,ask", Routes.flowsView(listOf("capture", "ask")))
    }
}
