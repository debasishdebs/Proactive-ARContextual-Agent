package com.rokid.context.orchestration.proactive

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where would-show suggestions, the "never suggest this" list and the shadow counters live (ruling 2026-11-18: hold Room v18; a storage interface with a memory implementation and an encrypted,
 * app-private, backup-excluded, bounded prefs implementation). Everything in it is an id or a fixed token; IDs and place keys are sensitive too, so the persisted form is encrypted and Forget and
 * Delete all purge it. All methods are synchronous, thread-safe and never throw: a store that cannot write answers false ("nothing recorded"), which the engine treats as a drop.
 */
interface SuggestionStore {
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
    /** Delete all (ruling 6): records, the never list and the counters. */
    fun clearAll()

    companion object {
        const val MAX_RECORDS = 100
        const val MAX_NEVER = 200
        const val RETENTION_MS = 14 * 24 * 3_600_000L
    }
}

class InMemorySuggestionStore : SuggestionStore {
    private val lock = Any()
    private val rows = ArrayList<SuggestionRecord>()
    private val nevers = LinkedHashSet<String>()
    private val counts = LinkedHashMap<String, Int>()

    override fun add(r: SuggestionRecord): Boolean = synchronized(lock) {
        rows.removeAll { it.id == r.id }
        rows.add(0, r)
        BoundedRows.trim(rows, r.ts)
        true
    }
    override fun records(): List<SuggestionRecord> = synchronized(lock) { rows.toList() }
    override fun withdrawWhere(predicate: (SuggestionRecord) -> Boolean): Int = synchronized(lock) { val n = rows.size; rows.removeAll(predicate); n - rows.size }
    override fun never(): Set<String> = synchronized(lock) { nevers.toSet() }
    override fun addNever(key: String): Boolean = synchronized(lock) { if (nevers.size >= SuggestionStore.MAX_NEVER && key !in nevers) false else { nevers += key; true } }
    override fun removeNever(key: String) { synchronized(lock) { nevers -= key } }
    override fun counters(): Map<String, Int> = synchronized(lock) { counts.toMap() }
    override fun bump(counter: String) { synchronized(lock) { counts[counter] = (counts[counter] ?: 0) + 1 } }
    override fun clearAll() { synchronized(lock) { rows.clear(); nevers.clear(); counts.clear() } }
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
    }
    override fun records(): List<SuggestionRecord> = synchronized(lock) { readRows() }
    override fun withdrawWhere(predicate: (SuggestionRecord) -> Boolean): Int = synchronized(lock) {
        val rows = readRows(); val n = rows.size
        rows.removeAll(predicate)
        if (rows.size == n) 0 else if (writeRows(rows)) n - rows.size else 0
    }
    override fun never(): Set<String> = synchronized(lock) { readNever() }
    override fun addNever(key: String): Boolean = synchronized(lock) {
        val s = readNever()
        if (key in s) return true
        if (s.size >= SuggestionStore.MAX_NEVER) return false
        s += key
        try { prefs.edit().putString(NEVER, JSONArray(s.toList()).toString()).commit() } catch (e: Exception) { false }
    }
    override fun removeNever(key: String) { synchronized(lock) { val s = readNever(); if (s.remove(key)) try { prefs.edit().putString(NEVER, JSONArray(s.toList()).toString()).commit() } catch (_: Exception) { } } }
    override fun counters(): Map<String, Int> = synchronized(lock) { readCounters() }
    override fun bump(counter: String) { synchronized(lock) { val c = readCounters(); c[counter] = (c[counter] ?: 0) + 1; try { prefs.edit().putString(COUNTS, JSONObject(c as Map<*, *>).toString()).commit() } catch (_: Exception) { } } }
    override fun clearAll() { synchronized(lock) { try { prefs.edit().clear().commit() } catch (_: Exception) { } } }

    companion object {
        /** The prefs file (a Delete-all-wiped, backup-excluded file; production opens it encrypted). */
        const val PREFS = "proactive_shadow"
        private const val ROWS = "rows"
        private const val NEVER = "never"
        private const val COUNTS = "counts"

        internal fun encode(r: SuggestionRecord) = JSONObject().put("id", r.id).put("ts", r.ts).put("key", r.key).put("fam", r.family.token).put("trg", r.trigger.token).put("score", r.score)
            .put("anchor", r.anchorKey).put("src", JSONArray(r.sourceIds)).put("why", JSONArray(r.factors))

        internal fun decode(o: JSONObject): SuggestionRecord? {
            val fam = ProactiveFamily.entries.firstOrNull { it.token == o.optString("fam") } ?: return null
            val trg = TriggerKind.of(o.optString("trg")) ?: return null
            val src = o.getJSONArray("src"); val why = o.getJSONArray("why")
            return SuggestionRecord(o.getString("id"), o.getLong("ts"), o.getString("key"), fam, trg, o.getDouble("score"), o.getString("anchor"), (0 until src.length()).map { src.getString(it) }, (0 until why.length()).map { why.getString(it) })
        }
    }
}
