package com.rokid.context.memory

import com.rokid.context.memory.db.MomentDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read-only access to the marks (`canvas_label` MOMENT rows) for the Activity tab: newest first, each with the Moment that holds it (its type and the no-photo reason), as the tables say NOW.
 * Nothing here writes or deletes: deleting a mark stays where it is (the recorder's undo, Forget).
 */
class MarkRepository(private val dao: MomentDao) {
    /** The marks in [range] (phone-clock ms), newest first, at most [limit]. */
    fun marksIn(range: LongRange, limit: Int = MAX_ROWS): Flow<List<MomentDao.MarkRow>> = dao.marksIn(range.first, range.last).map { it.take(limit) }

    companion object { const val MAX_ROWS = 200 }
}
