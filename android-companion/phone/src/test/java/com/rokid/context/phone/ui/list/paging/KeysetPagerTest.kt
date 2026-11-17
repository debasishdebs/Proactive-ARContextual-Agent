package com.rokid.context.phone.ui.list.paging

import com.rokid.context.memory.Cursor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A source over a mutable list of (time, id) rows; counts its queries. */
class FakeSource(vararg initial: Pair<Long, String>) : PageSource<Cursor> {
    val rows = initial.map { Cursor(it.first, it.second) }.toMutableList()
    var queries = 0
    val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    override suspend fun page(range: LongRange, after: Cursor, n: Int): List<Cursor> {
        queries++
        return rows.filter { it.key in range && Cursor.NEWEST_FIRST.compare(it, after) > 0 }.sortedWith(Cursor.NEWEST_FIRST).take(n)
    }
    override fun cursorOf(t: Cursor) = t
    override val changes get() = signals
    fun ids(from: Int, to: Int) = (from..to).map { Cursor(it * 10L, "r$it") }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KeysetPagerTest {
    private val range = 0L..100_000L
    private fun source(n: Int) = FakeSource().also { s -> (1..n).forEach { s.rows += Cursor(it * 10L, "r$it") } }
    private fun ids(p: KeysetPager<Cursor>) = p.state.value.rows.map { it.id }

    @Test fun loadsPagesNewestFirstAndKnowsTheEnd() = runTest {
        val p = KeysetPager(source(250), range, pageSize = 100, windowPages = 10)
        p.loadFirst(); assertEquals("r250", ids(p).first()); assertEquals(100, p.state.value.rows.size); assertTrue(p.state.value.hasMore)
        p.loadMore(); p.loadMore()
        assertEquals(250, p.state.value.rows.size); assertFalse(p.state.value.hasMore)
        assertEquals("no duplicate", 250, ids(p).toSet().size)
    }

    @Test fun theWindowSlidesAtTenPagesAndShowNewerReadsThePageBack() = runTest {
        val p = KeysetPager(source(1_300), range, pageSize = 100, windowPages = 10)
        p.loadFirst(); repeat(10) { p.loadMore() } // page 11 drops page 1
        val s = p.state.value
        assertEquals(1_000, s.rows.size); assertTrue(s.headDropped); assertEquals("r1200", ids(p).first()); assertEquals("r201", ids(p).last())
        p.showNewer()
        assertEquals("the dropped page is back above, the bottom page let go", "r1300", ids(p).first()); assertEquals(1_000, p.state.value.rows.size)
        assertFalse(p.state.value.headDropped); assertTrue(p.state.value.hasMore); assertEquals("r301", ids(p).last())
        assertEquals(1_000, ids(p).toSet().size)
    }

    @Test fun showNewerAfterAForgetInTheDroppedPageNeverDuplicatesARetainedRow() = runTest {
        val src = source(1_300); val p = KeysetPager(src, range, pageSize = 100, windowPages = 10)
        p.loadFirst(); repeat(10) { p.loadMore() } // 11 pages: page 1 (r1300..r1201) is dropped
        assertTrue(p.state.value.headDropped)
        src.rows.removeAll { it.id == "r1250" } // a row of the dropped page is forgotten: its reload reaches one row into the retained window
        p.showNewer()
        assertEquals("every id once (the list uses ids as lazy keys)", ids(p).size, ids(p).toSet().size)
        assertEquals("r1300", ids(p).first()); assertFalse("r1250" in ids(p))
        p.refresh(); p.showNewer()
        assertEquals(ids(p).size, ids(p).toSet().size)
    }

    @Test fun aRefreshIsOneQueryAndDeletedRowsVanishNewRowsAppearTheBottomStays() = runTest {
        val src = source(250); val p = KeysetPager(src, range, pageSize = 100)
        p.loadFirst(); p.loadMore()
        val bottom = ids(p).last()
        src.rows.removeAll { it.id == "r240" || it.id == "r200" }
        src.rows += Cursor(5_000L, "new1"); src.rows += Cursor(5_000L, "new2")
        val before = src.queries
        p.refresh()
        assertEquals("one query for the whole window", 1, src.queries - before)
        assertTrue("r240" !in ids(p) && "r200" !in ids(p)); assertTrue("new1" in ids(p))
        assertEquals("the bottom did not move", bottom, ids(p).last())
        assertEquals(2, p.state.value.newCount)
        assertEquals(ids(p).toSet().size, ids(p).size)
        p.clearNew(); assertEquals(0, p.state.value.newCount)
    }

    @Test fun aRefreshCountsOnlyRowsAboveTheFirstRow() = runTest {
        val src = source(50); val p = KeysetPager(src, range, pageSize = 100)
        p.loadFirst()
        src.rows += Cursor(255L, "late") // a late row inside the window (a re-describe): appears in place, not "new"
        p.refresh()
        assertEquals(0, p.state.value.newCount); assertTrue("late" in ids(p))
    }

    @Test fun changeBurstsAreDebouncedTo300ms() = runTest {
        val src = source(5); val p = KeysetPager(src, range)
        var fired = 0
        val job = launch { p.refreshSignals.collect { fired++ } }
        runCurrent()
        repeat(5) { src.signals.emit(Unit); advanceTimeBy(50) }
        assertEquals(0, fired)
        advanceTimeBy(KeysetPager.DEBOUNCE_MS + 1)
        assertEquals("one refresh follows a burst (one Forget touches many tables)", 1, fired)
        job.cancel()
    }

    @Test fun forgettingTheAnchorRowScrollsToTheNearestFollowingRow() = runTest {
        val src = source(300); val p = KeysetPager(src, range, pageSize = 100)
        p.loadFirst(); p.loadMore()
        val anchor = p.anchor(firstVisible = Cursor(2_000L, "r200")) // r200 is on screen
        src.rows.removeAll { it.id == "r200" }
        val q = KeysetPager(src, range, pageSize = 100)
        val index = q.restore(anchor)
        assertEquals("the row after the forgotten one", "r199", q.state.value.rows[index].id)
    }

    @Test fun processDeathRestoreReadsFromTheSavedWindowAndScrollsToTheAnchor() = runTest {
        val src = source(1_300)
        val a = KeysetPager(src, range, pageSize = 100); a.loadFirst(); repeat(10) { a.loadMore() }
        val anchor = a.anchor(firstVisible = Cursor(5_000L, "r500")) // saved state: the window top and the first visible row
        val b = KeysetPager(src, range, pageSize = 100)
        val before = src.queries
        val index = b.restore(anchor)
        assertEquals("r500", b.state.value.rows[index].id)
        assertTrue("two queries, not every page again", src.queries - before <= 2)
        assertTrue(b.state.value.headDropped)
        b.showNewer() // the pages above are unknown after a restore: back to the head
        assertEquals("r1300", b.state.value.rows.first().id); assertFalse(b.state.value.headDropped)
    }
}
