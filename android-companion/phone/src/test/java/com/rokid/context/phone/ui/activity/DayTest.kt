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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DayTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
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

    @Test fun dayScreenListsRowsAndOpensFrames() {
        val opened = mutableListOf<LocalDate>()
        val state = DayUiState(
            loading = false, found = true, date = TODAY, title = "Today", frameCount = 3,
            sections = listOf(TimelineSection("Morning", listOf(TimelineRow("m1", "07:40", "Breakfast", 0.9, null, null)))),
        )
        compose.setContent { PcTheme { DayScreen(state, onBack = {}, onOpenMemory = {}, onOpenFrames = { opened += it }) } }
        compose.onNodeWithText("Breakfast").assertIsDisplayed()
        compose.onNodeWithText("View 3 photos").performClick()
        assertEquals(listOf(TODAY), opened)
    }

    @Test fun dayScreenNotFound() {
        compose.setContent { PcTheme { DayScreen(DayUiState(loading = false, found = false), onBack = {}, onOpenMemory = {}, onOpenFrames = {}) } }
        compose.onNodeWithText("Day not found").assertIsDisplayed()
    }
}
