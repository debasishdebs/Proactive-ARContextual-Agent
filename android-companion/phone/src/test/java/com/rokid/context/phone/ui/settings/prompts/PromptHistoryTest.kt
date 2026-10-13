package com.rokid.context.phone.ui.settings.prompts

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.TestSettings
import com.rokid.context.prompt.LineDiff
import com.rokid.context.prompt.PromptDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PromptHistoryTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun versionsDiffAndRestore() = runBlocking {
        TestGraph.fresh(ctx)
        val ai = TestSettings.ai(ctx)
        val seed = ai.prompts.active(PromptKey.ANSWER).template
        ai.prompts.save(PromptKey.ANSWER, PromptDraft("$seed\nBe brief.", null), "brief", true)
        val vm = PromptHistoryViewModel("ANSWER", ai)
        val s = withTimeout(5_000) { vm.state.first { it.versions.size == 2 } }
        assertEquals(listOf(2, 1), s.versions.map { it.version }); assertEquals(2, s.selected)
        assertTrue(s.diff.any { it.kind == LineDiff.Kind.ADDED && it.text == "Be brief." })
        vm.select(1)
        assertTrue(withTimeout(5_000) { vm.state.first { it.selected == 1 } }.canRestore)
        vm.restore()
        assertEquals("Restored as v3", withTimeout(5_000) { vm.state.first { it.notice != null } }.notice)
        assertEquals(seed, ai.prompts.active(PromptKey.ANSWER).template)
        vm.cancelScope(); TestGraph.close(ctx)
    }
}
