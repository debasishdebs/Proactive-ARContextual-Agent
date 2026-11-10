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

    // codex r17 P1-3: the rebuilt summary inherits its sources' photo provenance. MA cites A and carries X; S from MA/MB; Forget MB; R rebuilt from MA;
    // MA expires; Forget X -> R is invalidated
    @Test fun aRebuiltSummaryInheritsItsSourcesPhotosAndGoesWithThem() = runBlocking {
        for (f in listOf("A", "X")) {
            graph.db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity(f, 1, "/$f.jpg", "rokid_glasses"))
            graph.db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity(f, null, null, "STILL", false, null, null, null, 640, 0.3f, null, null, null, "s1", 1))
        }
        graph.db.memoryDao().insert(MemoryEntity("MA", "A", "Sibling source with an X detail", "object", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "t"))
        graph.db.frameSourceDao().insertAll(listOf(com.rokid.context.memory.db.FrameSourceEntity("MA", "A", null, "FACT"), com.rokid.context.memory.db.FrameSourceEntity("MA", "X", null, "CARRIED")))
        mem("MB", "Another source", derived = false); mem("S", "S from MA and MB")
        edge("S", "MA"); edge("S", "MB")
        forget("MB")
        work()
        val r = graph.memories.all().single { it.text == "Rebuilt B" }
        assertEquals(setOf("A", "X"), graph.db.frameSourceDao().forMemory(r.id).filter { it.role == "CARRIED" }.map { it.frameId }.toSet())
        ForgetService(graph.db, graph.proposals).expire(listOf("MA")) // retention expires MA: R keeps living, its edge to MA goes
        assertTrue("R survives MA's expiry", graph.memories.all().any { it.id == r.id })
        ForgetService(graph.db, graph.proposals).forgetMoment("X")
        assertTrue("R, derived from X's detail, is gone after Forget X", graph.memories.all().none { it.id == r.id && it.reviewState == ReviewState.OK })
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

    /** Fix round 1 I8: one memory's failure is skipped, the others are still rebuilt, and the run retries. */
    @Test fun perMemoryFailureSkipsAndContinues() = runBlocking {
        mem("A", "Forgotten", derived = false); mem("S", "Sibling source", derived = false); mem("T", "Third source", derived = false)
        mem("B", "b"); mem("C", "c")
        edge("B", "A"); edge("B", "S"); edge("C", "A"); edge("C", "T")
        forget("A")
        reply = { p -> if ("Sibling source" in p) throw java.io.IOException("socket closed") else """{"summary":"Rebuilt C","events":[],"entities":[],"confidence":0.9,"candidate_memory_type":"short_term"}""" }
        assertTrue(work() is ListenableWorker.Result.Retry)
        assertEquals(ReviewState.INVALIDATED, graph.db.memoryViewDao().byId("B")!!.reviewState)
        assertTrue(graph.memories.all().any { it.text == "Rebuilt C" })
    }

    @Test fun attemptsAreCappedAtFive() = runBlocking {
        mem("A", "Forgotten", derived = false); mem("S", "Sibling source", derived = false); mem("B", "b")
        edge("B", "A"); edge("B", "S")
        forget("A")
        reply = { throw java.io.IOException("socket closed") }
        val last = TestListenableWorkerBuilder<ResummarizeWorker>(ctx).setRunAttemptCount(4).build().doWork()
        assertTrue(last is ListenableWorker.Result.Failure)
        // A permanent failure (FAIL by RetryClassifier) never retries.
        reply = { throw com.rokid.context.ai.ProviderHttpException(401, "Azure OpenAI", "denied") }
        assertTrue(work() is ListenableWorker.Result.Failure)
    }

    /** Fix round 1 C1: a rebuilt memory keeps the original createdAt, so it keeps its retention window. */
    @Test fun rebuiltMemoryKeepsCreatedAt() = runBlocking {
        mem("A", "Forgotten", derived = false); mem("S", "Sibling source", derived = false)
        graph.db.memoryDao().insert(MemoryEntity("B", null, "b", "summary", "", MemoryTier.MEDIUM, 0.8, ReviewState.OK, false, 777, true, "b"))
        edge("B", "A"); edge("B", "S")
        forget("A")
        work()
        assertEquals(777L, graph.memories.all().single { it.text == "Rebuilt B" }.createdAt)
    }
}
