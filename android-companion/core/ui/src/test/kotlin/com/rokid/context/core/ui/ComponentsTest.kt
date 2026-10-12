package com.rokid.context.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.components.Banner
import com.rokid.context.core.ui.components.BannerTone
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.PillNav
import com.rokid.context.core.ui.components.PillNavItem
import com.rokid.context.core.ui.components.SegmentedSwitch
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ComponentsTest {
    @get:Rule val compose = createComposeRule()
    private val tabs = listOf(
        PillNavItem("home", "Home", Icons.Outlined.Home),
        PillNavItem("activity", "Activity", Icons.Outlined.Timeline),
        PillNavItem("settings", "Settings", Icons.Outlined.Settings),
    )

    @Test fun pillNavReportsTheTappedRouteAndMarksSelection() {
        var picked: String? = null
        compose.setContent { PcTheme { PillNav(tabs, selectedRoute = "home", onSelect = { picked = it }) } }
        compose.onNodeWithText("Home").assertIsSelected()
        compose.onNodeWithText("Activity").assertIsNotSelected()
        compose.onNodeWithText("Activity").performClick()
        assertEquals("activity", picked)
    }

    @Test fun segmentedSwitchReportsIndex() {
        var picked = -1
        compose.setContent { PcTheme { SegmentedSwitch(listOf("Today", "Week", "Month"), selectedIndex = 0, onSelect = { picked = it }) } }
        compose.onNodeWithText("Today").assertIsSelected()
        compose.onNodeWithText("Week").performClick()
        assertEquals(1, picked)
    }

    @Test fun disabledControlsIgnoreTaps() {
        var rowTaps = 0; var buttonTaps = 0
        compose.setContent {
            PcTheme {
                ChipRow(Icons.Outlined.Settings, PcColors.ChipSlate, "Diagnostics", onClick = { rowTaps++ }, enabled = false)
                GradientButton("Save", onClick = { buttonTaps++ }, enabled = false)
            }
        }
        compose.onNodeWithText("Diagnostics").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(0, rowTaps); assertEquals(0, buttonTaps)
    }

    @Test fun bannerActionFires() {
        var fixed = false
        compose.setContent { PcTheme { Banner(BannerTone.CORAL, "AI key rejected", actionLabel = "Fix now", onAction = { fixed = true }) } }
        compose.onNodeWithText("Fix now").performClick()
        assertEquals(true, fixed)
    }

    @Test fun tokensMatchSpec() {
        assertEquals(0xFF14C3A2.toInt(), PcColors.Mint.toArgb())
        assertEquals(0xFF3D8BFF.toInt(), PcColors.Sky.toArgb())
        assertEquals(0xFF15181D.toInt(), PcColors.Ink.toArgb())
    }
}
