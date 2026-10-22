package com.rokid.context.capture.sweep

import com.rokid.context.capture.replay.BatteryLine
import com.rokid.context.capture.replay.ReplayFigures
import com.rokid.context.capture.replay.ReplayReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SweepObjectivesTest {
    private fun fig(opens: Int, cam: Double, keeps: Double, unobs: Int, minutes: Double): ReplayFigures {
        val pct = BatteryLine.pctPerMin(cam / minutes)
        return ReplayFigures("h", opens, opens / minutes, cam, cam / minutes, keeps, keeps / minutes, unobs, pct, BatteryLine.hoursFromFull(pct))
    }
    private fun rep(minutes: Double, base: ReplayFigures, cand: ReplayFigures?) = ReplayReport("COUNTERFACTUAL", minutes, base, cand, 0.0, 0, 0, 0, emptyMap(), false, emptyList())

    @Test fun objectivesAreTotalsOverTheTracesNotMeansOfMeans() {
        val a = rep(10.0, fig(10, 7.0, 6.0, 0, 10.0), fig(20, 14.0, 12.0, 5, 10.0))
        val b = rep(30.0, fig(30, 21.0, 18.0, 0, 30.0), fig(30, 21.0, 24.0, 3, 30.0))
        val f = SweepObjectives.candidate(listOf(a, b))
        assertEquals(40.0, f.minutes)
        assertEquals(36.0 / 40.0, f.capturesPerMin, 1e-12)
        assertEquals(50.0 / 40.0, f.opensPerMin, 1e-12)
        assertEquals(35.0 / 40.0, f.camOpenSPerMin, 1e-12)
        assertEquals(BatteryLine.pctPerMin(35.0 / 40.0), f.estimatedPctPerMin, 1e-12)
        assertEquals(100.0 / f.estimatedPctPerMin / 60.0, f.estimatedHoursFromFull, 1e-12)
        assertEquals(100.0 * 8 / 50, f.unobservedOpensPct, 1e-12)
        val base = SweepObjectives.baseline(listOf(a, b))
        assertEquals(24.0 / 40.0, base.capturesPerMin, 1e-12)
        assertEquals(0.0, base.unobservedOpensPct)
    }

    @Test fun noOpensMeansNoUnobservedShareAndAReportWithoutACandidateIsRefused() {
        val z = rep(5.0, fig(0, 0.0, 0.0, 0, 5.0), fig(0, 0.0, 0.0, 0, 5.0))
        assertEquals(0.0, SweepObjectives.candidate(listOf(z)).unobservedOpensPct)
        assertEquals(BatteryLine.FLOOR_PCT_PER_MIN, SweepObjectives.candidate(listOf(z)).estimatedPctPerMin, 1e-12)
        assertFailsWith<IllegalArgumentException> { SweepObjectives.candidate(listOf(rep(5.0, fig(1, 1.0, 1.0, 0, 5.0), null))) }
    }
}
