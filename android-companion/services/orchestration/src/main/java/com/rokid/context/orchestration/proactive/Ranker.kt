package com.rokid.context.orchestration.proactive

import com.rokid.context.core.model.QuietWindow
import com.rokid.context.orchestration.nudge.NudgeHud
import java.time.Instant

/** `score = familyPrior x confidence x recency x feedbackMultiplier` (design 2.3); below [THRESHOLD] is dropped. v3.0 has no learned feedback: the multiplier is 1 (v3.1, T13). */
object ProactiveRanker {
    const val THRESHOLD = 0.35
    /** How long an arrival keeps its full weight, and where it ends. */
    private const val FRESH_MS = 5 * 60_000L
    private const val STALE_MS = 20 * 60_000L

    fun recency(arrivedAtMs: Long, nowMs: Long): Double {
        val age = (nowMs - arrivedAtMs).coerceAtLeast(0L)
        return when { age <= FRESH_MS -> 1.0; age >= STALE_MS -> 0.5; else -> 1.0 - 0.5 * (age - FRESH_MS).toDouble() / (STALE_MS - FRESH_MS) }
    }

    fun score(c: Candidate, nowMs: Long, feedback: Double = 1.0): Double = c.family.prior * c.confidence.coerceIn(0.0, 1.0) * recency(c.arrivedAtMs, nowMs) * feedback

    /** Best first, ties by key (deterministic); the candidates under the threshold are kept with their score so a replay can report them. */
    fun rank(cs: List<Candidate>, nowMs: Long): List<Pair<Candidate, Double>> = cs.map { it to score(it, nowMs) }.sortedWith(compareByDescending<Pair<Candidate, Double>> { it.second }.thenBy { it.first.key })
}

/** The counts the budget reads. [records] are the proactive would-shows (simulated: shadow mode never consumes a live slot); [globalToday] the nudges stored since local midnight. */
data class BudgetState(val records: List<SuggestionRecord>, val globalToday: Int, val globalCap: Int)

/**
 * The attention budget (rulings 2 and 3): 3 per day INSIDE the global nudge cap (a global cap of 0 disables everything), a gap since the last would-show, 2 per family per day, a per-key cooldown, and quiet hours
 * that always win (dropped, never deferred). Pure: the caller passes the numbers.
 */
class ProactiveBudget(
    val capPerDay: Int = 3, val familyMax: Int = 2, val gapMs: Long = 30 * 60_000L, val cooldownMs: Long = 24 * 3_600_000L,
) {
    fun check(c: Candidate, state: BudgetState, hud: NudgeHud, nowMs: Long): Gate {
        if (state.globalCap <= 0 || capPerDay <= 0) return Gate.OFF
        if (hud.quietHours?.let { QuietWindow.occurrenceAt(it, nowMs, hud.zone) } != null) return Gate.QUIET_HOURS
        val midnight = Instant.ofEpochMilli(nowMs).atZone(hud.zone).toLocalDate().atStartOfDay(hud.zone).toInstant().toEpochMilli()
        val today = state.records.filter { it.ts >= midnight && it.ts <= nowMs }
        if (today.size >= capPerDay) return Gate.CAP_REACHED
        if (today.size + state.globalToday >= state.globalCap) return Gate.GLOBAL_CAP
        if (today.count { it.family == c.family } >= familyMax) return Gate.FAMILY_MAX
        if (gapMs > 0 && state.records.any { it.ts <= nowMs && nowMs - it.ts < gapMs }) return Gate.SHOWN_GAP
        if (state.records.any { it.key == c.key && it.ts <= nowMs && nowMs - it.ts < cooldownMs }) return Gate.COOLDOWN
        return Gate.WOULD_SHOW
    }
}
