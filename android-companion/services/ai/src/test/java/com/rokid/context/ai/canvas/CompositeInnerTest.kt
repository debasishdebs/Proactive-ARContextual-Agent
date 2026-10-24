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

    @Test fun onlyTheLocalCriticIsCompositeInPhaseA() {
        assertEquals(listOf(NodeTypes.LOCAL_CRITIC), NodeTypes.all.filter { it.inner != null }.map { it.type })
    }

    @Test fun everyInnerFlowIsWellFormed() {
        for (t in NodeTypes.all) {
            val f = t.inner ?: continue
            val ids = f.nodes.map { it.id }
            assertEquals("${t.type}: node ids unique", ids.size, ids.toSet().size)
            for (w in f.wires) { assertTrue("${t.type}: wire from ${w.from}", w.from in ids); assertTrue("${t.type}: wire to ${w.to}", w.to in ids) }
            val names = t.params.specs.map { it.name }
            for (e in f.exposed) { assertTrue("${t.type}: exposed ${e.param} is a param", e.param in names); assertTrue("${t.type}: owner ${e.node}", e.node in ids) }
            assertEquals("every param is exposed", names.toSet(), f.exposed.map { it.param }.toSet())
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
}
