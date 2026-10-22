package com.rokid.context.capture.sweep

import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanState
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SweepPlannerTest {
    private val base = PlanResolver.defaults()
    private fun nums(path: String, vararg v: Number) = SweepParam(path, v.map { JsonPrimitive(it) })
    private fun grid(vararg p: SweepParam) = SweepSpec(SweepMode.GRID, p.toList())
    private fun refused(spec: SweepSpec): String = assertFailsWith<IllegalArgumentException> { SweepPlanner.points(base, spec) }.message!!

    @Test fun gridIsTheCartesianProductInOrder() {
        val pts = SweepPlanner.points(base, grid(nums("gate.hamming", 8, 10), nums("gate.bigChange", 20, 22, 24)))
        assertEquals(6, pts.size)
        assertEquals(listOf(8 to 20, 8 to 22, 8 to 24, 10 to 20, 10 to 22, 10 to 24), pts.map { it.config.gate.hamming to it.config.gate.bigChange })
        assertEquals(List(6) { it }, pts.map { it.index })
        assertEquals(6, pts.map { it.configHash }.distinct().size)
        pts.forEach { assertEquals(PlanJson.configHash(it.config), it.configHash) }
    }

    /** A value equal to the base's gives exactly the base's hash (identity is exact). */
    @Test fun aPointEqualToTheBaseHasTheBaseHash() {
        val pts = SweepPlanner.points(base, grid(nums("gate.hamming", base.gate.hamming)))
        assertEquals(PlanJson.configHash(base), pts.single().configHash)
    }

    /** S83/S84: timer photo gaps are sweepable per state, 0 (inherit) or 5 to 300 s. */
    @Test fun sweepGapUpTo300s() {
        val pts = SweepPlanner.points(base, grid(nums("policy.REST.timerPhotoGapsMs.settledMs", 0, 5_000, 120_000, 300_000)))
        assertEquals(listOf(0L, 5_000L, 120_000L, 300_000L), pts.map { it.config.policy.getValue(PlanState.REST).timerPhotoGapsMs.settledMs })
        assertTrue(refused(grid(nums("policy.REST.timerPhotoGapsMs.settledMs", 300_001))).contains("timer photo gaps must be 0"))
        assertTrue(refused(grid(nums("policy.FLIGHT.timerPhotoGapsMs.stillCapMs", 4_999))).contains("timer photo gaps must be 0"))
        // the other rows and the other three gaps are untouched
        val r = pts.last().config.policy.getValue(PlanState.REST)
        assertEquals(base.policy.getValue(PlanState.REST).timerPhotoGapsMs.quietMs, r.timerPhotoGapsMs.quietMs)
        assertEquals(base.policy.getValue(PlanState.FLIGHT), pts.last().config.policy.getValue(PlanState.FLIGHT))
    }

    @Test fun sweepSensingKeyRefused() {
        for (k in listOf("sampleRateHz", "sensorDelay", "imuGyroHz", "tickMs", "fusionCadenceMs", "evaluateEveryMs", "detectorThreshold", "classifierMode")) {
            for (path in listOf(k, "glance.$k", "policy.REST.$k")) {
                assertEquals("$k: sensing and detection are not plan parameters", refused(grid(nums(path, 1))), path)
            }
        }
    }

    @Test fun otherPathsAreRefusedWithFixedTexts() {
        assertEquals("phoneFusion.stillFrac: fusion is not sweepable", refused(grid(nums("phoneFusion.stillFrac", 0.5))))
        assertEquals("fusion.fastKmh: fusion is not sweepable", refused(grid(nums("fusion.fastKmh", 30))))
        assertEquals("policy row WALKING is locked", refused(grid(nums("policy.WALKING.capPerMin", 2))))
        assertEquals("policy row UNKNOWN is locked", refused(grid(nums("policy.UNKNOWN.timerPhotoGapsMs.settledMs", 20_000))))
        assertEquals("glance.nope: not a sweepable param", refused(grid(nums("glance.nope", 1))))
        assertEquals("ctxBound: not a sweepable param", refused(grid(nums("ctxBound", 1))))
        assertEquals("policy.REST.pause: not a sweepable param", refused(grid(nums("policy.REST.pause", 1))))
    }

    @Test fun boundsAreTheValidators() {
        assertTrue(refused(grid(nums("glance.onsetRadS", 1.1))).contains("glance.onsetRadS out of bounds"))
        assertTrue(refused(grid(nums("gate.hamming", 5))).contains("gate.hamming out of bounds"))
        assertTrue(refused(grid(nums("policy.REST.capPerMin", 5))).contains("capPerMin must be"))
        assertTrue(refused(grid(SweepParam("gate.ringCompare", listOf(JsonPrimitive("nope"))))).contains("ringCompare"))
        assertEquals(2, SweepPlanner.points(base, grid(SweepParam("gate.ringCompare", listOf(JsonPrimitive("last"), JsonPrimitive("all"))))).size)
        // a blank capPerMin means "unchanged"
        assertEquals(null, SweepPlanner.points(base, grid(SweepParam("policy.REST.capPerMin", listOf(kotlinx.serialization.json.JsonNull)))).single().config.policy.getValue(PlanState.REST).capPerMin)
    }

    @Test fun atMostFourParamsAndTwoHundredConfigs() {
        val five = (1..5).map { nums(listOf("glance.settleMs", "glance.bridgeMs", "glance.minBoutMs", "gate.hamming", "gate.bigChange")[it - 1], 1_000 + it) }
        assertEquals("at most 4 params", refused(grid(*five.toTypedArray())))
        val four = SweepPlanner.points(base, grid(nums("gate.hamming", 8, 9, 10, 11, 12), nums("gate.bigChange", 20, 21, 22, 23), nums("glance.settleMs", 1_000, 1_500), nums("glance.bridgeMs", 200, 250, 300, 350, 400)))
        assertEquals(200, four.size)                                       // 5 * 4 * 2 * 5 = 200 is allowed
        assertEquals("at most 200 configs", refused(grid(nums("gate.hamming", 6, 7, 8, 9, 10, 11), nums("gate.bigChange", 20, 21, 22, 23, 24, 25), nums("glance.settleMs", 1_000, 1_500, 2_000, 2_500, 3_000), nums("glance.bridgeMs", 200, 250))))
        assertEquals("duplicate param", refused(grid(nums("gate.hamming", 8), nums("gate.hamming", 9))))
        assertEquals("no params", refused(grid()))
        assertEquals("a param needs at least one value", refused(grid(SweepParam("gate.hamming", emptyList()))))
    }

    @Test fun randomIsSeededAndBounded() {
        val ps = arrayOf(nums("gate.hamming", 6, 8, 10, 12, 14, 16), nums("glance.settleMs", 1_000, 1_500, 2_000, 2_500, 3_000))
        fun run(seed: Long, n: Int = 12) = SweepPlanner.points(base, SweepSpec(SweepMode.RANDOM, ps.toList(), samples = n, seed = seed))
        assertEquals(run(7).map { it.configHash }, run(7).map { it.configHash })
        assertNotEquals(run(7).map { it.configHash }, run(8).map { it.configHash })
        assertTrue(run(7).size in 1..12)
        assertEquals(run(7).map { it.configHash }.distinct(), run(7).map { it.configHash })           // duplicates are dropped
        assertEquals("samples must be 1 to 200", assertFailsWith<IllegalArgumentException> { run(1, 0) }.message)
        assertEquals("samples must be 1 to 200", assertFailsWith<IllegalArgumentException> { run(1, 201) }.message)
        run(1, 200)
    }

    @Test fun rangesExpandWithoutDrift() {
        assertEquals(listOf(0.4, 0.45, 0.5, 0.55, 0.6), SweepPlanner.range(0.4, 0.6, 0.05).map { it.content.toDouble() })
        assertEquals(listOf(5_000L, 10_000L, 15_000L), SweepPlanner.range(5_000.0, 15_000.0, 5_000.0).map { it.content.toLong() })
        assertFailsWith<IllegalArgumentException> { SweepPlanner.range(1.0, 2.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { SweepPlanner.range(1.0, 100_000.0, 1.0) }
    }

    @Test fun theCandidateCarriesTheConfig() {
        val p = SweepPlanner.points(base, grid(nums("gate.hamming", 12), nums("glance.settleMs", 2_000), nums("policy.REST.timerPhotoGapsMs.settledMs", 90_000))).single()
        val c = SweepPlanner.candidate(p)
        assertEquals(p.configHash, c.configHash)
        assertEquals(12, c.gate!!.hamming); assertEquals(2_000L, c.glance!!.settleMs)
        assertEquals(90_000L, c.table!![com.rokid.context.core.model.context.ContextState.REST]!!.minSettledCheckMs)
        assertEquals(base.policy.getValue(PlanState.FLIGHT).timerPhotoGapsMs.settledMs, c.table!![com.rokid.context.core.model.context.ContextState.FLIGHT]!!.minSettledCheckMs)
    }
}
