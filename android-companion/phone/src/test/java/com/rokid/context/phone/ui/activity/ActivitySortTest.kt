package com.rokid.context.phone.ui.activity

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.Fixtures.ZONE
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.ViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Activity's sort menu: Latest first (default), Oldest first, A-Z, Z-A; remembered in a restorable prefs file. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActivitySortTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<ViewModel>()

    @Before fun setUp() {
        graph = TestGraph.fresh(ctx)
        ctx.getSharedPreferences(PrefsActivitySort.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(sort: ActivitySortStore) = ActivityViewModel(
        Period.TODAY, FakeGlassesStatusSource(), FakePipelineControl(), graph.timeline, graph.asks, graph.nudges, graph.marks,
        clock = flowOf(Fixtures.at(TODAY, 20)), storagePct = flowOf(null), zone = { ZONE }, sort = sort,
    ).also { vms += it }

    /** Morning / afternoon / evening, plus a backlog row whose photo is from yesterday; texts in mixed case. */
    private val mems = listOf(
        Fixtures.memory("m1", "banana bread", Fixtures.at(TODAY, 8)),
        Fixtures.memory("m2", "Apple on the desk", Fixtures.at(TODAY, 10)),
        Fixtures.memory("a1", "cherry tomatoes", Fixtures.at(TODAY, 13)),
        Fixtures.memory("e1", "Date night", Fixtures.at(TODAY, 19)),
        Fixtures.memory("old", "egg box", Fixtures.at(TODAY, 9), frameId = "f-old"),
    )
    private val frames = listOf(Fixtures.frame("f-old", Fixtures.at(TODAY.minusDays(1), 22)))
    private fun sections(order: ActivitySort) = Timeline.sections(mems, frames, ZONE, TODAY, order)
    private fun ids(s: List<TimelineSection>) = s.flatMap { sec -> sec.rows.map { it.memoryId } }

    private val week = PeriodWindow.of(Period.WEEK, TODAY.plusDays(2)) // Wed 14 Oct
    private val counts = mapOf(TODAY to 1, TODAY.plusDays(1) to 1, TODAY.plusDays(2) to 1)
    private fun dayRows(order: ActivitySort) = ActivityModel.dayRows(week, counts, emptyMap(), TODAY.plusDays(2), order)

    @Test fun defaultOrderIsLatestFirst() = runBlocking {
        assertEquals(ActivitySort.LATEST_FIRST, ActivitySort.DEFAULT)
        assertEquals(ActivitySort.LATEST_FIRST, InMemoryActivitySort().order.value)
        assertEquals(ActivitySort.LATEST_FIRST, PrefsActivitySort(ctx).order.value)
        // Default Timeline.sections: newest section first, newest row first; the backlog (yesterday's photo) last.
        val s = Timeline.sections(mems, frames, ZONE, TODAY)
        assertEquals(listOf("Evening", "Afternoon", "Morning", Timeline.EARLIER), s.map { it.label })
        assertEquals(listOf("e1", "a1", "m2", "m1", "old"), ids(s))
        assertEquals(listOf(TODAY.plusDays(2), TODAY.plusDays(1), TODAY), ActivityModel.dayRows(week, counts, emptyMap(), TODAY.plusDays(2)).map { it.date })
        mems.forEach { graph.memories.add(it) }
        frames.forEach { graph.frames.add(it) }
        val st = withTimeout(5_000) { vm(InMemoryActivitySort()).state.first { it.today == TODAY && it.tiles.memories == 5 } }
        assertEquals(ActivitySort.LATEST_FIRST, st.sort)
        assertEquals("e1", st.sections.first().rows.first().memoryId)
    }

    @Test fun oldestFirstReverses() {
        val s = sections(ActivitySort.OLDEST_FIRST)
        assertEquals(listOf(Timeline.EARLIER, "Morning", "Afternoon", "Evening"), s.map { it.label })
        assertEquals(ids(sections(ActivitySort.LATEST_FIRST)).reversed(), ids(s))
        assertEquals(listOf(TODAY, TODAY.plusDays(1), TODAY.plusDays(2)), dayRows(ActivitySort.OLDEST_FIRST).map { it.date })
    }

    @Test fun alphaOrdersByTitleCaseInsensitive() {
        val s = sections(ActivitySort.A_Z)
        // Ruling: alpha order flattens the part-of-day / Earlier days sections into one list.
        assertEquals(listOf(Timeline.ALL), s.map { it.label })
        assertEquals(listOf("Apple on the desk", "banana bread", "cherry tomatoes", "Date night", "egg box"), s.single().rows.map { it.text })
        assertEquals("Sun 11 Oct 22:00", s.single().rows.last().time) // the backlog row keeps its date
        assertEquals(listOf("Mon 12 Oct", "Today", "Yesterday"), dayRows(ActivitySort.A_Z).map { it.title })
    }

    @Test fun reverseAlpha() {
        val s = sections(ActivitySort.Z_A)
        assertEquals(listOf(Timeline.ALL), s.map { it.label })
        assertEquals(listOf("egg box", "Date night", "cherry tomatoes", "banana bread", "Apple on the desk"), s.single().rows.map { it.text })
        assertEquals(listOf("Yesterday", "Today", "Mon 12 Oct"), dayRows(ActivitySort.Z_A).map { it.title })
    }

    @Test fun choiceSurvivesRecreationAndRestart() = runBlocking {
        mems.forEach { graph.memories.add(it) }
        frames.forEach { graph.frames.add(it) }
        val store = PrefsActivitySort(ctx)
        val first = vm(store)
        withTimeout(5_000) { first.state.first { it.tiles.memories == 5 } }
        first.selectSort(ActivitySort.Z_A)
        val zA = withTimeout(5_000) { first.state.first { it.sort == ActivitySort.Z_A } }
        assertEquals("egg box", zA.sections.single().rows.first().text)
        // Recreation: a new ViewModel over the same (process-wide) store.
        val again = withTimeout(5_000) { vm(store).state.first { it.tiles.memories == 5 } }
        assertEquals(ActivitySort.Z_A, again.sort)
        // Restart: a new store reads the prefs file back.
        val restarted = PrefsActivitySort(ctx)
        assertEquals(ActivitySort.Z_A, restarted.order.value)
        assertEquals(ActivitySort.Z_A, withTimeout(5_000) { vm(restarted).state.first { it.tiles.memories == 5 } }.sort)
        // The Day screen reached from Activity uses the same order.
        val day = DayViewModel(TODAY.toString(), graph.timeline, zone = { ZONE }, today = { TODAY }, sort = restarted.order).also { vms += it }
        val ds = withTimeout(5_000) { day.state.first { it.found && it.sections.isNotEmpty() } }
        assertEquals(listOf(Timeline.ALL), ds.sections.map { it.label })
        assertEquals("egg box", ds.sections.single().rows.first().text)
        // An unknown stored value falls back to the default.
        ctx.getSharedPreferences(PrefsActivitySort.PREFS, Context.MODE_PRIVATE).edit().putString(PrefsActivitySort.KEY, "SIDEWAYS").commit()
        assertEquals(ActivitySort.LATEST_FIRST, PrefsActivitySort(ctx).order.value)
    }
}
