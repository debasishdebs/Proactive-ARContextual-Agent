package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelImageInput
import com.rokid.context.prompt.PromptDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Canvas seam on [ExtractSave.extract]: an in-memory prompt override and the context-sources variable. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExtractSaveOverrideTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prompts = mutableListOf<String>()

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun sentIds(p: String) = Regex("frame_id: (ex\\d)").findAll(p).map { it.groupValues[1] }.toList().distinct()
    private fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA"))
    private fun reply(vararg ids: String) =
        """{"facts":[${ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }}]}"""
    private val legacyReply = """{"facts":[{"text":"A mug","type":"object","confidence":0.9,"tags":[]}]}"""
    private val three = listOf("ex1", "ex2", "ex3")

    private fun graph(reply: (String) -> String) = TestAi.graph(ctx) { p -> prompts += p; reply(p) }.also { TestAi.bindAll(it) }

    @Test fun overrideWithProvenanceSchemaBatchesThree() = runBlocking<Unit> {
        val ai = graph { reply(*sentIds(it).toTypedArray()) }
        val draft = PromptDraft("OVERRIDE {{recent_context}}", DefaultSchemas.text(PromptKey.VISION_EXTRACT))
        val b = ExtractSave.extract(ai.runner, three.map(::photo), CallClass.EXTRACT, override = draft)
        assertEquals(1, prompts.size)
        assertTrue(prompts.single().contains("OVERRIDE"))
        assertEquals(0, b.promptVersion)
        assertTrue(b.output.provenance)
    }

    @Test fun overrideWithLegacySchemaGoesSinglePhoto() = runBlocking<Unit> {
        val legacy = """{"type":"object","properties":{"facts":{"type":"array","items":{"type":"object","properties":{"text":{"type":"string"},"type":{"type":"string"},"confidence":{"type":"number"},"tags":{"type":"array","items":{"type":"string"}}},"required":["text","type","confidence","tags"]}}},"required":["facts"]}"""
        val ai = graph { legacyReply }
        val b = ExtractSave.extract(ai.runner, three.map(::photo), CallClass.EXTRACT, override = PromptDraft("OVERRIDE {{recent_context}}", legacy))
        assertEquals(3, prompts.size)
        assertFalse(b.output.provenance)
        assertEquals(listOf("ex1", "ex2", "ex3"), b.output.facts.map { it.sourceFrameIds.single() })
    }

    @Test fun contextSourcesReachPrompt() = runBlocking<Unit> {
        val ai = graph { reply("ex1") }
        val draft = PromptDraft("SRC[{{context_sources}}] {{recent_context}}", DefaultSchemas.text(PromptKey.VISION_EXTRACT))
        ExtractSave.extract(ai.runner, listOf(photo("ex1")), CallClass.EXTRACT, override = draft, contextSources = "CTXSRC-XYZ")
        assertTrue(prompts.single().contains("SRC[CTXSRC-XYZ]"))
    }

    @Test fun defaultsMatchTodayBehaviour() = runBlocking<Unit> {
        val ai = graph { reply(*sentIds(it).toTypedArray()) }
        val a = ExtractSave.extract(ai.runner, three.map(::photo), CallClass.EXTRACT)
        val withDefaults = prompts.toList(); prompts.clear()
        val b = ExtractSave.extract(ai.runner, three.map(::photo), CallClass.EXTRACT, override = null, contextSources = "")
        assertEquals(withDefaults.size, prompts.size)
        assertEquals(withDefaults.map { it.lines().filter { l -> !l.startsWith("Now") } }, prompts.map { it.lines().filter { l -> !l.startsWith("Now") } })
        assertEquals(a.output, b.output)
        assertEquals(a.promptVersion, b.promptVersion)
    }
}
