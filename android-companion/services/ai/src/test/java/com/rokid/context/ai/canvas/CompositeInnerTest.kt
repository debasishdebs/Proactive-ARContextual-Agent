package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `inner` registry description and the new `gate.local_critic` params. */
class CompositeInnerTest {
    private val lc = NodeTypes.byType(NodeTypes.LOCAL_CRITIC)!!
    private fun problems(params: JsonObject) = ParamCheck.problems("c", lc, params)

    /** Phase A: the local critic; phase B adds the async black boxes; phase C the glasses lane (Threshold and Guard are plain, hub.context is phase D). */
    @Test fun theCompositeTypesAreTheOnesBuiltSoFar() {
        assertEquals(setOf(NodeTypes.LOCAL_CRITIC, NodeTypes.VISION_EXTRACT, NodeTypes.CRITIC, NodeTypes.MEMORY_WRITE, NodeTypes.POLICY_CAPTURE_RATE,
            NodeTypes.IMU_HEAD_MOTION, NodeTypes.IMU_SETTLE, NodeTypes.GATE_NOVELTY, NodeTypes.GATE_RATE_CAP, NodeTypes.CAPTURE_IMAGE), NodeTypes.all.filter { it.inner != null }.map { it.type }.toSet())
    }

    @Test fun everyInnerFlowIsWellFormed() {
        for (t in NodeTypes.all) {
            val f = t.inner ?: continue
            val ids = f.nodes.map { it.id }
            assertEquals("${t.type}: node ids unique", ids.size, ids.toSet().size)
            for (w in f.wires) { assertTrue("${t.type}: wire from ${w.from}", w.from in ids); assertTrue("${t.type}: wire to ${w.to}", w.to in ids) }
            // a type without a ParamSchema keeps its descriptors in the backend (CanvasBackendImplTest checks the served names); here only the owner box
            val names = t.params.specs.map { it.name }
            if (names.isNotEmpty()) {
                for (e in f.exposed) assertTrue("${t.type}: exposed ${e.param} is a param", e.param in names)
                assertEquals("every param is exposed", names.toSet(), f.exposed.map { it.param }.toSet())
            }
            for (e in f.exposed) assertTrue("${t.type}: owner ${e.node}", e.node in ids)
        }
    }

    @Test fun localCriticKeepsItsBranchesAndGainsParams() {
        assertEquals(listOf("trusted", "unsure"), lc.branches(JsonObject(emptyMap())))
        assertEquals(listOf("aggregate", "operator", "cutoff"), lc.params.specs.map { it.name })
        assertNotNull(lc.inner)
        val inner = lc.inner!!
        assertEquals(setOf("trusted", "unsure"), inner.nodes.filter { it.kind == "branch" }.map { it.id }.toSet())
    }

    @Test fun defaultsComeFromTheOneSource() {
        val by = lc.params.specs.associateBy { it.name }
        assertEquals(JsonPrimitive(LocalTrust.DEFAULT_CUTOFF), by.getValue("cutoff").default)
        assertEquals(JsonPrimitive(LocalTrust.DEFAULT_AGGREGATE), by.getValue("aggregate").default)
        assertEquals(JsonPrimitive(LocalTrust.DEFAULT_OPERATOR), by.getValue("operator").default)
        assertEquals(LocalTrust.AGGREGATES, by.getValue("aggregate").options)
        assertEquals(LocalTrust.OPERATORS, by.getValue("operator").options)
    }

    @Test fun savedPipelinesWithoutTheParamsStillValidate() {
        assertEquals(emptyList<String>(), problems(JsonObject(emptyMap())))
        assertEquals(emptyList<String>(), problems(buildJsonObject { put("aggregate", "min"); put("operator", ">"); put("cutoff", 0.0) }))
    }

    @Test fun badValuesAreRefused() {
        assertTrue(problems(buildJsonObject { put("cutoff", 1.5) }).single().contains("cutoff must be between 0 and 1"))
        assertTrue(problems(buildJsonObject { put("cutoff", -0.1) }).isNotEmpty())
        assertTrue(problems(buildJsonObject { put("cutoff", "high") }).isNotEmpty())
        assertTrue(problems(buildJsonObject { put("aggregate", "median") }).single().contains("one of mean, min, max"))
        assertTrue(problems(buildJsonObject { put("operator", "<") }).single().contains("one of >=, >"))
    }

    @Test fun plainNodesHaveNoInner() {
        assertNull(NodeTypes.byType(NodeTypes.SWITCH)!!.inner)
    }

    private val vx = NodeTypes.byType(NodeTypes.VISION_EXTRACT)!!.inner!!

    @Test fun factsFromFramesShowsItsRealStepsInOrderAndExposesOnlyTheBatch() {
        assertEquals(listOf("frames", "batch", "prompt", "model", "retry", "parse"), vx.nodes.map { it.id })
        assertEquals(listOf("batch"), vx.exposed.map { it.param })
        assertEquals("batch", vx.exposed.single().node)
        assertEquals(ExtractComposite.DEFAULT_BATCH, 1)
        assertTrue("the read-only prompt is said so", vx.note.any { it.contains("prompt") && it.contains("inspector") })
        assertTrue("every wire is between known boxes", vx.wires.all { w -> vx.nodes.any { it.id == w.from } && vx.nodes.any { it.id == w.to } })
    }

    @Test fun theLocalCriticHasNoNoteAndTheExtractTypeKeepsItsParamsPlain() {
        assertEquals(emptyList<String>(), lc.inner!!.note)
        // no ParamSchema was added: the type's validation stays the PipelineValidator's own batch / prompt checks
        assertEquals(ParamSchema.NONE, NodeTypes.byType(NodeTypes.VISION_EXTRACT)!!.params)
    }

    @Test fun criticShowsItsFixedShortcutsAndBranchesReadOnly() {
        val t = NodeTypes.byType(NodeTypes.CRITIC)!!
        val c = t.inner!!
        assertEquals(emptyList<InnerExposed>(), c.exposed)
        assertEquals(t.branches(JsonObject(emptyMap())).toSet(), c.nodes.filter { it.kind == "branch" }.map { it.id }.toSet())
        assertEquals(ParamSchema.NONE, t.params)
        // the model call is only reached after both shortcuts: any facts, then a bound model
        val into = c.wires.filter { it.to == "model" || it.to == "prompt" }.map { it.from }
        assertEquals(listOf("bound", "prompt"), into)
        assertTrue(c.wires.any { it.from == "any" && it.to == "pass" && it.port == "none" })
        assertTrue(c.wires.any { it.from == "bound" && it.to == "fail" && it.port == "no" })
        assertTrue(c.note.any { it.contains("Read only") && it.contains("inspector") })
    }

    @Test fun saveMemoryShowsItsRealRowRulesReadOnlyAndSaysThereIsNoDedupe() {
        val t = NodeTypes.byType(NodeTypes.MEMORY_WRITE)!!
        val m = t.inner!!
        assertEquals(emptyList<InnerExposed>(), m.exposed)
        assertEquals(ParamSchema.NONE, t.params)
        val labels = m.nodes.associate { it.id to it.label }
        assertTrue(labels.getValue("factRow").contains("SHORT")); assertTrue(labels.getValue("captionRow").contains("LONG"))
        // the review state feeds the fact row only: captions never wait for review
        assertTrue(m.wires.any { it.from == "review" && it.to == "factRow" })
        assertTrue(m.wires.none { it.from == "review" && it.to == "captionRow" })
        assertTrue(m.note.any { it.contains("no duplicate or novelty check") })
        assertTrue(m.note.any { it.contains("Preview only") })
        assertEquals(setOf("preview"), m.nodes.filter { it.kind == "branch" }.map { it.id }.toSet())
    }
}
