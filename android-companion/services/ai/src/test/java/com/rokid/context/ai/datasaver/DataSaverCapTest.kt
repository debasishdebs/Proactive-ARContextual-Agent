package com.rokid.context.ai.datasaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.net.DailyLimit
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.DataMode
import com.rokid.context.core.net.DataPolicy
import com.rokid.context.core.net.DataSaverSettings
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.NetSnapshot
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.Skipped
import com.rokid.context.core.net.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Slice 2 at the graph: the caps as the call sites meet them, the byte counter, the midnight wake on virtual time, and the store. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DataSaverCapTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val mb = 1024L * 1024L

    private val wifi = NetSnapshot(true, Transport.WIFI, validated = true, notMetered = true, notRoaming = true)
    private val home = NetSnapshot(true, Transport.CELLULAR, validated = true, notMetered = false, notRoaming = true)
    private val roaming = home.copy(notRoaming = false)

    private fun graph(name: String, snap: () -> NetSnapshot, config: DataSaverStore.() -> Unit = {}): DataSaverGraph {
        val store = DataSaverStore(prefs(name)).apply(config)
        return DataSaverGraph(store, snap, epochDay = { 100L })
    }

    // ---- default behaviour is unchanged ----

    @Test fun aDefaultUserOnHomeMobileDataOrWifiNeverHitsAnyLimitHoweverMuchIsSent() {
        var snap = home
        val g = graph("cap1", { snap })
        repeat(50) { g.sent(DataKind.PHOTO, 5 * mb, 1000, 3) } // 250 MB and 150 photos
        assertNull("no limit applies", g.limitNow()); assertFalse(g.limitReachedNow())
        assertTrue(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
        assertTrue(g.decide(DataKind.BULK_OTHER, EndpointReach.REMOTE).allowed)
        assertFalse("not held back, so the keyframe loop keeps going", g.heldBack())
        snap = wifi
        assertTrue(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
        // wifi is not counted
        val before = g.mobile.today().bytes
        g.sent(DataKind.PHOTO, 99 * mb, 0, 9)
        assertEquals(before, g.mobile.today().bytes)
    }

    @Test fun defaultsStillBlockRoamingAndUnknownLikeSliceOne() {
        var snap = roaming
        val g = graph("cap2", { snap })
        assertEquals("roaming", g.decide(DataKind.PHOTO, EndpointReach.REMOTE).reason)
        snap = NetSnapshot.UNKNOWN
        assertEquals("unknown_network", g.decide(DataKind.PHOTO, EndpointReach.REMOTE).reason)
        assertTrue(g.decide(DataKind.SMALL, EndpointReach.REMOTE).allowed)
    }

    // ---- the abroad cap ----

    @Test fun abroadWithAnywhereGoesOutUnderTheCapThenParksPhotosButNotText() {
        val g = graph("cap3", { home }) { setMode(DataMode.ANYWHERE); setAbroad(true) } // abroad: cellular is ROAMING
        assertEquals(NetworkCost.ROAMING, g.costNow())
        assertEquals(DailyLimit(10 * mb, 30), g.limitNow())
        assertTrue(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
        g.sent(DataKind.PHOTO, 9 * mb, 1000, 29)
        assertFalse(g.limitReachedNow()); assertTrue(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
        g.sent(DataKind.PHOTO, 1 * mb, 1000, 3) // 10+ MB and 32 photos
        assertTrue(g.limitReachedNow())
        val d = g.decide(DataKind.PHOTO, EndpointReach.REMOTE)
        assertEquals(DataPolicy.R_DAILY_LIMIT, d.reason); assertFalse(d.allowed)
        assertTrue("held back: frames park exactly like a data-saver hold", g.heldBack())
        assertTrue("text still goes", g.decide(DataKind.SMALL, EndpointReach.REMOTE).allowed)
        assertTrue("an on-device model is never counted or capped", g.decide(DataKind.PHOTO, EndpointReach.DEVICE).allowed)
        g.skipped(DataKind.PHOTO, Skipped.UPLOAD_QUEUED, d)
        assertEquals(1, g.ledger.today().uploadsQueued)
        assertTrue("the held-back mark is persisted for a restart", g.blockedSince() != 0L)
    }

    @Test fun theLimitIsLiftedWhenTheNetworkIsWifiAndStillCountedSeparately() {
        var snap = home
        val g = graph("cap4", { snap }) { setMode(DataMode.ANYWHERE); setAbroad(true) }
        g.sent(DataKind.PHOTO, 11 * mb, 0, 31)
        assertTrue(g.limitReachedNow())
        snap = wifi
        assertNull(g.limitNow()); assertFalse(g.limitReachedNow())
        assertTrue(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
        assertEquals("the mobile counters stay for the status lines", 31L, g.mobile.today().photos)
    }

    // ---- the user's own limit ----

    @Test fun theUsersOwnLimitAppliesAtHomeAndRaisingItWakesTheGate() {
        val g = graph("cap5", { home }) { setDailyLimitOn(true); setLimitMb(5); setLimitPhotos(100) }
        assertEquals(DailyLimit(5 * mb, 100), g.limitNow())
        g.sent(DataKind.PHOTO, 5 * mb, 0, 10)
        assertEquals(DataPolicy.R_DAILY_LIMIT, g.decide(DataKind.PHOTO, EndpointReach.REMOTE).reason)
        g.settingsStore.setLimitMb(25)
        assertTrue(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
        g.settingsStore.setDailyLimitOn(false)
        g.sent(DataKind.PHOTO, 500 * mb, 0, 1000)
        assertTrue("limit switched off: unlimited again", g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
    }

    // ---- send once ----

    @Test fun decideOnceLiftsAModeBlockOrTheLimitButNotOfflineOnly() {
        val g = graph("cap6", { home }) { setMode(DataMode.WIFI_ONLY) }
        assertEquals("wifi_only", g.decide(DataKind.PHOTO, EndpointReach.REMOTE).reason)
        assertEquals(DataPolicy.R_SEND_ONCE, g.decideOnce(DataKind.PHOTO, EndpointReach.REMOTE).reason)
        assertFalse("a plain decision afterwards is blocked again: nothing is remembered", g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
        val offline = DataSaverGraph(DataSaverStore(prefs("cap6b")), { home }, epochDay = { 100L }, privacy = { com.rokid.context.core.net.ModelPrivacy.OFFLINE_ONLY })
        assertFalse(offline.decideOnce(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
    }

    // ---- midnight, on virtual time ----

    /** 2026-11-20 22:30 in Asia/Kolkata: 90 minutes to midnight. */
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val start = ZonedDateTime.of(2026, 11, 20, 22, 30, 0, 0, kolkata).toInstant().toEpochMilli()

    private fun TestScope.timedGraph(name: String, config: DataSaverStore.() -> Unit): DataSaverGraph {
        val clock = { start + currentTime }
        val day = { Instant.ofEpochMilli(clock()).atZone(kolkata).toLocalDate().toEpochDay() }
        val store = DataSaverStore(prefs(name)).apply(config)
        return DataSaverGraph(store, { home }, epochDay = day, scope = backgroundScope, clock = clock, zone = { kolkata })
    }

    @Test fun midnightMathIsLocalAndExact() {
        assertEquals(90 * 60_000L, DataSaverGraph.msUntilLocalMidnight(start, kolkata))
        assertEquals(1L, DataSaverGraph.msUntilLocalMidnight(ZonedDateTime.of(2026, 11, 20, 23, 59, 59, 999_000_000, kolkata).toInstant().toEpochMilli(), kolkata))
        assertEquals(24 * 3_600_000L, DataSaverGraph.msUntilLocalMidnight(ZonedDateTime.of(2026, 11, 21, 0, 0, 0, 0, kolkata).toInstant().toEpochMilli(), kolkata))
    }

    @Test fun aCapReachedBeforeMidnightIsLiftedAtLocalMidnightAndWakesTheParkedLoop() = runTest {
        val g = timedGraph("cap7") { setMode(DataMode.ANYWHERE); setAbroad(true) }
        g.refreshState()
        var wakes = 0
        g.addOpenListener { wakes++ }
        g.startMidnightWatch()
        g.sent(DataKind.PHOTO, 11 * mb, 0, 31) // reaches the limit at 22:30: held back
        assertTrue(g.heldBack()); assertTrue(g.limitReachedNow())
        advanceTimeBy(89 * 60_000L)                // 23:59
        assertTrue("still the same day", g.heldBack()); assertEquals(0, wakes)
        advanceTimeBy(2 * 60_000L + 1_000L)       // just past 00:00 local
        assertEquals("counters reset at local midnight", 0L, g.mobile.today().bytes)
        assertFalse(g.heldBack()); assertFalse(g.limitReachedNow())
        assertEquals("the parked keyframe loop is woken once", 1, wakes)
        assertTrue(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
    }

    @Test fun theMidnightWatchKeepsGoingEveryNight() = runTest {
        val g = timedGraph("cap8") { setMode(DataMode.ANYWHERE); setAbroad(true) }
        g.refreshState(); var wakes = 0; g.addOpenListener { wakes++ }
        g.startMidnightWatch()
        g.sent(DataKind.PHOTO, 11 * mb, 0, 31); assertTrue(g.heldBack())
        advanceTimeBy(91 * 60_000L); assertEquals(1, wakes)
        g.sent(DataKind.PHOTO, 11 * mb, 0, 31); assertTrue("day 2 reaches it again", g.heldBack())
        advanceTimeBy(24 * 3_600_000L); assertEquals(2, wakes)
    }

    @Test fun midnightMathFollowsDstDayLengths() {
        val london = ZoneId.of("Europe/London")
        fun ms(y: Int, m: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, m, d, h, mi, 0, 0, london).toInstant().toEpochMilli()
        assertEquals("25-hour day (clocks go back)", 25 * 3_600_000L, DataSaverGraph.msUntilLocalMidnight(ms(2026, 10, 25, 0, 0), london))
        assertEquals("23-hour day (clocks go forward)", 23 * 3_600_000L, DataSaverGraph.msUntilLocalMidnight(ms(2026, 3, 29, 0, 0), london))
    }

    @Test fun aZoneChangeRecomputesTheMidnightWakeInsteadOfWaitingForTheOldOne() = runTest {
        var zone = kolkata
        val clock = { start + currentTime }
        val day = { Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toEpochDay() }
        val g = DataSaverGraph(DataSaverStore(prefs("cap7z")).apply { setMode(DataMode.ANYWHERE); setAbroad(true) }, { home }, epochDay = day, scope = backgroundScope, clock = clock, zone = { zone })
        g.refreshState(); var wakes = 0; g.addOpenListener { wakes++ }
        g.startMidnightWatch()
        g.sent(DataKind.PHOTO, 11 * mb, 0, 31); assertTrue(g.heldBack())
        advanceTimeBy(10 * 60_000L) // 22:40 Kolkata: the old wake is 80 minutes away
        // fly east to Tokyo (+3:30): it is already 02:10 on the 21st there, so the day changed now
        zone = ZoneId.of("Asia/Tokyo")
        g.onClockChanged()
        advanceTimeBy(1)
        assertFalse("the new local day lifted the limit at the change, not 80 minutes later", g.heldBack())
        assertEquals(1, wakes)
        // and the next wake is the Tokyo midnight (21h50m away), not the stale Kolkata one
        g.sent(DataKind.PHOTO, 11 * mb, 0, 31); assertTrue(g.heldBack())
        advanceTimeBy(80 * 60_000L + 5_000L); assertTrue("the old Kolkata midnight no longer fires", g.heldBack())
        advanceTimeBy(21 * 3_600_000L); assertFalse(g.heldBack()); assertEquals(2, wakes)
    }

    @Test fun flyingWestExtendsTheCapDayUntilTheLocalDatePassesIt() = runTest {
        var zone = kolkata
        val clock = { start + currentTime }
        val day = { Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toEpochDay() }
        val g = DataSaverGraph(DataSaverStore(prefs("cap7w")).apply { setMode(DataMode.ANYWHERE); setAbroad(true) }, { home }, epochDay = day, scope = backgroundScope, clock = clock, zone = { zone })
        g.refreshState(); g.startMidnightWatch()
        advanceTimeBy(91 * 60_000L) // 00:01 on the 21st in Kolkata
        g.sent(DataKind.PHOTO, 11 * mb, 0, 31); assertTrue(g.heldBack())
        zone = ZoneId.of("America/Los_Angeles") // still the 20th there
        g.onClockChanged(); advanceTimeBy(1)
        assertTrue("west: the stored day (21st) stays the cap day", g.heldBack())
    }

    @Test fun heldBackForPromptIgnoresTheWifiReconnectBlipAndOfflineOnly() {
        val now = System.currentTimeMillis()
        var snap = NetSnapshot(true, Transport.WIFI, validated = false, notMetered = true, notRoaming = true, unvalidatedSinceMs = now - 5_000)
        var privacy = com.rokid.context.core.net.ModelPrivacy.AUTO
        val g = DataSaverGraph(DataSaverStore(prefs("cap10")), { snap }, epochDay = { 100L }, privacy = { privacy })
        assertFalse("blip: no prompt", g.heldBackForPrompt())
        snap = snap.copy(unvalidatedSinceMs = now - 60_000)
        assertTrue("still unvalidated after the debounce: prompt", g.heldBackForPrompt())
        privacy = com.rokid.context.core.net.ModelPrivacy.OFFLINE_ONLY
        assertFalse("offline only cannot be lifted by once: no prompt", g.heldBackForPrompt())
    }

    // ---- the store ----

    @Test fun theStoreDefaultsToNoLimitAndRoundTripsTheSliceTwoSettings() {
        val p = prefs("cap9")
        val d = DataSaverStore(p).settings.value
        assertFalse(d.dailyLimitOn); assertFalse(d.wifiMeteredAbroad); assertEquals(10, d.limitMb); assertEquals(30, d.limitPhotos)
        DataSaverStore(p).apply { setDailyLimitOn(true); setLimitMb(25); setLimitPhotos(100); setWifiMeteredAbroad(true) }
        assertEquals(DataSaverSettings(dailyLimitOn = true, limitMb = 25, limitPhotos = 100, wifiMeteredAbroad = true), DataSaverStore(p).settings.value)
        p.edit().putInt("daily_limit_mb", -5).putInt("daily_limit_photos", 99_999_999).commit()
        assertEquals("an unreadable number reads as the default", DataSaverSettings(dailyLimitOn = true, wifiMeteredAbroad = true), DataSaverStore(p).settings.value)
    }

    @Test fun everySliceTwoKeyLivesInTheOneExcludedPrefsFileAndHoldsOnlyNumbers() {
        val p = prefs("data_saver")
        val store = DataSaverStore(p)
        store.setDailyLimitOn(true); store.setLimitMb(5); store.setLimitPhotos(10); store.setWifiMeteredAbroad(true); store.setAbroad(true)
        val g = DataSaverGraph(store, { home }, epochDay = { 100L })
        g.sent(DataKind.PHOTO, 1234, 56, 2)
        val keys = p.all
        assertTrue(keys.keys.toString(), keys.keys.containsAll(setOf("daily_limit_on", "daily_limit_mb", "daily_limit_photos", "wifi_metered_abroad", "mobile_day", "mobile_photos")))
        assertTrue("numbers and switches only: no text anywhere in the file", keys.values.none { it is String && it != "ANYWHERE" && it != "WIFI_ONLY" && it != "WIFI_AND_HOME_MOBILE" })
        assertEquals("data_saver", DataSaverStore.PREFS)
    }

    @Test fun theHotspotSwitchTurnsAWifiIntoMobileDataWhileAbroad() {
        val g = graph("cap10", { wifi }) { setMode(DataMode.WIFI_ONLY); setAbroad(true); setWifiMeteredAbroad(true) }
        assertEquals(NetworkCost.ROAMING, g.costNow())
        assertFalse(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
        g.settingsStore.setWifiMeteredAbroad(false)
        assertEquals(NetworkCost.FREE, g.costNow())
        assertTrue(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
    }
}
