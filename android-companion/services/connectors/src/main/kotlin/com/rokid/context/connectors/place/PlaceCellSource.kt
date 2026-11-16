package com.rokid.context.connectors.place

import com.rokid.context.memory.place.CellFetch
import com.rokid.context.memory.place.CellSource
import com.rokid.context.memory.place.FetchedPlace
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.zoneCheck
import kotlinx.coroutines.CancellationException

/**
 * The real [CellSource]: one 5 km cell from Wikipedia geosearch + extracts, Wikidata classes and hierarchy, and Overpass joined on `wikidata`. Only the cell key is the input; the
 * three sources are asked for the cell's box, never for a position. People are never candidates: an article is kept only when Wikidata says its class is in [MONUMENT_CLASSES].
 *
 * Wikipedia or Wikidata failing fails the cell (nothing stored). Overpass failing only makes the cell [CellFetch.complete] false.
 */
class PlaceCellSource(
    private val wikipedia: WikipediaSource,
    private val wikidata: WikidataSource,
    private val overpass: OverpassSource?,
    private val log: (String) -> Unit = {},
) : CellSource {
    override suspend fun fetchCell(cell: String): CellFetch {
        val box = requireNotNull(Geohash5.bounds(cell)) { "not a cell" }
        zoneCheck() // the zone fence is asked immediately before EACH request (Wikipedia, Wikidata, Overpass), not once per cell
        val hits = wikipedia.geosearch(box)
        val pages = hits.map { it.pageId }.distinct().chunked(WikipediaHttpSource.EXTRACT_BATCH).flatMap { zoneCheck(); wikipedia.pages(it) }
        val hitById = hits.associateBy { it.pageId }
        val qids = pages.mapNotNull { it.qid }.distinct()
        val entities = qids.chunked(WikidataHttpSource.BATCH).fold(emptyMap<String, WikidataEntity>()) { acc, ids -> zoneCheck(); acc + wikidata.entities(ids) }

        val kept = LinkedHashMap<String, FetchedPlace>()
        val hasPart = HashMap<String, List<String>>()
        for (p in pages) {
            val q = p.qid ?: continue
            val ent = entities[q] ?: continue
            val kind = ent.classes.firstNotNullOfOrNull { MONUMENT_CLASSES[it] } ?: continue
            val lat = ent.lat ?: p.lat ?: hitById[p.pageId]?.lat ?: continue
            val lon = ent.lon ?: p.lon ?: hitById[p.pageId]?.lon ?: continue
            kept["wd:$q"] = FetchedPlace("wd:$q", ent.partOf.firstOrNull()?.let { "wd:$it" }, p.title, kind, lat, lon, PlaceKinds.defaultRadiusM(kind), p.extract, "wikipedia:en", "page:${p.pageId}")
            if (ent.hasPart.isNotEmpty()) hasPart[q] = ent.hasPart
        }
        // P527 (has part) gives a sub-place its parent when the sub-place does not name one itself (P361).
        for ((parent, parts) in hasPart) for (part in parts) kept["wd:$part"]?.let { if (it.parentId == null && it.id != "wd:$parent") kept["wd:$part"] = it.copy(parentId = "wd:$parent") }

        var complete = true
        val osmPlaces = ArrayList<FetchedPlace>()
        if (overpass != null) {
            try {
                zoneCheck()
                for (el in overpass.elements(box)) {
                    val wd = el.tags["wikidata"]?.let { "wd:$it" }
                    val joined = wd?.let { kept[it] }
                    if (joined != null) {
                        val r = el.radiusM
                        if (r != null && r > joined.radiusM) kept[joined.id] = joined.copy(radiusM = r.coerceAtMost(PlaceKinds.MAX_RADIUS_M))
                    } else osmKind(el.tags)?.let { kind ->
                        val name = el.tags["name"] ?: return@let
                        osmPlaces += FetchedPlace("osm:${el.shortId}", null, name, kind, el.lat, el.lon, (el.radiusM ?: PlaceKinds.defaultRadiusM(kind)).coerceIn(10, PlaceKinds.MAX_RADIUS_M), "", "osm", "osm:${el.type}")
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                complete = false
                log("overpass failed cls=${e.javaClass.simpleName}")
            }
        }
        val out = kept.values.toList() + osmPlaces.filter { it.id !in kept }
        log("cell parsed hits=${hits.size} pages=${pages.size} kept=${kept.size} osm=${osmPlaces.size}")
        return CellFetch(out, complete)
    }

    companion object {
        /**
         * Wikidata classes (P31) a place may have to enter the pack: monument, temple, stupa, fort, palace, prison, archaeological site, memorial, church, mosque, lighthouse,
         * heritage and historic sites, museum. A person (Q5) is in no entry. The Q-ids are from memory of the Wikidata ontology: verify against wikidata.org before the trip.
         */
        val MONUMENT_CLASSES: Map<String, String> = mapOf(
            "Q4989906" to PlaceKinds.MONUMENT,
            "Q44539" to PlaceKinds.TEMPLE, "Q842402" to PlaceKinds.TEMPLE, "Q160742" to PlaceKinds.TEMPLE,
            "Q180987" to PlaceKinds.STUPA,
            "Q57821" to PlaceKinds.FORT, "Q1785071" to PlaceKinds.FORT,
            "Q16560" to PlaceKinds.PALACE,
            "Q40357" to PlaceKinds.PRISON_MUSEUM,
            "Q839954" to PlaceKinds.ARCHAEOLOGICAL_SITE,
            "Q5003624" to PlaceKinds.MEMORIAL,
            "Q16970" to PlaceKinds.CHURCH, "Q2977" to PlaceKinds.CHURCH,
            "Q32815" to PlaceKinds.MOSQUE,
            "Q39715" to PlaceKinds.LIGHTHOUSE,
            "Q358" to PlaceKinds.HERITAGE_BUILDING,
            "Q1081138" to PlaceKinds.HISTORIC_SITE,
            "Q33506" to PlaceKinds.MUSEUM,
        )

        /** The OSM tag combinations that make an object a place: historic, heritage and tourism=attraction|museum|viewpoint, named. */
        fun osmKind(t: Map<String, String>): String? {
            t["tourism"]?.let { when (it) { "museum" -> return PlaceKinds.MUSEUM; "viewpoint" -> return PlaceKinds.VIEWPOINT; "attraction" -> return PlaceKinds.ATTRACTION } }
            t["historic"]?.let { h ->
                when (h) {
                    "monument" -> return PlaceKinds.MONUMENT
                    "memorial" -> return PlaceKinds.MEMORIAL
                    "castle", "fort" -> return PlaceKinds.FORT
                    "archaeological_site", "ruins" -> return PlaceKinds.ARCHAEOLOGICAL_SITE
                    "tomb" -> return PlaceKinds.TOMB
                    "palace" -> return PlaceKinds.PALACE
                    "building", "city_gate", "temple", "church", "manor", "yes" -> return PlaceKinds.HERITAGE_BUILDING
                }
            }
            if (t.containsKey("heritage")) return PlaceKinds.HERITAGE_BUILDING
            return null
        }
    }
}
