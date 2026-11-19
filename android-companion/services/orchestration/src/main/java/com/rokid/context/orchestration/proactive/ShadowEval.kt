package com.rokid.context.orchestration.proactive

import java.time.Instant
import java.time.ZoneId

/**
 * Task N5: the read-only evaluation of the 7-day shadow week (spec 2026-11-18 section 4 exit bar). A PURE function over what the stores already hold; it writes nothing and changes no schema.
 * Output is counts and fixed labels only: no key, anchor, source id, template text, place name or photo id is ever copied into the report.
 *
 * The invariant predicates are supplied by the caller (they need history the records do not hold). A null predicate means "cannot be checked": the invariant is reported UNCHECKED and the verdict cannot be PASS.
 */
data class ShadowEvalInput(
    val records: List<SuggestionRecord>,
    /** [SuggestionStore.counters]: cumulative (`runs`, `would_show`, `drop_<gate>`). */
    val counters: Map<String, Int>,
    val feedback: FeedbackState,
    /** Size of the never list (the keys themselves are not needed). */
    val neverCount: Int = 0,
    /** Optional per-day counter snapshots (day = ISO local date, value = that day's counters); gives per-day gate histograms. */
    val dailyCounters: Map<String, Map<String, Int>> = emptyMap(),
    val dailyCap: Int = DEFAULT_DAILY_CAP,
    val zone: ZoneId = ZoneId.systemDefault(),
    val quietAt: ((Long) -> Boolean)? = null,
    val zoneBlocked: ((SuggestionRecord) -> Boolean)? = null,
    val drivingAt: ((Long) -> Boolean)? = null,
    val wornAt: ((Long) -> Boolean)? = null,
) { companion object { const val DEFAULT_DAILY_CAP = 3 } }

enum class EvalVerdict(val label: String) { PASS("PASS"), FAIL("FAIL"), NOT_ENOUGH_DATA("NOT ENOUGH DATA") }

data class LeakCounts(val quiet: Int?, val zone: Int?, val driving: Int?, val unworn: Int?) {
    /** Leaks found (null = unchecked counts as none). */
    fun total() = (quiet ?: 0) + (zone ?: 0) + (driving ?: 0) + (unworn ?: 0)
    fun unchecked() = listOf("quiet" to quiet, "zone" to zone, "driving" to driving, "unworn" to unworn).filter { it.second == null }.map { it.first }
}

data class EvalDay(
    val day: String,
    val candidates: Int?, val passed: Int,
    val refusedByGate: Map<String, Int>,
    val wouldShow: Int,
    val perCategory: Map<String, Int>,
    val leaks: LeakCounts,
)

data class EvalReport(
    val days: List<EvalDay>,
    val observedDays: Int,
    val runs: Int, val wouldShowTotal: Int,
    val refusedByGate: Map<String, Int>,
    val budgetExhausted: Int,
    val perCategory: Map<String, Int>,
    val leaks: LeakCounts,
    val dismissed: Int, val snoozed: Int, val never: Int, val up: Int, val down: Int,
    val dismissRate: Double?, val snoozeRate: Double?, val neverRate: Double?, val usefulRate: Double?,
    val maxShownInADay: Int, val dailyCap: Int,
    val verdict: EvalVerdict, val reasons: List<String>,
    /** Daily-counter keys that were not an ISO date and were dropped (the key text is never kept). */
    val invalidDayKeys: Int = 0,
    /** Days whose snapshot counted more would-show than the retained records: the safety history for them is incomplete. */
    val incompleteDays: Int = 0,
) {
    /** Literal counts and fixed labels, one fact per line. */
    fun toText(): String = buildString {
        fun pct(d: Double?) = if (d == null) "n/a" else "${Math.round(d * 100)}%"
        fun map(m: Map<String, Int>) = if (m.isEmpty()) "none" else m.entries.joinToString(",") { "${it.key}=${it.value}" }
        fun leak(v: Int?) = v?.toString() ?: "UNCHECKED"
        appendLine("shadow-eval verdict=${verdict.label}")
        reasons.forEach { appendLine("reason: $it") }
        appendLine("observed_days=$observedDays (bar>=$MIN_DAYS) runs=$runs would_show=$wouldShowTotal")
        appendLine("invalid day keys: $invalidDayKeys incomplete_days=$incompleteDays")
        appendLine("max_would_show_in_a_day=$maxShownInADay cap=$dailyCap")
        appendLine("refused_by_gate: ${map(refusedByGate)}")
        appendLine("budget_exhausted=$budgetExhausted")
        appendLine("per_category: ${map(perCategory)}")
        appendLine("safety_leaks (must be 0): quiet=${leak(leaks.quiet)} zone=${leak(leaks.zone)} driving=${leak(leaks.driving)} unworn=${leak(leaks.unworn)}")
        appendLine("feedback: dismissed=$dismissed snoozed=$snoozed never=$never up=$up down=$down")
        appendLine("rates: dismiss=${pct(dismissRate)} snooze=${pct(snoozeRate)} never=${pct(neverRate)} useful=${pct(usefulRate)} (bar>=${Math.round(USEFUL_BAR * 100)}% of >=$MIN_RATED rated)")
        appendLine("harmful_or_false_claim: not measurable here, review the would-show list by hand (bar=0)")
        days.forEach { d ->
            appendLine("day ${d.day}: candidates=${d.candidates ?: "n/a"} passed=${d.passed} would_show=${d.wouldShow} refused: ${map(d.refusedByGate)} category: ${map(d.perCategory)} " +
                "leaks: quiet=${leak(d.leaks.quiet)} zone=${leak(d.leaks.zone)} driving=${leak(d.leaks.driving)} unworn=${leak(d.leaks.unworn)}")
        }
    }.trimEnd()

    companion object {
        const val MIN_DAYS = 7
        const val MIN_RATED = 5
        const val USEFUL_BAR = 0.60
    }
}

object ShadowEval {
    /** The gates that count as "budget exhausted". */
    private val BUDGET = listOf(Gate.CAP_REACHED, Gate.GLOBAL_CAP, Gate.FAMILY_MAX, Gate.SHOWN_GAP)

    /** Only counters named by the engine (`drop_<Gate>`) are read, so no unknown string can reach the report. */
    private fun refusals(c: Map<String, Int>): Map<String, Int> =
        Gate.entries.mapNotNull { g -> (c["drop_" + g.name.lowercase()] ?: 0).takeIf { it > 0 }?.let { g.name to it } }.toMap()

    fun evaluate(i: ShadowEvalInput): EvalReport {
        fun dayOf(ts: Long) = Instant.ofEpochMilli(ts).atZone(i.zone).toLocalDate().toString()
        // a throwing predicate counts as a leak (fail closed)
        fun count(rs: List<SuggestionRecord>, p: ((SuggestionRecord) -> Boolean)?): Int? = if (p == null) null else rs.count { try { p(it) } catch (e: Exception) { true } }
        fun leaks(rs: List<SuggestionRecord>) = LeakCounts(
            count(rs, i.quietAt?.let { q -> { r: SuggestionRecord -> q(r.ts) } }), count(rs, i.zoneBlocked),
            count(rs, i.drivingAt?.let { d -> { r: SuggestionRecord -> d(r.ts) } }), count(rs, i.wornAt?.let { w -> { r: SuggestionRecord -> !w(r.ts) } }))

        val byDay = i.records.groupBy { dayOf(it.ts) }
        // only canonical ISO dates survive; any other key (it could hold a place name) is dropped and only counted. Two keys for the same date merge by the larger count.
        val snaps = HashMap<String, Map<String, Int>>(); var invalidDayKeys = 0
        for ((k, v) in i.dailyCounters) {
            val d = try { java.time.LocalDate.parse(k).toString().takeIf { it == k } } catch (e: Exception) { null }
            if (d == null) invalidDayKeys++ else snaps[d] = snaps[d]?.let { o -> if ((o["would_show"] ?: 0) >= (v["would_show"] ?: 0)) o else v } ?: v
        }
        val dayKeys = (byDay.keys + snaps.keys).toSortedSet()
        var incompleteDays = 0
        val days = dayKeys.map { d ->
            val rs = byDay[d].orEmpty()
            val dc = snaps[d]
            // a snapshot that counted more would-show than the retained records is used (conservative) and marks the day's history incomplete
            val snapShow = dc?.get("would_show") ?: 0
            if (snapShow > rs.size) incompleteDays++
            val shown = maxOf(rs.size, snapShow)
            EvalDay(d, dc?.get("runs"), shown, dc?.let { refusals(it) } ?: emptyMap(), shown,
                rs.groupingBy { it.family.token }.eachCount().toSortedMap(), leaks(rs))
        }
        val total = days.sumOf { it.wouldShow }
        val leak = leaks(i.records)
        val refused = refusals(i.counters)
        val budget = BUDGET.sumOf { refused[it.name] ?: 0 }
        val fb = i.feedback
        val dismissed = fb.keys.values.sumOf { it.dismissCount }
        val snoozed = fb.keys.values.count { it.snoozeUntilMs > 0 }
        val up = fb.families.values.sumOf { it.up }; val down = fb.families.values.sumOf { it.down }
        fun rate(n: Int) = if (total > 0) n.toDouble() / total else null
        val rated = up + down
        val useful = if (rated > 0) up.toDouble() / rated else null
        val maxDay = days.maxOfOrNull { it.wouldShow } ?: 0

        val fail = mutableListOf<String>(); val nod = mutableListOf<String>()
        if (leak.total() > 0) fail += "safety invariant broken: ${leak.total()} would-show record(s) inside a quiet, zone, driving or unworn period"
        if (maxDay > i.dailyCap) fail += "a day had $maxDay would-show cards, cap is ${i.dailyCap}"
        if (useful != null && rated >= EvalReport.MIN_RATED && useful < EvalReport.USEFUL_BAR) fail += "useful rate ${Math.round(useful * 100)}% is below ${Math.round(EvalReport.USEFUL_BAR * 100)}%"
        if (incompleteDays > 0) nod += "$incompleteDays day(s) counted more would-show than the retained records, so the safety history for them is incomplete"
        if (leak.unchecked().isNotEmpty()) nod += "cannot check the safety invariant for: ${leak.unchecked().joinToString(",")}"
        if (dayKeys.size < EvalReport.MIN_DAYS) nod += "only ${dayKeys.size} observed day(s), need ${EvalReport.MIN_DAYS}"
        if (total == 0) nod += "no would-show records"
        if (rated < EvalReport.MIN_RATED) nod += "only $rated thumbs, need ${EvalReport.MIN_RATED}"
        val verdict = when { fail.isNotEmpty() -> EvalVerdict.FAIL; nod.isNotEmpty() -> EvalVerdict.NOT_ENOUGH_DATA; else -> EvalVerdict.PASS }
        val reasons = when (verdict) { EvalVerdict.FAIL -> fail; EvalVerdict.NOT_ENOUGH_DATA -> nod; EvalVerdict.PASS -> listOf("all measurable bars met") }

        return EvalReport(days, dayKeys.size, i.counters["runs"] ?: 0, total, refused, budget, i.records.groupingBy { it.family.token }.eachCount().toSortedMap(), leak,
            dismissed, snoozed, i.neverCount, up, down, rate(dismissed), rate(snoozed), rate(i.neverCount), useful, maxDay, i.dailyCap, verdict, reasons, invalidDayKeys, incompleteDays)
    }
}
