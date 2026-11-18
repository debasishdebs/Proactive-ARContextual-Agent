package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.privacy.zones.FakeZoneEditor
import com.rokid.context.phone.ui.settings.privacy.zones.OfflineMapPanel
import com.rokid.context.phone.ui.settings.privacy.zones.ZonesCallbacks
import com.rokid.context.phone.ui.settings.privacy.zones.ZonesScreen
import com.rokid.context.phone.ui.settings.privacy.zones.ZonesViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class OfflineMapCardTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val vms = mutableListOf<androidx.lifecycle.ViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }

    private val calls = mutableListOf<String>()
    private val uri = "content://docs/doc/1"

    private fun show(files: FakeMapFiles, store: OfflineMapStore = OfflineMapStore(), withPanel: Boolean = true): OfflineMapViewModel {
        val zones = ZonesViewModel(FakeZoneEditor(), elapsed = { 1_000L }, newId = { "z" }, work = main.dispatcher).also { vms += it }
        val map = OfflineMapViewModel(files, store, main.dispatcher).also { vms += it }
        compose.setContent {
            val s by zones.state.collectAsStateWithLifecycle()
            val ui by map.ui.collectAsStateWithLifecycle()
            val reader by map.reader.collectAsStateWithLifecycle()
            val panel = if (withPanel) OfflineMapPanel(ui, reader, { calls += "link" }, { calls += "import" }, map::remove, map::dismissMessage) else null
            PcTheme { ZonesScreen(s, ZonesCallbacks.of(zones, back = {}), panel) }
        }
        return map
    }

    private fun scrollTo(tag: String) { compose.onNodeWithTag("zones_screen").performScrollToNode(hasTestTag(tag)) }
    private fun fakeFiles() = FakeMapFiles(File(tmp.root, "offline_maps")).also { it.docs[uri] = PmTilesFixture.build(PmTilesFixture.sample()); it.names[uri] = "sri-lanka.pmtiles" }

    @Test fun withNoFileThePlainCanvasAndTheChoicesShow() {
        show(fakeFiles())
        compose.onNodeWithTag("zone_map").assertIsDisplayed()
        scrollTo("offline_none"); compose.onNodeWithTag("offline_none").assertExists()
        compose.onNodeWithTag("offline_choose").assertIsEnabled(); compose.onNodeWithTag("offline_import").assertIsEnabled()
        compose.onNodeWithTag("offline_remove").assertDoesNotExist()
        compose.onNodeWithTag("zone_map_attribution").assertDoesNotExist()
        compose.onNodeWithTag("offline_choose").performClick(); compose.onNodeWithTag("offline_import").performClick()
        assertEquals(listOf("link", "import"), calls)
    }

    @Test fun aLinkedFileShowsNameWhereAndZoomsAndCanBeStopped() {
        val m = show(fakeFiles())
        m.link(uri); compose.waitForIdle()
        scrollTo("offline_name")
        compose.onNodeWithTag("offline_name").assertExists()
        compose.onNodeWithTag("offline_where").assertExists()
        scrollTo("offline_remove")
        compose.onNodeWithTag("offline_remove").performClick(); compose.waitForIdle()
        assertEquals(null, m.ui.value.selection)
        scrollTo("offline_choose")
        compose.onNodeWithTag("offline_remove").assertDoesNotExist()
        compose.onNodeWithTag("offline_none").assertExists()
    }

    @Test fun aCorruptFileShowsAPlainErrorAndTheMapStaysUsable() {
        val f = fakeFiles().also { it.docs[uri] = ByteArray(400) }
        val m = show(f)
        m.link(uri); compose.waitForIdle()
        scrollTo("offline_message"); compose.onNodeWithTag("offline_message").assertExists()
        compose.onNodeWithTag("offline_message_dismiss").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("offline_message").assertDoesNotExist()
        compose.onNodeWithTag("zone_map").assertExists()
    }

    @Test fun anImportedCopyOffersDelete() {
        val f = fakeFiles(); val m = show(f)
        m.importCopy(uri); compose.waitForIdle()
        scrollTo("offline_remove"); compose.onNodeWithTag("offline_remove").assertExists()
        compose.onNodeWithTag("offline_remove").performClick(); compose.waitForIdle()
        assertEquals(false, File(f.importDir, OfflineMapViewModel.IMPORT_NAME).exists())
    }

    @Test fun withoutAMapBuildThereIsNoOfflineCard() {
        show(fakeFiles(), withPanel = false)
        compose.onNodeWithTag("offline_choose").assertDoesNotExist()
    }
}
