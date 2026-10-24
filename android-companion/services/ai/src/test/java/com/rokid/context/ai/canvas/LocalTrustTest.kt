package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parameterised trust rule of `gate.local_critic` (composite node, phase A). */
class LocalTrustTest {
    private val none = JsonObject(emptyMap())
    private fun p(agg: String? = null, op: String? = null, cut: Double? = null) = buildJsonObject {
        agg?.let { put("aggregate", it) }; op?.let { put("operator", it) }; cut?.let { put("cutoff", it) }
    }
    private fun d(c: List<Double>, params: JsonObject = none) = LocalTrust.decide(c, params)

    @Test fun defaultsAreTodaysRule() {
        assertEquals(0.70, LocalTrust.DEFAULT_CUTOFF, 0.0)
        assertEquals("mean", LocalTrust.DEFAULT_AGGREGATE)
        assertEquals(">=", LocalTrust.DEFAULT_OPERATOR)
        assertTrue(d(listOf(0.7)))
        assertFalse(d(listOf(Math.nextDown(0.7))))
    }

    @Test fun noFactsIsAlwaysTrustedWhateverTheParams() {
        assertTrue(d(emptyList()))
        assertTrue(d(emptyList(), p("min", ">", 1.0)))
    }

    @Test fun minAndMaxChangeTheBranch() {
        val facts = listOf(0.9, 0.5) // mean 0.7
        assertTrue(d(facts))
        assertFalse(d(facts, p(agg = "min")))
        assertTrue(d(facts, p(agg = "max")))
        assertFalse(d(listOf(0.9, 0.1), p(agg = "mean")))
        assertTrue(d(listOf(0.9, 0.1), p(agg = "max")))
    }

    @Test fun cutoffMovesTheBranch() {
        assertFalse(d(listOf(0.75), p(cut = 0.8)))
        assertTrue(d(listOf(0.75), p(cut = 0.5)))
        assertTrue(d(listOf(0.0), p(cut = 0.0)))
    }

    @Test fun strictOperatorExcludesTheCutoffItself() {
        assertTrue(d(listOf(0.7), p(op = ">=")))
        assertFalse(d(listOf(0.7), p(op = ">")))
        assertTrue(d(listOf(0.71), p(op = ">")))
    }

    @Test fun missingOrBadParamsFallBackToDefaults() {
        assertEquals(LocalTrust.Settings(), LocalTrust.settings(none))
        val bad = buildJsonObject { put("aggregate", "median"); put("operator", "<"); put("cutoff", "high") }
        assertEquals(LocalTrust.Settings(), LocalTrust.settings(bad))
        assertEquals(LocalTrust.Settings(cutoff = 0.5), LocalTrust.settings(buildJsonObject { put("cutoff", JsonPrimitive(0.5)) }))
    }
}
