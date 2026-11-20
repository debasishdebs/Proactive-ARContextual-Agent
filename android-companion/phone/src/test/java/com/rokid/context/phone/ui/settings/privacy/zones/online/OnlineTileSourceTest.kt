package com.rokid.context.phone.ui.settings.privacy.zones.online

import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.log.Route
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.TileKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** A scripted network: each call takes the next answer (or the last one); every URL asked is recorded. */
private class FakeTransport(private val answers: MutableList<() -> TileResponse>) : TileTransport {
    val urls = mutableListOf<String>()
    var cancelled = 0
    override fun get(url: String, userAgent: String): TileResponse { synchronized(urls) { urls += url }; return (if (answers.size > 1) answers.removeAt(0) else answers[0])() }
    override fun cancelAll() { cancelled++ }
}

private fun png(extra: Int = 0) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13) + ByteArray(extra)
private fun ok(b: ByteArray = png()) = { TileResponse(200, body = b) }

class OnlineTileSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = "k3yABC123xyz"
    private var now = 1_000_000L
    private val lines = mutableListOf<Line>()

    @Before fun logs() { PcLog.testOnlyResetNeverCallFromProduction(); PcLog.install(object : LogSink { override fun emit(line: Line, route: Route) { synchronized(lines) { lines += line } } }); PcLog.setDiagnostic(true) }
    @After fun reset() { PcLog.testOnlyResetNeverCallFromProduction() }

    private fun source(t: FakeTransport, provider: OnlineProvider = OnlineProvider.mapTiler(key), cap: Int = 400, maxBytes: Long = OnlineTileDiskCache.MAX_BYTES, started: Boolean = true) =
        OnlineTileSource(provider, t, OnlineTileDiskCache(File(tmp.root, "c"), provider.cacheId, maxBytes, clock = { now }), "TestAgent/1", clock = { now }, sessionCap = cap)
            .also { if (started) it.start() }

    private val k1 = TileKey(15, 23_456, 14_321)
    private val k2 = TileKey(15, 23_457, 14_321)

    // ---- provider and the host allowlist

    @Test fun mapTilerWithAKey_osmOnlyInADebugBuildWithoutOne_nothingInAKeylessRelease() {
        assertEquals(OnlineProvider.MAPTILER_HOST, OnlineProvider.select(key, debug = false)!!.host)
        assertEquals(OnlineProvider.MAPTILER_HOST, OnlineProvider.select(key, debug = true)!!.host)
        assertEquals(OnlineProvider.OSM_HOST, OnlineProvider.select("", debug = true)!!.host)
        assertEquals(OnlineProvider.OSM_HOST, OnlineProvider.select("  ", debug = true)!!.host)
        assertNull("a release build without a key shows the grid only", OnlineProvider.select("", debug = false))
        assertNull(OnlineProvider.select("   ", debug = false))
    }

    @Test fun theKeyIsNeverInToString_andTheUrlIsHttpsOnTheProviderHost() {
        val p = OnlineProvider.mapTiler(key)
        assertFalse(p.toString().contains(key))
        val u = p.url(k1)
        assertTrue(u.startsWith("https://api.maptiler.com/maps/streets-v2/256/15/23456/14321.png?key="))
        assertEquals("https://tile.openstreetmap.org/15/23456/14321.png", OnlineProvider.osmDebug().url(k1))
    }

    @Test fun anImageIsFetchedAndThenServedFromTheDiskCacheWithoutAnotherRequest() {
        val t = FakeTransport(mutableListOf(ok()))
        val s = source(t)
        assertArrayEquals(png(), s.tile(k1))
        assertArrayEquals(png(), s.tile(k1))
        assertEquals(1, t.urls.size)
    }

    @Test fun aRedirectToAnotherHostIsRefused() {
        val t = FakeTransport(mutableListOf({ TileResponse(302, location = "https://evil.example/t.png") }, ok()))
        assertNull(source(t).tile(k1))
        assertEquals("the off-host target was never asked", 1, t.urls.size)
    }

    @Test fun aRedirectToPlainHttpIsRefused_evenOnTheSameHost() {
        val t = FakeTransport(mutableListOf({ TileResponse(301, location = "http://api.maptiler.com/maps/x.png") }, ok()))
        assertNull(source(t).tile(k1))
        assertEquals(1, t.urls.size)
    }

    @Test fun aRedirectWithUserInfoOrAnotherPortIsRefused() {
        for (loc in listOf("https://user:pw@api.maptiler.com/a.png", "https://api.maptiler.com:8443/a.png", "https://api.maptiler.com.evil.example/a.png")) {
            val t = FakeTransport(mutableListOf({ TileResponse(307, location = loc) }, ok()))
            assertNull(loc, source(t).tile(TileKey(14, (loc.length), 3)))
            assertEquals(loc, 1, t.urls.size)
        }
    }

    @Test fun aSameHostHttpsRedirectIsFollowed_aRelativeOneToo() {
        val t = FakeTransport(mutableListOf({ TileResponse(302, location = "/maps/streets-v2/256/15/1/2.png") }, ok()))
        assertNotNull(source(t).tile(k1))
        assertEquals(2, t.urls.size)
        assertTrue(t.urls[1].startsWith("https://api.maptiler.com/"))
    }

    @Test fun redirectLoopsStopAfterTwoHops() {
        val t = FakeTransport(mutableListOf({ TileResponse(302, location = "/again.png") }))
        assertNull(source(t).tile(k1))
        assertEquals(OnlineTileSource.MAX_REDIRECTS + 1, t.urls.size)
    }

    // ---- size, content and zoom limits

    @Test fun aTileOverTheCapIsRefused_aBodyAtTheCapIsKept() {
        val big = FakeTransport(mutableListOf(ok(png(OnlineTileSource.MAX_TILE_BYTES))))
        assertNull(source(big).tile(k1))
        val tooLargeFlag = FakeTransport(mutableListOf({ TileResponse(200, body = null) }))
        assertNull(source(tooLargeFlag).tile(k2))
        val atCap = FakeTransport(mutableListOf(ok(png(OnlineTileSource.MAX_TILE_BYTES - png().size))))
        assertEquals(OnlineTileSource.MAX_TILE_BYTES, source(atCap).tile(TileKey(15, 1, 1))!!.size)
    }

    @Test fun aBodyThatIsNotAnImageIsRefused() {
        val t = FakeTransport(mutableListOf(ok("<html>captive portal</html>".toByteArray())))
        assertNull(source(t).tile(k1))
    }

    @Test fun zoomPastTheMaxAndOutOfRangeNumbersAreNotRequested() {
        val t = FakeTransport(mutableListOf(ok()))
        val s = source(t)
        assertNull(s.tile(TileKey(20, 1, 1))); assertNull(s.tile(TileKey(15, -1, 1))); assertNull(s.tile(TileKey(3, 8, 1))); assertNull(s.tile(TileKey(0, 0, 0)))
        assertEquals(0, t.urls.size)
        assertNotNull(s.tile(TileKey(OnlineTileSource.MAX_ZOOM, 5, 5)))
    }

    // ---- backoff, caps and foreground

    @Test fun a429BacksOffTheWholeSource_thenRetriesAfterTheWait() {
        val t = FakeTransport(mutableListOf({ TileResponse(429) }, ok()))
        val s = source(t)
        assertNull(s.tile(k1))
        assertNull(s.tile(k2)); assertEquals("no request while backing off", 1, t.urls.size)
        now += OnlineTileSource.INITIAL_BACKOFF_MS + 1
        assertNotNull(s.tile(k2)); assertEquals(2, t.urls.size)
    }

    @Test fun a5xxBacksOff_retryAfterIsHonouredAndCapped_andRepeatsDouble() {
        val t = FakeTransport(mutableListOf({ TileResponse(503, retryAfterSec = 20) }, { TileResponse(500) }, { TileResponse(429, retryAfterSec = 100_000) }))
        val s = source(t)
        assertNull(s.tile(k1))
        now += 19_000; assertNull(s.tile(k2)); assertEquals("Retry-After 20 s not over", 1, t.urls.size)
        now += 2_000; assertNull(s.tile(k2)); assertEquals(2, t.urls.size)                   // 500: waits twice the first step (4 s)
        now += 3_000; assertNull(s.tile(TileKey(15, 9, 9))); assertEquals(2, t.urls.size)
        now += 2_000; assertNull(s.tile(TileKey(15, 9, 9))); assertEquals(3, t.urls.size)   // the huge Retry-After is capped
        now += OnlineTileSource.MAX_BACKOFF_MS + 1; assertNull(s.tile(TileKey(15, 8, 8))); assertEquals(4, t.urls.size)
    }

    @Test fun aSuccessResetsTheBackoffSteps() {
        val t = FakeTransport(mutableListOf({ TileResponse(500) }, ok(), { TileResponse(500) }, ok()))
        val s = source(t)
        s.tile(k1); now += 2_001; assertNotNull(s.tile(k2))
        s.tile(TileKey(15, 3, 3)); now += OnlineTileSource.INITIAL_BACKOFF_MS + 1
        assertNotNull("the step is the first again, not doubled", s.tile(TileKey(15, 4, 4)))
    }

    @Test fun theRequestCapIsPerVisit_aPauseAndResumeKeepsItAndANewVisitResetsIt() {
        val t = FakeTransport(mutableListOf(ok()))
        val s = source(t, cap = 3)
        for (i in 1..6) s.tile(TileKey(15, i, 1))
        assertEquals(3, t.urls.size); assertEquals(3, s.requestCount())
        s.stop(); s.start()
        assertNull("same visit: still capped after a resume", s.tile(TileKey(15, 10, 1))); assertEquals(3, t.urls.size)
        s.newVisit()
        assertEquals(0, s.requestCount())
        assertNotNull(s.tile(TileKey(15, 10, 1))); assertEquals(4, t.urls.size)
    }

    @Test fun webpNeedsTheWholeWebpMarker() {
        val riffWebp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()
        val riffWave = "RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray()
        assertTrue(OnlineTileSource.looksLikeImage(riffWebp)); assertFalse(OnlineTileSource.looksLikeImage(riffWave))
        assertFalse(OnlineTileSource.looksLikeImage("RIFF".toByteArray() + ByteArray(4) + "WxxP".toByteArray()))
    }

    @Test fun nothingIsRequestedBeforeStartOrAfterStop_andStopCancelsInFlightRequests() {
        val t = FakeTransport(mutableListOf(ok()))
        val s = source(t, started = false)
        assertNull(s.tile(k1)); assertEquals(0, t.urls.size)
        s.start(); assertNotNull(s.tile(k1)); assertEquals(1, t.urls.size)
        s.stop(); assertEquals(1, t.cancelled)
        assertNull(s.tile(k2)); assertEquals(1, t.urls.size)
        assertNotNull("a cached tile still draws while paused", s.tile(k1))
    }

    /** The switch goes off while a download is in flight: stop (first), then the purge; the answer that arrives afterwards is not stored. */
    @Test fun aTileArrivingAfterSwitchOffIsNotStored() {
        val arrive = java.util.concurrent.CountDownLatch(1); val asked = java.util.concurrent.CountDownLatch(1)
        val t = FakeTransport(mutableListOf({ asked.countDown(); arrive.await(5, java.util.concurrent.TimeUnit.SECONDS); TileResponse(200, body = png()) }))
        val cache = OnlineTileDiskCache(File(tmp.root, "c"), OnlineProvider.mapTiler(key).cacheId, clock = { now })
        val s = OnlineTileSource(OnlineProvider.mapTiler(key), t, cache, "TestAgent/1", clock = { now }).also { it.start() }
        val model = com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapModel(
            com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapStore().also { it.enabled = true; it.consented = true }, true, stop = { s.stop() }, purge = { cache.purge() })
        var got: ByteArray? = png()
        val th = Thread { got = s.tile(k1) }.also { it.start() }
        assertTrue(asked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        model.requestEnabled(false)
        arrive.countDown(); th.join(5000)
        assertNull(got)
        assertNull("nothing stored after switch-off", cache.get(k1))
        assertFalse("the cache folder was not recreated", File(tmp.root, "c").exists())
    }

    @Test fun aNetworkErrorGivesNothingAndIsNotRetriedAtOnce() {
        val t = FakeTransport(mutableListOf({ throw IOException("offline") }))
        val s = source(t)
        assertNull(s.tile(k1)); assertNull(s.tile(k1))
        assertEquals(1, t.urls.size)
    }

    // ---- disk cache

    @Test fun theDiskCacheIsCappedAndEvictsTheLeastRecentlyUsed() {
        val c = OnlineTileDiskCache(File(tmp.root, "d"), "p", maxBytes = 3 * (100 + 8L), clock = { now })
        val a = TileKey(15, 1, 1); val b = TileKey(15, 2, 1); val d = TileKey(15, 3, 1); val e = TileKey(15, 4, 1)
        c.put(a, ByteArray(100)); now += 10_000; c.put(b, ByteArray(100)); now += 10_000; c.put(d, ByteArray(100)); now += 10_000
        assertNotNull(c.get(a)); now += 10_000                        // a is now the most recently used
        c.put(e, ByteArray(100))
        assertTrue(c.sizeBytes() <= 3 * 108)
        assertNull("b was the least recently used", c.get(b)); assertNotNull(c.get(a)); assertNotNull(c.get(e))
    }

    @Test fun theCacheCapIsFiftyMegabytes() { assertEquals(50L * 1024 * 1024, OnlineTileDiskCache.MAX_BYTES) }

    @Test fun purgeDeletesEveryTile_andOldTilesExpire() {
        val root = File(tmp.root, "e")
        val c = OnlineTileDiskCache(root, "p", clock = { now }, ttlMs = 1_000)
        c.put(k1, ByteArray(50)); assertNotNull(c.get(k1))
        now += 1_001; assertNull(c.get(k1))
        c.put(k1, ByteArray(50)); c.put(k2, ByteArray(50)); assertTrue(root.exists())
        c.purge()
        assertFalse(root.exists()); assertNull(c.get(k1))
        c.put(k1, ByteArray(10)); assertNotNull("usable again after a purge", c.get(k1))
    }

    // ---- logs

    @Test fun noLogLineHoldsACoordinateAZoomAUrlOrTheKey() {
        val t = FakeTransport(mutableListOf({ TileResponse(302, location = "https://evil.example/15/23456/14321.png?key=$key") }, { TileResponse(429) }, { TileResponse(500) }, ok("not an image at all".toByteArray()), { throw IOException("boom 15/23456/14321") }, { TileResponse(200, body = null) }, { TileResponse(404) }))
        val s = source(t, cap = 6, maxBytes = 1)
        repeat(8) { i -> s.tile(TileKey(15, 23_456 + i, 14_321)); now += 100_000 }
        assertTrue("some events were logged", lines.isNotEmpty())
        for (l in lines) {
            assertTrue("literal event only: ${l.msg}", Regex("^[a-z ]+$").matches(l.msg))
            for (bad in listOf(key, "23456", "14321", "15/", "http", "maptiler", "?key")) assertFalse("${l.msg} has $bad", l.msg.contains(bad, ignoreCase = true))
        }
    }

    @Test fun theUserAgentCarriesNoEmailOrPersonalData() {
        val ua = com.rokid.context.connectors.place.UrlConnectionPlaceHttp.DEFAULT_USER_AGENT
        assertFalse("@" in ua); assertTrue(ua.startsWith("ProactiveContextAgent/"))
    }

    // ---- confinement

    @Test fun onlyTheOnlinePackageNamesNetworkClasses_andItsLogLinesAreLiteral() {
        val base = "src/main/java/com/rokid/context/phone/ui/settings/privacy/zones"
        val online = File("$base/online").listFiles { f -> f.extension == "kt" }.orEmpty().toList()
        assertTrue(online.isNotEmpty())
        val logs = online.flatMap { f -> f.readLines().filter { "PcLog." in it } }
        assertTrue(logs.isNotEmpty())
        for (l in logs) assertTrue("literal only: $l", Regex("""PcLog\.i\(TAG, "[a-z ]+"\)""").containsMatchIn(l))
        // no other file of the map names the network (the offline map scan, MapNoNetworkTest, covers tiles/, ZonesScreen.kt and MapViewport.kt)
        val others = listOf("ZonesModel.kt").map { File("$base/$it") }
        assertTrue(others.none { f -> f.readText().contains("java.net") })
    }

    @Test fun offlineOnlyRequestsNoTileButStillShowsTilesAlreadyOnThePhone() {
        val t = FakeTransport(mutableListOf(ok()))
        var off = false
        val provider = OnlineProvider.mapTiler(key)
        val src = OnlineTileSource(provider, t, OnlineTileDiskCache(File(tmp.root, "c2"), provider.cacheId, OnlineTileDiskCache.MAX_BYTES, clock = { now }), "TestAgent/1", clock = { now }, offlineOnly = { off }).also { it.start() }
        assertNotNull(src.tile(k1)); assertEquals(1, t.urls.size) // fetched and cached under Automatic
        off = true
        assertNotNull("a cached tile is still shown", src.tile(k1))
        assertNull("a new tile is not requested", src.tile(k2)); assertEquals(1, t.urls.size)
        off = false; assertNotNull(src.tile(k2)); assertEquals(2, t.urls.size)
    }

    @Test fun aRequestThatWaitedForASlotIsJudgedAgainAndNeverDownloadsAfterTheSwitch() {
        val provider = OnlineProvider.mapTiler(key)
        var off = false
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val urls = mutableListOf<String>()
        val transport = object : TileTransport {
            override fun get(url: String, userAgent: String): TileResponse { synchronized(urls) { urls += url }; entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); return TileResponse(200, body = png()) }
            override fun cancelAll() {}
        }
        val src = OnlineTileSource(provider, transport, OnlineTileDiskCache(File(tmp.root, "c4"), provider.cacheId, OnlineTileDiskCache.MAX_BYTES, clock = { now }), "TestAgent/1", clock = { now }, maxConcurrent = 1, offlineOnly = { off }).also { it.start() }
        val first = java.util.concurrent.Executors.newSingleThreadExecutor().submit<ByteArray?> { src.tile(k1) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) // the first request holds the only slot
        val second = java.util.concurrent.Executors.newSingleThreadExecutor().submit<ByteArray?> { src.tile(k2) } // queued behind it
        Thread.sleep(200)
        off = true; release.countDown() // the switch comes while the second one waits
        assertNotNull(first.get(5, java.util.concurrent.TimeUnit.SECONDS)); assertNull("the queued request is refused after the switch", second.get(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, urls.size)
    }

    @Test fun anUnreadableOfflineOnlyReaderFailsClosed() {
        val t = FakeTransport(mutableListOf(ok()))
        val provider = OnlineProvider.mapTiler(key)
        val src = OnlineTileSource(provider, t, OnlineTileDiskCache(File(tmp.root, "c3"), provider.cacheId, OnlineTileDiskCache.MAX_BYTES, clock = { now }), "TestAgent/1", clock = { now }, offlineOnly = { error("prefs") }).also { it.start() }
        assertNull(src.tile(k1)); assertEquals(0, t.urls.size)
    }

    // ---- data saver: the same decision function and caps as photos, and the byte ledger

    /** A gate built from the real policy: [cost] is the network class, [settings] the user's choices, bytes go to a real [com.rokid.context.core.net.MobileDataLedger]. */
    private inner class PolicyGate(var cost: com.rokid.context.core.net.NetworkCost, var settings: com.rokid.context.core.net.DataSaverSettings = com.rokid.context.core.net.DataSaverSettings()) : com.rokid.context.core.net.DataGate {
        val store = object : com.rokid.context.core.net.CounterStore {
            val m = HashMap<String, Long>()
            override fun getLong(key: String, default: Long) = m[key] ?: default
            override fun putLongs(values: Map<String, Long>) { m.putAll(values) }
        }
        val ledger = com.rokid.context.core.net.MobileDataLedger(store) { 20_000L }
        override fun decide(kind: com.rokid.context.core.net.DataKind, reach: com.rokid.context.core.net.EndpointReach) =
            com.rokid.context.core.net.DataPolicy.decide(kind, reach, cost, com.rokid.context.core.net.Transport.CELLULAR, settings, ledger.today().usage)
        override fun skipped(kind: com.rokid.context.core.net.DataKind, what: com.rokid.context.core.net.Skipped, d: com.rokid.context.core.net.DataDecision) {}
        override fun costlyNow() = com.rokid.context.core.net.NetworkCosts.isCostly(cost)
        override fun sentAt(kind: com.rokid.context.core.net.DataKind, txBytes: Long, rxBytes: Long, photos: Int, costlyAtStart: Boolean) { if (costlyAtStart) ledger.add(kind, txBytes + rxBytes, photos) }
    }

    private fun gated(t: FakeTransport, g: com.rokid.context.core.net.DataGate, dir: String): OnlineTileSource {
        val provider = OnlineProvider.mapTiler(key)
        return OnlineTileSource(provider, t, OnlineTileDiskCache(File(tmp.root, dir), provider.cacheId, OnlineTileDiskCache.MAX_BYTES, clock = { now }), "TestAgent/1", clock = { now }, dataGate = g).also { it.start() }
    }

    @Test fun aDefaultUserOnHomeMobileDataStillFetchesTiles_andTheBytesAreCounted() {
        val t = FakeTransport(mutableListOf(ok(png(100))))
        val g = PolicyGate(com.rokid.context.core.net.NetworkCost.HOME_MOBILE)
        val src = gated(t, g, "g1")
        assertNotNull(src.tile(k1)); assertEquals(1, t.urls.size)
        assertEquals("the received bytes (12 header + 100) are counted as big-download bytes", 112L, g.ledger.today().bulkBytes)
    }

    @Test fun onWifiTilesAreFetchedAndNotCounted() {
        val t = FakeTransport(mutableListOf(ok()))
        val g = PolicyGate(com.rokid.context.core.net.NetworkCost.FREE)
        assertNotNull(gated(t, g, "g2").tile(k1)); assertEquals(0L, g.ledger.today().bytes)
    }

    @Test fun onRoamingOnlyCachedTilesAreShown_noRequestIsMade() {
        val t = FakeTransport(mutableListOf(ok()))
        val g = PolicyGate(com.rokid.context.core.net.NetworkCost.FREE)
        val src = gated(t, g, "g3")
        assertNotNull(src.tile(k1)); assertEquals(1, t.urls.size) // cached on Wi-Fi
        g.cost = com.rokid.context.core.net.NetworkCost.ROAMING
        assertNotNull("a cached tile is still shown", src.tile(k1))
        assertNull("a new tile is not requested on roaming", src.tile(k2)); assertEquals(1, t.urls.size)
        g.cost = com.rokid.context.core.net.NetworkCost.UNKNOWN
        assertNull(src.tile(k2)); assertEquals(1, t.urls.size)
        g.cost = com.rokid.context.core.net.NetworkCost.FREE
        assertNotNull(src.tile(k2)); assertEquals(2, t.urls.size)
    }

    @Test fun onHomeMobileDataTheUsersOwnLimitStopsTilesOnceUsedUp() {
        val t = FakeTransport(mutableListOf(ok(png(2_000))))
        val g = PolicyGate(com.rokid.context.core.net.NetworkCost.HOME_MOBILE, com.rokid.context.core.net.DataSaverSettings(dailyLimitOn = true, limitMb = 0))
        val src = gated(t, g, "g4")
        assertNull("a limit of 0 MB is already used up", src.tile(k1)); assertEquals(0, t.urls.size)
        g.settings = com.rokid.context.core.net.DataSaverSettings(dailyLimitOn = true, limitMb = 5)
        assertNotNull(src.tile(k1)); assertEquals(1, t.urls.size)
    }

    @Test fun anAbroadUserWithTheDefaultModeGetsCachedTilesOnly() {
        val t = FakeTransport(mutableListOf(ok()))
        val g = PolicyGate(com.rokid.context.core.net.NetworkCost.ROAMING, com.rokid.context.core.net.DataSaverSettings(abroad = true))
        assertNull(gated(t, g, "g5").tile(k1)); assertEquals(0, t.urls.size)
    }

    @Test fun anUnreadableDataGateFailsClosed() {
        val t = FakeTransport(mutableListOf(ok()))
        val bad = object : com.rokid.context.core.net.DataGate {
            override fun decide(kind: com.rokid.context.core.net.DataKind, reach: com.rokid.context.core.net.EndpointReach): com.rokid.context.core.net.DataDecision = error("prefs")
            override fun skipped(kind: com.rokid.context.core.net.DataKind, what: com.rokid.context.core.net.Skipped, d: com.rokid.context.core.net.DataDecision) {}
            override fun costlyNow() = false
        }
        assertNull(gated(t, bad, "g6").tile(k1)); assertEquals(0, t.urls.size)
    }
}
