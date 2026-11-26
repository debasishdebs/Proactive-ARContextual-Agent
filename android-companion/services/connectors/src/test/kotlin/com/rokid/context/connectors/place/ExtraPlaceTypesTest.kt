package com.rokid.context.connectors.place

import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.PlaceKinds
import kotlinx.coroutines.test.runTest
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The optional place types (27 Nov): data shape, switches change what both the per-cell and the bulk filter admit, nothing guessed, the excluded kinds stay out. */
class ExtraPlaceTypesTest {
    @Test fun theOwnersIdsAreTheOnesInTheData() {
        val expected = mapOf(
            ExtraPlaceTypes.MOUNTAINS to setOf("Q8502", "Q207326", "Q1595289", "Q740445", "Q46831"), ExtraPlaceTypes.TEA_GARDENS to setOf("Q1827414"), ExtraPlaceTypes.RIVERS to setOf("Q47521"),
            ExtraPlaceTypes.GLACIERS to setOf("Q35666"), ExtraPlaceTypes.HILLS to setOf("Q54050"), ExtraPlaceTypes.RAILWAY to setOf("Q55488", "Q1497019"), ExtraPlaceTypes.POWER to setOf("Q1411996"),
            ExtraPlaceTypes.STADIUMS to setOf("Q483110"), ExtraPlaceTypes.BUS_STOPS to emptySet(),
        )
        for ((k, ids) in expected) assertEquals(ids, ExtraPlaceTypes.byKey(k)!!.classIds, k)
        assertEquals(expected.keys, ExtraPlaceTypes.KEYS)
    }

    @Test fun busStopsHaveNoGuessedIdSoTheSwitchIsNotShownAndAdmitsNothing() {
        assertTrue(ExtraPlaceTypes.byKey(ExtraPlaceTypes.BUS_STOPS)!!.classes.isEmpty())
        assertFalse(ExtraPlaceTypes.SHOWN.any { it.key == ExtraPlaceTypes.BUS_STOPS })
        assertTrue(ExtraPlaceTypes.classesFor(setOf(ExtraPlaceTypes.BUS_STOPS)).isEmpty())
    }

    @Test fun everyTypeIsOnByDefaultAndEachIsASeparateSwitch() {
        assertEquals(ExtraPlaceTypes.KEYS, ExtraPlaceTypes.DEFAULT_ON)
        assertEquals(ExtraPlaceTypes.KEYS.size, ExtraPlaceTypes.ALL.map { it.key }.toSet().size)
        for (t in ExtraPlaceTypes.SHOWN) { assertTrue(t.title.startsWith("Include ")); assertTrue(t.category.isNotBlank()) }
        assertEquals("Include mountains and peaks (adds many places; good for a mountain trip)", ExtraPlaceTypes.fullLabel(ExtraPlaceTypes.byKey(ExtraPlaceTypes.MOUNTAINS)!!))
    }

    @Test fun eachSwitchAdmitsExactlyItsClassesAndTheStrictListIsUnaffected() {
        for (t in ExtraPlaceTypes.SHOWN) {
            for (c in t.classes) {
                assertNull(PlaceCellSource.kindOf(setOf(c.id), "Q1", emptySet()), "${c.id} needs its switch")
                assertEquals(c.kind, PlaceCellSource.kindOf(setOf(c.id), "Q1", setOf(t.key)), c.id)
                for (other in ExtraPlaceTypes.KEYS - t.key) assertNull(PlaceCellSource.kindOf(setOf(c.id), "Q1", setOf(other)), "${c.id} must not come in through $other")
            }
        }
        assertNotNull(PlaceCellSource.kindOf(setOf("Q4989906"), "Q1", emptySet())) // monument: strict, always
        assertNull(PlaceCellSource.kindOf(setOf("Q8502"), "Q1", setOf("not_a_key")))
    }

    @Test fun excludedKindsHaveNoSwitchAndNoClassHere() {
        val excluded = setOf("Q486972" /* human settlement */, "Q3914" /* school */, "Q5" /* human */, "Q43501" /* zoo */)
        assertTrue(ExtraPlaceTypes.ALL.flatMap { it.classIds }.none { it in excluded })
        for (c in excluded) assertNull(PlaceCellSource.kindOf(setOf(c), "Q1", ExtraPlaceTypes.KEYS))
    }

    @Test fun riversAreAreaLikeWithASmallCapAndPointLikeExtrasKeepTheOneKmRule() {
        assertEquals(5000.0, PlaceCellSource.disagreeLimitM(setOf("Q47521"), "Q1")); assertEquals(100, PlaceCellSource.radiusCapOf(setOf("Q47521")))
        assertEquals(5000.0, PlaceCellSource.disagreeLimitM(setOf("Q8502"), "Q1")); assertEquals(300, PlaceCellSource.radiusCapOf(setOf("Q8502")))
        for (c in listOf("Q35666", "Q54050")) assertEquals(5000.0, PlaceCellSource.disagreeLimitM(setOf(c), "Q1"))
        for (c in listOf("Q1827414", "Q55488", "Q1497019", "Q1411996", "Q483110")) assertEquals(1000.0, PlaceCellSource.disagreeLimitM(setOf(c), "Q1"), c)
    }

    @Test fun theSwitchesChangeWhatTheBulkAndThePerCellFilterAdmit() = runTest {
        val rows = ExtraPlaceTypes.SHOWN.flatMap { it.classes }.mapIndexed { i, c -> Triple("Q${900 + i}", "Place $i", c.id) }
        val body = """{"results":{"bindings":[${rows.joinToString(",") { (q, t, c) -> """{"item":{"value":"http://www.wikidata.org/entity/$q"},"title":{"value":"$t"},"cls":{"value":"http://www.wikidata.org/entity/$c"},"lat":{"value":"27.2"},"lon":{"value":"88.2"}}""" }}]}}"""
        val http = object : PlaceHttp {
            override suspend fun get(url: String): String = when {
                "/sparql" in url -> body
                "prop=coordinates" in url -> """{"query":{"pages":[]}}"""
                else -> """{"query":{"pages":[${URLDecoder.decode(url.substringAfter("titles=").substringBefore("&"), "UTF-8").split("|").mapIndexed { i, t -> """{"pageid":$i,"title":"$t","extract":"x."}""" }.joinToString(",")}]}}"""
            }
            override suspend fun postForm(url: String, form: Map<String, String>): String = error("no post")
        }
        val box = GeoBox(27.0, 88.0, 27.5, 88.5)
        assertEquals(emptySet(), WikidataBulkSource(http).fetchBox(box).map { it.id }.toSet())
        assertEquals(rows.map { "wd:${it.first}" }.toSet(), WikidataBulkSource(http, extraTypes = { ExtraPlaceTypes.DEFAULT_ON }).fetchBox(box).map { it.id }.toSet())
        // one switch off: only that type's places leave
        val noRivers = WikidataBulkSource(http, extraTypes = { ExtraPlaceTypes.DEFAULT_ON - ExtraPlaceTypes.RIVERS }).fetchBox(box).map { it.id }.toSet()
        val riverId = rows.first { it.third == "Q47521" }.first
        assertEquals(rows.map { "wd:${it.first}" }.toSet() - "wd:$riverId", noRivers)
        // the per-cell filter reads the same switches
        val wiki = object : WikipediaSource {
            override suspend fun geosearch(box: GeoBox) = rows.mapIndexed { i, r -> WikiHit(i + 1L, r.second, 27.3, 88.6) }
            override suspend fun pages(pageIds: List<Long>) = rows.mapIndexed { i, r -> WikiPage(i + 1L, r.second, "x.", r.first, 27.3, 88.6) }.filter { it.pageId in pageIds }
        }
        val wd = object : WikidataSource {
            override suspend fun entities(ids: List<String>) = rows.associate { it.first to WikidataEntity(it.first, setOf(it.third), emptyList(), emptyList(), 27.3, 88.6) }
        }
        val cell = com.rokid.context.memory.place.Geohash5.encode(27.3, 88.6)
        assertEquals(emptySet(), PlaceCellSource(wiki, wd, null).fetchCell(cell).places.map { it.id }.toSet())
        assertEquals(rows.map { "wd:${it.first}" }.toSet(), PlaceCellSource(wiki, wd, null, extraTypes = { ExtraPlaceTypes.DEFAULT_ON }).fetchCell(cell).places.map { it.id }.toSet())
        val rivers = PlaceCellSource(wiki, wd, null, extraTypes = { setOf(ExtraPlaceTypes.RIVERS) }).fetchCell(cell).places
        assertEquals(setOf("wd:$riverId"), rivers.map { it.id }.toSet()); assertTrue(rivers.single().radiusM <= 100); assertEquals(PlaceKinds.ATTRACTION, rivers.single().kind)
    }
}
