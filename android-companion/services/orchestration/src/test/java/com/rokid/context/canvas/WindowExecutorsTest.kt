package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** `agg.window` (a deterministic phone-side Aggregate) and `gate.compare` (the Condition that reads its number): no model, one result per item. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WindowExecutorsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private var modelCalls = 0

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(app)) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private val noPhoto = ExtractPhoto("", "", ModelImageInput("image/jpeg", ""))
    private fun item(id: String, v: CanvasValue) = CanvasItem(id, noPhoto, v)
    private fun scene(i: Int, gyro: Double?) = item("scene:S$i", CanvasValue.Json(buildJsonObject {
        put("itemType", "scene"); put("text", "t$i")
        if (gyro != null) putJsonObject("sensor") { put("seconds", 5); put("gyroMean", gyro) }
    }))
    private fun ctx(): RunContext {
        val ai = TestAi.graph(app) { modelCalls++; "" }.also { TestAi.bindAll(it) }
        return RunContext("run1", ai.runner, ai.router, ai.prompts, ai.persona, { r -> ai.bindings.get(r) }, liveness = com.rokid.context.orchestration.AllLive)
    }
    private fun agg(vararg kv: Pair<String, Any>) = PipelineNode("agg", NodeTypes.AGG_WINDOW, "Window", buildJsonObject { kv.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v as String) } })
    private fun cmp(op: String? = null, cut: Double? = null) = PipelineNode("cmp", NodeTypes.COMPARE, "Compare", buildJsonObject { op?.let { put("operator", it) }; cut?.let { put("cutoff", it) } })
    private fun exec(t: String) = checkNotNull(Executors.all()[t])
    private fun num(r: NodeResult) = ((r.value as CanvasValue.Json).value.jsonObject["value"] as JsonPrimitive).content.toDouble()

    @Test fun aSlidingMeanOverTheItemsInRunOrderWithoutAnyModelCall() = runBlocking<Unit> {
        val r = exec(NodeTypes.AGG_WINDOW).run(agg("feature" to "gyroMean", "op" to "mean", "window" to 2), listOf(scene(1, 1.0), scene(2, 3.0), scene(3, 5.0), scene(4, 2.0)), ctx())
        assertEquals(listOf("scene:S1", "scene:S2", "scene:S3", "scene:S4"), r.map { it.frameId })
        assertEquals(listOf(1.0, 2.0, 4.0, 3.5), r.map(::num))
        val j = (r[3].value as CanvasValue.Json).value.jsonObject
        assertEquals(listOf("gyroMean", "mean", "2", "2"), listOf("feature", "op", "window", "n").map { j.getValue(it).jsonPrimitive.content })
        assertTrue(r.all { it.error == null && it.calls == 0 && it.meta == null }); assertEquals(0, modelCalls)
    }

    @Test fun absentParamsAreTheDefaultsAndMinMaxCountAreSelectable() = runBlocking<Unit> {
        val items = listOf(scene(1, 1.0), scene(2, 3.0), scene(3, 2.0))
        assertEquals(listOf(1.0, 2.0, 2.0), exec(NodeTypes.AGG_WINDOW).run(agg(), items, ctx()).map(::num)) // gyroMean, mean, window 10
        assertEquals(listOf(1.0, 1.0, 1.0), exec(NodeTypes.AGG_WINDOW).run(agg("op" to "min"), items, ctx()).map(::num))
        assertEquals(listOf(1.0, 3.0, 3.0), exec(NodeTypes.AGG_WINDOW).run(agg("op" to "max"), items, ctx()).map(::num))
        assertEquals(listOf(1.0, 2.0, 3.0), exec(NodeTypes.AGG_WINDOW).run(agg("op" to "count"), items, ctx()).map(::num))
    }

    @Test fun anItemWithNoSensorSummaryAddsNothingAndAnEmptyWindowSaysNoData() = runBlocking<Unit> {
        val moment = item("moment:M1", CanvasValue.Json(buildJsonObject { put("itemType", "moment"); put("sensor", JsonNull) }))
        val r = exec(NodeTypes.AGG_WINDOW).run(agg("window" to 1), listOf(moment, scene(2, 0.5)), ctx())
        assertEquals(listOf("NO_DATA", null), r.map { it.error }); assertNull(r[0].value); assertEquals(0.5, num(r[1]), 0.0)
        val c = exec(NodeTypes.AGG_WINDOW).run(agg("op" to "count", "window" to 1), listOf(moment), ctx()).single()
        assertNull(c.error); assertEquals(0.0, num(c), 0.0)
    }

    @Test fun onlyJsonItemsAreAggregated() = runBlocking<Unit> {
        val r = exec(NodeTypes.AGG_WINDOW).run(agg(), listOf(item("p", CanvasValue.Photo), scene(1, 1.0)), ctx())
        assertEquals(listOf("BAD_INPUT", null), r.map { it.error })
        assertEquals("a bad item does not enter the window of the next one", 1.0, num(r[1]), 0.0)
    }

    @Test fun costIsZeroAndTheWholeRunArrivesInOneChunk() {
        val e = exec(NodeTypes.AGG_WINDOW)
        assertEquals(0 to 0, e.nominalCalls(agg(), 9) to e.maxCalls(agg(), 9))
        assertTrue("the window needs every item of the run at once", e.chunkSize(agg()) >= CanvasRunner.MAX_PHOTOS)
        assertEquals(0 to 0, exec(NodeTypes.COMPARE).nominalCalls(cmp(), 9) to exec(NodeTypes.COMPARE).maxCalls(cmp(), 9))
    }

    private fun number(v: Double) = item("scene:S", CanvasValue.Json(buildJsonObject { put("value", v); put("n", 1) }))

    @Test fun compareBranchesYesWhenTheOperatorHoldsAndPassesTheNumberThrough() = runBlocking<Unit> {
        suspend fun branch(op: String?, cut: Double?, v: Double) = exec(NodeTypes.COMPARE).run(cmp(op, cut), listOf(number(v)), ctx()).single()
        assertEquals("yes", branch(null, null, 0.5).branch)          // defaults: >= 0.5
        assertEquals("no", branch(null, null, 0.49).branch)
        assertEquals("no", branch(">", 0.5, 0.5).branch)
        assertEquals("yes", branch(">", 0.5, 0.51).branch)
        assertEquals("yes", branch("<=", 0.5, 0.5).branch)
        assertEquals("no", branch("<", 0.5, 0.5).branch)
        assertEquals("yes", branch("<", -2.0, -3.0).branch)
        val r = branch(">=", 0.2, 0.9)
        assertEquals(number(0.9).value, r.value); assertNull(r.error); assertEquals(0, r.calls)
    }

    @Test fun compareNeedsANumberAndSaysBadInputOtherwise() = runBlocking<Unit> {
        val bad = listOf(
            item("a", CanvasValue.Photo), item("b", CanvasValue.Json(buildJsonObject { put("other", 1) })),
            item("c", CanvasValue.Json(buildJsonObject { put("value", "0.9") })), item("d", CanvasValue.Json(buildJsonObject { put("value", JsonNull) })),
        )
        val r = exec(NodeTypes.COMPARE).run(cmp(), bad, ctx())
        assertEquals(List(4) { "BAD_INPUT" }, r.map { it.error }); assertTrue(r.all { it.branch == null && it.value == null })
    }
}
