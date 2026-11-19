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

    /** The ids and English labels as wikidata.org returned them on 2026-11-17 (wbgetentities, props=labels): a wrong id from memory cannot come back. */
    @Test fun monumentClassIdsAreTheOnesVerifiedAgainstWikidata() {
        val verified = mapOf(
            "Q4989906" to "monument", "Q44539" to "temple", "Q842402" to "Hindu temple", "Q5393308" to "Buddhist temple", "Q180987" to "stupa", "Q12539846" to "dagoba",
            "Q57821" to "fortification", "Q1785071" to "fort", "Q16560" to "palace", "Q10500763" to "prison museum", "Q839954" to "archaeological site", "Q5003624" to "memorial",
            "Q16970" to "church building", "Q2977" to "cathedral", "Q32815" to "mosque", "Q39715" to "lighthouse", "Q358" to "heritage site", "Q1081138" to "historic site", "Q33506" to "museum",
            // read back from wikidata.org on 2026-11-18 (coverage check): the P31 of Qutb Shahi tombs, Salar Jung Museum and Ramoji Film City
            "Q381885" to "tomb", "Q207694" to "art museum", "Q17431399" to "national museum", "Q2416723" to "theme park",
            // read back from wikidata.org on 2026-11-18 (trip coverage check): the P31 of Rumtek/Enchey/Ghum/Pemayangtse, Darjeeling Peace Pagoda, Polonnaruwa Vatadage, Peradeniya
            "Q844619" to "vihāra", "Q54074585" to "Tibetan Buddhist monastery", "Q1456873" to "peace pagoda", "Q109607" to "ruins", "Q167346" to "botanical garden",
        )
        assertEquals(verified.keys, PlaceCellSource.MONUMENT_CLASSES.keys)
        assertEquals(PlaceKinds.PRISON_MUSEUM, PlaceCellSource.MONUMENT_CLASSES["Q10500763"])
        assertFalse("Q40357 is a plain prison") { "Q40357" in PlaceCellSource.MONUMENT_CLASSES }
        assertFalse("Q160742 is an abbey") { "Q160742" in PlaceCellSource.MONUMENT_CLASSES }
    }

    /** Classes seen on the trip check and deliberately NOT admitted (not monument-like, or people/organisations/businesses), ids and labels from wikidata.org 2026-11-18. */
    @Test fun tripClassesThatAreNotMonumentsStayOut() {
        val out = mapOf("Q23397" to "lake", "Q211302" to "glacial lake", "Q34038" to "waterfall", "Q133056" to "mountain pass", "Q618123" to "geographical feature", "Q55488" to "railway station",
            "Q1395089" to "horseshoe curve", "Q70780172" to "tea plantation", "Q811534" to "remarkable tree", "Q486972" to "human settlement", "Q5" to "human")
        for ((id, label) in out) assertFalse("$label ($id) must not be admitted") { id in PlaceCellSource.MONUMENT_CLASSES }
    }

    @Test fun httpIsHttpsOnlyAndRateLimited() = runTest {
        val h = UrlConnectionPlaceHttp()
        assertFailsWith<IllegalArgumentException> { h.get("http://example.invalid/") }
        assertEquals("a=1&b=x%20y".replace("%20", "+"), formEncode(linkedMapOf("a" to "1", "b" to "x y")))
        assertTrue(UrlConnectionPlaceHttp.DEFAULT_USER_AGENT.contains("ProactiveContextAgent") && !UrlConnectionPlaceHttp.DEFAULT_USER_AGENT.contains("@"))
        assertEquals("ProactiveContextAgent/1.1 (https://github.com/; contact: see repository)", UrlConnectionPlaceHttp.DEFAULT_USER_AGENT)
        val pauses = mutableListOf<Long>(); var t = 1_000L
        val limited = UrlConnectionPlaceHttp(minGapMs = 400, clock = { t }, pause = { pauses += it; t += it })
        // no network: both calls fail at connect, but the second must have waited out the gap first
        runCatching { limited.get("https://127.0.0.1:1/a") }; runCatching { limited.get("https://127.0.0.1:1/b") }
        assertEquals(listOf(400L), pauses)
    }

    // Codex r4: the zone fence is asked immediately before EACH request (Wikipedia geosearch, Wikipedia pages, Wikidata, Overpass), and a closed fence sends nothing further.
    @Test fun theFenceClosingBeforeAnyRequestOfACellStopsEveryLaterRequest() {
        val total = FixtureHttp("golconda").let { h -> kotlinx.coroutines.runBlocking { source(h).fetchCell(golcondaCell) }; h.gets.size + h.posts.size }
        assertTrue(total >= 3)
        for (closeAt in 1..total) {
            val h = FixtureHttp("golconda")
            val fence = com.rokid.context.memory.place.ZoneFence()
            val counting = object : PlaceHttp {
                var n = 0
                override suspend fun get(url: String): String { if (++n == closeAt) fence.hold(); return h.get(url) }
                override suspend fun postForm(url: String, form: Map<String, String>): String { if (++n == closeAt) fence.hold(); return h.postForm(url, form) }
            }
            val src = PlaceCellSource(WikipediaHttpSource(counting), WikidataHttpSource(counting), OverpassHttpSource(counting))
            val result = runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withContext(fence.ticket()) { src.fetchCell(golcondaCell) } } }
            // closing during the LAST request leaves nothing further to dispatch; every earlier closing stops the cell
            assertEquals(closeAt < total, result.exceptionOrNull() is com.rokid.context.memory.place.ZoneFenced, "closeAt=$closeAt")
            assertEquals(closeAt, counting.n, "closeAt=$closeAt: no request after the fence closed")
        }
    }

    @Test fun theHttpClientNeverConnectsWithAClosedTicket() {
        val fence = com.rokid.context.memory.place.ZoneFence(); fence.hold()
        val r = runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withContext(fence.ticket()) { UrlConnectionPlaceHttp().get("https://invalid.invalid/x") } } }
        assertTrue(r.exceptionOrNull() is com.rokid.context.memory.place.ZoneFenced)
    }

    /** Hyderabad coverage check 2026-11-18: these three were returned by the geosearch but dropped by the class allowlist; their real P31 values are kept now. */
    @Test fun qutbShahiTombsSalarJungMuseumAndRamojiFilmCityAreKept() = runTest {
        val cell = Geohash5.encode(17.3713, 78.4804)
        val wiki = object : WikipediaSource {
            override suspend fun geosearch(box: com.rokid.context.memory.place.GeoBox) = listOf(WikiHit(1, "Qutb Shahi tombs", 17.3947, 78.3956), WikiHit(2, "Salar Jung Museum", 17.3713, 78.4804), WikiHit(3, "Ramoji Film City", 17.2543, 78.6808), WikiHit(4, "Charminar Assembly constituency", 17.36, 78.47))
            override suspend fun pages(pageIds: List<Long>) = listOf(WikiPage(1, "Qutb Shahi tombs", "Tombs.", "Q7273006", null, null), WikiPage(2, "Salar Jung Museum", "Museum.", "Q1864572", null, null), WikiPage(3, "Ramoji Film City", "Studio.", "Q1786944", null, null), WikiPage(4, "Charminar Assembly constituency", "Seat.", "Q5086502", 17.36, 78.47))
        }
        val wd = object : WikidataSource {
            override suspend fun entities(ids: List<String>) = mapOf(
                "Q7273006" to WikidataEntity("Q7273006", setOf("Q381885"), emptyList(), emptyList(), 17.3947, 78.3956),
                "Q1864572" to WikidataEntity("Q1864572", setOf("Q207694", "Q17431399"), emptyList(), emptyList(), 17.3713, 78.4804),
                "Q1786944" to WikidataEntity("Q1786944", setOf("Q375336", "Q2416723"), emptyList(), emptyList(), 17.2543, 78.6808),
                "Q5086502" to WikidataEntity("Q5086502", setOf("Q54375514"), emptyList(), emptyList(), null, null),
            )
        }
        val got = PlaceCellSource(wiki, wd, null).fetchCell(cell)
        assertEquals(mapOf("wd:Q7273006" to PlaceKinds.TOMB, "wd:Q1864572" to PlaceKinds.MUSEUM, "wd:Q1786944" to PlaceKinds.ATTRACTION), got.places.associate { it.id to it.kind })
    }

    /** Trip coverage check 2026-11-18 (real P31 values from wikidata.org): monasteries, peace pagoda, ruins and botanical garden are kept; lake, waterfall, tea plantation, settlement and a person stay out unless their QID is curated (see the curated tests; the ids here are NOT curated). */
    @Test fun tripSitesAreKeptAndNonMonumentClassesAreNot() = runTest {
        val rows = listOf(
            Triple("Rumtek Monastery", "Q1546234", setOf("Q844619", "Q54074585")) to PlaceKinds.TEMPLE,
            Triple("Enchey Monastery", "Q5375417", setOf("Q54074585")) to PlaceKinds.TEMPLE,
            Triple("Pemayangtse Monastery", "Q7161856", setOf("Q844619")) to PlaceKinds.TEMPLE,
            Triple("Peace Pagoda, Darjeeling", "Q7157413", setOf("Q1456873")) to PlaceKinds.STUPA,
            Triple("Polonnaruwa Vatadage", "Q7225955", setOf("Q109607")) to PlaceKinds.ARCHAEOLOGICAL_SITE,
            Triple("Royal Botanic Gardens, Peradeniya", "Q3119056", setOf("Q167346")) to PlaceKinds.ATTRACTION,
            Triple("Some other lake", "Q9999981", setOf("Q23397")) to null,
            Triple("Some other waterfall", "Q9999982", setOf("Q34038")) to null,
            Triple("Some other tea estate", "Q9999983", setOf("Q70780172", "Q56436498")) to null,
            Triple("Some other settlement", "Q9999984", setOf("Q486972")) to null,
            Triple("Some Lama", "Q9999991", setOf("Q5")) to null,
        )
        val wiki = object : WikipediaSource {
            override suspend fun geosearch(box: com.rokid.context.memory.place.GeoBox) = rows.mapIndexed { i, r -> WikiHit(i + 1L, r.first.first, 27.3, 88.6) }
            override suspend fun pages(pageIds: List<Long>) = rows.mapIndexed { i, r -> WikiPage(i + 1L, r.first.first, "x.", r.first.second, 27.3, 88.6) }.filter { it.pageId in pageIds }
        }
        val wd = object : WikidataSource {
            override suspend fun entities(ids: List<String>) = rows.associate { it.first.second to WikidataEntity(it.first.second, it.first.third, emptyList(), emptyList(), 27.3, 88.6) }
        }
        val got = PlaceCellSource(wiki, wd, null).fetchCell(Geohash5.encode(27.3, 88.6)).places.associate { it.id to it.kind }
        assertEquals(rows.filter { it.second != null }.associate { "wd:${it.first.second}" to it.second!! }, got)
    }

    /** Curated landmark allowlist (version 1): ids and English labels as wikidata.org returned them on 2026-11-18. */
    @Test fun curatedLandmarkIdsAreTheOnesVerifiedAgainstWikidata() {
        val verified = mapOf(
            "Q272153" to "Sigiriya", "Q16901632" to "Tiger Hill, Darjeeling", "Q4868648" to "Batasia Loop", "Q23038925" to "Banjhakri falls and park", "Q3214979" to "Khecheopalri Lake",
            "Q944175" to "Sri Maha Bodhi", "Q5653142" to "Happy Valley Tea Estate", "Q542864" to "Lake Tsongmo", "Q5285886" to "Do-drul Chorten", "Q24949017" to "Darjeeling railway station",
            "Q60181073" to "Ghum railway station", "Q1961579" to "Jetavanaramaya",
            // version 4, read back from wikidata.org on 2026-11-19
            "Q7685364" to "Taramati Baradari", "Q7124701" to "Paigah Tombs", "Q4984164" to "Buddha Statue of Hyderabad", "Q3275994" to "Hussain Sagar", "Q1650886" to "Lumbini Park",
            "Q3493970" to "Nehru Zoological Park", "Q6466530" to "Laad Bazaar",
        )
        assertEquals(verified.keys, PlaceCellSource.CURATED_LANDMARK_QIDS.keys)
        assertEquals(4, PlaceCellSource.CURATED_LANDMARKS_VERSION)
        assertTrue("Q19719626" !in PlaceCellSource.CURATED_LANDMARK_QIDS) // Ritigala: verified point is the mountain, not the ruins
        assertTrue(PlaceCellSource.CURATED_LANDMARK_QIDS.values.all { it in PlaceKinds.ALL })
        assertEquals(setOf("Q5", "Q15632617", "Q95074", "Q21070568"), PlaceCellSource.PERSON_CLASSES)
        assertTrue(PlaceCellSource.CURATED_LANDMARK_QIDS.keys.none { it in PlaceCellSource.MONUMENT_CLASSES })
    }

    private suspend fun curatedCell(rows: List<Triple<String, Set<String>, Pair<Double, Double>?>>): Map<String, String> {
        val wiki = object : WikipediaSource {
            override suspend fun geosearch(box: com.rokid.context.memory.place.GeoBox) = rows.mapIndexed { i, r -> WikiHit(i + 1L, r.first, 27.3, 88.6) }
            override suspend fun pages(pageIds: List<Long>) = rows.mapIndexed { i, r -> WikiPage(i + 1L, r.first, "x.", r.first, null, null) }.filter { it.pageId in pageIds }
        }
        val wd = object : WikidataSource {
            override suspend fun entities(ids: List<String>) = rows.associate { it.first to WikidataEntity(it.first, it.second, emptyList(), emptyList(), it.third?.first, it.third?.second) }
        }
        return PlaceCellSource(wiki, wd, null).fetchCell(Geohash5.encode(27.3, 88.6)).places.associate { it.id to it.kind }
    }

    @Test fun curatedQidIsAdmittedAndTheSameClassWithoutTheQidIsNot() = runTest {
        val at = 27.3 to 88.6
        val got = curatedCell(listOf(
            Triple("Q3214979", setOf("Q23397"), at), // Khecheopalri Lake: curated lake
            Triple("Q1111111", setOf("Q23397"), at), // another lake: not curated
            Triple("Q4868648", setOf("Q1395089"), at), // Batasia Loop
            Triple("Q5285886", emptySet(), at), // Do-drul Chorten, no P31
            Triple("Q2222222", emptySet(), at), // no P31, not curated
        ))
        assertEquals(mapOf("wd:Q3214979" to PlaceKinds.ATTRACTION, "wd:Q4868648" to PlaceKinds.ATTRACTION, "wd:Q5285886" to PlaceKinds.STUPA), got)
    }

    @Test fun aPersonIsRejectedEvenWhenItsQidIsCurated() = runTest {
        val at = 27.3 to 88.6
        val got = curatedCell(listOf(Triple("Q272153", setOf("Q5"), at), Triple("Q16901632", setOf("Q15632617", "Q618123"), at), Triple("Q944175", setOf("Q811534"), at)))
        assertEquals(mapOf("wd:Q944175" to PlaceKinds.HISTORIC_SITE), got)
    }

    /** Real parser output (Wikidata JSON) through PlaceCellSource: absent P31 admits a curated QID only; malformed P31 rejects even a curated one; a person class rejects. */
    private suspend fun parsedCell(json: String, logs: MutableList<String> = mutableListOf()): Map<String, String> {
        val wiki = object : WikipediaSource {
            override suspend fun geosearch(box: com.rokid.context.memory.place.GeoBox) = listOf(WikiHit(1, "Sigiriya", 7.957, 80.76))
            override suspend fun pages(pageIds: List<Long>) = listOf(WikiPage(1, "Sigiriya", "x.", "Q272153", null, null))
        }
        val wd = object : WikidataSource { override suspend fun entities(ids: List<String>) = WikidataHttpSource.parse(json) }
        return PlaceCellSource(wiki, wd, null) { logs += it }.fetchCell(Geohash5.encode(7.957, 80.76)).places.associate { it.id to it.kind }
    }
    private fun item(claims: String) = """{"entities":{"Q272153":{"claims":$claims}}}"""
    private fun p31(vararg ids: String) = """{"P31":[${ids.joinToString(",") { """{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"$it"}}}}""" }}]}"""

    @Test fun parsedAbsentP31AdmitsACuratedQidButMalformedP31DoesNot() = runTest {
        assertEquals(setOf("wd:Q272153"), parsedCell(item("""{"P625":[]}""")).keys) // key absent
        assertEquals(setOf("wd:Q272153"), parsedCell(item("""{"P31":[]}""")).keys) // empty list
        assertEquals(setOf("wd:Q272153"), parsedCell(item("[]")).keys) // Wikidata's empty claims
        for (bad in listOf("""{"P31":{}}""", """{"P31":"Q5"}""", """{"P31":[1,"x",null]}""", """{"P31":[{"mainsnak":{}}]}""", """{"P31":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":5}}}}]}""")) {
            val logs = mutableListOf<String>()
            assertEquals(emptyMap<String, String>(), parsedCell(item(bad), logs), bad)
            assertTrue(logs.any { it.endsWith("kept=0 osm=0 rejected=1") }, bad)
        }
        assertEquals(emptyMap<String, String>(), parsedCell(item(""""unreadable"""")))
        assertEquals(setOf("wd:Q272153"), parsedCell(item("""{"P31":[{"mainsnak":{"snaktype":"somevalue"}}]}""")).keys) // unknown value is not damage
    }

    @Test fun parsedPersonClassIsRejectedAndAValidClassIsKept() = runTest {
        assertEquals(emptyMap<String, String>(), parsedCell(item(p31("Q5"))))
        assertEquals(mapOf("wd:Q272153" to PlaceKinds.ARCHAEOLOGICAL_SITE), parsedCell(item(p31("Q839954"))))
    }

    /** A geosearch hit always carries coordinates, so "no coordinates" means no usable item: a curated QID whose Wikidata entity did not come back (or has no page) is never stored. */
    @Test fun aCuratedQidWithoutAWikidataEntityOrPageIsRejected() = runTest {
        val wiki = object : WikipediaSource {
            override suspend fun geosearch(box: com.rokid.context.memory.place.GeoBox) = listOf(WikiHit(1, "Sigiriya", 7.957, 80.76), WikiHit(2, "Tiger Hill", 26.99, 88.28))
            override suspend fun pages(pageIds: List<Long>) = listOf(WikiPage(1, "Sigiriya", "x.", "Q272153", null, null), WikiPage(2, "Tiger Hill", "x.", null, null, null))
        }
        val wd = object : WikidataSource { override suspend fun entities(ids: List<String>) = emptyMap<String, WikidataEntity>() }
        assertEquals(0, PlaceCellSource(wiki, wd, null).fetchCell(Geohash5.encode(7.957, 80.76)).places.size)
    }

    // ---- Verified-coordinate extras (curated places whose Wikipedia page has no coordinates) ----

    @Test fun verifiedCoordinateTableIsPinned() {
        val t = PlaceCellSource.VERIFIED_COORDINATES
        assertEquals(3, PlaceCellSource.VERIFIED_COORDINATES_VERSION)
        assertEquals(setOf("Q1961579", "Q60181073", "Q7685364"), t.keys)
        assertEquals(VerifiedCoordinate("Taramati Baradari", 17.37608, 78.378117), t["Q7685364"])
        assertEquals(VerifiedCoordinate("Jetavanaramaya", 8.351667, 80.403611), t["Q1961579"])
        assertEquals(VerifiedCoordinate("Ghum railway station", 27.008643, 88.254223), t["Q60181073"])
        assertTrue(t.keys.all { it in PlaceCellSource.CURATED_LANDMARK_QIDS })
        assertTrue(t.values.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
    }

    /** No geosearch hits at all; the Wikidata response is [json] parsed by the real parser. Returns id to (kind, lat, lon, name) and the ids Wikidata was asked for. */
    private suspend fun extrasCell(cell: String, json: String, asked: MutableList<String> = mutableListOf()): Map<String, List<Any>> {
        val wiki = object : WikipediaSource {
            override suspend fun geosearch(box: com.rokid.context.memory.place.GeoBox) = emptyList<WikiHit>()
            override suspend fun pages(pageIds: List<Long>) = emptyList<WikiPage>()
        }
        val wd = object : WikidataSource { override suspend fun entities(ids: List<String>): Map<String, WikidataEntity> { asked += ids; return WikidataHttpSource.parse(json) } }
        return PlaceCellSource(wiki, wd, null).fetchCell(cell).places.associate { it.id to listOf(it.kind, it.lat, it.lon, it.name, it.source) }
    }
    private fun entity(q: String, claims: String, lat: Double = 1.0, lon: Double = 2.0) =
        """{"entities":{"$q":{"claims":{${claims.let { if (it.isEmpty()) "" else "$it," }}"P625":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"latitude":$lat,"longitude":$lon}}}}]}}}}"""
    private fun p31c(vararg ids: String) = """"P31":[${ids.joinToString(",") { """{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"$it"}}}}""" }}]"""

    @Test fun aTableEntryInsideTheCellIsAdmittedWithTheTableCoordinateNotTheResponses() = runTest {
        val asked = mutableListOf<String>()
        val got = extrasCell(Geohash5.encode(8.3517, 80.4036), entity("Q1961579", p31c("Q180987"), 9.9, 99.9), asked)
        assertEquals(listOf("Q1961579"), asked)
        assertEquals(setOf("wd:Q1961579"), got.keys)
        assertEquals(listOf("stupa", 8.351667, 80.403611, "Jetavanaramaya", "wikidata"), got["wd:Q1961579"])
    }

    @Test fun aTableEntryOutsideTheCellIsNeitherAskedNorAdmitted() = runTest {
        val asked = mutableListOf<String>()
        val got = extrasCell(Geohash5.encode(7.957, 80.76), entity("Q1961579", p31c("Q180987")), asked) // Sigiriya cell
        assertTrue(got.isEmpty()); assertTrue(asked.isEmpty())
    }

    @Test fun aPersonClassIsRejectedEvenWithATableEntry() = runTest {
        val got = extrasCell(Geohash5.encode(8.3517, 80.4036), entity("Q1961579", p31c("Q5", "Q180987")))
        assertTrue(got.isEmpty())
    }

    @Test fun anEntryWithoutP31IsAdmittedWithTheCuratedKind() = runTest {
        val cell = Geohash5.encode(8.3517, 80.4036)
        assertEquals("stupa", extrasCell(cell, entity("Q1961579", ""))["wd:Q1961579"]?.first())
        assertEquals("stupa", extrasCell(cell, entity("Q1961579", p31c("Q180987")))["wd:Q1961579"]?.first())
    }

    @Test fun aMalformedOrMissingResponseRejectsTheEntry() = runTest {
        val cell = Geohash5.encode(8.3517, 80.4036)
        assertTrue(extrasCell(cell, """{"entities":{"Q1961579":{"claims":"junk"}}}""").isEmpty()) // unreadable claims
        assertTrue(extrasCell(cell, """{"entities":{"Q1961579":{"claims":{"P31":"junk"}}}}""").isEmpty()) // malformed P31
        assertTrue(extrasCell(cell, "{}").isEmpty()) // no entities
        assertTrue(extrasCell(cell, """{"entities":{"Q999":{"claims":[]}}}""").isEmpty()) // another item than the one asked for
    }

    @Test fun aPlaceGeosearchAlreadyReturnedIsNotDuplicatedOrAskedForTwice() = runTest {
        val asked = mutableListOf<String>()
        val wiki = object : WikipediaSource {
            override suspend fun geosearch(box: com.rokid.context.memory.place.GeoBox) = listOf(WikiHit(1, "Jetavanaramaya", 8.35, 80.40))
            override suspend fun pages(pageIds: List<Long>) = listOf(WikiPage(1, "Jetavanaramaya", "A stupa.", "Q1961579", 8.35, 80.40))
        }
        val wd = object : WikidataSource { override suspend fun entities(ids: List<String>): Map<String, WikidataEntity> { asked += ids; return WikidataHttpSource.parse(entity("Q1961579", p31c("Q180987"))) } }
        val places = PlaceCellSource(wiki, wd, null).fetchCell(Geohash5.encode(8.3517, 80.4036)).places
        assertEquals(listOf("Q1961579"), asked)
        assertEquals(1, places.size); assertEquals("wikipedia:en", places.single().source)
    }

    private class Mirror(val answer: suspend () -> List<OsmElement>) : OverpassSource {
        var calls = 0
        override suspend fun elements(box: com.rokid.context.memory.place.GeoBox): List<OsmElement> { calls++; return answer() }
    }
    private val someBox = com.rokid.context.memory.place.GeoBox(17.0, 78.0, 17.04, 78.04)
    private val el = OsmElement("node", 1, 17.0, 78.0, null, mapOf("name" to "x", "historic" to "monument"))

    @Test fun mirrorsAreTriedInOrderUntilOneAnswers() = runTest {
        val a = Mirror { throw PlaceHttpException(504) }; val b = Mirror { listOf(el) }; val c = Mirror { error("not reached") }
        val waits = mutableListOf<Long>(); val logs = mutableListOf<String>()
        val src = OverpassMirrorSource(listOf(a, b, c), pause = { waits += it }, log = { logs += it })
        assertEquals(listOf(el), src.elements(someBox))
        assertEquals(listOf(1, 1, 0), listOf(a.calls, b.calls, c.calls))
        assertEquals(listOf(2_000L), waits) // backed off before the second mirror
        assertEquals(listOf("overpass mirror failed idx=0 cls=PlaceHttpException"), logs)
    }

    @Test fun aHangingMirrorTimesOutAndTheNextOneIsUsed() = runTest {
        val a = Mirror { kotlinx.coroutines.delay(1_000_000); emptyList() }; val b = Mirror { listOf(el) }
        val src = OverpassMirrorSource(listOf(a, b), perEndpointTimeoutMs = 5_000, pause = {})
        assertEquals(listOf(el), src.elements(someBox))
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test fun allMirrorsFailingThrowsOnceThenCoolsDownWithoutAskingAgainAndProbesAfterwards() = runTest {
        var now = 1_000L
        val ms = List(3) { Mirror { throw IOException("down") } }
        val logs = mutableListOf<String>()
        val src = OverpassMirrorSource(ms, cooldownMs = 180_000, clock = { now }, pause = {}, log = { logs += it })
        assertFailsWith<IOException> { src.elements(someBox) }
        assertEquals(listOf(1, 1, 1), ms.map { it.calls })
        assertTrue("overpass all mirrors failed n=3" in logs)
        now += 60_000
        assertFailsWith<IOException> { src.elements(someBox) }
        assertEquals(listOf(1, 1, 1), ms.map { it.calls }) // cooling down: nothing was asked
        now += 130_000
        assertFailsWith<IOException> { src.elements(someBox) }
        assertEquals(listOf(2, 2, 2), ms.map { it.calls })
        assertTrue(logs.none { "http" in it.lowercase() || "17." in it })
    }

    @Test fun allMirrorsDownMakesTheCellPartialButKeepsWikipediaAndWikidata() = runTest {
        val h = FixtureHttp("golconda")
        val down = OverpassMirrorSource(OverpassMirrorSource.DEFAULT_ENDPOINTS.map { OverpassHttpSource(object : PlaceHttp by h { override suspend fun postForm(url: String, form: Map<String, String>): String { h.posts += url to form; throw IOException("down") } }, it) }, pause = {})
        val got = PlaceCellSource(WikipediaHttpSource(h), WikidataHttpSource(h), down).fetchCell(golcondaCell)
        assertFalse(got.complete)
        assertEquals(3, got.places.size)
        assertEquals(OverpassMirrorSource.DEFAULT_ENDPOINTS, h.posts.map { it.first })
        assertEquals(listOf("overpass-api.de", "overpass.kumi.systems", "overpass.private.coffee"), OverpassMirrorSource.DEFAULT_ENDPOINTS.map { java.net.URL(it).host })
    }
}
