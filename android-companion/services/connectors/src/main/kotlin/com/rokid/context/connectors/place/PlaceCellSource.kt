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
            if (ent.classes.any { it in PERSON_CLASSES }) continue // people are never candidates, not even for a curated QID
            val kind = ent.classes.firstNotNullOfOrNull { MONUMENT_CLASSES[it] } ?: CURATED_LANDMARK_QIDS[q] ?: continue
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
         * heritage and historic sites, museum (and, since 2026-11-18, tomb, art museum, national museum, theme park, vihara, Tibetan Buddhist monastery, peace pagoda, ruins, botanical garden). A person (Q5) is in no entry.
         *
         * VERIFIED 2026-11-17 against wikidata.org (`wbgetentities`, props=labels, en): every id below has the English label in its line comment. Two ids from memory were WRONG and are fixed:
         * Q160742 is "abbey" (not a temple) and Q40357 is "prison" (a plain prison is not a place; the class "prison museum" is Q10500763). Q5393308 (Buddhist temple) and Q12539846 (dagoba)
         * were added. The match is on P31 only, with no subclass walk: an item whose P31 is a subclass of one of these is not kept.
         */
        val MONUMENT_CLASSES: Map<String, String> = mapOf(
            "Q4989906" to PlaceKinds.MONUMENT, // monument
            "Q44539" to PlaceKinds.TEMPLE, "Q842402" to PlaceKinds.TEMPLE, "Q5393308" to PlaceKinds.TEMPLE, // temple, Hindu temple, Buddhist temple
            "Q180987" to PlaceKinds.STUPA, "Q12539846" to PlaceKinds.STUPA, // stupa, dagoba
            "Q57821" to PlaceKinds.FORT, "Q1785071" to PlaceKinds.FORT, // fortification, fort
            "Q16560" to PlaceKinds.PALACE, // palace
            "Q10500763" to PlaceKinds.PRISON_MUSEUM, // prison museum
            "Q839954" to PlaceKinds.ARCHAEOLOGICAL_SITE, // archaeological site
            "Q5003624" to PlaceKinds.MEMORIAL, // memorial
            "Q16970" to PlaceKinds.CHURCH, "Q2977" to PlaceKinds.CHURCH, // church building, cathedral
            "Q32815" to PlaceKinds.MOSQUE, // mosque
            "Q39715" to PlaceKinds.LIGHTHOUSE, // lighthouse
            "Q358" to PlaceKinds.HERITAGE_BUILDING, // heritage site
            "Q1081138" to PlaceKinds.HISTORIC_SITE, // historic site
            "Q33506" to PlaceKinds.MUSEUM, // museum
            // Added 2026-11-18 after the Hyderabad coverage check (docs/superpowers/reviews/2026-11/hyderabad-seed-coverage-2026-11-18.md), each id read back from wikidata.org (English label):
            "Q381885" to PlaceKinds.TOMB, // tomb: the P31 of "Qutb Shahi tombs" (Q7273006)
            "Q207694" to PlaceKinds.MUSEUM, "Q17431399" to PlaceKinds.MUSEUM, // art museum, national museum: the P31 of "Salar Jung Museum" (Q1864572)
            "Q2416723" to PlaceKinds.ATTRACTION, // theme park: the P31 of "Ramoji Film City" (Q1786944), with film studio (Q375336), which is not admitted
            // Added 2026-11-18 after the trip coverage check (docs/superpowers/reviews/2026-11/trip-seed-coverage-2026-11-18.md), each id and English label read back from wikidata.org on 2026-11-18.
            // Not admitted although seen: lake, glacial lake, waterfall, mountain pass, geographical feature, railway station, horseshoe curve, tea plantation, remarkable tree, human settlement.
            "Q844619" to PlaceKinds.TEMPLE, "Q54074585" to PlaceKinds.TEMPLE, // vihara, Tibetan Buddhist monastery: Rumtek, Enchey, Ghum, Pemayangtse
            "Q1456873" to PlaceKinds.STUPA, // peace pagoda (a Buddhist stupa): Darjeeling Peace Pagoda
            "Q109607" to PlaceKinds.ARCHAEOLOGICAL_SITE, // ruins: Polonnaruwa Vatadage
            "Q167346" to PlaceKinds.ATTRACTION, // botanical garden: Royal Botanic Gardens, Peradeniya
        )

        /** Wikidata classes that make an item a person or character: such an item is rejected even when its QID is curated. Labels read back from wikidata.org on 2026-11-18. */
        val PERSON_CLASSES: Set<String> = setOf(
            "Q5", // human
            "Q15632617", // fictional human
            "Q95074", // character
            "Q21070568", // human whose existence is disputed
        )

        /** Version of [CURATED_LANDMARK_QIDS]; raise it with every change to the set. */
        const val CURATED_LANDMARKS_VERSION = 1

        /**
         * CURATED_LANDMARK_QIDS (version 1, 2026-11-18): individual landmarks admitted by Wikidata item id although their P31 class is NOT in [MONUMENT_CLASSES] (and must not be, as a general class).
         * An item is admitted when its P31 is allowlisted OR its QID is here; it still needs P625 or page coordinates and is never a person ([PERSON_CLASSES]). Only items that Wikipedia geosearch can
         * return can enter (the discovery flow is unchanged). Each id was read back from wikidata.org on 2026-11-18 (wbgetentities, labels en, claims P31 and P625); label, P31 and why:
         */
        val CURATED_LANDMARK_QIDS: Map<String, String> = mapOf(
            "Q272153" to PlaceKinds.ARCHAEOLOGICAL_SITE, // "Sigiriya" (7.957, 80.760), P31 human settlement Q486972: the rock fortress page, a headline Sri Lanka site
            "Q16901632" to PlaceKinds.VIEWPOINT, // "Tiger Hill, Darjeeling" (26.995, 88.285), P31 geographical feature + mountain: the Darjeeling sunrise viewpoint
            "Q4868648" to PlaceKinds.ATTRACTION, // "Batasia Loop" (27.017, 88.247), P31 horseshoe curve: Darjeeling Himalayan Railway loop and war memorial park
            "Q23038925" to PlaceKinds.ATTRACTION, // "Banjhakri falls and park" (27.351, 88.604), P31 waterfall: Gangtok Banjhakri Falls and Energy Park
            "Q3214979" to PlaceKinds.ATTRACTION, // "Khecheopalri Lake" (27.373, 88.208), P31 lake: sacred lake, West Sikkim
            "Q944175" to PlaceKinds.HISTORIC_SITE, // "Sri Maha Bodhi" (8.345, 80.397), P31 remarkable tree: Jaya Sri Maha Bodhi, Anuradhapura, the sacred fig
            "Q5653142" to PlaceKinds.ATTRACTION, // "Happy Valley Tea Estate" (27.048, 88.251), P31 tea plantation + settlement: the Darjeeling visitor tea estate
            "Q542864" to PlaceKinds.ATTRACTION, // "Lake Tsongmo" (27.375, 88.764), P31 glacial lake: Tsomgo (Changu) Lake, East Sikkim
            "Q5285886" to PlaceKinds.STUPA, // "Do-drul Chorten" (27.313, 88.605), no P31 at all: Gangtok stupa
            "Q24949017" to PlaceKinds.HERITAGE_BUILDING, // "Darjeeling railway station" (27.038, 88.263), P31 railway station: Darjeeling Himalayan Railway terminus
            "Q60181073" to PlaceKinds.HERITAGE_BUILDING, // "Ghum railway station" (27.009, 88.254), P31 railway station: highest station of the Darjeeling Himalayan Railway
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
