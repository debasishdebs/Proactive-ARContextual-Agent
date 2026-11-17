package com.rokid.context.phone.ui.list

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.FateCounts
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.forget.lifecycle.SelectionLimits
import com.rokid.context.memory.forget.lifecycle.SelectionPreview
import com.rokid.context.orchestration.forget.ForgetActions.Outcome
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.activity.Period
import androidx.lifecycle.viewModelScope
import com.rokid.context.phone.ui.list.paging.SelectionForgetPort
import com.rokid.context.phone.ui.list.paging.SelectionText
import com.rokid.context.phone.ui.preview.RepoActivityData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A7/A8 Compose: select mode (long press, toggle, accessibility), the top-right menu, the confirmation with the dependency preview, the acknowledgement, the changed-content re-confirm, the refusal, and nothing at all with the flag OFF. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class SelectionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private var vm: ListViewModel? = null
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    // cancel without joining: tearDown runs on the main thread, which the cancelled children still need
    @After fun tearDown() { vm?.viewModelScope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancel(); TestGraph.close(ctx) }

    private class Port(val previews: MutableList<SelectionPreview>, val outcomes: MutableList<Outcome> = mutableListOf(Outcome.DONE)) : SelectionForgetPort {
        val forgot = ArrayList<Pair<Set<Ref>, String>>()
        override suspend fun preview(roots: Set<Ref>): SelectionPreview = if (previews.size > 1) previews.removeAt(0) else previews.first()
        override suspend fun forget(roots: Set<Ref>, token: String): Outcome { forgot += roots to token; return outcomes.removeAt(0) }
    }
    private fun preview(roots: Int, needsAck: Boolean = false, hash: String = "hash") =
        SelectionPreview(roots, emptySet(), roots + 2, mapOf(Family.MEMORY to FateCounts(roots, 1, 0), Family.ANSWER to FateCounts(0, 2, 0), Family.RUN_RESULT to FateCounts(0, 0, 3)), hash, needsAck, false)

    private suspend fun seed(hour: Int, n: Int) { for (i in 1..n) graph.db.memoryDao().insert(Fixtures.memory("h${hour}_$i", "Moment $hour $i", Fixtures.at(TODAY, hour, i % 60))) }

    private fun show(kind: ListKind, port: SelectionForgetPort?): ListViewModel {
        val data = RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE }, tableChanges = { t -> graph.db.tableChanges(*t) })
        val v = ListViewModel(kind, emptyList(), ListScope(Period.TODAY, TODAY), data, { Fixtures.ZONE }, { TODAY }); v.forgetPort = port; vm = v
        compose.setContent {
            PcTheme {
                val ui = v.breakdown?.let { b -> BreakdownUi(b.tree, v.view, v::setView, BreakdownActions(v::toggleDay, v::toggleHour, v::previousPage, v::nextPage)) }
                ListScreen(v.source, onBack = {}, onMessage = {}, breakdown = ui, selection = v.selection)
            }
        }
        return v
    }
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun until(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) { compose.waitForIdle(); if (cond()) return; check(System.currentTimeMillis() < end) { "condition not met" }; Thread.sleep(20) }
    }
    private fun longPress(tag: String) = compose.onNodeWithTag(tag).performTouchInput { longClick() }
    private fun forgetFromMenu() { compose.onNodeWithTag("sel_menu").performClick(); compose.onNodeWithTag("sel_forget").performClick() }

    @Test fun withTheFlagOffThereIsNoMenuNoCheckboxAndALongPressDoesNothing() {
        kotlinx.coroutines.runBlocking { seed(9, 3) }
        show(ListKind.MEMORIES, null)
        compose.waitUntilAtLeastOneExists(hasTestTag("list_row_h9_1"), 5_000)
        assertTrue("the menu action is absent", !exists("sel_menu") && !exists("sel_forget"))
        longPress("list_row_h9_1"); compose.waitForIdle()
        assertTrue(!exists("sel_count") && !exists("list_pick_h9_1"))
    }

    @Test fun answersOfferNoSelectionEvenWithTheFlagOn() {
        kotlinx.coroutines.runBlocking { graph.asks.upsert(Fixtures.ask("a1", Fixtures.at(TODAY, 9))) }
        show(ListKind.ANSWERS, Port(mutableListOf(preview(1))))
        compose.waitUntilAtLeastOneExists(hasTestTag("list_row_a1"), 5_000)
        assertTrue(!exists("sel_menu"))
    }

    @Test fun aLongPressEntersSelectModeATapTogglesAndTheCountIsAnnounced() {
        kotlinx.coroutines.runBlocking { seed(9, 3) }
        val v = show(ListKind.MEMORIES, Port(mutableListOf(preview(1))))
        compose.waitUntilAtLeastOneExists(hasTestTag("list_row_h9_1"), 5_000)
        longPress("list_row_h9_1")
        until { exists("sel_count") }
        compose.onNodeWithText("1 selected").assertExists()
        assertEquals(setOf(Ref(Family.MEMORY, "h9_1")), v.selection.state.value.selection.refs)
        compose.onNodeWithTag("list_row_h9_2").performClick()                       // in select mode a tap toggles, it does not open
        until { v.selection.state.value.selection.size == 2 }
        compose.onNodeWithText("2 selected").assertExists()
        val state = compose.onNodeWithTag("list_row_h9_2", useUnmergedTree = false).fetchSemanticsNode().config
        assertEquals("Selected", state[SemanticsProperties.StateDescription]); assertEquals(true, state[SemanticsProperties.Selected])
        compose.onNodeWithTag("list_row_h9_3").fetchSemanticsNode().config.let { assertEquals("Not selected", it[SemanticsProperties.StateDescription]) }
        compose.onNodeWithTag("list_row_h9_2").performClick(); until { v.selection.state.value.selection.size == 1 }
        compose.onNodeWithTag("sel_done").performClick(); until { !exists("sel_count") }
        assertTrue(v.selection.state.value.selection.refs.isEmpty())
    }

    @Test fun theMenuSelectsThePageAndForgetsAfterAConfirmationThatShowsTheDependencies() {
        kotlinx.coroutines.runBlocking { seed(9, 4) }
        val port = Port(mutableListOf(preview(4)))
        val v = show(ListKind.MEMORIES, port)
        compose.waitUntilAtLeastOneExists(hasTestTag("list_row_h9_1"), 5_000)
        compose.onNodeWithTag("sel_menu").performClick(); compose.onNodeWithTag("sel_page").performClick()
        until { v.selection.state.value.selection.size == 4 }
        forgetFromMenu()
        until { exists("sel_confirm") }
        compose.onNodeWithText("Forget 4 selected?").assertExists()
        val lines = compose.onAllNodes(hasTestTag("sel_line"), useUnmergedTree = true).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertTrue(lines.toString(), lines.any { it.contains("memories") } && lines.any { it.contains("answers") } && lines.any { it.contains("generated results") })
        compose.onNodeWithTag("sel_confirm").assertIsEnabled().performClick()
        until { port.forgot.isNotEmpty() && v.selection.state.value.selection.refs.isEmpty() }
        assertEquals("hash", port.forgot.single().second); assertTrue(!exists("sel_confirm"))
    }

    @Test fun aLargeSelectionNeedsTheAcknowledgementTickBeforeForgetIsEnabled() {
        kotlinx.coroutines.runBlocking { seed(9, 2) }
        val port = Port(mutableListOf(preview(250, needsAck = true)))
        val v = show(ListKind.MEMORIES, port)
        compose.waitUntilAtLeastOneExists(hasTestTag("list_row_h9_1"), 5_000)
        longPress("list_row_h9_1"); until { exists("sel_count") }
        forgetFromMenu(); until { exists("sel_ack") }
        compose.onNodeWithTag("sel_confirm").assertIsNotEnabled()
        compose.onNodeWithText(SelectionText.ack(252)).assertExists()
        compose.onNodeWithTag("sel_ack", useUnmergedTree = true).performClick()
        until { runCatching { compose.onNodeWithTag("sel_confirm").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("sel_confirm").performClick()
        until { port.forgot.isNotEmpty() }
        assertEquals("hash+ack", port.forgot.single().second)
    }

    @Test fun aChangedSetReopensTheConfirmationWithTheNewCounts() {
        kotlinx.coroutines.runBlocking { seed(9, 2) }
        val port = Port(mutableListOf(preview(1, hash = "old"), preview(1, hash = "new").let { SelectionPreview(9, emptySet(), 11, it.counts, "new", false, false) }), mutableListOf(Outcome.CONTENT_CHANGED, Outcome.DONE))
        show(ListKind.MEMORIES, port)
        compose.waitUntilAtLeastOneExists(hasTestTag("list_row_h9_1"), 5_000)
        longPress("list_row_h9_1"); until { exists("sel_count") }
        forgetFromMenu(); until { exists("sel_confirm") }
        compose.onNodeWithTag("sel_confirm").performClick()
        until { exists("sel_changed") }
        compose.onNodeWithText(SelectionText.CHANGED).assertExists()
        compose.onNodeWithTag("sel_confirm").performClick()
        until { port.forgot.size == 2 }; assertEquals("new", port.forgot.last().second)
    }

    @Test fun aSelectionAboveTheCeilingDisablesForgetInTheMenuAndShowsTheRefusalText() {
        kotlinx.coroutines.runBlocking { seed(9, 1) }
        val v = show(ListKind.MEMORIES, Port(mutableListOf(preview(1))))
        compose.waitUntilAtLeastOneExists(hasTestTag("list_row_h9_1"), 5_000)
        v.selection.selectPage((1..SelectionLimits.REFUSE_ABOVE + 1).map { "x$it" })
        until { exists("sel_count") }
        compose.onNodeWithTag("sel_menu").performClick()
        compose.onNodeWithTag("sel_forget").assertIsNotEnabled()
        compose.onNodeWithText("Forget selected (5001): select fewer").assertExists()
        compose.onNodeWithTag("sel_clear").performClick(); until { !exists("sel_count") }
        v.selection.selectPage((1..SelectionLimits.REFUSE_ABOVE + 1).map { "x$it" }); v.selection.forgetSelected() // what a stale tap could still reach: refused before planning
        until { exists("sel_refused") }
        compose.onNodeWithText(SelectionText.refusedCeiling(5001)).assertExists()
        compose.onNodeWithTag("sel_refused_ok").performClick(); until { !exists("sel_refused") }
    }

    @Test fun aTooLargeClosureShowsSelectFewer() {
        kotlinx.coroutines.runBlocking { seed(9, 2) }
        val port = Port(mutableListOf(SelectionPreview(2, emptySet(), 0, emptyMap(), "", false, true)))
        show(ListKind.MEMORIES, port)
        compose.waitUntilAtLeastOneExists(hasTestTag("list_row_h9_1"), 5_000)
        longPress("list_row_h9_1"); until { exists("sel_count") }
        forgetFromMenu(); until { exists("sel_refused") }
        compose.onNodeWithText(SelectionText.REFUSED_TOO_LARGE).assertExists(); assertTrue(port.forgot.isEmpty())
    }

    @Test fun inTheBreakdownALongPressOnAnHourAndTheMenuSelectKindScopedIds() {
        kotlinx.coroutines.runBlocking { seed(9, 3); seed(10, 2); graph.db.frameDao().insertIgnore(Fixtures.frame("p", Fixtures.at(TODAY, 10, 5))) }
        val v = show(ListKind.MOMENTS, Port(mutableListOf(preview(1))))
        compose.onNodeWithTag("list_view_tree").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("bd_hour_${Fixtures.at(TODAY, 10)}"), 5_000)
        longPress("bd_hour_${Fixtures.at(TODAY, 10)}")
        until { v.selection.state.value.selection.size == 2 }
        assertTrue("memories only: the photo of that hour is not selected", v.selection.state.value.selection.refs.all { it.family == Family.MEMORY && it.id.startsWith("h10_") })
        compose.onNodeWithTag("bd_hour_${Fixtures.at(TODAY, 9)}").performClick()      // open an hour: it becomes the one "this hour" means
        until { exists("bd_row_h9_1") }
        compose.onNodeWithTag("sel_menu").performClick(); compose.onNodeWithTag("sel_hour").performClick()
        until { v.selection.state.value.selection.size == 5 }
        compose.onNodeWithTag("sel_menu").performClick(); compose.onNodeWithText("Select this day (5)").assertExists()
        compose.onNodeWithTag("sel_day").performClick()
        until { exists("sel_count") }
        assertEquals("a set: the same ids again change nothing", 5, v.selection.state.value.selection.size)
        longPress("bd_row_h9_1") // in select mode a long press toggles
        until { v.selection.state.value.selection.size == 4 }
    }
}
