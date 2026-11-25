package com.rokid.context.phone.ui.activity

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.Fixtures.ZONE
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.list.DateTimeRange
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.list.ListScope
import com.rokid.context.phone.ui.list.RangeFilter
import com.rokid.context.phone.ui.list.listView
import com.rokid.context.phone.ui.status.StatusUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** The Activity page's custom range (Range chip): the list key with and without a range, the page's counts for it, the way back to the period chips, and the chip itself. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActivityRangeTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<ViewModel>()
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private val start = LocalDateTime.of(2026, 10, 10, 9, 0)
    private val end = LocalDateTime.of(2026, 10, 12, 17, 30)
    private val range = RangeFilter.of(start, end)!!

    // The list key --------------------------------------------------------------------------------------------------------------------------------------

    @Test fun theOldKeyStillParsesAndAKeyWithARangeRoundTrips() {
        assertEquals(ListScope(Period.MONTH, TODAY), ListScope.parse("MONTH:2026-10-12"))
        assertNull(ListScope.parse("MONTH:2026-10-12").let { it?.range })
        assertEquals("MONTH:2026-10-12", ListScope(Period.MONTH, TODAY).key)
        val scope = ListScope(Period.WEEK, TODAY, range)
        assertEquals("WEEK:2026-10-12:2026-10-10T09:00|2026-10-12T17:30", scope.key)
        assertEquals(scope, ListScope.parse(scope.key))
        assertEquals("the route carries the key, never ids", "list/FRAMES?scope=WEEK%3A2026-10-12%3A2026-10-10T09%3A00%7C2026-10-12T17%3A30", listView(ListKind.FRAMES, Period.WEEK, TODAY, range))
        assertEquals("a period-only list route is unchanged", "list/FRAMES?scope=MONTH%3A2026-10-12", listView(ListKind.FRAMES, Period.MONTH, TODAY))
    }

    @Test fun aDamagedKeyIsRefusedNeverGuessed() {
        listOf("junk", "", "WEEK:nope", "WEEK:2026-10-12:", "WEEK:2026-10-12:garbage", "WEEK:2026-10-12x", "WEEK:2026-10-12:2026-10-12T09:00|2026-10-10T09:00", "WEEK:2026-1-2").forEach { assertNull(it, ListScope.parse(it)) }
        assertNull(ListScope.parse(null))
    }

    @Test fun aRangeScopeReadsTheRangeNotThePeriod() {
        val ms = ListScope(Period.TODAY, TODAY, range).millis(ZONE)
        assertEquals(RangeFilter.millis(range, ZONE), ms)
        assertEquals(PeriodWindow.of(Period.MONTH, TODAY).millis(ZONE), ListScope(Period.MONTH, TODAY).millis(ZONE))
    }

    // The page ------------------------------------------------------------------------------------------------------------------------------------------

    private fun vm() = ActivityViewModel(
        Period.TODAY, FakeGlassesStatusSource(), FakePipelineControl(), graph.timeline, graph.asks, graph.nudges, graph.marks,
        clock = flowOf(Fixtures.at(TODAY, 20)), storagePct = flowOf(null), zone = { ZONE },
    ).also { vms += it }

    @Test fun aRangeCountsExactlyItsOwnTimeAndTheChipsAreTheWayBack() = runBlocking<Unit> {
        val day = TODAY.minusDays(1) // 11 Oct
        graph.frames.add(Fixtures.frame("in", Fixtures.at(day, 10)))
        graph.frames.add(Fixtures.frame("before", Fixtures.at(LocalDateTime.of(2026, 10, 10, 8, 59).toLocalDate(), 8, 59)))
        graph.frames.add(Fixtures.frame("today", Fixtures.at(TODAY, 8)))
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", Fixtures.at(day, 11)))
        val model = vm()
        assertFalse("a range that starts after it ends is refused", model.selectRange(end, start))
        assertTrue(model.selectRange(start, end))
        val s = withTimeout(5_000) { model.state.first { it.range != null && it.tiles.frames == 2 } }
        assertEquals(1, s.tiles.memories); assertEquals("10 Oct 09:00 to 12 Oct 17:30", s.header)
        assertEquals(3, s.bars.size); assertEquals(listOf("Today", "Yesterday"), s.dayRows.map { it.title })
        model.selectPeriod(Period.MONTH)
        val back = withTimeout(5_000) { model.state.first { it.range == null && it.period == Period.MONTH } }
        assertEquals("October 2026", back.header)
        model.selectRange(start, end); withTimeout(5_000) { model.state.first { it.range != null } }
        model.clearRange()
        assertNull(withTimeout(5_000) { model.state.first { it.range == null } }.range)
    }

    // The chip ------------------------------------------------------------------------------------------------------------------------------------------

    private val status = StatusUi("Capturing", live = true, paused = false, batteryPct = 80, banner = null)
    private val periods = mutableListOf<Period>()
    private var cleared = 0
    private fun show(state: ActivityUiState) = compose.setContent {
        PcTheme { ActivityScreen(state, onSelectPeriod = { periods += it }, onTogglePause = {}, onBannerAction = {}, onPreview = {}, onOpenList = { _, _ -> }, onOpenDay = {}, onClearRange = { cleared++ }) }
    }

    @Test fun theRangeChipOpensTheDateTimeDialogAndThePeriodChipsStillWork() {
        show(ActivityUiState(header = "Mon 12 Oct", status = status, today = TODAY))
        compose.onNodeWithText("Range").assertIsDisplayed()
        compose.onNodeWithText("Range").performClick()
        compose.onNodeWithTag("list_filter_dialog").assertIsDisplayed()
        compose.onNodeWithTag("list_filter_cancel").performClick()
        compose.onNodeWithText("Week").performClick()
        assertEquals(listOf(Period.WEEK), periods)
    }

    @Test fun anActiveRangeNamesItselfAndItsXReturnsToThePeriodChips() {
        val r: DateTimeRange = range
        show(ActivityUiState(header = RangeFilter.label(r), status = status, today = TODAY, range = r))
        compose.onNodeWithTag("list_filter_chip").assertIsDisplayed()
        compose.onNodeWithTag("list_filter_clear").performClick()
        assertEquals(1, cleared)
    }
}
