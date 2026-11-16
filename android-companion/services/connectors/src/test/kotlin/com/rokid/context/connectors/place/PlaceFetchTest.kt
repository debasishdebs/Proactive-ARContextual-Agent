package com.rokid.context.connectors.place

import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlaceKinds
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String): String = requireNotNull(PlaceFetchTest::class.java.getResourceAsStream("/place/$name")) { name }.bufferedReader().use { it.readText() }

/** Serves the recorded-shape fixtures by endpoint and remembers every request. The network is never touched. */
private class FixtureHttp(val site: String, val overpassFails: Boolean = false, val wikidataFails: Boolean = false) : PlaceHttp {
    val gets = mutableListOf<String>()
    val posts = mutableListOf<Pair<String, Map<String, String>>>()
    override suspend fun get(url: String): String {
        gets += url
        return when {
            "list=geosearch" in url -> fixture("wikipedia_geosearch_$site.json")
            "prop=extracts" in url -> fixture("wikipedia_pages_$site.json")
            "wbgetentities" in url -> if (wikidataFails) throw PlaceHttpException(503) else fixture("wikidata_entities_$site.json")
            else -> error("unexpected $url")
        }
    }
    override suspend fun postForm(url: String, form: Map<String, String>): String {
        posts += url to form
        if (overpassFails) throw IOException("down")
        return if (site == "golconda") fixture("overpass_golconda.json") else """{"elements":[]}"""
    }
}

private fun source(h: FixtureHttp, logs: MutableList<String> = mutableListOf()) =
    PlaceCellSource(WikipediaHttpSource(h), WikidataHttpSource(h), OverpassHttpSource(h)) { logs += it }

class PlaceFetchTest {
    private val charminarCell = Geohash5.encode(17.361944, 78.474722)
    private val golcondaCell = Geohash5.encode(17.3833, 78.4011)

    @Test fun charminarCellKeepsMonumentsAndNeverPeopleOrMarkets() = runTest {
        val h = FixtureHttp("charminar")
        val got = source(h).fetchCell(charminarCell)
        assertTrue(got.complete)
        assertEquals(listOf("wd:Q9000001", "wd:Q9000003"), got.places.map { it.id })
        val c = got.places.first()
        assertEquals("Charminar", c.name); assertEquals(PlaceKinds.MONUMENT, c.kind); assertEquals("wikipedia:en", c.source)
        assertEquals(17.361944, c.lat, 1e-9); assertTrue(c.summary.startsWith("Charminar is a monument"))
        assertEquals(PlaceKinds.MOSQUE, got.places[1].kind)
        assertTrue(got.places.none { it.name.contains("Mahboob") || it.name.contains("Laad") })
    }

    @Test fun golcondaHierarchyFromPartOfAndHasPartAndOverpassJoin() = runTest {
        val got = source(FixtureHttp("golconda")).fetchCell(golcondaCell)
        val byId = got.places.associateBy { it.id }
        assertEquals(setOf("wd:Q9000011", "wd:Q9000012", "wd:Q9000013", "osm:n777"), byId.keys)
        assertNull(byId["wd:Q9000011"]!!.parentId)
        assertEquals("wd:Q9000011", byId["wd:Q9000012"]!!.parentId) // P361
        assertEquals("wd:Q9000011", byId["wd:Q9000013"]!!.parentId) // only the parent's P527 names it
        assertEquals(PlaceKinds.FORT, byId["wd:Q9000011"]!!.kind)
        assertTrue(byId["wd:Q9000011"]!!.radiusM > 250, "the OSM box widens the fort's extent: ${byId["wd:Q9000011"]!!.radiusM}")
        val osm = byId["osm:n777"]!!
        assertEquals(PlaceKinds.VIEWPOINT, osm.kind); assertEquals("osm", osm.source); assertEquals("", osm.summary)
        assertFalse("osm:n778" in byId, "a wayside cross is not a place")
    }

    @Test fun overpassFailureOnlyMakesTheCellIncomplete() = runTest {
        val got = source(FixtureHttp("golconda", overpassFails = true)).fetchCell(golcondaCell)
        assertFalse(got.complete)
        assertEquals(3, got.places.size)
    }

    @Test fun wikidataFailureFailsTheCell() = runTest {
        assertFailsWith<PlaceHttpException> { source(FixtureHttp("charminar", wikidataFails = true)).fetchCell(charminarCell) }
    }

    @Test fun requestsCarryOnlyTheCellBoxAndAreBatched() = runTest {
        val h = FixtureHttp("golconda")
        source(h).fetchCell(golcondaCell)
        val box = Geohash5.bounds(golcondaCell)!!
        val geo = h.gets.single { "list=geosearch" in it }
        assertTrue("gsbbox=" in geo && "gslimit=500" in geo)
        // the box in the request is the cell's, to 5 decimals: nothing finer than the cell key leaves the phone
        assertTrue(String.format("%.5f", box.north) in geo && String.format("%.5f", box.west) in geo)
        assertFalse("17.3833" in geo || "78.4011" in geo)
        val form = h.posts.single().second.getValue("data")
        assertTrue("historic" in form && "heritage" in form && "tourism" in form)
        assertFalse("17.3833" in form)
        assertTrue(h.gets.none { "key=" in it.lowercase() || "token" in it.lowercase() })
    }

    @Test fun extractsGoIn20sAndWikidataIn50s() = runTest {
        val gets = mutableListOf<String>()
        val wiki = object : WikipediaSource {
            override suspend fun geosearch(box: com.rokid.context.memory.place.GeoBox) = (1L..45L).map { WikiHit(it, "T$it", 1.0, 1.0) }
            override suspend fun pages(pageIds: List<Long>): List<WikiPage> { gets += "p${pageIds.size}"; return pageIds.map { WikiPage(it, "T$it", "x", "Q$it", 1.0, 1.0) } }
        }
        val wd = object : WikidataSource {
            override suspend fun entities(ids: List<String>): Map<String, WikidataEntity> { gets += "e${ids.size}"; return emptyMap() }
        }
        PlaceCellSource(wiki, wd, null).fetchCell(golcondaCell)
        assertEquals(listOf("p20", "p20", "p5", "e45"), gets)
        val http = FixtureHttp("charminar")
        assertFailsWith<IllegalArgumentException> { WikipediaHttpSource(http).pages((1L..21L).toList()) }
        assertFailsWith<IllegalArgumentException> { WikidataHttpSource(http).entities((1..51).map { "Q$it" }) }
    }

    @Test fun parsersTolerateMissingAndOddShapes() {
        assertEquals(emptyList(), WikipediaHttpSource.parseGeosearch("""{"query":{}}"""))
        assertEquals(emptyList(), WikipediaHttpSource.parsePages("""{"query":{"pages":[{"pageid":1,"title":"x","missing":true}]}}"""))
        assertEquals(emptyMap(), WikidataHttpSource.parse("""{"entities":{"Q1":{"missing":""}}}"""))
        val e = WikidataHttpSource.parse(fixture("wikidata_entities_golconda.json")).getValue("Q9000011")
        assertEquals(setOf("Q1785071"), e.classes); assertEquals(listOf("Q9000012", "Q9000013"), e.hasPart); assertNotNull(e.lat)
        assertEquals(PlaceKinds.MONUMENT, PlaceCellSource.osmKind(mapOf("historic" to "monument")))
        assertNull(PlaceCellSource.osmKind(mapOf("historic" to "wayside_cross")))
        assertEquals(PlaceKinds.HERITAGE_BUILDING, PlaceCellSource.osmKind(mapOf("heritage" to "2")))
        assertTrue(PlaceCellSource.MONUMENT_CLASSES.values.all { it in PlaceKinds.ALL })
        assertFalse("Q5" in PlaceCellSource.MONUMENT_CLASSES)
    }

    @Test fun httpIsHttpsOnlyAndRateLimited() = runTest {
        val h = UrlConnectionPlaceHttp()
        assertFailsWith<IllegalArgumentException> { h.get("http://example.invalid/") }
        assertEquals("a=1&b=x%20y".replace("%20", "+"), formEncode(linkedMapOf("a" to "1", "b" to "x y")))
        assertTrue(UrlConnectionPlaceHttp.DEFAULT_USER_AGENT.contains("ProactiveContextAgent") && !UrlConnectionPlaceHttp.DEFAULT_USER_AGENT.contains("@"))
        val pauses = mutableListOf<Long>(); var t = 1_000L
        val limited = UrlConnectionPlaceHttp(minGapMs = 400, clock = { t }, pause = { pauses += it; t += it })
        // no network: both calls fail at connect, but the second must have waited out the gap first
        runCatching { limited.get("https://127.0.0.1:1/a") }; runCatching { limited.get("https://127.0.0.1:1/b") }
        assertEquals(listOf(400L), pauses)
    }
}
