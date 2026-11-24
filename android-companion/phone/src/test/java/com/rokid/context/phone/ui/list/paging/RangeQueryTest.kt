package com.rokid.context.phone.ui.list.paging

import com.rokid.context.phone.ui.list.RangeFilter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The query predicate of the date and time filter: the pager reads only the narrowed range from its source, page by page (nothing is dropped in memory afterwards). */
class RangeQueryTest {
    private fun source() = FakeSource().also { s -> (1..300).forEach { s.rows += com.rokid.context.memory.Cursor(it * 10L, "r$it") } }
    private fun ids(p: KeysetPager<com.rokid.context.memory.Cursor>) = p.state.value.rows.map { it.id }

    @Test fun onlyRowsInsideTheRangeAreReadAndTheEdgesAreIncluded() = runTest {
        val range = RangeFilter.narrow(0L..100_000L, 1_000L..2_000L) // r100 .. r200
        val p = KeysetPager(source(), range, pageSize = 50, windowPages = 10)
        p.loadFirst()
        assertEquals("r200", ids(p).first()); assertEquals(50, ids(p).size)
        p.loadMore(); p.loadMore()
        assertEquals(101, ids(p).size); assertEquals("r100", ids(p).last()); assertTrue(!p.state.value.hasMore)
    }

    @Test fun anEmptyRangeReadsNothing() = runTest {
        val p = KeysetPager(source(), RangeFilter.narrow(0L..5_000L, 6_000L..7_000L), pageSize = 50)
        p.loadFirst()
        assertTrue(ids(p).isEmpty()); assertTrue(p.state.value.loaded); assertTrue(!p.state.value.hasMore)
    }

    @Test fun noFilterReadsThePeriodAsBefore() = runTest {
        val p = KeysetPager(source(), RangeFilter.narrow(0L..100_000L, null), pageSize = 100)
        p.loadFirst(); assertEquals("r300", ids(p).first()); assertEquals(100, ids(p).size)
    }
}
