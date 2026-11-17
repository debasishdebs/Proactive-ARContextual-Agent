package com.rokid.context.phone.ui.preview

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.Cursor
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.activity.Period
import com.rokid.context.phone.ui.activity.PeriodWindow
import com.rokid.context.phone.ui.list.ListKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Activity pagination A2: nothing a list shows is capped at 1,000 (answers, nudges) or 200 (marks), and a page filter equals its tile-count filter. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RepoActivityDataTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() = TestGraph.close(ctx)

    private fun data() = RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE })
    private val range = PeriodWindow.of(Period.TODAY, TODAY).millis(Fixtures.ZONE)

    /** Every id of [kind] in the period, 100 a page. */
    private suspend fun all(kind: ListKind): List<String> {
        val d = data(); val out = mutableListOf<String>(); var after = Cursor.head(range)
        while (true) {
            val p = d.page(kind, range, after, 100); out += p.ids
            if (p.ids.size < 100) return out
            after = p.cursors.last()
        }
    }

    @Test fun theThousandAndFirstAnswerAndNudgeAreListedAndOpenable() = runBlocking<Unit> {
        val t = Fixtures.at(TODAY, 9)
        for (i in 1..1_001) { graph.asks.upsert(Fixtures.ask("a$i", t + i)); graph.nudges.addIgnore(Fixtures.nudge("n$i", t + i)) }
        graph.asks.upsert(Fixtures.ask("eval", t + 5).copy(source = "canvas_eval"))
        assertEquals(1_001, all(ListKind.ANSWERS).size); assertEquals(1_001, all(ListKind.NUDGES).size)
        assertTrue("the oldest answer is reachable", "a1" in all(ListKind.ANSWERS) && "eval" !in all(ListKind.ANSWERS))
        assertEquals("the tile count and the list agree", graph.asks.timesIn(range).first().size, all(ListKind.ANSWERS).size)
        assertEquals(listOf("a1"), data().asks(listOf("a1")).map { it.id })
        assertEquals(listOf("n1"), data().nudges(listOf("n1")).map { it.id })
    }

    @Test fun theTwoHundredAndFirstMarkIsListedAndOpenable() = runBlocking<Unit> {
        val e = DeletionEpoch("i", 0, 0); val f = DaoFence(0, e, { 0 }, { e })
        val t = Fixtures.at(TODAY, 9)
        for (i in 1..201) graph.db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity("k$i", "MOMENT", "tap", "X", t + i, createdAt = 1), f)
        val ids = all(ListKind.MARKS)
        assertEquals(201, ids.size); assertTrue("k1" in ids)
        assertEquals(listOf("k1"), data().marks(listOf("k1")).map { it.labelId })
    }

    @Test fun aPageCarriesItsRowsSoTheSummaryReadsNothingMore() = runBlocking<Unit> {
        val t = Fixtures.at(TODAY, 9)
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", t))
        graph.memories.add(Fixtures.memory("hidden", "Needs review", t + 1, review = com.rokid.context.memory.db.ReviewState.NEEDS_REVIEW))
        val page = data().page(ListKind.MEMORIES, range, Cursor.head(range), 100)
        assertEquals(listOf("m1"), page.ids)
        assertEquals(listOf("m1"), page.data.memories(listOf("m1")).map { it.id })
        assertEquals("the visible memory tile count and the list agree", graph.timeline.visibleMemoryTimesIn(range).first().size, page.ids.size)
    }
}
