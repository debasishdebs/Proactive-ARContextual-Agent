package com.rokid.context.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.components.BarChart
import com.rokid.context.core.ui.components.BarChartMath
import com.rokid.context.core.ui.components.Confidence
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.core.ui.components.FrameDecode
import com.rokid.context.core.ui.components.FrameThumb
import com.rokid.context.core.ui.components.StatusStrip
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShellComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun confidenceIsClampedAndNaNShowsDash() {
        assertEquals("92%", Confidence.label(0.92))
        assertEquals("100%", Confidence.label(1.7))
        assertEquals("0%", Confidence.label(-0.2))
        assertEquals("—", Confidence.label(Double.NaN))
        assertEquals(PcColors.ChipMint, Confidence.colors(0.8))
        assertEquals(PcColors.ChipAmber, Confidence.colors(0.5))
        assertEquals(PcColors.ChipCoral, Confidence.colors(0.49))
        assertEquals(PcColors.ChipSlate, Confidence.colors(Double.NaN))
        compose.setContent { PcTheme { ConfidenceChip(0.71) } }
        compose.onNodeWithText("71%").assertIsDisplayed()
    }

    @Test fun statusStripShowsBatteryAndReportsPauseTap() {
        var taps = 0
        compose.setContent { PcTheme { StatusStrip("Capturing · next 4:12", live = true, batteryPct = 82, onTogglePause = { taps++ }) } }
        compose.onNodeWithText("Capturing · next 4:12").assertIsDisplayed()
        compose.onNodeWithText("82%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause capture").performClick()
        assertEquals(1, taps)
    }

    @Test fun pausedStripOffersResumeAndNoCallbackMeansNoButton() {
        compose.setContent {
            PcTheme {
                Column {
                    StatusStrip("Paused", live = false, paused = true, onTogglePause = {})
                    StatusStrip("Glasses offline", live = false)
                }
            }
        }
        compose.onNodeWithContentDescription("Resume capture").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Pause capture").assertCountEquals(0)
    }

    @Test fun allZeroValuesGiveZeroHeights() {
        assertEquals(listOf(0f, 0f, 0f), BarChartMath.fractions(listOf(0, 0, 0)))
        assertEquals(emptyList<Float>(), BarChartMath.fractions(emptyList()))
        assertEquals(listOf(0.5f, 1f, 0f), BarChartMath.fractions(listOf(2, 4, -1)))
        assertNull(BarChartMath.indexAt(5f, 100f, 0))
        assertNull(BarChartMath.indexAt(-1f, 70f, 7))
        assertEquals(0, BarChartMath.indexAt(0f, 70f, 7))
        assertEquals(6, BarChartMath.indexAt(70f, 70f, 7))
    }

    @Test fun barChartDescribesValuesAndReportsTheTappedBar() {
        var tapped: Int? = null
        compose.setContent { PcTheme { BarChart(listOf(3, 0, 5), listOf("M", "T", "W"), onBarClick = { tapped = it }) } }
        compose.onNodeWithContentDescription("Bar chart: M 3, T 0, W 5").performTouchInput { click(Offset(width - 2f, height / 2f)) }
        assertEquals(2, tapped)
    }

    @Test fun sampleSizeKeepsTheShortSideAboveTarget() {
        assertEquals(16, FrameDecode.sampleSize(1440, 1080, 56))
        assertEquals(1, FrameDecode.sampleSize(100, 100, 200))
        assertEquals(1, FrameDecode.sampleSize(0, 0, 56))
        assertNull(FrameDecode.decode(null, 56))
        assertNull(FrameDecode.decode("/definitely/missing.jpg", 56))
    }

    @Test fun missingFileShowsPlaceholder() {
        compose.setContent { PcTheme { FrameThumb("/definitely/missing.jpg") } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("No photo").fetchSemanticsNodes().isNotEmpty() }
    }
}
