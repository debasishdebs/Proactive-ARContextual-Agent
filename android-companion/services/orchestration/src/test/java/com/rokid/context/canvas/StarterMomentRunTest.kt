package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.moment.DaoMomentSource
import com.rokid.context.canvas.moment.MomentId
import com.rokid.context.canvas.moment.MomentResolver
import com.rokid.context.memory.db.FrameEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** The built-in `starter-moment-summary` RUNS on two Moments through the real runner: Prompt (Moments in), Summary, Confidence check, Save memory (preview), model calls counted first. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StarterMomentRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val sec = 1_761_000_000L
    private val ms = sec * 1000
    private val m0 = MomentId.format(sec)
    private val m1 = MomentId.format(sec + 5)
    private val label: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }
    private val prompts = CopyOnWriteArrayList<String>()

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    private suspend fun frame(id: String, ts: Long) = h.db.frameDao().insertIgnore(FrameEntity(id, ts, "/$id.jpg", "rokid_glasses"))
    private fun reply(p: String) = if ("Summarise the data below" in p) """{"summary":"A desk moment.","confidence":0.9}""" else """{"description":"Two photos at a desk."}"""
    private fun runner() = CanvasRunner(
        h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) },
        Executors.all(),
        { id ->
            val ai = com.rokid.context.orchestration.TestAi.graph(app) { p -> prompts += p; reply(p) }.also { com.rokid.context.orchestration.TestAi.bindAll(it) }
            RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive)
        },
        h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m },
        moments = MomentResolver(DaoMomentSource(h.dao, h.dao3)),
    )

    @Test fun twoMomentsGoThroughThePromptAndTheSummaryToTheSavePreview() = runBlocking<Unit> {
        frame("fA", ms + 10); frame("fB", ms + 900); frame("fC", ms + 5000)
        val p = DefaultPipelines.starterMomentSummary()
        val r = runner()
        val e = r.estimateMoments(p, listOf(m0, m1), label) as Estimate.Ok
        assertEquals("moments", e.kind); assertEquals("a Prompt and a Summary call per Moment", 4, e.nominal)
        assertEquals(setOf("ask", "sum"), e.models.map { it.nodeId }.toSet())
        val id = r.startMoments(p, p.id, null, listOf(m0, m1), e.confirmToken, h.scope)
        h.finish(r)
        assertEquals("DONE", h.dao.run(id)!!.status); assertEquals(4, h.dao.run(id)!!.calls); assertEquals(4, prompts.size)
        val items = h.dao.items(id)
        for (n in listOf("moments", "ask", "sum", "trust", "write")) assertEquals(n, listOf("moment:$m0", "moment:$m1"), items.filter { it.nodeId == n }.map { it.frameId }.sorted())
        assertTrue(items.all { it.status == "OK" })
        assertEquals(listOf("trusted", "trusted"), items.filter { it.nodeId == "trust" }.map { it.branch })
        assertTrue(items.first { it.nodeId == "write" }.outputJson!!.contains("A desk moment."))
        assertTrue("the Summary read the Prompt's JSON, not the raw Moment", prompts.filter { "Summarise the data below" in it }.all { "Two photos at a desk." in it })
    }
}
