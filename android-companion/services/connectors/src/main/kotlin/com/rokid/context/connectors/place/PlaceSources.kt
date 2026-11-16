package com.rokid.context.connectors.place

import com.rokid.context.memory.place.GeoBox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/** One Wikipedia geosearch hit: a titled article with a coordinate. */
data class WikiHit(val pageId: Long, val title: String, val lat: Double, val lon: Double)

/** One article after the extracts call: its 3-sentence plain intro, its Wikidata item and its own coordinate when it has one. */
data class WikiPage(val pageId: Long, val title: String, val extract: String, val qid: String?, val lat: Double?, val lon: Double?)

/** The parts of a Wikidata item the pack needs: classes (P31), part of (P361), has part (P527) and the coordinate (P625). */
data class WikidataEntity(val qid: String, val classes: Set<String>, val partOf: List<String>, val hasPart: List<String>, val lat: Double?, val lon: Double?)

/** One Overpass element, a node, way or relation, with its tags. [radiusM] comes from the box of a way or relation. */
data class OsmElement(val type: String, val id: Long, val lat: Double, val lon: Double, val radiusM: Int?, val tags: Map<String, String>) {
    /** "w123", "n45", "r6": the id used in `osm:<this>`. */
    val shortId: String get() = type.first() + id.toString()
}

interface WikipediaSource {
    /** Articles with a coordinate inside [box] (up to 500). */
    suspend fun geosearch(box: GeoBox): List<WikiHit>
    /** The intro extracts, wikibase items and coordinates of [pageIds]; the caller batches by [WikipediaHttpSource.EXTRACT_BATCH]. */
    suspend fun pages(pageIds: List<Long>): List<WikiPage>
}

interface WikidataSource {
    /** The entities of [ids] (at most [WikidataHttpSource.BATCH] per call). */
    suspend fun entities(ids: List<String>): Map<String, WikidataEntity>
}

interface OverpassSource {
    suspend fun elements(box: GeoBox): List<OsmElement>
}

internal val placeJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.lng(): Long? = (this as? JsonPrimitive)?.longOrNull

private fun fmt(d: Double) = String.format(Locale.US, "%.5f", d)

/** Wikipedia geosearch (`list=geosearch`) and the extracts call (`prop=extracts|pageprops|coordinates`), English Wikipedia. */
class WikipediaHttpSource(private val http: PlaceHttp, private val host: String = "en.wikipedia.org") : WikipediaSource {
    override suspend fun geosearch(box: GeoBox): List<WikiHit> =
        parseGeosearch(http.get("https://$host/w/api.php?action=query&list=geosearch&gsbbox=${fmt(box.north)}%7C${fmt(box.west)}%7C${fmt(box.south)}%7C${fmt(box.east)}&gslimit=$GEO_LIMIT&format=json&formatversion=2"))

    override suspend fun pages(pageIds: List<Long>): List<WikiPage> {
        if (pageIds.isEmpty()) return emptyList()
        require(pageIds.size <= EXTRACT_BATCH)
        val ids = pageIds.joinToString("%7C")
        return parsePages(http.get("https://$host/w/api.php?action=query&prop=extracts%7Cpageprops%7Ccoordinates&exintro=1&explaintext=1&exsentences=$SENTENCES&exlimit=$EXTRACT_BATCH&ppprop=wikibase_item&colimit=max&coprimary=primary&pageids=$ids&format=json&formatversion=2"))
    }

    companion object {
        const val GEO_LIMIT = 500
        const val EXTRACT_BATCH = 20
        const val SENTENCES = 3

        fun parseGeosearch(body: String): List<WikiHit> =
            placeJson.parseToJsonElement(body).obj()?.get("query").obj()?.get("geosearch").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                WikiHit(o["pageid"].lng() ?: return@mapNotNull null, o["title"].str() ?: return@mapNotNull null, o["lat"].dbl() ?: return@mapNotNull null, o["lon"].dbl() ?: return@mapNotNull null)
            }

        fun parsePages(body: String): List<WikiPage> =
            placeJson.parseToJsonElement(body).obj()?.get("query").obj()?.get("pages").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                if (o["missing"] != null) return@mapNotNull null
                val c = o["coordinates"].arr()?.firstOrNull().obj()
                WikiPage(
                    o["pageid"].lng() ?: return@mapNotNull null, o["title"].str() ?: return@mapNotNull null, o["extract"].str().orEmpty(),
                    o["pageprops"].obj()?.get("wikibase_item").str(), c?.get("lat").dbl(), c?.get("lon").dbl(),
                )
            }
    }
}

/** Wikidata `wbgetentities` (claims only). */
class WikidataHttpSource(private val http: PlaceHttp, private val host: String = "www.wikidata.org") : WikidataSource {
    override suspend fun entities(ids: List<String>): Map<String, WikidataEntity> {
        if (ids.isEmpty()) return emptyMap()
        require(ids.size <= BATCH)
        return parse(http.get("https://$host/w/api.php?action=wbgetentities&ids=${ids.joinToString("%7C")}&props=claims&format=json"))
    }

    companion object {
        const val BATCH = 50

        fun parse(body: String): Map<String, WikidataEntity> {
            val entities = placeJson.parseToJsonElement(body).obj()?.get("entities").obj() ?: return emptyMap()
            return entities.entries.mapNotNull { (qid, v) ->
                val claims = v.obj()?.get("claims").obj() ?: return@mapNotNull null
                fun ids(p: String) = claims[p].arr().orEmpty().mapNotNull { it.obj()?.get("mainsnak").obj()?.get("datavalue").obj()?.get("value").obj()?.get("id").str() }
                val coord = claims["P625"].arr()?.firstNotNullOfOrNull { it.obj()?.get("mainsnak").obj()?.get("datavalue").obj()?.get("value").obj() }
                qid to WikidataEntity(qid, ids("P31").toSet(), ids("P361"), ids("P527"), coord?.get("latitude").dbl(), coord?.get("longitude").dbl())
            }.toMap()
        }
    }
}

/** Overpass: named historic, heritage and tourism objects in the box. `out center bb` gives a point and, for ways and relations, the box that sets the radius. */
class OverpassHttpSource(private val http: PlaceHttp, private val endpoint: String = "https://overpass-api.de/api/interpreter") : OverpassSource {
    override suspend fun elements(box: GeoBox): List<OsmElement> = parse(http.postForm(endpoint, mapOf("data" to query(box))))

    companion object {
        fun query(b: GeoBox): String {
            val bb = "(${fmt(b.south)},${fmt(b.west)},${fmt(b.north)},${fmt(b.east)})"
            return "[out:json][timeout:25];(nwr[\"historic\"][\"name\"]$bb;nwr[\"heritage\"][\"name\"]$bb;nwr[\"tourism\"~\"^(attraction|museum|viewpoint)\$\"][\"name\"]$bb;);out tags center bb;"
        }

        fun parse(body: String): List<OsmElement> =
            placeJson.parseToJsonElement(body).obj()?.get("elements").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val type = o["type"].str() ?: return@mapNotNull null
                val id = o["id"].lng() ?: return@mapNotNull null
                val c = o["center"].obj()
                val lat = (o["lat"] ?: c?.get("lat")).dbl() ?: return@mapNotNull null
                val lon = (o["lon"] ?: c?.get("lon")).dbl() ?: return@mapNotNull null
                val b = o["bounds"].obj()
                val radius = b?.let {
                    val s = it["minlat"].dbl(); val w = it["minlon"].dbl(); val n = it["maxlat"].dbl(); val ee = it["maxlon"].dbl()
                    if (s == null || w == null || n == null || ee == null) null
                    else (com.rokid.context.memory.place.Geo.distanceM(s, w, n, ee) / 2).toInt()
                }
                val tags = o["tags"].obj()?.entries?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap().orEmpty()
                OsmElement(type, id, lat, lon, radius, tags)
            }
    }
}
