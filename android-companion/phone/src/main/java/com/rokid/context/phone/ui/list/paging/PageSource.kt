package com.rokid.context.phone.ui.list.paging

import com.rokid.context.memory.Cursor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** What a pager reads (activity pagination A3): keyset pages of [T] over a time range, newest first. Pure Kotlin: no Android type. */
interface PageSource<T> {
    /** At most [n] rows of [range], strictly after [after] in list order (`Cursor.head(range)` for the first page). */
    suspend fun page(range: LongRange, after: Cursor, n: Int): List<T>
    fun cursorOf(t: T): Cursor
    /** List order of two cursors: negative when [a] is listed before [b] (marks and the other kinds differ on a tie). */
    fun compare(a: Cursor, b: Cursor): Int = Cursor.NEWEST_FIRST.compare(a, b)
    /** Emits after a write that can change what the pages show (raw: the view debounces it, see [KeysetPager.refreshSignals]). */
    val changes: Flow<Unit> get() = emptyFlow()
}
