package com.rokid.context.memory.place

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** An axis-aligned lat/lon box (no antimeridian crossing: the same rule as the pause zones). */
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    val valid: Boolean get() = south < north && west < east && south >= -90.0 && north <= 90.0 && west >= -180.0 && east <= 180.0
    fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east
    fun intersects(o: GeoBox): Boolean = south <= o.north && o.south <= north && west <= o.east && o.west <= east
}

/** A place with its distance from a point, for the candidate list. */
data class PlaceHit(val place: PlaceEntity, val distanceM: Double) {
    /** How far the point is from the place's own extent (negative inside it). */
    val gapM: Double get() = distanceM - place.radiusM
}

/**
 * The 5-character geohash cell (about 4.9 km square): the only thing that ever leaves the phone when the pack is fetched (spec 2026-11-16 §B3). 25 bits, longitude first: 13 bits
 * of longitude and 12 of latitude, so a cell is 360/8192 by 180/4096 degrees.
 */
object Geohash5 {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    const val LENGTH = 5
    private const val LON_CELLS = 8192
    private const val LAT_CELLS = 4096
    const val LON_STEP = 360.0 / LON_CELLS
    const val LAT_STEP = 180.0 / LAT_CELLS

    fun encode(lat: Double, lon: Double): String {
        val la = lat.coerceIn(-90.0, 90.0); val lo = lon.coerceIn(-180.0, 180.0)
        return fromIndex(latIndex(la), lonIndex(lo))
    }

    fun isCell(s: String): Boolean = s.length == LENGTH && s.all { it in BASE32 }

    /** The box of a cell; null when [cell] is not a valid 5 character geohash. */
    fun bounds(cell: String): GeoBox? {
        if (!isCell(cell)) return null
        var lonLo = -180.0; var lonHi = 180.0; var latLo = -90.0; var latHi = 90.0; var evenBit = true
        for (ch in cell) {
            val v = BASE32.indexOf(ch)
            for (b in 4 downTo 0) {
                val one = (v shr b) and 1 == 1
                if (evenBit) { val mid = (lonLo + lonHi) / 2; if (one) lonLo = mid else lonHi = mid }
                else { val mid = (latLo + latHi) / 2; if (one) latLo = mid else latHi = mid }
                evenBit = !evenBit
            }
        }
        return GeoBox(latLo, lonLo, latHi, lonHi)
    }

    /** Every cell that touches [box], south-west to north-east. */
    fun cellsCovering(box: GeoBox): List<String> {
        if (!box.valid) return emptyList()
        val la0 = latIndex(box.south); val la1 = latIndex(box.north); val lo0 = lonIndex(box.west); val lo1 = lonIndex(box.east)
        val out = ArrayList<String>((la1 - la0 + 1) * (lo1 - lo0 + 1))
        for (la in la0..la1) for (lo in lo0..lo1) out += fromIndex(la, lo)
        return out
    }

    private fun latIndex(lat: Double) = min(LAT_CELLS - 1, floor((lat + 90.0) / LAT_STEP).toInt())
    private fun lonIndex(lon: Double) = min(LON_CELLS - 1, floor((lon + 180.0) / LON_STEP).toInt())

    private fun fromIndex(latIdx: Int, lonIdx: Int): String {
        // Interleave: bit 24 (the first) is longitude's most significant bit.
        var bits = 0L
        for (i in 0 until 25) {
            val fromLon = i % 2 == 0
            val k = i / 2
            val bit = if (fromLon) (lonIdx shr (12 - k)) and 1 else (latIdx shr (11 - k)) and 1
            bits = (bits shl 1) or bit.toLong()
        }
        return buildString { for (c in 0 until LENGTH) append(BASE32[((bits shr (5 * (LENGTH - 1 - c))) and 31).toInt()]) }
    }
}

/** Pure geometry for the place pack and the matcher. */
object Geo {
    private const val EARTH_M = 6_371_008.8
    /** A place is a candidate when the point is at most this far from its edge (spec §B4). */
    const val CANDIDATE_GAP_M = 300.0
    const val MAX_CANDIDATES = 8
    private const val PIN_SLOTS = 3
    private const val M_PER_DEG_LAT = 111_320.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
        val dp = p2 - p1; val dl = (lon2 - lon1) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * asin(min(1.0, sqrt(a)))
    }

    /** The lat/lon window that holds every point within [meters] of (lat, lon). */
    fun window(lat: Double, lon: Double, meters: Double): GeoBox {
        val dLat = meters / M_PER_DEG_LAT
        val dLon = meters / (M_PER_DEG_LAT * max(0.01, cos(lat * PI / 180)))
        return GeoBox(max(-90.0, lat - dLat), max(-180.0, lon - dLon), min(90.0, lat + dLat), min(180.0, lon + dLon))
    }

    /**
     * The places whose `distance - radiusM <= gapM` from the point, nearest edge first (ties by distance, then id), at most [limit]. People are never places in the pack
     * (their kind is not in [PlaceKinds.ALL]) and are refused here too.
     */
    fun candidates(places: List<PlaceEntity>, lat: Double, lon: Double, gapM: Double = CANDIDATE_GAP_M, limit: Int = MAX_CANDIDATES): List<PlaceHit> {
        val sorted = places.asSequence().filter { it.kind in PlaceKinds.ALL }
            .map { PlaceHit(it, distanceM(lat, lon, it.lat, it.lon)) }
            .filter { it.gapM <= gapM }
            .sortedWith(compareBy<PlaceHit> { it.gapM }.thenBy { it.distanceM }.thenBy { it.place.id })
            .toList()
        val head = sorted.take(limit)
        // The user pinned these on purpose: up to [PIN_SLOTS] of the nearest pins within reach keep a place in the list even when more than [limit] pack places are nearer.
        val missing = sorted.drop(limit).filter { UserPlace.isUserId(it.place.id) }.take(PIN_SLOTS)
        if (missing.isEmpty()) return head
        val keep = head.toMutableList()
        for (pin in missing) { val at = keep.indexOfLast { !UserPlace.isUserId(it.place.id) }; if (at >= 0) keep.removeAt(at) else break; keep += pin }
        return keep.sortedWith(compareBy<PlaceHit> { it.gapM }.thenBy { it.distanceM }.thenBy { it.place.id })
    }

    /** Site to sub-place expansion: the chosen places first, then the [children] whose parent was chosen (each once). */
    fun expandSubPlaces(chosen: List<PlaceEntity>, children: List<PlaceEntity>): List<PlaceEntity> {
        val ids = chosen.mapTo(HashSet()) { it.id }
        val added = children.filter { it.parentId in ids && it.id !in ids }.sortedWith(compareBy({ it.parentId }, { it.name }, { it.id })).distinctBy { it.id }
        return chosen + added
    }
}
