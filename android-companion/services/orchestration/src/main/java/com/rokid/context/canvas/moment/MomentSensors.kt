package com.rokid.context.canvas.moment

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.SecondFeatureRow
import com.rokid.context.capture.trace.TraceBounds
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.capture.trace.TraceSecondFeatures
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.TraceClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the per-second sensor features of a Moment come from: the stored (imported) IMU traces, read from `files/canvas_traces/<traceId>` and turned into phone-clock seconds by
 * [TraceSecondFeatures]. Reading and computing run on [io], never on the caller's thread, and BEFORE the builder's database transaction (the transaction only writes, and re-checks that
 * every trace it used is still the one that was read: [validTraces]).
 *
 * A second gets a row ONLY from a trace that is live (not tombstoned, not quarantined), recorded under the current deletion epoch, mapped to the phone clock ([TraceClock.OFFSET_ESTIMATED])
 * and whose clock uncertainty is at most [maxUncertaintyMs]; an unmapped, quarantined or more uncertain trace gives nothing (a second is never guessed). When several traces cover one second
 * the one with the most samples wins, then the smaller trace id. A trace whose files cannot be read gives nothing.
 */
class MomentSensors(
    private val dao: CanvasSlice3Dao,
    private val tracesRoot: File,
    /** The persisted deletion epoch NOW (a trace recorded under another install or an older counter is ignored). Null only in tests that do not model epochs. */
    private val epoch: (() -> DeletionEpoch)? = null,
    private val maxUncertaintyMs: Long = MAX_UNCERTAINTY_MS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** What was read for one trace: the identity the numbers were computed from, and its rows by phone second. */
    class TraceRows(val traceId: String, val revision: Int, val manifestJson: String, val clockQuality: String, val uncertaintyMs: Long, val rows: Map<Long, SecondFeatureRow>)

    /** The result of [load]: the phone [seconds] that were asked for (only those get decided) and the traces that were read for them. */
    class Loaded(val seconds: Set<Long>, val traces: List<TraceRows>)

    /** Test seam: called (on the reading thread) before each chunk file is read. Always null in production. */
    @Volatile internal var testOnFileRead: ((String) -> Unit)? = null

    private val cache = object : LinkedHashMap<String, Map<Long, SecondFeatureRow>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Long, SecondFeatureRow>>?) = size > CACHE_TRACES
    }

    suspend fun load(seconds: Collection<Long>): Loaded = withContext(io) {
        val wanted = seconds.toSet()
        if (wanted.isEmpty()) return@withContext Loaded(wanted, emptyList())
        val traces = dao.liveTraces().filter { eligible(it, wanted) }
        Loaded(wanted, traces.mapNotNull { t -> read(t)?.let { TraceRows(t.traceId, t.revision, t.manifestJson, t.clockQuality, t.uncertaintyMs, it) } })
    }

    private fun eligible(t: CanvasTraceEntity, seconds: Set<Long>): Boolean {
        if (t.source != "IMU" || t.tombstoned || t.clockQuality != TraceClock.OFFSET_ESTIMATED || t.uncertaintyMs !in 0..maxUncertaintyMs) return false
        epoch?.invoke()?.let { e -> if (t.installId != e.installId || t.deletionCounter != e.counter) return false }
        val end = t.phoneEndMs ?: return false
        return seconds.any { it * 1000 + 999 >= t.phoneStartMs && it * 1000 <= end }
    }

    private fun read(t: CanvasTraceEntity): Map<Long, SecondFeatureRow>? {
        if (!TraceBounds.isTraceId(t.traceId)) return null
        val key = t.traceId + "@" + t.revision + "@" + t.manifestJson.hashCode()
        synchronized(cache) { cache[key]?.let { return it } }
        val manifest = Manifest.decodeStrict(t.manifestJson).getOrNull() ?: return null
        val dir = File(tracesRoot, TraceFiles.dirName(t.traceId))
        if (!dir.isDirectory) return null
        val rows = try {
            val events = (0 until manifest.chunkCount).asSequence().flatMap { seq ->
                val name = TraceFiles.chunkFile(seq)
                testOnFileRead?.invoke(name)
                val tc = TraceFiles.decodeFile(File(dir, name).readText(Charsets.UTF_8)).getOrThrow()
                ChunkCodec.decode(tc).getOrThrow().events.asSequence()
            }
            TraceSecondFeatures.compute(manifest, events).associateBy { it.phoneSecond }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        synchronized(cache) { cache[key] = rows }
        return rows
    }

    /**
     * The traces of [loaded] that are still exactly what was read, checked INSIDE the caller's transaction: row present, not tombstoned, same revision, manifest, clock quality and
     * uncertainty. A trace that was forgotten, deleted or imported again since the read is not used.
     */
    suspend fun validTraces(loaded: Loaded): Set<String> = loaded.traces.filter { t ->
        val row = dao.trace(t.traceId)
        row != null && !row.tombstoned && row.revision == t.revision && row.manifestJson == t.manifestJson && row.clockQuality == t.clockQuality && row.uncertaintyMs == t.uncertaintyMs
    }.map { it.traceId }.toSet()

    /** The sensor row of [second] (null = none) from the valid traces of [loaded]: most samples, then smaller trace id. */
    fun rowFor(loaded: Loaded, second: Long, valid: Set<String>): MomentSensorEntity? {
        val best = loaded.traces.filter { it.traceId in valid && second in it.rows }
            .minWithOrNull(compareBy<TraceRows>({ -(it.rows.getValue(second).let { r -> r.nGyro + r.nAcc }) }, { it.traceId })) ?: return null
        val r = best.rows.getValue(second)
        return MomentSensorEntity(
            MomentId.format(second), second, best.traceId, best.revision, best.clockQuality, best.uncertaintyMs,
            r.gyroMax, r.gyroMean, r.gyroRms, r.aboveOnFrac, r.accVar, r.stepHz, r.rotRad, r.state.name, r.context?.name, r.nGyro, r.nAcc,
        )
    }

    companion object {
        /**
         * The clock tolerance of a sensor second: above it a second could land in the neighbouring phone second (moment-model spec 7.4: "uncertainty larger than 500 ms may put a part one
         * second off"), so the trace gives no row. The uncertainty is the trace's [com.rokid.context.capture.trace.ClockMap] one (offset, drift and sample age), never capped.
         */
        const val MAX_UNCERTAINTY_MS = 500L
        private const val CACHE_TRACES = 3
    }
}
