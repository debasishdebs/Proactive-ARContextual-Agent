package com.rokid.context.capture.replay

import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.capture.plan.TimerPhotoGaps
import com.rokid.context.capture.replay.ReplayFixtures.START
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.core.model.context.ContextState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 3c E2a: the simulator runs the same value programs as the glasses, through the same row hook (a candidate with its own table). */
class ReplayValueTest {
    private val rest = listOf(TraceEvent.Ctx(START, ContextState.REST))
    private val initial = ReplayFixtures.initial().filter { it !is TraceEvent.Ctx } + rest

    private fun value(id: String, field: String, s: String, row: String = "REST", out: Int = 0): JsonObject =
        Json.parseToJsonElement("""{"id":"$id","role":"value","row":"$row","field":"$field","out":$out,"s":[$s]}""").jsonObject
    private fun const(field: String, v: String) = value("v", field, """{"op":"const","v":$v}""")

    private val plain = ReplayCandidate.policyTable(mapOf(PlanState.REST to PlanResolver.defaults().policy.getValue(PlanState.REST).copy(timerPhotoGapsMs = TimerPhotoGaps(0, 0, 0, 0))))
    private fun cand(vararg v: JsonObject) = ReplayCandidate("c", table = plain, exprs = v.toList())
    private fun run(c: ReplayCandidate, endMs: Long) =
        CaptureSimulator.replay(ReplayFixtures.trace(emptyList(), endMs = endMs, initial = initial).asSequence(), ReplayFixtures.manifest(endMs = endMs), c)

    @Test fun aValueSetsTheCandidateCadenceLikeARowGap() {
        val r = run(cand(const("settledGapS", "20")), START + 120_000)
        val step = 20_000 + 700 - ReplayFixtures.LEAD
        assertEquals((0 until 5).map { START + 20_000 - ReplayFixtures.LEAD + it * step }, r.opens.map { it.atMs }, "the same opens as a row with a 20 s settled gap")
        assertTrue(r.valueTicks.getValue("v") >= 1, r.valueTicks.toString())
        assertEquals(0, r.windowWarmingS)
    }

    @Test fun ValueOutsideTheClampIsClampedInReplayToo() {
        val r = run(cand(const("settledGapS", "100000")), START + 700_000)   // far above 300 s
        val first = START + 300_000 - ReplayFixtures.LEAD
        assertEquals(listOf(first to EpisodeCause.TIMER, first + 300_000 to EpisodeCause.RESV), r.opens.map { it.atMs to it.cause }, "clamped to 300 s, as the rows are")
    }

    @Test fun anErroringValueLeavesTheRowDefaultAndCountsTheError() {
        val div0 = value("d", "settledGapS", """{"op":"const","v":1},{"op":"const","v":0},{"op":"arith","f":"/","a":0,"b":1}""", out = 2)
        val none = run(cand(), START + 60_000)
        val r = run(cand(div0), START + 60_000)
        assertEquals(none.opens, r.opens, "the row default stands")
        assertTrue(r.exprDiv0 >= 1); assertEquals(0, r.exprErr); assertEquals(0, r.valueTicks.getValue("d"))
    }

    @Test fun aValueOnAnotherRowChangesNothingHere() {
        val none = run(cand(), START + 60_000)
        val r = run(cand(value("f", "settledGapS", """{"op":"const","v":20}""", row = "FLIGHT")), START + 60_000)
        assertEquals(none.opens, r.opens, "the trace is in REST")
    }

    @Test fun theBaseReplayIgnoresValues() {
        // no candidate: nothing to tune, nothing counted
        val r = CaptureSimulator.replay(ReplayFixtures.trace(emptyList(), endMs = START + 60_000, initial = initial).asSequence(), ReplayFixtures.manifest(endMs = START + 60_000), null)
        assertEquals(emptyMap(), r.valueTicks)
    }
}
