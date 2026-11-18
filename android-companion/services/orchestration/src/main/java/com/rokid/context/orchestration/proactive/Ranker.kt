package com.rokid.context.orchestration.proactive

import com.rokid.context.core.model.QuietWindow
import com.rokid.context.orchestration.nudge.NudgeHud
import java.time.Instant

/** `score = familyPrior x confidence x recency x feedbackMultiplier` (design 2.3); below [THRESHOLD] is dropped. v3.1: the multiplier is [FeedbackMath.multiplier] (bounded plain counters; 1 without feedback). */
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
    fun rank(cs: List<Candidate>, nowMs: Long, feedback: (Candidate) -> Double = { 1.0 }): List<Pair<Candidate, Double>> = cs.map { it to score(it, nowMs, feedback(it)) }.sortedWith(compareByDescending<Pair<Candidate, Double>> { it.second }.thenBy { it.first.key })
}

/**
 * The counts the budget reads. [records] are the retained would-shows; [globalToday] the nudges stored since local midnight; [ledger] the delivery ledger (v3.1 r1): delivered suggestions count from the ledger, NOT from the
 * retained records, so dismissing (which deletes the record) frees no budget. A retained record that was never delivered still counts as a would-show.
 */
data class BudgetState(val records: List<SuggestionRecord>, val globalToday: Int, val globalCap: Int, val ledger: List<LedgerEntry> = emptyList())

/**
 * The attention budget (rulings 2 and 3): 3 per day INSIDE the global nudge cap (a global cap of 0 disables everything), a gap since the last would-show, 2 per family per day, a per-key cooldown, and quiet hours
 * that always win (dropped, never deferred). Pure: the caller passes the numbers.
 */
class ProactiveBudget(
    val capPerDay: Int = 3, val familyMax: Int = 2, val gapMs: Long = 30 * 60_000L, val cooldownMs: Long = 24 * 3_600_000L,
) {
    fun check(c: Candidate, state: BudgetState, hud: NudgeHud, nowMs: Long): Gate = check(c.key, c.family, state, hud, nowMs)

    /** The same by key and family (the delivery stage has no candidate any more; it passes a state WITHOUT the record it is delivering). */
    fun check(key: String, family: ProactiveFamily, state: BudgetState, hud: NudgeHud, nowMs: Long): Gate {
        if (state.globalCap <= 0 || capPerDay <= 0) return Gate.OFF
        if (hud.quietHours?.let { QuietWindow.occurrenceAt(it, nowMs, hud.zone) } != null) return Gate.QUIET_HOURS
        val midnight = Instant.ofEpochMilli(nowMs).atZone(hud.zone).toLocalDate().atStartOfDay(hud.zone).toInstant().toEpochMilli()
        if (DeliveryLedger.pausedUntil(state.ledger) > nowMs) return Gate.DISMISS_PAUSE
        // consumption = the ledger (delivered, whatever became of the record) + the retained would-shows that were never delivered
        val events = state.ledger.filter { it.kind == LedgerKind.DELIVERED }.map { it.ts to it.family } + state.records.filter { it.delivered.isEmpty() }.map { it.ts to it.family }
        val today = events.filter { it.first >= midnight && it.first <= nowMs }
        if (today.size >= capPerDay) return Gate.CAP_REACHED
        if (today.size + state.globalToday >= state.globalCap) return Gate.GLOBAL_CAP
        if (today.count { it.second == family } >= familyMax) return Gate.FAMILY_MAX
        if (gapMs > 0 && events.any { it.first <= nowMs && nowMs - it.first < gapMs }) return Gate.SHOWN_GAP
        if (state.records.any { it.key == key && it.ts <= nowMs && nowMs - it.ts < cooldownMs }) return Gate.COOLDOWN
        return Gate.WOULD_SHOW
    }
}
