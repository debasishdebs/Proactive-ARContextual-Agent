package com.rokid.context.memory.forget

import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import org.json.JSONArray

/**
 * Durable lineage of EXPIRED memories (retention fix, Codex retention r1 finding 4). System expiry deletes a memory's row, its `frame_source` rows and its `memory_source` edges but, by the no-loss rule, keeps every
 * nudge and answer that cites it. A LATER user Forget of the photo (or of a source) must still find those dependents, so expiry first writes what the deleted rows would have told the Forget, as ids only, into
 * `canvas_meta` (no Room schema change, context.db stays v17):
 *  - `lin:f:<frameId>` : the expired memories that belonged to that photo (memory.frameId or any frame_source role);
 *  - `lin:u:<sourceId>`: the expired memories derived from that source (an edge expired-child to a source, live or expired);
 *  - `lin:d:<expiredId>`: the live memories derived from that expired memory (a summary built on it).
 * [reach] reads them for a user Forget (the cited set grows by the expired ids, the invalidated set by the derived ones), [consume] deletes what a Forget used, Delete all wipes the `lin:` keys. Nothing here holds text.
 * The same lineage is read by the executor and the planner, so the preview equals the delete.
 */
internal object ExpiredLineage {
    private const val F = "lin:f:"
    private const val U = "lin:u:"
    private const val D = "lin:d:"
    private const val CHUNK = 400

    /** [expired]: ids of expired memories a Forget now reaches. [derived]: live memories derived from them. [keys]: the lineage keys read (a Forget deletes them once it ran). */
    class Reach(val expired: Set<String>, val derived: Set<String>, val keys: Set<String>) { companion object { val EMPTY = Reach(emptySet(), emptySet(), emptySet()) } }

    /** Writes the lineage of [found] (about to be deleted by retention) from the rows that are still there. Merges into what earlier runs stored. */
    suspend fun record(dao: CanvasSlice3Dao, found: List<MemoryEntity>, frameRows: List<FrameSourceEntity>, edgesFrom: List<MemorySourceEntity>, edgesTo: List<MemorySourceEntity>) {
        if (found.isEmpty()) return
        val expiring = found.map { it.id }.toSet()
        val add = LinkedHashMap<String, LinkedHashSet<String>>()
        fun put(key: String, id: String) { add.getOrPut(key) { LinkedHashSet() } += id }
        found.forEach { m -> m.frameId?.let { put(F + it, m.id) } }
        frameRows.forEach { put(F + it.frameId, it.memoryId) }
        edgesFrom.forEach { put(U + it.sourceMemoryId, it.memoryId) } // memoryId (expiring) is derived from sourceMemoryId
        edgesTo.filter { it.memoryId !in expiring }.forEach { put(D + it.sourceMemoryId, it.memoryId) } // a live memory built on an expiring one
        if (add.isEmpty()) return
        val existing = add.keys.toList().chunked(CHUNK).flatMap { dao.metas(it) }.associate { it.key to it.value }
        for ((key, ids) in add) {
            val merged = LinkedHashSet<String>().also { s -> existing[key]?.let { s += parse(it) }; s += ids }
            dao.putMeta(key, JSONArray(merged.toList()).toString())
        }
    }

    /** What a user Forget of [frames] (photos it forgets) and [seeds] (live memories it deletes) reaches among expired memories and the live ones derived from them. Read only. */
    suspend fun reach(dao: CanvasSlice3Dao, seeds: Collection<String>, frames: Collection<String>): Reach {
        if (seeds.isEmpty() && frames.isEmpty()) return Reach.EMPTY
        val keys = LinkedHashSet<String>(); val expired = LinkedHashSet<String>(); val derived = LinkedHashSet<String>()
        val seen = HashSet<String>(); var frontier = LinkedHashSet<String>()
        suspend fun read(wanted: List<String>): Map<String, Set<String>> {
            val rows = wanted.chunked(CHUNK).flatMap { dao.metas(it) }
            rows.forEach { keys += it.key }
            return rows.associate { it.key to parse(it.value) }
        }
        read(frames.distinct().map { F + it }).values.forEach { ids -> ids.forEach { if (expired.add(it)) frontier += it } }
        seeds.forEach { if (seen.add(it)) frontier += it }
        frontier.forEach { seen += it }
        var round = 0
        while (frontier.isNotEmpty() && round++ < MAX_ROUNDS) {
            val f = frontier.toList(); frontier = LinkedHashSet()
            read(f.map { U + it }).values.forEach { ids -> ids.forEach { if (expired.add(it) && seen.add(it)) frontier += it } }
            val dKeys = read(f.filter { it in expired }.map { D + it })
            dKeys.values.forEach { derived += it }
        }
        return Reach(expired, derived - expired, keys)
    }

    /** The Forget that used [reach] ran: its lineage is spent. */
    suspend fun consume(dao: CanvasSlice3Dao, reach: Reach) { reach.keys.toList().chunked(CHUNK).forEach { dao.deleteMetaKeys(it) } }

    private fun parse(json: String): Set<String> = runCatching { val a = JSONArray(json); (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet() }.getOrDefault(emptySet())

    private const val MAX_ROUNDS = 8
}
