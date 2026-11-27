package com.rokid.context.phone.ui.settings.privacy.zones

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.PlaceDot
import kotlin.math.hypot

/** One dot to paint: its id (for the label on a tap), position and colour. In memory only; never logged. */
data class PaintedDot(val id: String, val lat: Double, val lon: Double, val argb: Int, val inArea: Boolean)

/** A tapped dot's label: name, kind and the first line of the stored intro. Public place data. */
data class DotLabel(val id: String, val text: String)

/**
 * Seeded places as small dots on the zones maps. Pure rules: when to draw (zoomed in enough: fewer than [MAX_VISIBLE_CELLS] 5 km cells in view), the cap, which area colours a dot, the label and the hit test.
 * Places are public seed data read from places.db (no network); manual pins are the owner's own and keep the purple pin marker (they are never read as dots).
 */
object SeedDots {
    /** Dots are drawn only when the view covers fewer cells than this (zoomed out, thousands of places would be noise and a heavy read). */
    const val MAX_VISIBLE_CELLS = 400L
    /** The most dots drawn; above it the map says "zoom in to see all". */
    const val CAP = 1_500
    const val TOUCH_DP = 16f
    const val DOT_DP = 3.5f
    /** A place outside every seeded area (or in an area that was edited smaller). */
    const val GREY = 0xFF8A8F98.toInt()
    const val ZOOM_HINT = "Zoom in to see seeded places."
    const val CAPPED = "Showing the first 1,500 places. Zoom in to see all."
    const val SWITCH = "Show seeded places"

    /** The geographic box the view covers (null when the viewport is unusable). */
    fun visibleBox(v: MapViewport?, w: Float, h: Float): GeoBox? {
        if (v == null || !(w >= 10f && h >= 10f)) return null
        val tl = v.toLatLon(0f, 0f); val br = v.toLatLon(w, h)
        val b = GeoBox(minOf(br.lat, tl.lat), minOf(tl.lon, br.lon), maxOf(br.lat, tl.lat), maxOf(tl.lon, br.lon))
        return b.takeIf { it.valid && listOf(b.south, b.west, b.north, b.east).all { d -> d.isFinite() } }
    }

    /** Whether a view of [box] is close enough to read the places: at least one and fewer than [MAX_VISIBLE_CELLS] cells. */
    fun closeEnough(box: GeoBox?): Boolean {
        val n = box?.let { SeedEstimate.cells(it) } ?: return false
        return n in 1 until MAX_VISIBLE_CELLS
    }

    /** The colour of each dot: its seeded area's palette colour (the first area, in list order, whose box holds it), else [GREY]. At most [CAP] dots. */
    fun paint(dots: List<PlaceDot>, areas: List<SeedOverlay>): List<PaintedDot> = dots.asSequence().take(CAP).map { d ->
        val a = areas.firstOrNull { it.box.size == 4 && d.lat in it.box[0]..it.box[2] && d.lon in it.box[1]..it.box[3] }
        PaintedDot(d.id, d.lat, d.lon, a?.let { SeedPalette.argb(it.colour) } ?: GREY, a != null)
    }.toList()

    /** "Name · kind" and the first non-blank line of the intro (cut at 120 characters). */
    fun label(name: String, kind: String, summary: String): String {
        val first = summary.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { if (it.length > 120) it.take(119) + "…" else it }
        return name.trim().ifEmpty { "Place" } + " · " + kind.lowercase().replace('_', ' ') + (first?.let { "\n$it" } ?: "")
    }

    /** The dot nearest to ([x], [y]) within [radiusPx], or null. */
    fun hit(v: MapViewport, dots: List<PaintedDot>, x: Float, y: Float, radiusPx: Float): String? {
        var best: String? = null; var bestD = Float.MAX_VALUE
        for (d in dots) {
            val p = v.toPx(d.lat, d.lon)
            if (!(p.x.isFinite() && p.y.isFinite())) continue
            val dist = hypot(p.x - x, p.y - y)
            if (dist <= radiusPx && dist < bestD) { bestD = dist; best = d.id }
        }
        return best
    }
}

/** Draws the dots that fall inside the canvas: a white halo and a small disc in the area colour. */
internal fun DrawScope.drawSeedDots(v: MapViewport, dots: List<PaintedDot>, selectedId: String? = null) {
    val r = SeedDots.DOT_DP.dp.toPx()
    for (d in dots) {
        val p = v.toPx(d.lat, d.lon)
        if (!(p.x.isFinite() && p.y.isFinite()) || p.x < -r || p.y < -r || p.x > size.width + r || p.y > size.height + r) continue
        val c = Offset(p.x, p.y)
        drawCircle(Color.White, r + 1.5.dp.toPx(), c)
        drawCircle(Color(d.argb), if (d.id == selectedId) r * 1.5f else r, c)
    }
}
