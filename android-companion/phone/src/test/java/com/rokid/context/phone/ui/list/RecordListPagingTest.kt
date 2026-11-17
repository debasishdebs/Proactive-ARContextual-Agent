package com.rokid.context.phone.ui.list

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.activity.Period
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Activity pagination A4 over the real database: a period's list pages with no cap, hides what is forgotten, and finds its place again after a process death. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RecordListPagingTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val scopes = mutableListOf<CoroutineScope>()
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { scopes.forEach { it.cancel() }; TestGraph.close(ctx) }

    private fun source(kind: ListKind = ListKind.ANSWERS, saved: SavedStateHandle = SavedStateHandle()): RecordListSource {
        val data = RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE }, tableChanges = { t -> graph.db.tableChanges(*t) })
        val s = RecordListSource(kind, emptyList(), data, { Fixtures.ZONE }, { TODAY }, onPreview = {}, scope = ListScope(Period.TODAY, TODAY))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        s.startIn(scope, saved)
        return s
    }
    private suspend fun RecordListSource.loaded() = withTimeout(10_000) { pane.first { it.loaded } }
    private suspend fun RecordListSource.more(): Unit { val n = pane.value.rows.size; loadMore(); withTimeout(10_000) { pane.first { it.rows.size > n } } }
    private suspend fun seed(n: Int) { val t = Fixtures.at(TODAY, 9); for (i in 1..n) graph.asks.upsert(Fixtures.ask("a$i", t + i)) }

    @Test fun aPeriodListPagesWithoutACapAndSkipsEvalAsks() = runBlocking<Unit> {
        seed(250); graph.asks.upsert(Fixtures.ask("eval", Fixtures.at(TODAY, 9) + 7).copy(source = "canvas_eval"))
        val s = source(); s.loaded()
        assertEquals(100, s.pane.value.rows.size); assertTrue(s.pane.value.hasMore); assertEquals("a250", s.pane.value.rows.first().id)
        s.more(); s.more()
        assertEquals(250, s.pane.value.rows.size); assertFalse(s.pane.value.hasMore)
        assertTrue("the oldest is reachable and the eval ask is never listed", s.pane.value.rows.last().id == "a1" && s.pane.value.rows.none { it.id == "eval" })
    }

    @Test fun aForgottenRowLeavesTheOpenListAfterTheDebouncedRefresh() = runBlocking<Unit> {
        val t = Fixtures.at(TODAY, 9)
        for (i in 1..30) graph.db.memoryDao().insert(Fixtures.memory("m$i", "Memory $i", t + i))
        val s = source(ListKind.MEMORIES); s.loaded()
        assertTrue(s.pane.value.rows.any { it.id == "m5" })
        graph.db.forgetDao().deleteMemories(listOf("m5")) // what a Forget does
        withTimeout(10_000) { while (s.pane.value.rows.any { it.id == "m5" }) kotlinx.coroutines.delay(50) }
        assertEquals(29, s.pane.value.rows.size)
    }

    @Test fun theFirstVisibleRowSurvivesProcessDeath() = runBlocking<Unit> {
        seed(250)
        val saved = SavedStateHandle()
        val a = source(saved = saved); a.loaded(); a.more(); a.more()
        a.onFirstVisible("a120")
        scopes.forEach { it.cancel() } // the process dies; only the SavedStateHandle's contents are kept
        val b = source(saved = SavedStateHandle(mapOf(RecordListSource.KEY_ANCHOR to saved.get<String>(RecordListSource.KEY_ANCHOR))))
        b.loaded()
        withTimeout(10_000) { b.restoreTarget.first { it != null } }
        assertEquals("a120", b.restoreTarget.value)
        assertTrue(b.pane.value.rows.any { it.id == "a120" })
        b.consumeRestore(); assertEquals(null, b.restoreTarget.value)
    }

    @Test fun theAnchorFormatRoundTripsAndRejectsJunk() {
        val a = com.rokid.context.phone.ui.list.paging.PagerAnchor(com.rokid.context.memory.Cursor(5L, "x"), com.rokid.context.memory.Cursor(3L, "y"))
        assertEquals(a, RecordListSource.parseAnchor(RecordListSource.formatAnchor(a)))
        assertEquals(null, RecordListSource.parseAnchor("junk"))
        val top = com.rokid.context.phone.ui.list.paging.PagerAnchor(com.rokid.context.memory.Cursor(5L, ""), null)
        assertEquals(top, RecordListSource.parseAnchor(RecordListSource.formatAnchor(top)))
    }
}
