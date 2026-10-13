package com.rokid.context.phone.ui.settings.connectors

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.db.ShoppingItemEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ShoppingListTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyStateShown() {
        compose.setContent { PcTheme { ShoppingListScreen(emptyList(), onDone = { _, _ -> }, onDelete = {}, back = {}) } }
        compose.onNodeWithText("Nothing on your list").assertIsDisplayed()
    }

    @Test fun checkboxTogglesDone() {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        val items = listOf(ShoppingItemEntity("i1", "milk", null, null, false, 1, "p"))
        compose.setContent { PcTheme { ShoppingListScreen(items, onDone = { id, d -> toggled += id to d }, onDelete = {}, back = {}) } }
        compose.onNodeWithTag("done_i1").performClick()
        assertEquals(listOf("i1" to true), toggled)
    }

    /** Device run 2026-10-14: the list looked like it had no delete control. Swiping removes and offers Undo. */
    @Test fun swipeRemovesTheItemAndOffersUndo() {
        val deleted = mutableListOf<String>()
        val restored = mutableListOf<ShoppingItemEntity>()
        val items = listOf(ShoppingItemEntity("i1", "milk", null, null, false, 1, "p"))
        compose.setContent { PcTheme { ShoppingListScreen(items, onDone = { _, _ -> }, onDelete = { deleted += it }, back = {}, onRestore = { restored += it }) } }
        compose.onNodeWithTag("shopping_row_i1").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf("i1"), deleted)
        compose.onNodeWithText("Removed milk").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(items, restored)
    }

    @Test fun talkBackDeleteActionRemovesTheItem() {
        val deleted = mutableListOf<String>()
        val items = listOf(ShoppingItemEntity("i1", "milk", 2.0, "l", false, 1, "p"))
        compose.setContent { PcTheme { ShoppingListScreen(items, onDone = { _, _ -> }, onDelete = { deleted += it }, back = {}) } }
        val actions = compose.onNodeWithTag("shopping_row_i1").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == "Delete" }.action() }
        compose.waitForIdle()
        assertEquals(listOf("i1"), deleted)
        compose.onNodeWithText("Removed 2 l milk").assertIsDisplayed()
    }
}
