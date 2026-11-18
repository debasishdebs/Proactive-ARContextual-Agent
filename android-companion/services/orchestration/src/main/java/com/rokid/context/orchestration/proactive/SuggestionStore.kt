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
    override fun clearAll() { synchronized(lock) { rows.clear(); nevers.clear(); counts.clear(); fb = FeedbackState.EMPTY }; changed() }
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
class PrefsSuggestionStore(private val prefs: SharedPreferences) : SuggestionStore {
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
        val rows = readRows().apply { removeAll { it.id == r.id }; add(0, r) }
        BoundedRows.trim(rows, r.ts)
        writeRows(rows)
    }.also { changed() }
    override fun records(): List<SuggestionRecord> = synchronized(lock) { readRows() }
    override fun withdrawWhere(predicate: (SuggestionRecord) -> Boolean): Int = synchronized(lock) {
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
    override fun clearAll() { synchronized(lock) { try { prefs.edit().clear().commit() } catch (_: Exception) { } }; changed() }
    override fun markDelivered(id: String, surface: Surface): Boolean = synchronized(lock) {
        val rows = readRows()
        val i = rows.indexOfFirst { it.id == id }
        if (i < 0 || surface.token in rows[i].delivered) false else { rows[i] = rows[i].copy(delivered = rows[i].delivered + surface.token); writeRows(rows) }
    }.also { if (it) changed() }
    override fun feedback(): FeedbackState = synchronized(lock) { FeedbackState.fromJson(try { prefs.getString(FB, null) } catch (e: Exception) { null }) }
    override fun recordFeedback(action: FeedbackAction, key: String, family: ProactiveFamily, nowMs: Long): Boolean = synchronized(lock) {
        val next = FeedbackState.fromJson(try { prefs.getString(FB, null) } catch (e: Exception) { null }).after(action, key, family, nowMs)
        val c = readCounters(); c["fb_" + action.token] = (c["fb_" + action.token] ?: 0) + 1
        try { prefs.edit().putString(FB, next.toJson()).putString(COUNTS, JSONObject(c as Map<*, *>).toString()).commit() } catch (e: Exception) { false }
    }.also { changed() }
    override fun resetFeedback() { synchronized(lock) { try { prefs.edit().remove(FB).commit() } catch (_: Exception) { } }; changed() }

    companion object {
        /** The prefs file (a Delete-all-wiped, backup-excluded file; production opens it encrypted). */
        const val PREFS = "proactive_shadow"
        private const val ROWS = "rows"
        private const val NEVER = "never"
        private const val COUNTS = "counts"
        private const val FB = "fb"

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
