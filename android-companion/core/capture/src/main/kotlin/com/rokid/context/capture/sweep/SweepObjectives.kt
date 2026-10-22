package com.rokid.context.capture.sweep

import com.rokid.context.capture.replay.BatteryLine
import com.rokid.context.capture.replay.ReplayFigures
import com.rokid.context.capture.replay.ReplayReport

/**
 * The objectives of one config over all the traces of a sweep (slice 3 section 6.3, the 3a-CORE set): captures (keeps) per minute, camera opens per minute, the estimated
 * %/min and hours from full on the fixed two-point line, and the unobserved opens %. Every figure is an ESTIMATE (replay is approximate). Moment recall, intent hit rate,
 * the holdout and the Pareto front are LATER (LA6): they only rank configs.
 */
data class SweepFigures(
    val minutes: Double, val capturesPerMin: Double, val opensPerMin: Double, val camOpenSPerMin: Double,
    val estimatedPctPerMin: Double, val estimatedHoursFromFull: Double, val unobservedOpensPct: Double,
)

object SweepObjectives {
    /** The candidate config over [reports] (one per trace): totals over the traces, divided by the total minutes. */
    fun candidate(reports: List<ReplayReport>): SweepFigures = of(reports.map { it.minutes to (it.candidate ?: throw IllegalArgumentException("no candidate")) })

    /** The recorded config over [reports]. */
    fun baseline(reports: List<ReplayReport>): SweepFigures = of(reports.map { it.minutes to it.baseline })

    private fun of(parts: List<Pair<Double, ReplayFigures>>): SweepFigures {
        val minutes = parts.sumOf { it.first }
        val opens = parts.sumOf { it.second.opens }
        val cam = parts.sumOf { it.second.camOpenS }
        val keeps = parts.sumOf { it.second.keeps }
        val unobserved = parts.sumOf { it.second.unobservedOpens }
        fun perMin(x: Double) = if (minutes > 0.0) x / minutes else 0.0
        val camPerMin = perMin(cam)
        val pct = BatteryLine.pctPerMin(camPerMin)
        return SweepFigures(minutes, perMin(keeps), perMin(opens.toDouble()), camPerMin, pct, BatteryLine.hoursFromFull(pct), if (opens == 0) 0.0 else 100.0 * unobserved / opens)
    }
}
