package com.rokid.context.ai.canvas

import com.rokid.context.ai.resolve.ModelNodeBackend
import com.rokid.context.ai.resolve.NodeBackendPref
import com.rokid.context.core.net.Backend
import com.rokid.context.core.net.ModelPrivacy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelNodesTest {
    @After fun reset() { NodeTypes.modelNodesEnabled = { false } }

    private fun p(vararg kv: Pair<String, String>) = buildJsonObject { kv.forEach { (k, v) -> put(k, v) } }
    private fun classify(labels: String) = PipelineNode("c", ModelNodeTypes.CLASSIFY, "c", p("labels" to labels))

    @Test fun offByDefaultAndFailsClosed() {
        assertNull(NodeTypes.byType(ModelNodeTypes.CLASSIFY))
        assertNull(NodeTypes.resolve(ModelNodeTypes.RULE_TABLE, 2))
        assertEquals(NodeTypes.all, NodeTypes.available())
        NodeTypes.modelNodesEnabled = { error("prefs unreadable") }
        assertNull(NodeTypes.byType(ModelNodeTypes.ROUTE))
    }

    @Test fun onOffersTheThreeTypesNotLiveable() {
        NodeTypes.modelNodesEnabled = { true }
        assertEquals(NodeTypes.all.size + 3, NodeTypes.available().size)
        for (t in ModelNodeTypes.all) { assertFalse(t.liveable); assertNotNull(NodeTypes.resolve(t.type, 2)); assertNull(NodeTypes.resolve(t.type, 1)) }
    }

    @Test fun branchesAreLabelsPlusNone() {
        NodeTypes.modelNodesEnabled = { true }
        assertEquals(listOf("indoor", "outdoor", "none"), NodeTypes.byType(ModelNodeTypes.CLASSIFY)!!.branches(p("labels" to "Indoor, outdoor")))
        assertEquals(listOf("none"), NodeTypes.byType(ModelNodeTypes.CLASSIFY)!!.branches(p("labels" to "just one")))
    }

    @Test fun validatorChecksLabelsAndRows() {
        NodeTypes.modelNodesEnabled = { true }
        assertTrue(ModelNodeTypes.paramProblems("c", classify("a,b")).isEmpty())
        assertEquals(1, ModelNodeTypes.paramProblems("c", classify("a")).size)
        assertEquals(1, ModelNodeTypes.paramProblems("c", classify("a,a")).size)
        assertEquals(1, ModelNodeTypes.paramProblems("c", classify("a,none")).size)
        assertEquals(1, ModelNodeTypes.paramProblems("c", classify((1..13).joinToString(",") { "l$it" })).size)
        val rt = { rows: String -> PipelineNode("r", ModelNodeTypes.RULE_TABLE, "r", p("rows" to rows)) }
        assertTrue(ModelNodeTypes.paramProblems("r", rt("""[{"field":"a","op":">=","value":1,"branch":"hi"}]""")).isEmpty())
        assertEquals(1, ModelNodeTypes.paramProblems("r", rt("not json")).size)
        assertEquals(1, ModelNodeTypes.paramProblems("r", rt("""[{"field":"a","op":"~","value":1,"branch":"hi"}]""")).size)
        assertEquals(1, ModelNodeTypes.paramProblems("r", rt("""[{"field":"a","op":"==","value":1,"branch":"other"}]""")).size)
    }

    @Test fun normalizeApplesThresholdAndUnknownLabels() {
        val labels = listOf("indoor", "outdoor")
        fun n(json: String, th: Double = 0.6) = ClassifySpec.normalize(kotlinx.serialization.json.Json.parseToJsonElement(json), labels, th)!!
        assertEquals("indoor", n("""{"label":"Indoor","confidence":0.9}""").label)
        assertEquals("none", n("""{"label":"indoor","confidence":0.5}""").label)
        assertTrue(n("""{"label":"indoor","confidence":0.5}""").belowThreshold)
        assertEquals("none", n("""{"label":"street","confidence":0.99}""").label)
        assertEquals("none", n("""{"label":"indoor"}""").label)
        assertEquals(1.0, n("""{"label":"indoor","confidence":7}""").confidence, 0.0)
        assertNull(ClassifySpec.normalize(JsonPrimitive("x"), labels, 0.6))
    }

    @Test fun noIdentityEverLeavesTheNode() {
        val v = ClassifySpec.normalize(kotlinx.serialization.json.Json.parseToJsonElement("""{"label":"indoor","confidence":0.9,"name":"Alice","person":"Bob"}"""), listOf("indoor", "outdoor"), 0.6)!!
        val out = ClassifySpec.toJson(v).toString()
        assertEquals(setOf("label", "confidence"), ClassifySpec.toJson(v).keys)
        assertFalse(out.contains("Alice") || out.contains("Bob"))
        assertTrue(ClassifySpec.instruction(listOf("a", "b"), null).contains("never name, identify"))
        assertTrue(ClassifySpec.schema(listOf("a", "b")).toString().contains("\"additionalProperties\":false"))
    }

    @Test fun ruleTableFirstMatchWins() {
        val rows = RuleTable.parse(p("rows" to """[
          {"field":"confidence","op":">=","value":0.9,"branch":"sure"},
          {"field":"label","op":"in","value":["indoor","outdoor"],"branch":"place"},
          {"field":"meta.tags.0","op":"contains","value":"cat","branch":"cat"},
          {"field":"gone","op":"exists","branch":"has"}]"""))!!
        fun pick(j: String) = RuleTable.pick(rows, kotlinx.serialization.json.Json.parseToJsonElement(j))
        assertEquals("sure", pick("""{"confidence":0.95,"label":"indoor"}"""))
        assertEquals("place", pick("""{"confidence":0.5,"label":"indoor"}"""))
        assertEquals("cat", pick("""{"meta":{"tags":["Big CAT"]}}"""))
        assertEquals("has", pick("""{"gone":1}"""))
        assertEquals("other", pick("""{"x":1}"""))
        assertEquals("other", pick("""{"confidence":"high"}"""))
        assertEquals(listOf("sure", "place", "cat", "has", "other"), RuleTable.branches(p("rows" to """[{"field":"a","op":"exists","branch":"sure"},{"field":"a","op":"exists","branch":"place"},{"field":"a","op":"exists","branch":"cat"},{"field":"a","op":"exists","branch":"has"}]""")))
    }

    @Test fun backendIntersectsWithPrivacyFailClosed() {
        val c = ModelNodeBackend::check
        assertEquals("offline_only", c(NodeBackendPref.ONLINE, ModelPrivacy.OFFLINE_ONLY, Backend.ONLINE))
        assertEquals("offline_only", c(NodeBackendPref.AUTO, ModelPrivacy.OFFLINE_ONLY, Backend.ONLINE))
        assertEquals("offline_only_no_local_model", c(NodeBackendPref.OFFLINE, ModelPrivacy.OFFLINE_ONLY, Backend.ONLINE))
        assertEquals("offline_only_no_local_model", c(NodeBackendPref.OFFLINE, ModelPrivacy.AUTO, Backend.ONLINE))
        assertEquals("backend_mismatch", c(NodeBackendPref.ONLINE, ModelPrivacy.AUTO, Backend.OFFLINE))
        assertEquals("not_bound", c(NodeBackendPref.AUTO, ModelPrivacy.AUTO, null))
        assertNull(c(NodeBackendPref.AUTO, ModelPrivacy.OFFLINE_ONLY, Backend.OFFLINE))
        assertNull(c(NodeBackendPref.AUTO, ModelPrivacy.AUTO, Backend.ONLINE))
        assertEquals(NodeBackendPref.AUTO, NodeBackendPref.parse("garbage"))
    }
}
