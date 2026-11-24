package com.rokid.context.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Slice 2: the per-day cap decision table, the mobile-data ledger, "Send once" and the hotspot switch. */
class DailyCapsTest {
    private val mb = 1024L * 1024L
    private fun decide(kind: DataKind, cost: NetworkCost, s: DataSaverSettings, usage: DayUsage = DayUsage.ZERO, once: Boolean = false, reach: EndpointReach = EndpointReach.REMOTE) =
        DataPolicy.decide(kind, reach, cost, if (cost == NetworkCost.FREE) Transport.WIFI else Transport.CELLULAR, s, usage, once)
    private val full = DayUsage(10 * mb, 30)

    // ---- default user: unlimited ----

    @Test fun aDefaultUserHasNoLimitAnywhereWhateverHeHasUsed() {
        val s = DataSaverSettings()
        val huge = DayUsage(10_000 * mb, 100_000)
        for (cost in NetworkCost.entries) assertNull(DailyCaps.limitFor(s, cost), "default settings, $cost")
        assertTrue(decide(DataKind.PHOTO, NetworkCost.HOME_MOBILE, s, huge).allowed)
        assertTrue(decide(DataKind.BULK_OTHER, NetworkCost.HOME_MOBILE, s, huge).allowed)
        assertTrue(decide(DataKind.PHOTO, NetworkCost.FREE, s, huge).allowed)
        // the same decisions as slice 1 for a default user: roaming and unknown stay blocked, with the slice-1 reasons
        assertEquals(DataDecision(false, DataPolicy.R_ROAMING), decide(DataKind.PHOTO, NetworkCost.ROAMING, s, huge))
        assertEquals(DataDecision(false, DataPolicy.R_UNKNOWN), decide(DataKind.PHOTO, NetworkCost.UNKNOWN, s, huge))
    }

    @Test fun anywhereAtHomeIsAlsoUnlimitedUntilTheUserAsksForALimit() {
        val s = DataSaverSettings(mode = DataMode.ANYWHERE)
        assertNull(DailyCaps.limitFor(s, NetworkCost.HOME_MOBILE))
        assertNull(DailyCaps.limitFor(s, NetworkCost.FREE))
        assertTrue(decide(DataKind.PHOTO, NetworkCost.HOME_MOBILE, s, DayUsage(10_000 * mb, 9_999)).allowed)
    }

    // ---- abroad / roaming cap ----

    @Test fun abroadCapAppliesOnlyWhenAbroadOrRoamingAndTheModeIsAnywhere() {
        val any = DataSaverSettings(mode = DataMode.ANYWHERE)
        assertEquals(DailyLimit(10 * mb, 30), DailyCaps.limitFor(any, NetworkCost.ROAMING))
        assertEquals(DailyLimit(10 * mb, 30), DailyCaps.limitFor(any, NetworkCost.UNKNOWN), "unknown is treated as roaming")
        assertEquals(DailyLimit(10 * mb, 30), DailyCaps.limitFor(any.copy(abroad = true), NetworkCost.ROAMING))
        assertNull(DailyCaps.limitFor(any.copy(abroad = true), NetworkCost.FREE), "never on Wi-Fi")
        assertNull(DailyCaps.limitFor(any.copy(abroad = true), NetworkCost.OFFLINE))
        // other modes: roaming photos are blocked by the mode itself, there is nothing to cap
        assertNull(DailyCaps.limitFor(DataSaverSettings(mode = DataMode.WIFI_AND_HOME_MOBILE), NetworkCost.ROAMING))
        assertNull(DailyCaps.limitFor(DataSaverSettings(mode = DataMode.WIFI_ONLY, abroad = true), NetworkCost.ROAMING))
    }

    @Test fun roamingAnywhereGoesUnderTheCapThenParksPhotosAndBulkButNotText() {
        val s = DataSaverSettings(mode = DataMode.ANYWHERE, abroad = true)
        assertTrue(decide(DataKind.PHOTO, NetworkCost.ROAMING, s, DayUsage(10 * mb - 1, 29)).allowed)
        assertEquals(DataDecision(false, DataPolicy.R_DAILY_LIMIT), decide(DataKind.PHOTO, NetworkCost.ROAMING, s, DayUsage(10 * mb, 3)), "byte limit")
        assertEquals(DataDecision(false, DataPolicy.R_DAILY_LIMIT), decide(DataKind.PHOTO, NetworkCost.ROAMING, s, DayUsage(mb, 30)), "photo limit")
        assertEquals(DataDecision(false, DataPolicy.R_DAILY_LIMIT), decide(DataKind.BULK_OTHER, NetworkCost.ROAMING, s, DayUsage(10 * mb, 0)))
        assertTrue(decide(DataKind.BULK_OTHER, NetworkCost.ROAMING, s, DayUsage(mb, 30)).allowed, "the photo count does not stop a big non-photo job")
        assertTrue(decide(DataKind.SMALL, NetworkCost.ROAMING, s, full).allowed, "text still goes at the limit")
        assertFalse(decide(DataKind.SMALL, NetworkCost.ROAMING, s.copy(smallOnRoaming = false), full).allowed, "unless the user turned small requests off")
        assertTrue(DailyCaps.reached(DailyCaps.limitFor(s, NetworkCost.ROAMING), full))
    }

    // ---- the user's own limit ----

    @Test fun theUsersOwnLimitAppliesOnHomeMobileDataToo() {
        val s = DataSaverSettings(dailyLimitOn = true, limitMb = 5, limitPhotos = 10)
        assertEquals(DailyLimit(5 * mb, 10), DailyCaps.limitFor(s, NetworkCost.HOME_MOBILE))
        assertNull(DailyCaps.limitFor(s, NetworkCost.FREE), "never on Wi-Fi")
        assertTrue(decide(DataKind.PHOTO, NetworkCost.HOME_MOBILE, s, DayUsage(5 * mb - 1, 9)).allowed)
        assertEquals(DataDecision(false, DataPolicy.R_DAILY_LIMIT), decide(DataKind.PHOTO, NetworkCost.HOME_MOBILE, s, DayUsage(5 * mb, 0)))
        assertEquals(DataDecision(false, DataPolicy.R_DAILY_LIMIT), decide(DataKind.PHOTO, NetworkCost.HOME_MOBILE, s, DayUsage(0, 10)))
        assertTrue(decide(DataKind.PHOTO, NetworkCost.FREE, s, DayUsage(5000 * mb, 5000)).allowed)
        assertEquals(DailyLimit(0, 0), DailyCaps.limitFor(s.copy(limitMb = 0, limitPhotos = 0), NetworkCost.HOME_MOBILE), "0 means nothing on mobile data")
    }

    @Test fun ownLimitAndAbroadCapTakeTheStricterNumbers() {
        val s = DataSaverSettings(mode = DataMode.ANYWHERE, abroad = true, dailyLimitOn = true, limitMb = 50, limitPhotos = 10)
        assertEquals(DailyLimit(10 * mb, 10), DailyCaps.limitFor(s, NetworkCost.ROAMING))
        assertEquals(DailyLimit(5 * mb, 30), DailyCaps.limitFor(s.copy(limitMb = 5, limitPhotos = 100), NetworkCost.ROAMING))
    }

    @Test fun theLimitChoicesIncludeTheDefaults() {
        assertTrue(DailyCaps.DEFAULT_MB in DailyCaps.MB_CHOICES); assertTrue(DailyCaps.DEFAULT_PHOTOS in DailyCaps.PHOTO_CHOICES)
        assertFalse(DataSaverSettings().dailyLimitOn, "the user's limit is OFF by default")
    }

    // ---- send once ----

    @Test fun sendOnceLiftsTheModeAndTheLimitForPhotosOnly() {
        val s = DataSaverSettings(mode = DataMode.WIFI_ONLY)
        assertEquals(DataDecision(false, DataPolicy.R_WIFI_ONLY), decide(DataKind.PHOTO, NetworkCost.HOME_MOBILE, s))
        assertEquals(DataDecision(true, DataPolicy.R_SEND_ONCE), decide(DataKind.PHOTO, NetworkCost.HOME_MOBILE, s, once = true))
        assertEquals(DataDecision(true, DataPolicy.R_SEND_ONCE), decide(DataKind.PHOTO, NetworkCost.ROAMING, s, once = true))
        assertEquals(DataDecision(true, DataPolicy.R_SEND_ONCE), decide(DataKind.PHOTO, NetworkCost.RESTRICTED, s, once = true))
        assertFalse(decide(DataKind.BULK_OTHER, NetworkCost.ROAMING, s, once = true).allowed, "bulk is never sent once")
        val capped = DataSaverSettings(mode = DataMode.ANYWHERE, abroad = true)
        assertEquals(DataDecision(false, DataPolicy.R_DAILY_LIMIT), decide(DataKind.PHOTO, NetworkCost.ROAMING, capped, full))
        assertEquals(DataDecision(true, DataPolicy.R_SEND_ONCE), decide(DataKind.PHOTO, NetworkCost.ROAMING, capped, full, once = true))
        assertEquals(DataDecision(true, DataPolicy.R_ANYWHERE), decide(DataKind.PHOTO, NetworkCost.ROAMING, capped, DayUsage.ZERO, once = true), "not blocked: no override token")
    }

    // ---- hotspot switch ----

    @Test fun wifiMeteredAbroadTreatsEveryWifiAsRoamingOnlyWhileAbroadAndOnlyWhenAsked() {
        val wifi = NetSnapshot(true, Transport.WIFI, validated = true, notMetered = true, notRoaming = true)
        assertEquals(NetworkCost.FREE, NetworkCosts.classify(wifi, abroad = true, wifiMeteredAbroad = false))
        assertEquals(NetworkCost.FREE, NetworkCosts.classify(wifi, abroad = false, wifiMeteredAbroad = true), "the switch does nothing at home")
        assertEquals(NetworkCost.ROAMING, NetworkCosts.classify(wifi, abroad = true, wifiMeteredAbroad = true))
        assertEquals(NetworkCost.ROAMING, NetworkCosts.classify(wifi, DataSaverSettings(abroad = true, wifiMeteredAbroad = true)))
        assertEquals(NetworkCosts.WHY_ABROAD, NetworkCosts.roamingWhy(wifi, abroad = true, wifiMeteredAbroad = true))
        val eth = wifi.copy(transport = Transport.ETHERNET)
        assertEquals(NetworkCost.FREE, NetworkCosts.classify(eth, abroad = true, wifiMeteredAbroad = true), "Ethernet is not a hotspot")
        assertFalse(DataSaverSettings().wifiMeteredAbroad, "off by default")
    }

    // ---- the mobile-data ledger ----

    private class MapStore : CounterStore {
        val m = mutableMapOf<String, Long>()
        override fun getLong(key: String, default: Long) = m[key] ?: default
        override fun putLongs(values: Map<String, Long>) { m.putAll(values) }
    }

    @Test fun ledgerCountsBytesPerKindAndPhotosAndRollsOverOnALaterDay() {
        var day = 100L; val l = MobileDataLedger(MapStore()) { day }
        assertEquals(MobileDataLedger.Today(0, 0, 0, 0), l.today())
        l.add(DataKind.PHOTO, 200_000, 2); l.add(DataKind.PHOTO, 100_000, 1); l.add(DataKind.SMALL, 3_000, 0); l.add(DataKind.BULK_OTHER, 50_000, 0)
        val t = l.today()
        assertEquals(MobileDataLedger.Today(300_000, 50_000, 3_000, 3), t)
        assertEquals(353_000, t.bytes); assertEquals(DayUsage(353_000, 3), t.usage)
        day = 101
        assertEquals(MobileDataLedger.Today(0, 0, 0, 0), l.today(), "a new local day reads zero before any write")
        l.add(DataKind.SMALL, 10, 0)
        assertEquals(MobileDataLedger.Today(0, 0, 10, 0), l.today(), "the first write of the new day started new counters")
    }

    @Test fun ledgerClockSetBackNeverGivesAFreeReset() {
        var day = 100L; val l = MobileDataLedger(MapStore()) { day }
        l.add(DataKind.PHOTO, 9 * mb, 29)
        day = 90 // the user set the clock back
        assertEquals(9 * mb, l.today().photoBytes, "still today's numbers")
        l.add(DataKind.PHOTO, 2 * mb, 1)
        assertEquals(11 * mb, l.today().photoBytes)
        assertEquals(30, l.today().photos)
        assertTrue(DailyCaps.reached(DailyLimit(10 * mb, 30), l.today().usage), "the cap still holds after the clock change")
        day = 102
        assertEquals(0, l.today().bytes, "and the real next day starts clean")
    }

    @Test fun ledgerIgnoresNegativeNumbersAndNoticesOncePerDay() {
        var day = 5L; val l = MobileDataLedger(MapStore()) { day }
        l.add(DataKind.PHOTO, -10, -3)
        assertEquals(0, l.today().bytes); assertEquals(0, l.today().photos)
        assertTrue(l.firstNoticeToday()); assertFalse(l.firstNoticeToday())
        day = 6
        assertTrue(l.firstNoticeToday(), "a new day may notice again"); assertFalse(l.firstNoticeToday())
        day = 3
        assertFalse(l.firstNoticeToday(), "a clock set back does not re-arm it")
    }

    @Test fun ledgerKeysAreCountersOnlyAndDoNotCollideWithTheSkippedLedger() {
        val st = MapStore(); val l = MobileDataLedger(st) { 7 }; val k = SkippedLedger(st) { 7 }
        l.add(DataKind.PHOTO, 1000, 1); k.add(Skipped.DROPPED, 2)
        assertEquals(1000, l.today().photoBytes); assertEquals(2, k.today().dropped)
        assertTrue(st.m.keys.all { it.startsWith("mobile_") || it.startsWith("skipped_") }, st.m.keys.toString())
    }

    // ---- time zone changes (the local day is the cap day) ----

    private fun dayIn(zone: String, at: java.time.Instant) = at.atZone(java.time.ZoneId.of(zone)).toLocalDate().toEpochDay()

    @Test fun flyingWestKeepsTheCapDayInsteadOfGivingAFreeReset() {
        // 2026-11-21 02:00 in Kolkata is still 20 Nov in Los Angeles (the local date goes back one day)
        val t = java.time.ZonedDateTime.of(2026, 11, 21, 2, 0, 0, 0, java.time.ZoneId.of("Asia/Kolkata")).toInstant()
        var zone = "Asia/Kolkata"
        val l = MobileDataLedger(MapStore()) { dayIn(zone, t) }
        l.add(DataKind.PHOTO, 10 * mb, 30)
        assertTrue(DailyCaps.reached(DailyLimit(10 * mb, 30), l.today().usage))
        zone = "America/Los_Angeles"
        assertEquals(10 * mb, l.today().photoBytes, "an earlier local date keeps counting into the stored day")
        l.add(DataKind.SMALL, 1000, 0)
        assertEquals(10 * mb + 1000, l.today().bytes)
        assertTrue(DailyCaps.reached(DailyLimit(10 * mb, 30), l.today().usage), "the cap day is extended, not reset")
    }

    @Test fun flyingEastStartsTheNextDayEarly() {
        val t = java.time.ZonedDateTime.of(2026, 11, 20, 20, 0, 0, 0, java.time.ZoneId.of("America/Los_Angeles")).toInstant()
        var zone = "America/Los_Angeles"
        val l = MobileDataLedger(MapStore()) { dayIn(zone, t) }
        l.add(DataKind.PHOTO, 10 * mb, 30)
        assertTrue(DailyCaps.reached(DailyLimit(10 * mb, 30), l.today().usage))
        zone = "Asia/Kolkata" // the same instant is already 21 Nov there
        assertEquals(0, l.today().bytes, "a later local date is a new day: counters read zero")
        assertFalse(DailyCaps.reached(DailyLimit(10 * mb, 30), l.today().usage))
    }

    @Test fun aDstChangeInsideTheDayNeitherResetsNorDoublesTheDay() {
        val zone = java.time.ZoneId.of("Europe/London")
        // 2026-10-25 has 25 hours (clocks go back); 2026-03-29 has 23. The local date is the key either side of the change.
        val before = java.time.ZonedDateTime.of(2026, 10, 25, 0, 30, 0, 0, zone).toInstant()
        val after = before.plusSeconds(2 * 3600) // the same local date, after the repeated hour
        var now = before
        val l = MobileDataLedger(MapStore()) { now.atZone(zone).toLocalDate().toEpochDay() }
        l.add(DataKind.PHOTO, 5 * mb, 3)
        now = after
        assertEquals(5 * mb, l.today().photoBytes, "same local date across the change")
        now = java.time.ZonedDateTime.of(2026, 10, 26, 0, 1, 0, 0, zone).toInstant()
        assertEquals(0, l.today().bytes)
    }

    // ---- Codex r4 P1-3: reads never wait for a reservation's disk commit ----

    /** A store whose durable commit blocks until released (a slow disk) and which is safe for concurrent readers, like SharedPreferences. */
    private class BlockingStore : CounterStore {
        val m = java.util.concurrent.ConcurrentHashMap<String, Long>()
        @Volatile var block = false
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        override fun getLong(key: String, default: Long) = m[key] ?: default
        override fun putLongs(values: Map<String, Long>) { m.putAll(values) }
        override fun putLongsDurable(values: Map<String, Long>): Boolean {
            m.putAll(values)
            if (block) { entered.countDown(); release.await() }
            return true
        }
    }

    @Test fun aReadReturnsWhileAReservationsDiskCommitIsBlocked() {
        val st = BlockingStore(); val l = MobileDataLedger(st) { 7 }
        l.add(DataKind.SMALL, 100, 0)
        st.block = true
        val writer = Thread { l.reserve(DataKind.PHOTO, 5_000, 1) }.also { it.start() }
        assertTrue(st.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the commit started")
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val read = reader.submit<Triple<Long, Long, Boolean>> { Triple(l.today().bytes, l.dayNow(), true) }
            val got = read.get(5, java.util.concurrent.TimeUnit.SECONDS) // would time out if the reader waited for the commit lock
            assertEquals(7L, got.second)
            assertTrue(got.first >= 100)
        } finally { st.release.countDown(); writer.join(5_000); reader.shutdownNow() }
    }

    @Test fun concurrentReservationsAreAllCountedAndTheCapStillRefuses() {
        val st = BlockingStore(); val l = MobileDataLedger(st) { 7 }
        val n = 40; val each = 1_000L
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val tokens = (1..n).map { pool.submit<DataReservation> { l.reserve(DataKind.PHOTO, each, 1) } }.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(tokens.none { it.failed })
            assertEquals(DayUsage(n * each, n.toLong()), l.today().usage, "no reservation was lost")
            val cap = DailyLimit(n * each, n.toLong())
            assertTrue(DailyCaps.reached(cap, l.today().usage), "the cap is reached at exactly the reserved total")
            assertFalse(DataPolicy.decide(DataKind.PHOTO, EndpointReach.REMOTE, NetworkCost.HOME_MOBILE, Transport.CELLULAR, DataSaverSettings(dailyLimitOn = true, limitMb = 1, limitPhotos = n), l.today().usage, false).allowed)
        } finally { pool.shutdownNow() }
    }
}
