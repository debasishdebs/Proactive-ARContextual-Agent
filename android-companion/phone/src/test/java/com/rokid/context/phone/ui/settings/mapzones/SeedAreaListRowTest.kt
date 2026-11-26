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
}
