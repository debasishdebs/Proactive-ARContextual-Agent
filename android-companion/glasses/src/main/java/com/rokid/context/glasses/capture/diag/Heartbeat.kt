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
    /** The interval [upBytes] covers (the service's own, not the session snapshot's). */
    val upSpanMs: Long = 60_000L,
    val heat: String,
    val batteryC: Any?,
    val wear: Boolean,
    val known: Boolean,
    val parked: Boolean,
    val sensors: String,
    val phoneUse: Boolean,
    val restartGapSec: Any?,
    val stillGapMs: Long = 0L,
    val scrOn: Int = 0,
    val scrOurs: Int = 0,
    val nudgeOff: Int = 0,
    /** Motion CSV write failures (count only); printed only when non-zero so the key order above stays pinned. */
    val csvFail: Int = 0,
    /** Wave B: the last `pc_motion_ctx` state name or "none", its age in seconds, the state applied to capture, vetoes in the span. */
    val ctx: String = "none",
    val ctxAgeS: Long? = null,
    val ctxApplied: String = "UNKNOWN",
    val ctxVeto: Int = 0,
    /** Slice 3a: the plan in force (first 8 hex of its config hash, or "none"), its gen, the effective mode, and the last auto-revert reason. */
    val plan: String = "none",
    val gen: Long = 0,
    val planMode: String = "SETTINGS",
    val planRevert: String = "none",
    /** SHADOW's counterfactual glance opens and skips since the previous heartbeat (counts, not per-minute). */
    val shOpen: Int = 0,
    val shSkip: Int = 0,
) {
    private fun perMin(v: Long, spanMs: Long = stats.spanMs): Long = v * 60_000L / spanMs.coerceAtLeast(1)

    fun format(): String {
        val span = stats.spanMs.coerceAtLeast(1)
        val openPct = (100 * stats.openMs / span).coerceIn(0, 100)
        val drain = drainPctPerMin?.let { String.format(Locale.US, "%.2f", it) } ?: "na"
        val proj = projectedHours?.let { String.format(Locale.US, "%.1f", it) } ?: "na"
        return "heartbeat v=2 running=$running reason=$reason ui_mode=$uiMode wake=$wake tier=$tier target_h=$targetH batt=$batt chg=$charging " +
            "drain=$drain proj_h=$proj kf1m=${stats.kf1m} camOpen=$openPct% camOpenMs1m=${perMin(stats.openMs)} " +
            "analysed1m=${perMin(stats.analysed.toLong())} upBytes1m=${perMin(upBytes, upSpanMs)} heat=$heat batteryC=$batteryC " +
            "wear=$wear known=$known parked=$parked sensors=$sensors phoneUse=$phoneUse restartGapSec=$restartGapSec " +
            "opens1m=${perMin(stats.opens.toLong())} wk=${listOf("timer", "dwell", "onset", "now", "stream").joinToString(",") { "$it:${stats.wakes[it] ?: 0}" }} stillGapMs=$stillGapMs " +
            "glances1m=${perMin(stats.glances.toLong())} micro1m=${perMin(stats.micro.toLong())} novelSkip1m=${perMin(stats.novelSkips.toLong())} " +
            "capped1m=${perMin(stats.capped.toLong())} same1m=${perMin(stats.same.toLong())} scrOn=$scrOn scrOurs=$scrOurs nudgeOff=$nudgeOff" +
            " ctx=$ctx ctxAgeS=${ctxAgeS ?: "na"} ctxApplied=$ctxApplied ctxVeto=$ctxVeto" +
            " plan=$plan gen=$gen planMode=$planMode ruleSkip1m=0 exprErr=0 planRevert=$planRevert resv=${stats.wakes["resv"] ?: 0} shOpen=$shOpen shSkip=$shSkip" +
            " worn=${if (wear) 1 else 0} charging=${if (charging) 1 else 0}" +
            if (csvFail > 0) " csvFail=$csvFail" else ""
    }

    companion object {
        /** A19: "off" when there is no sampling session (the sensors are not running), else the feed's rate. */
        fun sensorsLabel(hasSession: Boolean, rate: String?): String = if (!hasSession) "off" else rate ?: "full"

        /** A20: builds the line, or logs the exception class only (never its message) and returns null so the caller carries on. */
        fun guarded(log: (String) -> Unit, build: () -> String): String? =
            try { build() } catch (e: Exception) { log("heartbeat failed: ${e.javaClass.simpleName}"); null }
    }
}
