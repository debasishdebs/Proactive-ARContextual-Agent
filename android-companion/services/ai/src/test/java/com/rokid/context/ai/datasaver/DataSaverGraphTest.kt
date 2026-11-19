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
import com.rokid.context.core.net.NetworkCosts
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
        assertEquals(0L, g.blockedSince())
        g.refreshState()
        assertTrue("a refresh that sees photos blocked marks it", g.blockedSince() != 0L)
        snap = wifi
        val afterRestart = graph(DataSaverStore(p)) { snap } // a new process: photoOpen starts unknown, no listener fires
        afterRestart.refreshState()
        assertTrue("the mark survives, so the pipeline still parks the backlog", afterRestart.blockedSince() != 0L)
        assertTrue(afterRestart.clearBlocked(afterRestart.blockedSince()))
        assertEquals(0L, graph(DataSaverStore(p)) { snap }.blockedSince())
        // a blocked call marks it too
        graph(DataSaverStore(p)) { mobile.copy(notRoaming = false) }.also { it.skipped(DataKind.PHOTO, Skipped.UPLOAD_QUEUED, DataDecision(false, "roaming")); assertTrue(it.blockedSince() != 0L) }
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

    private val cellRoaming = NetSnapshot(true, Transport.CELLULAR, validated = true, notMetered = false, notRoaming = false)

    @Test fun aWifiReconnectBlipBeforeValidationDoesNotMarkButOneStillUnvalidatedAfterTheDebounceDoes() {
        val now = System.currentTimeMillis()
        var snap = NetSnapshot(true, Transport.WIFI, validated = false, notMetered = true, notRoaming = true, unvalidatedSinceMs = now - 5_000)
        val g = graph { snap }
        g.refreshState()
        assertEquals("pre-validation blip: not marked", 0L, g.blockedSince()); assertFalse(g.heldBack())
        snap = snap.copy(unvalidatedSinceMs = now - 30_000)
        g.refreshState()
        assertTrue("still blocked after the debounce: marked", g.blockedSince() != 0L); assertTrue(g.heldBack())
    }

    @Test fun anOnDeviceExtractorOnRoamingMobileNeverMarksButARemoteOneDoes() {
        val device = graph { cellRoaming }.also { it.reachProvider = { EndpointReach.DEVICE } }
        device.refreshState(); device.refreshState()
        assertEquals(0L, device.blockedSince()); assertFalse(device.heldBack())
        device.skipped(DataKind.PHOTO, Skipped.UPLOAD_QUEUED, DataDecision(false, "roaming")) // some other remote photo call being blocked is not this app's backlog
        assertEquals(0L, device.blockedSince())
        val lan = graph(DataSaverStore(prefs("dsg9"))) { cellRoaming }.also { it.reachProvider = { EndpointReach.LAN } }
        lan.refreshState(); assertEquals("a LAN box is not reachable for free on mobile data: held back", true, lan.blockedSince() != 0L)
        val remote = graph(DataSaverStore(prefs("dsg10"))) { cellRoaming }
        remote.refreshState(); assertTrue(remote.blockedSince() != 0L)
    }

    @Test fun clearBlockedIsCompareAndClearSoABlockSeenWhileParkingSurvives() {
        val store = DataSaverStore(prefs("dsg11"))
        store.markBlocked(1_000)
        val read = store.blockedSince()
        store.markBlocked(1_000 + DataSaverStore.MARK_REFRESH_MS + 5) // a new block arrives between the read and the clear
        assertFalse("the mark moved: not cleared", store.clearBlocked(read))
        assertTrue(store.blockedSince() > read)
        assertTrue(store.clearBlocked(store.blockedSince()))
        assertEquals(0L, store.blockedSince())
        assertFalse("nothing to clear", store.clearBlocked(read))
        assertFalse("0 never clears", store.clearBlocked(0L))
    }

    // ---- round 6: the debounced, injectable graph ----

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private class Blip {
        val scope = kotlinx.coroutines.test.TestScope(kotlinx.coroutines.test.StandardTestDispatcher())
        var clock = 1_000_000L
        var snap = NetSnapshot(true, Transport.WIFI, validated = false, notMetered = true, notRoaming = true, unvalidatedSinceMs = 1_000_000L - 5_000)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun blipGraph(b: Blip, name: String) = DataSaverGraph(DataSaverStore(prefs(name)), { b.snap }, { 100L }, {}, b.scope, { b.clock })

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aBlipThatIsStillUnvalidatedAfterTheDebounceIsMarkedByTheRecheck() {
        val b = Blip(); val g = blipGraph(b, "dsb1")
        g.refreshState()
        assertEquals("blip: not marked yet", 0L, g.blockedSince())
        b.clock += 25_000 // 30 s unvalidated now
        b.scope.testScheduler.advanceTimeBy(NetworkCosts.BLIP_MS + 2_000); b.scope.testScheduler.runCurrent()
        assertTrue("the recheck marked it", g.blockedSince() != 0L)
        assertTrue(g.heldBack())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aBlipThatValidatesIsNeverMarkedAndLeavesNothingPending() {
        val b = Blip(); val g = blipGraph(b, "dsb2")
        g.refreshState()
        b.snap = b.snap.copy(validated = true, unvalidatedSinceMs = 0)
        b.clock += 25_000
        b.scope.testScheduler.advanceTimeBy(NetworkCosts.BLIP_MS + 2_000); b.scope.testScheduler.runCurrent()
        assertEquals(0L, g.blockedSince()); assertFalse(g.heldBack())
        b.scope.testScheduler.advanceUntilIdle() // would run (and mark) any coroutine left over
        assertEquals("no coroutine left over", 0L, g.blockedSince())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun theOpenTransitionFollowsTheDebouncedDecisionNotTheRawOne() {
        val b = Blip(); var fired = 0
        val g = blipGraph(b, "dsb3"); g.addOpenListener { fired++ }
        g.refreshState(); g.refreshState()
        assertEquals("a reconnect blip from an open state wakes nothing and parks nothing", 0, fired); assertEquals(0L, g.blockedSince())
        // held back (roaming), then the blip of the Wi-Fi reconnect: that is the opening, once
        b.snap = NetSnapshot(true, Transport.CELLULAR, validated = true, notMetered = false, notRoaming = false)
        g.refreshState(); assertTrue(g.blockedSince() != 0L)
        b.snap = NetSnapshot(true, Transport.WIFI, validated = false, notMetered = true, notRoaming = true, unvalidatedSinceMs = b.clock - 1_000)
        g.refreshState(); g.refreshState()
        assertEquals(1, fired)
    }

    @Test fun theHeldBackDecisionTheProbeAndTheWaitingLineUseIsReachAwareAndDebounced() {
        val b = Blip()
        @Suppress("OPT_IN_USAGE") val g = blipGraph(b, "dsb4")
        assertFalse("blip", g.heldBack())
        b.snap = NetSnapshot(true, Transport.CELLULAR, validated = true, notMetered = false, notRoaming = false)
        assertTrue("remote on roaming", g.heldBack())
        g.reachProvider = { EndpointReach.DEVICE }
        assertFalse("on-device on roaming", g.heldBack())
    }

    @Test fun insideTheThrottleWindowAClearWithTheOldValueDoesClearAndThatIsSafeBecauseFramesAreBornParked() {
        val store = DataSaverStore(prefs("dsb5"))
        store.markBlocked(1_000); val read = store.blockedSince()
        store.markBlocked(1_000 + DataSaverStore.MARK_REFRESH_MS - 1) // inside the window: the value does not move
        assertEquals(read, store.blockedSince())
        assertTrue(store.clearBlocked(read))
    }
}
