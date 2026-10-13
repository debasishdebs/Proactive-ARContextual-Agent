package com.rokid.context.phone.ui.settings.connectors

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
