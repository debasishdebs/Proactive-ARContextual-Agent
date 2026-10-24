package com.rokid.context.canvas

import com.rokid.context.ai.canvas.LocalTrust
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.contract.Fact
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.orchestration.ExtractFollowUp
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Golden: the parameterised rule at its defaults equals today's `ExtractFollowUp.locallyTrusted` for every input (one source of truth for the cutoff). */
class LocalCriticCompositeTest {
    private fun facts(c: List<Double>) = c.map { Fact("f", "object", it, emptyList()) }
    private fun old(c: List<Double>) = ExtractFollowUp.locallyTrusted(CriticWorker(), "id", facts(c))
    private fun new(c: List<Double>) = LocalTrust.decide(c, JsonObject(emptyMap()))

    private val grid = (0..20).map { it / 20.0 } + listOf(0.69, 0.7, 0.71, Math.nextDown(0.7), Math.nextUp(0.7), 0.6999999999999999, 1.0, 0.0, Double.NaN, -1.0, 2.0)

    @Test fun theCutoffIsOneConstant() {
        assertEquals(LocalTrust.DEFAULT_CUTOFF, CriticWorker().confidenceThreshold, 0.0)
        assertEquals(0.70, LocalTrust.DEFAULT_CUTOFF, 0.0)
    }

    @Test fun singlesAndPairsOnTheGridMatchToday() {
        assertEquals(old(emptyList()), new(emptyList()))
        for (a in grid) { assertEquals("[$a]", old(listOf(a)), new(listOf(a))); for (b in grid) assertEquals("[$a,$b]", old(listOf(a, b)), new(listOf(a, b))) }
    }

    @Test fun manyRandomListsMatchToday() {
        val r = Random(20261024)
        repeat(20_000) {
            val c = List(r.nextInt(0, 8)) { if (r.nextInt(4) == 0) grid[r.nextInt(grid.size)] else r.nextDouble() }
            assertEquals(c.toString(), old(c), new(c))
        }
    }

    @Test fun typeIsStillRegisteredWithTheSameBranches() {
        assertEquals(listOf("trusted", "unsure"), NodeTypes.byType(NodeTypes.LOCAL_CRITIC)!!.branches(JsonObject(emptyMap())))
    }
}
