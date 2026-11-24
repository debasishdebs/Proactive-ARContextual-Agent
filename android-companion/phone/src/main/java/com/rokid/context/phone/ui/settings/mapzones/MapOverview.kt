package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import kotlin.math.floor

// Map zones P1 (flag mapZonesV2): the zoomed-out overview as pure logic: types, per-type look, clustering, the counts row that is also the legend, and the layer toggles.

enum class ItemType { PAUSE, SEED, PIN }

/** Colour is never the only cue: each type also has a fill pattern and an edge style. */
enum class FillPattern { HATCH, DOTS, NONE }
enum class EdgeStyle { SOLID, DOTTED, DASHED }

data class TypeLook(val argb: Long, val fillAlpha: Float, val pattern: FillPattern, val edge: EdgeStyle)

object TypeLooks {
    val PAUSE = TypeLook(0xFFD32F2F, 0.25f, FillPattern.HATCH, EdgeStyle.SOLID) // hatched, solid edge, dashed buffer ring
    val SEED = TypeLook(0xFF1E6FD9, 0.15f, FillPattern.DOTS, EdgeStyle.DOTTED)
    val PIN = TypeLook(0xFF7B3FC4, 1.0f, FillPattern.NONE, EdgeStyle.SOLID)
    fun of(t: ItemType) = when (t) { ItemType.PAUSE -> PAUSE; ItemType.SEED -> SEED; ItemType.PIN -> PIN }
}

/** One thing on the map as the overview needs it: a type and a box (a pin is a zero-size box). [alert] = a pause zone that is inside or being approached. */
data class MapItem(val id: String, val type: ItemType, val box: DoubleArray, val alert: Boolean = false) {
    override fun equals(other: Any?) = other is MapItem && id == other.id && type == other.type && box.contentEquals(other.box) && alert == other.alert
    override fun hashCode() = 31 * (31 * id.hashCode() + type.hashCode()) + box.contentHashCode()
}

/** A chip on the map: one item, or several merged. Pause wins the type (the safety signal). */
data class MapCluster(val type: ItemType, val count: Int, val ids: List<String>, val centerLat: Double, val centerLon: Double) {
    val isSingle: Boolean get() = count == 1
}

enum class MapLayer { PAUSE, SEED, PINS }

object MapOverview {
    const val CLUSTER_CELL_PX = 48f

    fun layerOf(t: ItemType) = when (t) { ItemType.PAUSE -> MapLayer.PAUSE; ItemType.SEED -> MapLayer.SEED; ItemType.PIN -> MapLayer.PINS }

    /**
     * Merges the items that draw as chips (smaller than [minShapePx] on screen) when they fall in the same grid cell of [CLUSTER_CELL_PX]. The grid is anchored to the world at the current zoom,
     * not to the screen, so the groups do not change as you pan. Items big enough to draw as shapes are not merged (they come back as single chips only for the caller to skip).
     * Items outside the view (plus a margin) are dropped. Returns clusters sorted by id for a stable result.
     */
    fun clusters(items: List<MapItem>, v: MapViewport, minShapePx: Float, layers: Set<MapLayer>, cellPx: Float = CLUSTER_CELL_PX): List<MapCluster> {
        if (!(v.width.isFinite() && v.height.isFinite() && v.ppd.isFinite()) || cellPx <= 0f) return emptyList()
        val world = MapViewport(0.0, 0.0, v.ppd, 0f, 0f) // world pixels at this zoom: x = lon * ppd, y from the Mercator row
        val groups = LinkedHashMap<Pair<Long, Long>, MutableList<MapItem>>()
        val margin = cellPx
        for (it in items.sortedBy { i -> i.id }) {
            if (layerOf(it.type) !in layers) continue
            val b = it.box
            if (b.size != 4 || b.any { d -> !d.isFinite() }) continue
            val tl = v.toPx(b[2], b[1]); val br = v.toPx(b[0], b[3])
            if (br.x < -margin || tl.x > v.width + margin || br.y < -margin || tl.y > v.height + margin) continue
            if ((br.x - tl.x) >= minShapePx && (br.y - tl.y) >= minShapePx) continue // drawn as a shape, not a chip
            val clat = (b[0] + b[2]) / 2; val clon = (b[1] + b[3]) / 2
            val w = world.toPx(clat, clon)
            val key = floor(w.x / cellPx).toLong() to floor(w.y / cellPx).toLong()
            groups.getOrPut(key) { ArrayList() } += it
        }
        return groups.values.map { g ->
            val type = if (g.any { it.type == ItemType.PAUSE }) ItemType.PAUSE else if (g.any { it.type == ItemType.SEED }) ItemType.SEED else ItemType.PIN
            MapCluster(type, g.size, g.map { it.id }, g.map { (it.box[0] + it.box[2]) / 2 }.average(), g.map { (it.box[1] + it.box[3]) / 2 }.average())
        }.sortedBy { it.ids.first() }
    }

    /** The counts row; each count is also a layer chip. */
    fun counts(pause: Int, seed: Int, pins: Int): String =
        "$pause pause · $seed seed · $pins pin${if (pins == 1) "" else "s"}"

    /** The spoken summary of the map (for the accessibility description). */
    fun summary(pause: Int, seed: Int, pins: Int, inside: Boolean): String =
        "Map with $pause pause zone${if (pause == 1) "" else "s"}, $seed seed area${if (seed == 1) "" else "s"}, $pins pin${if (pins == 1) "" else "s"}" + if (inside) "; you are inside a pause zone" else ""

    /**
     * Toggles [layer]. The pause layer cannot be hidden while any pause zone is inside or being approached ([pauseLocked]), so an active zone never vanishes from the view.
     */
    fun toggle(layers: Set<MapLayer>, layer: MapLayer, pauseLocked: Boolean): Set<MapLayer> {
        if (layer in layers) return if (layer == MapLayer.PAUSE && pauseLocked) layers else layers - layer
        return layers + layer
    }

    /** What is drawn: the layers, with pause forced on while locked. */
    fun effective(layers: Set<MapLayer>, pauseLocked: Boolean): Set<MapLayer> = if (pauseLocked) layers + MapLayer.PAUSE else layers
}
