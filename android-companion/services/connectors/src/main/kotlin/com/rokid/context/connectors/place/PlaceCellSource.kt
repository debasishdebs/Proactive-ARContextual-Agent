package com.rokid.context.connectors.place

import com.rokid.context.memory.place.CellFetch
import com.rokid.context.memory.place.CellSource
import com.rokid.context.memory.place.FetchedPlace
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.zoneCheck
import kotlinx.coroutines.CancellationException

/** A verified coordinate for a curated place whose Wikipedia page has none. */
data class VerifiedCoordinate(val name: String, val lat: Double, val lon: Double)

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
    /** Settings > Place Guide "Include mountains and peaks" (default OFF): admits [MOUNTAIN_CLASSES] too. Read at every cell, so a change applies to the next seed. */
    private val includeMountains: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) : CellSource {
    override suspend fun fetchCell(cell: String): CellFetch {
        val box = requireNotNull(Geohash5.bounds(cell)) { "not a cell" }
        zoneCheck() // the zone fence is asked immediately before EACH request (Wikipedia, Wikidata, Overpass), not once per cell
        val hits = wikipedia.geosearch(box)
        val pages = hits.map { it.pageId }.distinct().chunked(WikipediaHttpSource.EXTRACT_BATCH).flatMap { zoneCheck(); wikipedia.pages(it) }
        val hitById = hits.associateBy { it.pageId }
        val pageQids = pages.mapNotNull { it.qid }.toSet()
        // Verified-coordinate extras (see VERIFIED_COORDINATES): curated places with no Wikipedia coordinates, whose stored coordinate is inside this cell and which geosearch did not return.
        val extras = VERIFIED_COORDINATES.filter { (q, c) -> q !in pageQids && q in CURATED_LANDMARK_QIDS && box.contains(c.lat, c.lon) }
        val qids = (pages.mapNotNull { it.qid } + extras.keys).distinct()
        val entities = qids.chunked(WikidataHttpSource.BATCH).fold(emptyMap<String, WikidataEntity>()) { acc, ids -> zoneCheck(); acc + wikidata.entities(ids) }

        val mountains = includeMountains()
        val kept = LinkedHashMap<String, FetchedPlace>()
        val radiusCaps = HashMap<String, Int>() // places whose OSM extent must not widen the radius past a cap (see RADIUS_CAP_M)
        val hasPart = HashMap<String, List<String>>()
        var rejected = 0
        var coordsDisagree = 0
        // The 1 km rule is per PLACE (QID), not per page: when two pages share one QID and ANY of them disagrees with Wikidata, the QID is rejected entirely (fail closed), counted once.
        val disagreeingQids = HashSet<String>()
        val wideAllowed = HashSet<String>()
        for (p in pages) {
            val q = p.qid ?: continue
            val ent = entities[q] ?: continue
            val wpLat = hitById[p.pageId]?.lat ?: p.lat
            val wpLon = hitById[p.pageId]?.lon ?: p.lon
            if (ent.lat != null && ent.lon != null && wpLat != null && wpLon != null) {
                val limit = disagreeLimitM(ent.classes, q)
                if (coordsDisagree(wpLat, wpLon, ent.lat, ent.lon, limit)) disagreeingQids += q
                else if (limit > COORD_DISAGREE_M && coordsDisagree(wpLat, wpLon, ent.lat, ent.lon)) wideAllowed += q // an area-like place whose two points are 1 to 5 km apart: kept, stored at Wikidata's point
            }
        }
        val countedDisagree = HashSet<String>()
        for (p in pages) {
            val q = p.qid ?: continue
            val ent = entities[q] ?: run { rejected++; null } ?: continue
            if (ent.p31Malformed) { rejected++; continue } // unreadable classification is not absence: person exclusion cannot be checked, so not even a curated QID is admitted
            if (ent.classes.any { it in PERSON_CLASSES }) continue // people are never candidates, not even for a curated QID
            val kind = kindOf(ent.classes, q, mountains) ?: continue
            // Fail closed on a coordinate conflict: when Wikipedia (geosearch hit, else the page) and Wikidata P625 both have a point and they are more than COORD_DISAGREE_M apart, the place (QID) is dropped, curated or not; the rule is per QID, not per page.
            if (q in disagreeingQids) { if (countedDisagree.add(q)) coordsDisagree++; continue } // per QID, see above
            val lat = ent.lat ?: p.lat ?: hitById[p.pageId]?.lat ?: continue
            val lon = ent.lon ?: p.lon ?: hitById[p.pageId]?.lon ?: continue
            val cap = radiusCapOf(ent.classes)
            if (cap != null) radiusCaps["wd:$q"] = cap
            kept["wd:$q"] = FetchedPlace("wd:$q", ent.partOf.firstOrNull()?.let { "wd:$it" }, p.title, kind, lat, lon, PlaceKinds.defaultRadiusM(kind).coerceAtMost(cap ?: Int.MAX_VALUE), p.extract, "wikipedia:en", "page:${p.pageId}")
            if (ent.hasPart.isNotEmpty()) hasPart[q] = ent.hasPart
        }
        for ((q, c) in extras) {
            val ent = entities[q] ?: run { rejected++; null } ?: continue
            if (ent.p31Malformed) { rejected++; continue }
            if (ent.classes.any { it in PERSON_CLASSES }) continue
            val kind = kindOf(ent.classes, q, mountains) ?: continue
            // Coordinates come from the verified table only; the entity's own P625 is never used for these.
            kept.putIfAbsent("wd:$q", FetchedPlace("wd:$q", ent.partOf.firstOrNull()?.let { "wd:$it" }, c.name, kind, c.lat, c.lon, PlaceKinds.defaultRadiusM(kind), "", "wikidata", "wd:$q"))
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
                        if (r != null && r > joined.radiusM) kept[joined.id] = joined.copy(radiusM = r.coerceAtMost(radiusCaps[joined.id] ?: PlaceKinds.MAX_RADIUS_M))
                    } else osmKind(el.tags)?.let { kind ->
                        val name = el.tags["name"] ?: return@let
                        osmPlaces += FetchedPlace("osm:${el.shortId}", null, name, kind, el.lat, el.lon, (el.radiusM ?: PlaceKinds.defaultRadiusM(kind)).coerceIn(10, PlaceKinds.MAX_RADIUS_M), "", "osm", "osm:${el.type}")
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: com.rokid.context.memory.place.PlaceOfflineOnly) { throw e // the mode changed mid-fetch: the whole cell is refused, not stored as a partial
            } catch (e: Exception) {
                complete = false
                log("overpass failed cls=${e.javaClass.simpleName}")
            }
        }
        val out = kept.values.toList() + osmPlaces.filter { it.id !in kept }
        log("cell parsed coordAllowedWide=${wideAllowed.size} hits=${hits.size} pages=${pages.size} kept=${kept.size} osm=${osmPlaces.size} rejected=$rejected coordsDisagree=$coordsDisagree")
        return CellFetch(out, complete)
    }

    companion object {
        /** Two sources for one entity that are further apart than this (metres) disagree about where it is. */
        const val COORD_DISAGREE_M = 1000.0

        /** Area-like places (a lake, a park, a mountain...) have no single point: Wikipedia and Wikidata may put theirs a few km apart. Such an item is kept up to this distance (metres); the stored point stays Wikidata's. */
        const val WIDE_COORD_DISAGREE_M = 5000.0

        /** P31 classes that are areas rather than points: lake, glacial lake, park, national park, wildlife refuge, protected area, waterfall, mountain pass and the mountain classes (admitted or not, the limit only matters once admitted). TODO-verify classes (proglacial lake, sanctuary, bird sanctuary) join when their ids are verified. */
        val AREA_LIKE_CLASSES: Set<String> = setOf("Q23397", "Q211302", "Q22698", "Q46169", "Q1377575", "Q473972", "Q34038", "Q133056", "Q8502", "Q207326", "Q1595289")

        /** Curated ids with no admitted area class that still get the wide limit: Do-drul Chorten Q5285886 (no P31 at all; owner follow-up 27 Nov). A point-like curated place is not listed. */
        val WIDE_COORD_QIDS: Set<String> = setOf("Q5285886")

        /** The 1 km rule for point-like places (monastery, fort, temple, stupa, palace, museum, bridge...), [WIDE_COORD_DISAGREE_M] for area-like ones. The radius cap (300 m) is NOT a limit: it would be tighter than the 1 km point rule. */
        fun disagreeLimitM(classes: Set<String>, qid: String): Double = if (classes.any { it in AREA_LIKE_CLASSES } || qid in WIDE_COORD_QIDS) WIDE_COORD_DISAGREE_M else COORD_DISAGREE_M

        /** Great-circle distance in metres between two points (haversine, mean Earth radius 6,371,008.8 m). Pure. */
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_008.8
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
            return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
        }

        /** True when the two points are more than [COORD_DISAGREE_M] apart; a non-finite result is treated as a disagreement (fail closed). */
        fun coordsDisagree(lat1: Double, lon1: Double, lat2: Double, lon2: Double, limitM: Double = COORD_DISAGREE_M): Boolean {
            val d = haversineM(lat1, lon1, lat2, lon2)
            return !d.isFinite() || d > limitM
        }

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
            // Not admitted at that time (lake, glacial lake, waterfall, mountain pass: admitted 2026-11-27, below) although seen: geographical feature, railway station, horseshoe curve, tea plantation, remarkable tree, human settlement.
            "Q844619" to PlaceKinds.TEMPLE, "Q54074585" to PlaceKinds.TEMPLE, // vihara, Tibetan Buddhist monastery: Rumtek, Enchey, Ghum, Pemayangtse
            "Q1456873" to PlaceKinds.STUPA, // peace pagoda (a Buddhist stupa): Darjeeling Peace Pagoda
            "Q109607" to PlaceKinds.ARCHAEOLOGICAL_SITE, // ruins: Polonnaruwa Vatadage
            "Q167346" to PlaceKinds.ATTRACTION, // botanical garden: Royal Botanic Gardens, Peradeniya
            // Added 2026-11-27 (owner decision, design 2026-11-27-large-area-seeding-design.md sections 9 and 10, Sikkim coverage). Ids from that document and from trip-seed-coverage-2026-11-18.md (glacial lake Q211302 was
            // read back from wikidata.org on 2026-11-18); the English labels are in the comments. Not admitted: tea garden Q1827414, villages, human settlements, schools, zoos.
            // TODO-verify (no id in the repo docs, NOT guessed, so NOT admitted yet): proglacial lake, sanctuary, bird sanctuary.
            "Q23397" to PlaceKinds.ATTRACTION, "Q211302" to PlaceKinds.ATTRACTION, // lake, glacial lake
            "Q34038" to PlaceKinds.ATTRACTION, // waterfall
            "Q133056" to PlaceKinds.ATTRACTION, // mountain pass
            "Q1559394" to PlaceKinds.TEMPLE, // gompa
            "Q46169" to PlaceKinds.ATTRACTION, // national park (radius capped, see RADIUS_CAP_M)
            "Q1377575" to PlaceKinds.ATTRACTION, // wildlife refuge (radius capped)
            "Q473972" to PlaceKinds.ATTRACTION, // protected area (radius capped)
            "Q537127" to PlaceKinds.ATTRACTION, // road bridge
            "Q22698" to PlaceKinds.ATTRACTION, // park
            "Q1139682" to PlaceKinds.MUSEUM, // Tibet museum
        )

        /**
         * Admitted only while Settings > Place Guide "Include mountains and peaks" is ON (default OFF; adds many places, good for a mountain trip): mountain Q8502, summit Q207326, sacred mountain Q1595289.
         * Matched on P31 only, like [MONUMENT_CLASSES]; their radius is capped like the large areas.
         */
        val MOUNTAIN_CLASSES: Map<String, String> = mapOf(
            "Q8502" to PlaceKinds.VIEWPOINT, // mountain
            "Q207326" to PlaceKinds.VIEWPOINT, // summit
            "Q1595289" to PlaceKinds.VIEWPOINT, // sacred mountain
        )

        /** Large areas: the radius an OSM extent may widen a place to (metres). A national park or a mountain is hundreds of square kilometres: the card is for being near its sign, not inside its border. */
        const val AREA_RADIUS_CAP_M = 300
        val RADIUS_CAP_M: Map<String, Int> = mapOf("Q473972" to AREA_RADIUS_CAP_M, "Q46169" to AREA_RADIUS_CAP_M, "Q1377575" to AREA_RADIUS_CAP_M, "Q8502" to AREA_RADIUS_CAP_M, "Q207326" to AREA_RADIUS_CAP_M, "Q1595289" to AREA_RADIUS_CAP_M)

        /** The kind of an item with these P31 [classes] and this [qid], or null when it is not admitted (the one class rule of the per-cell and the bulk path; people are checked by the caller). */
        fun kindOf(classes: Set<String>, qid: String, mountains: Boolean): String? =
            classes.firstNotNullOfOrNull { MONUMENT_CLASSES[it] } ?: CURATED_LANDMARK_QIDS[qid] ?: if (mountains) classes.firstNotNullOfOrNull { MOUNTAIN_CLASSES[it] } else null

        /** The smallest radius cap of the item's classes, or null. */
        fun radiusCapOf(classes: Set<String>): Int? = classes.mapNotNull { RADIUS_CAP_M[it] }.minOrNull()

        /** Wikidata classes that make an item a person or character: such an item is rejected even when its QID is curated. Labels read back from wikidata.org on 2026-11-18. */
        val PERSON_CLASSES: Set<String> = setOf(
            "Q5", // human
            "Q15632617", // fictional human
            "Q95074", // character
            "Q21070568", // human whose existence is disputed
        )

        /** Version of [CURATED_LANDMARK_QIDS]; raise it with every change to the set. */
        const val CURATED_LANDMARKS_VERSION = 4

        /** Version of [VERIFIED_COORDINATES]; raise it with every change to the table. */
        const val VERIFIED_COORDINATES_VERSION = 3

        /**
         * VERIFIED_COORDINATES (version 2, 2026-11-19): curated QIDs that Wikipedia geosearch cannot return because the English Wikipedia page carries no coordinates (checked with
         * prop=coordinates on 2026-11-19). Each row was read back from wikidata.org on 2026-11-19 (wbgetentities, labels en, claims P31 and P625), one request at a time; label, P31, P625:
         * Q1961579 "Jetavanaramaya", P31 stupa Q180987, (8.351667, 80.403611);
         * Q60181073 "Ghum railway station", P31 railway station Q55488, (27.008643, 88.254223) (HTTP 429 once, retried after a wait).
         * Ritigala (Q19719626) is deliberately NOT seeded: its verified Wikidata point (8.108611, 80.656111) is the mountain, ~1.2 km from the monastic ruins (about 8.11858, 80.65929), and no monastery-specific item was verified (removed in version 2).
         * The other curated QIDs have Wikipedia coordinates, so geosearch finds them and they are not listed. A cell fetch adds the places of this table whose coordinate lies inside the cell and
         * which geosearch did not return, through the same person, class and curated gating; the coordinate used is THIS table's, never the response's. Every key must be in [CURATED_LANDMARK_QIDS].
         */
        val VERIFIED_COORDINATES: Map<String, VerifiedCoordinate> = mapOf(
            "Q1961579" to VerifiedCoordinate("Jetavanaramaya", 8.351667, 80.403611),
            "Q60181073" to VerifiedCoordinate("Ghum railway station", 27.008643, 88.254223),
            // Version 3 (2026-11-19), read back from wikidata.org: Q7685364 "Taramati Baradari", P31 caravanserai Q186347 + Baradari Q4858146, P625 (17.37608, 78.378117). The Wikipedia page has no
            // coordinates. Checked it is the building itself: OpenStreetMap Nominatim "Taramati Baradari" returns (17.3761241, 78.3781632), about 5 m from the Wikidata point.
            "Q7685364" to VerifiedCoordinate("Taramati Baradari", 17.37608, 78.378117),
        )

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
            // Added in version 2 (2026-11-19), read back from wikidata.org; Q1961579 is found through VERIFIED_COORDINATES, as their Wikipedia pages have no coordinates:
            "Q1961579" to PlaceKinds.STUPA, // "Jetavanaramaya" (8.352, 80.404), P31 stupa (also in MONUMENT_CLASSES): Anuradhapura
        // Version 3 (2026-11-19): Q19719626 "Ritigala" removed again (its point is the mountain, not the ruins; see VERIFIED_COORDINATES).
            // Version 4 (2026-11-19), Hyderabad landmark audit (docs/superpowers/reviews/2026-11/hyderabad-landmark-audit-2026-11-19.md); each read back from wikidata.org on 2026-11-19 (label en, P31, P625):
            "Q7685364" to PlaceKinds.HISTORIC_SITE, // "Taramati Baradari" (17.37608, 78.378117), P31 caravanserai + Baradari: Golconda pavilion; its Wikipedia page has no coordinates, so it is found through VERIFIED_COORDINATES
            "Q7124701" to PlaceKinds.TOMB, // "Paigah Tombs" (17.343983, 78.504131), P31 cemetery: the Paigah nobles' tombs
            "Q4984164" to PlaceKinds.MONUMENT, // "Buddha Statue of Hyderabad" (17.415556, 78.475), P31 colossal statue: the statue in Hussain Sagar
            "Q3275994" to PlaceKinds.ATTRACTION, // "Hussain Sagar" (17.42222, 78.47389), P31 reservoir: the lake with the Buddha statue
            "Q1650886" to PlaceKinds.ATTRACTION, // "Lumbini Park" (17.41, 78.4722), P31 park: Hussain Sagar lakeside park
            "Q3493970" to PlaceKinds.ATTRACTION, // "Nehru Zoological Park" (17.351111, 78.449722), P31 zoo: Hyderabad zoo
            "Q6466530" to PlaceKinds.ATTRACTION, // "Laad Bazaar" (17.361923, 78.472842), P31 marketplace + bazaar: the bangle market beside Charminar
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
