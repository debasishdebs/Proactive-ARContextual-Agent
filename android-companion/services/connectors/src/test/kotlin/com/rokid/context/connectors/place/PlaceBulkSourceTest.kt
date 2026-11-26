package com.rokid.context.connectors.place

import com.rokid.context.memory.place.BulkTooBig
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.PlaceKinds
import kotlinx.coroutines.test.runTest
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bulk Wikidata discovery with a fake HTTP seam (generic ids and coordinates; the network is never touched). */
class PlaceBulkSourceTest {
    private val box = GeoBox(27.0, 88.0, 27.5, 88.5)

    private fun row(q: String, title: String, cls: String?, lat: Double = 27.2, lon: Double = 88.2) =
        """{"item":{"value":"http://www.wikidata.org/entity/$q"},"title":{"value":"$title"},""" + (if (cls != null) """"cls":{"value":"http://www.wikidata.org/entity/$cls"},""" else "") +
            """"lat":{"value":"$lat"},"lon":{"value":"$lon"}}"""
    private fun sparql(vararg rows: String) = """{"head":{"vars":[]},"results":{"bindings":[${rows.joinToString(",")}]}}"""
    private fun coords(vararg c: Triple<String, Double, Double>) = """{"query":{"pages":[${c.joinToString(",") { """{"pageid":1,"title":"${it.first}","coordinates":[{"lat":${it.second},"lon":${it.third}}]}""" }}]}}"""
    private fun intros(vararg t: String) = """{"query":{"pages":[${t.mapIndexed { i, s -> """{"pageid":${100 + i},"title":"$s","extract":"About $s."}""" }.joinToString(",")}]}}"""

    private class Net(val onSparql: (Int, String) -> String, val onCoords: (String) -> String = { """{"query":{"pages":[]}}""" }, val onIntro: (String) -> String = { """{"query":{"pages":[]}}""" }) : PlaceHttp {
        val urls = mutableListOf<String>()
        var sparqlCalls = 0
        override suspend fun get(url: String): String {
            urls += url
            return when {
                "/sparql" in url -> onSparql(sparqlCalls++, URLDecoder.decode(url.substringAfter("query="), "UTF-8"))
                "prop=coordinates" in url -> onCoords(URLDecoder.decode(url.substringAfter("titles=").substringBefore("&"), "UTF-8"))
                "prop=extracts" in url -> onIntro(URLDecoder.decode(url.substringAfter("titles=").substringBefore("&"), "UTF-8"))
                else -> error("unexpected")
            }
        }
        override suspend fun postForm(url: String, form: Map<String, String>): String = error("no post")
        fun count(s: String) = urls.count { s in it }
    }

    private fun titlesOf(url: String) = URLDecoder.decode(url.substringAfter("titles=").substringBefore("&"), "UTF-8").split("|")

    @Test fun curatedVerifiedCoordinateIsStoredAtTheTablePointNotTheLiveOne() = runTest {
        // Q1961579 is curated and has a verified point; Wikipedia has no coordinate for it, so the 1 km rule cannot check a (changed) live point
        val net = Net({ _, _ -> sparql(row("Q1961579", "Old Stupa", "Q180987", lat = 8.9, lon = 80.9)) }, { """{"query":{"pages":[]}}""" }, { t -> intros(*t.split("|").toTypedArray()) })
        val p = WikidataBulkSource(net).fetchBox(box).single()
        assertEquals(8.351667, p.lat, 1e-9); assertEquals(80.403611, p.lon, 1e-9)
    }

    @Test fun queryHasTheMeasuredShape() {
        val q = WikidataBulkSource.query(box, 2500, 5000)
        for (part in listOf("SERVICE wikibase:box", "schema:isPartOf <https://en.wikipedia.org/>", "OPTIONAL { ?item wdt:P31 ?cls }", "ORDER BY ?item ?cls ?lat ?lon LIMIT 2500 OFFSET 5000", "Point(88.00000 27.00000)", "Point(88.50000 27.50000)")) assertTrue(part in q, part)
        assertFalse("SELECT ?x WHERE { SELECT" in q) // no sub-select
    }

    @Test fun classRulePersonsAndTheMountainToggle() = runTest {
        val net = Net({ _, _ -> sparql(row("Q1", "Old Fort", "Q1785071"), row("Q2", "Some Person", "Q5"), row("Q3", "Some Village", "Q486972"), row("Q4", "Some Peak", "Q8502"), row("Q5", "Some Lake", "Q23397"), row("Q6", "Nothing", null)) },
            { """{"query":{"pages":[]}}""" }, { t -> intros(*t.split("|").toTypedArray()) })
        val off = WikidataBulkSource(net).fetchBox(box).associateBy { it.id }
        assertEquals(setOf("wd:Q1", "wd:Q5"), off.keys)
        assertEquals(PlaceKinds.FORT, off.getValue("wd:Q1").kind); assertEquals("About Old Fort.", off.getValue("wd:Q1").summary)
        val on = WikidataBulkSource(net, extraTypes = { setOf(ExtraPlaceTypes.MOUNTAINS) }).fetchBox(box).associateBy { it.id }
        assertEquals(setOf("wd:Q1", "wd:Q4", "wd:Q5"), on.keys)
        // intro ONLY for qualifying titles: persons, villages and class-less items never reach the intro call
        val introTitles = net.urls.filter { "prop=extracts" in it }.flatMap { titlesOf(it) }.toSet()
        assertEquals(setOf("Old Fort", "Some Lake", "Some Peak"), introTitles)
    }

    @Test fun oneKmRuleDropsPointLikeAndKeepsAreaLikeUpTo5kmAtWikidatasPoint() = runTest {
        val net = Net({ _, _ -> sparql(row("Q1", "Point Fort", "Q1785071"), row("Q2", "Area Lake", "Q23397"), row("Q3", "Far Lake", "Q23397"), row("Q4", "Agree Fort", "Q1785071"), row("Q5", "No Coord Fort", "Q1785071")) },
            { _ -> coords(Triple("Point Fort", 27.23, 88.2), Triple("Area Lake", 27.23, 88.2), Triple("Far Lake", 27.30, 88.2), Triple("Agree Fort", 27.2001, 88.2)) }, { t -> intros(*t.split("|").toTypedArray()) })
        val logs = mutableListOf<String>()
        val got = WikidataBulkSource(net, log = { logs += it }).fetchBox(box).associateBy { it.id }
        assertEquals(setOf("wd:Q2", "wd:Q4", "wd:Q5"), got.keys) // fort 3.3 km: dropped; lake 3.3 km: kept; lake 11 km: dropped; fort with no Wikipedia coordinate: kept
        assertEquals(27.2, got.getValue("wd:Q2").lat) // the stored point is Wikidata's
        // dropped places cost no intro
        assertFalse(net.urls.filter { "prop=extracts" in it }.flatMap { titlesOf(it) }.any { it == "Point Fort" || it == "Far Lake" })
        assertTrue(logs.single().contains("coordDropped=2") && logs.single().contains("coordAllowedWide=1"), logs.toString())
        assertFalse(logs.single().contains("Fort") || logs.single().contains("Lake"))
    }

    @Test fun batchesAre50ForCoordinatesAnd20ForIntros() = runTest {
        val rows = (1..130).map { row("Q$it", "T$it", "Q1785071", 27.2, 88.2) }
        val net = Net({ _, _ -> sparql(*rows.toTypedArray()) }, { _ -> """{"query":{"pages":[]}}""" }, { t -> intros(*t.split("|").toTypedArray()) })
        val got = WikidataBulkSource(net).fetchBox(box)
        assertEquals(130, got.size)
        assertEquals(3, net.count("prop=coordinates")); assertEquals(7, net.count("prop=extracts"))
        assertTrue(net.urls.filter { "prop=coordinates" in it }.all { titlesOf(it).size <= 50 })
        assertTrue(net.urls.filter { "prop=extracts" in it }.all { titlesOf(it).size <= 20 })
    }

    @Test fun pagingFollowsOrderByAndMergesRowsOfOneItem() = runTest {
        // page 1 is full (2 rows), page 2 has the rest; an item may straddle the pages
        val net = Net({ n, q ->
            when (n) { 0 -> { assertTrue("OFFSET 0" in q); sparql(row("Q1", "A Fort", "Q1785071"), row("Q2", "B Fort", "Q1785071")) }
                else -> { assertTrue("OFFSET 2" in q); sparql(row("Q2", "B Fort", "Q57821")) } }
        }, { _ -> """{"query":{"pages":[]}}""" }, { t -> intros(*t.split("|").toTypedArray()) })
        val got = WikidataBulkSource(net, pageRows = 2).fetchBox(box)
        assertEquals(setOf("wd:Q1", "wd:Q2"), got.map { it.id }.toSet())
        assertEquals(2, net.sparqlCalls)
    }

    @Test fun tooManyPagesAsksForASmallerBox() = runTest {
        val net = Net({ n, _ -> sparql(row("Q${n}a", "T${n}a", "Q1785071"), row("Q${n}b", "T${n}b", "Q1785071")) })
        assertFailsWith<BulkTooBig> { WikidataBulkSource(net, pageRows = 2, maxPages = 3).fetchBox(box) }
        assertEquals(3, net.sparqlCalls)
    }

    @Test fun timeoutsAndGatewayErrorsAskForASmallerBox() = runTest {
        for (code in listOf(500, 502, 504)) assertFailsWith<BulkTooBig>("http $code") { WikidataBulkSource(Net({ _, _ -> throw PlaceHttpException(code) })).fetchBox(box) }
        assertFailsWith<BulkTooBig> { WikidataBulkSource(Net({ _, _ -> throw java.net.SocketTimeoutException("slow") })).fetchBox(box) }
        assertFailsWith<BulkTooBig> { WikidataBulkSource(Net({ _, _ -> "java.util.concurrent.TimeoutException: query" })).fetchBox(box) }
    }

    @Test fun rateLimitIsRetriedTwiceThenGivenUp() = runTest {
        val ok = Net({ n, _ -> if (n < 2) throw PlaceHttpException(429, 1) else sparql(row("Q1", "A Fort", "Q1785071")) }, { _ -> """{"query":{"pages":[]}}""" }, { t -> intros(*t.split("|").toTypedArray()) })
        assertEquals(1, WikidataBulkSource(ok).fetchBox(box).size); assertEquals(3, ok.sparqlCalls)
        val down = Net({ _, _ -> throw PlaceHttpException(503) })
        val e = assertFailsWith<PlaceHttpException> { WikidataBulkSource(down).fetchBox(box) }
        assertEquals(503, e.code); assertEquals(3, down.sparqlCalls)
        assertFailsWith<PlaceHttpException> { WikidataBulkSource(Net({ _, _ -> throw PlaceHttpException(403) })).fetchBox(box) } // not a split reason: the pack falls back per cell
    }

    @Test fun errorBodiesAndParseErrorsAreOrdinaryFailures() = runTest {
        for (body in listOf("""{"error":"x"}""", "<html>captive portal</html>", """{"head":{}}""", "[]")) assertFailsWith<Exception>(body) { WikidataBulkSource(Net({ _, _ -> body })).fetchBox(box) }
        // an API error body on the coordinate or intro call is a failure too, never an empty answer
        val badCoords = Net({ _, _ -> sparql(row("Q1", "A Fort", "Q1785071")) }, { _ -> """{"error":{"code":"ratelimited"}}""" })
        assertFailsWith<Exception> { WikidataBulkSource(badCoords).fetchBox(box) }
        val badIntro = Net({ _, _ -> sparql(row("Q1", "A Fort", "Q1785071")) }, { _ -> """{"query":{"pages":[]}}""" }, { _ -> """{"error":{"code":"maxlag"}}""" })
        assertFailsWith<Exception> { WikidataBulkSource(badIntro).fetchBox(box) }
    }

    @Test fun protectedAreaRadiusIsCappedAndHostileRowsAreSkipped() = runTest {
        val net = Net({ _, _ -> sparql(row("Q1", "Some Reserve", "Q473972"), row("Q2", "Bad Lat", "Q1785071", 95.0, 88.2), """{"item":{"value":"x"}}""") }, { _ -> """{"query":{"pages":[]}}""" }, { t -> intros(*t.split("|").toTypedArray()) })
        val got = WikidataBulkSource(net).fetchBox(box)
        assertEquals(listOf("wd:Q1"), got.map { it.id }); assertTrue(got.single().radiusM <= 300)
    }
}
