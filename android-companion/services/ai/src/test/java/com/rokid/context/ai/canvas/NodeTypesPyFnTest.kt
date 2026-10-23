package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** WP7: the `py.fn` registry entry. It is an ASYNC code node that is never liveable and never part of a v1 graph; it has no executor here (WP8 owns `PythonExecutor`). */
class NodeTypesPyFnTest {
    @Test fun pyFnIsAsyncCodeNonLiveable() {
        val t = NodeTypes.byType("py.fn")
        assertNotNull("py.fn is registered", t)
        t!!
        assertEquals(Domain.ASYNC, t.domain); assertEquals(Category.CODE, t.category); assertEquals(ExecutorKind.PYTHON, t.executor)
        assertFalse(t.liveable); assertFalse("not offered in the palette until the helper exists (WP9)", t.palette)
        assertEquals(0, t.cost.modelCalls); assertEquals(0, t.cost.rtOps)
        assertEquals(setOf(PortType.JSON), t.inputs); assertEquals(PortType.JSON, t.output)
        assertEquals(setOf("source", "outSchema"), t.params.specs.map { it.name }.toSet())
        assertEquals(16000, t.params.specs.first { it.name == "source" }.maxLen)
    }

    @Test fun v1SchemaCannotHoldPyFn() {
        assertNull(NodeTypes.resolve("py.fn", 1))
        assertNotNull(NodeTypes.resolve("py.fn", 2))
    }

    @Test fun pyFnIsTierThreeThroughTheRegistryToo() {
        val p = Pipeline(2, "p", "P", listOf(PipelineNode("z", "py.fn", "z", JsonObject(emptyMap()))), emptyList())
        assertEquals(listOf("z"), com.rokid.context.ai.canvas.plan.PlanShape.tier3Nodes(p))
    }
}
