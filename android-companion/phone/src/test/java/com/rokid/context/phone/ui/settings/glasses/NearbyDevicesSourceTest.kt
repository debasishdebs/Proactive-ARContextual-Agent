package com.rokid.context.phone.ui.settings.glasses

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.hub.HubSettingsStore
import com.rokid.context.phone.hub.KeepRunningNearby
import com.rokid.context.phone.ui.list.ActionResult
import com.rokid.context.phone.ui.list.ItemStatus
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class NearbyDevicesSourceTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = app.getSharedPreferences(HubSettingsStore.PREFS, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val keep = KeepRunningNearby(prefs)
    private val opened = mutableListOf<String>()

    private var userClosed = false
    private fun source(paired: Boolean, link: LinkState = LinkState.CONNECTED) =
        NearbyDevicesSource(paired = { paired }, link = { link }, keepRunning = keep, openGlassesSettings = { opened += "glasses" }, closedByUser = { userClosed })

    @Test fun closedByYouShowsTheReopenHint() = runBlocking {
        userClosed = true
        assertEquals("Glasses app closed by you; reopen from Settings \u2192 Glasses", source(paired = true, link = LinkState.CLOSED_ON_GLASSES).load().single().subtitle)
        userClosed = false
    }

    @Test fun pageListsPairedGlasses() = runBlocking {
        val rows = source(paired = true).load()
        assertEquals(1, rows.size)
        val r = rows.single()
        assertEquals(NearbyDevicesSource.GLASSES_ID, r.id)
        assertEquals("Rokid glasses", r.title)
        assertEquals(true, r.toggle)
        assertEquals(ItemStatus.None, r.status)
        assertEquals("Connected \u00b7 keeps running \u00b7 reopens after Rokid closes it", r.subtitle)

        val off = source(paired = true, link = LinkState.OFFLINE).load().single()
        assertEquals("Paired \u00b7 keeps running \u00b7 reopens after Rokid closes it", off.subtitle)

        val unpaired = source(paired = false).load().single()
        assertNull("no switch until the glasses are paired", unpaired.toggle)
        assertEquals(ItemStatus.ActionNeeded, unpaired.status)
        assertEquals("Not paired \u00b7 authorize in Rokid Glasses settings", unpaired.subtitle)
        assertEquals(ActionResult.Silent, source(paired = false).actionFor(unpaired).run {})
        assertEquals(listOf("glasses"), opened)
    }

    @Test fun keepRunningSwitchDefaultsOn() = runBlocking {
        assertTrue("default ON (today's behaviour)", keep.enabled())
        val s = source(paired = true)
        val row = s.load().single()
        assertEquals(true, row.toggle)
        s.actionFor(row).run {}
        assertFalse(keep.enabled())
        assertFalse("stored in the restorable hub_settings file", prefs.getBoolean(KeepRunningNearby.KEY, true))
        assertEquals("Connected \u00b7 stops when the app is closed", s.load().single().subtitle)
        s.actionFor(s.load().single()).run {}
        assertTrue(KeepRunningNearby(prefs).enabled())
    }

    @Test fun nearbyRouteIsALiveSettingsRoute() {
        assertEquals("settings/glasses/nearby", Routes.SETTINGS_NEARBY)
        assertTrue(Routes.SETTINGS_NEARBY in Routes.LIVE_SETTINGS)
        assertEquals(Routes.Tab.SETTINGS, Routes.tabFor(Routes.SETTINGS_NEARBY))
        assertTrue(Routes.showsNav(Routes.SETTINGS_NEARBY))
    }

    @Test fun glassesSettingsOpensNearbyDevices() {
        var clicks = 0
        compose.setContent {
            PcTheme {
                GlassesSettingsScreen(GlassesSettingsUiState("Linked", HudSettings()), onBack = {}, onVerbosity = {}, onScale = {}, onPosition = {},
                    onGesture = { _, _ -> }, onReauthorize = {}, onOpenNearby = { clicks++ })
            }
        }
        compose.onNodeWithTag("glasses_settings").performScrollToNode(hasText("Nearby devices"))
        compose.onNodeWithText("Nearby devices").performClick()
        assertEquals(1, clicks)
    }

    @Test fun thePageRendersOnTheGenericListScreen() {
        compose.setContent { PcTheme { NearbyDevicesRoute(source(paired = true), onBack = {}) } }
        compose.onNodeWithText("Nearby devices").assertExists()
        compose.onNodeWithTag("list_row_${NearbyDevicesSource.GLASSES_ID}").assertExists()
        compose.onNodeWithTag("list_row_${NearbyDevicesSource.GLASSES_ID}").performClick()
        compose.waitUntil(5_000) { !keep.enabled() }
    }
}
