package com.rokid.context.glasses.mark

import android.content.SharedPreferences
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.MarkAck
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.Wire

/** Where the queue lives: one string. [write] returns false on a failed write (never throws). */
interface MarkStore {
    fun read(): String?
    fun write(text: String): Boolean
}

/** The `mark_queue` prefs (one string), excluded from backup and device transfer (GlassesBackupRulesTest). Synchronous `commit()`. */
class PrefsMarkStore(private val prefs: SharedPreferences) : MarkStore {
    override fun read(): String? = try { prefs.getString(KEY, null) } catch (e: Exception) { null }
    override fun write(text: String): Boolean = try { prefs.edit().putString(KEY, text).commit() } catch (e: Exception) { false }

    companion object { const val PREFS = "mark_queue"; private const val KEY = "state" }
}

/**
 * Slice 3a Task 30: the glasses' persisted, epoch-scoped outbox of `pc_mark` messages (design §4.3, S57, S74).
 *
 * - A mark is written to disk before [record] returns, then stays until the phone acks its `(epoch, seq)`; the phone is idempotent on it.
 * - [MarkMessage.epoch] is a random id generated once per install; `seq` is monotonic and never reused (the counter is persisted even when the queue empties).
 * - Occurrence time is never a freshness test: a queued mark is re-sent on every session open whatever its age, up to [MAX_AGE_MS] (24 h), at most [BURST] per call.
 * - The deletion epoch ([onEpoch], from `pc_trace_ctl`): a different `installId`, or a larger `deletionCounter` of the same one, clears the queue (S57); a
 *   smaller counter is stale and ignored. The first epoch ever seen is adopted without clearing (marks taken before pairing are not a deleted past).
 *   Each queued mark carries the deletion counter known when it was taken (-1 = none yet).
 * - Capped at [CAP] entries (oldest dropped). A corrupt store is an empty queue. A failed write never loses the mark for this run ([lastWriteOk]).
 * No transcripts or audio, only times and the source.
 */
class MarkOutbox(private val store: MarkStore, private val wallClock: () -> Long, private val newEpoch: () -> String) {
    private val lock = Any()
    private var epoch = ""
    private var installId = ""
    private var counter = -1L
    private var nextSeq = 1L
    private var entries = ArrayList<MarkMessage>()
    @Volatile var lastWriteOk = true
        private set

    init { load() }

    val size: Int get() = synchronized(lock) { entries.size }
    fun all(): List<MarkMessage> = synchronized(lock) { entries.toList() }

    fun record(atMs: Long, atElapsedMs: Long, bootCount: Int, listen: Boolean, undo: Long? = null): MarkMessage = synchronized(lock) {
        if (epoch.isEmpty()) epoch = newEpoch()
        val m = MarkMessage(epoch = epoch, seq = nextSeq++, atMs = atMs, atElapsedMs = atElapsedMs, bootCount = bootCount, listen = listen, undo = undo, deletionCounter = counter)
        entries.add(m)
        while (entries.size > CAP) entries.removeAt(0)
        persist()
        m
    }

    /** The marks to (re)send now: not older than 24 h by occurrence time, oldest first, at most [BURST]. Older ones are dropped. */
    fun pending(): List<MarkMessage> = synchronized(lock) {
        val floor = wallClock() - MAX_AGE_MS
        if (entries.removeAll { it.atMs < floor }) persist()
        entries.take(BURST)
    }

    fun onAck(a: MarkAck) = synchronized(lock) {
        if (a.epoch != epoch || a.seqs.isEmpty()) return@synchronized
        if (entries.removeAll { it.seq in a.seqs }) persist()
    }

    fun onEpoch(newInstallId: String, newCounter: Long) = synchronized(lock) {
        if (newInstallId.isEmpty()) return@synchronized
        if (newInstallId == installId && newCounter < counter) return@synchronized // a stale message of this pairing
        val changed = newInstallId != installId || newCounter > counter
        if (!changed) return@synchronized
        if (installId.isNotEmpty()) entries.clear()
        installId = newInstallId; counter = newCounter
        persist()
    }

    private fun persist() {
        val sb = StringBuilder()
        sb.append(epoch).append('\n').append(installId).append('\n').append(counter).append('\n').append(nextSeq)
        entries.forEach { sb.append('\n').append(Wire.encode(it)) }
        lastWriteOk = store.write(sb.toString())
    }

    private fun load() {
        try {
            val lines = store.read()?.split('\n') ?: return
            if (lines.size < 4) return
            val ep = lines[0]; val inst = lines[1]; val c = lines[2].toLong(); val n = lines[3].toLong()
            val list = ArrayList<MarkMessage>()
            for (l in lines.drop(4)) if (l.isNotBlank()) (Wire.decode<MarkMessage>(l) as? Decoded.Ok)?.value?.let(list::add)
            epoch = ep; installId = inst; counter = c; nextSeq = maxOf(n, (list.maxOfOrNull { it.seq } ?: 0) + 1); entries = list
        } catch (e: Exception) {
            // unreadable: an empty queue (a new epoch is generated on the next mark)
        }
    }

    companion object { const val CAP = 200; const val BURST = 20; const val MAX_AGE_MS = 24 * 3_600_000L }
}
