package com.rokid.context.connectors.place

import com.rokid.context.memory.place.PlaceNetGuard
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A 429/503 pauses EVERY later request (any host, any cell) and the diagnostics carry tokens and numbers only. */
class PlaceHttpGlobalPauseTest {
    private class Script(val code: Int = 200, val retryAfter: String? = null, val timeout: Boolean = false)

    private class Conn(u: URL, private val s: Script) : HttpURLConnection(u) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int { if (s.timeout) throw SocketTimeoutException("read timed out"); return s.code }
        override fun getHeaderField(name: String?): String? = if (name.equals("Retry-After", true)) s.retryAfter else null
        override fun getContentEncoding(): String? = null
        override fun getInputStream() = java.io.ByteArrayInputStream("{}".toByteArray())
    }

    private var now = 1_000_000L
    private val slept = mutableListOf<Long>()
    private val logs = mutableListOf<String>()
    private val script = ArrayDeque<Script>()
    private val startedAt = mutableListOf<Long>()
    private val guard = PlaceNetGuard(clock = { now }, sleep = { now += it; slept += it }, log = { logs += it })
    private val http = UrlConnectionPlaceHttp(minGapMs = 0, clock = { now }, guard = guard, open = { startedAt += now; Conn(it, script.removeFirst()) })

    private fun get(url: String) = runBlocking { http.get(url) }

    @Test fun retryAfterPausesTheNextRequestToAnotherHostAndServiceToo() {
        script += Script(429, "7"); script += Script(200)
        val e = assertFailsWith<PlaceHttpException> { get("https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gsbbox=17.4%7C78.4") }
        assertEquals(429, e.code); assertEquals(7, e.retryAfterS)
        get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=Q1") // a different service and host: still waits
        assertEquals(7_000L, slept.sum())
        assertEquals(startedAt[0] + 7_000L, startedAt[1])
        assertTrue(logs.any { it == "place req failed svc=wiki_geosearch status=429 cls=http attempt=1 retryAfterS=7 waitMs=7000 ms=0" }, logs.toString())
        assertTrue(logs.any { it.startsWith("place pause group=wikimedia status=429 waitMs=7000") })
    }

    @Test fun a503WithoutRetryAfterBacksOffTenTwentyFortyAndASuccessResets() {
        script += Script(503); script += Script(503); script += Script(503); script += Script(200); script += Script(503); script += Script(200)
        repeat(3) { assertFailsWith<PlaceHttpException> { get("https://overpass-api.de/api/interpreter") } }
        get("https://overpass-api.de/api/interpreter")
        assertFailsWith<PlaceHttpException> { get("https://overpass-api.de/api/interpreter") }
        get("https://overpass-api.de/api/interpreter")
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 0L, 10_000L), startedAt.zipWithNext { x, y -> y - x })
        assertEquals(80_000L, slept.sum())
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L), listOf(1, 2, 3, 4, 5, 9).map { PlaceNetGuard.backoffMs(it) })
    }

    @Test fun aTimeoutIsLoggedAsATimeoutAndDoesNotPauseAnyone() {
        script += Script(timeout = true); script += Script(200)
        assertFailsWith<SocketTimeoutException> { get("https://en.wikipedia.org/w/api.php?action=query&prop=extracts") }
        get("https://en.wikipedia.org/w/api.php?action=query&prop=extracts")
        assertEquals(0L, slept.sum())
        assertTrue(logs.any { it.startsWith("place req failed svc=wiki_extract status=0 cls=timeout attempt=1 retryAfterS=-1 waitMs=0") }, logs.toString())
    }

    @Test fun logsNeverCarryTheUrlTheQueryOrACoordinate() {
        script += Script(429, "2")
        assertFailsWith<PlaceHttpException> { get("https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gsbbox=17.4123%7C78.4456") }
        assertFalse(logs.any { it.contains("wikipedia") || it.contains("http") && it.contains("://") || it.contains("17.41") || it.contains("gsbbox") })
    }

    @Test fun anOverpass503DoesNotPauseWikipediaOrWikidata() {
        script += Script(503); script += Script(200); script += Script(200)
        assertFailsWith<PlaceHttpException> { get("https://overpass-api.de/api/interpreter") }
        get("https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gsbbox=1")
        get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=Q1")
        assertEquals(0L, slept.sum())
        assertTrue(logs.any { it.startsWith("place pause group=overpass status=503 waitMs=10000") }, logs.toString())
    }

    @Test fun aWikipedia429PausesWikipediaAndWikidataButNotOverpass() {
        script += Script(429, "5"); script += Script(200); script += Script(200); script += Script(200)
        assertFailsWith<PlaceHttpException> { get("https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gsbbox=1") }
        get("https://overpass-api.de/api/interpreter")
        assertEquals(0L, slept.sum())
        get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=Q1") // same group: waits
        assertEquals(5_000L, slept.sum())
    }

    @Test fun aPauseSetWhileWaitingForTheHostGapStillHoldsTheRequest() {
        val gapHttp = UrlConnectionPlaceHttp(minGapMs = 1_000, clock = { now }, guard = guard, pause = { w -> if (startedAt.size == 1) guard.failed("wiki_extract", 429, "http", 5, 0, 1); now += w },
            open = { startedAt += now; Conn(it, script.removeFirst()) })
        script += Script(200); script += Script(200)
        runBlocking { gapHttp.get("https://en.wikipedia.org/w/api.php?action=query&prop=extracts"); gapHttp.get("https://en.wikipedia.org/w/api.php?action=query&prop=extracts") }
        assertTrue(startedAt[1] - startedAt[0] >= 5_000L, startedAt.toString()) // the 5 s pause set during the 1 s gap wait is honoured before dispatch
    }

    @Test fun userAgentCarriesThePublicRepositoryAsContact() {
        assertEquals("ProactiveContextAgent/1.1 (https://github.com/debasishdebs/Proactive-ARContextual-Agent; contact: see repository)", UrlConnectionPlaceHttp.DEFAULT_USER_AGENT)
    }
}
