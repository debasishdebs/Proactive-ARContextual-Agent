package com.rokid.context.phone.ui.settings.memory

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.work.frames.scene.WatchListStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WatchListRowTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun editSavesAndResetRestores() {
        val store = WatchListStore(ctx.getSharedPreferences("wl_row", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        compose.setContent {
            PcTheme {
                val items by store.items.collectAsState()
                androidx.compose.foundation.layout.Column { WatchListRow(items, onSave = { store.set(it) }, onReset = { store.reset() }) }
            }
        }
        compose.onNodeWithTag("watch_list_field").performTextReplacement(" bike ,umbrella, bike")
        compose.onNodeWithTag("watch_list_field").assertTextEquals(" bike ,umbrella, bike")
        compose.onNodeWithTag("watch_list_save").performClick()
        assertEquals(listOf("bike", "umbrella"), store.items.value)
        compose.onNodeWithTag("watch_list_reset").performClick()
        assertEquals(WatchListStore.DEFAULTS, store.items.value)
    }

    @Test fun settingsScreenSaveChangesTheSharedStore() {
        val store = WatchListStore.get(ctx).also { it.reset() }
        compose.setContent {
            PcTheme {
                val items by store.items.collectAsState()
                MemorySettingsScreen(
                    MemorySettingsUiState(), onBack = {}, onRecall = {}, onQuota = {}, onEncryption = {}, onExport = {},
                    onAskDeleteAll = {}, onConfirmDeleteAll = {}, onCancelDeleteAll = {}, onOpenReview = {},
                    watchList = items, onWatchListSave = store::set, onWatchListReset = store::reset,
                )
            }
        }
        compose.onNodeWithTag("memory_settings").performScrollToNode(hasTestTag("watch_list_reset"))
        compose.onNodeWithTag("watch_list_field").performTextReplacement("bike, umbrella")
        compose.onNodeWithTag("watch_list_save").performClick()
        assertEquals(listOf("bike", "umbrella"), WatchListStore.get(ctx).items.value)
        compose.onNodeWithTag("watch_list_reset").performClick()
        assertEquals(WatchListStore.DEFAULTS, WatchListStore.get(ctx).items.value)
    }
}
