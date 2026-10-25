package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.core.model.context.WatchMotionRing
import com.rokid.context.core.model.context.WatchSample
import com.rokid.context.core.model.context.WatchState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** `src.watch_motion -> agg.window -> gate.compare` through the REAL runner: the hub's watch values feed a window in time order, no model, no photo, nothing stored but canvas run rows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WatchRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val ring = WatchMotionRing()
    private val label: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }
    private val modelCalls = java.util.concurrent.atomic.AtomicInteger()

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    private fun runner() = CanvasRunner(
        h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) },
        mapOf(NodeTypes.AGG_WINDOW to WindowAggregateExecutor, NodeTypes.COMPARE to CompareExecutor),
        { id ->
            val ai = com.rokid.context.orchestration.TestAi.graph(app) { modelCalls.incrementAndGet(); "" }
            RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) })
        },
        h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m }, watch = WatchRunSource { f, t -> ring.recent(f, t) },
    )
    private fun pipeline(op: String = "max", window: Int = 2) = Pipeline(2, "wp", "W", listOf(
        PipelineNode("watch", NodeTypes.SRC_WATCH_MOTION, "Watch", JsonObject(emptyMap())),
        PipelineNode("agg", NodeTypes.AGG_WINDOW, "Window", buildJsonObject { put("feature", "watchMotion"); put("op", op); put("window", window) }),
        PipelineNode("cmp", NodeTypes.COMPARE, "Compare", buildJsonObject { put("operator", ">="); put("cutoff", 0.5) }),
    ), listOf(PipelineEdge("watch", "agg"), PipelineEdge("agg", "cmp")))

    private fun put(vararg v: Pair<Long, WatchState>) = v.forEach { (at, st) -> ring.add(WatchSample(at, st, 1f)) }
    private suspend fun runAll(r: CanvasRunner, p: Pipeline, from: Long = 0, to: Long = 10_000): String {
        val e = r.estimateWatch(p, from, to, label) as Estimate.Ok
        assertEquals("nothing here calls a model", 0, e.nominal); assertEquals(0, e.max); assertTrue(e.models.isEmpty())
        val id = r.startWatch(p, "wp", null, from, to, e.confirmToken, h.scope)
        h.finish(r); return id
    }
    private fun num(it: com.rokid.context.memory.db.CanvasRunItemEntity) = Json.parseToJsonElement(it.outputJson!!).jsonObject.getValue("value").jsonObject.getValue("value").jsonPrimitive.double

    @Test fun aSlidingMaxOverTheHubsValuesThenAYesOrNoPerValue() = runBlocking<Unit> {
        put(1000L to WatchState.STILL, 2000L to WatchState.WALKING, 3000L to WatchState.RUNNING, 4000L to WatchState.STILL)
        val id = runAll(runner(), pipeline())
        assertEquals("DONE", h.dao.run(id)!!.status)
        val items = h.dao.items(id)
        fun agg(at: Long) = items.first { it.nodeId == "agg" && it.frameId == "watch:$at" }
        assertEquals(listOf(0.0, 0.5, 1.0, 1.0), listOf(1000L, 2000L, 3000L, 4000L).map { num(agg(it)) })
        assertEquals(listOf("no", "yes", "yes", "yes"), listOf(1000L, 2000L, 3000L, 4000L).map { at -> items.first { it.nodeId == "cmp" && it.frameId == "watch:$at" }.branch })
        assertEquals(0, modelCalls.get()); assertEquals(0, h.dao.run(id)!!.calls)
    }

    @Test fun onlyTheValuesOfTheWindowAreRead() = runBlocking<Unit> {
        put(1000L to WatchState.RUNNING, 5000L to WatchState.STILL, 6000L to WatchState.STILL)
        val id = runAll(runner(), pipeline(), from = 4000, to = 9000)
        assertEquals(setOf("watch:5000", "watch:6000"), h.dao.items(id).map { it.frameId }.toSet())
    }

    @Test fun anEmptyWindowIsOneNoDataItemNotAZero() = runBlocking<Unit> {
        val id = runAll(runner(), pipeline())
        val items = h.dao.items(id)
        assertEquals(listOf("watch:none"), items.map { it.frameId }.distinct())
        assertEquals("NO_DATA", items.single { it.nodeId == "watch" }.error)
        assertTrue("nothing flows on from an error", items.none { it.nodeId == "agg" || it.nodeId == "cmp" })
    }

    @Test fun aValueThatArrivedAfterTheEstimateRefusesTheStart() = runBlocking<Unit> {
        put(1000L to WatchState.WALKING)
        val r = runner(); val p = pipeline()
        val e = r.estimateWatch(p, 0, 10_000, label) as Estimate.Ok
        put(2000L to WatchState.RUNNING)
        val err = runCatching { r.startWatch(p, "wp", null, 0, 10_000, e.confirmToken, h.scope) }.exceptionOrNull()
        assertTrue(err is CanvasRunner.WatchMoved)
    }

    @Test fun aPhotoOrMomentRunRefusesAWatchPipelineAndAWatchRunRefusesOthers() = runBlocking<Unit> {
        val r = runner()
        assertTrue(r.estimate(pipeline(), listOf("f1"), label) is Estimate.Refused)
        val noWatch = Pipeline(2, "x", "X", listOf(PipelineNode("m", NodeTypes.INPUT_MOMENTS, "m", JsonObject(emptyMap()))), emptyList())
        val e = r.estimateWatch(noWatch, 0, 10, label)
        assertTrue(e is Estimate.Refused && e.reasons.any { it.contains("does not take watch") })
        assertTrue(r.estimateWatch(pipeline(), 10, 0, label) is Estimate.Refused)
    }
}
