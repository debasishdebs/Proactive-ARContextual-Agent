package com.rokid.context.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataSaverTest {
    private fun snap(t: Transport, validated: Boolean = true, notMetered: Boolean = false, notRoaming: Boolean = true, mismatch: Boolean = false, restrict: Boolean = false) =
        NetSnapshot(true, t, validated, notMetered, notRoaming, mismatch, restrict)

    private val wifiFree = snap(Transport.WIFI, notMetered = true)
    private val homeMobile = snap(Transport.CELLULAR)

    // ---- cost classification ----

    @Test fun classificationTable() {
        data class Row(val why: String, val s: NetSnapshot, val abroad: Boolean, val want: NetworkCost)
        listOf(
            Row("unknown snapshot fails closed", NetSnapshot.UNKNOWN, false, NetworkCost.UNKNOWN),
            Row("known=false wins over good flags", wifiFree.copy(known = false), false, NetworkCost.UNKNOWN),
            Row("validated unmetered wifi", wifiFree, false, NetworkCost.FREE),
            Row("ethernet", wifiFree.copy(transport = Transport.ETHERNET), false, NetworkCost.FREE),
            Row("abroad does not touch a real free wifi", wifiFree, true, NetworkCost.FREE),
            Row("wifi not validated (portal)", wifiFree.copy(validated = false), false, NetworkCost.UNKNOWN),
            Row("metered wifi at home", snap(Transport.WIFI, notMetered = false), false, NetworkCost.HOME_MOBILE),
            Row("metered wifi abroad is a likely hotspot", snap(Transport.WIFI, notMetered = false), true, NetworkCost.ROAMING),
            Row("wifi reported roaming (hotspot)", wifiFree.copy(notRoaming = false), false, NetworkCost.ROAMING),
            Row("android data saver on, metered wifi", snap(Transport.WIFI, restrict = true), false, NetworkCost.RESTRICTED),
            Row("home mobile", homeMobile, false, NetworkCost.HOME_MOBILE),
            Row("mobile reported roaming", snap(Transport.CELLULAR, notRoaming = false), false, NetworkCost.ROAMING),
            Row("mobile, country mismatch", snap(Transport.CELLULAR, mismatch = true), false, NetworkCost.ROAMING),
            Row("mobile, android data saver (not exempt)", snap(Transport.CELLULAR, restrict = true), false, NetworkCost.RESTRICTED),
            Row("mobile, android data saver but the app is exempt (reads as off)", snap(Transport.CELLULAR, restrict = false), false, NetworkCost.HOME_MOBILE),
            Row("roaming wins over android data saver", snap(Transport.CELLULAR, restrict = true, notRoaming = false), false, NetworkCost.ROAMING),
            Row("mobile, I'm abroad", homeMobile, true, NetworkCost.ROAMING),
            Row("mobile flagged not-metered is still not free", snap(Transport.CELLULAR, notMetered = true), false, NetworkCost.HOME_MOBILE),
            Row("vpn only / other transport", snap(Transport.OTHER), false, NetworkCost.UNKNOWN),
            Row("no network", snap(Transport.NONE), false, NetworkCost.OFFLINE),
        ).forEach { assertEquals(it.want, NetworkCosts.classify(it.s, it.abroad), it.why) }
    }

    @Test fun costlyMeansEverythingButFreeAndOffline() {
        assertEquals(setOf(NetworkCost.HOME_MOBILE, NetworkCost.ROAMING, NetworkCost.UNKNOWN, NetworkCost.RESTRICTED), NetworkCost.entries.filter(NetworkCosts::isCostly).toSet())
    }

    @Test fun aWifiThatNeverValidatesIsStuckOnlyAfterTenMinutesAndOnlyWhenOtherwiseConnected() {
        val t0 = 1_000_000L
        val s = snap(Transport.WIFI, validated = false, notMetered = true).copy(unvalidatedSinceMs = t0)
        assertFalse(NetworkCosts.stuckUnvalidated(s, t0 + 9 * 60_000L))
        assertTrue(NetworkCosts.stuckUnvalidated(s, t0 + 11 * 60_000L))
        assertFalse(NetworkCosts.stuckUnvalidated(s.copy(validated = true), t0 + 11 * 60_000L))
        assertFalse(NetworkCosts.stuckUnvalidated(s.copy(notMetered = false), t0 + 11 * 60_000L), "metered: not the portal case")
        assertFalse(NetworkCosts.stuckUnvalidated(s.copy(transport = Transport.CELLULAR), t0 + 11 * 60_000L))
        assertFalse(NetworkCosts.stuckUnvalidated(s.copy(unvalidatedSinceMs = 0), t0 + 11 * 60_000L))
        // it stays costly for remote photos, but a LAN box or the device keeps working
        assertEquals(NetworkCost.UNKNOWN, NetworkCosts.classify(s, false))
        assertFalse(DataPolicy.decide(DataKind.PHOTO, EndpointReach.REMOTE, NetworkCost.UNKNOWN, Transport.WIFI, DataSaverSettings()).allowed)
        assertTrue(DataPolicy.decide(DataKind.PHOTO, EndpointReach.LAN, NetworkCost.UNKNOWN, Transport.WIFI, DataSaverSettings()).allowed)
        assertTrue(DataPolicy.decide(DataKind.PHOTO, EndpointReach.DEVICE, NetworkCost.UNKNOWN, Transport.WIFI, DataSaverSettings()).allowed)
    }

    @Test fun androidDataSaverHasItsOwnTokenBlocksPhotosInEveryModeAndKeepsText() {
        for (mode in DataMode.entries) for (kind in listOf(DataKind.PHOTO, DataKind.BULK_OTHER)) {
            assertEquals(DataDecision(false, DataPolicy.R_ANDROID_SAVER), decide(kind, NetworkCost.RESTRICTED, DataSaverSettings(mode)), "$mode $kind")
        }
        assertTrue(decide(DataKind.SMALL, NetworkCost.RESTRICTED, DataSaverSettings(smallOnRoaming = false)).allowed)
        // the app exempt (whitelisted): plain home mobile data, allowed in the default mode
        assertTrue(DataPolicy.decide(DataKind.PHOTO, EndpointReach.REMOTE, NetworkCosts.classify(snap(Transport.CELLULAR, restrict = false), false), Transport.CELLULAR, DataSaverSettings()).allowed)
    }

    @Test fun roamingWhyNamesTheSignal() {
        assertEquals(NetworkCosts.WHY_REPORTED, NetworkCosts.roamingWhy(snap(Transport.CELLULAR, notRoaming = false), false))
        assertEquals(NetworkCosts.WHY_COUNTRY, NetworkCosts.roamingWhy(snap(Transport.CELLULAR, mismatch = true), false))
        assertEquals(NetworkCosts.WHY_ABROAD, NetworkCosts.roamingWhy(snap(Transport.CELLULAR), true))
        assertEquals("", NetworkCosts.roamingWhy(snap(Transport.CELLULAR), false))
        assertEquals("", NetworkCosts.roamingWhy(snap(Transport.CELLULAR, restrict = true), false))
    }

    @Test fun preValidationBlipOnlyOnAConnectedWifiForTheFirstTwentySeconds() {
        val t0 = 5_000_000L
        val s = snap(Transport.WIFI, validated = false, notMetered = true).copy(unvalidatedSinceMs = t0)
        assertTrue(NetworkCosts.preValidationBlip(s, t0 + 5_000))
        assertFalse(NetworkCosts.preValidationBlip(s, t0 + 25_000))
        assertFalse(NetworkCosts.preValidationBlip(s.copy(transport = Transport.CELLULAR), t0 + 5_000))
        assertFalse(NetworkCosts.preValidationBlip(s.copy(unvalidatedSinceMs = 0), t0 + 5_000))
        assertFalse(NetworkCosts.preValidationBlip(NetSnapshot.UNKNOWN, t0))
    }

    // ---- the decision ----

    private fun decide(kind: DataKind, cost: NetworkCost, s: DataSaverSettings = DataSaverSettings(DataMode.WIFI_ONLY), reach: EndpointReach = EndpointReach.REMOTE, t: Transport = Transport.CELLULAR) =
        DataPolicy.decide(kind, reach, cost, t, s)

    @Test fun defaultsAreWifiAndHomeMobileAndTextAllowed() {
        val d = DataSaverSettings()
        assertEquals(DataMode.WIFI_AND_HOME_MOBILE, d.mode); assertFalse(d.abroad); assertTrue(d.smallOnRoaming)
    }

    @Test fun photoAndBulkTableInWifiOnlyMode() {
        for (kind in listOf(DataKind.PHOTO, DataKind.BULK_OTHER)) {
            assertTrue(decide(kind, NetworkCost.FREE).allowed, "free $kind")
            assertTrue(decide(kind, NetworkCost.OFFLINE).allowed, "offline $kind: the existing offline path handles it")
            assertEquals(DataDecision(false, DataPolicy.R_WIFI_ONLY), decide(kind, NetworkCost.HOME_MOBILE))
            assertEquals(DataDecision(false, DataPolicy.R_ROAMING), decide(kind, NetworkCost.ROAMING))
            assertEquals(DataDecision(false, DataPolicy.R_UNKNOWN), decide(kind, NetworkCost.UNKNOWN), "an unknown network is NOT allowed for $kind")
        }
    }

    @Test fun textStaysAllowedEverywhereByDefault() {
        NetworkCost.entries.forEach { assertTrue(decide(DataKind.SMALL, it).allowed, "text on $it") }
    }

    @Test fun textOnRoamingCanBeSwitchedOffButHomeMobileTextStays() {
        val s = DataSaverSettings(smallOnRoaming = false)
        assertFalse(decide(DataKind.SMALL, NetworkCost.ROAMING, s).allowed)
        assertFalse(decide(DataKind.SMALL, NetworkCost.UNKNOWN, s).allowed)
        assertTrue(decide(DataKind.SMALL, NetworkCost.HOME_MOBILE, s).allowed)
        assertTrue(decide(DataKind.SMALL, NetworkCost.FREE, s).allowed)
    }

    @Test fun homeMobileModeAllowsHomeMobileNeverRoamingOrUnknown() {
        val s = DataSaverSettings(mode = DataMode.WIFI_AND_HOME_MOBILE)
        assertTrue(decide(DataKind.PHOTO, NetworkCost.HOME_MOBILE, s).allowed)
        assertFalse(decide(DataKind.PHOTO, NetworkCost.ROAMING, s).allowed)
        assertFalse(decide(DataKind.PHOTO, NetworkCost.UNKNOWN, s).allowed)
    }

    @Test fun anywhereModeAllowsAllAndAbroadHonoursItUnderTheDailyCap() {
        val any = DataSaverSettings(mode = DataMode.ANYWHERE)
        NetworkCost.entries.filter { it != NetworkCost.RESTRICTED }.forEach { assertTrue(decide(DataKind.PHOTO, it, any).allowed, "anywhere $it") }
        val abroad = any.copy(abroad = true)
        // slice 2: abroad + Anywhere goes out, but only under today's cap (see DailyCapsTest for the cap table)
        assertTrue(decide(DataKind.PHOTO, NetworkCost.ROAMING, abroad).allowed)
        assertTrue(decide(DataKind.BULK_OTHER, NetworkCost.UNKNOWN, abroad).allowed)
    }

    @Test fun onDeviceIsNeverGated() {
        assertTrue(decide(DataKind.PHOTO, NetworkCost.ROAMING, reach = EndpointReach.DEVICE).allowed)
    }

    @Test fun lanEndpointIsFreeOnlyOnWifiOrEthernet() {
        assertTrue(decide(DataKind.PHOTO, NetworkCost.UNKNOWN, reach = EndpointReach.LAN, t = Transport.WIFI).allowed)
        assertTrue(decide(DataKind.PHOTO, NetworkCost.HOME_MOBILE, reach = EndpointReach.LAN, t = Transport.ETHERNET).allowed)
        // on mobile data a "private" address is a tunnel to a remote box: treated as remote
        assertFalse(decide(DataKind.PHOTO, NetworkCost.ROAMING, reach = EndpointReach.LAN, t = Transport.CELLULAR).allowed)
        assertFalse(decide(DataKind.PHOTO, NetworkCost.UNKNOWN, reach = EndpointReach.LAN, t = Transport.OTHER).allowed)
    }

    @Test fun reasonsAreFixedTokens() {
        val tokens = setOf("allowed", "on_device", "lan", "free", "offline", "wifi_only", "roaming", "unknown_network", "home_mobile", "small_allowed", "anywhere", "android_data_saver", "daily_limit", "send_once")
        for (kind in DataKind.entries) for (cost in NetworkCost.entries) for (mode in DataMode.entries) for (abroad in listOf(false, true)) for (small in listOf(false, true)) {
            val r = decide(kind, cost, DataSaverSettings(mode, abroad, small)).reason
            assertTrue(r in tokens, r)
        }
    }

    @Test fun loopbackIsLanButOnlyLocalhost127AndIpv6LoopbackAreLoopback() {
        listOf("localhost", "LOCALHOST.", "127.0.0.1", "127.9.9.9", "::1", "[::1]").forEach { assertTrue(DataPolicy.isLoopbackHost(it), it); assertTrue(DataPolicy.isLanHost(it), it) }
        listOf("192.168.0.9", "10.0.0.1", "mybox.local", "128.0.0.1", "1127.0.0.1", "127.0.0.1.evil.com", "127.0.0", "", null).forEach { assertFalse(DataPolicy.isLoopbackHost(it), it.toString()) }
    }

    @Test fun lanHosts() {
        listOf("mybox.local", "MYBOX.LOCAL.", "10.1.2.3", "192.168.0.9", "172.16.0.1", "172.31.255.1", "169.254.1.1", "127.0.0.1", "localhost", "[::1]").forEach { assertTrue(DataPolicy.isLanHost(it), it) }
        listOf("api.openai.com", "172.32.0.1", "172.15.0.1", "8.8.8.8", "192.169.0.1", "1.2.3", "999.1.1.1", "", null, "10.0.0.1.evil.com", "fd00::1").forEach { assertFalse(DataPolicy.isLanHost(it), it.toString()) }
    }

    @Test fun modeParseFallsBackToTheDefault() {
        assertEquals(DataMode.DEFAULT, DataMode.parse(null)); assertEquals(DataMode.DEFAULT, DataMode.parse("nonsense"))
        assertEquals(DataMode.ANYWHERE, DataMode.parse("ANYWHERE"))
    }

    // ---- the ledger ----

    private class MapStore : CounterStore {
        val m = mutableMapOf<String, Long>()
        override fun getLong(key: String, default: Long) = m[key] ?: default
        override fun putLongs(values: Map<String, Long>) { m.putAll(values) }
    }

    @Test fun ledgerCountsAndRollsOverOnALaterDay() {
        var day = 100L; val store = MapStore(); val l = SkippedLedger(store) { day }
        assertEquals(SkippedLedger.Today(0, 0, 0), l.today())
        l.add(Skipped.UPLOAD_QUEUED); l.add(Skipped.UPLOAD_QUEUED); l.add(Skipped.DROPPED); l.add(Skipped.BULK, 3)
        assertEquals(SkippedLedger.Today(2, 1, 3), l.today())
        day = 101
        assertEquals(SkippedLedger.Today(0, 0, 0), l.today(), "a new day reads zero before any write")
        l.add(Skipped.DROPPED)
        assertEquals(SkippedLedger.Today(0, 1, 0), l.today(), "the write started new counters")
    }

    @Test fun ledgerClockSetBackKeepsCountingIntoTheStoredDay() {
        var day = 100L; val l = SkippedLedger(MapStore()) { day }
        l.add(Skipped.UPLOAD_QUEUED, 5)
        day = 90
        l.add(Skipped.UPLOAD_QUEUED)
        assertEquals(6, l.today().uploadsQueued, "never a free reset")
    }
}
