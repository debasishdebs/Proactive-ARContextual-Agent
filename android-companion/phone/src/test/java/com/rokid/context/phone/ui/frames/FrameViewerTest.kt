package com.rokid.context.phone.ui.frames

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FrameViewerTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<FrameViewerViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(date: String?, start: String? = null) =
        FrameViewerViewModel(date, start, graph.timeline, zone = { Fixtures.ZONE }, today = { TODAY }).also { vms += it }

    private val two = FrameViewerUiState(
        loading = false, found = true, title = "Today",
        frames = listOf(ViewerFrame("f1", "/missing/f1.jpg", "09:00"), ViewerFrame("f2", "/missing/f2.jpg", "09:05")), startIndex = 0,
    )

    @Test fun framesOfTheDayOldestFirstStartingAtTheRequestedFrame() = runBlocking {
        graph.frames.add(Fixtures.frame("f2", Fixtures.at(TODAY, 9, 5)))
        graph.frames.add(Fixtures.frame("f1", Fixtures.at(TODAY, 9, 0)))
        graph.frames.add(Fixtures.frame("other", Fixtures.at(TODAY.minusDays(1), 9)))
        val s = withTimeout(5_000) { vm(TODAY.toString(), start = "f2").state.first { it.found } }
        assertEquals(listOf("f1", "f2"), s.frames.map { it.id })
        assertEquals(1, s.startIndex)
        assertEquals("Today", s.title)
        assertEquals(0, withTimeout(5_000) { vm(TODAY.toString(), start = "nope").state.first { it.found } }.startIndex)
        assertFalse(withTimeout(5_000) { vm("12-10-2026").state.first { !it.loading } }.found)
    }

    @Test fun swipingMovesToTheNextFrame() {
        compose.setContent { PcTheme { FrameViewerScreen(two, onBack = {}) } }
        compose.onNodeWithText("1 / 2 · 09:00").assertIsDisplayed()
        compose.onNodeWithTag("frame_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 2 · 09:05").assertIsDisplayed()
    }

    @Test fun startIndexAndPlayToggle() {
        compose.setContent { PcTheme { FrameViewerScreen(two.copy(startIndex = 1), onBack = {}) } }
        compose.onNodeWithText("2 / 2 · 09:05").assertIsDisplayed()
        compose.mainClock.autoAdvance = false // keep the 1.5 s autoplay step from elapsing before the assertion
        compose.onNodeWithContentDescription("Play").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test fun viewerShowsPlaceholderForMissingFile() {
        compose.setContent { PcTheme { FrameViewerScreen(two, onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Photo no longer on this phone").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun emptyOrUnknownDay() {
        compose.setContent { PcTheme { FrameViewerScreen(FrameViewerUiState(loading = false, found = true, title = "Today"), onBack = {}) } }
        compose.onNodeWithText("No photos for this day").assertIsDisplayed()
    }
}
