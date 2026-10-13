package com.rokid.context.core.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Verbosity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HudPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mediumShowsTheGlancePageWithTheCountdown() {
        compose.setContent { HudPreview(HudSettings(verbosity = Verbosity.MEDIUM), nowMs = 0) }
        compose.onNodeWithTag("hud_preview").assertExists()
        compose.onNodeWithText("NEXT").assertExists()
        compose.onNodeWithText("4:12").assertExists()
    }

    @Test fun silentShowsOnlyTheRecDot() {
        compose.setContent { HudPreview(HudSettings(verbosity = Verbosity.SILENT)) }
        compose.onNodeWithText("NEXT").assertDoesNotExist()
    }

    @Test fun maxCanPreviewTheStatusPage() {
        compose.setContent { HudPreview(HudSettings(verbosity = Verbosity.MAX), page = HudPage.STATUS) }
        compose.onNodeWithText("STATUS").assertExists()
    }
}
