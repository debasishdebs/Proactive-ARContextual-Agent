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
    /** The marks in [range] (phone-clock ms), newest first, at most [limit]. */
    fun marksIn(range: LongRange, limit: Int = MAX_ROWS): Flow<List<MomentDao.MarkRow>> = dao.marksIn(range.first, range.last).map { it.take(limit) }

    /** The marks named by [ids] (any day), newest first. Reads the existing marks query once; an unknown id is missing from the result. */
    suspend fun byIds(ids: Collection<String>): List<MomentDao.MarkRow> =
        if (ids.isEmpty()) emptyList() else dao.marksIn(0L, Long.MAX_VALUE).first().filter { it.labelId in ids }

    /** The mark [id] observed (null while unknown): re-emits when its label, photo link or the Moment holding it changes. */
    fun observeById(id: String): Flow<MomentDao.MarkRow?> = dao.marksIn(0L, Long.MAX_VALUE).map { rows -> rows.firstOrNull { it.labelId == id } }

    companion object { const val MAX_ROWS = 200 }
}
