package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.DefaultPipelines
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.core.model.context.WatchMotionRing
import com.rokid.context.core.model.context.WatchSample
import com.rokid.context.core.model.context.WatchState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The built-in `starter-watch-motion` RUNS over a window of the hub's watch values through the real runner and the real executor map: no model call, a frequency preview per value. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StarterWatchRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val ring = WatchMotionRing()
    private val label: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }
    private val modelCalls = java.util.concurrent.atomic.AtomicInteger()

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    private fun runner() = CanvasRunner(
        h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) }, Executors.all(),
        { id ->
            val ai = com.rokid.context.orchestration.TestAi.graph(app) { modelCalls.incrementAndGet(); "" }
            RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive)
        },
        h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m }, watch = WatchRunSource { f, t -> ring.recent(f, t) },
    )

    @Test fun aWalkRaisesTheCaptureRatePreviewAndAStillWristLowersIt() = runBlocking<Unit> {
        listOf(1000L to WatchState.STILL, 2000L to WatchState.WALKING, 3000L to WatchState.RUNNING, 4000L to WatchState.STILL).forEach { (a, s) -> ring.add(WatchSample(a, s, 1f)) }
        val p = DefaultPipelines.starterWatchMotion()
        val r = runner()
        val e = r.estimateWatch(p, 0, 10_000, label) as Estimate.Ok
        assertEquals("watch", e.kind); assertEquals(4, e.photos); assertEquals(0, e.nominal)
        val id = r.startWatch(p, p.id, null, 0, 10_000, e.confirmToken, h.scope); h.finish(r)
        assertEquals("DONE", h.dao.run(id)!!.status); assertEquals(0, modelCalls.get())
        val items = h.dao.items(id)
        assertEquals("only the watch chain ran: the glasses nodes produced nothing", setOf("watch", "win", "model", "freq"), items.map { it.nodeId }.toSet())
        // max of the last 5 values: 0, 0.5, 1.0, 1.0 -> moving from the second value on, and it stays so (the window still holds the run)
        val change = listOf(1000L, 2000L, 3000L, 4000L).map { at -> Json.parseToJsonElement(items.first { it.nodeId == "freq" && it.frameId == "watch:$at" }.outputJson!!).jsonObject.getValue("value").jsonObject.getValue("label").jsonPrimitive.content }
        assertEquals(listOf("still", "moving", "moving", "moving"), change)
        assertTrue(items.all { it.status == "OK" })
    }
}
