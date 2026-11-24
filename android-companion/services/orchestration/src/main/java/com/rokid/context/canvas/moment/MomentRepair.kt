package com.rokid.context.canvas.moment

import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.ContextDatabase
import java.time.Instant
import java.time.ZoneId

/**
 * P1 retention fix: puts back the stored Moments (and their Scenes) that an older retention pass dropped. Nothing here calls a model, and it is fenced exactly like the builders (a tombstoned or
 * Forgotten second is refused, so a repair can never resurrect it). Run on demand by the Developer route `POST /api/moments/repair`; counts-only log line.
 *
 * Two independent gaps, both re-derived from the stored state on every run, so a run that stopped half way (a crash, a failed Scene rebuild) is simply resumed by the next one:
 *  - **Moment gaps** (retention r1 finding 6): the seconds that hold a live photo or a mark but have no stored Moment. Only THOSE seconds are built ([MomentBuilder.build], in chunks of [CHUNK_SECONDS], each its own
 *    transaction): a Moment that already exists is never rebuilt or overwritten, so text kept after its source facts expired is not replaced by a rebuild from the survivors.
 *  - **Scene gaps** (finding 7): the stored Moments that no Scene holds, found on their own (a repair whose Moment pass committed and whose Scene pass failed is visible to the next run). Each affected clock hour
 *    is rebuilt on its own ([SceneBuilder.rebuildWindow], one transaction per hour: finding 8), and a Scene that is already right is left unchanged by the builder.
 * Bounded work: no transaction ever covers a day (finding 8).
 */
class MomentRepair(
    private val db: ContextDatabase,
    private val moments: MomentBuilder,
    private val scenes: SceneBuilder,
    /** Seconds per Moment build transaction (test seam; production [CHUNK_SECONDS]). */
    private val chunkSeconds: Int = CHUNK_SECONDS,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** [daysChecked] local days looked at, [daysRepaired] the ones that had a Moment or Scene gap, [missing] the seconds with no stored Moment before the repair, [momentsStored] / [scenesStored] what the rebuilds wrote, [scenesMissing] the stored Moments no Scene held before the repair. */
    data class Report(val daysChecked: Int, val daysRepaired: Int, val missing: Int, val momentsStored: Int, val scenesStored: Int, val scenesMissing: Int = 0)

    suspend fun run(nowMs: Long, days: Int = DEFAULT_DAYS): Report {
        val n = days.coerceIn(1, MAX_DAYS)
        val z = zone()
        val today = Instant.ofEpochMilli(nowMs).atZone(z).toLocalDate()
        val dao = db.momentDao()
        var repaired = 0; var missing = 0; var stored = 0; var scenesStored = 0; var scenesMissing = 0
        for (back in 0 until n) {
            val d = today.minusDays(back.toLong())
            val from = d.atStartOfDay(z).toInstant().toEpochMilli()
            val to = d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - 1
            var touched = false
            // 1. Moment gaps: build only the seconds that have no stored Moment
            val wanted = (dao.liveFrameSeconds(from, to) + dao.labelSeconds(from, to)).distinct().filter { it in 0..MomentId.MAX_SECOND }.map { MomentId.format(it) }
            if (wanted.isNotEmpty()) {
                val have = dao.momentIdsBetween(MomentId.format(from / 1000), MomentId.format(to / 1000)).toSet()
                val gap = wanted.filter { it !in have }
                if (gap.isNotEmpty()) {
                    touched = true; missing += gap.size
                    for (chunk in gap.chunked(chunkSeconds.coerceAtLeast(1))) stored += moments.build(chunk).stored
                }
            }
            // 2. Scene gaps: stored Moments that no Scene holds, independent of step 1, one clock hour at a time
            val unscened = dao.momentIdsWithoutScene(MomentId.format(from / 1000), MomentId.format(to / 1000))
            if (unscened.isNotEmpty()) {
                touched = true; scenesMissing += unscened.size
                val hours = unscened.mapNotNull { MomentId.startMs(it) }.map { Math.floorDiv(it, HOUR_MS) * HOUR_MS }.distinct().sorted()
                for (h in hours) scenesStored += scenes.rebuildWindow(h, h + HOUR_MS - 1).stored
            }
            if (touched) repaired++
        }
        val repairedDays = repaired; val missingSeconds = missing; val momentsStored = stored; val scenesBuilt = scenesStored; val unscenedMoments = scenesMissing
        PcLog.milestone("PcMoments", "repair done: days=$n repairedDays=$repairedDays missingSeconds=$missingSeconds momentsStored=$momentsStored scenesBuilt=$scenesBuilt unscenedMoments=$unscenedMoments")
        return Report(n, repaired, missing, stored, scenesStored, scenesMissing)
    }

    companion object { const val DEFAULT_DAYS = 30; const val MAX_DAYS = 90; const val CHUNK_SECONDS = 200; private const val HOUR_MS = 3_600_000L }
}
