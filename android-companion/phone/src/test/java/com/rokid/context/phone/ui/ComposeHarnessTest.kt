package com.rokid.context.phone.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ComposeHarnessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneModuleRendersCoreUiUnderRobolectric() {
        compose.setContent { PcTheme { SectionLabel("Today") } }
        compose.onNodeWithText("TODAY").assertIsDisplayed()
    }
}
