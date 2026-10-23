package com.rokid.context.canvas.helper

/** What the helper last advertised for one capability (poll field `ready`). [atMs] is the phone-clock time of the advertisement. */
data class ReadinessRecord(val ready: Boolean, val atMs: Long)

/** The readiness the status route shows: [ready] is true only for a fresh `true` advertisement; [stale] is true when none arrived within [HelperProtocol.READY_STALE_MS]. */
data class Readiness(val ready: Boolean, val stale: Boolean, val ageMs: Long?)

/** Where the last advertisement is kept. Memory only (it is a live signal, not user data): a restart forgets it, which reads as stale. */
interface HelperReadinessStore {
    fun record(capability: String, ready: Boolean, atMs: Long)
    fun read(capability: String): ReadinessRecord?
    fun clear()
}

class InMemoryReadinessStore : HelperReadinessStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ReadinessRecord>()
    override fun record(capability: String, ready: Boolean, atMs: Long) { map[capability] = ReadinessRecord(ready, atMs) }
    override fun read(capability: String): ReadinessRecord? = map[capability]
    override fun clear() = map.clear()
}
