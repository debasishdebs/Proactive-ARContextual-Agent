package com.rokid.context.phone.ui.settings.prompts

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.TestSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PromptsListTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val vms = mutableListOf<PromptsListViewModel>()
    private var graph = false

    private fun fresh() { TestGraph.fresh(ctx); graph = true }

    @After fun tearDown() { try { vms.forEach { it.cancelScope() } } finally { if (graph) TestGraph.close(ctx) } }

    @Test fun everyPromptIsListedAndTheToggleIsTheSetting() = runBlocking {
        fresh()
        val ai = TestSettings.ai(ctx)
        val vm = PromptsListViewModel(ai).also { vms += it }
        val s = withTimeout(5_000) { vm.state.first { it.rows.size == PromptKey.entries.size } }
        assertEquals("v1", s.rows.first { it.key == PromptKey.ANSWER }.subtitle)
        vm.setKeepVersions(false)
        assertFalse(ai.prompts.settings.keepVersions.value)
    }

    /** Fix round 1: a seed failure shows fixed text instead of crashing the app. */
    @Test fun aSeedFailureShowsFixedText() = runBlocking {
        fresh()
        val ai = TestSettings.ai(ctx)
        val vm = PromptsListViewModel(ai, seed = { throw IllegalStateException("asset prompts/answer.txt missing") }).also { vms += it }
        assertEquals("Couldn't load the prompts", withTimeout(5_000) { vm.state.first { it.notice != null } }.notice)
    }

    @Test fun theToggleSitsInTheHeader() {
        var keep: Boolean? = null
        compose.setContent { PcTheme { PromptsListScreen(PromptsListUiState(keepVersions = true), onBack = {}, onKeepVersions = { keep = it }, onOpen = {}) } }
        compose.onNodeWithTag("keep_versions").performClick()
        assertEquals(false, keep)
    }
}
