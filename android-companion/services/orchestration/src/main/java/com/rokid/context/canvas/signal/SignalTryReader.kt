package com.rokid.context.canvas.signal

import androidx.room.withTransaction
import com.rokid.context.ai.canvas.plan.ExprSeries
import com.rokid.context.capture.plan.SignalCatalog
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.provenance.ProvenanceGate

/**
 * Whether a recorded second may be shown: [CLEAR] (known to be outside every privacy zone), [HELD] (the zone fence was held) or [UNKNOWN] (no zone history exists for it). Only CLEAR seconds are used; the
 * others are excluded BEFORE evaluation, so they influence no window state and no summary (proxy ruling A.Q3).
 */
enum class ZoneStatus { CLEAR, HELD, UNKNOWN }

/**
 * The zone status of a recorded phone-clock second. `moment_sensor` rows carry no zone label and no zone history is kept, so production wires [UNAVAILABLE]: every second is UNKNOWN and the preview of today's
 * unlabelled history is refused honestly. A future source of zone history only has to implement this.
 */
fun interface ZoneHistory {
    fun status(second: Long): ZoneStatus
    companion object { val UNAVAILABLE = ZoneHistory { ZoneStatus.UNKNOWN } }
}

/**
 * The reader of the derived-signal try-it (v1.2 row 2): the numbers of the LATEST IMPORTED RECORDING (one trace revision of `moment_sensor` rows) in a window, plus the ids and times of the photo Moments in it. IDs, times and
 * numbers only: no frame id, no path, no text, no photo. Read under a `Leases.Kind.TRY_IT` lease over every trace and Moment (and the frames consulted) so a Forget or Delete all that lands at any point, also during the
 * evaluation in [read]'s `compute`, ends in [Outcome.Forgotten] and nothing is shown. The lease is verified with `verifyReadable` (trace and Moment liveness plus the trace revision) inside the one read transaction and
 * again right before the answer is returned. Nothing is written.
 */
class SignalTryReader(
    private val db: ContextDatabase,
    private val gate: DataOpGate = DataOpGate.shared,
    private val provenance: ProvenanceGate = ProvenanceGate(db),
    private val zones: ZoneHistory = ZoneHistory.UNAVAILABLE,
    private val generation: () -> Long = { MemoryWipe.generation.get() },
    private val nowS: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    class MomentRef(val id: String, val second: Long)

    /** What the preview is made of. [rows] are the CLEAR seconds of the recording inside the window; the counters are the denominators the screen shows. */
    class Snapshot(
        val fromS: Long, val toS: Long, val seconds: Int,
        val recordedFromMs: Long, val recordedToMs: Long?, val coverageFromS: Long, val coverageToS: Long, val coverageSeconds: Int,
        val rows: List<ExprSeries.Row>, val zoneHeld: Int, val zoneUnknown: Int, val moments: List<MomentRef>, val momentsTruncated: Boolean,
    )

    sealed class Outcome<out T> {
        class Done<T>(val value: T, val snapshot: Snapshot) : Outcome<T>()
        /** Nothing to show: [reason] is `no_sensor_data`, `no_zone_history` or `zone_held`; [snapshot] (rows empty) carries the counts when there was a recording. */
        class Unavailable(val reason: String, val snapshot: Snapshot? = null) : Outcome<Nothing>()
        /** A Forget or Delete all reached the data while it was read or evaluated: nothing is shown. */
        data object Forgotten : Outcome<Nothing>()
        /** A delete is running. */
        data object Busy : Outcome<Nothing>()
    }

    suspend fun <T> read(seconds: Int, compute: (Snapshot) -> T): Outcome<T> {
        require(seconds in 1..ExprSeries.MAX_SECONDS)
        val gen = generation()
        val lease = try { gate.lease(Leases.Kind.TRY_IT, emptyList(), gen, generation) } catch (_: DataOpGate.LeaseBlocked) { return Outcome.Busy }
        try {
            var revisions: Map<String, Int> = emptyMap()
            var unavailable: Outcome.Unavailable? = null
            var snap: Snapshot? = null
            val verified = db.withTransaction {
                val md = db.momentDao()
                val end = md.latestSensorSecond(nowS() - LOOK_BACK_S)
                if (end == null) { unavailable = Outcome.Unavailable("no_sensor_data"); return@withTransaction true }
                val from = end - seconds + 1
                val inWindow = md.sensorBetween(from, end)
                val newest = inWindow.last { it.phoneSecond == end }
                val trace = db.canvasSlice3Dao().readableTrace(newest.traceId)
                if (trace == null || trace.revision != newest.traceRevision) { unavailable = Outcome.Unavailable("no_sensor_data"); return@withTransaction true }
                revisions = mapOf(trace.traceId to trace.revision)
                lease.extend(listOf(Ref(Family.TRACE, trace.traceId)))
                val ofRecording = inWindow.filter { it.traceId == trace.traceId && it.traceRevision == trace.revision }
                var held = 0; var unknown = 0
                val clear = ArrayList<MomentSensorEntity>()
                for (r in ofRecording) when (zones.status(r.phoneSecond)) { ZoneStatus.CLEAR -> clear += r; ZoneStatus.HELD -> held++; ZoneStatus.UNKNOWN -> unknown++ }
                val moments = if (clear.isEmpty()) Triple(emptyList(), false, emptyList()) else momentsIn(from, end)
                lease.extend(moments.first.map { Ref(Family.MOMENT, it.id) } + moments.third.map { Ref(Family.PHOTO, it) })
                val s = Snapshot(
                    from, end, seconds, trace.phoneStartMs, trace.phoneEndMs,
                    md.firstSensorSecondOf(trace.traceId, trace.revision) ?: from, md.lastSensorSecondOf(trace.traceId, trace.revision) ?: end, md.sensorSecondCountOf(trace.traceId, trace.revision),
                    clear.map { toRow(it) }, held, unknown, moments.first, moments.second,
                )
                if (clear.isEmpty()) unavailable = Outcome.Unavailable(if (unknown > 0) "no_zone_history" else "zone_held", s) else snap = s
                lease.verifyReadable(db, revisions)
            }
            if (!verified) return Outcome.Forgotten
            unavailable?.let { u -> return if (lease.verifyReadable(db, revisions) && generation() == gen) u else Outcome.Forgotten }
            val s = snap!!
            val value = compute(s)
            // The last look: a Forget or Delete all that landed during the evaluation (revocation, wipe generation, trace or Moment gone or re-imported) means nothing is shown.
            return if (generation() == gen && lease.verifyReadable(db, revisions)) Outcome.Done(value, s) else Outcome.Forgotten
        } finally { lease.close() }
    }

    /** The newest [MAX_MOMENTS] photo Moments of the window whose second is CLEAR and that still hold a live photo; ids and seconds only. Third = the frame ids consulted (never returned). */
    private suspend fun momentsIn(from: Long, end: Long): Triple<List<MomentRef>, Boolean, List<String>> {
        if (from < 0 || end > MomentId.MAX_SECOND) return Triple(emptyList(), false, emptyList())
        val md = db.momentDao()
        val cands = md.momentsBetween(MomentId.format(from), MomentId.format(end)).mapNotNull { m -> MomentId.parse(m.momentId)?.takeIf { zones.status(it) == ZoneStatus.CLEAR }?.let { m.momentId to it } }
        if (cands.isEmpty()) return Triple(emptyList(), false, emptyList())
        val framesOf = cands.map { it.first }.chunked(CHUNK).flatMap { md.partsOf(it) }.filter { it.kind == MomentPartKind.FRAME }.groupBy({ it.momentId }, { it.refId })
        val gone = provenance.goneFrames(framesOf.values.flatten().toSet())
        val live = cands.filter { (id, _) -> framesOf[id].orEmpty().any { it !in gone } }
        val newest = live.takeLast(MAX_MOMENTS)
        return Triple(newest.map { MomentRef(it.first, it.second) }, live.size > MAX_MOMENTS, newest.flatMap { framesOf[it.first].orEmpty() }.filter { it !in gone }.distinct().take(MAX_FRAMES))
    }

    private fun toRow(r: MomentSensorEntity): ExprSeries.Row {
        val f = DoubleArray(SignalCatalog.featureNames.size)
        val by = mapOf("gyroMax" to r.gyroMax, "gyroMean" to r.gyroMean, "gyroRms" to r.gyroRms, "aboveOnFrac" to r.aboveOnFrac, "accVar" to r.accVar, "stepHz" to r.stepHz, "rotRad" to r.rotRad)
        SignalCatalog.featureNames.forEachIndexed { i, n -> f[i] = by[n] ?: Double.NaN }
        return ExprSeries.Row(r.phoneSecond, f, r.contextCode)
    }

    companion object {
        const val MAX_MOMENTS = 50
        const val MAX_FRAMES = 50
        const val LOOK_BACK_S = 7L * 24 * 3600
        private const val CHUNK = 400
    }
}
