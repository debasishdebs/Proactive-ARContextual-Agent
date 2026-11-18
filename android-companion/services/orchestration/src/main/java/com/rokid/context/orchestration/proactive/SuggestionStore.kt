package com.rokid.context.orchestration.proactive

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where would-show suggestions, the "never suggest this" list and the shadow counters live (ruling 2026-11-18: hold Room v18; a storage interface with a memory implementation and an encrypted,
 * app-private, backup-excluded, bounded prefs implementation). Everything in it is an id or a fixed token; IDs and place keys are sensitive too, so the persisted form is encrypted and Forget and
 * Delete all purge it. All methods are synchronous, thread-safe and never throw: a store that cannot write answers false ("nothing recorded"), which the engine treats as a drop.
 */
interface SuggestionStore {
    /** A monotonic version that moves on every change to the records or the never list (add, withdraw, never, Delete all): what a screen observes to drop rendered text the moment its record goes. */
    val changes: StateFlow<Long>
    /** Records a would-show. False = not stored (full write failure). */
    fun add(r: SuggestionRecord): Boolean
    /** Newest first. */
    fun records(): List<SuggestionRecord>
    /** Removes the records [predicate] selects; returns how many. */
    fun withdrawWhere(predicate: (SuggestionRecord) -> Boolean): Int
    fun withdraw(id: String): Boolean = withdrawWhere { it.id == id } > 0
    fun never(): Set<String>
    fun addNever(key: String): Boolean
    fun removeNever(key: String)
    fun counters(): Map<String, Int>
    fun bump(counter: String)
    /** Delete all (ruling 6): records, the never list, the feedback counters and the counters. */
    fun clearAll()

    /** v3.1: atomically marks the record delivered to [surface]. True only for the call that set it (at most once per record and surface); false = already delivered, or the record is gone. */
    fun markDelivered(id: String, surface: Surface): Boolean
    /** v3.1: the feedback counters (plain, bounded). */
    fun feedback(): FeedbackState
    /** Applies [action] to the counters; also bumps the plain counter `fb_<action>`. False = not stored. */
    fun recordFeedback(action: FeedbackAction, key: String, family: ProactiveFamily, nowMs: Long): Boolean
    /** "Reset learning": clears the feedback counters (the never list has its own button). */
    fun resetFeedback()
    /** v3.1 r1: the delivery ledger (see [DeliveryLedger]): delivered and dismissed lines, independent of the records, oldest first, pruned after 48 h. Not touched by Forget or Reset learning; Delete all clears it. */
    fun ledger(): List<LedgerEntry> = emptyList()
    /** Appends one line (then prunes). False = not stored. */
    fun appendLedger(e: LedgerEntry): Boolean = false
    /** v3.1 r2: turns the RESERVED line [e] into DELIVERED. False = not stored (the line stays RESERVED, which counts the same: conservative). */
    fun confirmLedger(e: LedgerEntry): Boolean = false
    /** v3.1 r2: removes the RESERVED line [e] (nothing was shown). False = not stored (the line stays and counts: conservative). */
    fun releaseLedger(e: LedgerEntry): Boolean = false

    companion object {
        const val MAX_RECORDS = 100
        const val MAX_NEVER = 200
        const val RETENTION_MS = 14 * 24 * 3_600_000L
    }
}

class InMemorySuggestionStore : SuggestionStore {
    private val version = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = version.asStateFlow()
    private fun changed() { version.value = version.value + 1 }
    private val lock = Any()
    private val rows = ArrayList<SuggestionRecord>()
    private val nevers = LinkedHashSet<String>()
    private val counts = LinkedHashMap<String, Int>()

    override fun add(r: SuggestionRecord): Boolean = synchronized(lock) {
        rows.removeAll { it.id == r.id }
        rows.add(0, r)
        BoundedRows.trim(rows, r.ts)
        true
    }.also { changed() }
    override fun records(): List<SuggestionRecord> = synchronized(lock) { rows.toList() }
    override fun withdrawWhere(predicate: (SuggestionRecord) -> Boolean): Int = synchronized(lock) { val n = rows.size; rows.removeAll(predicate); n - rows.size }.also { if (it > 0) changed() }
    override fun never(): Set<String> = synchronized(lock) { nevers.toSet() }
    override fun addNever(key: String): Boolean = synchronized(lock) { if (nevers.size >= SuggestionStore.MAX_NEVER && key !in nevers) false else { nevers += key; true } }.also { changed() }
    override fun removeNever(key: String) { synchronized(lock) { nevers -= key }; changed() }
    override fun counters(): Map<String, Int> = synchronized(lock) { counts.toMap() }
    override fun bump(counter: String) { synchronized(lock) { counts[counter] = (counts[counter] ?: 0) + 1 } }
    private var fb = FeedbackState.EMPTY
    private var ledgerRows = emptyList<LedgerEntry>()
    override fun ledger(): List<LedgerEntry> = synchronized(lock) { ledgerRows }
    override fun appendLedger(e: LedgerEntry): Boolean { synchronized(lock) { ledgerRows = DeliveryLedger.prune(ledgerRows + e, e.ts) }; return true }
    override fun confirmLedger(e: LedgerEntry): Boolean = synchronized(lock) { DeliveryLedger.confirmed(ledgerRows, e)?.let { ledgerRows = it; true } ?: false }
    override fun releaseLedger(e: LedgerEntry): Boolean = synchronized(lock) { DeliveryLedger.released(ledgerRows, e)?.let { ledgerRows = it; true } ?: false }
    override fun clearAll() { synchronized(lock) { rows.clear(); nevers.clear(); counts.clear(); fb = FeedbackState.EMPTY; ledgerRows = emptyList() }; changed() }
    override fun markDelivered(id: String, surface: Surface): Boolean = synchronized(lock) {
        val i = rows.indexOfFirst { it.id == id }
        if (i < 0 || surface.token in rows[i].delivered) false else { rows[i] = rows[i].copy(delivered = rows[i].delivered + surface.token); true }
    }.also { if (it) changed() }
    override fun feedback(): FeedbackState = synchronized(lock) { fb }
    override fun recordFeedback(action: FeedbackAction, key: String, family: ProactiveFamily, nowMs: Long): Boolean { synchronized(lock) { fb = fb.after(action, key, family, nowMs); counts["fb_" + action.token] = (counts["fb_" + action.token] ?: 0) + 1 }; changed(); return true }
    override fun resetFeedback() { synchronized(lock) { fb = FeedbackState.EMPTY }; changed() }
    /** Tests and the replay: the feedback of another store. */
    fun seedFeedback(s: FeedbackState) { synchronized(lock) { fb = s } }
}

internal object BoundedRows {
    /** Newest first; drops what is older than the retention or beyond the bound. */
    fun trim(rows: MutableList<SuggestionRecord>, nowMs: Long) {
        val ref = maxOf(nowMs, rows.maxOfOrNull { it.ts } ?: nowMs)
        rows.removeAll { ref - it.ts > SuggestionStore.RETENTION_MS }
        while (rows.size > SuggestionStore.MAX_RECORDS) rows.removeAt(rows.size - 1)
    }
}

/**
 * The persisted store: ONE JSON document per kind in a SharedPreferences file. Production hands it an EncryptedSharedPreferences (see [ProactiveGraph]); the file is excluded from backup and transfer and
 * wiped by Delete all. Writes are committed synchronously; a failed commit rolls the in-memory copy back, so what [records] says is what is on disk.
 */
class PrefsSuggestionStore(
    private val prefs: SharedPreferences,
    /**
     * v3.1 r3 P1-3: the tiny SECOND file ([MARKER_PREFS], plain, a timestamp only, written with commit()) that makes a dismissal durable even when the ledger write fails: the marker is written BEFORE the ledger write (write-ahead) and
     * removed only once the line is on disk. A new process that finds a marker cannot know which dismissals were lost, so it pauses delivery for [DeliveryLedger.DISMISS_PAUSE_MS] from the marked time. Null = none (tests, the unavailable store).
     */
    private val marker: SharedPreferences? = null,
) : SuggestionStore {
    private val version = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = version.asStateFlow()
    private fun changed() { version.value = version.value + 1 }
    private val lock = Any()

    private fun readRows(): MutableList<SuggestionRecord> = try {
        val a = JSONArray(prefs.getString(ROWS, null) ?: "[]")
        (0 until a.length()).mapNotNull { i -> decode(a.getJSONObject(i)) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    private fun writeRows(rows: List<SuggestionRecord>): Boolean = try { prefs.edit().putString(ROWS, JSONArray().also { a -> rows.forEach { a.put(encode(it)) } }.toString()).commit() } catch (e: Exception) { false }
    private fun readNever(): LinkedHashSet<String> = try { JSONArray(prefs.getString(NEVER, null) ?: "[]").let { a -> (0 until a.length()).map { a.getString(it) }.toCollection(LinkedHashSet()) } } catch (e: Exception) { LinkedHashSet() }
    private fun readCounters(): LinkedHashMap<String, Int> = try {
        JSONObject(prefs.getString(COUNTS, null) ?: "{}").let { o -> o.keys().asSequence().associateWithTo(LinkedHashMap()) { o.getInt(it) } }
    } catch (e: Exception) { LinkedHashMap() }

    override fun add(r: SuggestionRecord): Boolean = synchronized(lock) {
        flushUnsaved()
        val rows = readRows().apply { removeAll { it.id == r.id }; add(0, r) }
        BoundedRows.trim(rows, r.ts)
        writeRows(rows)
    }.also { changed() }
    override fun records(): List<SuggestionRecord> = synchronized(lock) { flushUnsaved(); readRows() }
    override fun withdrawWhere(predicate: (SuggestionRecord) -> Boolean): Int = synchronized(lock) {
        flushUnsaved()
        val rows = readRows(); val n = rows.size
        rows.removeAll(predicate)
        if (rows.size == n) 0 else if (writeRows(rows)) n - rows.size else 0
    }.also { if (it > 0) changed() }
    override fun never(): Set<String> = synchronized(lock) { readNever() }
    override fun addNever(key: String): Boolean = synchronized(lock) {
        val s = readNever()
        if (key in s) return true
        if (s.size >= SuggestionStore.MAX_NEVER) return false
        s += key
        try { prefs.edit().putString(NEVER, JSONArray(s.toList()).toString()).commit() } catch (e: Exception) { false }
    }.also { changed() }
    override fun removeNever(key: String) { synchronized(lock) { val s = readNever(); if (s.remove(key)) try { prefs.edit().putString(NEVER, JSONArray(s.toList()).toString()).commit() } catch (_: Exception) { } }; changed() }
    override fun counters(): Map<String, Int> = synchronized(lock) { readCounters() }
    override fun bump(counter: String) { synchronized(lock) { val c = readCounters(); c[counter] = (c[counter] ?: 0) + 1; try { prefs.edit().putString(COUNTS, JSONObject(c as Map<*, *>).toString()).commit() } catch (_: Exception) { } } }
    override fun clearAll() { synchronized(lock) { unsaved.clear(); fbMemory = null; inheritedTs = 0L; try { marker?.edit()?.clear()?.commit() } catch (_: Exception) { }; try { prefs.edit().clear().commit() } catch (_: Exception) { } }; changed() }
    override fun markDelivered(id: String, surface: Surface): Boolean = synchronized(lock) {
        flushUnsaved()
        val rows = readRows()
        val i = rows.indexOfFirst { it.id == id }
        if (i < 0 || surface.token in rows[i].delivered) false else { rows[i] = rows[i].copy(delivered = rows[i].delivered + surface.token); writeRows(rows) }
    }.also { if (it) changed() }
    /** v3.1 r5 P1-3: feedback whose write failed after the retries (a dismissal's 24 h rest, a never): held in memory so the key is NEVER shown again by this process; replaced by the next successful write. */
    private var fbMemory: FeedbackState? = null
    override fun feedback(): FeedbackState = synchronized(lock) { fbMemory ?: FeedbackState.fromJson(try { prefs.getString(FB, null) } catch (e: Exception) { null }) }
    override fun recordFeedback(action: FeedbackAction, key: String, family: ProactiveFamily, nowMs: Long): Boolean = synchronized(lock) {
        flushUnsaved()
        val next = (fbMemory ?: FeedbackState.fromJson(try { prefs.getString(FB, null) } catch (e: Exception) { null })).after(action, key, family, nowMs)
        val c = readCounters(); c["fb_" + action.token] = (c["fb_" + action.token] ?: 0) + 1
        val ok = retry { try { prefs.edit().putString(FB, next.toJson()).putString(COUNTS, JSONObject(c as Map<*, *>).toString()).commit() } catch (e: Exception) { false } }
        fbMemory = if (ok) null else next
        ok
    }.also { changed() }
    override fun resetFeedback() { synchronized(lock) { fbMemory = null; try { prefs.edit().remove(FB).commit() } catch (_: Exception) { } }; changed() }
    /** Dismissal lines whose write failed: kept in memory so the five-dismissals pause still holds for this process (a lost dismissal must not reopen delivery). RESERVED lines never go here: a failed reservation is no delivery. */
    private val unsaved = ArrayList<LedgerEntry>()
    private fun readLedger(): List<LedgerEntry> = DeliveryLedger.decode(try { prefs.getString(LEDGER, null) } catch (x: Exception) { null })
    private fun writeLedger(l: List<LedgerEntry>): Boolean = try { prefs.edit().putString(LEDGER, DeliveryLedger.encode(l)).commit() } catch (x: Exception) { false }
    /** v3.1 r5 P1-3: a dismissal's writes are retried before they are given up on (a transient commit() failure is common). */
    private fun retry(times: Int = WRITE_ATTEMPTS, op: () -> Boolean): Boolean { repeat(times) { if (op()) return true }; return false }
    /** v3.1 r3 P1-3: the time of a dismissal a PREVIOUS process may have lost (its marker was still there at start-up); 0 = none. */
    private var inheritedTs: Long = maxOf(
        marker?.let { m -> try { maxOf(m.getLong(MARK_TS, 0L), m.getLong(MARK_OWN, 0L)) } catch (_: Exception) { 0L } } ?: 0L,
        try { prefs.getLong(MARK_FALLBACK, 0L) } catch (_: Exception) { 0L },
    )

    init {
        // the previous process's own pending write becomes the inherited one (still one tiny file, commit()); this process then writes MARK_OWN alone
        if (inheritedTs > 0L) try { marker?.edit()?.putLong(MARK_TS, inheritedTs)?.remove(MARK_OWN)?.commit() } catch (_: Exception) { }
    }

    /** Write-ahead: a dismissal at [ts] is about to be written; best effort (a failing marker store still leaves the in-memory pause). */
    private fun markPending(ts: Long): Boolean {
        val second = marker != null && retry { try { marker.edit().putLong(MARK_OWN, maxOf(ts, marker.getLong(MARK_OWN, 0L))).commit() } catch (_: Exception) { false } }
        // r5 P1-3: no marker file, or its commit failed after the retries: the marker also goes into the ledger file itself (a different key; the same file is likely failing too, so this only helps a partial failure)
        val inMain = !second && retry { try { prefs.edit().putLong(MARK_FALLBACK, maxOf(ts, prefs.getLong(MARK_FALLBACK, 0L))).commit() } catch (_: Exception) { false } }
        return second || inMain
    }
    private fun clearPending() { try { marker?.edit()?.remove(MARK_OWN)?.commit() } catch (_: Exception) { }; try { if (prefs.contains(MARK_FALLBACK)) prefs.edit().remove(MARK_FALLBACK).commit() } catch (_: Exception) { } }

    /** The conservative pause of a lost dismissal: five dismissal lines at the marked time (only in memory, never written), so [DeliveryLedger.pausedUntil] pauses for 24 h from it. */
    private fun inheritedLines(): List<LedgerEntry> = if (inheritedTs <= 0L) emptyList() else List(DeliveryLedger.DISMISS_LIMIT) { LedgerEntry(inheritedTs, ProactiveFamily.entries.first(), LedgerKind.DISMISSED) }

    /** Retries the dismissals whose write failed (called by every store operation). A success also takes the marker down. */
    private fun flushUnsaved() {
        if (unsaved.isEmpty()) return
        val ref = unsaved.maxOf { it.ts }
        if (writeLedger(DeliveryLedger.prune(readLedger() + unsaved, ref))) { unsaved.clear(); clearPending() }
    }

    override fun ledger(): List<LedgerEntry> = synchronized(lock) { flushUnsaved(); (readLedger() + unsaved + inheritedLines()).sortedBy { it.ts } }
    override fun appendLedger(e: LedgerEntry): Boolean = synchronized(lock) {
        // write-ahead: durable BEFORE the card is acknowledged as dismissed. If neither the marker nor the ledger can be written (below) the dismissal is still held in memory: suppression stays for this process
        if (e.kind == LedgerKind.DISMISSED) markPending(e.ts)
        val ok = if (e.kind == LedgerKind.DISMISSED) retry { writeLedger(DeliveryLedger.prune(readLedger() + unsaved + e, e.ts)) } else writeLedger(DeliveryLedger.prune(readLedger() + unsaved + e, e.ts))
        if (ok) { unsaved.clear(); clearPending(); if (inheritedTs > 0L && e.ts - inheritedTs >= DeliveryLedger.DISMISS_PAUSE_MS) { inheritedTs = 0L; try { marker?.edit()?.remove(MARK_TS)?.commit() } catch (_: Exception) { } } }
        else if (e.kind == LedgerKind.DISMISSED) { if (unsaved.size >= DeliveryLedger.MAX_ENTRIES) unsaved.removeAt(0); unsaved += e } // never drop the newest: keep suppressing
        ok
    }
    override fun confirmLedger(e: LedgerEntry): Boolean = synchronized(lock) { flushUnsaved(); DeliveryLedger.confirmed(readLedger(), e)?.let { writeLedger(it) } ?: false }
    override fun releaseLedger(e: LedgerEntry): Boolean = synchronized(lock) { flushUnsaved(); DeliveryLedger.released(readLedger(), e)?.let { writeLedger(it) } ?: false }

    companion object {
        /** The prefs file (a Delete-all-wiped, backup-excluded file; production opens it encrypted). */
        const val PREFS = "proactive_shadow"
        private const val ROWS = "rows"
        private const val NEVER = "never"
        private const val COUNTS = "counts"
        private const val FB = "fb"
        /** The delivery ledger: `[[ts, family, kind], ...]`, no content (same encrypted, backup-excluded file). */
        const val LEDGER = "ldg"
        /** v3.1 r3: the second, tiny, plain prefs file of the failed-dismissal marker (a timestamp only). Excluded from backup and transfer, wiped by Delete all. */
        const val MARKER_PREFS = "proactive_dismiss_marker"
        private const val MARK_OWN = "pending"
        private const val MARK_TS = "lost"
        private const val MARK_FALLBACK = "pending_mark"
        private const val WRITE_ATTEMPTS = 3

        internal fun encode(r: SuggestionRecord) = JSONObject().put("id", r.id).put("ts", r.ts).put("key", r.key).put("fam", r.family.token).put("trg", r.trigger.token).put("score", r.score)
            .put("anchor", r.anchorKey).put("src", JSONArray(r.sourceIds)).put("why", JSONArray(r.factors)).put("dlv", JSONArray(r.delivered.toList()))

        internal fun decode(o: JSONObject): SuggestionRecord? {
            val fam = ProactiveFamily.entries.firstOrNull { it.token == o.optString("fam") } ?: return null
            val trg = TriggerKind.of(o.optString("trg")) ?: return null
            val src = o.getJSONArray("src"); val why = o.getJSONArray("why")
            return SuggestionRecord(o.getString("id"), o.getLong("ts"), o.getString("key"), fam, trg, o.getDouble("score"), o.getString("anchor"), (0 until src.length()).map { src.getString(it) }, (0 until why.length()).map { why.getString(it) },
                o.optJSONArray("dlv")?.let { d -> (0 until d.length()).map { d.getString(it) }.filter { t -> Surface.of(t) != null }.toSet() } ?: emptySet())
        }
    }
}

/**
 * What the process store is when the encrypted file cannot be opened (a Keystore failure): it holds NOTHING and refuses every write. It is not an empty history: the persisted Never list, budget and cooldown
 * are unknown, so the loop is switched off ([ProactiveSettings.markStorageUnavailable]) instead of running without them.
 */
object UnavailableSuggestionStore : SuggestionStore {
    private val none = MutableStateFlow(0L).asStateFlow()
    override val changes: StateFlow<Long> = none
    override fun add(r: SuggestionRecord) = false
    override fun records(): List<SuggestionRecord> = emptyList()
    override fun withdrawWhere(predicate: (SuggestionRecord) -> Boolean) = 0
    override fun never(): Set<String> = emptySet()
    override fun addNever(key: String) = false
    override fun removeNever(key: String) {}
    override fun counters(): Map<String, Int> = emptyMap()
    override fun bump(counter: String) {}
    override fun clearAll() {}
    override fun markDelivered(id: String, surface: Surface) = false
    override fun feedback() = FeedbackState.EMPTY
    override fun recordFeedback(action: FeedbackAction, key: String, family: ProactiveFamily, nowMs: Long) = false
    override fun resetFeedback() {}
}
