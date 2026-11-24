package com.rokid.context.phone.ui.settings.datasaver

import android.app.Application
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.net.DailyLimit
import com.rokid.context.core.net.DataMode
import com.rokid.context.core.net.MobileDataLedger
import com.rokid.context.core.net.DataSaverSettings
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.SkippedLedger
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    @Test fun theDefaultSettingsExplainWifiAndHomeMobileAndWifiOnlyStaysSelectable() {
        val s = DataSaverSettings()
        assertEquals(DataMode.WIFI_AND_HOME_MOBILE, s.mode)
        assertEquals("Photos on Wi-Fi and home mobile data", DataSaverModel.rowSubtitle(s))
        assertEquals("Photos on Wi-Fi only", DataSaverModel.rowSubtitle(s.copy(mode = DataMode.WIFI_ONLY)))
        assertEquals("Photos and other big uploads wait for Wi-Fi.", DataSaverModel.modeNote(DataMode.WIFI_ONLY))
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

    @Test fun androidDataSaverAndTheCountryMismatchHaveTheirOwnWords() {
        assertEquals(DataSaverModel.ANDROID_SAVER_LINE, DataSaverModel.nowLine(NetworkCost.RESTRICTED, false))
        assertTrue(DataSaverModel.ANDROID_SAVER_LINE.contains("Android Data Saver is on for this app"))
        assertFalse(DataSaverModel.nowLine(NetworkCost.RESTRICTED, false).contains("roaming", ignoreCase = true))
        assertTrue(DataSaverModel.nowLine(NetworkCost.ROAMING, false, why = com.rokid.context.core.net.NetworkCosts.WHY_COUNTRY).contains("SIM country differs"))
        assertFalse(DataSaverModel.nowLine(NetworkCost.ROAMING, false, why = com.rokid.context.core.net.NetworkCosts.WHY_COUNTRY).contains("reports roaming"))
        assertEquals("Photos wait for Wi-Fi.", DataSaverModel.nowExplain(NetworkCost.RESTRICTED, DataSaverSettings(DataMode.ANYWHERE)))
    }

    @Test fun nowExplainSaysWhetherPhotosGoOnThisConnection() {
        val wifiOnly = DataSaverSettings(DataMode.WIFI_ONLY)
        assertEquals("Photos upload normally.", DataSaverModel.nowExplain(NetworkCost.FREE, wifiOnly))
        assertEquals("Photos wait for Wi-Fi.", DataSaverModel.nowExplain(NetworkCost.HOME_MOBILE, wifiOnly))
        assertEquals("Photos wait for Wi-Fi.", DataSaverModel.nowExplain(NetworkCost.UNKNOWN, wifiOnly))
        assertEquals("Photos upload on this connection.", DataSaverModel.nowExplain(NetworkCost.HOME_MOBILE, wifiOnly.copy(mode = DataMode.WIFI_AND_HOME_MOBILE)))
        // slice 2: abroad + Anywhere uploads under the daily cap; once the cap is used up the status line says so
        assertEquals("Photos upload on this connection.", DataSaverModel.nowExplain(NetworkCost.ROAMING, wifiOnly.copy(mode = DataMode.ANYWHERE, abroad = true)))
        assertEquals(DataSaverModel.LIMIT_REACHED_LINE, DataSaverModel.nowExplain(NetworkCost.ROAMING, wifiOnly.copy(mode = DataMode.ANYWHERE, abroad = true), limitReached = true))
        assertTrue(DataSaverModel.LIMIT_REACHED_LINE.startsWith("Daily limit reached"))
        assertEquals("on Wi-Fi the limit is never shown", "Photos upload normally.", DataSaverModel.nowExplain(NetworkCost.FREE, wifiOnly, limitReached = true))
    }

    @Test fun theRowSubtitleFollowsTheSettings() {
        assertEquals("Abroad: photos up to the daily limit", DataSaverModel.rowSubtitle(DataSaverSettings(DataMode.ANYWHERE, abroad = true)))
        assertEquals("Abroad: photos on Wi-Fi only", DataSaverModel.rowSubtitle(DataSaverSettings(DataMode.WIFI_AND_HOME_MOBILE, abroad = true)))
        assertEquals("Photos on Wi-Fi and home mobile data", DataSaverModel.rowSubtitle(DataSaverSettings(DataMode.WIFI_AND_HOME_MOBILE)))
        assertEquals("Photos on any connection", DataSaverModel.rowSubtitle(DataSaverSettings(DataMode.ANYWHERE)))
    }

    @Test fun anywhereExplainsTheAbroadCapAndTodayLinesAreCountsOnly() {
        assertTrue(DataSaverModel.modeNote(DataMode.ANYWHERE).contains("10 MB or 30 photos"))
        assertTrue(DataSaverModel.modeNote(DataMode.ANYWHERE).contains("no limit unless you set one"))
        val lines = DataSaverModel.todayLines(SkippedLedger.Today(4, 2, 1), waiting = 7, waitingBytes = 5_242_880L)
        assertEquals(4, lines.size)
        assertEquals("7 photos waiting for Wi-Fi, 5.0 MB", lines[0])
        assertEquals("1 photo waiting for Wi-Fi", DataSaverModel.waitingLine(1, 0))
        assertTrue(lines[1].contains(": 4 ") && lines[2].endsWith(": 2") && lines[3].endsWith(": 1"))
        assertFalse((lines + DataSaverModel.COUNTS_ONLY).any { it.contains("http") || it.contains("host") })
    }

    // ---- the screen ----

    @Test fun theScreenShowsStatusTogglesAndReportsChanges() {
        var abroad: Boolean? = null; var small: Boolean? = null; var mode: DataMode? = null
        compose.setContent {
            PcTheme { DataSaverScreen(DataSaverUiState(DataSaverSettings(), NetworkCost.UNKNOWN, SkippedLedger.Today(3, 0, 0), waiting = 9), {}, { mode = it }, { abroad = it }, { small = it }) }
        }
        compose.onNodeWithTag("data_saver_now").assertTextContains("Unknown network, treated as roaming", substring = true)
        compose.onNodeWithTag("data_saver_now_explain").assertTextContains("Photos wait for Wi-Fi.")
        compose.onNodeWithTag("data_saver_mode_note").assertTextContains("mobile data at home", substring = true)
        compose.onNodeWithTag("data_saver_abroad").assertIsOff()
        compose.onNodeWithTag("data_saver_small_roaming").assertIsOn()
        compose.onNodeWithTag("data_saver_abroad").performClick()
        compose.onNodeWithTag("data_saver_small_roaming").performClick()
        compose.onNodeWithTag("data_saver_settings").performScrollToNode(androidx.compose.ui.test.hasTestTag("data_saver_status"))
        compose.onNodeWithTag("data_saver_status_0").assertTextContains("waiting for Wi-Fi now: 9", substring = true)
        compose.onNodeWithTag("data_saver_status").assertTextContains("Counts only", substring = true)
        assertEquals(true, abroad); assertEquals(false, small); assertEquals(null, mode)
    }

    @Test fun sentTodayAndLimitLinesAreCountsAndSizesOnly() {
        assertEquals("Sent today on mobile data: 0 KB / 0 photos", DataSaverModel.sentLine(MobileDataLedger.Today(0, 0, 0, 0)))
        assertEquals("Sent today on mobile data: 1.2 MB / 7 photos", DataSaverModel.sentLine(MobileDataLedger.Today(1_000_000, 200_000, 60_000, 7)))
        assertEquals("Sent today on mobile data: 412 KB / 3 photos", DataSaverModel.sentLine(MobileDataLedger.Today(400_000, 0, 22_000, 3)))
        assertNull(DataSaverModel.limitLine(null))
        assertEquals("Limit today on this connection: 10.0 MB and 30 photos", DataSaverModel.limitLine(DailyLimit(10L * 1024 * 1024, 30)))
        assertEquals(listOf("5 MB", "10 MB", "25 MB", "50 MB"), DataSaverModel.MB_LABELS); assertEquals(listOf("0", "10", "30", "100"), DataSaverModel.PHOTO_LABELS)
        assertFalse(DataSaverModel.LIMIT_NOTE.contains("http"))
        assertTrue(DataSaverModel.LIMIT_NOTE.startsWith("Off by default"))
    }

    @Test fun theLimitAndHotspotRowsReportChangesAndTheNumbersOnlyShowWhileTheLimitIsOn() {
        var on: Boolean? = null; var mb: Int? = null; var photos: Int? = null; var metered: Boolean? = null
        var state by androidx.compose.runtime.mutableStateOf(DataSaverUiState(DataSaverSettings(), NetworkCost.HOME_MOBILE))
        compose.setContent {
            PcTheme { DataSaverScreen(state, {}, {}, {}, {}, { on = it }, { mb = it }, { photos = it }, { metered = it }) }
        }
        compose.onNodeWithTag("data_saver_settings").performScrollToNode(androidx.compose.ui.test.hasTestTag("data_saver_limit_on"))
        compose.onNodeWithTag("data_saver_limit_on").assertIsOff()
        compose.onNodeWithTag("data_saver_cap_bytes").assertDoesNotExist()
        compose.onNodeWithTag("data_saver_limit_on").performClick()
        assertEquals(true, on)
        state = state.copy(settings = state.settings.copy(dailyLimitOn = true, limitMb = 25, limitPhotos = 30), limit = DailyLimit(25L * 1024 * 1024, 30))
        compose.onNodeWithTag("data_saver_limit_on").assertIsOn()
        compose.onNodeWithTag("data_saver_cap_bytes").assertExists(); compose.onNodeWithTag("data_saver_cap_photos").assertExists()
        compose.onNodeWithTag("data_saver_settings").performScrollToNode(androidx.compose.ui.test.hasTestTag("data_saver_wifi_metered"))
        compose.onNodeWithTag("data_saver_wifi_metered").assertIsOff().performClick()
        assertEquals(true, metered)
    }

    @Test fun theTodayCardShowsSentOnMobileDataAndBlockedTodayAsSeparateLines() {
        compose.setContent {
            PcTheme { DataSaverScreen(DataSaverUiState(DataSaverSettings(), NetworkCost.ROAMING, SkippedLedger.Today(2, 0, 0), MobileDataLedger.Today(2_000_000, 0, 10_000, 5), DailyLimit(10L * 1024 * 1024, 30), limitReached = true, waiting = 4), {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("data_saver_now_explain").assertTextContains("Daily limit reached", substring = true)
        compose.onNodeWithTag("data_saver_settings").performScrollToNode(androidx.compose.ui.test.hasTestTag("data_saver_status"))
        compose.onNodeWithTag("data_saver_sent").assertTextContains("Sent today on mobile data: 1.9 MB / 5 photos", substring = true)
        compose.onNodeWithTag("data_saver_limit_line").assertTextContains("10.0 MB and 30 photos", substring = true)
        compose.onNodeWithTag("data_saver_today_title").assertTextContains("Blocked today", substring = true)
        compose.onNodeWithTag("data_saver_status_0").assertTextContains("waiting for Wi-Fi now: 4", substring = true)
    }

    @Test fun aWifiStuckWithoutValidationSaysSoAndOnlyThen() {
        assertEquals(DataSaverModel.STUCK_LINE, DataSaverModel.nowLine(NetworkCost.UNKNOWN, false, stuck = true))
        assertEquals("Now: Unknown network, treated as roaming", DataSaverModel.nowLine(NetworkCost.UNKNOWN, false, stuck = false))
        assertEquals("Now: Wi-Fi", DataSaverModel.nowLine(NetworkCost.FREE, false, stuck = true))
    }

    @Test fun aSeedRefusedForWifiSaysSo() {
        assertEquals("Seeding needs Wi-Fi (data saver). Connect to Wi-Fi and try again.", com.rokid.context.phone.ui.settings.place.seedFailureText(com.rokid.context.phone.ui.settings.place.SeedNeedsWifiException()))
        assertTrue(com.rokid.context.phone.ui.settings.place.seedFailureText(java.io.IOException("x")).startsWith("Could not fetch places"))
    }

    @Test fun waitingPhotosAreNeverThinnedAndTheScreenSaysSo() {
        assertTrue(DataSaverModel.CAP_NOTE.contains("never thinned or deleted"))
        compose.setContent { PcTheme { DataSaverScreen(DataSaverUiState(), {}, {}, {}, {}) } }
        compose.onNodeWithTag("data_saver_settings").performScrollToNode(androidx.compose.ui.test.hasTestTag("data_saver_status"))
        compose.onNodeWithTag("data_saver_cap_note").assertTextContains("never thinned or deleted", substring = true)
    }

    @Test fun theAbroadSwitchShowsOnWhenSet() {
        compose.setContent { PcTheme { DataSaverScreen(DataSaverUiState(DataSaverSettings(abroad = true), NetworkCost.ROAMING), {}, {}, {}, {}) } }
        compose.onNodeWithTag("data_saver_abroad").assertIsOn()
        compose.onNodeWithTag("data_saver_now").assertTextContains("I'm abroad", substring = true)
    }
}
