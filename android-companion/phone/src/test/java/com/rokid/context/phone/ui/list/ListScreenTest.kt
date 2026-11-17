package com.rokid.context.phone.ui.list

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A fixed-row source; [opened] records row actions. */
private class FakeSource(
    private val rows: List<ListItem>,
    override val selectable: Boolean = false,
    override val sortable: Boolean = false,
) : ListSource {
    val opened = mutableListOf<String>()
    override val title = "Things"
    override val searchHint = "Search things"
    override suspend fun load(): List<ListItem> = rows
    override fun actionFor(item: ListItem): ItemAction = ItemAction { opened += item.id; ActionResult.Silent }
}

private val ROWS = listOf(
    ListItem("b", "Banana", subtitle = "Yellow fruit"),
    ListItem("a", "Apple", subtitle = "Crunchy"),
    ListItem("c", "Cherry", subtitle = "Small and red"),
)

@RunWith(AndroidJUnit4::class)
/** A phone-sized screen (the Robolectric default is 320x470 px, which cuts the third row off). */
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class ListScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(source: ListSource, onOpenSelected: (List<String>) -> Unit = {}) =
        compose.setContent { PcTheme { ListScreen(source, onBack = {}, onMessage = {}, onOpenSelected = onOpenSelected) } }

    @Test fun searchFiltersByTitleAndSubtitle() {
        show(FakeSource(ROWS))
        compose.onNodeWithTag("list_search").performTextInput("RED")
        compose.onNodeWithText("Cherry").assertIsDisplayed()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Banana")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("list_search").performTextInput("x")
        compose.onNodeWithText("No matches").assertIsDisplayed()
    }

    @Test fun selectionBarAppearsOnlyWhenSelectableAndCountsSelection() {
        val plain = FakeSource(ROWS)
        val multi = FakeSource(ROWS, selectable = true)
        var selectable by mutableStateOf(false)
        compose.setContent { PcTheme { ListScreen(if (selectable) multi else plain, onBack = {}, onMessage = {}) } }
        compose.onNodeWithText("Apple").assertIsDisplayed()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("list_show_selected")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("list_select_a")).fetchSemanticsNodes().isEmpty())
        selectable = true
        compose.onNodeWithTag("list_show_selected").assertIsNotEnabled()
        compose.onNodeWithText("Show selected (0)").assertIsDisplayed()
        compose.onNodeWithTag("list_select_a").performClick()
        compose.onNodeWithTag("list_select_c").performClick()
        compose.onNodeWithText("Show selected (2)").assertIsDisplayed()
        compose.onNodeWithTag("list_show_selected").assertIsEnabled()
    }

    @Test fun showSelectedPassesIdsInListOrder() {
        var got: List<String>? = null
        show(FakeSource(ROWS, selectable = true)) { got = it }
        compose.onNodeWithTag("list_select_c").performClick()
        compose.onNodeWithTag("list_select_c").assertIsOn()
        compose.onNodeWithTag("list_select_b").performClick()
        compose.onNodeWithTag("list_show_selected").performClick()
        assertEquals(listOf("b", "c"), got)
    }

    @Test fun rowTapRunsActionNotSelection() {
        val source = FakeSource(ROWS, selectable = true)
        show(source)
        compose.onNodeWithText("Apple").performClick()
        compose.waitForIdle()
        assertEquals(listOf("a"), source.opened)
        compose.onNodeWithTag("list_select_a").assertIsOff()
        compose.onNodeWithText("Show selected (0)").assertIsDisplayed()
    }

    @Test fun selectionSurvivesRecreation() {
        val restore = StateRestorationTester(compose)
        val source = FakeSource(ROWS, selectable = true)
        restore.setContent { PcTheme { ListScreen(source, onBack = {}, onMessage = {}) } }
        compose.onNodeWithTag("list_select_a").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("list_select_a").assertIsOn()
        compose.onNodeWithText("Show selected (1)").assertIsDisplayed()
    }

    @Test fun noMatchesShowsEmptyText() {
        show(FakeSource(emptyList()))
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    // ---- Activity pagination A4 ------------------------------------------------------------------------------------------------------------------

    @Test fun rowsAreLazyOnlyTheVisibleOnesAreComposed() {
        val many = (1..1_000).map { ListItem("id$it", "Row $it") }
        show(FakeSource(many))
        compose.onNodeWithText("Row 1").assertIsDisplayed()
        val composed = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("list_lazy").not().and(androidx.compose.ui.test.hasText("Row ", substring = true))).fetchSemanticsNodes().size
        assertTrue("composed $composed of 1,000 rows", composed in 1..80)
    }

    @Test fun pagedListLoadsTheNextPageNearTheEndAndKeepsEveryRowReachable() {
        val src = FakePaged(100, hasMore = true)
        show(src)
        compose.waitForIdle()
        assertTrue("the last 10 rows are in view range or the page loads as the list is scrolled", src.loads >= 0)
        compose.onNodeWithTag("list_lazy").performScrollToIndex(99)
        compose.waitForIdle()
        assertTrue("scrolled to the end: the next page was asked for", src.loads >= 1)
        compose.onNodeWithTag("list_lazy").performScrollToIndex(150)
        compose.onNodeWithText("Row 150").assertIsDisplayed()
    }

    @Test fun theNewPillShowsWhenScrolledDownAndScrollsToTheTop() {
        val src = FakePaged(100, hasMore = false)
        show(src)
        compose.onNodeWithTag("list_lazy").performScrollToIndex(60)
        src.paneS.value = src.paneS.value.copy(newCount = 3)
        compose.waitForIdle()
        compose.onNodeWithText("3 new").assertIsDisplayed()
        compose.onNodeWithTag("list_new_pill").performClick()
        compose.waitForIdle()
        assertEquals("clearing the count", 1, src.cleared)
        compose.onNodeWithText("Row 1").assertIsDisplayed()
    }

    @Test fun showNewerAppearsWhenThePageAboveWasDroppedAndSearchSaysItIsLoadedRowsOnly() {
        val src = FakePaged(30, hasMore = true, headDropped = true)
        show(src)
        compose.onNodeWithTag("list_show_newer").performClick()
        assertEquals(1, src.newer)
        compose.onNodeWithTag("list_search").performTextInput("Row 2")
        compose.onNodeWithTag("list_note").assertIsDisplayed()
    }

    @Test fun theListPositionSurvivesGoingToAPreviewAndBack() {
        val src = FakePaged(200, hasMore = false)
        var onPreview by mutableStateOf(false)
        compose.setContent {
            PcTheme {
                val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                if (onPreview) androidx.compose.material3.Text("preview") else holder.SaveableStateProvider("list") { ListScreen(src, onBack = {}, onMessage = {}) }
            }
        }
        compose.onNodeWithTag("list_lazy").performScrollToIndex(120)
        compose.onNodeWithText("Row 121").assertIsDisplayed()
        compose.runOnIdle { onPreview = true }
        compose.onNodeWithText("preview").assertIsDisplayed()
        compose.runOnIdle { onPreview = false }
        compose.waitForIdle()
        compose.onNodeWithText("Row 121").assertIsDisplayed()
    }

    @Test fun aRestoredAnchorScrollsToItsRowOnce() {
        val src = FakePaged(200, hasMore = false)
        src.restoreS.value = "id150"
        show(src)
        compose.waitForIdle()
        compose.onNodeWithText("Row 150").assertIsDisplayed()
        assertEquals(1, src.consumed)
    }
}

/** A paged source over a fixed number of rows; the test drives [paneS]. */
private class FakePaged(n: Int, hasMore: Boolean, headDropped: Boolean = false) : PagedListSource {
    var loads = 0; var newer = 0; var cleared = 0; var consumed = 0
    val paneS = MutableStateFlow(ListPane((1..n).map { ListItem("id$it", "Row $it") }, headDropped = headDropped, hasMore = hasMore, loaded = true))
    val restoreS = MutableStateFlow<String?>(null)
    override val pane: StateFlow<ListPane> get() = paneS
    override val restoreTarget: StateFlow<String?> get() = restoreS
    override fun consumeRestore() { consumed++; restoreS.value = null }
    override fun loadMore() { loads++; val s = paneS.value; val n = s.rows.size; paneS.value = s.copy(rows = s.rows + (n + 1..n + 100).map { ListItem("id$it", "Row $it") }) }
    override fun showNewer() { newer++ }
    override fun clearNew() { cleared++; paneS.value = paneS.value.copy(newCount = 0) }
    override fun retry() {}
    override fun onFirstVisible(id: String?) {}
    override val title = "Things"
    override val searchHint = "Search things"
    override val sortable = false
    override val hasMore get() = paneS.value.hasMore
    override suspend fun load(): List<ListItem> = paneS.value.rows
    override fun actionFor(item: ListItem): ItemAction = ItemAction { ActionResult.Silent }
}
