package com.rokid.context.phone.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings > Diagnostics > Developer: the "Send test card to glasses" button (a button, not a flag). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DevTestCardRowTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    private fun show(dev: Boolean, linked: Boolean, onSend: () -> Unit = {}, result: String? = null) {
        compose.setContent { PcTheme { DiagnosticsScreen(DiagnosticsUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, developerMode = dev, glassesConnected = linked, onSendTestCard = onSend, testCardResult = result) } }
    }

    @Test fun hiddenWhenDeveloperModeIsOff() { show(dev = false, linked = true); compose.onNodeWithTag("dev_test_card_button").assertDoesNotExist() }

    @Test fun enabledAndSendsOneTapWhenConnected() {
        var taps = 0; show(dev = true, linked = true, onSend = { taps++ })
        compose.onNodeWithTag("dev_test_card_button").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, taps)
    }

    @Test fun disabledWithAClearNoteWhenNotConnected() {
        var taps = 0; show(dev = true, linked = false, onSend = { taps++ })
        compose.onNodeWithTag("dev_test_card_button").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("dev_test_card_note").assertExists()
        assertEquals(0, taps)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() = this.assertDoesNotExist()
}
