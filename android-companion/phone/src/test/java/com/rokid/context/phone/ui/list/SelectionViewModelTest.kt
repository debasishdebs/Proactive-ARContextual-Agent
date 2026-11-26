package com.rokid.context.phone.ui.list

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.orchestration.forget.ForgetActions
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.activity.Period
import androidx.lifecycle.viewModelScope
import com.rokid.context.phone.ui.list.paging.SelectionForgetPort
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

/** A7: the selection is a set of refs owned by the ViewModel, so paging, a dropped window and a process death never change it; with no port (the flag OFF) the selection does not exist. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SelectionViewModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<ListViewModel>()
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    // no join in tearDown: the main thread would wait for children that need it
    @After fun tearDown() { vms.forEach { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }; TestGraph.close(ctx) }

    private val port = object : SelectionForgetPort {
        override suspend fun preview(roots: Set<Ref>) = null
        override suspend fun forget(roots: Set<Ref>, token: String) = ForgetActions.Outcome.FAILED
    }
    private fun vm(kind: ListKind, saved: SavedStateHandle = SavedStateHandle(), on: Boolean = true): ListViewModel {
        val data = RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE }, tableChanges = { t -> graph.db.tableChanges(*t) })
        return ListViewModel(kind, emptyList(), ListScope(Period.TODAY, TODAY), data, { Fixtures.ZONE }, { TODAY }, saved).also { it.forgetPort = if (on) port else null; vms += it }
    }
    /** The ViewModel runs on the main looper: pump it (a runBlocking would starve it) until [cond] holds. */
    private fun until(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!cond()) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); if (cond()) return; check(System.currentTimeMillis() < end) { "condition not met" }; Thread.sleep(20) }
    }
    private fun ListViewModel.loaded() = until { source.pane.value.loaded }
    private fun ListViewModel.more() { val n = source.pane.value.rows.size; source.loadMore(); until { source.pane.value.rows.size > n || source.pane.value.headDropped } }
    private fun seed(n: Int) = runBlocking { val t = Fixtures.at(TODAY, 9); for (i in 1..n) graph.db.memoryDao().insert(Fixtures.memory("m$i", "Memory $i", t + i)) }

    @Test fun aSelectionSurvivesPagingAndTheDroppedHeadWindow() {
        seed(1_150)
        val v = vm(ListKind.MEMORIES); v.loaded()
        v.selection.enter("m1150")                                  // the newest row, on page 1
        v.selection.selectPage(v.source.pane.value.rows.map { it.id }) // "Select this page"
        assertEquals(100, v.selection.state.value.selection.size)
        repeat(10) { v.more() }                                     // page 11 drops page 1 from the window
        assertTrue("page 1 left the window", v.source.pane.value.headDropped && v.source.pane.value.rows.none { it.id == "m1150" })
        val sel = v.selection.state.value.selection
        assertEquals("the selection is untouched by paging", 100, sel.size); assertTrue(Ref(Family.MEMORY, "m1150") in sel.refs)
        v.source.showNewer()
        until { v.source.pane.value.rows.any { r -> r.id == "m1150" } }
        assertEquals(sel, v.selection.state.value.selection)
    }

    @Test fun aSelectionSurvivesProcessDeathThroughTheSavedState() {
        seed(5)
        val saved = SavedStateHandle(); val a = vm(ListKind.MEMORIES, saved); a.loaded()
        a.selection.enter("m2"); a.selection.toggle("m4")
        val b = vm(ListKind.MEMORIES, SavedStateHandle(mapOf("list.selection" to saved.get<String>("list.selection"))))
        assertEquals(a.selection.state.value.selection, b.selection.state.value.selection); assertEquals(2, b.selection.state.value.selection.size)
    }

    @Test fun aSelectionSurvivesSwitchingViewsOnAMomentsList() {
        seed(3)
        val v = vm(ListKind.MOMENTS); v.loaded()
        v.selection.enter("m1")
        v.setView(ListViewMode.BREAKDOWN); v.setView(ListViewMode.LIST); v.setView(ListViewMode.BREAKDOWN)
        assertEquals(setOf(Ref(Family.MEMORY, "m1")), v.selection.state.value.selection.refs)
    }

    @Test fun withNoPortTheSelectionDoesNotExist() {
        seed(3)
        val v = vm(ListKind.MEMORIES, on = false); v.loaded()
        v.selection.enter("m1"); v.selection.selectPage(listOf("m2")); v.selection.forgetSelected()
        assertFalse(v.selection.available); assertTrue(v.selection.state.value.selection.refs.isEmpty()); assertNull(v.selection.state.value.message)
    }

    @Test fun theGateIsOnByDefaultAndTheSettingsSwitchTurnsItOff() {
        com.rokid.context.orchestration.forget.DeveloperFlags.setForgetV2(ctx, true) // the default (owner 6b, 26 Nov): ON; written so a stale value of another test cannot leak
        assertTrue(SelectionGate.port(ctx) != null)
        try {
            com.rokid.context.orchestration.forget.DeveloperFlags.setForgetV2(ctx, false)
            assertNull(SelectionGate.port(ctx))
        } finally { com.rokid.context.orchestration.forget.DeveloperFlags.setForgetV2(ctx, true) }
        assertTrue(SelectionGate.port(ctx) != null)
    }

    @Test fun withNoStoredPreferenceTheDefaultIsOn() {
        ctx.getSharedPreferences("pc_dev", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(com.rokid.context.orchestration.forget.DeveloperFlags.forgetV2(ctx))
    }
}
