package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.AiGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ResummarizeWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prompts = mutableListOf<String>()
    private var reply: suspend (String) -> String = { p ->
        val s = if ("Sibling source" in p) "Rebuilt B" else "Rebuilt C"
        """{"summary":"$s","events":[],"entities":[],"confidence":0.95,"candidate_memory_type":"short_term"}"""
    }
    private val graph get() = MemoryGraph.get(ctx)
    private val dao get() = graph.db.forgetDao()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        TestAi.bindAll(TestAi.graph(ctx) { p -> prompts += p; reply(p) })
    }

    @After fun tearDown() { AiGraph.resetForTests(null); graph.db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun mem(id: String, text: String, derived: Boolean = true, conf: Double = 0.8, tier: MemoryTier = MemoryTier.MEDIUM) =
        graph.db.memoryDao().insert(MemoryEntity(id, null, text, "summary", "", tier, conf, ReviewState.OK, false, 1, derived, text))
    private suspend fun edge(child: String, parent: String) = dao.insertEdges(listOf(MemorySourceEntity(child, parent)))
    private fun work() = runBlocking { TestListenableWorkerBuilder<ResummarizeWorker>(ctx).build().doWork() }
    private suspend fun forget(id: String) = ForgetService(graph.db, graph.proposals).forgetFact(id)

    @Test fun parentProcessedBeforeChildAndEdgesRepointed() = runBlocking {
        mem("A", "Forgotten diary page", derived = false)
        mem("S", "Sibling source about the desk", derived = false, conf = 0.7)
        mem("B", "B from A and S"); mem("C", "C from B")
        edge("B", "A"); edge("B", "S"); edge("C", "B")
        assertEquals(setOf("B", "C"), forget("A").invalidated)
        assertTrue(work() is ListenableWorker.Result.Success)
        assertNull(graph.db.memoryViewDao().byId("B")); assertNull(graph.db.memoryViewDao().byId("C"))
        val all = graph.memories.all()
        val b2 = all.single { it.text == "Rebuilt B" }; val c2 = all.single { it.text == "Rebuilt C" }
        assertTrue(b2.id.startsWith("rokid_mem_rs_") && b2.derived && b2.reviewState == ReviewState.OK && b2.tier == MemoryTier.MEDIUM)
        assertEquals(0.7, b2.confidence, 1e-9) // min(model 0.95, max source 0.7)
        assertEquals(listOf("S"), dao.edgesFrom(listOf(b2.id)).map { it.sourceMemoryId })
        assertEquals(listOf(b2.id), dao.edgesFrom(listOf(c2.id)).map { it.sourceMemoryId })
        // B was rebuilt before C (C's prompt holds B'), and no prompt ever held the forgotten text.
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("Rebuilt B"))
        assertTrue(prompts.none { "Forgotten diary" in it || "B from A and S" in it || "C from B" in it })
    }

    @Test fun noSourcesDeletes() = runBlocking {
        mem("A", "Forgotten", derived = false); mem("B", "Only from A")
        edge("B", "A")
        forget("A")
        work()
        assertNull(graph.db.memoryViewDao().byId("B"))
        assertTrue(prompts.isEmpty())
        assertTrue(dao.edgesFrom(listOf("B")).isEmpty())
    }

    @Test fun cycleMembersAreTreatedAsSourceless() = runBlocking {
        mem("A", "Forgotten", derived = false); mem("B", "b"); mem("C", "c")
        edge("B", "A"); edge("C", "B"); edge("B", "C")
        forget("A")
        work()
        assertEquals(0, graph.memories.all().size)
        assertTrue(prompts.isEmpty())
    }

    @Test fun modelFailureRetriesAndStaysHidden() = runBlocking {
        mem("A", "Forgotten", derived = false); mem("S", "Sibling source", derived = false); mem("B", "b")
        edge("B", "A"); edge("B", "S")
        forget("A")
        reply = { throw java.io.IOException("socket closed") }
        assertTrue(work() is ListenableWorker.Result.Retry)
        assertEquals(ReviewState.INVALIDATED, graph.db.memoryViewDao().byId("B")!!.reviewState)
        assertFalse(graph.memories.all().any { it.id.startsWith("rokid_mem_rs_") })
    }

    /** A parent that is itself hidden (INVALIDATED) is never a source: its text never reaches the model. */
    @Test fun invalidatedParentTextNeverSent() = runBlocking {
        mem("A", "Forgotten", derived = false); mem("S", "Sibling source", derived = false)
        mem("X", "Hidden parent secret", derived = false); mem("B", "b")
        edge("B", "A"); edge("B", "S"); edge("B", "X")
        forget("A")
        dao.invalidate(listOf("X"))
        work()
        assertTrue(prompts.single().contains("Sibling source"))
        assertFalse(prompts.single().contains("Hidden parent secret"))
    }
}
