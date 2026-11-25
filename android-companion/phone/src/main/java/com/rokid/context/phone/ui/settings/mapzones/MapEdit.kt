package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.phone.ui.settings.privacy.zones.ChipLevel
import com.rokid.context.phone.ui.settings.privacy.zones.LatLon
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import com.rokid.context.phone.ui.settings.privacy.zones.SeedEstimate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Map zones P2 (flags mapZonesV2 and mapZonesP23): pure logic for the editing tools. No Compose, no Android, no logging, no store access. Everything here works on a DRAFT in memory;
// nothing in this file can save, change or delete a pause zone, and a coordinate never leaves the screen's memory.

/** Undo and redo of the draft shape. Immutable. Many events in one drag (gaps under [COALESCE_MS]) are ONE undo step. It holds shapes only, never a name, and is dropped with the draft. */
data class DraftHistory(val past: List<DrawnShape> = emptyList(), val future: List<DrawnShape> = emptyList(), val lastEventMs: Long = Long.MIN_VALUE) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Call BEFORE replacing [current] by a new shape. A burst of changes (each within [COALESCE_MS] of the one before) keeps only the shape the burst started from. */
    fun record(current: DrawnShape?, now: Long): DraftHistory {
        if (current == null) return this
        val inBurst = lastEventMs != Long.MIN_VALUE && now - lastEventMs in 0 until COALESCE_MS && past.isNotEmpty()
        if (inBurst) return copy(lastEventMs = now)
        val p = (past + current).takeLast(MAX_STEPS)
        return DraftHistory(p, emptyList(), now)
    }

    /** The shape to go back to and the history after it; null when there is nothing to undo. */
    fun undo(current: DrawnShape): Pair<DrawnShape, DraftHistory>? {
        val prev = past.lastOrNull() ?: return null
        return prev to DraftHistory(past.dropLast(1), (future + current).takeLast(MAX_STEPS), Long.MIN_VALUE)
    }

    fun redo(current: DrawnShape): Pair<DrawnShape, DraftHistory>? {
        val next = future.lastOrNull() ?: return null
        return next to DraftHistory((past + current).takeLast(MAX_STEPS), future.dropLast(1), Long.MIN_VALUE)
    }

    companion object {
        const val COALESCE_MS = 400L
        const val MAX_STEPS = 50
    }
}

/**
 * Edit rules for a STORED pause zone. This is a UI-side pre-check only; the zone controller still decides (ZoneChange.BLOCKED_INSIDE, unchanged). The UI is stricter than the controller on purpose: unless the zone's own
 * row says clearly "outside" (CLEAR) or the zone is switched off, nothing can be reshaped, and an unknown zone (no row) counts as blocked. Unreadable saved zones block everything.
 */
object ZoneEditPolicy {
    enum class Block { NONE, UNREADABLE, INSIDE_OR_UNKNOWN }

    fun reshapeBlock(level: ChipLevel?, inside: Boolean, protectedMode: Boolean): Block = when {
        protectedMode -> Block.UNREADABLE
        level == null || inside -> Block.INSIDE_OR_UNKNOWN
        level == ChipLevel.PAUSED || level == ChipLevel.WARN -> Block.INSIDE_OR_UNKNOWN
        else -> Block.NONE // CLEAR or OFF
    }

    fun words(b: Block): String? = when (b) {
        Block.NONE -> null
        Block.UNREADABLE -> "Not changed: the saved zones cannot be read, so no zone can be edited."
        Block.INSIDE_OR_UNKNOWN -> "Not changed: you are inside or near this zone, or the app cannot tell, so it cannot be reshaped now."
    }

    /** True when [next] does not cover all of [old] (the zone would protect less). Allowed only while clearly outside; shown as a notice before saving. */
    fun shrinks(old: DoubleArray, next: DoubleArray): Boolean {
        if (old.size != 4 || next.size != 4 || old.any { !it.isFinite() } || next.any { !it.isFinite() }) return true // unknown: treat as smaller (fail closed)
        return next[0] > old[0] || next[1] > old[1] || next[2] < old[2] || next[3] < old[3]
    }

    const val SHRINK_NOTICE = "This makes the zone smaller. It is saved only while you are clearly outside it."
}

/** Snaps a dragged shape to the edges of other shapes on the map. Pure; the result is always a valid shape (else the unsnapped one comes back). Never used for a save path: snapping changes the draft only. */
object ShapeSnap {
    const val SNAP_DP = 10f

    fun snap(prev: DrawnShape, next: DrawnShape, others: List<DoubleArray>, v: MapViewport, tolPx: Float): DrawnShape {
        if (!(tolPx > 0f) || !tolPx.isFinite() || !(v.ppd > 0.0) || !v.ppd.isFinite()) return next
        val boxes = others.filter { it.size == 4 && it.all { d -> d.isFinite() } }
        if (boxes.isEmpty()) return next
        val lats = boxes.flatMap { listOf(it[0], it[2]) }
        val lons = boxes.flatMap { listOf(it[1], it[3]) }
        val out = when {
            prev is DrawnShape.Rect && next is DrawnShape.Rect -> snapRect(prev, next, lats, lons, v, tolPx)
            prev is DrawnShape.Circle && next is DrawnShape.Circle -> snapCircle(prev, next, boxes, v, tolPx)
            else -> next
        }
        return if (ShapeEdit.box(out) != null) out else next
    }

    private fun latPx(v: MapViewport, lat: Double): Float = v.toPx(lat, 0.0).y
    private fun nearestLat(v: MapViewport, lat: Double, cands: List<Double>, tolPx: Float): Double? =
        cands.filter { abs(latPx(v, it) - latPx(v, lat)) <= tolPx }.minByOrNull { abs(latPx(v, it) - latPx(v, lat)) }
    private fun nearestLon(v: MapViewport, lon: Double, cands: List<Double>, tolPx: Float): Double? =
        cands.filter { abs(it - lon) * v.ppd <= tolPx }.minByOrNull { abs(it - lon) }

    private fun snapRect(prev: DrawnShape.Rect, n: DrawnShape.Rect, lats: List<Double>, lons: List<Double>, v: MapViewport, tol: Float): DrawnShape {
        val cS = n.south != prev.south; val cN = n.north != prev.north; val cW = n.west != prev.west; val cE = n.east != prev.east
        if (!(cS || cN || cW || cE)) return n
        if (cS && cN && cW && cE) { // a move: one shift per axis, by the nearest edge, so the size is kept
            val dLat = listOfNotNull(nearestLat(v, n.south, lats, tol)?.let { it - n.south }, nearestLat(v, n.north, lats, tol)?.let { it - n.north }).minByOrNull { abs(it) }
            val dLon = listOfNotNull(nearestLon(v, n.west, lons, tol)?.let { it - n.west }, nearestLon(v, n.east, lons, tol)?.let { it - n.east }).minByOrNull { abs(it) }
            return ShapeEdit.move(n, dLat ?: 0.0, dLon ?: 0.0)
        }
        val s = if (cS) nearestLat(v, n.south, lats, tol) ?: n.south else n.south
        val no = if (cN) nearestLat(v, n.north, lats, tol) ?: n.north else n.north
        val w = if (cW) nearestLon(v, n.west, lons, tol) ?: n.west else n.west
        val e = if (cE) nearestLon(v, n.east, lons, tol) ?: n.east else n.east
        if (no - s < ShapeEdit.MIN_SPAN_DEG || e - w < ShapeEdit.MIN_SPAN_DEG) return n
        return DrawnShape.Rect(s, w, no, e)
    }

    private fun snapCircle(prev: DrawnShape.Circle, n: DrawnShape.Circle, boxes: List<DoubleArray>, v: MapViewport, tol: Float): DrawnShape {
        if (n.radiusM != prev.radiusM || (n.lat == prev.lat && n.lon == prev.lon)) return n // a resize is not snapped
        val lats = boxes.flatMap { listOf(it[0], it[2], (it[0] + it[2]) / 2) }
        val lons = boxes.flatMap { listOf(it[1], it[3], (it[1] + it[3]) / 2) }
        return n.copy(lat = nearestLat(v, n.lat, lats, tol) ?: n.lat, lon = nearestLon(v, n.lon, lons, tol) ?: n.lon)
    }
}

/** How a draft box overlaps what is already on the map, in box terms (degrees, flat). Touching edges do not count. */
data class Overlap(val pauseIds: List<String>, val seedIds: List<String>)

object ZoneOverlap {
    fun intersects(a: DoubleArray, b: DoubleArray): Boolean {
        if (a.size != 4 || b.size != 4 || a.any { !it.isFinite() } || b.any { !it.isFinite() }) return false
        return min(a[2], b[2]) > max(a[0], b[0]) && min(a[3], b[3]) > max(a[1], b[1])
    }

    /** Share of [a]'s area covered by [b], 0..1. */
    fun fraction(a: DoubleArray, b: DoubleArray): Double {
        if (!intersects(a, b)) return 0.0
        val area = (a[2] - a[0]) * (a[3] - a[1])
        if (!(area > 0.0)) return 0.0
        return (((min(a[2], b[2]) - max(a[0], b[0])) * (min(a[3], b[3]) - max(a[1], b[1]))) / area).coerceIn(0.0, 1.0)
    }

    /** [ignoreId]: the stored pause zone being reshaped (it is not its own neighbour). */
    fun of(box: DoubleArray?, pause: List<MapItem>, seed: List<MapItem>, ignoreId: String? = null): Overlap {
        if (box == null) return Overlap(emptyList(), emptyList())
        return Overlap(
            pause.filter { it.id != ignoreId && intersects(box, it.box) }.map { it.id }.sorted(),
            seed.filter { intersects(box, it.box) }.map { it.id }.sorted(),
        )
    }

    /** The note on the Seed sheet (design 3.2.5): seeding is allowed, place cards stay off inside a pause zone. */
    fun seedNote(o: Overlap): String? = if (o.pauseIds.isEmpty()) null else "Part of this area is a pause zone: place cards stay off there."

    /** The note on the Pause sheet: overlapping zones are fine, each stays in force. */
    fun pauseNote(o: Overlap): String? = when (o.pauseIds.size) {
        0 -> null
        1 -> "This overlaps another pause zone. Both stay in force."
        else -> "This overlaps ${o.pauseIds.size} other pause zones. All stay in force."
    }
}

/** The four coordinate fields (south, west, north, east) or the circle's three, as text. */
data class CoordText(val a: String, val b: String, val c: String, val d: String = "")

sealed interface CoordResult {
    data class Ok(val shape: DrawnShape) : CoordResult
    data class Error(val text: String) : CoordResult
}

/** "Edit coordinates": exact placement for TalkBack and precision. Same limits as dragging: finite, on the map, a real span, no crossing of the 180 degree line. */
object CoordinateFields {
    const val LAT_LIMIT = MapViewport.MAX_LAT

    private fun num(s: String): Double? {
        val t = s.trim().replace(',', '.')
        if (t.isEmpty() || t.length > 20) return null
        if (!Regex("""[+-]?\d{1,3}(\.\d{1,6})?""").matches(t)) return null
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun parseRect(south: String, west: String, north: String, east: String): CoordResult {
        val s = num(south); val w = num(west); val n = num(north); val e = num(east)
        if (s == null || w == null || n == null || e == null) return CoordResult.Error("Enter all four numbers, for example 27.33 and 88.61 (up to 6 decimals).")
        if (s < -LAT_LIMIT || n > LAT_LIMIT) return CoordResult.Error("Latitude must be between -${LAT_LIMIT.toInt()} and ${LAT_LIMIT.toInt()}.")
        if (w < -180.0 || e > 180.0) return CoordResult.Error("Longitude must be between -180 and 180.")
        if (s >= n) return CoordResult.Error("South must be less than north.")
        if (w >= e) return CoordResult.Error("West must be less than east (an area across the 180 degree line cannot be drawn).")
        if (n - s < ShapeEdit.MIN_SPAN_DEG || e - w < ShapeEdit.MIN_SPAN_DEG) return CoordResult.Error("The area is too small.")
        val r = DrawnShape.Rect(s, w, n, e)
        return if (ShapeEdit.box(r) != null) CoordResult.Ok(r) else CoordResult.Error("That area cannot be drawn.")
    }

    fun parseCircle(lat: String, lon: String, radiusM: String): CoordResult {
        val la = num(lat); val lo = num(lon); val r = num(radiusM)
        if (la == null || lo == null || r == null) return CoordResult.Error("Enter the centre latitude, longitude and the radius in metres.")
        if (abs(la) > LAT_LIMIT || abs(lo) > 180.0) return CoordResult.Error("That centre is outside the map.")
        if (r < ShapeEdit.MIN_RADIUS_M || r > ShapeEdit.MAX_RADIUS_M) return CoordResult.Error("The radius must be ${ShapeEdit.MIN_RADIUS_M.toInt()} to ${ShapeEdit.MAX_RADIUS_M.toInt()} metres.")
        val c = DrawnShape.Circle(la, lo, r)
        return if (ShapeEdit.box(c) != null) CoordResult.Ok(c) else CoordResult.Error("That circle cannot be drawn here.")
    }

    fun textOf(s: DrawnShape): CoordText = when (s) {
        is DrawnShape.Rect -> CoordText(fmt(s.south), fmt(s.west), fmt(s.north), fmt(s.east))
        is DrawnShape.Circle -> CoordText(fmt(s.lat), fmt(s.lon), Math.round(s.radiusM).toString())
    }

    private fun fmt(d: Double) = String.format(java.util.Locale.US, "%.5f", d)
}

/** Double tap to zoom in x2 about the tapped point. */
object DoubleTap {
    const val WINDOW_MS = 300L
    const val FACTOR = 2.0

    fun isDouble(prevAtMs: Long?, prevX: Float, prevY: Float, atMs: Long, x: Float, y: Float, slopPx: Float): Boolean {
        if (prevAtMs == null || !(x.isFinite() && y.isFinite() && prevX.isFinite() && prevY.isFinite())) return false
        val dt = atMs - prevAtMs
        return dt in 1..WINDOW_MS && abs(x - prevX) <= slopPx && abs(y - prevY) <= slopPx
    }
}

/** "Also seed this area" (design 3.1.4): a pause zone is never converted. It makes a separate Seed draft from the same box; the pause zone is not touched. */
object AlsoSeed {
    fun draftOf(zoneBox: DoubleArray?): DrawnShape.Rect? {
        val b = zoneBox?.takeIf { it.size == 4 && it.all { d -> d.isFinite() } } ?: return null
        return DrawnShape.Rect(b[0], b[1], b[2], b[3]).takeIf { ShapeEdit.box(it) != null }
    }
}

enum class CellState { OK, PARTIAL, MISSING }
data class CellCover(val key: String, val box: DoubleArray, val state: CellState)
data class Coverage(val cells: List<CellCover>, val ok: Int, val partial: Int, val missing: Int) {
    val summary: String get() = "${cells.size} cell${if (cells.size == 1) "" else "s"}: $ok ready, $partial partial, $missing not fetched"
}

/** "Show cells": colours each 5 km cell of a seed area by its stored status. Pure; the statuses come from the places database through a read-only call. */
object SeedCoverage {
    fun of(box: GeoBox, statuses: Map<String, String>): Coverage? {
        if (!box.valid) return null
        val n = SeedEstimate.cells(box)
        if (n <= 0 || n > SeedEstimate.MAX_CELLS) return null
        val cells = Geohash5.cellsCovering(box).mapNotNull { key ->
            val b = Geohash5.bounds(key) ?: return@mapNotNull null
            val st = when (statuses[key]) { com.rokid.context.memory.place.CELL_OK -> CellState.OK; com.rokid.context.memory.place.CELL_PARTIAL -> CellState.PARTIAL; else -> CellState.MISSING }
            CellCover(key, doubleArrayOf(b.south, b.west, b.north, b.east), st)
        }
        return Coverage(cells, cells.count { it.state == CellState.OK }, cells.count { it.state == CellState.PARTIAL }, cells.count { it.state == CellState.MISSING })
    }
}

/** Spoken labels for the new controls (TalkBack and test tags share them). Counts and sizes only; never a name or a coordinate. */
object EditWords {
    const val UNDO = "Undo the last change to the drawn area"
    const val REDO = "Redo the change"
    const val UNDO_NONE = "Nothing to undo"
    const val COORDS = "Edit coordinates"
    const val ALSO_SEED = "Also seed this area"
    const val SHOW_CELLS = "Show cells"
    const val HIDE_CELLS = "Hide cells"
    const val DENSITY = "Show my Moments on the map"

    fun undoLabel(h: DraftHistory) = if (h.canUndo) UNDO else UNDO_NONE
}
