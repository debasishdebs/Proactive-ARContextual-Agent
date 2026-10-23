package com.rokid.context.canvas.plan

import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.plan.CompileResult
import com.rokid.context.ai.canvas.plan.PlanShape
import com.rokid.context.capture.plan.CapturePlan
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasReplayEntity
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.TraceSources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** What the phone knows of the glasses at publish time (gate 5). Bound to the hub's `planPeer()` and the link state in the composition task. */
enum class GlassesState { LINKED_CAPABLE, NOT_LINKED, TOO_OLD, UNKNOWN }

/** One of the five `capture_plan` gates (S11, section 7): [text] is the fixed sentence shown when it is not [ok]. */
data class GateResult(val n: Int, val ok: Boolean, val text: String)

/** What gate 3 found: the evidence behind the publish (all keyed by `configHash`) and the p95 the plan's `meta.expectOpensP95PerMin` is filled with. */
data class ReplayEvidence(
    val replayId: String, val configHash: String, val minutes: Double, val statesCovered: Int,
    /** Minutes-weighted mean of the replay's estimated %/min for the plan, and for the recorded config (the baseline of the same replay). */
    val candidatePctPerMin: Double, val baselinePctPerMin: Double,
    /** Per context state: the highest p95 opens/min of any trace (ruling: a p95 cannot be merged exactly, the highest is the conservative ceiling for the auto-revert). */
    val opensP95PerMin: Map<String, Double>,
    /** Over the replay's reports: windows the comparison could judge, windows it excluded as inexact, and whether any report says fusion is not replayed (Task 37 shows these beside the publish gates). */
    val windowsCompared: Int = 0, val inexactWindows: Int = 0, val fusionWarning: Boolean = false,
)

data class GateReport(val configHash: String?, val gates: List<GateResult>, val evidence: ReplayEvidence?, val details: List<String> = emptyList()) {
    val ok: Boolean get() = gates.all { it.ok }
    /** The fixed sentences of the failed gates, then the details (compiler problems, never user text). */
    val reasons: List<String> get() = gates.filterNot { it.ok }.map { it.text } + details
}

/** The LIVE verdict seam: unrestricted LIVE needs a measured, verified interval of this plan at <= 0.42 %/min (S11, S44). Its owner (LA9, with the calibration import) is later. */
fun interface LiveEvidence {
    suspend fun verdict(configHash: String): Boolean

    companion object {
        /** Core: LIVE is never granted, whatever the calibration rows say. */
        val NotBuilt = LiveEvidence { false }
    }
}

/**
 * The five `capture_plan` gates (spec section 7, S11), every piece of evidence keyed by `configHash` (S60):
 * 1. compile clean; 2. no tier-3 node; 3. a finished replay of exactly this config over >= 30 min of COMPLETE traces covering >= 2 states, whose sources and `labelRev` are still
 * current (S75); 4. S11: SHADOW, a TRIAL (M1 recorded, an estimate <= baseline x 1.10, at most 3 per config) or verified LIVE (refused in core); 5. the glasses linked and capable (S24).
 *
 * Rulings where the spec is silent: gate 3 accepts a replay of EITHER class whose `configHash` is the plan's (a never-recorded plan can only be replayed as a candidate);
 * a state counts as covered with >= 1 minute; the baseline of the TRIAL estimate is the recorded-config figure of the same replay.
 */
class PlanEvidenceGates(
    private val dao: CanvasSlice3Dao,
    private val m1: M1EvidenceStore,
    private val glasses: () -> GlassesState,
    private val canvas: CanvasDao,
    private val live: LiveEvidence = LiveEvidence.NotBuilt,
) {
    /** All five gates for publishing [p] (compiled to [compile]) in [mode]. LIVE and TRIAL need `m1Passed` first; SHADOW does not. */
    suspend fun evaluate(p: Pipeline, compile: CompileResult, mode: PlanMode): GateReport {
        require(mode != PlanMode.SETTINGS) { "mode" }
        val plan = compile.plan
        val compiled = plan != null && compile.problems.isEmpty()
        val g1 = GateResult(1, compiled, G1)
        val g2 = GateResult(2, PlanShape.tier3Nodes(p).isEmpty(), G2)
        val found = if (plan != null && compiled) replayEvidence(plan.configHash) else null
        val g3 = GateResult(3, found is Found, G3 + ((found as? Missing)?.why?.let { " ($it)" } ?: if (plan == null) " (compile first)" else ""))
        val evidence = (found as? Found)?.evidence
        val g4 = gate4(plan, evidence, mode)
        val g5 = GateResult(5, glasses() == GlassesState.LINKED_CAPABLE, G5)
        return GateReport(plan?.configHash, listOf(g1, g2, g3, g4, g5), evidence, details = if (compiled) emptyList() else compile.problems)
    }

    /** Gate 3 alone: the newest finished replay of [configHash] that is still current and long and varied enough. */
    suspend fun replayEvidence(configHash: String): ReplayLookup {
        val rows = dao.validReplays(configHash)
        if (rows.isEmpty()) return Missing("no finished replay of this plan")
        val labelRev = dao.labelRev()
        var why = "no current replay of this plan"
        for (r in rows) {
            when (val e = evaluateReplay(r, labelRev)) {
                is Found -> return e
                is Missing -> if (why == "no current replay of this plan" || e.why.startsWith("needs")) why = e.why
            }
        }
        return Missing(why)
    }

    private suspend fun evaluateReplay(r: CanvasReplayEntity, labelRev: Long): ReplayLookup {
        if (r.labelRev != labelRev) return Missing("the labels changed since the replay")
        val sources = TraceSources.decode(r.sourcesJson)?.takeIf { it.isNotEmpty() } ?: return Missing("the replay's traces are unknown")
        for (s in sources) {
            val t = dao.trace(s.traceId)
            if (t == null || t.tombstoned || t.revision != s.revision) return Missing("a trace changed since the replay")
            if (t.status != "COMPLETE") return Missing("a trace is not complete")
        }
        val reports = runCatching { Json.parseToJsonElement(r.resultJson ?: return Missing("the replay has no result")).jsonObject["reports"]!!.jsonArray.map { it.jsonObject["report"]!!.jsonObject } }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: return Missing("the replay result is unreadable")
        var minutes = 0.0; var cand = 0.0; var base = 0.0
        var compared = 0; var inexact = 0; var fusion = false
        val stateMinutes = HashMap<String, Double>(); val p95 = HashMap<String, Double>()
        for (rep in reports) {
            val m = rep.num("minutes") ?: return Missing("the replay result is unreadable")
            val b = rep["baseline"]?.jsonObject?.num("estimatedPctPerMin") ?: return Missing("the replay result is unreadable")
            val c = rep["candidate"]?.jsonObject?.num("estimatedPctPerMin") ?: b       // a replay of the recorded config only: the plan IS the baseline
            minutes += m; base += b * m; cand += c * m
            compared += ((rep.num("basePassed") ?: 0.0) + (rep.num("baseFailed") ?: 0.0)).toInt(); inexact += (rep.num("inexactWindows") ?: 0.0).toInt()
            if ((rep["shadowOrTrialOnly"] as? JsonPrimitive)?.booleanOrNull == true) fusion = true
            rep["states"]?.jsonObject?.forEach { (state, v) ->
                val o = v.jsonObject
                stateMinutes[state] = (stateMinutes[state] ?: 0.0) + (o.num("minutes") ?: 0.0)
                p95[state] = maxOf(p95[state] ?: 0.0, o.num("opensP95PerMin") ?: 0.0)
            }
        }
        if (minutes < MIN_MINUTES) return Missing(String.format(Locale.ROOT, "needs %d minutes of traces, has %.0f", MIN_MINUTES, minutes))
        val covered = stateMinutes.filterValues { it >= MIN_STATE_MINUTES }.keys
        if (covered.size < MIN_STATES) return Missing("needs $MIN_STATES states, has ${covered.size}")
        return Found(ReplayEvidence(r.replayId, r.configHash.orEmpty(), minutes, covered.size, cand / minutes, base / minutes, p95.filterKeys { it in covered }.mapValues { round2(it.value) }, compared, inexact, fusion))
    }

    private suspend fun gate4(plan: CapturePlan?, e: ReplayEvidence?, mode: PlanMode): GateResult {
        if (mode == PlanMode.SHADOW) return GateResult(4, true, "")
        if (m1.get() == null) return GateResult(4, false, G4_M1)
        if (plan == null) return GateResult(4, false, G4_NEEDS_PLAN)
        return when (mode) {
            PlanMode.TRIAL -> when {
                canvas.trialCount(plan.configHash) >= MAX_TRIALS -> GateResult(4, false, G4_TRIALS)
                e == null -> GateResult(4, false, G4_NEEDS_REPLAY)
                e.candidatePctPerMin > e.baselinePctPerMin * TRIAL_FACTOR ->
                    GateResult(4, false, String.format(Locale.ROOT, G4_ESTIMATE, e.candidatePctPerMin, e.baselinePctPerMin))
                else -> GateResult(4, true, "")
            }
            PlanMode.LIVE -> GateResult(4, live.verdict(plan.configHash), G4_LIVE)
            else -> GateResult(4, false, G4_NEEDS_PLAN)
        }
    }

    /** The gate-4 part a RESTORE re-checks for a TRIAL (the replay estimate is not recomputed): M1 recorded and trials left. Null = allowed. */
    suspend fun trialAllowed(configHash: String): String? = when {
        m1.get() == null -> G4_M1
        canvas.trialCount(configHash) >= MAX_TRIALS -> G4_TRIALS
        else -> null
    }

    /** Gate 5 alone (a reinstatement re-checks it). */
    fun glassesReady(): Boolean = glasses() == GlassesState.LINKED_CAPABLE

    sealed interface ReplayLookup
    class Found(val evidence: ReplayEvidence) : ReplayLookup
    class Missing(val why: String) : ReplayLookup

    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0

    companion object {
        const val MIN_MINUTES = 30
        const val MIN_STATES = 2
        const val MIN_STATE_MINUTES = 1.0
        const val MAX_TRIALS = 3
        const val TRIAL_FACTOR = 1.10
        /** The 60-minute TRIAL the glasses enforce themselves (S59). */
        const val TRIAL_MAX_MS = 3_600_000L
        const val G1 = "Gate 1: the capture plan does not compile cleanly."
        const val G2 = PlanShape.S6_REFUSAL
        const val G3 = "Gate 3: needs a finished replay of this exact plan over at least 30 minutes of complete traces covering at least 2 states, on current traces and labels"
        const val G4_M1 = "Gate 4: a TRIAL or LIVE plan is blocked until milestone M1 (the worn device run) is recorded as passed. SHADOW is not blocked."
        const val G4_TRIALS = "Gate 4: this plan has already had 3 trials."
        const val G4_NEEDS_REPLAY = "Gate 4: a TRIAL needs the replay estimate (gate 3)."
        const val G4_NEEDS_PLAN = "Gate 4: needs a compiled plan."
        const val G4_ESTIMATE = "Gate 4: the replay estimates %.2f %%/min, more than 10 %% above the baseline of %.2f %%/min."
        const val G4_LIVE = "Gate 4: unrestricted LIVE needs a verified measured interval of this plan at 0.42 %/min or less. Run a TRIAL or SHADOW."
        const val G5 = "Gate 5: the glasses must be linked and understand capture plans."

        /**
         * The plan with `meta.expectOpensP95PerMin` filled from the gate-3 replay. The identity does NOT change (S60): `configHash` is recomputed over `config` only, and the
         * result is the same hash the compile produced.
         */
        fun fillMeta(plan: CapturePlan, p95: Map<String, Double>): CapturePlan =
            plan.copy(meta = plan.meta.copy(expectOpensP95PerMin = p95.takeIf { it.isNotEmpty() }), configHash = PlanJson.configHash(plan.config))
    }
}
