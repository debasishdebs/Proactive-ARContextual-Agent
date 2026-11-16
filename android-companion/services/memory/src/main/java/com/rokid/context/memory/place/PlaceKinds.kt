package com.rokid.context.memory.place

/**
 * What a place in the pack can be. A fixed allowlist: a place of any other class is never stored, so a person can never be a candidate (spec §B3: people have no coordinates and
 * the class allowlist excludes them).
 */
object PlaceKinds {
    const val MONUMENT = "monument"
    const val TEMPLE = "temple"
    const val STUPA = "stupa"
    const val FORT = "fort"
    const val PALACE = "palace"
    const val PRISON_MUSEUM = "prison_museum"
    const val ARCHAEOLOGICAL_SITE = "archaeological_site"
    const val MEMORIAL = "memorial"
    const val CHURCH = "church"
    const val MOSQUE = "mosque"
    const val LIGHTHOUSE = "lighthouse"
    const val HERITAGE_BUILDING = "heritage_building"
    const val HISTORIC_SITE = "historic_site"
    const val MUSEUM = "museum"
    const val TOMB = "tomb"
    const val VIEWPOINT = "viewpoint"
    const val ATTRACTION = "attraction"

    val ALL: Set<String> = setOf(
        MONUMENT, TEMPLE, STUPA, FORT, PALACE, PRISON_MUSEUM, ARCHAEOLOGICAL_SITE, MEMORIAL, CHURCH, MOSQUE, LIGHTHOUSE, HERITAGE_BUILDING, HISTORIC_SITE, MUSEUM, TOMB, VIEWPOINT, ATTRACTION,
    )

    /** The matching radius of a place of this kind when the source gives no extent (metres). */
    fun defaultRadiusM(kind: String): Int = when (kind) {
        FORT, PALACE, ARCHAEOLOGICAL_SITE -> 250
        TEMPLE, STUPA, HISTORIC_SITE -> 80
        VIEWPOINT, ATTRACTION -> 60
        else -> 50
    }

    const val MAX_RADIUS_M = 1_500
}
