package com.rokid.context.phone.ui.settings.mapzones

import android.app.Application
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.place.AREA_DONE
import com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaInfo
import com.rokid.context.phone.ui.settings.privacy.zones.SeedMapUiState
import com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaList
import com.rokid.context.phone.ui.settings.privacy.zones.SeedOverlays
import com.rokid.context.phone.ui.settings.privacy.zones.SeedPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S4 seeded-areas list row: tap focuses the map on the area, Rename, Seed again, Remove. Driven through recorded callbacks. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SeedAreaListRowTest {
    @get:Rule val compose = createComposeRule()
    private val info = listOf(SeedAreaInfo("seed:1", 27.0, 88.0, 27.1, 88.1, AREA_DONE))
    private val log = mutableListOf<String>()
    private val view = MapViewState()

    private fun show() {
        val ui = SeedMapUiState(areas = SeedOverlays.of(info), rows = SeedAreaList.rows(info))
        val panel = SeedPanel(ui, false, { b -> log += "seed:" + (b != null) }, {}, {}, { log += "remove:$it" }, {}, {}, {}, rename = { id, n -> log += "rename:$id:$n" })
        compose.setContent { PcTheme { SeedAreaListRow(view, panel, ui.areas[0]) {} } }
    }

    @Test fun tapFocusesTheMapOnTheAreaAndSelectsIt() {
        val sel = mutableListOf<Any?>()
        view.size = Size(400f, 400f); assertNull(view.vp)
        val ui = SeedMapUiState(areas = SeedOverlays.of(info), rows = SeedAreaList.rows(info))
        compose.setContent { PcTheme { SeedAreaListRow(view, SeedPanel(ui, false, {}, {}, {}, {}, {}, {}, {}), ui.areas[0]) { sel += it } } }
        compose.onNodeWithTag("seed_area_seed:1").performClick()
        assertNotNull(view.vp)
        assertEquals(Selection(SelectionKind.SEED, "seed:1"), sel.single())
    }

    @Test fun seedAgainSeedsTheAreasOwnBox() {
        show(); compose.onNodeWithTag("seed_again_seed:1").assertIsEnabled().performClick()
        assertEquals(listOf("seed:true"), log)
    }

    @Test fun renameDialogSavesTheTypedName() {
        show(); compose.onNodeWithTag("seed_rename_seed:1").performClick()
        compose.onNodeWithTag("seed_rename_field").performTextInput("Sikkim")
        compose.onNodeWithTag("seed_rename_save").performClick()
        assertEquals(listOf("rename:seed:1:Sikkim"), log)
    }

    private val failedInfo = listOf(SeedAreaInfo("seed:1", 27.0, 88.0, 27.1, 88.1, com.rokid.context.memory.place.AREA_PARTIAL, failedCells = 3))

    @Test fun aFailedAreaShowsFAILEDWithItsCountAndARetryButtonOnTheRowAndInTheProgressArea() {
        val ui = SeedMapUiState(areas = SeedOverlays.of(failedInfo), rows = SeedAreaList.rows(failedInfo), progress = SeedAreaList.progress(failedInfo))
        assertTrue(ui.areas[0].label, ui.areas[0].label.contains("FAILED: 3 cells")); assertTrue(ui.rows[0].text, ui.rows[0].text.contains("FAILED: 3 cells did not seed: tap Retry failed cells"))
        val panel = SeedPanel(ui, false, {}, {}, {}, {}, {}, {}, {}, retryFailed = { log += "retry:$it" })
        compose.setContent { PcTheme { androidx.compose.foundation.layout.Column { SeedAreaListRow(view, panel, ui.areas[0]) {}; com.rokid.context.phone.ui.settings.privacy.zones.SeedProgressRows(panel) } } }
        compose.onNodeWithTag("seed_retry_seed:1").assertIsEnabled().performClick()
        compose.onNodeWithTag("seed_retry_0").assertIsEnabled().performClick()
        compose.onNodeWithTag("seed_cancel_0").assertDoesNotExist()
        assertEquals(listOf("retry:seed:1", "retry:seed:1"), log)
    }

    @Test fun anAreaStillMarkedSeedingWithNoLiveSeedNeverReadsSeeding() {
        val stuck = listOf(SeedAreaInfo("seed:1", 27.0, 88.0, 27.1, 88.1, com.rokid.context.memory.place.AREA_SEEDING))
        assertEquals("Seeded area 1 (not finished, seed again)", SeedOverlays.of(stuck)[0].label)
    }
}
