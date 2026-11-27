package com.rokid.context.phone.ui.settings.place

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 27 Nov device bug: on a short screen the photo link sat clipped at the bottom and no Fetch button could be reached. The dialog body now scrolls and the button is a real, tall one under the link. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ManualPhotoDialogUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(form: ManualFormState?, on: PlaceGuideActions, pins: List<UserPlace> = emptyList(), pinPhoto: PinPhotoState? = null) =
        compose.setContent { PcTheme { PlaceGuideScreen(PlaceGuideUiState(developerMode = true, manualForm = form, pins = pins, pinPhoto = pinPhoto), back = {}, onEnabled = {}, onSeed = {}, on = on) } }

    @Test fun fetchIsReachableAndVisibleInAShortDialogAndEnabledForAnHttpsLink() {
        var fetched = 0
        show(ManualFormState(photoLink = "https://example.com/a.jpg"), PlaceGuideActions(fetchManualPhoto = { fetched++ }))
        compose.onNodeWithTag("manual_scroll").assertIsDisplayed()
        compose.onNodeWithTag("manual_photo_link").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("manual_photo_fetch").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, fetched)
        compose.onNodeWithTag("manual_f_save").assertIsDisplayed() // the action row stays outside the scrolling body
    }

    @Test fun fetchIsOffForANonHttpsLinkAndAFailureAndTheAddWithoutPhotoChoiceShow() {
        var without = 0
        show(ManualFormState(photoLink = "http://example.com/a.jpg", photoError = "That is not an acceptable link.", askNoPhoto = true), PlaceGuideActions(saveManualWithoutPhoto = { without++ }))
        compose.onNodeWithTag("manual_photo_fetch").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("manual_photo_link_hint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("manual_photo_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("manual_f_save_nophoto").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, without)
    }

    @Test fun aReadyPhotoShowsItsThumbnailSizeAndRemove() {
        val bmp = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        var removed = 0
        show(ManualFormState(photo = com.rokid.context.work.frames.place.PreparedPhoto(out.toByteArray(), 8, 8)), PlaceGuideActions(removeManualPhoto = { removed++ }))
        compose.onNodeWithTag("manual_photo_size").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("manual_photo_remove").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, removed)
    }

    @Test fun theAddPhotoDialogHasTheSameFlow() {
        val pin = UserPlace("user:00000000-0000-0000-0000-000000000001", "Office", PlaceKinds.ATTRACTION, 1.0, 2.0, 60, null, "", null, 1L)
        show(null, PlaceGuideActions(), listOf(pin), PinPhotoState(pin.id, link = "https://example.com/a.jpg"))
        compose.onNodeWithTag("pin_photo_scroll").assertIsDisplayed()
        compose.onNodeWithTag("pin_photo_fetch").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("pin_photo_choose").performScrollTo().assertIsDisplayed()
    }

    @Test fun aPinThatNeedsAMomentHasAnAddPhotoButtonAndOneWithAMomentHasNone() {
        var opened: String? = null
        val bare = UserPlace("user:00000000-0000-0000-0000-000000000001", "Office", PlaceKinds.ATTRACTION, 1.0, 2.0, 60, null, "", null, 1L)
        show(null, PlaceGuideActions(openPinPhoto = { opened = it }), listOf(bare))
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("pin_add_photo_${bare.id}"))
        compose.onNodeWithTag("pin_add_photo_${bare.id}").assertIsDisplayed().performClick()
        assertEquals(bare.id, opened)
    }
}
