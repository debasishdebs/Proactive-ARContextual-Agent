package com.rokid.context.canvas.window

import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.CanvasItem
import com.rokid.context.canvas.CanvasValue
import com.rokid.context.canvas.CanvasValueJson
import com.rokid.context.canvas.NodeExecutor
import com.rokid.context.canvas.NodeResult
import com.rokid.context.canvas.RunContext
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.ExtractPhoto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedOutputsTest {
    private val params = buildJsonObject { put("prompt", "p1") }
    private val node = PipelineNode("n1", NodeTypes.PROMPT, "t", params)
    private val ctx: RunContext = run {
        val uc = Class.forName("sun.misc.Unsafe")
        val f = uc.getDeclaredField("theUnsafe"); f.isAccessible = true
        uc.getMethod("allocateInstance", Class::class.java).invoke(f.get(null), RunContext::class.java) as RunContext
    }
    private fun item(id: String, v: CanvasValue = CanvasValue.Json(JsonPrimitive("in-$id"))) = CanvasItem(id, ExtractPhoto(id, "d", ModelImageInput("image/jpeg", "AAAA")), v)
    private fun outJson(s: String) = CanvasValueJson.encode(CanvasValue.Json(JsonPrimitive(s)))

    private inner class Inner : NodeExecutor {
        var liveCalls = 0
        override val type = NodeTypes.PROMPT
        override fun nominalCalls(node: PipelineNode, items: Int) = items
        override fun maxCalls(node: PipelineNode, items: Int) = items
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> { liveCalls += items.size; return items.map { NodeResult(it.frameId, CanvasValue.Json(JsonPrimitive("live")), calls = 1) } }
    }

    private class Src(val rows: Map<String, RecordedRow>, val alive: Boolean = true) : RecordedSource {
        override suspend fun available() = alive
        override suspend fun recorded(frameId: String, nodeId: String) = rows[frameId]
    }

    private fun rec(id: String, pv: Int? = 3, model: String? = "remote:X", p: JsonObject = params, branch: String? = "pass") = RecordedRow(
        CanvasRunItemEntity("src", id, "n1", "OK", branch, outJson("rec-$id"), null, 5, 1, 1, model, pv, null, 1), CanvasValueJson.encode(CanvasValue.Json(JsonPrimitive("in-$id"))), NodeTypes.PROMPT, p)

    private fun exec(inner: NodeExecutor, src: RecordedSource, live: Boolean = false, pv: Int? = 3, model: String? = "remote:X") = RecordedOutputsExecutor(inner, src, live) { pv to model }

    @Test fun hitServesRecordedWithZeroCalls() = runBlocking {
        val inner = Inner()
        val r = exec(inner, Src(mapOf("a" to rec("a")))).run(node, listOf(item("a")), ctx)
        assertEquals(0, r[0].calls); assertEquals("pass", r[0].branch)
        assertEquals(CanvasValue.Json(JsonPrimitive("rec-a")), r[0].value)
        assertEquals(0, inner.liveCalls)
    }

    @Test fun changedParamsPromptVersionOrModelIsAMiss() = runBlocking {
        val src = Src(mapOf("a" to rec("a")))
        val changed = PipelineNode("n1", NodeTypes.PROMPT, "t", buildJsonObject { put("prompt", "p2") })
        assertEquals("not_recorded", exec(Inner(), src).run(changed, listOf(item("a")), ctx)[0].error)
        assertEquals("not_recorded", exec(Inner(), src, pv = 4).run(node, listOf(item("a")), ctx)[0].error)
        assertEquals("not_recorded", exec(Inner(), src, model = "device:L").run(node, listOf(item("a")), ctx)[0].error)
        assertEquals("not_recorded", exec(Inner(), src).run(node, listOf(item("a", CanvasValue.Json(JsonPrimitive("different")))), ctx)[0].error)
    }

    @Test fun paramKeyOrderDoesNotMatter() {
        val a = buildJsonObject { put("x", 1); put("y", 2) }; val b = buildJsonObject { put("y", 2); put("x", 1) }
        assertEquals(InputKey.of("t", a, 1, "m", "u"), InputKey.of("t", b, 1, "m", "u"))
    }

    @Test fun missOnMissLiveCallsOnlyTheMisses() = runBlocking {
        val inner = Inner()
        val r = exec(inner, Src(mapOf("a" to rec("a"))), live = true).run(node, listOf(item("a"), item("b")), ctx)
        assertEquals(0, r[0].calls); assertEquals(1, r[1].calls); assertEquals(1, inner.liveCalls)
    }

    @Test fun prunedOrForgottenSourceIsRefusedAndNeverCallsLive() = runBlocking {
        val inner = Inner()
        val r = exec(inner, Src(emptyMap(), alive = false), live = true).run(node, listOf(item("a")), ctx)
        assertEquals("replay_source_gone", r[0].error); assertNull(r[0].value); assertEquals(0, inner.liveCalls)
    }

    @Test fun onlyModelNodesAreWrapped() {
        assertTrue(RecordedOutputsExecutor.wrapsType(NodeTypes.PROMPT))
        assertFalse(RecordedOutputsExecutor.wrapsType(NodeTypes.SWITCH))
        assertFalse(RecordedOutputsExecutor.wrapsType(NodeTypes.COMPARE))
        assertFalse(RecordedOutputsExecutor.wrapsType(NodeTypes.AGG_WINDOW))
    }

    private fun row(f: String, n: String, out: String?, branch: String? = null, status: String = "OK") = CanvasRunItemEntity("r", f, n, status, branch, out, null, null, null, null, null, null, null)

    @Test fun determinismReportNamesFirstDriftedNode() {
        val src = listOf(row("f1", "a", "1"), row("f1", "b", "2"), row("f2", "a", "1"), row("f2", "b", "2"), row("f3", "a", "1"), row("f3", "b", "2"))
        val new = listOf(row("f1", "a", "1"), row("f1", "b", "2"), row("f2", "a", "1"), row("f2", "b", "X"), row("f3", "a", "Y"), row("f3", "b", "2"))
        val rep = DeterminismReport.of(src, new, listOf("a", "b"))
        assertEquals(3, rep.items); assertEquals(1, rep.identical)
        assertEquals(mapOf("a" to 1, "b" to 1), rep.driftedNodes)
        assertEquals("1/3 items identical; 2 drifted at node a", rep.line)
        assertEquals("1/1 items identical", DeterminismReport.of(src.take(2), src.take(2), listOf("a", "b")).line)
    }
}
