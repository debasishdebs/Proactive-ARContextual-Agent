package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import com.rokid.context.glasses.capture.session.GlanceAdmission
import com.rokid.context.glasses.capture.motion.GlanceParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 3c X3: the glasses runner of lowered guard programs (guards only). */
class ExprRunnerTest {
    private fun entry(id: String, s: String, out: Int): JsonObject =
        Json.parseToJsonElement("""{"id":"$id","role":"guard","out":$out,"s":[$s]}""").jsonObject

    /** `gyroMax > T` */
    private fun gyroGt(t: String, id: String = "g1") =
        entry(id, """{"op":"sig","name":"gyroMax"},{"op":"const","v":$t},{"op":"cmp","f":">","a":0,"b":1}""", 2)
    private val div0 = entry("d", """{"op":"const","v":1},{"op":"const","v":0},{"op":"arith","f":"/","a":0,"b":1},{"op":"const","v":0},{"op":"cmp","f":">","a":2,"b":3}""", 4)
    private val nonFinite = entry("n", """{"op":"const","v":1.0E308},{"op":"const","v":10},{"op":"arith","f":"*","a":0,"b":1},{"op":"const","v":0},{"op":"cmp","f":">","a":2,"b":3}""", 4)
    private val alwaysDrop = entry("x", """{"op":"const","v":false}""", 0)

    private fun feats(gyroMax: Float) = SecondFeatures(gyroMax, 0f, 0f, 0f, 0f, 0f, 0f)
    private fun sig(g: Float, at: Long = 1_000) = ExprSignals().also { it.set(feats(g), at) }
    private val ctx = ContextState.UNKNOWN

    @Test fun guardFalseDropsAndTrueKeeps() {
        val r = ExprRunner.of(listOf(gyroGt("0.5")))!!
        assertFalse(r.keep(2_000, sig(0.2f), ctx, 0))
        assertEquals(1, r.drops(0))
        assertTrue(r.keep(3_000, sig(0.9f), ctx, 0))
        assertEquals(1, r.drops(0))
    }

    @Test fun comparisonIsStrict() {
        val r = ExprRunner.of(listOf(gyroGt("0.5")))!!
        assertFalse(r.keep(2_000, sig(0.5f), ctx, 0), "0.5 > 0.5 is false: dropped")
    }

    @Test fun inSetMapsStateNamesToWireCodes() {
        val e = entry("c", """{"op":"sig","name":"ctx"},{"op":"in","a":0,"set":["REST","VEHICLE_PASSENGER"]}""", 1)
        val r = ExprRunner.of(listOf(e))!!
        val s = sig(0f)
        assertTrue(r.keep(2_000, s, ContextState.REST, 0))
        assertTrue(r.keep(3_000, s, ContextState.VEHICLE_PASSENGER, 0))
        assertFalse(r.keep(4_000, s, ContextState.WALKING, 0))
        assertFalse(r.keep(5_000, s, ContextState.UNKNOWN, 0))
    }

    @Test fun headSpeedAliasAndCtxAgeRead() {
        val e = entry("a", """{"op":"sig","name":"headSpeed"},{"op":"sig","name":"ctxAgeS"},{"op":"const","v":10},{"op":"cmp","f":"<","a":1,"b":2},{"op":"const","v":1},{"op":"cmp","f":">=","a":0,"b":4},{"op":"and","a":3,"b":5}""", 6)
        val r = ExprRunner.of(listOf(e))!!
        assertTrue(r.keep(2_000, sig(1f), ctx, 5))
        assertFalse(r.keep(3_000, sig(1f), ctx, 10), "ctxAgeS 10 is not < 10")
    }

    @Test fun guardErrorKeepsCandidateTest() {
        val r = ExprRunner.of(listOf(div0, nonFinite))!!
        assertTrue(r.keep(2_000, sig(1f), ctx, 0), "an ERROR never drops")
        assertEquals(1, r.exprDiv0); assertEquals(1, r.exprErr); assertEquals(1L, r.errTotal)
        assertEquals(0, r.totalDrops)
    }

    @Test fun unavailableKeepsAndIsNotAnError() {
        val r = ExprRunner.of(listOf(gyroGt("0.5")))!!
        assertTrue(r.keep(2_000, ExprSignals(), ctx, 0), "no features yet")
        assertTrue(r.keep(9_000, sig(0.1f, at = 1_000), ctx, 0), "features older than 3 s are stale")
        assertEquals(0, r.exprErr); assertEquals(0, r.totalDrops)
    }

    @Test fun twoEvaluationsPerSecondAtMost() {
        val r = ExprRunner.of(listOf(gyroGt("0.5")))!!
        val s = sig(0.1f, at = 1_900)
        assertFalse(r.keep(2_000, s, ctx, 0))
        assertEquals(1, r.drops(0))
        repeat(10) { assertFalse(r.keep(2_001 + it * 40L, s, ctx, 0)) }   // inside 500 ms: the previous verdict, no new evaluation
        assertEquals(1, r.drops(0))
        assertFalse(r.keep(2_500, s, ctx, 0))
        assertEquals(2, r.drops(0))
    }

    @Test fun malformedProgramGivesNoRunner() {
        assertNull(ExprRunner.of(listOf(entry("b", """{"op":"cmp","f":">","a":3,"b":1}""", 0))))
        assertNull(ExprRunner.of(emptyList()))
        assertNotNull(ExprRunner.of(listOf(alwaysDrop)))
    }

    @Test fun NoAllocationOnEventTest() {
        val r = ExprRunner.of(listOf(gyroGt("0.5"), div0, nonFinite, entry("c", """{"op":"sig","name":"ctx"},{"op":"in","a":0,"set":["REST"]}""", 1)))!!
        val s = sig(0.9f, at = 0)
        var t = 0L
        val f = feats(0.9f)   // preallocated: the probe measures the runner, not the test's own SecondFeatures
        fun event() { t += 600; s.set(f, t); r.keep(t, s, ctx, 7) }
        repeat(20_000) { event() }
        val bytes = AllocProbe.bytes { repeat(100_000) { event() } }
        assertTrue(bytes < 8_192, "allocated $bytes bytes")
    }

    // ---- the admission hook ----

    private val g = GlanceParams()
    private fun admit(guardKeep: Boolean, since: Long = 60_000, kept: Int = 0, cap: Int = 3, turned: Float = 0f, walked: Boolean = false) =
        GlanceAdmission.decide(since, kept, cap, false, turned, walked, g, guardKeep)

    @Test fun GuardDropsOnlyTest() {
        val grid = listOf(
            admit(true), admit(true, since = 100), admit(true, kept = 5), admit(true, since = 100, turned = 3f), admit(true, kept = 5, walked = true),
        )
        // a keeping guard changes nothing: same answer as the three-way test alone
        assertEquals(
            listOf(GlanceAdmission.Decision.OPEN, GlanceAdmission.Decision.NOVEL_SKIP, GlanceAdmission.Decision.CAPPED),
            grid.take(3),
        )
        // keep is not force-capture: a capped candidate stays capped, a novelty skip stays skipped
        assertEquals(GlanceAdmission.Decision.CAPPED, admit(true, kept = 99))
        assertEquals(GlanceAdmission.Decision.NOVEL_SKIP, admit(true, since = 0))
        // a false guard only ever drops: never OPEN, whatever else would have admitted
        assertEquals(GlanceAdmission.Decision.GUARD_DROP, admit(false))
        assertEquals(GlanceAdmission.Decision.GUARD_DROP, admit(false, kept = 99))
        assertEquals(GlanceAdmission.Decision.GUARD_DROP, admit(false, since = 0))
    }

    @Test fun guardDefaultsToKeepSoCallersAreUnchanged() {
        assertEquals(GlanceAdmission.decide(60_000, 0, 3, false, 0f, false, g), admit(true))
    }
}
