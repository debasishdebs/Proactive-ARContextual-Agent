package com.rokid.context.canvas.trace

import com.rokid.context.capture.trace.Manifest
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.TraceClock
import kotlinx.coroutines.CancellationException

/**
 * First-run job (spec 7.2): a row imported before Room v10 has only glasses wall times and the conservative `UNMAPPED` default (selected by every range). This maps it to the phone
 * clock from its STORED manifest, with the same [TracePhoneClock] rule as the import. A row whose manifest gives no usable clock stays UNMAPPED (never guessed); a hub row is on the
 * phone clock already; a tombstoned row is never touched. Idempotent. Returns the number of rows mapped.
 */
class TraceClockBackfill(private val dao: CanvasSlice3Dao) {
    suspend fun run(): Int {
        var mapped = 0
        for (row in dao.liveTraces()) {
            if (row.clockQuality != TraceClock.UNMAPPED) continue
            val interval = when {
                row.source == "HUB" -> TracePhoneClock.Interval(row.startedAtMs, row.endedAtMs, TraceClock.EXACT, 0)
                else -> try {
                    Manifest.decodeStrict(row.manifestJson).getOrNull()?.let(TracePhoneClock::of)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            }
            if (interval == null || interval.clockQuality == TraceClock.UNMAPPED) continue
            if (dao.mapTraceClock(row.traceId, row.manifestJson, interval.phoneStartMs, interval.phoneEndMs, interval.clockQuality, interval.uncertaintyMs) > 0) mapped++
        }
        return mapped
    }
}
