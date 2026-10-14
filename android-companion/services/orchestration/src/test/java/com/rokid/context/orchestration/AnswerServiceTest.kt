package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.ProposedAction
import com.rokid.context.ai.contract.ShoppingLine
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.retrieval.RetrievalSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AnswerServiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ai: AiGraph
    private var reply = """{"answer":"Added milk.","sources":["rokid_mem_1"],"proposedAction":{"type":"SHOPPING_ADD","items":[{"name":"milk"}]}}"""

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = TestAi.graph(ctx) { reply }
        TestAi.bindAll(ai)
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    /** G10 (MVP section 9.5): no match says so with an ETA and triggers a priority reprocess, once per no-match ask. */
    @Test fun noMatchSaysSoAndQueuesAPriorityReprocess() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        var reprocess = 0
        val service = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }, onNoMatch = { reprocess++ })
        assertEquals(AskOutcome.Failed(AskActions.NO_MEMORIES), service.answer("where are my keys", "phone_typed"))
        assertEquals("I don't have anything on that yet \u2014 I'll look again in your next photos", AskActions.NO_MEMORIES)
        assertEquals(1, reprocess)
        assertEquals("an empty question is not a no-match", AskOutcome.Failed(AskActions.EMPTY_QUESTION), service.answer("  ", "phone_typed"))
        assertEquals(1, reprocess)
        mem.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty"))
        service.answer("milk?", "phone_typed")
        assertEquals("a match triggers nothing", 1, reprocess)
    }

    /** "note that…" (user notes): an action command still reaches the model when there is no memory to answer from. */
    @Test fun anActionCommandWithNoMemoriesStillReachesTheModel() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        reply = """{"answer":"Noted.","sources":[],"proposedAction":{"type":"NOTE_ADD","text":"the car is on level 3"}}"""
        var reprocess = 0
        val actions = mutableListOf<ProposedAction>()
        val service = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }, onNoMatch = { reprocess++ },
            proposals = ProposalIntake { _, _, a, _ -> actions += a; "p-note" })
        val r = service.answer("Note that the car is on level 3", "glasses_voice") as AskOutcome.Answered
        assertEquals(ProposedAction.NoteAdd("the car is on level 3"), r.proposedAction)
        assertEquals("p-note", r.proposalId)
        assertEquals(listOf<ProposedAction>(ProposedAction.NoteAdd("the car is on level 3")), actions)
        assertEquals("Noted.", mem.asks.byId(r.askId)!!.answer)
        assertEquals("an action command is not a no-match", 0, reprocess)
    }

    @Test fun proposedActionGoesToIntakeAfterCommit() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        mem.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty"))
        val calls = mutableListOf<Pair<ProposalOrigin, String>>()
        val intake = ProposalIntake { origin, originId, action, _ ->
            assertNotNull("ask row committed before the intake runs", mem.asks.byId(originId))
            assertEquals(ProposedAction.ShoppingAdd(listOf(ShoppingLine("milk", null, null))), action)
            calls += origin to originId
            "p-fake"
        }
        val service = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }, proposals = intake)
        val r = service.answerOrThrow("add milk", "phone_typed", nowMs = 99)
        assertEquals(listOf(ProposalOrigin.ASK to r.askId), calls)
        assertEquals("p-fake", r.proposalId)
    }

    @Test fun failingIntakeStillAnswersAndCancellationPropagates() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        mem.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty"))
        val boom = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }, proposals = ProposalIntake { _, _, _, _ -> error("db") })
        val r = boom.answerOrThrow("add milk", "phone_typed", nowMs = 99)
        assertEquals(null, r.proposalId)
        assertNotNull(mem.asks.byId(r.askId))
        val cancel = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() },
            proposals = ProposalIntake { _, _, _, _ -> throw kotlinx.coroutines.CancellationException("left") })
        val thrown = runCatching { cancel.answerOrThrow("add milk", "phone_typed", nowMs = 100) }.exceptionOrNull()
        org.junit.Assert.assertTrue("$thrown", thrown is kotlinx.coroutines.CancellationException)
    }

    @Test fun sourceLinesGetTheAsksNow() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        mem.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty"))
        val seen = mutableListOf<Long>()
        val sources = AnswerService.sourcesFrom(com.rokid.context.connectors.ContextSourceProvider { _, now -> seen += now; emptyList() })
        AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }, sources = sources).answerOrThrow("add milk", "phone_typed", nowMs = 1_234)
        assertEquals(listOf(1_234L), seen)
    }
}
