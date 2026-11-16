package com.rokid.context.phone.ui.settings.privacy.zones

import com.rokid.context.core.model.zone.PauseZone
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** A point on the map in view pixels. */
data class MapPx(val x: Float, val y: Float)

/** A latitude/longitude in degrees. Held on screen only; never stored or logged. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * The tile-less map's projection: Web Mercator over a plain background, so a box looks the way it does on any map. Pure maths (no Compose, no Android) so it is unit tested.
 * [ppd] is pixels per degree of longitude at the centre; the centre is a latitude/longitude, the view is [width] x [height] px.
 */
data class MapViewport(val centerLat: Double, val centerLon: Double, val ppd: Double, val width: Float, val height: Float) {
    private fun mercY(lat: Double): Double = Math.toDegrees(ln(tan(Math.PI / 4 + Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)) / 2)))
    private fun latOf(mercDeg: Double): Double = Math.toDegrees(atan(sinh(Math.toRadians(mercDeg))))

    fun toPx(lat: Double, lon: Double): MapPx =
        MapPx((width / 2 + (lon - centerLon) * ppd).toFloat(), (height / 2 - (mercY(lat) - mercY(centerLat)) * ppd).toFloat())

    fun toLatLon(x: Float, y: Float): LatLon {
        val lon = centerLon + (x - width / 2) / ppd
        val lat = latOf(mercY(centerLat) + (height / 2 - y) / ppd)
        return LatLon(lat.coerceIn(-MAX_LAT, MAX_LAT), lon.coerceIn(-180.0, 180.0))
    }

    fun panned(dx: Float, dy: Float): MapViewport {
        val c = toLatLon(width / 2 - dx, height / 2 - dy)
        return copy(centerLat = c.lat, centerLon = c.lon)
    }

    /** Zooms by [factor] keeping the point under ([x], [y]) fixed. */
    fun zoomed(factor: Double, x: Float = width / 2, y: Float = height / 2): MapViewport {
        val anchor = toLatLon(x, y)
        val next = copy(ppd = (ppd * factor).coerceIn(MIN_PPD, MAX_PPD))
        val now = next.toPx(anchor.lat, anchor.lon)
        return next.panned(x - now.x, y - now.y)
    }

    /** The grid step (degrees) that keeps lines at least [MIN_GRID_PX] apart. */
    fun gridStepDeg(): Double = GRID_STEPS.firstOrNull { it * ppd >= MIN_GRID_PX } ?: GRID_STEPS.last()

    fun resized(w: Float, h: Float): MapViewport = copy(width = w, height = h)

    companion object {
        const val MAX_LAT = 85.0
        const val MIN_PPD = 0.5
        const val MAX_PPD = 400_000.0
        const val MIN_GRID_PX = 64f
        private val GRID_STEPS = listOf(0.0005, 0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0)

        /** A view with no zones and no fix: a region (India) the user can pan from. It invents no boundary. */
        fun default(w: Float, h: Float) = MapViewport(21.0, 80.0, max(min(w / 30.0, h / 30.0), MIN_PPD), w, h)

        /** The smallest view that holds all [boxes] (south, west, north, east) with [padPx] around them. */
        fun fit(boxes: List<DoubleArray>, w: Float, h: Float, padPx: Float = 24f): MapViewport {
            if (boxes.isEmpty() || w <= 2 * padPx || h <= 2 * padPx) return default(w, h)
            val s = boxes.minOf { it[0] }; val west = boxes.minOf { it[1] }
            val n = boxes.maxOf { it[2] }; val e = boxes.maxOf { it[3] }
            val probe = MapViewport((s + n) / 2, (west + e) / 2, 1.0, w, h)
            val spanLon = max(e - west, 1e-4)
            val spanMerc = max(abs(probe.mercY(n) - probe.mercY(s)), 1e-4)
            val ppd = min((w - 2 * padPx) / spanLon, (h - 2 * padPx) / spanMerc).coerceIn(MIN_PPD, MAX_PPD)
            val centerMerc = (probe.mercY(n) + probe.mercY(s)) / 2
            return MapViewport(probe.latOf(centerMerc), (west + e) / 2, ppd, w, h)
        }

        /** The box of [z] grown by its buffer, as (south, west, north, east): the ring drawn around it. Approximate (a flat metre-to-degree conversion), for drawing only. */
        fun ringOf(z: PauseZone): DoubleArray = grown(z.south, z.west, z.north, z.east, z.bufferM.toDouble())

        fun grown(s: Double, w: Double, n: Double, e: Double, metres: Double): DoubleArray {
            val dLat = metres / METRES_PER_DEG
            val midLat = (s + n) / 2
            val dLon = metres / (METRES_PER_DEG * max(cos(Math.toRadians(midLat)), 0.01))
            return doubleArrayOf((s - dLat).coerceAtLeast(-90.0), (w - dLon).coerceAtLeast(-180.0), (n + dLat).coerceAtMost(90.0), (e + dLon).coerceAtMost(180.0))
        }

        private const val METRES_PER_DEG = 111_320.0
    }
}
