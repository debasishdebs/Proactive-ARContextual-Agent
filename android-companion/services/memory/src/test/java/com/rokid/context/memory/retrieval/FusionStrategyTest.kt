package com.rokid.context.memory.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

class FusionStrategyTest {
    private val lex = listOf(Hit("a", 10.0), Hit("b", 5.0), Hit("c", 0.0))
    private val sem = listOf(Hit("b", 0.9), Hit("c", 0.8), Hit("d", 0.1))
    private fun close(want: Map<String, Double>, got: Map<String, Double>) {
        assertEquals(want.keys, got.keys); want.forEach { (k, v) -> assertEquals(k, v, got.getValue(k), 1e-6) }
    }

    @Test fun weightedScoreIsMinMaxNormalised() =
        close(mapOf("a" to 0.4, "b" to 0.8, "c" to 0.525, "d" to 0.0), FusionStrategy.WeightedScore(0.6).fuse(lex, sem))

    @Test fun rrfSumsReciprocalRanks() = close(
        mapOf("a" to 1 / 61.0, "b" to 1 / 62.0 + 1 / 61.0, "c" to 1 / 63.0 + 1 / 62.0, "d" to 1 / 63.0),
        FusionStrategy.Rrf(60).fuse(lex, sem),
    )

    @Test fun combMnzMultipliesByTheNumberOfRetrieversThatHit() =
        close(mapOf("a" to 1.0, "b" to 3.0, "c" to 1.75, "d" to 0.0), FusionStrategy.CombMnz.fuse(lex, sem))

    @Test fun theWeightObservablyReorders() {
        assertEquals("a", FusionStrategy.WeightedScore(0.0).fuse(lex, sem).maxByOrNull { it.value }!!.key)
        assertEquals("b", FusionStrategy.WeightedScore(1.0).fuse(lex, sem).maxByOrNull { it.value }!!.key)
    }

    @Test fun equalScoresNormaliseToOneAndEmptyListsAreFine() {
        assertEquals(mapOf("x" to 1.0, "y" to 1.0), FusionStrategy.minMax(listOf(Hit("x", 2.0), Hit("y", 2.0))))
        assertEquals(emptyMap<String, Double>(), FusionStrategy.CombMnz.fuse(emptyList(), emptyList()))
    }

    @Test fun rrfBreaksEqualScoresById() {
        val f = FusionStrategy.Rrf(60).fuse(listOf(Hit("b", 1.0), Hit("a", 1.0)), emptyList())
        assertEquals(1 / 61.0, f.getValue("a"), 1e-12); assertEquals(1 / 62.0, f.getValue("b"), 1e-12)
    }

    /** R4: "a" has the best lexical score but no vector at all; at full weight it outranks the embedded "b" (0.8), where today it is capped at 0.4. */
    @Test fun aMemoryWithNoVectorKeepsItsFullLexicalWeight() {
        val today = FusionStrategy.WeightedScore(0.6).fuse(lex, sem)
        assertEquals(0.4, today.getValue("a"), 1e-6)
        val fixed = FusionStrategy.WeightedScore(0.6).fuse(lex, sem, lexicalOnly = setOf("a"))
        assertEquals(1.0, fixed.getValue("a"), 1e-6)
        assertEquals("a", fixed.maxByOrNull { it.value }!!.key)
        assertEquals(today.getValue("b"), fixed.getValue("b"), 0.0) // an embedded id is scored as before
        // an id the semantic list DID return is never treated as lexical-only, whatever the set says
        assertEquals(today.getValue("b"), FusionStrategy.WeightedScore(0.6).fuse(lex, sem, setOf("b")).getValue("b"), 0.0)
        // RRF and CombMNZ count the missing semantic vote once more
        assertEquals(2 / 61.0, FusionStrategy.Rrf(60).fuse(lex, sem, setOf("a")).getValue("a"), 1e-9)
        assertEquals(2.0, FusionStrategy.CombMnz.fuse(lex, sem, setOf("a")).getValue("a"), 1e-9)
    }

    @Test fun anEmptyLexicalOnlySetIsTodaysBehaviourForEveryStrategy() {
        for (f in listOf(FusionStrategy.WeightedScore(), FusionStrategy.Rrf(), FusionStrategy.CombMnz)) assertEquals(f.fuse(lex, sem), f.fuse(lex, sem, emptySet()))
    }
}
