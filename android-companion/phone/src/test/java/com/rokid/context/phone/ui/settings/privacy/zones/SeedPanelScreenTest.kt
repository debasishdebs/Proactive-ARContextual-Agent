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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.place.AREA_DONE
import com.rokid.context.memory.place.GeoBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The seed panel's controls, driven through recorded callbacks that also move the state the way the view model does (so a dialog opens and closes for real). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SeedPanelScreenTest {
    @get:Rule val compose = createComposeRule()

    private val smallDraft = ZoneDraft(south = "17.2443", west = "78.6708", north = "17.2643", east = "78.6908")
    private val hugeDraft = ZoneDraft(south = "-80", west = "-170", north = "80", east = "170") // far above the cell cap
    private val area = SeedOverlays.of(listOf(SeedAreaInfo("seed:1", 27.0, 88.0, 27.1, 88.1, AREA_DONE)))

    private class Calls {
        val seedBox = mutableListOf<GeoBox?>()
        val log = mutableListOf<String>()
    }

    private var ui by mutableStateOf(SeedMapUiState())

    private fun show(draft: ZoneDraft, calls: Calls, mode: Boolean = false) {
        val on = ZonesCallbacks({}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
        compose.setContent {
            PcTheme {
                val panel = SeedPanel(
                    ui, mode,
                    seedBox = { calls.seedBox += it; calls.log += "seed" },
                    confirmSeed = { calls.log += "confirmSeed"; ui = ui.copy(confirmCells = null, seeding = true) },
                    cancelConfirm = { calls.log += "cancelConfirm"; ui = ui.copy(confirmCells = null) },
                    askRemove = { calls.log += "askRemove:$it"; ui = ui.copy(pendingRemoveId = it) },
                    confirmRemove = { calls.log += "confirmRemove"; ui = ui.copy(pendingRemoveId = null, areas = emptyList()) },
                    cancelRemove = { calls.log += "cancelRemove"; ui = ui.copy(pendingRemoveId = null) },
                    dismiss = { calls.log += "dismiss"; ui = ui.copy(message = null) },
                )
                ZonesScreen(ZonesUiState(draft = draft), on, null, null, panel)
            }
        }
    }

    private fun scrollTo(tag: String) { compose.onNodeWithTag("zones_screen").performScrollToNode(hasTestTag(tag)) }

    @Test fun seedButtonPassesTheDrawnBoxToTheCallback() {
        val c = Calls(); ui = SeedMapUiState(); show(smallDraft, c)
        scrollTo("seed_box"); compose.onNodeWithTag("seed_box").assertIsEnabled().performClick()
        assertEquals(listOf<GeoBox?>(GeoBox(17.2443, 78.6708, 17.2643, 78.6908)), c.seedBox)
    }

    private fun assertSeedButtonIsDisabled(draft: ZoneDraft, state: SeedMapUiState) {
        val c = Calls(); ui = state; show(draft, c)
        scrollTo("seed_box"); compose.onNodeWithTag("seed_box").assertIsNotEnabled().performClick()
        assertTrue(c.seedBox.isEmpty())
    }

    @Test fun seedButtonIsDisabledAboveTheCellCap() = assertSeedButtonIsDisabled(hugeDraft, SeedMapUiState())
    @Test fun seedButtonIsDisabledWithNoBoxDrawn() = assertSeedButtonIsDisabled(ZoneDraft(), SeedMapUiState())
    @Test fun seedButtonIsDisabledWhileASeedRuns() = assertSeedButtonIsDisabled(smallDraft, SeedMapUiState(seeding = true))

    @Test fun drawnBoxShowsTheEstimate() {
        ui = SeedMapUiState(); show(smallDraft, Calls())
        scrollTo("seed_estimate"); compose.onNodeWithTag("seed_estimate").assertExists()
    }

    @Test fun confirmDialogYesStartsTheSeedAndCancelDoesNot() {
        val c = Calls(); ui = SeedMapUiState(confirmCells = 150); show(smallDraft, c)
        compose.onNodeWithTag("seed_confirm_dialog").assertExists()
        compose.onNodeWithTag("seed_confirm_yes").performClick()
        assertEquals(listOf("confirmSeed"), c.log)
        compose.waitForIdle(); compose.onNodeWithTag("seed_confirm_dialog").assertDoesNotExist()
        // a second time: Cancel only closes it
        ui = SeedMapUiState(confirmCells = 150); compose.waitForIdle()
        compose.onNodeWithTag("seed_confirm_cancel").performClick()
        assertEquals(listOf("confirmSeed", "cancelConfirm"), c.log)
        compose.waitForIdle(); compose.onNodeWithTag("seed_confirm_dialog").assertDoesNotExist()
    }

    @Test fun removalNeedsAConfirmationAndKeepLeavesTheAreaAlone() {
        val c = Calls(); ui = SeedMapUiState(areas = area); show(smallDraft, c)
        scrollTo("seed_remove_seed:1"); compose.onNodeWithTag("seed_remove_seed:1").performClick()
        assertEquals(listOf("askRemove:seed:1"), c.log) // asking is not removing
        compose.waitForIdle(); compose.onNodeWithTag("seed_remove_dialog").assertExists()
        compose.onNodeWithTag("seed_remove_cancel").performClick()
        assertEquals(listOf("askRemove:seed:1", "cancelRemove"), c.log)
        compose.waitForIdle(); compose.onNodeWithTag("seed_remove_dialog").assertDoesNotExist()
        assertEquals(1, ui.areas.size)
    }

    @Test fun confirmingTheRemovalCallsConfirmRemoveAndTheAreaGoes() {
        val c = Calls(); ui = SeedMapUiState(areas = area); show(smallDraft, c)
        scrollTo("seed_remove_seed:1"); compose.onNodeWithTag("seed_remove_seed:1").performClick()
        compose.waitForIdle(); compose.onNodeWithTag("seed_remove_confirm").performClick()
        assertEquals(listOf("askRemove:seed:1", "confirmRemove"), c.log)
        compose.waitForIdle()
        compose.onNodeWithTag("seed_remove_dialog").assertDoesNotExist()
        compose.onNodeWithTag("seed_area_seed:1").assertDoesNotExist()
    }

    @Test fun aMessageCanBeDismissed() {
        val c = Calls(); ui = SeedMapUiState(message = "Seeding stopped: offline only.", messageIsError = true); show(smallDraft, c)
        scrollTo("seed_message"); compose.onNodeWithTag("seed_message").assertExists()
        scrollTo("seed_message_dismiss"); compose.onNodeWithTag("seed_message_dismiss").performClick()
        assertEquals(listOf("dismiss"), c.log)
        compose.waitForIdle(); compose.onNodeWithTag("seed_message").assertDoesNotExist()
    }

    @Test fun seedModeHidesTheNewZoneForm() {
        ui = SeedMapUiState(); show(ZoneDraft(), Calls(), mode = true)
        scrollTo("seed_box")
        compose.onNodeWithTag("seed_box").assertExists()
        compose.onNodeWithTag("zone_name").assertDoesNotExist()
    }
}
