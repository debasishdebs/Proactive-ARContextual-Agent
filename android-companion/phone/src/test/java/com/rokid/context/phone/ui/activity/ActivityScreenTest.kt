package com.rokid.context.phone.ui.activity

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.status.StatusUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActivityScreenTest {
    @get:Rule val compose = createComposeRule()
    private val periods = mutableListOf<Period>()
    private val memories = mutableListOf<String>()
    private val frameDays = mutableListOf<LocalDate>()
    private val days = mutableListOf<LocalDate>()
    private var pauses = 0
    private val status = StatusUi("Capturing · next 4:12", live = true, paused = false, batteryPct = 82, banner = null)

    private fun show(state: ActivityUiState) = compose.setContent {
        PcTheme {
            ActivityScreen(
                state, onSelectPeriod = { periods += it }, onTogglePause = { pauses++ }, onBannerAction = {},
                onOpenMemory = { memories += it }, onOpenFrames = { frameDays += it }, onOpenDay = { days += it },
            )
        }
    }

    @Test fun todayShowsStripTilesAndTimeline() {
        show(
            ActivityUiState(
                period = Period.TODAY, header = "Mon 12 Oct", status = status, tiles = Tiles(14, 2, 3, 1), today = TODAY,
                sections = listOf(TimelineSection("Morning", listOf(TimelineRow("m1", "07:40", "Oatmeal and coffee at the counter", 0.92, null, null)))),
            ),
        )
        compose.onNodeWithText("What your glasses noticed").assertIsDisplayed()
        compose.onNodeWithText("Capturing · next 4:12").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause capture").performClick()
        assertEquals(1, pauses)
        compose.onNodeWithText("14").assertIsDisplayed()
        compose.onNodeWithText("Frames").performClick()
        assertEquals(listOf(TODAY), frameDays)
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText("Oatmeal and coffee at the counter"))
        compose.onNodeWithText("MORNING").assertIsDisplayed()
        compose.onNodeWithText("92%").assertIsDisplayed()
        compose.onNodeWithText("Oatmeal and coffee at the counter").performClick()
        assertEquals(listOf("m1"), memories)
    }

    /** Final review I1: under a glasses pause the strip has no ⏸/▶ (a tap would switch the phone pipeline off). */
    @Test fun glassesPauseHidesThePipelineToggle() {
        val onGlasses = StatusUi("Paused on glasses", false, true, null, null, pipelineEnabled = true, pausedOnGlasses = true)
        show(ActivityUiState(period = Period.TODAY, header = "Mon 12 Oct", status = onGlasses, today = TODAY))
        compose.onNodeWithText("Paused on glasses").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Resume capture").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Pause capture").assertCountEquals(0)
    }

    @Test fun emptyTodayShowsHint() {
        show(ActivityUiState(period = Period.TODAY, header = "Mon 12 Oct", status = status, today = TODAY))
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText("Nothing captured yet today"))
        compose.onNodeWithText("Nothing captured yet today").assertIsDisplayed()
    }

    @Test fun weekShowsChartAndOpensADay() {
        val week = PeriodWindow.of(Period.WEEK, TODAY)
        show(
            ActivityUiState(
                period = Period.WEEK, header = "Week of 12 Oct", status = status, tiles = Tiles(96, 210, 17, 9), today = TODAY,
                bars = ActivityModel.bars(week, mapOf(TODAY to 14)),
                dayRows = listOf(DayRow(TODAY.minusDays(1), "Yesterday", "9 frames · 20 memories")),
            ),
        )
        val chart = "Bar chart: M 14, T 0, W 0, T 0, F 0, S 0, S 0"
        compose.onNodeWithTag("activity_list").performScrollToNode(hasContentDescription(chart)) // below the fold on a phone-sized viewport
        compose.onNodeWithContentDescription(chart).assertIsDisplayed()
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText("Yesterday"))
        compose.onNodeWithText("Yesterday").performClick()
        assertEquals(listOf(TODAY.minusDays(1)), days)
    }

    @Test fun emptyWeekShowsHintAndSwitchReportsPeriod() {
        show(ActivityUiState(period = Period.WEEK, header = "Week of 12 Oct", status = status, today = TODAY, bars = ActivityModel.bars(PeriodWindow.of(Period.WEEK, TODAY), emptyMap())))
        compose.onNodeWithText("Month").performClick() // before scrolling: off-screen LazyColumn items are disposed
        assertEquals(listOf(Period.MONTH), periods)
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText("No activity this week"))
        compose.onNodeWithText("No activity this week").assertIsDisplayed()
    }
}
