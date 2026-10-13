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

    @Test fun everyPromptIsListedAndTheToggleIsTheSetting() = runBlocking {
        TestGraph.fresh(ctx)
        val ai = TestSettings.ai(ctx)
        val vm = PromptsListViewModel(ai)
        val s = withTimeout(5_000) { vm.state.first { it.rows.size == PromptKey.entries.size } }
        assertEquals("v1", s.rows.first { it.key == PromptKey.ANSWER }.subtitle)
        vm.setKeepVersions(false)
        assertFalse(ai.prompts.settings.keepVersions.value)
        vm.cancelScope(); TestGraph.close(ctx)
    }

    @Test fun theToggleSitsInTheHeader() {
        var keep: Boolean? = null
        compose.setContent { PcTheme { PromptsListScreen(PromptsListUiState(keepVersions = true), onBack = {}, onKeepVersions = { keep = it }, onOpen = {}) } }
        compose.onNodeWithTag("keep_versions").performClick()
        assertEquals(false, keep)
    }
}
