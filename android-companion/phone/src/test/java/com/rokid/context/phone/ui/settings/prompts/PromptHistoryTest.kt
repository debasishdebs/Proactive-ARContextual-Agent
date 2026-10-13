package com.rokid.context.phone.ui.settings.prompts

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.DefaultSchemas
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PromptHistoryTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val vms = mutableListOf<PromptHistoryViewModel>()
    private lateinit var ai: AiGraph

    @Before fun setUp() { TestGraph.fresh(ctx); ai = TestSettings.ai(ctx) }

    @After fun tearDown() { try { vms.forEach { it.cancelScope() } } finally { TestGraph.close(ctx) } }

    @Test fun versionsDiffAndRestore() = runBlocking {
        val seed = ai.prompts.active(PromptKey.ANSWER).template
        ai.prompts.save(PromptKey.ANSWER, PromptDraft("$seed\nBe brief.", null), "brief", true)
        val vm = PromptHistoryViewModel("ANSWER", ai).also { vms += it }
        val s = withTimeout(5_000) { vm.state.first { it.versions.size == 2 } }
        assertEquals(listOf(2, 1), s.versions.map { it.version }); assertEquals(2, s.selected)
        assertTrue(s.diff.any { it.kind == LineDiff.Kind.ADDED && it.text == "Be brief." })
        vm.select(1)
        assertTrue(withTimeout(5_000) { vm.state.first { it.selected == 1 } }.canRestore)
        vm.restore()
        assertEquals("Restored as v3", withTimeout(5_000) { vm.state.first { it.notice != null } }.notice)
        assertEquals(seed, ai.prompts.active(PromptKey.ANSWER).template)
        assertFalse(ai.prompts.active(PromptKey.ANSWER).testedOk) // untested until run
    }

    /** Fix round 1: Restore checks the schema against the endpoints bound now and refuses one that doesn't fit. */
    @Test fun restoreRefusesAVersionWhoseSchemaNoLongerFits() = runBlocking {
        val seed = ai.prompts.active(PromptKey.ANSWER)
        val loose = DefaultSchemas.text(PromptKey.ANSWER)!!.replace("\"maxLength\":600", "\"maxLength\":900")
        ai.prompts.save(PromptKey.ANSWER, PromptDraft(seed.template, loose), "loose", true)
        ai.prompts.save(PromptKey.ANSWER, PromptDraft(seed.template + "\nv3", null), "", true)
        val vm = PromptHistoryViewModel("ANSWER", ai).also { vms += it }
        withTimeout(5_000) { vm.state.first { it.versions.size == 3 } }
        vm.select(2)
        withTimeout(5_000) { vm.state.first { it.selected == 2 } }
        vm.restore()
        assertEquals("This version's schema doesn't fit the endpoints bound now", withTimeout(5_000) { vm.state.first { it.notice != null } }.notice)
        assertEquals(3, ai.prompts.active(PromptKey.ANSWER).version)
    }
}
