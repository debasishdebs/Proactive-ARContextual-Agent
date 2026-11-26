package com.rokid.context.connectors.place

import com.rokid.context.memory.place.PlaceKinds

/**
 * The OPTIONAL place types of Settings > Place Guide > "Extra place types" (owner decisions 27 Nov): each is a switch, stored in the `place_guide` prefs, honoured by NEW seeds (per-cell and bulk alike); places
 * already stored are never purged by switching one off. The strict list ([PlaceCellSource.MONUMENT_CLASSES] with the 27 Nov additions) is always on; these are on top of it.
 *
 * DATA, not code: a class is one [ClassDef] line in a [Type], a type is one [Type] line in [ALL] (id, label, category, areaLike flag, radius cap, default). Ids are the owner's (27 Nov) and the class survey's (design doc, Class survey Rajasthan 27 Nov); the always-on classes are in [PlaceCellSource.MONUMENT_CLASSES]. TODO-verify: bus stop Q953806.
 * Deliberately NOT here and with no switch: villages, settlements, constituencies, wards, districts, gewogs, blocks, borders, schools, census towns.
 * [Type.areaLike] types get the 5 km Wikipedia-versus-Wikidata tolerance; [Type.radiusCapM] is the most an OSM extent may widen the radius to.
 */
object ExtraPlaceTypes {
    /** One Wikidata P31 class: its id, its English label (for the log-free screens and docs) and the kind a place of it is stored with. */
    class ClassDef(val id: String, val label: String, val kind: String)

    /** [category] groups the switches on the screen; [defaultOn] is the owner's default (27 Nov night: ON except battles, valleys, cities and airports). A type with no [classes] admits nothing and is not shown (see [shown]). */
    class Type(val key: String, val title: String, val category: String, val classes: List<ClassDef>, val areaLike: Boolean = false, val radiusCapM: Int? = null, val defaultOn: Boolean = true, val note: String = "") {
        val classIds: Set<String> get() = classes.map { it.id }.toSet()
        val shown: Boolean get() = classes.isNotEmpty()
    }

    const val MOUNTAINS = "mountains"
    const val TEA_GARDENS = "tea_gardens"
    const val RIVERS = "rivers"
    const val GLACIERS = "glaciers"
    const val HILLS = "hills"
    const val RAILWAY = "railway"
    const val BUS_STOPS = "bus_stops"
    const val POWER = "power"
    const val STADIUMS = "stadiums"
    const val BATTLES = "battles"
    const val VALLEYS = "valleys"
    const val CITIES = "cities"
    const val AIRPORTS = "airports"
    const val VENUES = "venues"

    val ALL: List<Type> = listOf(
        Type(MOUNTAINS, "Include mountains and peaks", "Nature", listOf(
            ClassDef("Q8502", "mountain", PlaceKinds.VIEWPOINT), ClassDef("Q207326", "summit", PlaceKinds.VIEWPOINT), ClassDef("Q1595289", "sacred mountain", PlaceKinds.VIEWPOINT),
            ClassDef("Q740445", "mountain ridge", PlaceKinds.VIEWPOINT), ClassDef("Q46831", "mountain range", PlaceKinds.VIEWPOINT),
        ), areaLike = true, radiusCapM = 300, note = "adds many places; good for a mountain trip"),
        Type(RIVERS, "Include rivers and streams", "Nature", listOf(ClassDef("Q47521", "stream", PlaceKinds.ATTRACTION), ClassDef("Q4022", "river", PlaceKinds.ATTRACTION)), areaLike = true, radiusCapM = 100, note = "adds many places"),
        Type(GLACIERS, "Include glaciers", "Nature", listOf(ClassDef("Q35666", "glacier", PlaceKinds.ATTRACTION)), areaLike = true, radiusCapM = 300),
        Type(HILLS, "Include hills", "Nature", listOf(ClassDef("Q54050", "hill", PlaceKinds.VIEWPOINT)), areaLike = true, radiusCapM = 300, note = "adds many places"),
        Type(TEA_GARDENS, "Include tea gardens", "Sights", listOf(ClassDef("Q1827414", "tea garden", PlaceKinds.ATTRACTION))),
        Type(RAILWAY, "Include railway stations", "Transport", listOf(ClassDef("Q55488", "railway station", PlaceKinds.ATTRACTION), ClassDef("Q22808404", "station located on surface", PlaceKinds.ATTRACTION)), note = "adds many places"), // mountain railway, heritage railway and horseshoe curve are always on (MONUMENT_CLASSES)
        // 27 Nov night: bus station Q494829 is ON by default; "bus stop" Q953806 is TODO-verify (listed in the handoff only, not read back) and is NOT admitted.
        Type(BUS_STOPS, "Include bus stations", "Transport", listOf(ClassDef("Q494829", "bus station", PlaceKinds.ATTRACTION))),
        Type(POWER, "Include power stations", "Sights", listOf(ClassDef("Q1411996", "hydroelectric power station", PlaceKinds.ATTRACTION), ClassDef("Q15911738", "hydroelectric power station (design doc id)", PlaceKinds.ATTRACTION))), // dams are always on; both hydroelectric ids carry items in the trip-region survey (4 and 1), so both are admitted
        Type(STADIUMS, "Include stadiums and cricket grounds", "Sights", listOf(ClassDef("Q483110", "stadium", PlaceKinds.ATTRACTION), ClassDef("Q682943", "cricket field", PlaceKinds.ATTRACTION), ClassDef("Q641226", "arena", PlaceKinds.ATTRACTION), ClassDef("Q1049757", "multi-purpose stadium", PlaceKinds.ATTRACTION))),
        // New optional switches, default OFF (owner 27 Nov night).
        Type(BATTLES, "Include battles and sieges", "Sights", listOf(ClassDef("Q178561", "battle", PlaceKinds.HISTORIC_SITE), ClassDef("Q188055", "siege", PlaceKinds.HISTORIC_SITE)), defaultOn = false, note = "historic events at a place"),
        Type(VALLEYS, "Include valleys and plateaus", "Nature", listOf(ClassDef("Q39816", "valley", PlaceKinds.VIEWPOINT), ClassDef("Q75520", "plateau", PlaceKinds.VIEWPOINT)), areaLike = true, radiusCapM = 300, defaultOn = false),
        Type(CITIES, "Include cities and towns", "Context", listOf(ClassDef("Q515", "city", PlaceKinds.ATTRACTION), ClassDef("Q3957", "town", PlaceKinds.ATTRACTION), ClassDef("Q1549591", "big city", PlaceKinds.ATTRACTION)), areaLike = true, radiusCapM = 300, defaultOn = false, note = "adds place names like city and town for context, not sights"),
        Type(AIRPORTS, "Include airports", "Transport", listOf(ClassDef("Q1248784", "airport", PlaceKinds.ATTRACTION), ClassDef("Q94993988", "commercial traffic aerodrome", PlaceKinds.ATTRACTION)), defaultOn = false),
        // 27 Nov late night (owner): venues and ports, ON by default, switchable OFF.
        Type(VENUES, "Include venues and ports", "Sights", listOf(ClassDef("Q17350442", "venue", PlaceKinds.ATTRACTION), ClassDef("Q1076486", "sports venue", PlaceKinds.ATTRACTION), ClassDef("Q44782", "port", PlaceKinds.ATTRACTION), ClassDef("Q11822917", "horse racing venue", PlaceKinds.ATTRACTION))),
        // Not offered (owner: OFF and not offered): universities and colleges (Q3918, Q189004, Q131389368, Q7603893). No class, no toggle.
    )

    /** The switches the screen offers (a type with no class yet is not shown). */
    val SHOWN: List<Type> = ALL.filter { it.shown }

    val KEYS: Set<String> = ALL.map { it.key }.toSet()

    /** The keys that are ON when nothing was stored. */
    val DEFAULT_ON: Set<String> = ALL.filter { it.defaultOn }.map { it.key }.toSet()

    fun byKey(key: String): Type? = ALL.firstOrNull { it.key == key }

    /** The classes (id to kind) admitted by the switches in [enabled]; an unknown key admits nothing. */
    fun classesFor(enabled: Set<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (t in ALL) if (t.key in enabled) for (c in t.classes) out.putIfAbsent(c.id, c.kind)
        return out
    }

    /** Every extra class that is area-like, whether or not its switch is on (the tolerance only matters once the class is admitted). */
    val AREA_LIKE_CLASSES: Set<String> = ALL.filter { it.areaLike }.flatMap { it.classIds }.toSet()

    /** Every extra class with a radius cap, whether or not its switch is on. */
    val RADIUS_CAPS: Map<String, Int> = ALL.filter { it.radiusCapM != null }.flatMap { t -> t.classIds.map { it to t.radiusCapM!! } }.toMap()

    /** "Include mountains and peaks (adds many places; good for a mountain trip)": the title with its hint. */
    fun fullLabel(t: Type): String = if (t.note.isEmpty()) t.title else "${t.title} (${t.note})"
}
