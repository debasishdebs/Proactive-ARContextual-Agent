package com.rokid.context.memory

import com.rokid.context.memory.db.MomentDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Read-only access to the marks (`canvas_label` MOMENT rows) for the Activity tab: newest first, each with the Moment that holds it (its type and the no-photo reason), as the tables say NOW.
 * Nothing here writes or deletes: deleting a mark stays where it is (the recorder's undo, Forget).
 */
class MarkRepository(private val dao: MomentDao) {
    /** The marks in [range] (phone-clock ms), newest first, at most [limit]: the small INLINE list of the Activity page only (a list pages with [page], never through this cap). */
    fun marksIn(range: LongRange, limit: Int = INLINE_ROWS): Flow<List<MomentDao.MarkRow>> = dao.marksIn(range.first, range.last).map { it.take(limit) }

    /** One keyset page of the marks in [range], newest first (then `labelId`), strictly after [after]; the first page is `Cursor.head(range)`. */
    suspend fun page(range: LongRange, after: Cursor, n: Int): List<MomentDao.MarkRow> = dao.marksPage(range.first, range.last + 1, after.key, after.id, n)
    /** Ids only, at most [limit] (multi-select): the marks [page] lists in [range]. */
    suspend fun idsIn(range: LongRange, limit: Int): List<String> = dao.markIdsIn(range.first, range.last + 1, limit)

    /** The marks named by [ids] (any day), newest first. Indexed by id (no scan of every mark); an unknown id is missing from the result. */
    suspend fun byIds(ids: Collection<String>): List<MomentDao.MarkRow> =
        ids.distinct().chunked(MAX_IN).flatMap { dao.marksByIds(it) }.sortedWith(compareByDescending<MomentDao.MarkRow> { it.atMs }.thenBy { it.labelId })

    /** The mark [id] observed (null while unknown): re-emits when its label, photo link or the Moment holding it changes. */
    fun observeById(id: String): Flow<MomentDao.MarkRow?> = dao.observeMark(id)

    companion object {
        /** The inline marks of the Activity page. NOT a list cap. */
        const val INLINE_ROWS = 200
        private const val MAX_IN = 900
    }
}
