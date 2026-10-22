package com.rokid.context.phone.ui.settings.ai

import android.app.Application
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fix round 1 (C1): Settings > AI > "Send matching photos with questions", default off, naming the endpoint the photos go to. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AskPhotosCardTest {
    @get:Rule val compose = createComposeRule()
    private val changes = mutableListOf<Boolean>()

    private fun show(state: AskPhotosUi) = compose.setContent { PcTheme { AskPhotosCard(state) { changes += it } } }

    @Test fun offByDefaultAndNamesTheEndpoint() {
        // The card is driven from the store's real default, not a literal.
        show(AskPhotosUi(enabled = com.rokid.context.orchestration.AskPhotoPolicy.enabled(androidx.test.core.app.ApplicationProvider.getApplicationContext()), endpointName = "Azure OpenAI", visionOk = true))
        compose.onNodeWithTag("ask_photos_switch").assertIsOff()
        compose.onNodeWithText("Send matching photos with questions").assertExists()
        compose.onNodeWithText("Up to 2 photos that match a question are sent to Azure OpenAI with it. Captions are always sent.", substring = true).assertExists()
        compose.onNodeWithTag("ask_photos_switch").performClick()
        assertEquals(listOf(true), changes)
    }

    @Test fun onShowsOnAndWarnsWhenTheAnswerModelCannotSeePhotos() {
        show(AskPhotosUi(enabled = true, endpointName = "Local", visionOk = false))
        compose.onNodeWithTag("ask_photos_switch").assertIsOn()
        compose.onNodeWithTag("ask_photos_no_vision").assertExists()
    }
}
