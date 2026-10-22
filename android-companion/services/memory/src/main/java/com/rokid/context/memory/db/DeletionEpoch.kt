package com.rokid.context.memory.db

/**
 * The persisted, pairing-scoped deletion epoch (S57): [installId] is a random UUID created on first use, [counter] goes up by one on every Delete all
 * (committed BEFORE anything is deleted), and [wipedAtMs] is the wall time of the latest wipe. A mark whose occurrence time is before [wipedAtMs] is dropped.
 * `MemoryWipe.generation` stays for in-flight jobs of one process; this survives a process death.
 */
data class DeletionEpoch(val installId: String, val counter: Long, val wipedAtMs: Long) {
    /** The same install and counter: nothing has been deleted (and no re-install happened) since [other] was read. */
    fun sameEpoch(other: DeletionEpoch): Boolean = installId == other.installId && counter == other.counter
}

/**
 * What a derived commit captured BEFORE it started, re-checked INSIDE its transaction (S57, S58): the in-process wipe generation and the
 * persisted deletion epoch. [currentGeneration] and [currentEpoch] are read at check time (the app passes `MemoryWipe.generation::get` and the
 * epoch store's `read`).
 */
class DaoFence(
    val generation: Long,
    val epoch: DeletionEpoch,
    private val currentGeneration: () -> Long,
    private val currentEpoch: () -> DeletionEpoch,
) {
    fun holds(): Boolean = currentGeneration() == generation && currentEpoch().sameEpoch(epoch)

    /** The epoch as it is NOW (for the `wipedAtMs` occurrence-time fence of a mark). */
    fun now(): DeletionEpoch = currentEpoch()
}

/** One source of a replay or sweep: a trace id with the revision read, and the interval used. Serialised by hand so the text is deterministic. */
data class TraceSource(val traceId: String, val revision: Int, val fromMs: Long, val toMs: Long)

object TraceSources {
    fun encode(sources: List<TraceSource>): String = sources.joinToString(",", "[", "]") {
        "{\"traceId\":${org.json.JSONObject.quote(it.traceId)},\"revision\":${it.revision},\"fromMs\":${it.fromMs},\"toMs\":${it.toMs}}"
    }

    fun decode(json: String): List<TraceSource> = runCatching {
        val a = org.json.JSONArray(json)
        (0 until a.length()).map { i -> a.getJSONObject(i).let { TraceSource(it.getString("traceId"), it.getInt("revision"), it.getLong("fromMs"), it.getLong("toMs")) } }
    }.getOrDefault(emptyList())

    /** The substring [encode] writes for [traceId]: what `invalidateBySources` searches for. */
    fun needle(traceId: String): String = "\"traceId\":${org.json.JSONObject.quote(traceId)},"
}
