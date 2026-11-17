package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.CondenseSpec
import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.contract.CondenseOutput
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.orchestration.retrieval.QueryCondenser
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Retrieval-quality spec section 10 (T10) through the Ask path: the `rag.condense` search words feed the memory search while the Answer prompt keeps the ORIGINAL question; a plan without
 * the node makes no model call; a broken reply searches the raw question; another language is translated, with its own words as a second search; the Ask fence stops the call.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RagCoreCondenseTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneOffset.UTC
    private fun at(s: String) = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val now = at("2026-11-16T12:04:00")

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); TestAi.bindAll(TestAi.graph(ctx) { "{}" }) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun mem(id: String, text: String, createdAt: String) =
        MemoryGraph.get(ctx).memories.add(MemoryEntity(id, null, text, "note", "", MemoryTier.SHORT, 0.9, ReviewState.OK, false, at(createdAt), false, ""))

    private val asked = mutableListOf<String>()
    private fun condenser(reply: (String) -> CondenseOutput?) = QueryCondenser(ask = { q, _, _ -> asked += q; reply(q) })

    private fun service(c: QueryCondenser? = null, guard: AskZoneGuard = AskZoneGuard.None) = AnswerService(
        MemoryGraph.get(ctx), AiGraph.get(ctx).runner, AiGraph.get(ctx).retriever, { RetrievalSettings(mode = RetrievalMode.LEXICAL) }, zone = { zone }, zoneGuard = guard, condenser = c,
    )

    private val condensing = FlowPlan.BUILT_IN_QUERY.copy(condense = CondenseSpec())
    private fun ids(r: AnswerService.Retrieved) = r.memories.map { it.id }

    private suspend fun market() {
        mem("gourd", "Bitter gourd at the first stall of the vegetable market", "2026-11-15T20:41:00")
        mem("tea", "A cup of tea at the market corner, the table by the market gate", "2026-11-15T20:05:00") // shares the question's filler words
    }

    @Test fun theCondensedWordsFindWhatTheRawQuestionMisses() = runBlocking<Unit> {
        market()
        val q = "Did I see karela at the market?"
        val r = service(condenser { CondenseOutput("bitter gourd", "hi") }).retrieve(q, now, condensing)
        assertEquals("gourd", ids(r).first())
        // the Answer prompt still gets the ORIGINAL question, never the search words
        assertTrue(r.vars.question, r.vars.question.contains(q)); assertFalse(r.vars.question.contains("bitter gourd"))
        assertEquals(listOf(q), asked)
    }

    @Test fun anOwnLanguageMemoryIsFoundByTheSecondWordSearch() = runBlocking<Unit> {
        market()
        mem("voice", "karela bazaar mein dekha, pehli dukaan par", "2026-11-15T20:42:00") // a voice transcript in Hinglish
        val q = "Did I see karela at the market?"
        val r = service(condenser { CondenseOutput("bitter gourd", "hi") }).retrieve(q, now, condensing)
        assertTrue(ids(r).toString(), "gourd" in ids(r) && "voice" in ids(r))
        // an English question has no second search: the Hinglish memory is not pulled in by the question's own words
        val en = service(condenser { CondenseOutput("bitter gourd", "en") }).retrieve(q, now, condensing)
        assertTrue(ids(en).toString(), "gourd" in ids(en) && "voice" !in ids(en))
        // translate off: the same
        val off = service(condenser { CondenseOutput("bitter gourd", "hi") }).retrieve(q, now, FlowPlan.BUILT_IN_QUERY.copy(condense = CondenseSpec(translate = false)))
        assertFalse(ids(off).toString(), "voice" in ids(off))
    }

    @Test fun aBrokenReplyOrAnExceptionSearchesTheRawQuestion() = runBlocking<Unit> {
        market()
        val q = "Where is the tea?"
        val plain = ids(service().retrieve(q, now))
        assertEquals(plain, ids(service(condenser { null }).retrieve(q, now, condensing)))
        assertEquals(plain, ids(service(condenser { CondenseOutput("", "en") }).retrieve(q, now, condensing)))
        assertEquals(plain, ids(service(condenser { throw IllegalStateException("down") }).retrieve(q, now, condensing)))
        assertEquals("tea", plain.first())
    }

    @Test fun aPlanWithoutTheNodeMakesNoCall() = runBlocking<Unit> {
        market()
        val c = condenser { CondenseOutput("bitter gourd", "hi") }
        val s = service(c)
        s.retrieve("Did I see karela at the market?", now) // the built-in plan
        s.retrieve("Did I see karela at the market?", now, FlowPlan.BUILT_IN_QUERY.copy(condense = null))
        assertEquals(emptyList<String>(), asked); assertEquals(0, c.calls)
        assertEquals(null, FlowPlan.BUILT_IN_QUERY.condense)
    }

    @Test fun theAskFenceStopsTheCallBeforeItIsMade() = runBlocking<Unit> {
        market()
        val blocked = AskZoneGuard { true }
        val c = condenser { CondenseOutput("bitter gourd", "hi") }
        val failure = runCatching { service(c, blocked).retrieve("Did I see karela at the market?", now, condensing) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is AskZoneBlockedException)
        assertEquals(0, c.calls)
    }
}
