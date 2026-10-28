package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP7: the `py.fn` registry entry. It is an ASYNC code node that is never liveable and never part of a v1 graph; it has no executor here (WP8 owns `PythonExecutor`). */
class NodeTypesPyFnTest {
    @Test fun pyFnIsAsyncCodeNonLiveable() {
        val t = NodeTypes.byType("py.fn")
        assertNotNull("py.fn is registered", t)
        t!!
        assertEquals(Domain.ASYNC, t.domain); assertEquals(Category.CODE, t.category); assertEquals(ExecutorKind.PYTHON, t.executor)
        assertFalse(t.liveable); assertTrue("offered in the palette now that the helper UI exists (WP9)", t.palette)
        assertEquals(0, t.cost.modelCalls); assertEquals(0, t.cost.rtOps)
        assertEquals(setOf(PortType.JSON, PortType.MOMENTS, PortType.SCENES), t.inputs); assertEquals(PortType.JSON, t.output)
        assertEquals(setOf("source", "outSchema"), t.params.specs.map { it.name }.toSet())
        assertEquals(16000, t.params.specs.first { it.name == "source" }.maxLen)
    }

    /** The palette entry: a clear label, a hint that names the laptop helper, the per-run tap and the never-published rule; the S6 flags are unchanged. */
    @Test fun paletteEntryHasLabelHintAndKeepsS6Flags() {
        val t = NodeTypes.byType("py.fn")!!
        assertEquals("Python (experiment)", t.label)
        assertEquals("Code", t.category.label)
        assertFalse(t.liveable)
        for (w in listOf("laptop helper", "confirm", "never published to the glasses")) assertTrue("hint mentions $w: ${t.hint}", t.hint.contains(w))
        assertTrue("short hint", t.hint.length <= 160)
        assertEquals("every other palette type has no hint", emptyList<String>(), NodeTypes.all.filter { it.type != "py.fn" && it.hint.isNotEmpty() }.map { it.type })
        assertEquals("only the photo input and the slice C trigger stubs are hidden", listOf("input.photos", "trigger.moment", "trigger.scene", "trigger.signal", "trigger.schedule"), NodeTypes.all.filter { !it.palette }.map { it.type })
    }

    @Test fun v1SchemaCannotHoldPyFn() {
        assertNull(NodeTypes.resolve("py.fn", 1))
        assertNotNull(NodeTypes.resolve("py.fn", 2))
    }

    @Test fun pyFnIsTierThreeThroughTheRegistryToo() {
        val p = Pipeline(2, "p", "P", listOf(PipelineNode("z", "py.fn", "z", JsonObject(emptyMap()))), emptyList())
        assertEquals(listOf("z"), com.rokid.context.ai.canvas.plan.PlanShape.tier3Nodes(p))
    }

    private fun synthetic(type: String, executor: ExecutorKind = ExecutorKind.BUILTIN, domain: Domain = Domain.ASYNC, since: Int = 1) =
        NodeType(type, type, setOf(PortType.JSON), PortType.JSON, sinceSchema = since, domain = domain, executor = executor)
    private fun graph(schema: Int, type: String) = Pipeline(schema, "p", "P", listOf(PipelineNode("z", type, "z", JsonObject(emptyMap()))), emptyList())
    private fun tier3(schema: Int, d: NodeType) = com.rokid.context.ai.canvas.plan.PlanShape.tier3Nodes(graph(schema, d.type)) { if (it == d.type) d else null }

    /** The registry metadata alone (no `py.`/`tool.` prefix) classifies tier 3: a PYTHON or DRAFT executor, or the REPLAY_ONLY domain. */
    @Test fun metadataAloneClassifiesTierThree() {
        for (d in listOf(synthetic("x.python", executor = ExecutorKind.PYTHON), synthetic("x.draft", executor = ExecutorKind.DRAFT), synthetic("x.replay", domain = Domain.REPLAY_ONLY))) {
            assertEquals(d.type, listOf("z"), tier3(2, d))
        }
    }

    /** A descriptor NEWER than the graph's schema (a schema-1 graph naming a schema-2 type) is still tier 3: the lookup is the unresolved one. */
    @Test fun descriptorNewerThanTheGraphSchemaIsStillTierThree() {
        for (d in listOf(synthetic("x.python", executor = ExecutorKind.PYTHON, since = 2), synthetic("x.draft", executor = ExecutorKind.DRAFT, since = 2), synthetic("x.replay", domain = Domain.REPLAY_ONLY, since = 2))) {
            assertEquals(d.type, listOf("z"), tier3(1, d))
        }
    }

    @Test fun ordinaryDescriptorsAreNotTierThree() {
        for (executor in ExecutorKind.entries.filter { it != ExecutorKind.PYTHON && it != ExecutorKind.DRAFT }) for (domain in listOf(Domain.ASYNC, Domain.RT_GLASSES, Domain.RT_PHONE)) {
            val d = synthetic("x.ok", executor = executor, domain = domain, since = 2)
            assertEquals("$executor $domain", emptyList<String>(), tier3(2, d))
            assertEquals("$executor $domain newer", emptyList<String>(), tier3(1, d))
        }
        assertEquals("an unknown type is not tier 3 by itself", emptyList<String>(), com.rokid.context.ai.canvas.plan.PlanShape.tier3Nodes(graph(2, "x.unknown")) { null })
    }
}
