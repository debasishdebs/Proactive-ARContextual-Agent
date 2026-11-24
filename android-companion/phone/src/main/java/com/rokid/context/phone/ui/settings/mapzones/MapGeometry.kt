package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.phone.ui.settings.privacy.zones.LatLon
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Map zones P1 (flag mapZonesV2): pure geometry for drawing and editing a shape on the map. No Compose, no Android, no logging. Coordinates live in memory only.

/** What the user is drawing. */
enum class ShapeKind { RECT, CIRCLE }

/** A shape being drawn or edited, in geographic terms. Never stored by itself: a pause zone is saved only through the existing zone store. */
sealed interface DrawnShape {
    val kind: ShapeKind

    /** An axis-aligned box (south, west, north, east). */
    data class Rect(val south: Double, val west: Double, val north: Double, val east: Double) : DrawnShape {
        override val kind get() = ShapeKind.RECT
    }

    /** A circle: centre and radius in metres (a drawing aid; a pause or seed circle becomes the box around it, see [CircleBox]). */
    data class Circle(val lat: Double, val lon: Double, val radiusM: Double) : DrawnShape {
        override val kind get() = ShapeKind.CIRCLE
    }
}

/** The corner or point the finger is on. */
enum class Handle { NW, NE, SW, SE, CENTRE, RADIUS }

sealed interface Hit {
    data class OnHandle(val handle: Handle) : Hit
    data object OnBody : Hit
    data object Miss : Hit
}

object CircleBox {
    const val METRES_PER_DEG = 111_320.0
    /** The box is grown by this much so it always holds the whole circle, even where the flat metre-to-degree rule is a little short of the sphere (never less, only more). */
    const val SAFETY = 1.01

    /** Web-mercator y of [lat] in degrees (the unit the map view uses), and its inverse. */
    fun merc(lat: Double): Double = Math.toDegrees(kotlin.math.ln(kotlin.math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)))
    fun unmerc(m: Double): Double = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.toRadians(m))))

    /** The box that holds the whole circle AS DRAWN on the web-mercator map (and the sphere's circle), as (south, west, north, east); null when the circle is unusable (not finite, not positive, or it would cross the 180 degree line or pass the Mercator limit at the centre). */
    fun of(lat: Double, lon: Double, radiusM: Double): DoubleArray? {
        if (!lat.isFinite() || !lon.isFinite() || !radiusM.isFinite() || radiusM <= 0.0) return null
        if (abs(lat) > MapViewport.MAX_LAT || abs(lon) > 180.0) return null
        // The circle the user SEES is a circle in map-pixel space: its radius is r / (111320 cos(lat)) degrees of longitude, and on the web-mercator map the same distance north or south is the same number of mercator degrees. So the box is taken from that rendered circle: longitude +- rho, and latitude by inverting the mercator at M(lat) +- rho. (A flat rule r / 111320 degrees falls short of the rendered edge by several km at 60 degrees north or south.) Never less, only more: grown by SAFETY, and a latitude past the map limit is taken to the pole.
        val rho = radiusM / (METRES_PER_DEG * max(cos(Math.toRadians(lat)), 0.01)) * SAFETY
        val w = lon - rho; val e = lon + rho
        if (w < -180.0 || e > 180.0) return null // a box across the 180 degree line is not supported
        val m = merc(lat); val mLimit = merc(MapViewport.MAX_LAT)
        val s = if (m - rho <= -mLimit) -90.0 else max(unmerc(m - rho), -90.0)
        val n = if (m + rho >= mLimit) 90.0 else min(unmerc(m + rho), 90.0)
        // Outward-only guard: never smaller than the flat rule either (physical distance on the sphere).
        val flat = radiusM / METRES_PER_DEG * SAFETY
        return doubleArrayOf(min(s, max(lat - flat, -90.0)), w, max(n, min(lat + flat, 90.0)), e)
    }
}

object ShapeEdit {
    /** The smallest box side (degrees), the same as the zone form's [com.rokid.context.phone.ui.settings.privacy.zones.ZoneDrafts.MIN_SPAN_DEG]. */
    const val MIN_SPAN_DEG = 0.0005
    const val MIN_RADIUS_M = 50.0
    const val MAX_RADIUS_M = 200_000.0
    /** The touch target of a handle (dp); it is drawn smaller. */
    const val HANDLE_TOUCH_DP = 48f
    const val HANDLE_DRAW_DP = 12f

    private const val LAT_LIMIT = MapViewport.MAX_LAT

    /** The box a shape covers (a circle: the box around it); null when the shape is unusable. */
    fun box(s: DrawnShape): DoubleArray? = when (s) {
        is DrawnShape.Rect -> if (listOf(s.south, s.west, s.north, s.east).all { it.isFinite() } && s.south < s.north && s.west < s.east) doubleArrayOf(s.south, s.west, s.north, s.east) else null
        is DrawnShape.Circle -> CircleBox.of(s.lat, s.lon, s.radiusM)
    }

    /** A rectangle from a drag between two corners (any order). Null when it is too small, not finite, or crosses the 180 degree line. */
    fun rectFromDrag(a: LatLon, b: LatLon): DrawnShape.Rect? {
        if (!(a.lat.isFinite() && a.lon.isFinite() && b.lat.isFinite() && b.lon.isFinite())) return null
        val s = clampLat(min(a.lat, b.lat)); val n = clampLat(max(a.lat, b.lat)); val w = clampLon(min(a.lon, b.lon)); val e = clampLon(max(a.lon, b.lon))
        if (n - s < MIN_SPAN_DEG || e - w < MIN_SPAN_DEG) return null
        return DrawnShape.Rect(s, w, n, e)
    }

    /** A circle from a drag from its centre to its edge. Null when too small or unusable. */
    fun circleFromDrag(centre: LatLon, edge: LatLon): DrawnShape.Circle? {
        if (!(centre.lat.isFinite() && centre.lon.isFinite() && edge.lat.isFinite() && edge.lon.isFinite())) return null
        val r = distanceM(centre, edge)
        if (r < MIN_RADIUS_M) return null
        val c = DrawnShape.Circle(clampLat(centre.lat), clampLon(centre.lon), min(r, MAX_RADIUS_M))
        return c.takeIf { box(it) != null }
    }

    /** A default shape for "+ Draw area": about 40 % of the shorter side of the view, around its centre. Null for an unusable view. */
    fun defaultShape(v: MapViewport, kind: ShapeKind): DrawnShape? {
        if (!(v.width.isFinite() && v.height.isFinite()) || v.width < 10f || v.height < 10f || !v.ppd.isFinite()) return null
        val half = min(v.width, v.height) * 0.2f
        val cx = v.width / 2; val cy = v.height / 2
        val a = v.toLatLon(cx - half, cy + half); val b = v.toLatLon(cx + half, cy - half)
        return when (kind) {
            ShapeKind.RECT -> rectFromDrag(a, b)
            ShapeKind.CIRCLE -> circleFromDrag(v.toLatLon(cx, cy), v.toLatLon(cx + half, cy))
        }
    }

    /** What is under the finger at ([x], [y]): a handle first (the nearest within [touchPx] / 2), then the body, else a miss. */
    fun hit(v: MapViewport, s: DrawnShape, x: Float, y: Float, touchPx: Float): Hit {
        if (!(x.isFinite() && y.isFinite() && touchPx.isFinite()) || touchPx <= 0f) return Hit.Miss
        val half = touchPx / 2
        val handles = handlePoints(v, s) ?: return Hit.Miss
        var best: Handle? = null; var bestD = Float.MAX_VALUE
        for ((h, p) in handles) {
            val dx = abs(p.first - x); val dy = abs(p.second - y)
            if (dx <= half && dy <= half) { val d = hypot(dx, dy); if (d < bestD) { bestD = d; best = h } }
        }
        if (best != null) return Hit.OnHandle(best)
        return if (inside(v, s, x, y)) Hit.OnBody else Hit.Miss
    }

    /** The on-screen position of each handle (null when the shape cannot be placed). */
    fun handlePoints(v: MapViewport, s: DrawnShape): List<Pair<Handle, Pair<Float, Float>>>? {
        val b = box(s) ?: return null
        val tl = v.toPx(b[2], b[1]); val br = v.toPx(b[0], b[3])
        if (!(tl.x.isFinite() && tl.y.isFinite() && br.x.isFinite() && br.y.isFinite())) return null
        return when (s) {
            is DrawnShape.Rect -> listOf(Handle.NW to (tl.x to tl.y), Handle.NE to (br.x to tl.y), Handle.SW to (tl.x to br.y), Handle.SE to (br.x to br.y))
            is DrawnShape.Circle -> {
                val c = v.toPx(s.lat, s.lon)
                val r = (br.x - tl.x) / 2 / CircleBox.SAFETY.toFloat() // the drawn radius (the box is grown by the safety margin)
                listOf(Handle.CENTRE to (c.x to c.y), Handle.RADIUS to (c.x + r to c.y))
            }
        }
    }

    private fun inside(v: MapViewport, s: DrawnShape, x: Float, y: Float): Boolean {
        val b = box(s) ?: return false
        val tl = v.toPx(b[2], b[1]); val br = v.toPx(b[0], b[3])
        return when (s) {
            is DrawnShape.Rect -> x in tl.x..br.x && y in tl.y..br.y
            is DrawnShape.Circle -> { val c = v.toPx(s.lat, s.lon); hypot(x - c.x, y - c.y) <= (br.x - tl.x) / 2 / CircleBox.SAFETY.toFloat() }
        }
    }

    /** Moves the whole shape by ([dLat], [dLon]) degrees, keeping its size. It is pushed back inside the map's limits rather than shrunk; a move that would break the shape changes nothing. */
    fun move(s: DrawnShape, dLat: Double, dLon: Double): DrawnShape {
        if (!dLat.isFinite() || !dLon.isFinite()) return s
        return when (s) {
            is DrawnShape.Rect -> {
                val h = s.north - s.south; val w = s.east - s.west
                if (!(h > 0 && w > 0 && h.isFinite() && w.isFinite())) return s
                val south = (s.south + dLat).coerceIn(-LAT_LIMIT, max(LAT_LIMIT - h, -LAT_LIMIT))
                val west = (s.west + dLon).coerceIn(-180.0, max(180.0 - w, -180.0))
                DrawnShape.Rect(south, west, south + h, west + w)
            }
            is DrawnShape.Circle -> {
                val moved = s.copy(lat = clampLat(s.lat + dLat), lon = clampLon(s.lon + dLon))
                if (box(moved) != null) moved else s
            }
        }
    }

    /** Drags [handle] to [to]. A rectangle corner follows the finger with the opposite corner fixed and never crosses it (the span stays at least [MIN_SPAN_DEG]); a circle's radius handle sets the radius ([MIN_RADIUS_M] to [MAX_RADIUS_M]). Anything unusable changes nothing. */
    fun resize(s: DrawnShape, handle: Handle, to: LatLon): DrawnShape {
        if (!to.lat.isFinite() || !to.lon.isFinite()) return s
        return when (s) {
            is DrawnShape.Rect -> {
                val lat = clampLat(to.lat); val lon = clampLon(to.lon)
                var south = s.south; var north = s.north; var west = s.west; var east = s.east
                when (handle) {
                    Handle.NW -> { north = max(lat, south + MIN_SPAN_DEG); west = min(lon, east - MIN_SPAN_DEG) }
                    Handle.NE -> { north = max(lat, south + MIN_SPAN_DEG); east = max(lon, west + MIN_SPAN_DEG) }
                    Handle.SW -> { south = min(lat, north - MIN_SPAN_DEG); west = min(lon, east - MIN_SPAN_DEG) }
                    Handle.SE -> { south = min(lat, north - MIN_SPAN_DEG); east = max(lon, west + MIN_SPAN_DEG) }
                    else -> return s
                }
                val next = DrawnShape.Rect(south, west, north, east)
                if (box(next) != null && north <= 90.0 && south >= -90.0 && east <= 180.0 && west >= -180.0) next else s
            }
            is DrawnShape.Circle -> when (handle) {
                Handle.RADIUS -> {
                    val r = distanceM(LatLon(s.lat, s.lon), to).coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
                    val next = s.copy(radiusM = r)
                    if (box(next) != null) next else s
                }
                Handle.CENTRE -> move(s, to.lat - s.lat, to.lon - s.lon)
                else -> s
            }
        }
    }

    /** Flat-earth distance in metres, for the radius and the size readout (small areas only; not for navigation). Not finite in, 0 out. */
    fun distanceM(a: LatLon, b: LatLon): Double {
        if (!(a.lat.isFinite() && a.lon.isFinite() && b.lat.isFinite() && b.lon.isFinite())) return 0.0
        val dy = (b.lat - a.lat) * CircleBox.METRES_PER_DEG
        val dx = (b.lon - a.lon) * CircleBox.METRES_PER_DEG * cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot(dx, dy)
    }

    /** "2.1 km x 1.4 km" or "r 300 m" for the live readout. */
    fun sizeText(s: DrawnShape): String {
        val b = box(s) ?: return ""
        return when (s) {
            is DrawnShape.Circle -> "radius " + len(s.radiusM)
            is DrawnShape.Rect -> len(distanceM(LatLon((b[0] + b[2]) / 2, b[1]), LatLon((b[0] + b[2]) / 2, b[3]))) + " x " + len(distanceM(LatLon(b[0], b[1]), LatLon(b[2], b[1])))
        }
    }

    private fun len(m: Double): String = if (m >= 1_000) String.format(java.util.Locale.US, "%.1f km", m / 1_000) else "${Math.round(m)} m"

    private fun clampLat(v: Double) = v.coerceIn(-LAT_LIMIT, LAT_LIMIT)
    private fun clampLon(v: Double) = v.coerceIn(-180.0, 180.0)

    /** Rounds a box OUTWARD to five decimals (south and west down, north and east up), so the text a zone is saved from never covers less than the drawn shape. */
    fun outward(b: DoubleArray): DoubleArray = doubleArrayOf(floor(b[0] * 1e5) / 1e5, floor(b[1] * 1e5) / 1e5, -floor(-b[2] * 1e5) / 1e5, -floor(-b[3] * 1e5) / 1e5)
}
