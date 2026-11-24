package com.rokid.context.memory.forget

import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.ForgetDao
import org.json.JSONObject

/**
 * The durable expired-photo marker (retention v3, M1). "Expired" versus "forgotten" is DATA, not an inference from `frame_meta`:
 *  - `rx:f:<frameId>` -> `{"at":<expiryMs>,"ts":<captureMs|null>}`: written in the SAME transaction as the tombstone and the `frame` row delete of a retention photo expiry;
 *  - `rx:t:<captureMs, 13 digits>:<frameId>` -> "": the same marker keyed by capture time, so a pin sweep can ask "was a photo of this second expired" with one range read.
 * A photo is FORGOTTEN when it is tombstoned and NOT marked (`ProvenanceGate.forgottenFrames`). Every user path that tombstones a photo calls [clear] in its own transaction, so a forgotten photo can never read as expired.
 * No Room change (context.db stays v17). Ids and times only, never text. Delete all removes every `rx:` key; FrameSweep removes the keys of the tombstones it prunes.
 */
object ExpiryMarks {
    const val F = "rx:f:"
    const val T = "rx:t:"
    private const val CHUNK = 400

    /** The `rx:t:` key of a capture time (the id part empty = the lower bound of that millisecond). */
    fun tKey(ts: Long, id: String) = T + ts.coerceIn(0L, 9_999_999_999_999L).toString().padStart(13, '0') + ":" + id

    /** Records that [frameId] expired at [atMs]; [tsMs] is its capture time (the radius cleanup of a later Forget needs it; null = unknown). */
    suspend fun mark(dao: CanvasSlice3Dao, frameId: String, atMs: Long, tsMs: Long?) {
        dao.putMeta(F + frameId, JSONObject().put("at", atMs).put("ts", tsMs ?: JSONObject.NULL).toString())
        if (tsMs != null) dao.putMeta(tKey(tsMs, frameId), "")
    }

    /** The user path: [frameIds] are forgotten now, so they are not "expired" any more. Same transaction as the tombstone. */
    suspend fun clear(dao: CanvasSlice3Dao, frameIds: Collection<String>) {
        if (frameIds.isEmpty()) return
        frameIds.distinct().chunked(CHUNK).forEach { c ->
            val rows = dao.metas(c.map { F + it })
            val keys = rows.map { it.key } + rows.mapNotNull { r -> tsOf(r.value)?.let { ts -> tKey(ts, r.key.removePrefix(F)) } }
            if (keys.isNotEmpty()) dao.deleteMetaKeys(keys.distinct())
        }
    }

    /** Those of [frameIds] that carry the marker. */
    suspend fun marked(dao: CanvasSlice3Dao, frameIds: Collection<String>): Set<String> =
        frameIds.distinct().chunked(CHUNK).flatMap { c -> dao.metas(c.map { F + it }).map { it.key.removePrefix(F) } }.toSet()

    /** Capture times kept by the marker. */
    suspend fun markedTs(dao: CanvasSlice3Dao, frameIds: Collection<String>): Map<String, Long> =
        frameIds.distinct().chunked(CHUNK).flatMap { c -> dao.metas(c.map { F + it }).mapNotNull { r -> tsOf(r.value)?.let { r.key.removePrefix(F) to it } } }.toMap()

    private fun tsOf(json: String): Long? = runCatching { JSONObject(json).let { if (it.isNull("ts")) null else it.getLong("ts") } }.getOrNull()

    /**
     * The capture time of each of [frameIds] that still has any trace of one: `frame.ts`, then `frame_meta.capturedAtMs`, then `frame_meta.createdAt`, then the marker's `ts` (a legacy frame has no metadata). Read it BEFORE a
     * Forget deletes the rows or clears the marker, so the radius cleanup of a mark and trace near an expired photo still runs (r2-4). The planner, the service and the executor all call this one function: the preview equals the delete.
     */
    suspend fun captureTs(fd: ForgetDao, s3: CanvasSlice3Dao, frameIds: Collection<String>): List<Long> {
        val ids = frameIds.distinct()
        val direct = ids.associateWith { fd.captureTsOf(it) }
        val missing = direct.filterValues { it == null }.keys
        val fromMarks = if (missing.isEmpty()) emptyMap() else markedTs(s3, missing)
        return ids.mapNotNull { direct[it] ?: fromMarks[it] }
    }

    /**
     * The ids of ALL the photos with a marker captured within `[fromMs, toMs]` (inclusive), read by capture time from the `rx:t:` keys. A scoped Forget of a time span reaches an expired photo (no `frame` row, possibly no
     * metadata) through this, so the read is COMPLETE (Codex v3 r2 P1-5): it pages by key, [pageSize] keys at a time, until a short page. There is no cap that could silently leave a marker, and the answers citing its photo, behind.
     */
    suspend fun idsBetween(dao: CanvasSlice3Dao, fromMs: Long, toMs: Long, pageSize: Int = PAGE): Set<String> {
        val page = pageSize.coerceAtLeast(1)
        val out = LinkedHashSet<String>()
        var from = tKey(fromMs, ""); val to = tKey(toMs + 1, "")
        while (true) {
            val rows = dao.metasBetween(from, to, page)
            rows.forEach { r -> r.key.substring(T.length + 14).takeIf { it.isNotEmpty() }?.let { out += it } } // "rx:t:" + 13 digits + ":" + id
            if (rows.size < page) break
            from = rows.last().key + "\u0000"
        }
        return out
    }
    private const val PAGE = 1_000

    /** True when a photo captured within `[fromMs, toMs)` was expired (not forgotten). One range read. */
    suspend fun anyExpiredBetween(dao: CanvasSlice3Dao, fromMs: Long, toMs: Long): Boolean =
        dao.countMetaBetween(tKey(fromMs, ""), tKey(toMs, "")) > 0
}
