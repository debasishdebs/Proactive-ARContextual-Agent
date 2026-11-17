package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.canvas.OrderParams
import com.rokid.context.ai.canvas.OrderSpec
import com.rokid.context.ai.canvas.WindowSpec
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.orchestration.retrieval.TimeWindow
import com.rokid.context.orchestration.retrieval.WindowResolver
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * Retrieval-quality spec section 5 through the Ask path: the `rag.order` selection (newest first, one hit per day, the night extent), the `rag.window` switches
 * (widening, the model fallback), and that a plan WITHOUT the nodes (the built-in one) is untouched.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RagCoreOrderTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneOffset.UTC
    private fun at(s: String) = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val now = at("2026-11-16T12:04:00")

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); TestAi.bindAll(TestAi.graph(ctx) { "{}" }) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun mem(id: String, text: String, createdAt: String) =
        MemoryGraph.get(ctx).memories.add(MemoryEntity(id, null, text, "note", "", MemoryTier.SHORT, 0.9, ReviewState.OK, false, at(createdAt), false, ""))

    private fun service(llm: WindowResolver? = null) = AnswerService(
        MemoryGraph.get(ctx), AiGraph.get(ctx).runner, AiGraph.get(ctx).retriever, { RetrievalSettings(mode = RetrievalMode.LEXICAL) }, zone = { zone }, llmWindows = llm,
    )

    private fun words(s: String) = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    private val order = OrderSpec(words = mapOf(
        "night" to words(OrderParams.NIGHT_WORDS), "previous" to words(OrderParams.PREVIOUS_WORDS), "first" to words(OrderParams.FIRST_WORDS), "latest" to words(OrderParams.LATEST_WORDS),
        "count" to words(OrderParams.COUNT_WORDS), "state" to words(OrderParams.STATE_WORDS),
    ))
    private val dated = FlowPlan.BUILT_IN_QUERY.copy(window = WindowSpec(), order = order)

    private fun lines(r: AnswerService.Retrieved) = r.vars.retrievedMemories.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ") }
    private fun ids(r: AnswerService.Retrieved) = r.memories.map { it.id }

    private suspend fun keys() {
        mem("k02", "Keys on the hook by the door", "2026-11-02T10:00:00")
        mem("k14a", "Keys hanging on the hook", "2026-11-14T09:00:00"); mem("k14b", "Keys on the hook, close up", "2026-11-14T09:20:00")
        mem("k15a", "A bunch of keys on the hook", "2026-11-15T20:00:00"); mem("k15b", "Keys on the hook near the shoes", "2026-11-15T20:10:00")
        mem("tea", "A cup of tea on the table", "2026-11-15T20:05:00")
    }

    @Test fun lastMeansTheNewestRelevantMemoriesNewestFirstAndSaysSo() = runBlocking<Unit> {
        keys()
        val r = service().retrieve("When did I last see my keys?", now, dated)
        assertEquals(listOf("k15b", "k15a", "k14b", "k14a", "k02"), ids(r))
        val l = lines(r)
        assertTrue(l.first(), l.first().startsWith("note: memories are listed newest first"))
        assertTrue(l[1], l[1].startsWith("id=k15b · Sun 15 Nov 2026 20:10 · confidence"))
    }

    @Test fun thePreviousTimeSkipsTheNewestEpisode() = runBlocking<Unit> {
        keys()
        val r = service().retrieve("What was the previous time I saw my keys?", now, dated)
        assertEquals(listOf("k14b", "k14a", "k02"), ids(r))
        assertTrue(lines(r).first(), lines(r).first().contains("left out"))
    }

    @Test fun firstMeansOldestFirst() = runBlocking<Unit> {
        keys()
        assertEquals(listOf("k02", "k14a", "k14b", "k15a", "k15b"), ids(service().retrieve("When did I first see my keys?", now, dated)))
    }

    @Test fun thePlanWithoutTheNodesIsUntouched() = runBlocking<Unit> {
        keys()
        val plain = service().retrieve("When did I last see my keys?", now) // BUILT_IN_QUERY
        assertFalse(lines(plain).any { it.startsWith("note:") })
        assertEquals(plain.vars.retrievedMemories, service().retrieve("When did I last see my keys?", now, FlowPlan.BUILT_IN_QUERY.copy(window = null, order = null)).vars.retrievedMemories)
        // and a node plan whose question has no keyword and no date keeps the retriever's order
        val noKeyword = service().retrieve("Show my keys", now, dated)
        assertEquals(ids(service().retrieve("Show my keys", now)), ids(noKeyword))
        assertFalse(lines(noKeyword).any { it.startsWith("note:") })
    }

    @Test fun oneHitPerDayForAMultiDayQuestionInCompactTimeOrder() = runBlocking<Unit> {
        for (d in listOf("03", "05", "08")) for ((i, h) in listOf("19:00", "19:10", "22:00").withIndex()) mem("tv$d$i", "Watching TV on the sofa $i", "2026-11-${d}T$h:00")
        mem("tvold", "Watching TV in October", "2026-10-20T20:00:00")
        val r = service().retrieve("On which days did I watch TV this month?", now, dated)
        // 2 per day (the 19:10 one is within 30 minutes of the 19:00 one), October is outside "this month"
        assertEquals(6, r.memories.size)
        assertEquals(r.memories.map { it.createdAt }.sorted(), r.memories.map { it.createdAt })
        assertEquals(setOf("03", "05", "08"), r.memories.map { it.id.substring(2, 4) }.toSet())
        val l = lines(r)
        assertTrue(l.first(), l.first().contains("2 memories per day, in time order, 3 days"))
        assertTrue(l[1], Regex("id=tv\\d+ · \\w{3} \\d{1,2} Nov \\d\\d:\\d\\d · Watching TV on the sofa \\d").matches(l[1])) // the compact line: no confidence, no frame
        assertFalse(l.any { it.contains("tvold") })
    }

    @Test fun theNightExtentReadsTheWindowNotTheSearch() = runBlocking<Unit> {
        // nothing here says "stayed up"; the last capture of each night comes from the window itself
        mem("n1a", "Laptop on the desk", "2026-11-07T22:00:00"); mem("n1b", "Dark room, phone glowing", "2026-11-08T02:30:00")
        mem("n2a", "Reading on the sofa", "2026-11-09T21:00:00"); mem("n2b", "Lights off in the bedroom", "2026-11-09T23:40:00")
        val r = service().retrieve("What's the latest I stayed up this month?", now, dated)
        assertEquals(listOf("n1b", "n2b"), ids(r))
        assertTrue(lines(r).first(), lines(r).first().contains("one line per night"))
        assertFalse(r.vars.retrievedMemories.contains("n1a"))
    }

    @Test fun widenNoneStopsAtTheWindow() = runBlocking<Unit> {
        mem("m9", "Bought milk at the shop", "2026-11-09T20:00:00")
        val day = service().retrieve("What milk did I see on 10 Nov?", now, FlowPlan.BUILT_IN_QUERY.copy(window = WindowSpec(widen = "DAY")))
        assertEquals(listOf("m9"), ids(day)) // a day wider reaches 9 Nov
        val none = service().retrieve("What milk did I see on 10 Nov?", now, FlowPlan.BUILT_IN_QUERY.copy(window = WindowSpec(widen = "NONE")))
        assertTrue(lines(none).first(), lines(none).first().startsWith("note: nothing matched the asked time"))
        val rulesOff = service().retrieve("What milk did I see on 10 Nov?", now, FlowPlan.BUILT_IN_QUERY.copy(window = WindowSpec(rules = false)))
        assertFalse(lines(rulesOff).any { it.startsWith("note:") })
    }

    @Test fun theModelFallbackRunsOnlyWhenTheRulesFindNothingAndThePlanAllowsIt() = runBlocking<Unit> {
        mem("c8", "Bursting crackers on the roof", "2026-11-08T21:00:00"); mem("c1", "Crackers in a shop", "2026-11-01T12:00:00")
        val calls = AtomicInteger()
        val llm = WindowResolver { _, _, _ -> calls.incrementAndGet(); TimeWindow(at("2026-11-08T00:00:00"), at("2026-11-09T00:00:00"), "Sun 8 Nov 2026 00:00 - Mon 9 Nov 2026 00:00", "Diwali", TimeWindow.Source.LLM) }
        val s = service(llm)
        val on = FlowPlan.BUILT_IN_QUERY.copy(window = WindowSpec(llmFallback = true))
        val r = s.retrieve("Did I burst crackers on Diwali?", now, on)
        assertEquals(listOf("c8"), ids(r)); assertEquals(1, calls.get())
        assertTrue(lines(r).first(), lines(r).first().startsWith("note: time window Sun 8 Nov 2026 00:00 - Mon 9 Nov 2026 00:00 (\"Diwali\")"))
        s.retrieve("What crackers were there on 1 Nov?", now, on) // the rules read it: no call
        assertEquals(1, calls.get())
        s.retrieve("Did I burst crackers on Diwali?", now, FlowPlan.BUILT_IN_QUERY.copy(window = WindowSpec(llmFallback = false))) // off: no call
        s.retrieve("Did I burst crackers on Diwali?", now) // the built-in plan: no call
        assertEquals(1, calls.get())
        // a resolver that fails is no window, never an error
        assertEquals(2, service(WindowResolver { _, _, _ -> throw IllegalStateException("down") }).retrieve("Did I burst crackers on Diwali?", now, on).memories.size)
        assertNull(null)
    }
}
