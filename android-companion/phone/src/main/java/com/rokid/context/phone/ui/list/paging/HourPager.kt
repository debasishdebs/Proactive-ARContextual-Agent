package com.rokid.context.phone.ui.list.paging

import com.rokid.context.memory.Cursor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One page of an hour: [page] is 0-based, [pages] = `ceil(count / pageSize)` (0 for an empty hour, which disappears from the tree). */
data class HourPageState<T>(val page: Int = 0, val pages: Int = 0, val rows: List<T> = emptyList(), val loaded: Boolean = false)

/** What survives process death of one open hour: the page and the cursor stack (page i starts after `starts[i]`). */
data class HourSnapshot(val page: Int, val starts: List<Cursor>)

/**
 * The pages of ONE hour of the broken-down view (activity pagination A3). Pages REPLACE each other (a "1 of 4" control). The cursor stack (`starts[i]` = the cursor page i starts after, `starts[0]` is
 * [head]) makes Previous and Next one query each. [fetch] reads at most `n` rows strictly after a cursor.
 */
class HourPager<T>(
    private val head: Cursor,
    private val cursorOf: (T) -> Cursor,
    private val pageSize: Int = PAGE_SIZE,
    private val fetch: suspend (after: Cursor, n: Int) -> List<T>,
) {
    private val lock = Mutex()
    private val starts = arrayListOf(head)
    private var count = 0
    private val _state = MutableStateFlow(HourPageState<T>())
    val state: StateFlow<HourPageState<T>> get() = _state

    fun snapshot() = HourSnapshot(_state.value.page, starts.toList())

    /** Opens the first page (or the saved one) of an hour holding [count] rows (from a saved [snapshot] when given). */
    suspend fun open(count: Int, saved: HourSnapshot? = null) = lock.withLock {
        if (saved != null && saved.starts.isNotEmpty() && saved.starts.first() == head) { starts.clear(); starts += saved.starts }
        this.count = count
        readPage((saved?.page ?: 0).coerceIn(0, starts.size - 1))
    }

    suspend fun next() = lock.withLock { val s = _state.value; if (s.page + 1 < s.pages && s.rows.isNotEmpty()) { setStart(s.page + 1, cursorOf(s.rows.last())); readPage(s.page + 1) } }
    suspend fun previous() = lock.withLock { val s = _state.value; if (s.page > 0) readPage(s.page - 1) }

    /**
     * After a change ([count] is the hour's new live count): re-reads the open page from its own start cursor, so a page that became short fills up again. A page that became empty steps back one page;
     * an hour with no rows left ends with `pages == 0`.
     */
    suspend fun refresh(count: Int) = lock.withLock { this.count = count; readPage(_state.value.page) }

    private fun setStart(i: Int, c: Cursor) { while (starts.size > i) starts.removeAt(starts.lastIndex); starts += c }

    private suspend fun readPage(wanted: Int) {
        val pages = if (count <= 0) 0 else (count + pageSize - 1) / pageSize
        var page = wanted.coerceAtMost((pages - 1).coerceAtLeast(0)).coerceAtMost(starts.size - 1)
        while (true) {
            val rows = fetch(starts[page], pageSize)
            if (rows.isEmpty() && page > 0) { page--; continue }
            _state.value = HourPageState(page, if (rows.isEmpty()) 0 else maxOf(pages, page + 1), rows, loaded = true)
            if (rows.size == pageSize) setStart(page + 1, cursorOf(rows.last())) else while (starts.size > page + 1) starts.removeAt(starts.lastIndex)
            return
        }
    }

    companion object { const val PAGE_SIZE = 25 }
}
