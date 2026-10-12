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
}
