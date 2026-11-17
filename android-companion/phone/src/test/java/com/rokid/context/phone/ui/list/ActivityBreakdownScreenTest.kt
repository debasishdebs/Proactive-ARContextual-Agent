package com.rokid.context.phone.ui.list

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.activity.Period
import com.rokid.context.phone.ui.list.paging.DayHourTree
import com.rokid.context.phone.ui.list.paging.DayNode
import com.rokid.context.phone.ui.list.paging.HourNode
import com.rokid.context.phone.ui.list.paging.TreePane
import com.rokid.context.phone.ui.preview.RepoActivityData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Activity pagination A6: the "By time" view of a period's Moments. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class ActivityBreakdownScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() = TestGraph.close(ctx)

    private suspend fun seed(hour: Int, n: Int) { for (i in 1..n) graph.db.memoryDao().insert(Fixtures.memory("h${hour}_$i", "Moment $hour $i", Fixtures.at(TODAY, hour, i % 60))) }

    private fun show(): ListViewModel {
        val data = RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE }, tableChanges = { t -> graph.db.tableChanges(*t) })
        val vm = ListViewModel(ListKind.MOMENTS, emptyList(), ListScope(Period.TODAY, TODAY), data, { Fixtures.ZONE }, { TODAY })
        compose.setContent {
            PcTheme {
                val ui = BreakdownUi(vm.breakdown!!.tree, vm.view, vm::setView, BreakdownActions(vm::toggleDay, vm::toggleHour, vm::previousPage, vm::nextPage))
                ListScreen(vm.source, onBack = {}, onMessage = {}, breakdown = ui)
            }
        }
        return vm
    }
    /** Polls [cond], letting the main looper run between polls (the model resumes on it). */
    private fun until(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) { compose.waitForIdle(); if (cond()) return; check(System.currentTimeMillis() < end) { "condition not met" }; Thread.sleep(20) }
    }
    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
    private fun startOf(hour: Int) = Fixtures.at(TODAY, hour)
    private fun nodesWithPrefix(prefix: String) = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("tag starts $prefix") { n -> (androidx.compose.ui.semantics.SemanticsProperties.TestTag in n.config && n.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith(prefix)) }).fetchSemanticsNodes().size

    @Test fun theTogglePicksTheViewAndTodayIsExpandedWithItsHours() {
        kotlinx.coroutines.runBlocking { seed(9, 3); seed(10, 2) }
        show()
        compose.onNodeWithTag("list_view_tree").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("bd_day_$TODAY"), 5_000)
        compose.onNodeWithText("Today · 5 moments").assertExists()
        compose.waitUntilAtLeastOneExists(hasTestTag("bd_hour_${startOf(10)}"), 5_000)
        compose.onNodeWithTag("bd_hour_${startOf(9)}").assertExists()
        compose.onNodeWithTag("list_view_list").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("list_row_h9_1"), 5_000)
    }

    private fun hoursNow(vm: ListViewModel) = vm.breakdown!!.tree.value.days.first { it.date == TODAY }.hours.orEmpty().filterIsInstance<HourNode>()
    private fun scrollTo(tag: String) { compose.onNodeWithTag("bd_lazy").performScrollToNode(hasTestTag(tag)) }

    @Test fun anHourOpensToPageOneOfTwoAndPagesReplaceEachOther() {
        kotlinx.coroutines.runBlocking { seed(9, 30) }
        val vm = show()
        compose.onNodeWithTag("list_view_tree").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("bd_hour_${startOf(9)}"), 5_000)
        compose.onNodeWithTag("bd_hour_${startOf(9)}").performClick()
        until { hoursNow(vm).firstOrNull()?.rows?.size == 25 }
        scrollTo("bd_page_${startOf(9)}")
        compose.onNodeWithText("1 of 2").assertExists()
        compose.onNodeWithTag("bd_prev_${startOf(9)}").assertIsNotEnabled()
        val first = hoursNow(vm).single().rows!!.map { it.id }
        compose.onNodeWithTag("bd_next_${startOf(9)}").performClick()
        until { hoursNow(vm).single().page == 1 }
        until { hoursNow(vm).single().rows?.size == 5 }
        assertTrue("pages replace each other", first.intersect(hoursNow(vm).single().rows!!.map { it.id }.toSet()).isEmpty())
        scrollTo("bd_page_${startOf(9)}")
        compose.onNodeWithText("2 of 2").assertExists()
        compose.onNodeWithTag("bd_next_${startOf(9)}").assertIsNotEnabled()
        compose.onNodeWithTag("bd_prev_${startOf(9)}").assertIsEnabled()
    }

    @Test fun atMostFourHoursStayOpen() {
        kotlinx.coroutines.runBlocking { for (h in 1..6) seed(h, 30) }
        val vm = show()
        compose.onNodeWithTag("list_view_tree").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("bd_hour_${startOf(6)}"), 5_000)
        for (h in 6 downTo 2) {
            scrollTo("bd_hour_${startOf(h)}")
            compose.onNodeWithTag("bd_hour_${startOf(h)}").performClick()
            until { hoursNow(vm).firstOrNull { it.startMs == startOf(h) }?.rows != null }
        }
        // Five hours were opened: the least recently used one (06:00) closed.
        until { hoursNow(vm).count { it.expanded } == 4 }
        assertTrue(hoursNow(vm).first { it.startMs == startOf(6) }.expanded.not())
        assertEquals(setOf(startOf(5), startOf(4), startOf(3), startOf(2)), hoursNow(vm).filter { it.expanded }.map { it.startMs }.toSet())
    }

    @Test fun anHourStillReadingReservesSkeletonRowsOfItsKnownCount() {
        val hour = HourNode(1_000, 2_000, "09:00", count = 40, pauses = emptyList(), expanded = true, pages = 2, rows = null)
        val tree = TreePane(listOf(DayNode(TODAY, "Today · 40 moments", 40, true, listOf(hour))), loaded = true)
        compose.setContent { PcTheme { BreakdownPane(tree, BreakdownActions({}, { _, _ -> }, { _, _ -> }, { _, _ -> }), onRow = {}, emptyText = "none") } }
        assertTrue("25 reserved, not 40", count("bd_skeleton") in 1..25)
    }

    @Test fun anHourWithOnlyAPauseShowsItsChipAndTheSameForAPrivacyZone() {
        val e = DayHourTree.hours(TODAY, Fixtures.ZONE, emptyList(), listOf(com.rokid.context.phone.ui.preview.PauseSpan(startOf(14) + 5 * 60_000, startOf(14) + 40 * 60_000, false)))
        val tree = TreePane(listOf(DayNode(TODAY, "Today · 0 moments", 0, true, e)), loaded = true)
        compose.setContent { PcTheme { BreakdownPane(tree, BreakdownActions({}, { _, _ -> }, { _, _ -> }, { _, _ -> }), onRow = {}, emptyText = "none") } }
        compose.onNodeWithText("Paused 14:05-14:40").assertExists()
        assertEquals(1, count("bd_chip"))
        assertTrue("folded quiet runs", count("bd_quiet") >= 1)
    }
}
