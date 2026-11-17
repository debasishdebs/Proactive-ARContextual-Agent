package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.AiGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.AskSourceStates
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.orchestration.forget.ReanswerWorker
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
 * Forget lifecycle M5 (rulings 2026-11-17), end to end through the normal Ask path: the ask row records its CITED sources as source states (ids only), and with the OFF-by-default `forgetV2` a Forget of an input that
 * was only retrieved leaves the answer alone, a Forget of a cited source turns it into "Answered from a forgotten fact" (question kept), and "Ask again" (the user's tap, never automatic) saves a NEW answer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AskForgetStatesTest {
    @get:org.junit.Rule val pcLogShadow = com.rokid.context.PcLogToShadowRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var reply: suspend (String) -> String = { """{"answer":"Milk is almost empty.","sources":["rokid_mem_1"]}""" }
    private var calls = 0

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        val ai = TestAi.graph(ctx) { p -> calls++; reply(p) }
        TestAi.bindAll(ai)
    }
    @After fun tearDown() { ForgetV2.testOverride = null; AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun remember(id: String, text: String, at: Long) = MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory(id, "short_term", 0.9, at, text))
    private fun graph() = MemoryGraph.get(ctx)
    private suspend fun forget(id: String) { val g = graph(); ForgetService(g.db, g.proposals).forgetFact(id) }

    private suspend fun askMilk(): String {
        remember("rokid_mem_1", "Milk carton nearly empty in the fridge", 10); remember("rokid_mem_2", "Milk shelf was cleaned on Monday", 20)
        return (AskActions.ask(ctx, "milk?", nowMs = 99) as AskOutcome.Answered).askId
    }

    @Test fun theAskRowRecordsItsCitedSourcesAsStatesAndKeepsTheStoredJsonAsToday() = runBlocking {
        val id = askMilk()
        val a = graph().asks.byId(id)!!
        val s = AskSourceStates.parse(a.sourceStates)!!
        assertEquals(AskSourceStates.LIVE, s.state); assertEquals(listOf("rokid_mem_1"), s.cited); assertTrue(s.history.isEmpty())
        assertEquals("the legacy JSON still lists every retrieved input (flag OFF behaves as before)", listOf("rokid_mem_1"), AskSources.parse(a.sourcesJson))
        assertTrue("rokid_mem_2 is a stored dependency (not cited)", "rokid_mem_2" in AskSources.parseAll(a.sourcesJson))
        assertFalse(a.sourceStates!!.contains("milk") || a.sourceStates!!.contains("Milk"))
    }

    @Test fun flagOnForgettingAnUncitedInputAlsoErasesTheAnswerButKeepsTheQuestion() = runBlocking {
        ForgetV2.testOverride = true
        val id = askMilk()
        forget("rokid_mem_2") // retrieved, never cited: still a dependency (proxy ruling F4, conservative)
        val a = graph().asks.byId(id)!!
        assertTrue(a.redacted); assertEquals(ForgetService.REDACTED, a.answer); assertEquals("milk?", a.question)
        assertTrue(AskSourceStates.isForgotten(a))
    }

    @Test fun flagOnForgettingTheCitedInputErasesTheAnswerButKeepsTheQuestion() = runBlocking {
        ForgetV2.testOverride = true
        val id = askMilk()
        forget("rokid_mem_1")
        val a = graph().asks.byId(id)!!
        assertTrue(a.redacted); assertEquals(ForgetService.REDACTED, a.answer); assertEquals("milk?", a.question)
        assertTrue(AskSourceStates.isForgotten(a))
    }

    @Test fun flagOffBothForgetsRedactInPlaceAsToday() = runBlocking {
        ForgetV2.testOverride = false
        val id = askMilk()
        forget("rokid_mem_2")
        val a = graph().asks.byId(id)!!
        assertTrue("v1: every stored dependency redacts", a.redacted); assertFalse("and the new state is never written", AskSourceStates.isForgotten(a))
    }

    @Test fun askAgainRunsTheStoredQuestionThroughTheNormalPathAndMarksTheOldRow() = runBlocking {
        ForgetV2.testOverride = true
        val id = askMilk(); forget("rokid_mem_1")
        remember("rokid_mem_3", "Milk bought on Tuesday", 30)
        reply = { """{"answer":"You bought milk on Tuesday.","sources":["rokid_mem_3"]}""" }
        val before = calls
        assertEquals("no automatic re-answer: the Forget made no model call", before, calls)
        val out = ReanswerWorker.reask(ctx, id) as AskOutcome.Answered
        assertEquals(before + 1, calls)
        val fresh = graph().asks.byId(out.askId)!!
        assertEquals("milk?", fresh.question); assertEquals("You bought milk on Tuesday.", fresh.answer); assertFalse(fresh.redacted)
        val old = graph().asks.byId(id)!!
        assertTrue(AskSourceStates.isReasked(old)); assertEquals(out.askId, AskSourceStates.parse(old.sourceStates)?.reaskedAs); assertTrue(old.redacted)
        assertNull("a second tap finds nothing to ask", ReanswerWorker.askableQuestion(ctx, id))
        assertTrue(ReanswerWorker.reask(ctx, id) is AskOutcome.Failed)
    }

    @Test fun askAgainRefusesALiveAnswerAndAFailedAskKeepsTheButton() = runBlocking {
        ForgetV2.testOverride = true
        val id = askMilk()
        assertTrue("a live answer is not asked again", ReanswerWorker.reask(ctx, id) is AskOutcome.Failed)
        forget("rokid_mem_1")
        assertTrue(ReanswerWorker.reask(ctx, id) { AskOutcome.Failed("offline") } is AskOutcome.Failed)
        assertEquals("a failed ask changes nothing", "milk?", ReanswerWorker.askableQuestion(ctx, id))
    }

    @Test fun aDeveloperEvalAskHasNoStatesAndIsNeverAskedAgain() = runBlocking {
        remember("rokid_mem_1", "Milk carton nearly empty in the fridge", 10)
        val r = AnswerService.forApp(ctx).answer("milk?", AskActions.EVAL_SOURCE, 99) as AskOutcome.Answered
        val a = graph().asks.byId(r.askId)!!
        assertNull(a.sourceStates)
        assertNull(ReanswerWorker.askableQuestion(ctx, r.askId))
    }
}
