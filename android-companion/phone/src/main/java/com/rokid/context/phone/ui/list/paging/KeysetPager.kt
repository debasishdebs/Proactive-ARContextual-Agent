package com.rokid.context.phone.ui.list.paging

import com.rokid.context.memory.Cursor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the list shows. [headDropped]: pages above the first loaded row were let go ("Show newer"). [newCount]: rows that arrived above the first row since [KeysetPager.clearNew]. */
data class PagerState<T>(val rows: List<T> = emptyList(), val headDropped: Boolean = false, val hasMore: Boolean = false, val newCount: Int = 0, val loaded: Boolean = false)

/** Where a list was, small enough for a SavedStateHandle: the cursor the loaded window starts after, and the first row the user could see. Null [windowAfter] (never saved) means the head. */
data class PagerAnchor(val windowAfter: Cursor, val firstVisible: Cursor?)

/**
 * The list view's hand-rolled keyset pager (Paging 3 is not in the offline cache). Pages of [pageSize] rows are held in a sliding window of [windowPages] pages: loading one more drops the
 * oldest page from the top (and [PagerState.headDropped] says so). One change refreshes with ONE read ([refresh]). Rows inserted at the head never shift or repeat a loaded page, unlike OFFSET.
 */
class KeysetPager<T>(
    private val source: PageSource<T>,
    private val range: LongRange,
    val pageSize: Int = PAGE_SIZE,
    private val windowPages: Int = WINDOW_PAGES,
) {
    private val head = Cursor.head(range)
    private val lock = Mutex()
    private val _state = MutableStateFlow(PagerState<T>())
    val state: StateFlow<PagerState<T>> get() = _state

    /** For every loaded page, the cursor it starts after (its first is where the window starts). */
    private val starts = ArrayList<Cursor>()
    /** The `after` cursors of the pages dropped from the top, nearest page last. */
    private val dropped = ArrayList<Cursor>()
    /** Set after a restore: the window's top is not the head and the pages above it are unknown. */
    private var unknownAbove = false

    /** The raw change signal, debounced: one Forget touches many tables, so one refresh follows a burst (design 2.2: 300 ms). */
    @OptIn(FlowPreview::class)
    val refreshSignals: Flow<Unit> get() = source.changes.debounce(DEBOUNCE_MS)

    fun anchor(firstVisible: Cursor?): PagerAnchor = PagerAnchor(starts.firstOrNull() ?: head, firstVisible)

    /** Loads the first page: the head of the range. */
    suspend fun loadFirst() = lock.withLock { reset() }

    private suspend fun reset() {
        val rows = source.page(range, head, pageSize)
        starts.clear(); starts += head; dropped.clear(); unknownAbove = false
        _state.value = PagerState(rows, headDropped = false, hasMore = rows.size == pageSize, newCount = 0, loaded = true)
    }

    /** Reads the next page below the last row; the window slides when it holds more than [windowPages] pages. */
    suspend fun loadMore() = lock.withLock {
        val s = _state.value
        if (!s.hasMore || s.rows.isEmpty()) return@withLock
        val after = source.cursorOf(s.rows.last())
        val page = source.page(range, after, pageSize)
        var rows = distinctById(s.rows + page); var headDropped = s.headDropped
        if (page.isNotEmpty()) starts += after
        if (starts.size > windowPages) {
            dropped += starts.removeAt(0)
            val newTop = starts.first()
            rows = rows.dropWhile { source.compare(source.cursorOf(it), newTop) <= 0 }
            headDropped = true
        }
        _state.value = s.copy(rows = rows, headDropped = headDropped, hasMore = page.size == pageSize, loaded = true)
    }

    /** "Show newer": reads back the page dropped from the top (and lets the bottom page go when the window is full). After a restore the pages above are unknown, so it starts again from the head. */
    suspend fun showNewer() = lock.withLock {
        val s = _state.value
        if (!s.headDropped) return@withLock
        if (unknownAbove || dropped.isEmpty()) { reset(); return@withLock }
        val after = dropped.removeAt(dropped.lastIndex)
        // Bounded by the retained window's first row: after a Forget the reloaded page reaches one row further down (the deleted row's slot), into rows the window already holds.
        val firstKept = s.rows.firstOrNull()?.let(source::cursorOf)
        val above = source.page(range, after, pageSize).let { p -> if (firstKept == null) p else p.takeWhile { source.compare(source.cursorOf(it), firstKept) < 0 } }
        var rows = distinctById(above + s.rows); var hasMore = s.hasMore
        starts.add(0, after)
        if (starts.size > windowPages) {
            val lastStart = starts.removeAt(starts.lastIndex)
            rows = rows.takeWhile { source.compare(source.cursorOf(it), lastStart) <= 0 }; hasMore = true
        }
        _state.value = s.copy(rows = rows, headDropped = dropped.isNotEmpty() || unknownAbove, hasMore = hasMore)
    }

    /**
     * Re-reads the loaded window with ONE query after a change: from the window's top down to the last loaded row (at most `loaded + pageSize` rows). A deleted row vanishes, a new one appears, the bottom
     * does not move. Rows that arrived above the first row add to [PagerState.newCount].
     */
    suspend fun refresh() = lock.withLock {
        val s = _state.value
        if (!s.loaded) return@withLock
        val top = starts.firstOrNull() ?: head
        val oldFirst = s.rows.firstOrNull()?.let(source::cursorOf)
        val read = through(top, s.rows.lastOrNull()?.let(source::cursorOf), cap = s.rows.size + pageSize)
        val newer = if (oldFirst == null || s.headDropped) 0 else read.rows.takeWhile { source.compare(source.cursorOf(it), oldFirst) < 0 }.size
        setStarts(top, read.rows)
        _state.value = s.copy(rows = read.rows, hasMore = read.cutOff, newCount = s.newCount + newer)
    }

    /** Every merge keeps one row per id (the list uses ids as lazy keys: a duplicate would crash it). */
    private fun distinctById(rows: List<T>): List<T> = rows.distinctBy { source.cursorOf(it).id }

    private class Read<T>(val rows: List<T>, val cutOff: Boolean)

    /** Rows from [after] down to [boundary] (a null boundary reads one page) with ONE query of at most [cap] rows. [Read.cutOff]: rows may exist below what is returned. */
    private suspend fun through(after: Cursor, boundary: Cursor?, cap: Int): Read<T> {
        val n = if (boundary == null) pageSize else cap
        val page = source.page(range, after, n)
        val kept = if (boundary == null) page else page.takeWhile { source.compare(source.cursorOf(it), boundary) <= 0 }
        return Read(kept, kept.size < page.size || page.size == n)
    }

    private fun setStarts(top: Cursor, rows: List<T>) {
        starts.clear(); starts += top
        rows.forEachIndexed { i, r -> if (i > 0 && i % pageSize == 0) starts += source.cursorOf(rows[i - 1]) }
    }

    fun clearNew() { _state.value = _state.value.copy(newCount = 0) }

    /**
     * Process-death restore: one read from the saved window top down to the first row the user saw (plus a page), instead of every page again. Returns the index to scroll to: the anchor's row, or the nearest
     * following one when it was forgotten (or the last row).
     */
    suspend fun restore(a: PagerAnchor): Int = lock.withLock {
        val top = a.windowAfter
        val read = through(top, a.firstVisible, cap = windowPages * pageSize)
        // One more page below the anchor, so the user can keep scrolling.
        val tail = source.page(range, read.rows.lastOrNull()?.let(source::cursorOf) ?: top, pageSize)
        val rows = read.rows + tail
        setStarts(top, rows)
        dropped.clear(); unknownAbove = top != head
        _state.value = PagerState(rows, headDropped = unknownAbove, hasMore = tail.size == pageSize, loaded = true)
        a.firstVisible?.let { fv -> rows.indexOfFirst { source.compare(source.cursorOf(it), fv) >= 0 }.let { if (it < 0) (rows.size - 1).coerceAtLeast(0) else it } } ?: 0
    }

    companion object {
        const val PAGE_SIZE = 100
        const val WINDOW_PAGES = 10
        const val DEBOUNCE_MS = 300L
    }
}
