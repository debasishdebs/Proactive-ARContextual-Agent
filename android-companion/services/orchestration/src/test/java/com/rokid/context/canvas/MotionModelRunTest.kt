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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

/** `src.watch_motion -> agg.window -> proc.motion_model -> gate.compare / route.switch` through the REAL runner: the tuning is in the model's params, no model call. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MotionModelRunTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val ring = WatchMotionRing()
    private val label: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }
    private val modelCalls = java.util.concurrent.atomic.AtomicInteger()

    @Before fun setUp() { h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    private fun runner() = CanvasRunner(
        h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) },
        mapOf(NodeTypes.AGG_WINDOW to WindowAggregateExecutor, NodeTypes.PROC_MOTION_MODEL to MotionModelExecutor, NodeTypes.COMPARE to CompareExecutor, NodeTypes.SWITCH to SwitchExecutor),
        { id ->
            val ai = com.rokid.context.orchestration.TestAi.graph(app) { modelCalls.incrementAndGet(); "" }
            RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) })
        },
        h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m }, watch = WatchRunSource { f, t -> ring.recent(f, t) },
    )
    private fun pipeline(threshold: Double, hysteresis: Double) = Pipeline(2, "mp", "M", listOf(
        PipelineNode("watch", NodeTypes.SRC_WATCH_MOTION, "Watch", JsonObject(emptyMap())),
        PipelineNode("agg", NodeTypes.AGG_WINDOW, "Window", buildJsonObject { put("feature", "watchMotion"); put("op", "max"); put("window", 1) }),
        PipelineNode("model", NodeTypes.PROC_MOTION_MODEL, "Motion model", buildJsonObject { put("kind", "heuristic"); put("threshold", threshold); put("hysteresis", hysteresis) }),
        PipelineNode("cmp", NodeTypes.COMPARE, "Compare", buildJsonObject { put("operator", ">="); put("cutoff", 0.5) }),
        PipelineNode("sw", NodeTypes.SWITCH, "Switch", buildJsonObject { put("field", "label"); put("cases", JsonArray(listOf(JsonPrimitive("moving"), JsonPrimitive("still")))) }),
    ), listOf(PipelineEdge("watch", "agg"), PipelineEdge("agg", "model"), PipelineEdge("model", "cmp", null), PipelineEdge("model", "sw")))

    private suspend fun runIt(p: Pipeline): List<com.rokid.context.memory.db.CanvasRunItemEntity> {
        val r = runner()
        val e = r.estimateWatch(p, 0, 10_000, label) as Estimate.Ok
        assertEquals(0, e.nominal)
        val id = r.startWatch(p, "mp", null, 0, 10_000, e.confirmToken, h.scope); h.finish(r)
        assertEquals("DONE", h.dao.run(id)!!.status); assertEquals(0, modelCalls.get())
        return h.dao.items(id)
    }
    private fun labels(items: List<com.rokid.context.memory.db.CanvasRunItemEntity>) =
        listOf(1000L, 2000L, 3000L, 4000L).map { at -> Json.parseToJsonElement(items.first { it.nodeId == "model" && it.frameId == "watch:$at" }.outputJson!!).jsonObject.getValue("value").jsonObject.getValue("label").jsonPrimitive.content }

    /** still, running (1.0), in vehicle (0.2: below the 0.4 threshold, at the exit 0.2), still */
    private fun values() { listOf(1000L to WatchState.STILL, 2000L to WatchState.RUNNING, 3000L to WatchState.IN_VEHICLE, 4000L to WatchState.STILL).forEach { (a, s) -> ring.add(WatchSample(a, s, 1f)) } }

    @Test fun theThresholdAloneDecidesWithoutHysteresis() = runBlocking<Unit> {
        values()
        assertEquals(listOf("still", "moving", "still", "still"), labels(runIt(pipeline(0.4, 0.0))))
    }

    @Test fun hysteresisHoldsMovingThroughTheDipAndTheTuningChangesTheAnswer() = runBlocking<Unit> {
        values()
        val items = runIt(pipeline(0.4, 0.2))
        assertEquals(listOf("still", "moving", "moving", "still"), labels(items))
        // gate.compare on the 1.0 / 0.0 `value` and route.switch on the `label` both follow the decision
        assertEquals(listOf("no", "yes", "yes", "no"), listOf(1000L, 2000L, 3000L, 4000L).map { at -> items.first { it.nodeId == "cmp" && it.frameId == "watch:$at" }.branch })
        assertEquals(listOf("still", "moving", "moving", "still"), listOf(1000L, 2000L, 3000L, 4000L).map { at -> items.first { it.nodeId == "sw" && it.frameId == "watch:$at" }.branch })
    }

    @Test fun anEmptyWindowStaysNoDataAllTheWayNotAStillLabel() = runBlocking<Unit> {
        val items = runIt(pipeline(0.4, 0.0))
        assertEquals("NO_DATA", items.single { it.nodeId == "watch" }.error)
        assertTrue("no label is made from nothing", items.none { it.nodeId == "model" })
    }
}
