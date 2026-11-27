package com.rokid.context.phone.ui.settings.place

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.db.CardLabel
import com.rokid.context.memory.db.PlaceCardLogEntity
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.work.frames.place.LabelSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** B3: one-tap Right / Wrong / Not a place on each history row (change or clear) and the counts-only "Labelled" line. Synthetic ids only. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PlaceHistoryLabelTest {
    @get:Rule val compose = createComposeRule()
    private val d = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(d) }
    @After fun after() { Dispatchers.resetMain() }

    private fun row(id: String, at: Long, label: String? = null, kind: String = "PHOTO", delivery: String = CardDelivery.SENT) =
        PlaceCardLogEntity(id, at, "wd:Q$id", "Site $id", kind, null, "f-$id", null, "c-$id", delivery, 0, null, label, if (label != null) at + 1 else null)

    @Test fun theLineAndTheThreeButtonsShowAndATapSendsTheNextLabel() {
        val taps = mutableListOf<Pair<String, String?>>()
        val rows = listOf(row("a", 200, label = CardLabel.RIGHT).toUi(), row("b", 100).toUi())
        compose.setContent { PcTheme { PlaceCardsScreen(PlaceGuideUiState(history = rows, labelSummary = LabelSummary(1, 2, 3, 7)), back = {}, on = PlaceGuideActions(setLabel = { id, l -> taps += id to l })) } }
        compose.onNodeWithTag("place_history_label_summary").assertIsDisplayed()
        fun tap(tag: String) { compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).assertIsDisplayed().performClick() }
        tap("place_history_label_right_b"); tap("place_history_label_wrong_b"); tap("place_history_label_not_a_place_b")
        tap("place_history_label_right_a") // already RIGHT: the tap clears it
        tap("place_history_label_wrong_a") // change
        assertEquals(listOf("b" to "RIGHT", "b" to "WRONG", "b" to "NOT_A_PLACE", "a" to null, "a" to "WRONG"), taps)
    }

    @Test fun summaryLineIsCountsOnlyInTheAgreedWords() {
        assertEquals("Labelled: 4 right, 2 wrong, 1 not a place; attempts shown: 9", PlaceHistoryModel.labelSummaryLine(LabelSummary(4, 2, 1, 9)))
        assertNull(PlaceHistoryModel.nextLabel("RIGHT", "RIGHT")); assertEquals("WRONG", PlaceHistoryModel.nextLabel("RIGHT", "WRONG")); assertEquals("RIGHT", PlaceHistoryModel.nextLabel(null, "RIGHT"))
        assertEquals(listOf("Right", "Wrong", "Not a place"), PlaceHistoryModel.LABELS.map { it.second })
    }

    private class FakeHistory(var rows: List<PlaceCardLogEntity>) : PlaceHistoryControl {
        var summary = LabelSummary(0, 0, 0, 0)
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun rows() = rows.sortedByDescending { it.at }
        override suspend fun line(row: PlaceCardLogEntity) = "line"
        override suspend fun photoPath(frameId: String): String? = null
        override suspend fun delete(id: String) = true
        override fun showAgainBlock(row: PlaceCardLogEntity): String? = null
        override suspend fun showAgain(row: PlaceCardLogEntity) = true
        override suspend fun setLabel(id: String, label: String?): Boolean {
            calls += id to label
            rows = rows.map { if (it.id == id) it.copy(label = label, labelAt = if (label == null) null else 5L) else it }
            summary = LabelSummary(rows.count { it.label == "RIGHT" }, rows.count { it.label == "WRONG" }, rows.count { it.label == "NOT_A_PLACE" }, rows.size)
            return true
        }
        override suspend fun labelSummary() = summary
    }

    @Test fun theViewModelStoresReReadsTheRowAndTheCounts() {
        val h = FakeHistory(listOf(row("a", 100), row("b", 200)))
        val control = object : PlaceGuideControl {
            override var enabled = true
            override suspend fun seed(box: com.rokid.context.memory.place.GeoBox) = Result.success(SeedOutcome(""))
            override suspend fun summary() = ""
            override val history: PlaceHistoryControl? = h
        }
        val v = PlaceGuideViewModel(control, d); d.scheduler.advanceUntilIdle()
        v.setLabel("a", "RIGHT"); d.scheduler.advanceUntilIdle()
        assertEquals("RIGHT", v.state.value.history.first { it.id == "a" }.label); assertEquals(LabelSummary(1, 0, 0, 2), v.state.value.labelSummary)
        v.setLabel("a", null); d.scheduler.advanceUntilIdle()
        assertNull(v.state.value.history.first { it.id == "a" }.label); assertEquals(LabelSummary(0, 0, 0, 2), v.state.value.labelSummary)
        assertEquals(listOf("a" to "RIGHT", "a" to null), h.calls)
    }

    @Test fun historyOffShowsNoLabelLine() {
        val h = FakeHistory(listOf(row("a", 100)))
        val control = object : PlaceGuideControl {
            override var enabled = true
            override suspend fun seed(box: com.rokid.context.memory.place.GeoBox) = Result.success(SeedOutcome(""))
            override suspend fun summary() = ""
            override val history: PlaceHistoryControl? = object : PlaceHistoryControl by h { override val historyOn get() = false }
        }
        val v = PlaceGuideViewModel(control, d); d.scheduler.advanceUntilIdle()
        assertNull(v.state.value.labelSummary)
    }
}
