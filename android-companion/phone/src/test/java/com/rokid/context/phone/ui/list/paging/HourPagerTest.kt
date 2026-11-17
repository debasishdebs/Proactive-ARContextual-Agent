package com.rokid.context.phone.ui.list.paging

import com.rokid.context.memory.Cursor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HourPagerTest {
    private val rows = (1..60).map { Cursor(it * 10L, "r$it") }.toMutableList()
    private val head = Cursor(10_000, "")
    private val queries = mutableListOf<Cursor>()
    private fun pager() = HourPager<Cursor>(head, { it }, pageSize = 25) { after, n -> queries += after; rows.filter { Cursor.NEWEST_FIRST.compare(it, after) > 0 }.sortedWith(Cursor.NEWEST_FIRST).take(n) }
    private fun HourPager<Cursor>.ids() = state.value.rows.map { it.id }

    @Test fun nextAndPreviousAreOneQueryEachAndPagesReplaceEachOther() = runTest {
        val p = pager(); p.open(60)
        assertEquals(3, p.state.value.pages); assertEquals("r60", p.ids().first()); assertEquals(25, p.ids().size)
        queries.clear(); p.next()
        assertEquals(1, queries.size); assertEquals(1, p.state.value.page); assertEquals("r35", p.ids().first())
        p.next(); assertEquals(10, p.ids().size); assertEquals("r1", p.ids().last())
        queries.clear(); p.previous()
        assertEquals(1, queries.size); assertEquals("r35", p.ids().first())
        p.previous(); p.previous(); assertEquals(0, p.state.value.page)
    }

    @Test fun aShortPageAfterADeleteFillsUpFromItsStartCursor() = runTest {
        val p = pager(); p.open(60); p.next()
        rows.removeAll { it.id in setOf("r40", "r38", "r36") }
        p.refresh(57)
        assertEquals(25, p.ids().size); assertEquals(1, p.state.value.page); assertEquals(3, p.state.value.pages)
        assertEquals(false, "r40" in p.ids())
    }

    @Test fun anEmptyPageStepsBackAndAnEmptyHourEnds() = runTest {
        val p = pager(); p.open(60); p.next(); p.next()
        rows.removeAll { it.key <= 100 } // the whole last page (10 rows) is forgotten
        p.refresh(50)
        assertEquals(1, p.state.value.page); assertEquals(2, p.state.value.pages)
        rows.clear(); p.refresh(0)
        assertEquals(0, p.state.value.pages); assertEquals(emptyList<String>(), p.ids())
    }

    @Test fun aSnapshotRestoresThePageAndItsCursorStack() = runTest {
        val a = pager(); a.open(60); a.next()
        val snap = a.snapshot()
        val b = pager(); queries.clear(); b.open(60, snap)
        assertEquals(1, queries.size); assertEquals(1, b.state.value.page); assertEquals(a.ids(), b.ids())
    }
}
