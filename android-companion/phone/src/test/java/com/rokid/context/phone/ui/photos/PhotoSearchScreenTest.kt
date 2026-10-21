package com.rokid.context.phone.ui.photos

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PhotoSearchScreenTest {
    @get:Rule val compose = createComposeRule()
    private val queries = mutableListOf<String>()
    private var submits = 0
    private val describes = mutableListOf<String>()
    private val scenes = mutableListOf<String>()
    private val frames = mutableListOf<Pair<String, String>>()
    private val memories = mutableListOf<String>()

    /** The sheet is a separate window; Robolectric's touch injection lands on its scrim, so its buttons are clicked through their semantics action. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)
    private fun child(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun tile(
        id: String, expired: Boolean = false, cluster: String? = null, captionId: String? = null, caption: String? = null, described: Boolean = false,
    ) = PhotoTile(id, if (expired) null else "/p/$id.jpg", "09:41", "0.55", "Image", described, expired, caption, cluster, "2026-10-12", captionId)

    private fun show(state: PhotoSearchUiState) = compose.setContent {
        PcTheme {
            PhotoSearchScreen(
                state, onBack = {}, onQueryChange = { queries += it }, onSubmit = { submits++ }, onDescribe = { describes += it }, onDescribeScene = { scenes += it },
                onOpenFrame = { date, id -> frames += date to id }, onOpenMemory = { memories += it },
            )
        }
    }

    @Test fun submitRunsOneSearchNotPerKeystroke() {
        show(PhotoSearchUiState())
        compose.onNodeWithTag("photo_query").performTextInput("keys")
        assertEquals("typing only reports the text", 0, submits)
        compose.onNodeWithTag("photo_submit").performClick()
        assertEquals(1, submits)
    }

    @Test fun showsBannerNoteStatusAndTiles() {
        show(PhotoSearchUiState(query = "keys", tiles = listOf(tile("a", described = true)), banner = "B-text", note = "N-text", status = "S-text"))
        compose.onNodeWithTag("photo_tile_a").assertIsDisplayed()
        compose.onNodeWithTag("photo_banner").assertIsDisplayed(); compose.onNodeWithText("B-text").assertIsDisplayed()
        compose.onNodeWithText("N-text").assertIsDisplayed(); compose.onNodeWithTag("photo_status").assertIsDisplayed()
    }

    @Test fun describedTileShowsTheCheckAndUndescribedDoesNot() {
        show(PhotoSearchUiState(tiles = listOf(tile("a", described = true), tile("b"))))
        child("described_a").assertIsDisplayed()
        child("described_b").assertDoesNotExist()
    }

    @Test fun expiredTileDisablesDescribe() {
        show(PhotoSearchUiState(tiles = listOf(tile("e", expired = true, captionId = "cap1", caption = "Keys on a desk"))))
        child("expired_e").assertIsDisplayed()
        compose.onNodeWithText("Keys on a desk", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("photo_tile_e").performClick()
        compose.onNodeWithTag("describe_photo").assertIsNotEnabled()
    }

    @Test fun describeShowsStatusAndDisablesWhileRunning() {
        show(PhotoSearchUiState(tiles = listOf(tile("a")), describing = true, status = "Described · 2 memories saved"))
        compose.onNodeWithTag("photo_status").assertIsDisplayed()
        compose.onNodeWithTag("photo_tile_a").performClick()
        compose.onNodeWithTag("describe_photo").assertIsNotEnabled()
    }

    @Test fun describeSendsTheFrame() {
        show(PhotoSearchUiState(tiles = listOf(tile("a"))))
        compose.onNodeWithTag("photo_tile_a").performClick()
        compose.onNodeWithTag("describe_photo").assertIsEnabled().tap()
        assertEquals(listOf("a"), describes)
    }

    @Test fun sceneActionOnlyWithCluster() {
        show(PhotoSearchUiState(tiles = listOf(tile("a"), tile("b", cluster = "c1"))))
        compose.onNodeWithTag("photo_tile_a").performClick()
        compose.onNodeWithTag("describe_scene").assertDoesNotExist()
        compose.onNodeWithText("Cancel").tap()
        compose.onNodeWithTag("photo_tile_b").performClick()
        compose.onNodeWithTag("describe_scene").tap()
        assertEquals(listOf("c1"), scenes)
    }

    @Test fun openGoesToFrameViewerOrMemory() {
        show(PhotoSearchUiState(tiles = listOf(tile("a"), tile("e", expired = true, captionId = "cap1", caption = "Keys"))))
        compose.onNodeWithTag("photo_tile_a").performClick()
        compose.onNodeWithTag("open_photo").tap()
        assertEquals(listOf("2026-10-12" to "a"), frames)
        compose.onNodeWithTag("photo_tile_e").performClick()
        compose.onNodeWithTag("open_photo").tap()
        assertEquals(listOf("cap1"), memories); assertEquals(1, frames.size)
    }

    @Test fun openDisabledWithoutPhotoOrCaption() {
        show(PhotoSearchUiState(tiles = listOf(tile("e", expired = true))))
        compose.onNodeWithText("Photo expired · no caption").assertIsDisplayed()
        compose.onNodeWithTag("photo_tile_e").performClick()
        compose.onNodeWithTag("open_disabled").assertIsNotEnabled()
        compose.onNodeWithTag("open_photo").assertDoesNotExist()
    }
}
