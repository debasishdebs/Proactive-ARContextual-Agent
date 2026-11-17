package com.rokid.context.orchestration.proactive

import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.Verbosity
import com.rokid.context.orchestration.nudge.NudgeHud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RankerBudgetTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val noon = LocalDateTime.of(2026, 11, 18, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private val hud = NudgeHud(QuietHours(22 * 60, 7 * 60), Verbosity.MEDIUM, zone)
    private fun cand(key: String = "f2:a", conf: Double = 0.9, arrived: Long = noon) = Candidate(key, ProactiveFamily.PLACE_RECALL, "wd:Q1", listOf("mem:m"), PlaceFacts("A", 0, 1, null, 0), conf, arrived)
    private fun rec(key: String, at: Long) = SuggestionRecord("sg_$key$at", at, key, ProactiveFamily.PLACE_RECALL, TriggerKind.TICK, 0.7, "wd:Q1", listOf("mem:m"), emptyList())
    private val budget = ProactiveBudget()
    private fun check(c: Candidate, recs: List<SuggestionRecord>, now: Long = noon, today: Int = 0, cap: Int = 6) = budget.check(c, BudgetState(recs, today, cap), hud, now)

    @Test fun scoreIsPriorTimesConfidenceTimesRecency() {
        assertEquals(0.9 * 0.9, ProactiveRanker.score(cand(), noon), 1e-9)
        assertEquals(0.9 * 0.9 * 0.5, ProactiveRanker.score(cand(), noon + 20 * 60_000L), 1e-9)
        assertEquals(1.0, ProactiveRanker.recency(noon, noon), 0.0); assertEquals(0.75, ProactiveRanker.recency(noon, noon + 12.5.times(60_000).toLong()), 1e-9)
    }

    @Test fun rankIsBestFirstThenByKey() {
        val r = ProactiveRanker.rank(listOf(cand("f2:b", 0.5), cand("f2:a", 0.9), cand("f2:c", 0.9)), noon)
        assertEquals(listOf("f2:a", "f2:c", "f2:b"), r.map { it.first.key })
        assertTrue(ProactiveRanker.score(cand(conf = 0.3, arrived = noon - 60 * 60_000L), noon) < ProactiveRanker.THRESHOLD)
    }

    @Test fun budgetTable() {
        assertEquals(Gate.WOULD_SHOW, check(cand(), emptyList()))
        assertEquals(Gate.OFF, check(cand(), emptyList(), cap = 0))
        // quiet hours always win, dropped not deferred
        val night = LocalDateTime.of(2026, 11, 18, 23, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(Gate.QUIET_HOURS, check(cand(), emptyList(), now = night))
        // cap 3/day
        val three = listOf(rec("k1", noon - 4 * 3_600_000L), rec("k2", noon - 3 * 3_600_000L), rec("k3", noon - 2 * 3_600_000L))
        assertEquals(Gate.CAP_REACHED, check(cand("f2:new"), three))
        // inside the global cap: nudges today + suggestions today
        assertEquals(Gate.GLOBAL_CAP, check(cand("f2:new"), listOf(rec("k1", noon - 4 * 3_600_000L)), today = 5, cap = 6))
        // family max 2
        assertEquals(Gate.FAMILY_MAX, check(cand("f2:new"), listOf(rec("k1", noon - 5 * 3_600_000L), rec("k2", noon - 4 * 3_600_000L))))
        // 30 min gap
        assertEquals(Gate.SHOWN_GAP, check(cand("f2:new"), listOf(rec("k1", noon - 10 * 60_000L))))
        assertEquals(Gate.WOULD_SHOW, check(cand("f2:new"), listOf(rec("k1", noon - 31 * 60_000L))))
        // per-key cooldown 24h
        assertEquals(Gate.COOLDOWN, check(cand("f2:a"), listOf(rec("f2:a", noon - 3 * 3_600_000L))))
        assertEquals(Gate.WOULD_SHOW, check(cand("f2:a"), listOf(rec("f2:a", noon - 25 * 3_600_000L))))
        // yesterday's records do not count against today's cap
        assertEquals(Gate.WOULD_SHOW, check(cand("f2:new"), listOf(rec("k1", noon - 30 * 3_600_000L), rec("k2", noon - 29 * 3_600_000L), rec("k3", noon - 28 * 3_600_000L))))
    }
}
