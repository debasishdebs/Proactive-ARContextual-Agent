package com.rokid.context.glasses.capture.diag

import com.rokid.context.glasses.capture.session.DiagStats
import java.util.Locale

/** The once-a-minute heartbeat (spec §8, v2). Numbers and states only; per-minute counters are normalised to the span since the last one. */
data class Heartbeat(
    val running: Boolean,
    val reason: String,
    val uiMode: Any?,
    val wake: String,
    val tier: String,
    val targetH: Int,
    val batt: Int,
    val charging: Boolean,
    val drainPctPerMin: Double?,
    val projectedHours: Double?,
    val stats: DiagStats,
    val upBytes: Long,
    val heat: String,
    val batteryC: Any?,
    val wear: Boolean,
    val known: Boolean,
    val parked: Boolean,
    val sensors: String,
    val phoneUse: Boolean,
    val restartGapSec: Any?,
) {
    private fun perMin(v: Long): Long = v * 60_000L / stats.spanMs.coerceAtLeast(1)

    fun format(): String {
        val span = stats.spanMs.coerceAtLeast(1)
        val openPct = (100 * stats.openMs / span).coerceIn(0, 100)
        val drain = drainPctPerMin?.let { String.format(Locale.US, "%.2f", it) } ?: "na"
        val proj = projectedHours?.let { String.format(Locale.US, "%.1f", it) } ?: "na"
        return "heartbeat v=2 running=$running reason=$reason ui_mode=$uiMode wake=$wake tier=$tier target_h=$targetH batt=$batt chg=$charging " +
            "drain=$drain proj_h=$proj kf1m=${perMin(stats.kf1m.toLong())} camOpen=$openPct% camOpenMs1m=${perMin(stats.openMs)} " +
            "analysed1m=${perMin(stats.analysed.toLong())} upBytes1m=${perMin(upBytes)} heat=$heat batteryC=$batteryC " +
            "wear=$wear known=$known parked=$parked sensors=$sensors phoneUse=$phoneUse restartGapSec=$restartGapSec"
    }
}
