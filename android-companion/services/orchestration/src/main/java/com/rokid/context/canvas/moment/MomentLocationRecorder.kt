package com.rokid.context.canvas.moment

import androidx.room.withTransaction
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneEvaluator
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentLocationEntity
import kotlin.coroutines.cancellation.CancellationException

/**
 * A fix the phone ALREADY holds. [at] is on the [elapsedClock] (monotonic, the zone stream and the Place Guide fix) or the wall clock (the system last-known fix).
 * [generation] is the Delete all generation the fix was made under (Place Guide only); a fix of another generation is never used.
 */
data class HeldFix(val lat: Double, val lon: Double, val accM: Float?, val at: Long, val elapsedClock: Boolean, val generation: Long? = null)

/**
 * v18 WP4: the live location of a new Moment (plan section "New Moments: the recorder"). Pure logic, every input injected. It never requests a location: it only reads fixes held in memory (the pause-zone stream,
 * the last Place Guide fix, the system's last-known) and keeps one only when it lies within [MAX_GAP_MS] of the capture and is at most [MAX_ACC_M] metres wide. It refuses when the setting is off, a pause zone
 * is active, the fix lies in or near a zone, or the second is paused. The row is written with `insertIfAbsent` inside a transaction that re-checks the Forget authority, the anchor and the tombstones.
 * It never changes a Moment: `MomentBuilder` does not read `moment_location`, so `contentHash` is the same with or without the row. Log lines carry counts and fixed tokens only, never ids or coordinates.
 */
class MomentLocationRecorder(
    private val db: ContextDatabase,
    private val wall: () -> Long,
    private val elapsed: () -> Long,
    private val enabled: () -> Boolean,
    private val zoneBlocked: suspend () -> Boolean,
    private val zones: suspend () -> List<PauseZone>?,
    private val zoneFix: () -> HeldFix?,
    private val placeFix: () -> HeldFix?,
    private val lastKnown: () -> HeldFix?,
    private val generation: () -> Long,
    private val log: (String) -> Unit,
) {
    private class Candidate(val momentId: String, val atMs: Long, val kind: String, val anchorId: String)

    /** Looks at the last [LOOKBACK_MS] of frames and marks and saves what it can. Returns the number of rows written. Never throws (except cancellation). */
    suspend fun poke(): Int {
        val skipped = linkedMapOf<String, Int>()
        fun skip(reason: String, n: Int = 1) { skipped[reason] = (skipped[reason] ?: 0) + n }
        var saved = 0
        try {
            if (!enabled()) { skip("off") }
            else if (zoneBlocked()) { skip("zone_state") }
            else saved = run(::skip)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { skip("error_" + e.javaClass.simpleName) }
        if (saved > 0 || skipped.isNotEmpty()) {
            val skippedLine = skipped.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }
            log("moment loc poke saved=$saved skipped=$skippedLine")
        }
        return saved
    }

    private suspend fun run(skip: (String, Int) -> Unit): Int {
        val s3 = db.canvasSlice3Dao()
        val dao = db.momentLocationDao()
        val rev = s3.frameForgetRev()
        val gen = generation()
        val now = wall()
        val from = now - LOOKBACK_MS
        val raw = dao.framesSince(from).map { it to "FRAME" } + dao.marksSince(from).map { it to "MARK" }
        val byId = linkedMapOf<String, Candidate>()
        for ((a, kind) in raw.sortedBy { it.first.atMs }) {
            val id = try { MomentId.ofMs(a.atMs) } catch (_: IllegalArgumentException) { continue }
            if (id !in byId) byId[id] = Candidate(id, a.atMs, kind, a.anchorId)
        }
        if (byId.isEmpty()) return 0
        val held = dao.of(byId.keys).map { it.momentId }.toSet()
        val todo = byId.values.filter { it.momentId !in held }
        if (todo.isEmpty()) return 0
        // The three fixes are read once per poke; each is a value already in memory (the last-known read is a cheap system call).
        val sources = listOf(
            "ZONE_STREAM" to safe(zoneFix), "PLACE_GUIDE" to safe(placeFix).takeIf { it == null || it.generation == null || it.generation == gen }, "LAST_KNOWN" to safe(lastKnown),
        )
        var zonesRead = false; var zonesValue: List<PauseZone>? = null
        var saved = 0
        for (c in todo) {
            val pick = sources.firstNotNullOfOrNull { (name, f) -> f?.let { fix -> usable(fix, c.atMs, now)?.let { gap -> Triple(name, fix, gap) } } }
            if (pick == null) { skip("no_fix", 1); continue }
            val (source, fix, gap) = pick
            if (!zonesRead) { zonesValue = zones(); zonesRead = true }
            if (ZoneEvaluator.blocksPoint(zonesValue, fix.lat, fix.lon, fix.accM!!)) { skip("zone_point", 1); continue }
            if (db.pauseDao().countCovering(c.atMs) > 0) { skip("paused", 1); continue }
            val row = MomentLocationEntity(c.momentId, c.atMs, c.kind, c.anchorId, fix.lat, fix.lon, fix.accM.toDouble(), gap, source, null, null, now)
            val reason = db.withTransaction {
                when {
                    !enabled() -> "off" // switched off while this poke ran: "Delete saved locations" must not be followed by a late row
                    s3.frameForgetRev() != rev || generation() != gen -> "authority"
                    (if (c.kind == "FRAME") dao.frameAnchorExists(c.anchorId) else dao.markAnchorExists(c.anchorId)) == 0 -> "gone"
                    (c.kind == "FRAME" && dao.frameTombstoned(c.anchorId) > 0) || dao.momentTombstoned(c.momentId) > 0 -> "tombstoned"
                    dao.insertIfAbsent(row) < 0 -> "present"
                    else -> null
                }
            }
            if (reason == null) saved++ else skip(reason, 1)
        }
        return saved
    }

    private fun safe(f: () -> HeldFix?): HeldFix? = try { f() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** The gap |fix - capture| in ms when [fix] may be used for a capture at [captureMs], else null. */
    private fun usable(fix: HeldFix, captureMs: Long, now: Long): Long? {
        val acc = fix.accM ?: return null
        if (acc.isNaN() || acc <= 0f || acc > MAX_ACC_M) return null
        if (!fix.lat.isFinite() || !fix.lon.isFinite()) return null
        val fixWall = if (fix.elapsedClock) now - (elapsed() - fix.at) else fix.at
        val gap = Math.abs(fixWall - captureMs)
        return if (gap <= MAX_GAP_MS) gap else null
    }

    companion object {
        const val LOOKBACK_MS = 120_000L
        const val MAX_GAP_MS = 60_000L
        const val MAX_ACC_M = 100f
    }
}
