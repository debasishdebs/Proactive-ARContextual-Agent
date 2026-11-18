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

    // ---- v3.1 r1 P1-5: the ledger, not the retained records, is the consumption ----------------------------------------------------------------------

    private fun led(at: Long, kind: LedgerKind = LedgerKind.DELIVERED) = LedgerEntry(at, ProactiveFamily.PLACE_RECALL, kind)
    private fun checkL(c: Candidate, ledger: List<LedgerEntry>, now: Long = noon, recs: List<SuggestionRecord> = emptyList()) = budget.check(c, BudgetState(recs, 0, 6, ledger), hud, now)

    @Test fun deliveredLedgerLinesCountEvenThoughTheRecordsWereDismissed() {
        val three = listOf(led(noon - 4 * 3_600_000L), led(noon - 3 * 3_600_000L), led(noon - 2 * 3_600_000L))
        assertEquals(Gate.CAP_REACHED, checkL(cand("f2:other-place"), three))
        assertEquals(Gate.FAMILY_MAX, checkL(cand("f2:p3"), three.take(2)))
        assertEquals(Gate.SHOWN_GAP, checkL(cand("f2:p4"), listOf(led(noon - 10 * 60_000L))))
        assertEquals(Gate.WOULD_SHOW, checkL(cand("f2:p4"), listOf(led(noon - 31 * 60_000L))))
        assertEquals("a delivered record is counted once (by the ledger)", Gate.WOULD_SHOW, checkL(cand("f2:p5"), listOf(led(noon - 4 * 3_600_000L)), recs = listOf(rec("f2:x", noon - 4 * 3_600_000L).copy(delivered = setOf("phone")))))
        assertEquals("a retained would-show that was never delivered still counts", Gate.SHOWN_GAP, checkL(cand("f2:p6"), emptyList(), recs = listOf(rec("f2:y", noon - 5 * 60_000L))))
    }

    @Test fun fiveDismissalsInADayPauseDeliveryForADayFromTheFifth() {
        val d = (0 until 5).map { led(noon - 10 * 3_600_000L + it * 3_600_000L, LedgerKind.DISMISSED) } // the fifth at noon - 6 h
        assertEquals(Gate.DISMISS_PAUSE, checkL(cand("f2:n"), d, now = noon))
        assertEquals(noon - 6 * 3_600_000L + 24 * 3_600_000L, DeliveryLedger.pausedUntil(d))
        val open = hud.copy(quietHours = null); val end = noon + 18 * 3_600_000L
        assertEquals(Gate.DISMISS_PAUSE, budget.check(cand("f2:n"), BudgetState(emptyList(), 0, 6, d), open, end - 1))
        assertEquals("the pause ends 24 h after the fifth", Gate.WOULD_SHOW, budget.check(cand("f2:n"), BudgetState(emptyList(), 0, 6, d), open, end))
        assertEquals(Gate.WOULD_SHOW, checkL(cand("f2:n"), d.take(4), now = noon))
        val spread = (0 until 5).map { led(noon - 50 * 3_600_000L + it * 7 * 3_600_000L, LedgerKind.DISMISSED) } // five, but not inside one day
        assertEquals(0L, DeliveryLedger.pausedUntil(spread))
    }

    @Test fun theLedgerIsPrunedAfter48HoursAndBounded() {
        val old = led(noon - 49 * 3_600_000L); val fresh = led(noon - 47 * 3_600_000L)
        assertEquals(listOf(fresh), DeliveryLedger.prune(listOf(old, fresh), noon))
        assertEquals(DeliveryLedger.MAX_ENTRIES, DeliveryLedger.prune((0 until 900).map { led(noon - it * 1_000L) }, noon).size)
    }
}
