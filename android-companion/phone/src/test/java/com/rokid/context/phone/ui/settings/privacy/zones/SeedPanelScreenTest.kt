package com.rokid.context.phone.ui.settings.privacy.zones

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.place.AREA_DONE
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SeedPanelScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(ui: SeedMapUiState, draft: ZoneDraft, removed: MutableList<String>, mode: Boolean = false) {
        val on = ZonesCallbacks({}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
        val panel = SeedPanel(ui, mode, {}, {}, {}, { removed += it }, {}, {}, {})
        compose.setContent { PcTheme { ZonesScreen(ZonesUiState(draft = draft), on, null, null, panel) } }
    }

    private fun scrollTo(tag: String) { compose.onNodeWithTag("zones_screen").performScrollToNode(hasTestTag(tag)) }

    @Test fun drawnBoxShowsTheEstimateAndSeededAreasHaveARemove() {
        val removed = mutableListOf<String>()
        val areas = SeedOverlays.of(listOf(SeedAreaInfo("seed:1", 27.0, 88.0, 27.1, 88.1, AREA_DONE)))
        show(SeedMapUiState(areas = areas), ZoneDraft(south = "17.2443", west = "78.6708", north = "17.2643", east = "78.6908"), removed)
        scrollTo("seed_estimate")
        compose.onNodeWithTag("seed_estimate").assertExists()
        scrollTo("seed_remove_seed:1")
        compose.onNodeWithTag("seed_remove_seed:1").performClick()
        assertEquals(listOf("seed:1"), removed)
    }

    @Test fun seedModeHidesTheNewZoneForm() {
        show(SeedMapUiState(), ZoneDraft(), mutableListOf(), mode = true)
        scrollTo("seed_box")
        compose.onNodeWithTag("seed_box").assertExists()
        compose.onNodeWithTag("zone_name").assertDoesNotExist()
    }
}
