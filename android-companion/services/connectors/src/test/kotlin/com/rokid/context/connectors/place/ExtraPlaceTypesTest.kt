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
            ExtraPlaceTypes.MOUNTAINS to setOf("Q8502", "Q207326", "Q1595289", "Q740445", "Q46831"), ExtraPlaceTypes.TEA_GARDENS to setOf("Q1827414"), ExtraPlaceTypes.RIVERS to setOf("Q47521", "Q4022"),
            ExtraPlaceTypes.GLACIERS to setOf("Q35666"), ExtraPlaceTypes.HILLS to setOf("Q54050"), ExtraPlaceTypes.RAILWAY to setOf("Q55488", "Q22808404"), ExtraPlaceTypes.POWER to setOf("Q1411996", "Q15911738"),
            ExtraPlaceTypes.STADIUMS to setOf("Q483110", "Q682943", "Q641226", "Q1049757"), ExtraPlaceTypes.BUS_STOPS to setOf("Q494829"),
            ExtraPlaceTypes.BATTLES to setOf("Q178561", "Q188055"), ExtraPlaceTypes.VALLEYS to setOf("Q39816", "Q75520"), ExtraPlaceTypes.CITIES to setOf("Q515", "Q3957", "Q1549591"),
            ExtraPlaceTypes.AIRPORTS to setOf("Q1248784", "Q94993988"), ExtraPlaceTypes.VENUES to setOf("Q17350442", "Q1076486", "Q44782", "Q11822917"),
        )
        for ((k, ids) in expected) assertEquals(ids, ExtraPlaceTypes.byKey(k)!!.classIds, k)
        assertEquals(expected.keys, ExtraPlaceTypes.KEYS)
    }

    @Test fun busStopQ953806IsTodoVerifyAndNotAdmitted() {
        assertTrue(ExtraPlaceTypes.ALL.flatMap { it.classIds }.none { it == "Q953806" })
        assertNull(PlaceCellSource.kindOf(setOf("Q953806"), "Q1", ExtraPlaceTypes.KEYS))
        assertNotNull(PlaceCellSource.kindOf(setOf("Q494829"), "Q1", setOf(ExtraPlaceTypes.BUS_STOPS)))
    }

    @Test fun defaultsAreOnExceptBattlesValleysCitiesAirports() {
        val off = setOf(ExtraPlaceTypes.BATTLES, ExtraPlaceTypes.VALLEYS, ExtraPlaceTypes.CITIES, ExtraPlaceTypes.AIRPORTS)
        assertEquals(ExtraPlaceTypes.KEYS - off, ExtraPlaceTypes.DEFAULT_ON)
        assertEquals(ExtraPlaceTypes.KEYS.size, ExtraPlaceTypes.ALL.map { it.key }.toSet().size)
        assertTrue(ExtraPlaceTypes.SHOWN.map { it.key }.toSet().containsAll(ExtraPlaceTypes.KEYS)) // every switch is offered
        for (t in ExtraPlaceTypes.SHOWN) { assertTrue(t.title.startsWith("Include ")); assertTrue(t.category.isNotBlank()) }
        assertEquals("Include mountains and peaks (adds many places; good for a mountain trip)", ExtraPlaceTypes.fullLabel(ExtraPlaceTypes.byKey(ExtraPlaceTypes.MOUNTAINS)!!))
        assertEquals("Include cities and towns (adds place names like city and town for context, not sights)", ExtraPlaceTypes.fullLabel(ExtraPlaceTypes.byKey(ExtraPlaceTypes.CITIES)!!))
        assertEquals("Include stadiums and cricket grounds", ExtraPlaceTypes.byKey(ExtraPlaceTypes.STADIUMS)!!.title)
    }

    /** 27 Nov night owner list A: always admitted with no switch. Id to (kind, areaLike). */
    private val alwaysOn: Map<String, Pair<String, Boolean>> = mapOf(
        "Q2613100" to (PlaceKinds.TEMPLE to false), "Q337986" to (PlaceKinds.TEMPLE to false), "Q2639699" to (PlaceKinds.TOMB to false), "Q697295" to (PlaceKinds.TEMPLE to false), "Q44613" to (PlaceKinds.TEMPLE to false),
        "Q23413" to (PlaceKinds.FORT to false), "Q162875" to (PlaceKinds.TOMB to false), "Q1473950" to (PlaceKinds.HERITAGE_BUILDING to false), "Q7882520" to (PlaceKinds.HERITAGE_BUILDING to false),
        "Q321053" to (PlaceKinds.MEMORIAL to false), "Q936941" to (PlaceKinds.MONUMENT to false), "Q1010155" to (PlaceKinds.ATTRACTION to true), "Q12323" to (PlaceKinds.ATTRACTION to false), "Q3497167" to (PlaceKinds.ATTRACTION to false),
        "Q131681" to (PlaceKinds.ATTRACTION to true), "Q3215290" to (PlaceKinds.ATTRACTION to true), "Q55470036" to (PlaceKinds.ATTRACTION to true), "Q53060" to (PlaceKinds.MONUMENT to false), "Q2785355" to (PlaceKinds.MONUMENT to false),
        "Q43501" to (PlaceKinds.ATTRACTION to false), "Q34627" to (PlaceKinds.TEMPLE to false), "Q29553" to (PlaceKinds.ATTRACTION to true), "Q2714144" to (PlaceKinds.ATTRACTION to true), "Q1320719" to (PlaceKinds.ATTRACTION to true),
        "Q57831" to (PlaceKinds.FORT to false), "Q853854" to (PlaceKinds.MONUMENT to false), "Q1091037" to (PlaceKinds.STUPA to false), "Q24398318" to (PlaceKinds.TEMPLE to false), "Q188025" to (PlaceKinds.ATTRACTION to true),
        "Q9096832" to (PlaceKinds.ARCHAEOLOGICAL_SITE to true), "Q22746" to (PlaceKinds.ATTRACTION to true), "Q787934" to (PlaceKinds.MUSEUM to false), "Q420962" to (PlaceKinds.ATTRACTION to false), "Q1497019" to (PlaceKinds.ATTRACTION to false),
        "Q1395089" to (PlaceKinds.ATTRACTION to false), "Q13409250" to (PlaceKinds.ATTRACTION to false), "Q179700" to (PlaceKinds.MONUMENT to false),
        // 27 Nov late night (India survey)
        "Q40080" to (PlaceKinds.ATTRACTION to false), "Q35509" to (PlaceKinds.ATTRACTION to false), "Q23442" to (PlaceKinds.ATTRACTION to true), "Q58621988" to (PlaceKinds.TEMPLE to false),
        "Q56242215" to (PlaceKinds.CHURCH to false), "Q120560" to (PlaceKinds.CHURCH to false), "Q15135589" to (PlaceKinds.ATTRACTION to false), "Q194195" to (PlaceKinds.ATTRACTION to false),
        "Q1107656" to (PlaceKinds.ATTRACTION to true), "Q1244922" to (PlaceKinds.ATTRACTION to false), "Q350495" to (PlaceKinds.ATTRACTION to false), "Q736917" to (PlaceKinds.ATTRACTION to true),
        "Q12280" to (PlaceKinds.ATTRACTION to false), "Q1210334" to (PlaceKinds.ATTRACTION to false),
        // 27 Nov late night, second owner list
        "Q1088552" to (PlaceKinds.CHURCH to false), "Q56242250" to (PlaceKinds.CHURCH to false), "Q466449" to (PlaceKinds.TEMPLE to false), "Q21550442" to (PlaceKinds.TEMPLE to false), "Q105889895" to (PlaceKinds.TEMPLE to false), "Q4588528" to (PlaceKinds.TEMPLE to false), "Q7698769" to (PlaceKinds.TEMPLE to false), "Q141140" to (PlaceKinds.TEMPLE to false), "Q7426457" to (PlaceKinds.TEMPLE to false), "Q744099" to (PlaceKinds.FORT to false), "Q35112127" to (PlaceKinds.HERITAGE_BUILDING to false), "Q2319498" to (PlaceKinds.HERITAGE_BUILDING to false), "Q5739200" to (PlaceKinds.HERITAGE_BUILDING to false), "Q5739135" to (PlaceKinds.HERITAGE_BUILDING to false), "Q481289" to (PlaceKinds.HERITAGE_BUILDING to false), "Q24354" to (PlaceKinds.HERITAGE_BUILDING to false), "Q1329623" to (PlaceKinds.HERITAGE_BUILDING to false), "Q1200957" to (PlaceKinds.ATTRACTION to false), "Q570116" to (PlaceKinds.ATTRACTION to false), "Q12518" to (PlaceKinds.MONUMENT to false), "Q1779653" to (PlaceKinds.MONUMENT to false), "Q1584134" to (PlaceKinds.ARCHAEOLOGICAL_SITE to false), "Q740326" to (PlaceKinds.ATTRACTION to false), "Q588140" to (PlaceKinds.MUSEUM to false), "Q3329412" to (PlaceKinds.MUSEUM to false), "Q2772772" to (PlaceKinds.MUSEUM to false), "Q1254933" to (PlaceKinds.ATTRACTION to false), "Q62832" to (PlaceKinds.ATTRACTION to false), "Q148319" to (PlaceKinds.ATTRACTION to false), "Q132510" to (PlaceKinds.ATTRACTION to false), "Q330284" to (PlaceKinds.ATTRACTION to false), "Q158555" to (PlaceKinds.ATTRACTION to false), "Q1068842" to (PlaceKinds.ATTRACTION to false), "Q7315273" to (PlaceKinds.ATTRACTION to true), "Q179049" to (PlaceKinds.ATTRACTION to true), "Q5533772" to (PlaceKinds.ATTRACTION to true), "Q158454" to (PlaceKinds.ATTRACTION to true), "Q5162999" to (PlaceKinds.ATTRACTION to true), "Q1533036" to (PlaceKinds.ATTRACTION to true), "Q162602" to (PlaceKinds.ATTRACTION to true), "Q1402592" to (PlaceKinds.ATTRACTION to true), "Q33837" to (PlaceKinds.ATTRACTION to true), "Q187223" to (PlaceKinds.ATTRACTION to true), "Q170321" to (PlaceKinds.ATTRACTION to true), "Q55818" to (PlaceKinds.ATTRACTION to true),
    )

    @Test fun theAlwaysOnClassesAreAdmittedWithNoSwitchWithTheRightKindAndTolerance() {
        for ((id, ka) in alwaysOn) {
            assertEquals(ka.first, PlaceCellSource.kindOf(setOf(id), "Q1", emptySet()), id)
            assertEquals(ka.second, id in PlaceCellSource.AREA_LIKE_CLASSES, "$id areaLike")
            assertEquals(if (ka.second) 5000.0 else 1000.0, PlaceCellSource.disagreeLimitM(setOf(id), "Q1"), id)
            assertTrue(ExtraPlaceTypes.ALL.none { id in it.classIds }, "$id is not a toggle")
        }
        for (c in PlaceCellSource.RESERVE_LIKE_CLASSES) { assertEquals(300, PlaceCellSource.radiusCapOf(setOf(c)), c); assertTrue(c in PlaceCellSource.AREA_LIKE_CLASSES) }
        assertEquals(300, PlaceCellSource.radiusCapOf(setOf("Q29553"))); assertEquals(300, PlaceCellSource.radiusCapOf(setOf("Q2714144")))
    }

    @Test fun theAlwaysOnClassesAreAdmittedByBothThePerCellAndTheBulkPath() = runTest {
        val rows = alwaysOn.keys.mapIndexed { i, c -> Triple("Q${5000 + i}", "Sight $i", c) }
        val body = """{"results":{"bindings":[${rows.joinToString(",") { (q, t, c) -> """{"item":{"value":"http://www.wikidata.org/entity/$q"},"title":{"value":"$t"},"cls":{"value":"http://www.wikidata.org/entity/$c"},"lat":{"value":"26.9"},"lon":{"value":"75.8"}}""" }}]}}"""
        val http = object : PlaceHttp {
            override suspend fun get(url: String): String = when {
                "/sparql" in url -> body
                "prop=coordinates" in url -> """{"query":{"pages":[]}}"""
                else -> """{"query":{"pages":[${URLDecoder.decode(url.substringAfter("titles=").substringBefore("&"), "UTF-8").split("|").mapIndexed { i, t -> """{"pageid":$i,"title":"$t","extract":"x."}""" }.joinToString(",")}]}}"""
            }
            override suspend fun postForm(url: String, form: Map<String, String>): String = error("no post")
        }
        assertEquals(rows.map { "wd:${it.first}" }.toSet(), WikidataBulkSource(http).fetchBox(GeoBox(26.5, 75.5, 27.5, 76.5)).map { it.id }.toSet())
        val wiki = object : WikipediaSource {
            override suspend fun geosearch(box: GeoBox) = rows.mapIndexed { i, r -> WikiHit(i + 1L, r.second, 26.9, 75.8) }
            override suspend fun pages(pageIds: List<Long>) = rows.mapIndexed { i, r -> WikiPage(i + 1L, r.second, "x.", r.first, 26.9, 75.8) }.filter { it.pageId in pageIds }
        }
        val wd = object : WikidataSource { override suspend fun entities(ids: List<String>) = rows.associate { it.first to WikidataEntity(it.first, setOf(it.third), emptyList(), emptyList(), 26.9, 75.8) } }
        val cell = com.rokid.context.memory.place.Geohash5.encode(26.9, 75.8)
        assertEquals(rows.map { "wd:${it.first}" }.toSet(), PlaceCellSource(wiki, wd, null).fetchCell(cell).places.map { it.id }.toSet())
    }

    @Test fun theNewAreaLikeToggleClassesGetTheFiveKmToleranceAndTheOwnerOutListStaysOut() {
        for (c in listOf("Q39816", "Q515", "Q3957", "Q1549591")) assertEquals(5000.0, PlaceCellSource.disagreeLimitM(setOf(c), "Q1"), c)
        for (c in listOf("Q178561", "Q188055", "Q1248784", "Q94993988", "Q494829", "Q682943", "Q22808404")) assertEquals(1000.0, PlaceCellSource.disagreeLimitM(setOf(c), "Q1"), c)
        // universities and colleges, villages, settlements, constituencies, districts, schools, borders, tehsils, panchayats, hospitals, roads, stations of metro, generic building classes: no class, no switch
        val out = listOf("Q3918", "Q189004", "Q131389368", "Q7603893", "Q56436498", "Q532", "Q486972", "Q54375498", "Q1149652", "Q3914", "Q133346", "Q122987735", "Q2732840", "Q16917", "Q34442", "Q928830", "Q11670533", "Q22808403", "Q41176", "Q811979", "Q618123")
        for (c in out) { assertNull(PlaceCellSource.kindOf(setOf(c), "Q1", ExtraPlaceTypes.KEYS), c); assertTrue(c !in PlaceCellSource.MONUMENT_CLASSES) }
    }

    @Test fun riverQ4022JoinsTheRiversSwitchAreaLikeAndVenuesPortsIsASwitchOnByDefault() {
        assertEquals(5000.0, PlaceCellSource.disagreeLimitM(setOf("Q4022"), "Q1")); assertEquals(100, PlaceCellSource.radiusCapOf(setOf("Q4022")))
        assertNull(PlaceCellSource.kindOf(setOf("Q4022"), "Q1", emptySet()))
        assertEquals(PlaceKinds.ATTRACTION, PlaceCellSource.kindOf(setOf("Q4022"), "Q1", setOf(ExtraPlaceTypes.RIVERS)))
        assertNull(PlaceCellSource.kindOf(setOf("Q4022"), "Q1", ExtraPlaceTypes.KEYS - ExtraPlaceTypes.RIVERS))
        assertTrue(ExtraPlaceTypes.VENUES in ExtraPlaceTypes.DEFAULT_ON)
        assertEquals("Include venues and ports", ExtraPlaceTypes.byKey(ExtraPlaceTypes.VENUES)!!.title)
        for (c in listOf("Q17350442", "Q1076486", "Q44782", "Q11822917")) {
            assertNull(PlaceCellSource.kindOf(setOf(c), "Q1", ExtraPlaceTypes.KEYS - ExtraPlaceTypes.VENUES), c)
            assertEquals(PlaceKinds.ATTRACTION, PlaceCellSource.kindOf(setOf(c), "Q1", ExtraPlaceTypes.DEFAULT_ON), c)
            assertEquals(1000.0, PlaceCellSource.disagreeLimitM(setOf(c), "Q1"), c)
        }
        // both hydroelectric ids sit under the one power switch
        for (c in listOf("Q1411996", "Q15911738")) { assertNull(PlaceCellSource.kindOf(setOf(c), "Q1", ExtraPlaceTypes.KEYS - ExtraPlaceTypes.POWER)); assertNotNull(PlaceCellSource.kindOf(setOf(c), "Q1", setOf(ExtraPlaceTypes.POWER))) }
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

    @Test fun riversAreAreaLikeWithASmallCapAndPointLikeExtrasKeepTheOneKmRule() {
        assertEquals(5000.0, PlaceCellSource.disagreeLimitM(setOf("Q47521"), "Q1")); assertEquals(100, PlaceCellSource.radiusCapOf(setOf("Q47521")))
        assertEquals(5000.0, PlaceCellSource.disagreeLimitM(setOf("Q8502"), "Q1")); assertEquals(300, PlaceCellSource.radiusCapOf(setOf("Q8502")))
        for (c in listOf("Q35666", "Q54050")) assertEquals(5000.0, PlaceCellSource.disagreeLimitM(setOf(c), "Q1"))
        for (c in listOf("Q1827414", "Q55488", "Q1411996", "Q483110")) assertEquals(1000.0, PlaceCellSource.disagreeLimitM(setOf(c), "Q1"), c)
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
        assertEquals(rows.map { "wd:${it.first}" }.toSet(), WikidataBulkSource(http, extraTypes = { ExtraPlaceTypes.KEYS }).fetchBox(box).map { it.id }.toSet())
        val defaults = rows.filter { r -> ExtraPlaceTypes.DEFAULT_ON.any { k -> r.third in ExtraPlaceTypes.byKey(k)!!.classIds } }.map { "wd:${it.first}" }.toSet()
        assertEquals(defaults, WikidataBulkSource(http, extraTypes = { ExtraPlaceTypes.DEFAULT_ON }).fetchBox(box).map { it.id }.toSet()) // the four new switches add nothing until turned on
        // one switch off: only that type's places leave
        val noRivers = WikidataBulkSource(http, extraTypes = { ExtraPlaceTypes.KEYS - ExtraPlaceTypes.RIVERS }).fetchBox(box).map { it.id }.toSet()
        val riverIds = rows.filter { it.third in setOf("Q47521", "Q4022") }.map { it.first }
        assertEquals(rows.map { "wd:${it.first}" }.toSet() - riverIds.map { "wd:$it" }.toSet(), noRivers)
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
        assertEquals(rows.map { "wd:${it.first}" }.toSet(), PlaceCellSource(wiki, wd, null, extraTypes = { ExtraPlaceTypes.KEYS }).fetchCell(cell).places.map { it.id }.toSet())
        val rivers = PlaceCellSource(wiki, wd, null, extraTypes = { setOf(ExtraPlaceTypes.RIVERS) }).fetchCell(cell).places
        assertEquals(riverIds.map { "wd:$it" }.toSet(), rivers.map { it.id }.toSet()); assertTrue(rivers.all { it.radiusM <= 100 && it.kind == PlaceKinds.ATTRACTION })
    }
}
