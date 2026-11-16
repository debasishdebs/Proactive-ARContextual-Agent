package com.rokid.context.memory.forget.lifecycle

import androidx.sqlite.db.SimpleSQLiteQuery
import com.rokid.context.memory.db.ContextDatabase

/**
 * One family of the Forget lifecycle (design 2.1). [tables] is the inventory: every table the family owns, in `context.db` or `places.db`; [prefs] the preference files, [holders] the in-memory
 * holders of derived state. [dependents] gives the typed downstream edges of [ids] (read only, the existing queries). `selfDelete()` is added with the executor in M4: until then the families only
 * PLAN, and the code that deletes stays where it is.
 */
interface ForgetFamily {
    val family: Family
    val tables: List<String>
    val prefs: List<String> get() = emptyList()
    val holders: List<String> get() = emptyList()
    /** A family with no store yet: the inventory test fails the day one gets a table, so its dependents and its delete are written first. */
    val reserved: Boolean get() = false
    suspend fun dependents(ids: Set<String>, tx: PlanTx): List<Edge> = emptyList()
}

/** Read-only access for planning. Reads only: a plan never writes, takes no lock and starts no transaction. */
class PlanTx(val db: ContextDatabase) {
    /** First column of every row, as text. */
    suspend fun strings(sql: String, args: List<Any> = emptyList()): List<String> = rows(sql, args) { it.getString(0) }

    suspend fun <T> rows(sql: String, args: List<Any> = emptyList(), read: (android.database.Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        db.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c -> while (c.moveToNext()) out += read(c) }
        return out
    }

    /** [f] over [ids] in chunks of [CHUNK] (SQLite's bind limit), results concatenated. */
    suspend fun <T> chunked(ids: Collection<String>, f: suspend (List<String>) -> List<T>): List<T> = ids.toList().chunked(CHUNK).flatMap { f(it) }

    companion object { const val CHUNK = 500 }
}
