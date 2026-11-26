package com.rokid.context.phone.ui.settings.privacy.zones

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Offline: the notice shows and only drawing and the new-zone form are disabled; the zone list, the Preset and pause are unchanged. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ZonesOfflineTest {
    @get:Rule val compose = createComposeRule()

    private var online by mutableStateOf(true)
    private var state by mutableStateOf(ZonesUiState())
    private val drawCalls = mutableListOf<Boolean>()

    private fun show(seed: SeedPanel? = null) {
        val on = ZonesCallbacks({}, { d -> drawCalls += d; state = state.copy(drawing = d) }, { _, _ -> }, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
        compose.setContent { PcTheme { ZonesScreen(state, on, null, null, seed, networkOnline = online) } }
    }

    private fun scrollTo(tag: String) { compose.onNodeWithTag("zones_screen").performScrollToNode(hasTestTag(tag)) }

    @Test fun offlineShowsNoticeAndDisablesDraw() {
        online = false; show()
        compose.onNodeWithTag("zone_offline").assertExists()
        compose.onNodeWithTag("zone_draw").assertIsNotEnabled()
        assertEquals("You are offline. Go online to draw or change zones.", ZoneWords.OFFLINE_NOTICE)
        assertFalse(ZoneWords.OFFLINE_NOTICE.contains('—'))
    }

    @Test fun offlineShowsTheRedNoNetworkBannerAndOnlineDoesNot() {
        online = false; show()
        compose.onNodeWithTag("no_network_banner").assertExists()
        assertEquals("No network: the map background is unavailable. Seeding and pause zones need a connection.", ZoneWords.NO_NETWORK_BANNER)
        compose.runOnIdle { online = true }
        compose.onNodeWithTag("no_network_banner").assertDoesNotExist()
    }

    @Test fun offlineDisablesForm() {
        online = false; show()
        scrollTo("zone_save")
        for (t in listOf("zone_name", "zone_south", "zone_north", "zone_west", "zone_east", "zone_buffer", "zone_save")) compose.onNodeWithTag(t).assertIsNotEnabled()
        scrollTo("zone_preset")
        compose.onNodeWithTag("zone_preset").assertIsEnabled()
    }

    @Test fun goingOfflineCancelsDrawing() {
        show()
        compose.runOnIdle { state = state.copy(drawing = true) }
        compose.waitForIdle()
        compose.onNodeWithTag("zone_draw_hint").assertExists()
        compose.runOnIdle { online = false }
        compose.waitForIdle()
        assertEquals(listOf(false), drawCalls)
        compose.onNodeWithTag("zone_draw_hint").assertDoesNotExist()
    }

    @Test fun onlineUnchanged() {
        show()
        compose.onNodeWithTag("zone_offline").assertDoesNotExist()
        compose.onNodeWithTag("zone_draw").assertIsEnabled()
        scrollTo("zone_save")
        for (t in listOf("zone_name", "zone_south", "zone_buffer", "zone_save")) compose.onNodeWithTag(t).assertIsEnabled()
        assertTrue(drawCalls.isEmpty())
    }

    @Test fun seedModeOfflineSameRule() {
        online = false
        val panel = SeedPanel(SeedMapUiState(), true, {}, {}, {}, {}, {}, {}, {})
        show(panel)
        compose.onNodeWithTag("zone_offline").assertExists()
        compose.onNodeWithTag("zone_draw").assertIsNotEnabled()
        compose.runOnIdle { online = true }
        compose.onNodeWithTag("zone_offline").assertDoesNotExist()
        compose.onNodeWithTag("zone_draw").assertIsEnabled()
    }
}
