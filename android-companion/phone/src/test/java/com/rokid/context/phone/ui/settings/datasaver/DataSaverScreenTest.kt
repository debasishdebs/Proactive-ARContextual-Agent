package com.rokid.context.phone.ui.settings.datasaver

import android.app.Application
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.net.DataMode
import com.rokid.context.core.net.DataSaverSettings
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.SkippedLedger
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DataSaverScreenTest {
    @get:Rule val compose = createComposeRule()

    // ---- the model (pure) ----

    @Test fun theDefaultSettingsExplainWifiOnly() {
        val s = DataSaverSettings()
        assertEquals("Photos on Wi-Fi only", DataSaverModel.rowSubtitle(s))
        assertEquals("Photos and other big uploads wait for Wi-Fi.", DataSaverModel.modeNote(s.mode))
        assertEquals(3, DataSaverModel.MODE_LABELS.size); assertEquals(DataMode.entries.size, DataSaverModel.MODE_LABELS.size)
    }

    @Test fun theNowLineNamesEveryNetworkClassAndUnknownIsSaidToBeRoaming() {
        assertEquals("Now: Wi-Fi", DataSaverModel.nowLine(NetworkCost.FREE, false))
        assertEquals("Now: Home mobile data", DataSaverModel.nowLine(NetworkCost.HOME_MOBILE, false))
        assertTrue(DataSaverModel.nowLine(NetworkCost.ROAMING, false).contains("reports roaming"))
        assertTrue(DataSaverModel.nowLine(NetworkCost.ROAMING, true).contains("I'm abroad"))
        assertEquals("Now: Unknown network, treated as roaming", DataSaverModel.nowLine(NetworkCost.UNKNOWN, false))
        assertEquals("Now: Offline", DataSaverModel.nowLine(NetworkCost.OFFLINE, false))
    }

    @Test fun nowExplainSaysWhetherPhotosGoOnThisConnection() {
        val wifiOnly = DataSaverSettings()
        assertEquals("Photos upload normally.", DataSaverModel.nowExplain(NetworkCost.FREE, wifiOnly))
        assertEquals("Photos wait for Wi-Fi.", DataSaverModel.nowExplain(NetworkCost.HOME_MOBILE, wifiOnly))
        assertEquals("Photos wait for Wi-Fi.", DataSaverModel.nowExplain(NetworkCost.UNKNOWN, wifiOnly))
        assertEquals("Photos upload on this connection.", DataSaverModel.nowExplain(NetworkCost.HOME_MOBILE, wifiOnly.copy(mode = DataMode.WIFI_AND_HOME_MOBILE)))
        assertEquals("Photos wait for Wi-Fi.", DataSaverModel.nowExplain(NetworkCost.ROAMING, wifiOnly.copy(mode = DataMode.ANYWHERE, abroad = true)))
    }

    @Test fun theRowSubtitleFollowsTheSettings() {
        assertEquals("Abroad: photos on Wi-Fi only", DataSaverModel.rowSubtitle(DataSaverSettings(DataMode.ANYWHERE, abroad = true)))
        assertEquals("Photos on Wi-Fi and home mobile data", DataSaverModel.rowSubtitle(DataSaverSettings(DataMode.WIFI_AND_HOME_MOBILE)))
        assertEquals("Photos on any connection", DataSaverModel.rowSubtitle(DataSaverSettings(DataMode.ANYWHERE)))
    }

    @Test fun anywhereAdmitsThereIsNoLimitYetAndTodayLinesAreCountsOnly() {
        assertTrue(DataSaverModel.modeNote(DataMode.ANYWHERE).contains("no daily limit yet", ignoreCase = true))
        val lines = DataSaverModel.todayLines(SkippedLedger.Today(4, 2, 1))
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith(": 4") && lines[1].endsWith(": 2") && lines[2].endsWith(": 1"))
        assertFalse((lines + DataSaverModel.COUNTS_ONLY).any { it.contains("http") || it.contains("host") })
    }

    // ---- the screen ----

    @Test fun theScreenShowsStatusTogglesAndReportsChanges() {
        var abroad: Boolean? = null; var small: Boolean? = null; var mode: DataMode? = null
        compose.setContent {
            PcTheme { DataSaverScreen(DataSaverUiState(DataSaverSettings(), NetworkCost.UNKNOWN, SkippedLedger.Today(3, 0, 0)), {}, { mode = it }, { abroad = it }, { small = it }) }
        }
        compose.onNodeWithTag("data_saver_now").assertTextContains("Unknown network, treated as roaming", substring = true)
        compose.onNodeWithTag("data_saver_now_explain").assertTextContains("Photos wait for Wi-Fi.")
        compose.onNodeWithTag("data_saver_mode_note").assertTextContains("wait for Wi-Fi", substring = true)
        compose.onNodeWithTag("data_saver_abroad").assertIsOff()
        compose.onNodeWithTag("data_saver_small_roaming").assertIsOn()
        compose.onNodeWithTag("data_saver_status_0").assertTextContains("waiting for Wi-Fi: 3", substring = true)
        compose.onNodeWithTag("data_saver_status").assertTextContains("Counts only", substring = true)
        compose.onNodeWithTag("data_saver_abroad").performClick()
        compose.onNodeWithTag("data_saver_small_roaming").performClick()
        assertEquals(true, abroad); assertEquals(false, small); assertEquals(null, mode)
    }

    @Test fun theAbroadSwitchShowsOnWhenSet() {
        compose.setContent { PcTheme { DataSaverScreen(DataSaverUiState(DataSaverSettings(abroad = true), NetworkCost.ROAMING), {}, {}, {}, {}) } }
        compose.onNodeWithTag("data_saver_abroad").assertIsOn()
        compose.onNodeWithTag("data_saver_now").assertTextContains("I'm abroad", substring = true)
    }
}
