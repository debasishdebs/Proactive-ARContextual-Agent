package com.rokid.context.memory.forget

import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentPartKind
import org.json.JSONArray

/**
 * LEGACY lineage of memories that builds BEFORE retention v3 expired (Codex v3 r1 P1-3). Those builds deleted a memory's row, its `frame_source` rows and its edges but kept every nudge and answer that cited it, and wrote what the
 * deleted rows would have told a later Forget, as ids only, into `canvas_meta`:
 *  - `lin:f:<frameId>` : the expired memories that belonged to that photo;
 *  - `lin:u:<sourceId>`: the expired memories derived from that source;
 *  - `lin:d:<expiredId>`: the live memories derived from that expired memory (a summary built on it).
 * This build NEVER writes these keys (retention v3 deletes no memory) and never purges them while a dependent survives: [reach] reads them for a user Forget (the cited set grows by the expired ids, the invalidated set by the
 * derived ones), [consume] deletes what a Forget used, [prune] drops the keys whose dependents are all gone, Delete all wipes the `lin:` keys. The same read serves the service, the executor and the planner (preview == delete).
 * Nothing here holds text.
 */
object ExpiredLineage {
    const val F = "lin:f:"
    const val U = "lin:u:"
    const val D = "lin:d:"
    private const val FROM = "lin:"
    private const val TO = "lin;" // ':' + 1: the end of the `lin:` prefix range
    private const val CHUNK = 400

    /** [expired]: ids of expired memories a Forget now reaches. [derived]: live memories derived from them. [keys]: the lineage keys read (a Forget deletes them once it ran). */
    class Reach(val expired: Set<String>, val derived: Set<String>, val keys: Set<String>) { companion object { val EMPTY = Reach(emptySet(), emptySet(), emptySet()) } }

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

    /**
     * Drops the legacy lineage keys nothing depends on any more (Codex v3 r1 P1-3): a key stays while an unredacted answer or nudge cites one of its expired ids, a stored Moment holds one, or (for a `lin:d:` key) a derived memory
     * it names still exists; an `lin:f:` / `lin:u:` key also stays while the `lin:d:` key of one of its expired ids does. Bounded: at most [limit] keys are read per call, one transaction's worth. Returns how many keys were dropped.
     */
    suspend fun prune(db: ContextDatabase, limit: Int = 2_000): Int {
        val s3 = db.canvasSlice3Dao(); val fd = db.forgetDao(); val md = db.momentDao()
        val rows = s3.metasBetween(FROM, TO, limit)
        if (rows.isEmpty()) return 0
        val dKeys = rows.filter { it.key.startsWith(D) }.map { it.key.removePrefix(D) }.toSet()
        val drop = ArrayList<String>()
        suspend fun citedByDependent(expiredId: String): Boolean {
            val like = ForgetService.likeFor(expiredId)
            if (fd.asksCiting(like).any { expiredId in ForgetService.parseIds(it.sourcesJson) }) return true
            if (fd.nudgesCiting(like).any { expiredId in ForgetService.parseIds(it.sourceIdsJson) }) return true
            return md.momentIdsHolding(listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY), listOf(expiredId)).isNotEmpty()
        }
        for (r in rows) {
            val ids = parse(r.value)
            val keep = if (r.key.startsWith(D)) {
                val expiredId = r.key.removePrefix(D)
                citedByDependent(expiredId) || ids.toList().chunked(CHUNK).any { fd.memories(it).isNotEmpty() }
            } else {
                ids.any { it in dKeys || citedByDependent(it) }
            }
            if (!keep) drop += r.key
        }
        drop.chunked(CHUNK).forEach { s3.deleteMetaKeys(it) }
        return drop.size
    }

    private fun parse(json: String): Set<String> = runCatching { val a = JSONArray(json); (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet() }.getOrDefault(emptySet())

    private const val MAX_ROUNDS = 8
}
