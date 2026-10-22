package com.rokid.context.glasses.capture.session

import com.rokid.context.glasses.capture.motion.GlanceParams
import kotlin.test.Test
import kotlin.test.assertEquals
import com.rokid.context.glasses.capture.session.GlanceAdmission.Decision

class GlanceAdmissionTest {
    private val g = GlanceParams() // noveltyWindowMs 20 000, noveltyMinRad 0.30, bigTurnRad 0.8
    private val cap = 2

    /** The pre-extraction onDwell condition, verbatim (the reference the extract must equal). */
    private fun reference(since: Long, k: Int, hard: Boolean, turned: Float, walked: Boolean): Decision =
        if (since < g.noveltyWindowMs && turned < g.noveltyMinRad && !walked) Decision.NOVEL_SKIP
        else if (k >= cap && (hard || turned < g.bigTurnRad && !walked) || k.toLong() >= 2L * cap) Decision.CAPPED
        else Decision.OPEN

    /** 96 cases: recent x turned (below novelty, at the novelty edge, between, at the big-turn edge) x walked x hard cap x kept (cap-1, cap, 2*cap). */
    @Test fun matchesOnDwellTruthTable() {
        var n = 0
        for (since in listOf(5_000L, 30_000L)) for (turned in listOf(0.1f, g.noveltyMinRad, 0.5f, g.bigTurnRad))
            for (walked in listOf(false, true)) for (hard in listOf(false, true)) for (k in listOf(cap - 1, cap, 2 * cap)) {
                assertEquals(reference(since, k, hard, turned, walked), GlanceAdmission.decide(since, k, cap, hard, turned, walked, g), "since=$since turned=$turned walked=$walked hard=$hard k=$k")
                n++
            }
        assertEquals(96, n)
    }

    @Test fun noveltySkipsOnlyAStillRecentLookAndIsStrictAtTheEdges() {
        assertEquals(Decision.NOVEL_SKIP, GlanceAdmission.decide(5_000, 0, cap, false, 0.1f, false, g))
        assertEquals(Decision.OPEN, GlanceAdmission.decide(5_000, 0, cap, false, g.noveltyMinRad, false, g)) // turned == min is not novel-skipped
        assertEquals(Decision.OPEN, GlanceAdmission.decide(g.noveltyWindowMs, 0, cap, false, 0.1f, false, g)) // window edge is open
        assertEquals(Decision.OPEN, GlanceAdmission.decide(5_000, 0, cap, false, 0.1f, true, g)) // walked
    }

    @Test fun capRules() {
        assertEquals(Decision.OPEN, GlanceAdmission.decide(30_000, cap - 1, cap, false, 0.1f, false, g))
        assertEquals(Decision.CAPPED, GlanceAdmission.decide(30_000, cap, cap, false, 0.1f, false, g)) // small turn: no bonus
        assertEquals(Decision.OPEN, GlanceAdmission.decide(30_000, cap, cap, false, 1.0f, false, g)) // big turn: the dwell bonus
        assertEquals(Decision.CAPPED, GlanceAdmission.decide(30_000, cap, cap, true, 1.0f, false, g)) // hard cap: no bonus
        assertEquals(Decision.CAPPED, GlanceAdmission.decide(30_000, 2 * cap, cap, false, 1.0f, false, g)) // twice the cap is final
    }
}
