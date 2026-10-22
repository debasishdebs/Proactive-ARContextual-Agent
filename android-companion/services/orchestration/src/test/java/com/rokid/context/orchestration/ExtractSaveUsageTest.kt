package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S32: [ExtractedBatch.attempts] lists one entry per provider attempt (retries and single-photo fallback calls included). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExtractSaveUsageTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var calls = 0
    private var tokens = true
    private lateinit var script: (String, Int) -> String

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); calls = 0; tokens = true }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun sentIds(p: String) = Regex("frame_id: (ex\\d)").findAll(p).map { it.groupValues[1] }.toList().distinct()
    private fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA"))
    private fun good(vararg ids: String) =
        """{"facts":[${ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }}],"captions":[${ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }}]}"""
    private val bad = """{"facts":[],"captions":[]}"""

    /** [script] gets the prompt and the 1-based call number; each reply reports 100+n prompt tokens and 10+n completion tokens. */
    private fun runner(script: (String, Int) -> String): com.rokid.context.ai.run.RoleRunner {
        this.script = script
        val ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String) = error("unused")
                override suspend fun complete(request: ModelRequest): ModelReply {
                    val n = ++calls
                    return ModelReply(this@ExtractSaveUsageTest.script(request.prompt, n), if (tokens) 100 + n else null, if (tokens) 10 + n else null)
                }
            }
        }).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
        return ai.runner
    }

    @Test fun attemptsFromReplies() = runBlocking<Unit> {
        val r = runner { p, _ -> good(*sentIds(p).toTypedArray()) }
        val b = ExtractSave.extract(r, listOf("ex1", "ex2", "ex3").map(::photo), CallClass.EXPERIMENT)
        assertEquals(1, calls)
        val a = b.attempts.single()
        assertEquals(101, a.promptTokens); assertEquals(11, a.completionTokens)
        assertEquals("gpt", a.model)
        assertTrue(a.latencyMs >= 0)
    }

    @Test fun rejectedRetryAttemptCounted() = runBlocking<Unit> {
        val r = runner { p, n -> if (n == 1) "not json" else good(*sentIds(p).toTypedArray()) }
        val b = ExtractSave.extract(r, listOf(photo("ex1")), CallClass.EXPERIMENT)
        assertEquals(2, calls)
        assertEquals(listOf(101, 102), b.attempts.map { it.promptTokens })
    }

    @Test fun fallbackAttemptsListed() = runBlocking<Unit> {
        // The three-photo call is rejected twice (the runner's own retry), then each photo is its own call.
        val r = runner { p, _ -> if (sentIds(p).size > 1) bad else good(*sentIds(p).toTypedArray()) }
        val b = ExtractSave.extract(r, listOf("ex1", "ex2", "ex3").map(::photo), CallClass.EXPERIMENT)
        assertEquals(5, calls)
        assertEquals(5, b.attempts.size)
        assertEquals(listOf(101, 102, 103, 104, 105), b.attempts.map { it.promptTokens })
        assertEquals(3, b.output.captions.size)
    }

    @Test fun defaultsNullWhenUnknown() = runBlocking<Unit> {
        tokens = false
        val r = runner { p, _ -> good(*sentIds(p).toTypedArray()) }
        val a = ExtractSave.extract(r, listOf(photo("ex1")), CallClass.EXPERIMENT).attempts.single()
        assertNull(a.promptTokens); assertNull(a.completionTokens)
        assertEquals("gpt", a.model)
        assertEquals(emptyList<CallUsage>(), ExtractedBatch(com.rokid.context.ai.contract.ExtractOutput(emptyList()), 0).attempts)
    }
}
