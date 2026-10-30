package com.rokid.context.phone.ui.activity

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DayTest {
    private val main = MainDispatcherRule()
    private val compose = createComposeRule()
    /** Explicit order: Main is installed before, and reset after, the Compose rule (unordered @Rules raced on Dispatchers.setMain). */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(main).around(compose)
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<DayViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(arg: String?) = DayViewModel(arg, graph.timeline, zone = { Fixtures.ZONE }, today = { TODAY }).also { vms += it }

    @Test fun malformedDateShowsNotFound() = runBlocking {
        for (arg in listOf("2026-13-45", "", null)) assertFalse("$arg", withTimeout(5_000) { vm(arg).state.first { !it.loading } }.found)
    }

    @Test fun extremeDateShowsNotFound() = runBlocking {
        assertFalse(withTimeout(5_000) { vm("+999999999-12-31").state.first { !it.loading } }.found)
    }

    @Test fun loadsThatDaysTimeline() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Market run", Fixtures.at(TODAY.minusDays(2), 18)))
        val s = withTimeout(5_000) { vm(TODAY.minusDays(2).toString()).state.first { it.found && it.sections.isNotEmpty() } }
        assertEquals("Sat 10 Oct", s.title)
        assertEquals(listOf("Evening"), s.sections.map { it.label })
    }

    @Test fun backlogMemoryFromAnEarlierDayPhotoKeepsItsPhoto() = runBlocking {
        graph.frames.add(Fixtures.frame("old", Fixtures.at(TODAY.minusDays(3), 23, 10), path = "/old.jpg"))
        graph.memories.add(Fixtures.memory("m1", "Cable by the cushion", Fixtures.at(TODAY.minusDays(2), 9), frameId = "old"))
        val s = withTimeout(5_000) { vm(TODAY.minusDays(2).toString()).state.first { !it.loading } }
        assertEquals("/old.jpg", s.sections.flatMap { it.rows }.single().framePath)
    }

    @Test fun dayScreenListsRowsAndEveryTapGoesThroughPreview() {
        val opened = mutableListOf<List<String>>()
        val state = DayUiState(
            loading = false, found = true, date = TODAY, title = "Today", frameIds = listOf("f1", "f2", "f3"),
            sections = listOf(TimelineSection("Morning", listOf(TimelineRow("m1", "07:40", "Breakfast", 0.9, null, null)))),
        )
        compose.setContent { PcTheme { DayScreen(state, onBack = {}, onPreview = { opened += it }) } }
        compose.onNodeWithText("Breakfast").assertIsDisplayed()
        compose.onNodeWithText("View 3 photos").performClick()
        compose.onNodeWithText("Breakfast").performClick()
        assertEquals("the photos page by swipe from the first; a row previews its memory", listOf(listOf("frame:f1"), listOf("memory:m1")), opened)
    }

    @Test fun dayScreenNotFound() {
        compose.setContent { PcTheme { DayScreen(DayUiState(loading = false, found = false), onBack = {}, onPreview = {}) } }
        compose.onNodeWithText("Day not found").assertIsDisplayed()
    }

    @Test fun showThatTimePicksTheNearestRowsListIndex() {
        fun row(id: String, at: Long) = TimelineRow(id, "", id, 0.8, null, null, at)
        val sections = listOf(TimelineSection("Morning", listOf(row("a", 1_000), row("b", 5_000))), TimelineSection("Afternoon", listOf(row("c", 9_000))))
        // items: 0 header, 1 photos chip, 2 "Morning", 3 a, 4 b, 5 "Afternoon", 6 c
        assertEquals(3, DayScroll.indexFor(sections, 0, hasPhotosChip = true))
        assertEquals(4, DayScroll.indexFor(sections, 4_000, hasPhotosChip = true))
        assertEquals(6, DayScroll.indexFor(sections, 99_000, hasPhotosChip = true))
        assertEquals("no chip: one item less", 3, DayScroll.indexFor(sections, 4_000, hasPhotosChip = false))
        assertEquals("a tie takes the first in list order", 3, DayScroll.indexFor(sections, 3_000, hasPhotosChip = true))
        assertEquals(null, DayScroll.indexFor(emptyList(), 1, hasPhotosChip = true))
        // latest-first lists run the other way: the index follows the list, not the clock
        assertEquals(2, DayScroll.indexFor(listOf(TimelineSection("Evening", listOf(row("z", 9_000), row("y", 1_000)))), 9_000, hasPhotosChip = false))
    }
}
