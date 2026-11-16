package com.rokid.context.phone.ui.settings.privacy.zones

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.zone.ZoneEvaluation
import com.rokid.context.core.model.zone.ZonePresets
import com.rokid.context.core.model.zone.ZoneReason
import com.rokid.context.core.model.zone.ZoneState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.zone.ZoneSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ZonesScreenTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ed = FakeZoneEditor()
    private val vms = mutableListOf<ZonesViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }

    private fun show(granted: Boolean = true): ZonesViewModel {
        val vm = ZonesViewModel(ed, elapsed = { 1_000L }, newId = { "zone-1" }, work = main.dispatcher).also { vms += it }
        vm.setLocationGranted(granted)
        compose.setContent {
            val s by vm.state.collectAsStateWithLifecycle()
            PcTheme { ZonesScreen(s, ZonesCallbacks.of(vm, back = {})) }
        }
        return vm
    }

    private fun scrollTo(tag: String) { compose.onNodeWithTag("zones_screen").performScrollToNode(hasTestTag(tag)) }

    @Test fun emptyScreenShowsMapPresetAndNoPositionNote() {
        show()
        compose.onNodeWithTag("zone_map").assertIsDisplayed()
        compose.onNodeWithTag("zone_no_fix").assertExists()
        scrollTo("zone_preset")
        compose.onNodeWithTag("zone_preset").assertExists()
    }

    @Test fun typedBoundsAddAZoneAndTheChipAndSwitchAppear() {
        show()
        scrollTo("zone_name")
        compose.onNodeWithTag("zone_name").performTextInput("Camp")
        compose.onNodeWithTag("zone_south").performTextInput("12.0")
        compose.onNodeWithTag("zone_north").performTextInput("12.5")
        compose.onNodeWithTag("zone_west").performTextInput("92.0")
        compose.onNodeWithTag("zone_east").performTextInput("92.5")
        scrollTo("zone_save")
        compose.onNodeWithTag("zone_save").performClick()
        ed.snap.value = ed.snap.value.copy(evaluation = ZoneEvaluation(listOf(status("zone-1", ZoneState.INSIDE, ZoneReason.NO_FIX))))
        compose.waitForIdle()
        assertEquals("Camp", ed.upserts.single().name)
        scrollTo("zone_chip_zone-1")
        compose.onNodeWithTag("zone_chip_zone-1").assertExists()
        compose.onNodeWithTag("zone_switch_zone-1").assertExists()
    }

    @Test fun deniedBannerSaysCaptureIsPaused() {
        ed.snap.value = ZoneSnapshot(listOf(ZonePresets.JARAWA), ZoneEvaluation(emptyList()))
        show(granted = false)
        scrollTo("zone_denied")
        compose.onNodeWithTag("zone_denied").assertExists()
        compose.onNodeWithTag("zone_allow_location").assertExists()
    }

    @Test fun deleteAsksFirst() {
        ed.snap.value = ZoneSnapshot(listOf(ZonePresets.JARAWA), ZoneEvaluation(emptyList()))
        show()
        scrollTo("zone_delete_jarawa")
        compose.onNodeWithTag("zone_delete_jarawa").performClick()
        compose.onNodeWithTag("zone_delete_dialog").assertExists()
        compose.onNodeWithTag("zone_delete_confirm").performClick()
        compose.waitForIdle()
        assertEquals(listOf("remove:jarawa"), ed.calls)
    }
}
