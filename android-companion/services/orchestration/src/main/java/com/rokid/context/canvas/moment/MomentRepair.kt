package com.rokid.context.canvas.moment

import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.ContextDatabase
import java.time.Instant
import java.time.ZoneId

/**
 * P1 retention fix: puts back the stored Moments (and their Scenes) that an older retention pass dropped. For each of the last [days] local days it finds the seconds that hold a live photo
 * but have no stored Moment and, for a day with any, runs the SAME window rebuilds the cadence runs ([MomentBuilder.rebuildWindow], then [SceneBuilder.rebuildWindow]). Nothing here calls a
 * model. Idempotent (a second run finds nothing to do for a day that was repaired, and the builders write nothing that is unchanged) and fenced exactly like the builders (a tombstoned or
 * Forgotten second is refused, so a repair can never resurrect it). Run on demand by the Developer route `POST /api/moments/repair`; counts-only log line.
 */
class MomentRepair(
    private val db: ContextDatabase,
    private val moments: MomentBuilder,
    private val scenes: SceneBuilder,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** [daysChecked] local days looked at, [daysRepaired] the ones that had a photo second with no stored Moment, [missing] those seconds before the repair, [momentsStored] / [scenesStored] what the rebuilds wrote. */
    data class Report(val daysChecked: Int, val daysRepaired: Int, val missing: Int, val momentsStored: Int, val scenesStored: Int)

    suspend fun run(nowMs: Long, days: Int = DEFAULT_DAYS): Report {
        val n = days.coerceIn(1, MAX_DAYS)
        val z = zone()
        val today = Instant.ofEpochMilli(nowMs).atZone(z).toLocalDate()
        val dao = db.momentDao()
        var repaired = 0; var missing = 0; var mStored = 0; var sStored = 0
        for (back in 0 until n) {
            val d = today.minusDays(back.toLong())
            val from = d.atStartOfDay(z).toInstant().toEpochMilli()
            val to = d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - 1
            val live = dao.liveFrameSeconds(from, to).filter { it in 0..MomentId.MAX_SECOND }.map { MomentId.format(it) }
            if (live.isEmpty()) continue
            val stored = dao.momentIdsBetween(MomentId.format(from / 1000), MomentId.format(to / 1000)).toSet()
            val gap = live.count { it !in stored }
            if (gap == 0) continue
            repaired++; missing += gap
            mStored += moments.rebuildWindow(from, to).stored
            sStored += scenes.rebuildWindow(from, to).stored
        }
        PcLog.milestone("PcMoments", "repair done: days=$n repaired=$repaired missing=$missing momentsStored=$mStored scenesStored=$sStored")
        return Report(n, repaired, missing, mStored, sStored)
    }

    companion object { const val DEFAULT_DAYS = 30; const val MAX_DAYS = 90 }
}
