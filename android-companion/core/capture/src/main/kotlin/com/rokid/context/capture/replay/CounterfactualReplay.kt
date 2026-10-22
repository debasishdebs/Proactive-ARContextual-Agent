package com.rokid.context.capture.replay

import com.rokid.context.capture.plan.PlanBounds
import com.rokid.context.capture.trace.GateOutcome
import com.rokid.context.capture.trace.Inexact
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.TraceEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the replay of one config produced, as numbers (per-minute figures use the post-pre-roll span of the trace). The battery figures are ESTIMATES. */
data class ReplayFigures(
    val configHash: String,
    val opens: Int,
    val opensPerMin: Double,
    val camOpenS: Double,
    val camOpenSPerMin: Double,
    /** Keeps: exact for the recorded config, a point estimate for a counterfactual one. */
    val keeps: Double,
    val keepsPerMin: Double,
    /** Opens with no recorded frame decision within [CounterfactualReplay.NEAREST_FRAME_MS]. Always 0 for the recorded config. */
    val unobservedOpens: Int,
    val estimatedPctPerMin: Double,
    val estimatedHoursFromFull: Double,
)

/**
 * The result of a counterfactual (or baseline-only) replay. Replay is APPROXIMATE: [inexactWindows] were excluded from the base comparison and counted; every
 * figure is an estimate ("estimate" in the UI) until a verified LIVE interval exists (S11, S44).
 */
data class ReplayReport(
    /** `EXACT` when only the recorded config was replayed, `COUNTERFACTUAL` when a candidate was. */
    val replayClass: String,
    val minutes: Double,
    val baseline: ReplayFigures,
    /** Null when no candidate was replayed. */
    val candidate: ReplayFigures?,
    val unobservedOpensPct: Double,
    val basePassed: Int,
    val baseFailed: Int,
    val inexactWindows: Int,
    val inexactReasons: Map<Inexact, Int>,
    /** A plan whose fusion params differ from the trace's can run only in SHADOW or TRIAL (S30). */
    val shadowOrTrialOnly: Boolean,
    val warnings: List<String>,
) {
    fun toJson(): JsonElement {
        fun f(x: ReplayFigures) = buildJsonObject {
            put("configHash", x.configHash); put("opens", x.opens); put("opensPerMin", x.opensPerMin); put("camOpenS", x.camOpenS); put("camOpenSPerMin", x.camOpenSPerMin)
            put("keeps", x.keeps); put("keepsPerMin", x.keepsPerMin); put("unobservedOpens", x.unobservedOpens)
            put("estimatedPctPerMin", x.estimatedPctPerMin); put("estimatedHoursFromFull", x.estimatedHoursFromFull)
        }
        return buildJsonObject {
            put("replayClass", replayClass); put("estimate", true); put("minutes", minutes)
            put("baseline", f(baseline)); candidate?.let { put("candidate", f(it)) }
            put("unobservedOpensPct", unobservedOpensPct)
            put("basePassed", basePassed); put("baseFailed", baseFailed); put("inexactWindows", inexactWindows)
            put("inexactReasons", buildJsonObject { inexactReasons.toSortedMap().forEach { (k, v) -> put(k.name, v) } })
            put("shadowOrTrialOnly", shadowOrTrialOnly)
            put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
        }
    }
}

/**
 * Counterfactual replay over [CaptureSimulator] (S29, lean). The IMU-driven decisions (the candidate's opens) come from the simulator. The VISUAL outcome of a
 * candidate open is a point estimate: the outcome of the nearest recorded frame decision within [NEAREST_FRAME_MS], otherwise counted as a keep and counted
 * as unobserved ("unobserved opens: x %"). Fusion is not recomputed: a fusion change only adds the fixed warning (S30).
 *
 * Rulings made where the spec is silent (the simplest rule): keeps are per OPEN (one estimate per open); camera-open seconds per open are the mean recorded
 * open duration (episode open to close, after the pre-roll), [DEFAULT_OPEN_S] when none was recorded; per-minute figures divide by the span from the pre-roll end
 * to the last event.
 */
object CounterfactualReplay {
    const val NEAREST_FRAME_MS = 2_000L
    const val DEFAULT_OPEN_S = 0.7
    const val FUSION_WARNING = "fusion changes are not replayed"
    const val APPROXIMATE_WARNING = "replay is approximate"
    const val ESTIMATE_WARNING = "keeps and battery are estimates"

    /** Everything the single pass over the events collects besides the simulator's own work: bounded by the number of gates and episodes, not by the sensor rate. */
    private class Seen {
        val gateAt = ArrayList<Long>(); val gateKeep = ArrayList<Boolean>()
        val durations = ArrayList<Long>()
        var openAt: Long? = null
        var preRollEnd: Long? = null
        var first: Long? = null
        var last = 0L
        fun accept(e: TraceEvent) {
            if (first == null) first = e.tMs
            last = e.tMs
            when (e) {
                is TraceEvent.PreRollEnd -> preRollEnd = e.tMs
                is TraceEvent.Gate -> { gateAt += e.tMs; gateKeep += (e.outcome == GateOutcome.KEEP) }
                is TraceEvent.Episode -> if (e.open) openAt = e.tMs else {
                    val o = openAt; openAt = null
                    if (o != null && preRollEnd != null && o >= preRollEnd!!) durations += (e.tMs - o)
                }
                else -> Unit
            }
        }
    }

    /**
     * Replays the trace under the recorded config (and under [candidate] when given). [source] is called once per pass (one pass for the baseline, one more for a
     * candidate), so a replay streams every time. [recordedFusion] is the fusion block of the trace's plan (defaults when it had none); [candidateFusion] the
     * candidate's. [tick] is called for every event (the service uses it to stop at once when cancelled).
     */
    fun run(
        source: () -> Sequence<TraceEvent>, m: Manifest, candidate: ReplayCandidate? = null,
        recordedFusion: Map<String, Double> = PlanBounds.FUSION_DEFAULTS, candidateFusion: Map<String, Double> = recordedFusion,
        tick: () -> Unit = {},
    ): ReplayReport {
        val seen = Seen()
        val base = CaptureSimulator.replay(source().onEach { tick(); seen.accept(it) }, m, null)
        val cand = candidate?.let { c -> CaptureSimulator.replay(source().onEach { tick() }, m, c) }
        val start = seen.preRollEnd ?: seen.first ?: m.startElapsedMs
        val minutes = ((seen.last - start).coerceAtLeast(0L)) / 60_000.0
        val meanOpenS = if (seen.durations.isEmpty()) DEFAULT_OPEN_S else seen.durations.average() / 1000.0

        fun perMin(x: Double) = if (minutes > 0.0) x / minutes else 0.0
        fun figures(hash: String, opens: Int, camS: Double, keeps: Double, unobserved: Int): ReplayFigures {
            val camPerMin = perMin(camS)
            val pct = BatteryLine.pctPerMin(camPerMin)
            return ReplayFigures(hash, opens, perMin(opens.toDouble()), camS, camPerMin, keeps, perMin(keeps), unobserved, pct, BatteryLine.hoursFromFull(pct))
        }

        // The recorded config: the recorded durations are the camera-open time (not an estimate), the keeps are the replayed (= recorded) ones.
        val baseline = figures(base.configHash, base.opens.size, seen.durations.sum() / 1000.0, base.keeps.toDouble(), 0)
        var candFigures: ReplayFigures? = null
        var unobservedPct = 0.0
        if (cand != null) {
            val est = estimateKeeps(cand.opens.map { it.atMs }, seen.gateAt, seen.gateKeep)
            candFigures = figures(cand.configHash, cand.opens.size, cand.opens.size * meanOpenS, est.keeps.toDouble(), est.unobserved)
            unobservedPct = if (cand.opens.isEmpty()) 0.0 else 100.0 * est.unobserved / cand.opens.size
        }

        val warnings = ArrayList<String>()
        if (base.inexact > 0) warnings += "$APPROXIMATE_WARNING: ${base.inexact} window(s) excluded (${base.inexactReasons.entries.sortedBy { it.key.name }.joinToString(", ") { "${it.key.name.lowercase()} ${it.value}" }})"
        else warnings += APPROXIMATE_WARNING
        if (base.failed > 0) warnings += "the replay disagrees with the recording in ${base.failed} window(s)"
        warnings += ESTIMATE_WARNING
        if (cand != null) warnings += "unobserved opens: ${"%.1f".format(java.util.Locale.ROOT, unobservedPct)} %"
        val fusionChanged = cand != null && candidateFusion != recordedFusion
        if (fusionChanged) warnings += FUSION_WARNING
        return ReplayReport(
            replayClass = if (cand == null) "EXACT" else "COUNTERFACTUAL", minutes = minutes, baseline = baseline, candidate = candFigures, unobservedOpensPct = unobservedPct,
            basePassed = base.passed, baseFailed = base.failed, inexactWindows = base.inexact, inexactReasons = base.inexactReasons,
            shadowOrTrialOnly = fusionChanged, warnings = warnings,
        )
    }

    class KeepEstimate(val keeps: Int, val unobserved: Int)

    /**
     * The point estimate (S29): per open, the outcome of the nearest recorded frame decision within [NEAREST_FRAME_MS] (the earlier one on a tie); with none, a keep, counted as
     * unobserved. [gateAt] is time ordered (recording order).
     */
    fun estimateKeeps(opens: List<Long>, gateAt: List<Long>, gateKeep: List<Boolean>): KeepEstimate {
        var keeps = 0; var unobserved = 0
        for (o in opens) {
            var lo = 0; var hi = gateAt.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (gateAt[mid] < o) lo = mid + 1 else hi = mid }
            // candidates: lo-1 (before) and lo (at or after)
            var best = -1; var bestD = Long.MAX_VALUE
            if (lo - 1 >= 0) { val d = o - gateAt[lo - 1]; if (d < bestD) { bestD = d; best = lo - 1 } }
            if (lo < gateAt.size) { val d = gateAt[lo] - o; if (d < bestD) { bestD = d; best = lo } }
            if (best >= 0 && bestD <= NEAREST_FRAME_MS) { if (gateKeep[best]) keeps++ } else { keeps++; unobserved++ }
        }
        return KeepEstimate(keeps, unobserved)
    }
}
