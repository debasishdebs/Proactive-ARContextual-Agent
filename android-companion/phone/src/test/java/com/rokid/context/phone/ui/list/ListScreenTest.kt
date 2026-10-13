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
@Config(sdk = [34], application = Application::class)
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
}
