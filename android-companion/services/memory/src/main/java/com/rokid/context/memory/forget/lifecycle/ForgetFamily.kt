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
    /** True when this family deletes or changes user-derived rows through [selfDelete] (M4). The families that never are a deletion target (config, control, place pack and card, reserved) say false. */
    val selfDeletes: Boolean get() = false
    /**
     * The ONLY code that deletes, redacts or invalidates the rows of this family, called by the [ForgetExecutor] inside its one transaction. [fate]: ERASE removes the rows, REBUILD erases the content now (the row stays
     * as INVALIDATED / redacted), DROP removes a regenerable result. A family that does not support a fate throws: the executor never asks for it.
     */
    suspend fun selfDelete(ids: Set<String>, fate: Fate, ctx: DeleteCtx) { throw UnsupportedOperationException("${family.token} has no selfDelete") }
}

/** What a selfDelete may use: the database, the proposal repository, the Forget's clock reading and the post-commit notification queue of the [com.rokid.context.memory.forget.ForgetService]. */
class DeleteCtx(val db: ContextDatabase, val proposals: com.rokid.context.memory.ProposalRepository, val now: Long, private val pending: (List<String>, List<Triple<Long, Long, List<String>>>) -> Unit) {
    /** Registers the photos and time spans a listener is told about after the commit. */
    fun notifyAfterCommit(frames: List<String>, spans: List<Triple<Long, Long, List<String>>>) = pending(frames, spans)
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
