package com.rokid.context.memory.forget

import com.rokid.context.memory.db.ContextDatabase

/**
 * Forget lifecycle M0: a row-level snapshot of EVERY table of the database, and the difference between two of them. Rows are keyed by their primary key (or the first column), so a delete is a key that is gone,
 * a redaction or invalidation is a key whose row changed, and a tombstone or a fence is a key that was added. The golden delete-set tests pin [render] of this delta for every Forget path, and the planner shadow
 * test compares the planner's set to it. Keys only, never values: nothing here reads user text into a message beyond the ids the test seeded.
 */
class DbSnapshot private constructor(private val rows: Map<String, Map<String, String>>) {
    class Delta(val deleted: Map<String, Set<String>>, val changed: Map<String, Set<String>>, val added: Map<String, Set<String>>) {
        /** One line per table and effect, sorted: `deleted memory: mA, mD`. Stable text, so a failing golden test shows the exact difference. */
        fun render(): String = listOf("deleted" to deleted, "changed" to changed, "added" to added).flatMap { (label, m) ->
            m.filterValues { it.isNotEmpty() }.toSortedMap().map { (t, keys) -> "$label $t: ${keys.sorted().joinToString(", ")}" }
        }.joinToString("\n")
        fun deletedKeys(table: String): Set<String> = deleted[table].orEmpty()
        fun changedKeys(table: String): Set<String> = changed[table].orEmpty()
        fun addedKeys(table: String): Set<String> = added[table].orEmpty()
    }

    fun deltaTo(after: DbSnapshot): Delta {
        val del = HashMap<String, Set<String>>(); val chg = HashMap<String, Set<String>>(); val add = HashMap<String, Set<String>>()
        for (t in rows.keys + after.rows.keys) {
            val a = rows[t].orEmpty(); val b = after.rows[t].orEmpty()
            del[t] = a.keys - b.keys
            add[t] = b.keys - a.keys
            chg[t] = a.keys.intersect(b.keys).filter { a[it] != b[it] }.toSet()
        }
        return Delta(del, chg, add)
    }

    companion object {
        fun of(db: ContextDatabase): DbSnapshot {
            val sdb = db.openHelper.readableDatabase
            val tables = ArrayList<String>()
            sdb.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'memory_fts%'").use { c -> while (c.moveToNext()) tables += c.getString(0) }
            val out = HashMap<String, Map<String, String>>()
            for (t in tables) {
                val pk = ArrayList<Pair<Int, String>>(); val cols = ArrayList<String>()
                sdb.query("PRAGMA table_info(`$t`)").use { c -> while (c.moveToNext()) { cols += c.getString(1); if (c.getInt(5) > 0) pk += c.getInt(5) to c.getString(1) } }
                val keyCols = pk.sortedBy { it.first }.map { it.second }.ifEmpty { listOf(cols.first()) }
                val rows = HashMap<String, String>()
                sdb.query("SELECT * FROM `$t`").use { c ->
                    val idx = keyCols.map { c.getColumnIndex(it) }
                    while (c.moveToNext()) {
                        val key = idx.joinToString("|") { c.getString(it) ?: "null" }
                        rows[key] = (0 until c.columnCount).joinToString("\u0001") { i -> if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) "blob${c.getBlob(i).size}" else (c.getString(i) ?: "null") }
                    }
                }
                out[t] = rows
            }
            return DbSnapshot(out)
        }
    }
}
