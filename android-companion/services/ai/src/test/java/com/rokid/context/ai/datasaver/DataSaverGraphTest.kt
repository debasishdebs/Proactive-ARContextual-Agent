package com.rokid.context.ai.datasaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.DataMode
import com.rokid.context.core.net.DataSaverSettings
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.NetSnapshot
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.Skipped
import com.rokid.context.core.net.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DataSaverGraphTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    private val wifi = NetSnapshot(true, Transport.WIFI, validated = true, notMetered = true, notRoaming = true)
    private val mobile = NetSnapshot(true, Transport.CELLULAR, validated = true, notMetered = false, notRoaming = true)

    @Test fun storeDefaultsAreWifiAndHomeMobileNotAbroadTextAllowed() {
        val s = DataSaverStore(prefs("ds1")).settings.value
        assertEquals(DataSaverSettings(DataMode.WIFI_AND_HOME_MOBILE, abroad = false, smallOnRoaming = true), s)
    }

    @Test fun storeRoundTripsAndAnUnknownStoredModeReadsAsTheSafeDefault() {
        val p = prefs("ds2")
        DataSaverStore(p).apply { setMode(DataMode.ANYWHERE); setAbroad(true); setSmallOnRoaming(false) }
        assertEquals(DataSaverSettings(DataMode.ANYWHERE, true, false), DataSaverStore(p).settings.value)
        p.edit().putString("mode", "SOMETHING_NEW").commit()
        assertEquals(DataMode.DEFAULT, DataSaverStore(p).settings.value.mode)
    }

    @Test fun theStoreFileNameIsTheOneBackupRulesExclude() = assertEquals("data_saver", DataSaverStore.PREFS)

    private fun graph(store: DataSaverStore = DataSaverStore(prefs("dsg")).also { it.setMode(DataMode.WIFI_ONLY) }, day: () -> Long = { 100L }, snap: () -> NetSnapshot) = DataSaverGraph(store, snap, day)

    @Test fun beforeAnyNetworkReadingPhotosAreNotAllowedButTextIs() {
        val g = graph { NetSnapshot.UNKNOWN }
        assertFalse(g.decide(DataKind.PHOTO).allowed)
        assertTrue(g.decide(DataKind.SMALL).allowed)
        assertEquals(NetworkCost.UNKNOWN, g.costNow()); assertTrue(g.costlyNow())
    }

    @Test fun refreshRereadsTheNetworkFactsAndRestartsAFailedWatch() {
        var rereads = 0
        val g = DataSaverGraph(DataSaverStore(prefs("dsg6")), { wifi }, { 100L }, { rereads++ })
        g.refresh(); g.refresh()
        assertEquals(2, rereads)
        g.refreshState(); assertEquals("the state-only refresh does not reread", 2, rereads)
    }

    @Test fun androidDataSaverNotExemptBlocksPhotosEvenInTheDefaultModeButAnExemptAppIsHomeMobile() {
        var snap = mobile.copy(restrictBackground = true)
        val g = graph(DataSaverStore(prefs("dsg7"))) { snap }
        assertEquals(NetworkCost.RESTRICTED, g.costNow()); assertFalse(g.decide(DataKind.PHOTO).allowed)
        assertEquals("android_data_saver", g.decide(DataKind.PHOTO).reason)
        snap = mobile.copy(restrictBackground = false) // whitelisted: the monitor reads it as off
        assertTrue(g.decide(DataKind.PHOTO).allowed)
    }

    @Test fun theBlockedMarkIsPersistedSurvivesARestartAndIsClearedOnceHandled() {
        var snap = mobile.copy(notRoaming = false)
        val p = prefs("dsg8")
        val g = graph(DataSaverStore(p)) { snap }
        assertFalse(g.wasBlocked())
        g.refreshState()
        assertTrue("a refresh that sees photos blocked marks it", g.wasBlocked())
        snap = wifi
        val afterRestart = graph(DataSaverStore(p)) { snap } // a new process: photoOpen starts unknown, no listener fires
        afterRestart.refreshState()
        assertTrue("the mark survives, so the pipeline still parks the backlog", afterRestart.wasBlocked())
        afterRestart.clearBlocked()
        assertFalse(graph(DataSaverStore(p)) { snap }.wasBlocked())
        // a blocked call marks it too
        graph(DataSaverStore(p)) { snap }.also { it.skipped(DataKind.PHOTO, Skipped.UPLOAD_QUEUED, DataDecision(false, "roaming")); assertTrue(it.wasBlocked()) }
    }

    @Test fun theDefaultAllowsHomeMobileDataButNotRoamingOrUnknown() {
        var snap = mobile
        val g = graph(DataSaverStore(prefs("dsg5"))) { snap }
        assertTrue(g.decide(DataKind.PHOTO).allowed)
        snap = mobile.copy(notRoaming = false); assertFalse(g.decide(DataKind.PHOTO).allowed)
        snap = NetSnapshot.UNKNOWN; assertFalse(g.decide(DataKind.PHOTO).allowed)
        snap = mobile; g.settingsStore.setAbroad(true); assertFalse(g.decide(DataKind.PHOTO).allowed)
    }

    @Test fun wifiAllowsPhotosMobileDoesNot() {
        var snap = wifi
        val g = graph { snap }
        assertTrue(g.decide(DataKind.PHOTO).allowed); assertFalse(g.costlyNow())
        snap = mobile
        assertFalse(g.decide(DataKind.PHOTO).allowed); assertTrue(g.costlyNow())
    }

    @Test fun theAbroadSwitchMakesMobileRoamingAndLeavesRealWifiFree() {
        var snap = mobile
        val store = DataSaverStore(prefs("dsg2")); val g = graph(store) { snap }
        assertEquals(NetworkCost.HOME_MOBILE, g.costNow())
        store.setAbroad(true)
        assertEquals(NetworkCost.ROAMING, g.costNow())
        snap = wifi
        assertEquals(NetworkCost.FREE, g.costNow())
    }

    @Test fun lanEndpointOnWifiIsAllowedEvenWithoutValidation() {
        val g = graph { wifi.copy(validated = false) }
        assertTrue(g.decide(DataKind.PHOTO, EndpointReach.LAN).allowed)
        assertFalse(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
    }

    @Test fun skippedCountsInTheLedgerPerDayAndWritesToTheStoreFile() {
        var day = 100L
        val p = prefs("dsg3"); val g = graph(DataSaverStore(p), { day }) { wifi }
        val d = DataDecision(false, "wifi_only")
        g.skipped(DataKind.PHOTO, Skipped.UPLOAD_QUEUED, d); g.skipped(DataKind.PHOTO, Skipped.DROPPED, d); g.skipped(DataKind.SMALL, Skipped.BULK, d)
        assertEquals(1, g.ledger.today().uploadsQueued); assertEquals(1, g.ledger.today().dropped); assertEquals(1, g.ledger.today().bulk)
        // same file, so a new process sees today's counts
        assertEquals(3, DataSaverGraph(DataSaverStore(p), { wifi }, epochDay = { 100L }).ledger.today().total)
        day = 101
        assertEquals(0, g.ledger.today().total)
    }

    @Test fun openListenersFireOnlyWhenPhotosTurnFromBlockedToAllowed() {
        var snap = mobile; var fired = 0
        val store = DataSaverStore(prefs("dsg4")).also { it.setMode(DataMode.WIFI_ONLY) }; val g = graph(store) { snap }
        g.addOpenListener { fired++ }
        g.refresh(); assertEquals("first reading is blocked", 0, fired)
        g.refresh(); assertEquals(0, fired)
        snap = wifi; g.refresh(); assertEquals(1, fired)
        g.refresh(); assertEquals("no repeat while it stays open", 1, fired)
        snap = mobile; g.refresh(); snap = wifi; g.refresh(); assertEquals(2, fired)
        // the mode switch also opens it
        snap = mobile; g.refresh(); store.setMode(DataMode.WIFI_AND_HOME_MOBILE); g.refresh(); assertEquals(3, fired)
    }
}
