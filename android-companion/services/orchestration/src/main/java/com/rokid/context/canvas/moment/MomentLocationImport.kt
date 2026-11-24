package com.rokid.context.canvas.moment

import androidx.room.withTransaction
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneEvaluator
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentLocationEntity

/**
 * v18 WP5: the owner-started GPS backfill. It writes `moment_location` rows with `source = MEDIA_BACKFILL` through `insertIfAbsent` only: a row that is already there (live or imported) is never overwritten, and a
 * Moment that was forgotten (a `moment_tombstone`, or for a photo anchor a `forget_tombstone`) is refused. A row is also refused when the Moment or its anchor part is missing, when the point lies in or near a pause zone
 * (null zones block), when the second was paused, or when the setting is off. Chunks of [CHUNK], one transaction each, which re-checks the Forget authority. Only counts leave this class: no id, no coordinate, no log line.
 */
class MomentLocationImport(
    private val db: ContextDatabase,
    private val wall: () -> Long,
    private val enabled: () -> Boolean,
    private val zones: suspend () -> List<PauseZone>?,
    private val generation: () -> Long,
) {
    data class Row(
        val momentId: String, val atMs: Long, val anchorKind: String, val anchorId: String, val lat: Double, val lon: Double,
        val accuracyM: Double, val fixAgeMs: Long, val speedClass: String, val method: String,
    )

    /** [rows] are the valid data rows, [badRows] the lines that failed validation (they count `bad_row`). [skipped] is keyed by a fixed reason token. */
    data class Report(val received: Int, val written: Int, val skipped: Map<String, Int>)

    suspend fun run(rows: List<Row>, badRows: Int = 0): Report {
        val skipped = linkedMapOf<String, Int>()
        fun skip(reason: String, n: Int = 1) { if (n > 0) skipped[reason] = (skipped[reason] ?: 0) + n }
        skip("bad_row", badRows)
        var written = 0
        val dao = db.momentLocationDao()
        val moments = db.momentDao()
        val s3 = db.canvasSlice3Dao()
        val zoneList = if (rows.isEmpty()) emptyList() else zones()
        for (chunk in rows.chunked(CHUNK)) {
            val rev = s3.frameForgetRev(); val gen = generation()
            val now = wall()
            db.withTransaction {
                for (r in chunk) {
                    val reason = when {
                        !enabled() -> "setting_off"
                        s3.frameForgetRev() != rev || generation() != gen -> "authority"
                        moments.moment(r.momentId) == null -> "no_moment"
                        moments.parts(r.momentId).none { it.kind == r.anchorKind && it.refId == r.anchorId } -> "no_anchor"
                        dao.momentTombstoned(r.momentId) > 0 || (r.anchorKind == "FRAME" && dao.frameTombstoned(r.anchorId) > 0) -> "tombstoned"
                        ZoneEvaluator.blocksPoint(zoneList, r.lat, r.lon, r.accuracyM.toFloat()) -> "zone"
                        db.pauseDao().countCovering(r.atMs) > 0 -> "paused"
                        dao.insertIfAbsent(MomentLocationEntity(r.momentId, r.atMs, r.anchorKind, r.anchorId, r.lat, r.lon, r.accuracyM, r.fixAgeMs, SOURCE, r.speedClass, r.method, now)) < 0 -> "present"
                        else -> null
                    }
                    if (reason == null) written++ else skip(reason)
                }
            }
        }
        return Report(rows.size + badRows, written, skipped)
    }

    companion object {
        const val CHUNK = 200
        const val SOURCE = "MEDIA_BACKFILL"
        const val HEADER = "momentId\tatMs\tanchorKind\tanchorId\tlat\tlon\taccuracyM\tfixAgeMs\tspeedClass\tmethod"
        private val KINDS = setOf("FRAME", "MARK")
        private val CLASSES = setOf("STATIONARY", "WALKING", "VEHICLE", "UNKNOWN")
        private val METHODS = setOf("INTERPOLATED", "NEAREST")

        /** One TSV data line, or null when any field fails validation (the line is then a `bad_row`). */
        fun parseRow(line: String): Row? {
            val f = line.split('\t')
            if (f.size != 10) return null
            val id = f[0]
            val start = MomentId.startMs(id) ?: return null
            val at = f[1].toLongOrNull() ?: return null
            val lat = f[4].toDoubleOrNull() ?: return null
            val lon = f[5].toDoubleOrNull() ?: return null
            val acc = f[6].toDoubleOrNull() ?: return null
            val age = f[7].toLongOrNull() ?: return null
            if (f[2] !in KINDS || f[3].isBlank() || f[8] !in CLASSES || f[9] !in METHODS) return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            if (!acc.isFinite() || acc <= 0.0 || acc > 5000.0) return null
            if (age !in 0..3_600_000L) return null
            if (Math.abs(at - start) > 60_000L) return null
            return Row(id, at, f[2], f[3], lat, lon, acc, age, f[8], f[9])
        }
    }
}
