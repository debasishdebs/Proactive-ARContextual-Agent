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

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = TestAi.graph(ctx) {
            """{"answer":"Added milk.","sources":["rokid_mem_1"],"proposedAction":{"type":"SHOPPING_ADD","items":[{"name":"milk"}]}}"""
        }
        TestAi.bindAll(ai)
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

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
}
