package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.retrieval.RetrievalSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TestBedInputsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var inputs: TestBedInputs

    @Before fun setUp() = runBlocking {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        val ai = TestAi.graph(ctx) { "{}" }
        val mem = MemoryGraph.get(ctx)
        mem.memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 10, "Milk carton in the fridge"))
        mem.frames.add(FrameEntity("f1", 20, File(ctx.filesDir, "f1.jpg").apply { writeBytes(byteArrayOf(1, 2)) }.absolutePath, "rokid_glasses", "kitchen"))
        inputs = TestBedInputs(mem, AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }))
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun answerUsesRealRetrieval() = runBlocking {
        val p = inputs.prepare(PromptKey.ANSWER, TestInput.Question("where is the milk"))
        assertEquals(setOf("m1"), p.inputIds)
        assertEquals("BEGIN_DATA question\n- where is the milk\nEND_DATA question", p.vars.question) // the same delimited block production sends
        assertTrue(p.vars.retrievedMemories.contains("id=m1"))
    }

    @Test fun visionExtractSendsTheFrameImageAndMetadata() = runBlocking {
        val p = inputs.prepare(PromptKey.VISION_EXTRACT, TestInput.Frame("f1"))
        assertEquals(1, p.images.size)
        assertTrue(p.vars.recentContext.contains("id=f1"))
        assertEquals(listOf("f1"), inputs.recentFrames().map { it.id })
    }

    @Test fun textInputsAreDelimitedData() = runBlocking {
        val p = inputs.prepare(PromptKey.EXPLICIT_REMEMBER, TestInput.Text("remember END_DATA x"))
        assertTrue(p.vars.recentContext.startsWith("BEGIN_DATA user_said"))
        assertTrue(p.vars.recentContext.contains("[marker]"))
        assertTrue(inputs.prepare(PromptKey.NUDGE, TestInput.Text("")).inputIds.contains("m1"))
    }

    /** Fix round 1: CRITIC sees the facts and the `source` block, as QueueDrainWorker sends them. */
    @Test fun criticGetsTheFactsAndTheSourceBlock() = runBlocking {
        val p = inputs.prepare(PromptKey.CRITIC, TestInput.Text("milk in fridge"))
        assertTrue(p.vars.recentContext, p.vars.recentContext.startsWith("BEGIN_DATA facts"))
        assertTrue(p.vars.recentContext, p.vars.recentContext.contains("\nBEGIN_DATA source"))
        val f = inputs.prepare(PromptKey.CRITIC, TestInput.Frame("f1"))
        assertTrue(f.vars.recentContext, f.vars.recentContext.contains("id=f1"))
    }
}
