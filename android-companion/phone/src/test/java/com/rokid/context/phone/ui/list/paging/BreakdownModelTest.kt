package com.rokid.context.phone.ui.list.paging

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.activity.Period
import com.rokid.context.phone.ui.activity.PeriodWindow
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.list.ListScope
import com.rokid.context.phone.ui.list.RecordListSource
import com.rokid.context.phone.ui.list.tableChanges
import com.rokid.context.phone.ui.preview.RepoActivityData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Activity pagination A5: the day/hour tree over the real database: live counts equal the tile count, hours page 25 at a time, four hours stay open at most, the state is saved. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class BreakdownModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val scopes = mutableListOf<CoroutineScope>()
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { scopes.forEach { it.cancel() }; TestGraph.close(ctx) }

    private class MapStore(val m: MutableMap<String, String> = mutableMapOf()) : StateStore {
        override fun get(key: String) = m[key]
        override fun set(key: String, value: String) { m[key] = value }
    }

    private val range = PeriodWindow.of(Period.MONTH, TODAY).millis(Fixtures.ZONE)
    private fun model(store: StateStore = MapStore()): BreakdownModel {
        val data = RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE }, tableChanges = { t -> graph.db.tableChanges(*t) })
        val list = RecordListSource(ListKind.MOMENTS, emptyList(), data, { Fixtures.ZONE }, { TODAY }, {}, scope = ListScope(Period.MONTH, TODAY))
        val m = BreakdownModel(ListKind.MOMENTS, data, range, { Fixtures.ZONE }, { TODAY }, list::summarize, store)
        m.start(CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it })
        return m
    }
    private suspend fun BreakdownModel.ready() = withTimeout(10_000) { tree.first { it.loaded && it.days.isNotEmpty() } }
    private suspend fun BreakdownModel.until(cond: (TreePane) -> Boolean) = withTimeout(10_000) { tree.first(cond) }
    private suspend fun seed(date: java.time.LocalDate, hour: Int, n: Int, tag: String) { for (i in 1..n) graph.db.memoryDao().insert(Fixtures.memory("$tag$i", "Moment $tag $i", Fixtures.at(date, hour, i % 60))) }
    private fun today(t: TreePane) = t.days.first { it.date == TODAY }
    private fun hours(t: TreePane) = today(t).hours.orEmpty().filterIsInstance<HourNode>()

    @Test fun daysAreNewestFirstTodayIsExpandedAndTheCountsEqualTheTile() = runBlocking<Unit> {
        seed(TODAY, 9, 30, "a"); seed(TODAY, 10, 3, "b"); seed(TODAY.minusDays(1), 14, 5, "y")
        val m = model(); val t = m.ready()
        assertEquals(listOf(TODAY, TODAY.minusDays(1)), t.days.map { it.date })
        assertEquals(listOf(33, 5), t.days.map { it.count })
        assertTrue(today(t).expanded); assertTrue(!t.days[1].expanded && t.days[1].hours == null)
        assertEquals("the hour counts add up to the day, which is the tile count", graph.timeline.visibleMemoryTimesIn(range).first().size, t.days.sumOf { it.count })
        assertEquals(33, hours(t).sumOf { it.count }); assertEquals(listOf("10:00", "09:00"), hours(t).map { it.label })
        assertEquals("Today · 33 moments", today(t).title)
    }

    @Test fun anHourPagesTwentyFiveAtATimeAndPagesReplaceEachOther() = runBlocking<Unit> {
        seed(TODAY, 9, 30, "a")
        val m = model(); val t = m.ready()
        val h = hours(t).single()
        m.toggleHour(TODAY, h.startMs)
        var open = hours(m.until { hours(it).single().expanded })[0]
        assertEquals(25, open.rows!!.size); assertEquals(2, open.pages); assertEquals(0, open.page)
        val first = open.rows!!.map { it.id }
        m.nextPage(TODAY, h.startMs)
        open = hours(m.tree.value).single()
        assertEquals(1, open.page); assertEquals(5, open.rows!!.size); assertTrue(first.intersect(open.rows!!.map { it.id }.toSet()).isEmpty())
        m.previousPage(TODAY, h.startMs)
        assertEquals(first, hours(m.tree.value).single().rows!!.map { it.id })
    }

    @Test fun atMostFourHoursStayOpenTheLeastRecentlyUsedCloses() = runBlocking<Unit> {
        for (h in 1..6) seed(TODAY, h, 2, "h$h")
        val m = model(); val t = m.ready()
        val starts = hours(t).sortedBy { it.startMs }.map { it.startMs }
        starts.take(5).forEach { m.toggleHour(TODAY, it) }
        val open = hours(m.tree.value).filter { it.expanded }.map { it.startMs }
        assertEquals(4, open.size); assertTrue("the first opened closed", starts[0] !in open); assertEquals(starts.slice(1..4).toSet(), open.toSet())
    }

    @Test fun aForgottenMomentLeavesItsHourAndTheCountsDrop() = runBlocking<Unit> {
        seed(TODAY, 9, 3, "a")
        val m = model(); val t = m.ready()
        m.toggleHour(TODAY, hours(t).single().startMs)
        graph.db.forgetDao().deleteMemories(listOf("a1"))
        val after = m.until { today(it).count == 2 }
        assertEquals(2, hours(after).single().count)
        graph.db.forgetDao().deleteMemories(listOf("a2", "a3"))
        val gone = m.until { today(it).count == 0 }
        assertTrue("an empty hour disappears", hours(gone).isEmpty())
    }

    @Test fun theExpandedDaysAndOpenHoursAndPageSurviveProcessDeath() = runBlocking<Unit> {
        seed(TODAY, 9, 30, "a"); seed(TODAY.minusDays(1), 14, 5, "y")
        val store = MapStore()
        val a = model(store); val t = a.ready()
        a.toggleDay(TODAY.minusDays(1))
        val start = hours(t).single().startMs
        a.toggleHour(TODAY, start); a.nextPage(TODAY, start)
        scopes.forEach { it.cancel() }
        val b = model(MapStore(store.m.toMutableMap()))
        val back = b.until { tr -> tr.days.size == 2 && hours(tr).singleOrNull()?.expanded == true }
        assertEquals(true, back.days.first { it.date == TODAY.minusDays(1) }.expanded)
        assertEquals(1, hours(back).single().page); assertEquals(5, hours(back).single().rows!!.size)
    }
}
