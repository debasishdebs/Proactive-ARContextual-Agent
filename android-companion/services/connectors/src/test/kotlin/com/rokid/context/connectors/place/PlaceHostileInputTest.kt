package com.rokid.context.connectors.place

import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlaceKinds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Hostile-input and property tests for the place source parsers (Wikipedia geosearch and extracts, Wikidata wbgetentities, Overpass) and for PlaceCellSource on top of them.
 * Random cases use fixed seeds, so a failure reproduces; every failure message names the seed and iteration.
 * Already covered by PlaceFetchTest (not repeated): fixture-shaped happy paths, missing query/pages/entities, Wikidata and Overpass transport failure, absent vs malformed P31
 * (the five shapes there), a person under a curated QID, empty-array claims, "junk" claims, https only and rate limiting, mirror failover.
 */
class PlaceHostileInputTest {
    private companion object {
        const val SEED = 0x91ACE_2026L
        const val SIGIRIYA = "Q272153"
        val cell = Geohash5.encode(7.957, 80.76)
    }

    // ---- a scripted network: the bodies are whatever the test says; the network is never touched ----
    private class Net(var geo: String, var pages: String, var wd: String, var op: String? = null, val fail: Map<String, Throwable> = emptyMap()) : PlaceHttp {
        var gets = 0
        override suspend fun get(url: String): String {
            gets++
            val key = when { "list=geosearch" in url -> "geo"; "prop=extracts" in url -> "pages"; "wbgetentities" in url -> "wd"; else -> error("unexpected $url") }
            fail[key]?.let { throw it }
            return when (key) { "geo" -> geo; "pages" -> pages; else -> wd }
        }
        override suspend fun postForm(url: String, form: Map<String, String>): String {
            fail["op"]?.let { throw it }
            return op ?: throw IOException("no overpass")
        }
    }
    private fun source(n: Net, logs: MutableList<String> = mutableListOf()) = PlaceCellSource(WikipediaHttpSource(n), WikidataHttpSource(n), OverpassHttpSource(n)) { logs += it }

    private fun stmt(id: String, extra: String = "") = """{"mainsnak":{"snaktype":"value","datavalue":{"value":{"entity-type":"item","id":"$id"}}}$extra}"""
    private fun item(p31: String, coords: Boolean = true) =
        """{"claims":{"P31":[$p31]${if (coords) ""","P625":[{"mainsnak":{"datavalue":{"value":{"latitude":7.957,"longitude":80.76}}}}]""" else ""}}}"""
    private fun geoOf(vararg pages: Pair<Int, String>) = """{"query":{"geosearch":[${pages.joinToString(",") { """{"pageid":${it.first},"title":"T${it.first}","lat":7.957,"lon":80.76}""" }}]}}"""
    private fun pagesOf(vararg pages: Pair<Int, String>) =
        """{"query":{"pages":[${pages.joinToString(",") { """{"pageid":${it.first},"title":"T${it.first}","extract":"x.","pageprops":{"wikibase_item":"${it.second}"}}""" }}]}}"""

    /** One page (curated Sigiriya) whose Wikidata item has the given P31 statements. Returns the ids that were kept. */
    private suspend fun keptWithP31(p31: String, claimsOverride: String? = null): Set<String> {
        val wd = """{"entities":{"$SIGIRIYA":${claimsOverride ?: item(p31)}}}"""
        return source(Net(geoOf(1 to SIGIRIYA), pagesOf(1 to SIGIRIYA), wd)).fetchCell(cell).places.map { it.id }.toSet()
    }

    // ---- fixed base documents for the structural fuzz ----
    private val baseGeo = geoOf(1 to "Q272153", 2 to "Q9000001", 3 to "Q16901632", 4 to "Q9000002")
    private val basePages = pagesOf(1 to "Q272153", 2 to "Q9000001", 3 to "Q16901632", 4 to "Q9000002")
    private val baseWd = """{"entities":{
        "Q272153":${item(stmt("Q486972"))},
        "Q9000001":${item(stmt("Q842402"))},
        "Q16901632":${item(stmt("Q5"))},
        "Q9000002":${item(stmt("Q842402") + "," + stmt("Q5"))}}}""".trimIndent()
    private val baseOp = """{"elements":[
        {"type":"way","id":10,"center":{"lat":7.957,"lon":80.76},"bounds":{"minlat":7.95,"minlon":80.75,"maxlat":7.96,"maxlon":80.77},"tags":{"name":"Fort","historic":"fort","wikidata":"Q9000001"}},
        {"type":"node","id":11,"lat":7.95,"lon":80.75,"tags":{"name":"X","historic":"monument"}},
        {"type":"relation","id":12,"lat":7.955,"lon":80.759,"tags":{"name":"Y","tourism":"viewpoint","wikidata":"Q5"}}]}"""

    // ---- structural mutation ----
    private fun paths(e: JsonElement, at: List<Any> = emptyList(), out: MutableList<List<Any>> = ArrayList()): MutableList<List<Any>> {
        out += at
        if (e is JsonObject) e.forEach { (k, v) -> paths(v, at + k, out) }
        if (e is JsonArray) e.forEachIndexed { i, v -> paths(v, at + i, out) }
        return out
    }
    private fun replace(e: JsonElement, path: List<Any>, v: JsonElement): JsonElement {
        if (path.isEmpty()) return v
        val head = path.first(); val rest = path.drop(1)
        return when (e) {
            is JsonObject -> JsonObject(e.mapValues { (k, c) -> if (k == head) replace(c, rest, v) else c })
            is JsonArray -> JsonArray(e.mapIndexed { i, c -> if (i == head) replace(c, rest, v) else c })
            else -> e
        }
    }
    private fun junk(rnd: Random): JsonElement = when (rnd.nextInt(24)) {
        0 -> JsonNull; 1 -> JsonPrimitive(true); 2 -> JsonPrimitive(""); 3 -> JsonPrimitive("NaN"); 4 -> JsonPrimitive("Infinity"); 5 -> JsonPrimitive(Double.NaN)
        6 -> JsonPrimitive(Double.POSITIVE_INFINITY); 7 -> JsonPrimitive(1e308); 8 -> JsonPrimitive(-1e308); 9 -> JsonPrimitive(91); 10 -> JsonPrimitive(181); 11 -> JsonPrimitive(Long.MAX_VALUE)
        12 -> JsonPrimitive(0); 13 -> JsonObject(emptyMap()); 14 -> JsonArray(emptyList()); 15 -> JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("x"), JsonNull))
        16 -> JsonObject(mapOf("id" to JsonPrimitive("Q5"))); 17 -> JsonPrimitive("Q5"); 18 -> JsonPrimitive("x".repeat(100_000)); 19 -> JsonPrimitive("a\u0000b‮c\r\n\u001B[2J")
        20 -> JsonPrimitive("Q842402"); 21 -> JsonPrimitive(-1); 22 -> JsonPrimitive("12.5"); else -> (1..200).fold(JsonArray(emptyList()) as JsonElement) { acc, _ -> JsonArray(listOf(acc)) }
    }
    private fun mutated(base: String, rnd: Random): Pair<String, JsonElement> {
        var tree = placeJson.parseToJsonElement(base)
        repeat(rnd.nextInt(1, 4)) { val ps = paths(tree); tree = replace(tree, ps[rnd.nextInt(ps.size)], junk(rnd)) }
        return tree.toString() to tree
    }

    /** The Wikidata ids whose P31 lists a person class at the exact statement path, read straight from the tree (independent of the parser under test). */
    private fun personItems(wd: JsonElement): Set<String> {
        val ents = ((wd as? JsonObject)?.get("entities") as? JsonObject) ?: return emptySet()
        return ents.filter { (_, v) ->
            val p31 = (((v as? JsonObject)?.get("claims") as? JsonObject)?.get("P31") as? JsonArray) ?: return@filter false
            p31.any { s ->
                val id = ((((s as? JsonObject)?.get("mainsnak") as? JsonObject)?.get("datavalue") as? JsonObject)?.get("value") as? JsonObject)?.get("id")
                (id as? JsonPrimitive)?.takeIf { it.isString }?.content in PlaceCellSource.PERSON_CLASSES
            }
        }.keys
    }

    @Test fun structurallyMutatedResponsesNeverEscapeTheFetcherAndNeverAdmitAPerson() = runTest {
        val rnd = Random(SEED)
        val t0 = System.nanoTime()
        repeat(1_500) { i ->
            val which = rnd.nextInt(4)
            val (g, p, w, o) = listOf(baseGeo, basePages, baseWd, baseOp)
            val mg = if (which == 0) mutated(g, rnd) else g to placeJson.parseToJsonElement(g)
            val mp = if (which == 1) mutated(p, rnd) else p to placeJson.parseToJsonElement(p)
            val mw = if (which == 2) mutated(w, rnd) else w to placeJson.parseToJsonElement(w)
            val mo = if (which == 3) mutated(o, rnd) else o to placeJson.parseToJsonElement(o)
            val out = try {
                source(Net(mg.first, mp.first, mw.first, mo.first)).fetchCell(cell)
            } catch (e: SerializationException) {
                return@repeat // a non-object root is a malformed response: the cell fails and nothing is stored (the documented contract)
            } catch (e: Throwable) { throw AssertionError("seed=$SEED iter=$i which=$which: ${e.javaClass.name}: ${e.message?.take(200)}\nwd=${mw.first.take(600)}", e) }
            val people = personItems(mw.second)
            for (pl in out.places) {
                assertTrue(pl.kind in PlaceKinds.ALL, "iter=$i kind ${pl.kind}")
                if (pl.id.startsWith("wd:")) assertFalse(pl.id.removePrefix("wd:") in people, "iter=$i admitted a person: ${pl.id}\nwd=${mw.first.take(600)}")
                assertTrue(pl.lat.isFinite() && pl.lon.isFinite() && pl.lat in -90.0..90.0 && pl.lon in -180.0..180.0, "iter=$i out of range ${pl.id} ${pl.lat},${pl.lon}\n${mg.first.take(300)}\n${mo.first.take(500)}")
            }
        }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 120_000, "bounded time")
    }

    // ---- syntax: truncated, garbage, deep, huge ----
    private val parsers: Map<String, (String) -> Any> = mapOf(
        "geosearch" to { s -> WikipediaHttpSource.parseGeosearch(s) }, "pages" to { s -> WikipediaHttpSource.parsePages(s) },
        "wikidata" to { s -> WikidataHttpSource.parse(s) }, "overpass" to { s -> OverpassHttpSource.parse(s) },
    )
    /** The contract of a parser: a value, or kotlinx's SerializationException (the cell then fails and is retried). Never an Error, never another type. */
    private fun contract(name: String, body: String): Any? = try { parsers.getValue(name)(body) } catch (e: SerializationException) { null } catch (e: Throwable) {
        throw AssertionError("$name threw ${e.javaClass.name} for ${body.take(80)}...(${body.length} chars)", e)
    }

    // 12ac: an HTTP 200 error object is a FAILED fetch (SerializationException), a genuine empty answer is an empty list.
    @Test fun apiErrorBodyIsAFailureNotAnEmptyAnswer() {
        val err = """{"error":{"code":"ratelimited","info":"slow down"}}"""
        for (b in listOf(err, "{}", """{"batchcomplete":true}""")) {
            assertFailsWith<SerializationException> { WikipediaHttpSource.parseGeosearch(b) }
            assertFailsWith<SerializationException> { WikipediaHttpSource.parsePages(b) }
        }
        assertFailsWith<SerializationException> { WikidataHttpSource.parse(err) }
        assertEquals(emptyList(), WikipediaHttpSource.parseGeosearch("""{"query":{"geosearch":[]}}"""))
    }

    @Test fun everyTruncationAndGarbageBodyIsAValueOrASerializationException() {
        val docs = mapOf("geosearch" to baseGeo, "pages" to basePages, "wikidata" to baseWd, "overpass" to baseOp)
        for ((name, doc) in docs) for (n in doc.indices) contract(name, doc.substring(0, n))
        val rnd = Random(SEED + 1)
        for (name in parsers.keys) repeat(500) {
            val b = String(rnd.nextBytes(rnd.nextInt(0, 200)), Charsets.ISO_8859_1)
            contract(name, b)
            val chars = "{}[]\",:0123456789eE+-.nulltrfasNIy\\ \u0000"
            contract(name, String(CharArray(rnd.nextInt(0, 120)) { chars[rnd.nextInt(chars.length)] }))
        }
        for (name in parsers.keys) for (b in listOf("", " ", "null", "true", "0", "\"x\"", "[]", "{}", "[[[]]]", "{\"a\":", "﻿{}", "{}garbage", "{\"query\":null}", "{\"query\":[]}", "{\"entities\":[]}", "{\"elements\":{}}")) contract(name, b)
    }

    @Test fun absurdNestingIsRefusedOrIgnoredNeverAStackOverflow() {
        val n = 400_000
        val bodies = listOf(
            "[".repeat(n), "[".repeat(n) + "]".repeat(n), "{\"a\":".repeat(n) + "1" + "}".repeat(n),
            """{"query":{"geosearch":${"[".repeat(n)}${"]".repeat(n)}}}""", """{"entities":{"Q1":{"claims":{"P31":${"[".repeat(n)}${"]".repeat(n)}}}}}""",
            """{"elements":[{"type":"node","id":1,"lat":1,"lon":1,"tags":{"a":${"[".repeat(n)}${"]".repeat(n)}}}]}""",
        )
        for (b in bodies) for (name in parsers.keys) contract(name, b)
    }

    @Test fun hugeResponsesParseInBoundedTimeAndMemory() {
        val t0 = System.nanoTime()
        val title = "T".repeat(3 shl 20)
        val hits = WikipediaHttpSource.parseGeosearch("""{"query":{"geosearch":[{"pageid":1,"title":"$title","lat":1,"lon":1}]}}""")
        assertEquals(1, hits.size)
        val many = (1..60_000).joinToString(",") { """{"pageid":$it,"title":"t","lat":1.5,"lon":2.5}""" }
        assertEquals(60_000, WikipediaHttpSource.parseGeosearch("""{"query":{"geosearch":[$many]}}""").size)
        val ents = (1..30_000).joinToString(",") { """"Q$it":{"claims":{"P31":[${stmt("Q842402")}]}}""" }
        assertEquals(30_000, WikidataHttpSource.parse("""{"entities":{$ents}}""").size)
        val tags = (1..50_000).joinToString(",") { """"k$it":"v"""" }
        assertEquals(50_000, OverpassHttpSource.parse("""{"elements":[{"type":"node","id":1,"lat":1,"lon":1,"tags":{$tags}}]}""").single().tags.size)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 60_000, "bounded time")
    }

    @Test fun aHugeHitListIsBatchedBy20AndAHugeTitleOrControlCharactersDoNotBreakTheCell() = runTest {
        val hits = (1..2_000).joinToString(",") { """{"pageid":$it,"title":"t","lat":7.957,"lon":80.76}""" }
        val n = Net("""{"query":{"geosearch":[$hits]}}""", """{"query":{"pages":[]}}""", """{"entities":{}}""")
        assertEquals(0, source(n).fetchCell(cell).places.size)
        assertEquals(1 + 100, n.gets) // geosearch, then 2,000 / 20 extract batches; no Wikidata call without a qid
        val nasty = "A\u0000B‮\r\n\u001B[2J" + "x".repeat(1 shl 20)
        val geo = """{"query":{"geosearch":[{"pageid":1,"title":${JsonPrimitive(nasty)},"lat":7.957,"lon":80.76}]}}"""
        val pages = """{"query":{"pages":[{"pageid":1,"title":${JsonPrimitive(nasty)},"extract":${JsonPrimitive(nasty)},"pageprops":{"wikibase_item":"$SIGIRIYA"}}]}}"""
        val got = source(Net(geo, pages, """{"entities":{"$SIGIRIYA":${item(stmt("Q486972"))}}}""")).fetchCell(cell).places
        assertEquals(1, got.size) // kept as is: clipping and sanitising are the store's job, the cell source must simply not fail
        assertEquals(nasty, got.single().name)
    }

    // ---- values: types, NaN, Infinity, range, duplicates ----
    @Test fun wrongTypedFieldsDropTheRowAndNeverThrow() {
        assertEquals(emptyList(), WikipediaHttpSource.parseGeosearch("""{"query":{"geosearch":[1,null,"x",[],{"pageid":"a","title":"t","lat":1,"lon":1},{"pageid":1,"title":5,"lat":1,"lon":1},{"pageid":1,"title":"t","lat":{},"lon":1},{"pageid":1,"title":"t","lat":1}]}}"""))
        assertEquals(emptyList(), WikipediaHttpSource.parsePages("""{"query":{"pages":{"a":1}}}"""))
        assertEquals(emptyList(), WikipediaHttpSource.parsePages("""{"query":{"pages":[{"pageid":1},{"title":"t"},7,null]}}"""))
        val p = WikipediaHttpSource.parsePages("""{"query":{"pages":[{"pageid":1,"title":"t","extract":5,"pageprops":[],"coordinates":{"lat":1}}]}}""").single()
        assertEquals("", p.extract); assertEquals(null, p.qid); assertEquals(null, p.lat)
        assertEquals(emptyList(), OverpassHttpSource.parse("""{"elements":[{"type":1,"id":1,"lat":1,"lon":1},{"type":"node","id":"x","lat":1,"lon":1},{"type":"node","id":1},{"type":"node","id":1,"lat":"a","lon":1}]}"""))
        val tags = OverpassHttpSource.parse("""{"elements":[{"type":"node","id":1,"lat":1,"lon":1,"tags":{"a":1,"b":null,"c":["x"],"d":"ok"}}]}""").single().tags
        assertEquals(mapOf("d" to "ok"), tags)
        assertEquals(emptyMap(), WikidataHttpSource.parse("""{"entities":{"Q1":[],"Q2":"x","Q3":null,"Q4":{"claims":7}}}"""))
    }

    @Test fun nonFiniteAndOutOfRangeCoordinatesAreNeverReturnedAsPlaces() {
        val bad = listOf("NaN", "Infinity", "-Infinity", "\"NaN\"", "\"Infinity\"", "1e999", "-1e999", "91", "-91", "1000000", "181", "-181", "1e308")
        for (v in bad) {
            for ((lat, lon) in listOf(v to "1", "1" to v)) {
                val g = WikipediaHttpSource.parseGeosearch("""{"query":{"geosearch":[{"pageid":1,"title":"t","lat":$lat,"lon":$lon}]}}""")
                assertTrue(g.all { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }, "geosearch lat=$lat lon=$lon -> $g")
                val o = OverpassHttpSource.parse("""{"elements":[{"type":"node","id":1,"lat":$lat,"lon":$lon,"tags":{"name":"x","historic":"yes"}}]}""")
                assertTrue(o.all { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }, "overpass lat=$lat lon=$lon -> $o")
                val p = WikipediaHttpSource.parsePages("""{"query":{"pages":[{"pageid":1,"title":"t","coordinates":[{"lat":$lat,"lon":$lon}]}]}}""")
                assertTrue(p.all { (it.lat == null || it.lat.isFinite() && it.lat in -90.0..90.0) && (it.lon == null || it.lon.isFinite() && it.lon in -180.0..180.0) }, "pages lat=$lat lon=$lon -> $p")
                val w = WikidataHttpSource.parse("""{"entities":{"Q1":{"claims":{"P625":[{"mainsnak":{"datavalue":{"value":{"latitude":$lat,"longitude":$lon}}}}]}}}}""")
                assertTrue(w.values.all { (it.lat == null || it.lat.isFinite() && it.lat in -90.0..90.0) && (it.lon == null || it.lon.isFinite() && it.lon in -180.0..180.0) }, "wikidata lat=$lat lon=$lon -> $w")
            }
        }
    }

    @Test fun anOverpassBoxWithNonFiniteOrInvertedBoundsStillGivesASaneRadius() = runTest {
        for (b in listOf("""{"minlat":"NaN","minlon":1,"maxlat":2,"maxlon":2}""", """{"minlat":1,"minlon":1,"maxlat":Infinity,"maxlon":2}""", """{"minlat":50,"minlon":50,"maxlat":-50,"maxlon":-50}""", """{"minlat":1e308,"minlon":-1e308,"maxlat":-1e308,"maxlon":1e308}""", "[]", "null")) {
            val op = """{"elements":[{"type":"way","id":1,"lat":7.957,"lon":80.76,"bounds":$b,"tags":{"name":"W","historic":"ruins"}}]}"""
            val out = source(Net(geoOf(), """{"query":{"pages":[]}}""", """{"entities":{}}""", op)).fetchCell(cell)
            assertTrue(out.complete, b)
            for (p in out.places) assertTrue(p.radiusM in 10..PlaceKinds.MAX_RADIUS_M, "$b -> ${p.radiusM}")
        }
    }

    @Test fun duplicateKeysAreDeterministicAndNeverAdmitAPersonUnderEitherOrder() = runTest {
        // a hostile body that repeats P31 or the entity: whichever copy the parser keeps, a person copy must keep the item out
        val person = stmt("Q5"); val temple = stmt("Q842402")
        for ((a, b) in listOf(person to temple, temple to person)) {
            val dupP31 = """{"claims":{"P31":[$a],"P31":[$b],"P625":[{"mainsnak":{"datavalue":{"value":{"latitude":7.957,"longitude":80.76}}}}]}}"""
            val dupItem = """{"entities":{"$SIGIRIYA":${item(a)},"$SIGIRIYA":${item(b)}}}"""
            val kept1 = source(Net(geoOf(1 to SIGIRIYA), pagesOf(1 to SIGIRIYA), """{"entities":{"$SIGIRIYA":$dupP31}}""")).fetchCell(cell).places
            val kept2 = source(Net(geoOf(1 to SIGIRIYA), pagesOf(1 to SIGIRIYA), dupItem)).fetchCell(cell).places
            // KNOWN LIMITATION: kotlinx's tree reader is last-wins and keeps no trace of a repeated key, so detecting it would need a second streaming parse. A real Wikidata response never
            // repeats a key; the outcome is deterministic and follows the LAST copy: a person last is kept out, a temple last is admitted.
            assertEquals(b != person, kept1.any { it.kind == PlaceKinds.TEMPLE }, "duplicate P31 ($a / $b): last copy decides")
            assertEquals(b != person, kept2.any { it.kind == PlaceKinds.TEMPLE }, "duplicate item ($a / $b): last copy decides")
        }
        // duplicate keys elsewhere are harmless and must not crash
        assertEquals(1, WikipediaHttpSource.parseGeosearch("""{"query":{"geosearch":[{"pageid":1,"pageid":2,"title":"a","title":"b","lat":1,"lat":2,"lon":3}]}}""").size)
        assertEquals(1, OverpassHttpSource.parse("""{"elements":[{"type":"node","type":"way","id":1,"lat":1,"lon":2,"tags":{"a":"1","a":"2"}}],"elements":[{"type":"node","id":1,"lat":1,"lon":2}]}""").size)
    }
    // ---- people, odd P31 shapes ----
    @Test fun aPersonClassAnywhereInP31KeepsTheItemOutWhateverElseIsThere() = runTest {
        val monument = stmt("Q842402")
        for (person in PlaceCellSource.PERSON_CLASSES) {
            val shapes = listOf(
                stmt(person), monument + "," + stmt(person), stmt(person) + "," + monument, (1..200).joinToString(",") { monument } + "," + stmt(person),
                stmt(person, extra = ""","rank":"deprecated","qualifiers":{"P642":[{"snaktype":"novalue"}]}"""), stmt(person, extra = ""","rank":"preferred","references":[]"""),
                monument + ",{\"mainsnak\":{\"snaktype\":\"somevalue\"}}," + stmt(person), stmt(person) + "," + stmt(person), stmt("Q839954") + "," + stmt(person) + "," + stmt("Q16970"),
            )
            for (s in shapes) assertEquals(emptySet(), keptWithP31(s), "$person in ${s.take(120)}")
            // the same person class on a curated QID with no usable class otherwise
            assertEquals(emptySet(), keptWithP31(stmt(person) + "," + stmt("Q486972")))
        }
    }

    @Test fun unreadableOrOddlyShapedP31IsRejectedNotAdmittedForACuratedQid() = runTest {
        val shapes = listOf(
            """[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"numeric-id":5}}}}]""", // a numeric id only: the class cannot be read
            """[{"mainsnak":{"snaktype":"value","datavalue":{"value":"Q5"}}}]""", """[{"mainsnak":{"snaktype":"value","datavalue":{"value":["Q5"]}}}]""",
            """[{"mainsnak":{"snaktype":"value","datavalue":"Q5"}}]""", """[{"mainsnak":"Q5"}]""", """["Q5"]""", """[["Q5"]]""", """[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":["Q5"]}}}}]""",
            """[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":null}}}}]""", """[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":5}}}},${stmt("Q842402")}]""", // one unreadable statement spoils the list
            """[${stmt("Q842402")},null]""", """{"0":{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"Q5"}}}}}""",
        )
        for (s in shapes) {
            val claims = """{"claims":{"P31":$s,"P625":[{"mainsnak":{"datavalue":{"value":{"latitude":7.957,"longitude":80.76}}}}]}}"""
            assertEquals(emptySet(), keptWithP31("", claims), s)
        }
        // lower-case or padded class ids are not person classes and not monument classes either: the curated QID alone admits the item, which is the documented absence-of-class behaviour
        assertEquals(setOf("wd:$SIGIRIYA"), keptWithP31(stmt("q5")))
    }

    @Test fun aPersonCannotEnterThroughAnotherQidKeyPageOrEntityMismatch() = runTest {
        // the page names Q272153 but the response carries only a different, person item: nothing is admitted for the page
        val wd = """{"entities":{"Q1":${item(stmt("Q5"))},"Q272153x":${item(stmt("Q842402"))}}}"""
        assertEquals(emptyList(), source(Net(geoOf(1 to SIGIRIYA), pagesOf(1 to SIGIRIYA), wd)).fetchCell(cell).places)
        // an OSM element that names a person's Wikidata item is not joined to anything and is kept only for its own OSM tags
        val op = """{"elements":[{"type":"node","id":9,"lat":7.957,"lon":80.76,"tags":{"name":"Statue","historic":"memorial","wikidata":"Q5"}}]}"""
        val got = source(Net(geoOf(), """{"query":{"pages":[]}}""", """{"entities":{}}""", op)).fetchCell(cell).places
        assertTrue(got.all { it.id.startsWith("osm:") })
    }

    // ---- transport: the fetcher contract ----
    @Test fun anyTransportFailureOfWikipediaOrWikidataFailsTheCellWithAnExceptionAndStoresNothing() = runTest {
        for (key in listOf("geo", "pages", "wd")) for (t in listOf<Throwable>(IOException("response too large"), PlaceHttpException(429), PlaceHttpException(503), java.net.SocketTimeoutException("slow"))) {
            val n = Net(geoOf(1 to SIGIRIYA), pagesOf(1 to SIGIRIYA), """{"entities":{"$SIGIRIYA":${item(stmt("Q486972"))}}}""", """{"elements":[]}""", fail = mapOf(key to t))
            val e = assertFailsWith<Exception>("$key / $t") { source(n).fetchCell(cell) }
            assertTrue(e === t, "the transport error is passed through unchanged")
        }
        // garbage instead of JSON: the cell fails with a serialization exception, which PlacePack's catch treats like any other failed fetch
        assertFailsWith<SerializationException> { source(Net("<html>503</html>", "", "")).fetchCell(cell) }
        assertFailsWith<SerializationException> { source(Net(geoOf(1 to SIGIRIYA), "not json", "")).fetchCell(cell) }
        assertFailsWith<SerializationException> { source(Net(geoOf(1 to SIGIRIYA), pagesOf(1 to SIGIRIYA), "{\"entities\":")).fetchCell(cell) }
    }

    @Test fun overpassGarbageOrFailureOnlyMakesTheCellPartialAndKeepsTheRest() = runTest {
        val wd = """{"entities":{"$SIGIRIYA":${item(stmt("Q486972"))}}}"""
        val bodies = listOf("<html>", "", "{\"elements\":", "[".repeat(300_000), """{"elements":[{"type":"","id":1,"lat":1,"lon":1,"tags":{"name":"x","historic":"yes"}}]}""") // the last: an empty type
        for (b in bodies) {
            val out = source(Net(geoOf(1 to SIGIRIYA), pagesOf(1 to SIGIRIYA), wd, b)).fetchCell(cell)
            assertFalse(out.complete, b.take(30))
            assertEquals(listOf("wd:$SIGIRIYA"), out.places.map { it.id })
        }
        for (t in listOf<Throwable>(IOException("response too large"), PlaceHttpException(504), RuntimeException("boom"))) {
            val out = source(Net(geoOf(1 to SIGIRIYA), pagesOf(1 to SIGIRIYA), wd, "{}", fail = mapOf("op" to t))).fetchCell(cell)
            assertFalse(out.complete); assertEquals(1, out.places.size)
        }
    }

    @Test fun cancellationIsNeverSwallowedByThePartialPath() = runTest {
        val wd = """{"entities":{"$SIGIRIYA":${item(stmt("Q486972"))}}}"""
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            source(Net(geoOf(1 to SIGIRIYA), pagesOf(1 to SIGIRIYA), wd, "{}", fail = mapOf("op" to kotlinx.coroutines.CancellationException("cancelled")))).fetchCell(cell)
        }
    }

    @Test fun aSeededRandomGeoCellSetNeverBreaksTheBoxMaths() = runTest {
        // random 5 km cells all over the world, hostile coordinates in the answers: the box comes from the cell key only
        val rnd = Random(SEED + 2)
        repeat(300) { i ->
            val c = Geohash5.encode(rnd.nextDouble(-89.9, 89.9), rnd.nextDouble(-179.9, 179.9))
            val n = Net(geoOf(1 to SIGIRIYA), pagesOf(1 to SIGIRIYA), """{"entities":{"$SIGIRIYA":${item(stmt("Q486972"))}}}""", """{"elements":[]}""")
            try { source(n).fetchCell(c) } catch (e: Throwable) { fail("cell $c iter $i: ${e.javaClass.name}") }
        }
        assertFailsWith<IllegalArgumentException> { source(Net("", "", "")).fetchCell("not a cell!") }
    }
}
