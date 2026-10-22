package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [AnswerService.retrieveScored]: the same retrieval as [AnswerService.retrieve], with the scores kept and an optional as-of bound. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AnswerServiceScoredTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ai: AiGraph
    private val mem get() = MemoryGraph.get(ctx)
    private fun service() = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings(mode = RetrievalMode.LEXICAL) })
    private suspend fun add(id: String, text: String, at: Long) = mem.memories.add(LegacyImporter.toMemory(id, "short_term", 0.9, at, text))

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = TestAi.graph(ctx) { "{}" }.also { TestAi.bindAll(it) }
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun sameIdsAsRetrieve() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); add("m2", "milk bottle on the shelf", 20); add("m3", "house keys on the hook", 30)
        val s = service()
        val plain = s.retrieve("milk fridge", 1_000)
        val scored = s.retrieveScored("milk fridge", 1_000)
        assertEquals(plain.ids, scored.retrieved.ids)
        assertEquals(plain.memories.map { it.id }, scored.retrieved.memories.map { it.id })
        assertEquals(plain.vars, scored.retrieved.vars)
        assertEquals(RetrievalMode.LEXICAL, scored.mode)
        assertFalse(scored.fallback)
        assertEquals(setOf("m1", "m2"), scored.hits.map { it.memory.id }.toSet())
        assertTrue(scored.hits.all { it.lexical != null && it.score > 0 })
        assertEquals(scored.retrieved.memories.size, scored.lines.size)
    }

    @Test fun notAfterDropsNewerMemories() = runBlocking<Unit> {
        add("old", "milk carton in the fridge", 10); add("new", "milk bottle on the shelf", 100)
        val s = service()
        val bound = s.retrieveScored("milk", 1_000, notAfterMs = 50)
        assertEquals(listOf("old"), bound.hits.map { it.memory.id })
        assertEquals(setOf("old"), bound.retrieved.ids)
        assertFalse("new" in bound.retrieved.vars.retrievedMemories)
        assertEquals(setOf("old", "new"), s.retrieveScored("milk", 1_000).retrieved.ids) // null bound = today
        // the fallback pool is bounded too
        val fb = s.retrieveScored("zzzz", 1_000, notAfterMs = 50)
        assertTrue(fb.fallback)
        assertEquals(setOf("old"), fb.retrieved.ids)
    }

    @Test fun fallbackFlagWhenNoHits() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10)
        val s = service()
        val r = s.retrieveScored("zzzz", 1_000)
        assertTrue(r.fallback)
        assertTrue(r.hits.isEmpty())
        assertEquals(setOf("m1"), r.retrieved.ids)
        assertEquals(s.retrieve("zzzz", 1_000).ids, r.retrieved.ids)
        assertFalse(s.retrieveScored("milk", 1_000).fallback)
    }
}
