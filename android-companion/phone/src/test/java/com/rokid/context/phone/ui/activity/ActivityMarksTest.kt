package com.rokid.context.phone.ui.activity

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.MomentDao
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.memory.moment.NoPhotoReason
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.status.StatusUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The read-only "Marks" section of the Activity tab: the rows (newest first; time, code, joined Moment, no-photo reason), the empty state, and what the screen draws. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActivityMarksTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<ActivityViewModel>()
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(initial: Period = Period.TODAY) = ActivityViewModel(
        initial, FakeGlassesStatusSource(), FakePipelineControl(), graph.timeline, graph.asks, graph.nudges, graph.marks,
        clock = flowOf(Fixtures.at(TODAY, 20)), storagePct = flowOf(null), zone = { Fixtures.ZONE },
    ).also { vms += it }

    private suspend fun mark(id: String, at: Long, code: String, provenance: String = "clock:mapped") {
        val e = DeletionEpoch("i", 1, 0)
        graph.db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity(id, "MOMENT", code, "MOMENT", at, provenance = provenance, createdAt = 1), DaoFence(0, e, { 0 }, { e }))
    }

    @Test fun aFallbackMomentSaysWhyAndAMarkWithNoMomentYetShowsItsOwnPhotoState() = runBlocking<Unit> {
        val e = DeletionEpoch("i", 1, 0); val f = DaoFence(0, e, { 0 }, { e })
        val s3 = graph.db.canvasSlice3Dao()
        s3.insertLabelFenced(CanvasLabelEntity("a", "MOMENT", "tap", "MOMENT", Fixtures.at(TODAY, 8, 5), createdAt = 1, photo = "camera_busy"), f)
        s3.insertLabelFenced(CanvasLabelEntity("b", "MOMENT", "tap", "MOMENT", Fixtures.at(TODAY, 9, 5), createdAt = 1, photo = "pending"), f)
        s3.insertLabelFenced(CanvasLabelEntity("c", "MOMENT", "tap", "MOMENT", Fixtures.at(TODAY, 9, 30), createdAt = 1, photo = "heat"), f)
        s3.insertLabelFenced(CanvasLabelEntity("d", "MOMENT", "tap", "MOMENT", Fixtures.at(TODAY, 9, 45), createdAt = 1, photo = "ok", frameId = "p"), f)
        graph.db.momentDao().replace(MomentEntity("0000000a", 1, 1, "h", 1, MomentType.MARK, NoPhotoReason.UNKNOWN, errorReason = "camera_busy"), listOf(MomentPartEntity("0000000a", MomentPartKind.MARK, "a", "r", 1, "tap")))
        val st = withTimeout(5_000) { vm().state.first { it.today == TODAY && it.marks.size == 4 } }
        assertEquals(listOf(null, "No photo: the glasses were too warm", "Photo on its way", "No photo: the camera was busy"), st.marks.map { it.reason })
    }

    @Test fun theReasonTokensAreWordsAndAnUnknownTokenIsShownAsIs() {
        assertEquals("the camera was busy", MarkRows.reasonWords("camera_busy"))
        assertEquals("the photo never arrived", MarkRows.reasonWords("photo_timeout"))
        assertEquals("some_new_code", MarkRows.reasonWords("some_new_code"))
        assertEquals("No photo: reason unknown", MarkRows.reasonLabel(MomentType.MARK, NoPhotoReason.UNKNOWN))
        assertNull(MarkRows.reasonLabel(MomentType.FRAME, null, "camera_busy"))
    }

    @Test fun marksAreListedNewestFirstWithTimeCodeAndTheMomentThatHoldsThem() = runBlocking<Unit> {
        mark("a", Fixtures.at(TODAY, 8, 5), "tap"); mark("b", Fixtures.at(TODAY, 9, 30), "voice", "clock:receipt;call"); mark("c", Fixtures.at(TODAY, 9, 45), "ask_tap")
        mark("old", Fixtures.at(TODAY.minusDays(1), 23), "tap")
        graph.db.momentDao().replace(MomentEntity("0000000b", 1, 1, "h", 1, MomentType.MARK, NoPhotoReason.UNKNOWN), listOf(MomentPartEntity("0000000b", MomentPartKind.MARK, "b", "r", 1, "voice")))
        graph.db.momentDao().replace(MomentEntity("0000000a", 1, 1, "h", 1, MomentType.FRAME), listOf(MomentPartEntity("0000000a", MomentPartKind.MARK, "a", "r", 1, "tap")))
        val s = withTimeout(5_000) { vm().state.first { it.today == TODAY && it.marks.size == 3 } }
        assertEquals(listOf("c", "b", "a"), s.marks.map { it.labelId })
        assertEquals(listOf("09:45", "09:30", "08:05"), s.marks.map { it.time })
        assertEquals("only a mark made during a call is labelled; how a mark was made is never shown", listOf("", "During a call", ""), s.marks.map { it.code })
        assertEquals(listOf("Not in a Moment yet", "Moment with no photo", "In a Moment with a photo"), s.marks.map { it.joined })
        assertEquals(listOf(null, "No photo: reason unknown", null), s.marks.map { it.reason })
    }

    @Test fun theWeekShowsTheDayWithTheTimeAndAnEmptyPeriodHasNoMarks() = runBlocking<Unit> {
        mark("later", Fixtures.at(TODAY.plusDays(2), 9), "retro")
        val week = withTimeout(5_000) { vm(Period.WEEK).state.first { it.period == Period.WEEK && it.marks.isNotEmpty() } }
        assertEquals(listOf("Wed 14 Oct 09:00"), week.marks.map { it.time }); assertEquals(listOf(""), week.marks.map { it.code })
        val today = withTimeout(5_000) { vm().state.first { it.today == TODAY } }
        assertEquals(emptyList<MarkRowUi>(), today.marks)
    }

    @Test fun theSectionOnlyReadsAndTheTableIsUntouched() = runBlocking<Unit> {
        mark("a", Fixtures.at(TODAY, 8), "tap")
        withTimeout(5_000) { vm().state.first { it.marks.size == 1 } }
        assertEquals(listOf("a"), graph.db.canvasSlice3Dao().labels().map { it.labelId })
        assertNull(graph.db.momentDao().moment("0000000a"))
    }

    @Test fun everyMomentTypeAndReasonHasAWord() {
        assertEquals(listOf("In a Moment with a photo", "Moment with no photo"), MomentType.entries.map { MarkRows.joinedLabel(it) })
        assertEquals(listOf("No photo: reason unknown"), NoPhotoReason.entries.map { MarkRows.reasonLabel(MomentType.MARK, it) })
        assertNull("a photo Moment has no reason to show", MarkRows.reasonLabel(MomentType.FRAME, null))
        assertEquals("During a call", MarkRows.callLabel("clock:mapped;call"))
        assertEquals("a normal mark has no label", "", MarkRows.callLabel("clock:receipt"))
        assertEquals("a chat or voice or tap source never becomes a label", "", MarkRows.callLabel(""))
    }

    private val status = StatusUi("Capturing", live = true, paused = false, batteryPct = 82, banner = null)
    private fun show(state: ActivityUiState) = compose.setContent {
        PcTheme { ActivityScreen(state, onSelectPeriod = {}, onTogglePause = {}, onBannerAction = {}, onOpenMemory = {}, onOpenFrames = {}, onOpenDay = {}) }
    }

    @Test fun theScreenShowsTheEmptyStateAndTheRows() {
        show(ActivityUiState(period = Period.TODAY, header = "Mon 12 Oct", status = status, today = TODAY))
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText("MARKS"))
        compose.onNodeWithText("MARKS").assertIsDisplayed()
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText(MarkRows.EMPTY))
        compose.onNodeWithText(MarkRows.EMPTY).assertIsDisplayed()
    }

    @Test fun theScreenDrawsOneCardPerMark() {
        val rows = MarkRows.rows(
            listOf(MomentDao.MarkRow("b", "voice", Fixtures.at(TODAY, 9), "0000000b", MomentType.MARK, NoPhotoReason.UNKNOWN)), Fixtures.ZONE, withDay = false,
        )
        show(ActivityUiState(period = Period.TODAY, header = "Mon 12 Oct", status = status, today = TODAY, marks = rows))
        compose.onNodeWithTag("activity_list").performScrollToNode(hasText("No photo: reason unknown"))
        compose.onAllNodesWithTag("mark_row").assertCountEquals(1)
        compose.onNodeWithText("Moment with no photo").assertIsDisplayed()
    }
}
