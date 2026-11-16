package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.orchestration.forget.ForgetActions
import com.rokid.context.orchestration.nudge.NudgeEngine
import com.rokid.context.orchestration.nudge.NudgeGates
import com.rokid.context.orchestration.nudge.NudgeHud
import com.rokid.context.orchestration.nudge.NudgeInputs
import com.rokid.context.orchestration.nudge.NudgeRun
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Forget lifecycle M3 (T5, closes leak 5): a real Forget (`ForgetActions`, through the gate) runs while the model call of an Ask or a Nudge is suspended. The lease the flow holds is revoked by the Forget, so the flow
 * commits NOTHING (no answer, not even a redacted row; no nudge) and no lease stays open.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AskNudgeForgetRaceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 10, 12, 18, 0).atZone(zone).toInstant().toEpochMilli()
    private var duringModel: suspend () -> Unit = {}
    private var reply = ""
    private var outcome: ForgetActions.Outcome? = null
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = TestAi.graph(ctx) { duringModel(); reply }
        TestAi.bindAll(ai)
    }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun seedMemory() = MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, now - 3_600_000L, "Milk carton nearly empty"))
    private suspend fun asks() = MemoryGraph.get(ctx).db.canvasDao().recentAsks(10)

    @Test fun aFactForgottenWhileTheModelAnswersLeavesNoAskRow() = runBlocking<Unit> {
        seedMemory()
        reply = """{"answer":"There is milk.","sources":["rokid_mem_1"]}"""
        val service = AnswerService(MemoryGraph.get(ctx), ai.runner, ai.retriever, { RetrievalSettings() })
        service.testBeforeCommit = { outcome = ForgetActions.forgetFactOutcome(ctx, "rokid_mem_1") } // the model returned, the Forget commits, then the ask commits
        val err = runCatching { service.answerOrThrow("milk?", "phone_typed", nowMs = now) }.exceptionOrNull()
        assertTrue("the answer is not saved: $err outcome=$outcome", err is NoMemoriesException)
        assertEquals("no answer row of any kind (not even a redacted one)", emptyList<Any>(), asks())
        assertEquals("the lease is closed", 0, DataOpGate.shared.leases.openCount)
    }

    @Test fun anAskWithNoForgetStillSaves() = runBlocking<Unit> {
        seedMemory()
        reply = """{"answer":"There is milk.","sources":["rokid_mem_1"]}"""
        val r = AnswerService(MemoryGraph.get(ctx), ai.runner, ai.retriever, { RetrievalSettings() }).answerOrThrow("milk?", "phone_typed", nowMs = now)
        assertEquals("There is milk.", MemoryGraph.get(ctx).asks.byId(r.askId)!!.answer)
        assertEquals(0, DataOpGate.shared.leases.openCount)
    }

    @Test fun aFactForgottenWhileTheModelWritesANudgeLeavesNoNudgeRow() = runBlocking<Unit> {
        seedMemory()
        reply = """{"shouldNudge":true,"text":"Milk is nearly out","why":"You saw an empty carton","sourceIds":["rokid_mem_1"],"priority":"normal"}"""
        val mem = MemoryGraph.get(ctx)
        val engine = NudgeEngine(NudgeInputs(mem, ContextSourceProvider { _, _ -> emptyList() }) { zone }, ai.runner, NudgeGates(mem.db.nudgeGateDao(), { 0L }) { 6 }, mem,
            ProposalIntake { _, _, _, _ -> "p1" }, clock = { now }, hud = { NudgeHud(null, Verbosity.MEDIUM, zone) })
        engine.testBeforeCommit = { outcome = ForgetActions.forgetFactOutcome(ctx, "rokid_mem_1") }
        val run = engine.runOnce(); assertEquals("outcome=$outcome", NudgeRun.Forgotten, run)
        assertEquals("no nudge row", emptyList<Any>(), mem.nudges.recent(10).first())
        assertEquals(0, DataOpGate.shared.leases.openCount)
    }

    /** The real interleaving: the Forget starts while the model call holds the upload slot (so it waits for it) and races the flow's commit. Whoever wins, no forgotten text survives and no lease is left. */
    @Test fun aForgetThatStartsDuringTheModelCallNeverLeavesAnAnswerBuiltFromTheForgottenFact() = runBlocking<Unit> {
        seedMemory()
        reply = """{"answer":"There is milk.","sources":["rokid_mem_1"]}"""
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        var forget: kotlinx.coroutines.Deferred<ForgetActions.Outcome>? = null
        duringModel = { forget = scope.async { ForgetActions.forgetFactOutcome(ctx, "rokid_mem_1") }; kotlinx.coroutines.delay(200) } // it is parked behind the call's upload slot
        runCatching { AnswerService(MemoryGraph.get(ctx), ai.runner, ai.retriever, { RetrievalSettings() }).answerOrThrow("milk?", "phone_typed", nowMs = now) }
        assertEquals(ForgetActions.Outcome.DONE, kotlinx.coroutines.withTimeout(20_000) { forget!!.await() })
        val rows = asks()
        assertTrue("either nothing was saved or the row is redacted: $rows", rows.all { it.redacted && it.answer != "There is milk." })
        assertEquals(0, DataOpGate.shared.leases.openCount)
    }
}
