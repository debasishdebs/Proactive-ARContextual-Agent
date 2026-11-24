package com.rokid.context.phone.ui.list

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.activity.Period
import com.rokid.context.phone.ui.preview.RepoActivityData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** The date and time filter of the common list: applied in the query, kept across a rotation or process death, and composed with the period. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ListFilterViewModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<ListViewModel>()
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }; TestGraph.close(ctx) }

    private fun vm(saved: SavedStateHandle = SavedStateHandle(), scope: ListScope? = ListScope(Period.TODAY, TODAY)): ListViewModel {
        val data = RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE }, tableChanges = { t -> graph.db.tableChanges(*t) })
        return ListViewModel(ListKind.ANSWERS, emptyList(), scope, data, { Fixtures.ZONE }, { TODAY }, saved).also { vms += it }
    }
    private fun until(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!cond()) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); if (cond()) return; check(System.currentTimeMillis() < end) { "condition not met" }; Thread.sleep(20) }
    }
    private fun ListViewModel.ids(): Set<String> = source.pane.value.rows.map { it.id }.toSet()
    private fun ListViewModel.loaded() = until { source.pane.value.loaded }
    private fun at(h: Int, m: Int = 0): LocalDateTime = TODAY.atTime(h, m)
    /** Asks at 08:00, 09:00, 09:30, 10:00 and 18:00. */
    private fun seed() = runBlocking {
        for ((id, t) in listOf("a8" to Fixtures.at(TODAY, 8), "a9" to Fixtures.at(TODAY, 9), "a930" to Fixtures.at(TODAY, 9, 30), "a10" to Fixtures.at(TODAY, 10), "a18" to Fixtures.at(TODAY, 18))) graph.asks.upsert(Fixtures.ask(id, t))
    }

    @Test fun noFilterShowsEverythingInThePeriod() {
        seed(); val v = vm(); v.loaded()
        assertEquals(setOf("a8", "a9", "a930", "a10", "a18"), v.ids()); assertNull(v.filter.value)
    }

    @Test fun applyingARangeShowsOnlyItemsInsideAndClearingBringsThemBack() {
        seed(); val v = vm(); v.loaded()
        assertTrue(v.applyFilter(at(9), at(10)))
        until { v.source.pane.value.loaded && v.ids() == setOf("a9", "a930", "a10") }
        v.clearFilter()
        until { v.ids().size == 5 }
        assertNull(v.filter.value)
    }

    @Test fun theEndMinuteItselfIsIncludedButNotTheNext() {
        seed(); val v = vm(); v.loaded()
        v.applyFilter(at(9), at(9, 30))
        until { v.ids() == setOf("a9", "a930") }
        v.applyFilter(at(9), at(9, 29))
        until { v.ids() == setOf("a9") }
    }

    @Test fun aStartAfterTheEndIsRefusedAndTheListIsUnchanged() {
        seed(); val v = vm(); v.loaded()
        assertFalse(v.applyFilter(at(11), at(9)))
        assertNull(v.filter.value); assertEquals(5, v.ids().size)
    }

    @Test fun aRangeWithNothingInItGivesAnEmptyLoadedList() {
        seed(); val v = vm(); v.loaded()
        v.applyFilter(at(12), at(13))
        until { v.source.pane.value.loaded && v.ids().isEmpty() }
    }

    @Test fun aRangeOutsideThePeriodIsEmpty() {
        seed(); val v = vm(); v.loaded()
        v.applyFilter(TODAY.plusDays(3).atTime(0, 0), TODAY.plusDays(3).atTime(23, 59))
        until { v.source.pane.value.loaded && v.ids().isEmpty() }
    }

    @Test fun theFilterSurvivesANewViewModelOverTheSameSavedState() {
        seed(); val saved = SavedStateHandle(); val a = vm(saved); a.loaded()
        a.applyFilter(at(9), at(10))
        until { a.ids() == setOf("a9", "a930", "a10") }
        val b = vm(SavedStateHandle(mapOf(ListViewModel.KEY_FILTER to saved.get<String>(ListViewModel.KEY_FILTER))))
        b.loaded()
        assertEquals(a.filter.value, b.filter.value)
        until { b.ids() == setOf("a9", "a930", "a10") }
    }

    @Test fun aListOverASmallSetOfIdsHasNoFilter() {
        val v = vm(scope = null)
        assertFalse(v.canFilter); assertFalse(v.applyFilter(at(9), at(10))); assertNull(v.filter.value)
    }
}
