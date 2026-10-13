package com.rokid.context.core.ui.components

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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VersionStripTest {
    @get:Rule val compose = createComposeRule()

    @Test fun labelsAndSelection() {
        var picked = -1
        compose.setContent { PcTheme { VersionStrip(listOf(VersionChip(3, active = true, testedOk = true), VersionChip(2, false, false), VersionChip(1, false, true)), selected = 3, onSelect = { picked = it }) } }
        compose.onNodeWithText("v3 · active ✓").assertExists()
        compose.onNodeWithText("v1 ✓").assertExists()
        compose.onNodeWithTag("version_2").performClick()
        assertEquals(2, picked)
    }
}
