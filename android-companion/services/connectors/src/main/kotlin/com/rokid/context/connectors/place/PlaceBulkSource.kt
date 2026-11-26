package com.rokid.context.connectors.place

import com.rokid.context.memory.place.BulkSource
import com.rokid.context.memory.place.BulkTooBig
import com.rokid.context.memory.place.FetchedPlace
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.zoneCheck
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * BULK discovery of one box (owner decision 27 Nov; design 2026-11-27-large-area-seeding-design.md sections 9 and 10, measured on a Mac):
 *  1. ONE Wikidata SPARQL query for the box (`SERVICE wikibase:box`, English-Wikipedia sitelink join, `OPTIONAL wdt:P31`, `ORDER BY ?item`, no sub-select; paged by LIMIT/OFFSET) gives every item with an article and a coordinate;
 *  2. the app's class rule ([PlaceCellSource.kindOf], people never) keeps the qualifying ones;
 *  3. Wikipedia `prop=coordinates`, 50 titles a call: the 1 km rule (5 km for area-like classes, see [PlaceCellSource.disagreeLimitM]); the stored point stays Wikidata's;
 *  4. Wikipedia intros, 20 titles a call, 3 sentences, ONLY for the places that survived.
 * A SPARQL timeout (HTTP 500/502/504, a read timeout, a timeout body) or a result of more than [maxPages] pages throws [BulkTooBig] so the pack splits the box; HTTP 429/503 are retried twice (the shared guard waits first),
 * anything else (an error body, a parse error, a failed intro or coordinate call) is an ordinary exception: the pack walks the box's cells with the per-cell path instead. Logs carry counts only.
 */
class WikidataBulkSource(
    private val http: PlaceHttp,
    private val extraTypes: () -> Set<String> = { emptySet() },
    private val log: (String) -> Unit = {},
    private val sparqlHost: String = "query.wikidata.org",
    private val wikiHost: String = "en.wikipedia.org",
    private val pageRows: Int = PAGE_ROWS,
    private val maxPages: Int = MAX_PAGES,
) : BulkSource {

    private class Item(val title: String, val classes: MutableSet<String> = HashSet(), val lat: Double, val lon: Double)

    override suspend fun fetchBox(box: GeoBox): List<FetchedPlace> {
        val items = LinkedHashMap<String, Item>()
        var calls = 0
        var offset = 0
        var pages = 0
        while (true) {
            zoneCheck()
            val body = sparql(query(box, pageRows, offset)); calls++
            val rows = parseRows(body, items)
            pages++
            if (rows < pageRows) break
            if (pages >= maxPages) throw BulkTooBig("rows over ${pageRows * maxPages}")
            offset += pageRows
        }
        val extra = extraTypes()
        val qualifying = items.entries.filter { (q, it) -> it.classes.none { c -> c in PlaceCellSource.PERSON_CLASSES } && PlaceCellSource.kindOf(it.classes, q, extra) != null }
        // 1 km rule (5 km for area-like classes), BEFORE the intros: a dropped place costs no intro call
        val coords = HashMap<String, Pair<Double, Double>>()
        for (chunk in qualifying.map { it.value.title }.distinct().chunked(COORD_BATCH)) { zoneCheck(); coords += parseCoordinates(http.get(coordinatesUrl(chunk))); calls++ }
        var dropped = 0; var wide = 0
        val kept = qualifying.filter { (q, it) ->
            val wp = coords[it.title] ?: return@filter true // no Wikipedia coordinate: nothing to compare, kept (as in the per-cell rule)
            val limit = PlaceCellSource.disagreeLimitM(it.classes, q)
            when {
                PlaceCellSource.coordsDisagree(wp.first, wp.second, it.lat, it.lon, limit) -> { dropped++; false }
                limit > PlaceCellSource.COORD_DISAGREE_M && PlaceCellSource.coordsDisagree(wp.first, wp.second, it.lat, it.lon) -> { wide++; true }
                else -> true
            }
        }
        val intros = HashMap<String, WikiPage>()
        for (chunk in kept.map { it.value.title }.distinct().chunked(INTRO_BATCH)) {
            zoneCheck()
            val body = http.get(introUrl(chunk)); calls++
            val norm = parseNormalized(body)
            for (p in WikipediaHttpSource.parsePages(body)) { intros[p.title] = p; norm.filterValues { v -> v == p.title }.keys.forEach { k -> intros[k] = p } }
        }
        val out = kept.map { (q, it) ->
            val kind = PlaceCellSource.kindOf(it.classes, q, extra)!!
            val cap = PlaceCellSource.radiusCapOf(it.classes)
            val page = intros[it.title]
            FetchedPlace("wd:$q", null, it.title, kind, it.lat, it.lon, PlaceKinds.defaultRadiusM(kind).coerceAtMost(cap ?: Int.MAX_VALUE), page?.extract.orEmpty(), "wikipedia:en", page?.let { p -> "page:${p.pageId}" } ?: "wikidata:bulk")
        }
        log("bulk box rows=${items.size} qualifying=${qualifying.size} kept=${out.size} coordDropped=$dropped coordAllowedWide=$wide calls=$calls")
        return out
    }

    /** One SPARQL call: a timeout or a gateway error asks for a smaller box, a rate limit is retried twice. */
    private suspend fun sparql(q: String): String {
        var attempt = 0
        while (true) {
            try {
                val body = http.get("https://$sparqlHost/sparql?format=json&query=${enc(q)}")
                if (body.contains("TimeoutException") && !body.contains("\"bindings\"")) throw BulkTooBig("sparql timeout body")
                return body
            } catch (e: CancellationException) { throw e
            } catch (e: BulkTooBig) { throw e
            } catch (e: PlaceHttpException) {
                when {
                    e.code == 429 || e.code == 503 -> { if (++attempt > RATE_RETRIES) throw e } // the shared PlaceNetGuard already pauses the next request (Retry-After or backoff)
                    e.code == 500 || e.code == 502 || e.code == 504 -> throw BulkTooBig("sparql http ${e.code}")
                    else -> throw e
                }
            } catch (e: java.net.SocketTimeoutException) { throw BulkTooBig("sparql read timeout") }
        }
    }

    private fun parseRows(body: String, into: MutableMap<String, Item>): Int {
        val root = parseBody(body)
        if (root["error"] != null) throw SerializationException("sparql: error body")
        val bindings = (root["results"] as? JsonObject)?.get("bindings") as? kotlinx.serialization.json.JsonArray ?: throw SerializationException("sparql: no results")
        var n = 0
        for (b in bindings) {
            val o = b as? JsonObject ?: continue
            n++
            fun v(k: String) = ((o[k] as? JsonObject)?.get("value") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            val q = v("item")?.substringAfterLast('/')?.takeIf { it.startsWith("Q") } ?: continue
            val title = v("title")?.takeIf { it.isNotBlank() } ?: continue
            val lat = v("lat")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: continue
            val lon = v("lon")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: continue
            val it = into.getOrPut(q) { Item(title, lat = lat, lon = lon) }
            v("cls")?.substringAfterLast('/')?.takeIf { c -> c.startsWith("Q") }?.let { c -> it.classes += c }
        }
        return n
    }

    companion object {
        const val PAGE_ROWS = 2_500
        const val MAX_PAGES = 4
        const val COORD_BATCH = 50
        const val INTRO_BATCH = 20
        private const val RATE_RETRIES = 2

        private fun f(d: Double) = String.format(Locale.US, "%.5f", d)

        /** The measured query shape (design doc section 9): box service, English-Wikipedia sitelink, optional class, ORDER BY, no sub-select. */
        fun query(b: GeoBox, limit: Int, offset: Int): String =
            "SELECT ?item ?title ?cls ?lat ?lon WHERE { SERVICE wikibase:box { ?item wdt:P625 ?loc . bd:serviceParam wikibase:cornerSouthWest \"Point(${f(b.west)} ${f(b.south)})\"^^geo:wktLiteral . " +
                "bd:serviceParam wikibase:cornerNorthEast \"Point(${f(b.east)} ${f(b.north)})\"^^geo:wktLiteral } " +
                "?art schema:about ?item ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?title . OPTIONAL { ?item wdt:P31 ?cls } " +
                "BIND(geof:latitude(?loc) AS ?lat) BIND(geof:longitude(?loc) AS ?lon) } ORDER BY ?item LIMIT $limit OFFSET $offset"

        private fun titles(t: List<String>) = t.joinToString("%7C") { enc(it) }

        fun coordinatesUrl(t: List<String>, host: String = "en.wikipedia.org") =
            "https://$host/w/api.php?action=query&prop=coordinates&coprimary=primary&colimit=500&titles=${titles(t)}&format=json&formatversion=2"

        fun introUrl(t: List<String>, host: String = "en.wikipedia.org") =
            "https://$host/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exsentences=${WikipediaHttpSource.SENTENCES}&exlimit=${WikipediaHttpSource.EXTRACT_BATCH}&titles=${titles(t)}&format=json&formatversion=2"

        /** title to its first (primary) Wikipedia coordinate; a normalised title is reachable under both spellings. A body with an API error or no query is a failure. */
        fun parseCoordinates(body: String): Map<String, Pair<Double, Double>> {
            val norm = parseNormalized(body)
            val out = HashMap<String, Pair<Double, Double>>()
            for (p in WikipediaHttpSource.parsePages(body)) {
                val lat = p.lat ?: continue; val lon = p.lon ?: continue
                out[p.title] = lat to lon
                norm.filterValues { it == p.title }.keys.forEach { k -> out[k] = lat to lon }
            }
            return out
        }

        /** The `query.normalized` map (from to) of a MediaWiki answer; empty when absent. */
        fun parseNormalized(body: String): Map<String, String> {
            val arr = ((parseBody(body)["query"] as? JsonObject)?.get("normalized") as? kotlinx.serialization.json.JsonArray) ?: return emptyMap()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val from = (o["from"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val to = (o["to"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                from to to
            }.toMap()
        }
    }
}
