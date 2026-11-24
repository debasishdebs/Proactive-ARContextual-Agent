package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import kotlin.math.abs

/** What a tap on the map landed on. */
sealed interface TapTarget {
    data class Item(val type: ItemType, val id: String) : TapTarget
    /** Several merged chips: the caller zooms to fit these. */
    data class Cluster(val ids: List<String>) : TapTarget
    data object None : TapTarget
}

/** Pure tap hit-test over what the map draws (chips first, then shapes: pause before seed, the smaller shape first). */
object MapTap {
    fun at(items: List<MapItem>, v: MapViewport, x: Float, y: Float, minShapePx: Float, touchPx: Float, layers: Set<MapLayer>): TapTarget {
        if (!(x.isFinite() && y.isFinite() && touchPx > 0f && v.ppd.isFinite())) return TapTarget.None
        val half = touchPx / 2
        var best: MapCluster? = null; var bestD = Float.MAX_VALUE
        for (c in MapOverview.clusters(items, v, minShapePx, layers)) {
            val p = v.toPx(c.centerLat, c.centerLon)
            val dx = abs(p.x - x); val dy = abs(p.y - y)
            if (dx <= half && dy <= half && dx + dy < bestD) { bestD = dx + dy; best = c }
        }
        best?.let { c -> return if (c.isSingle) TapTarget.Item(c.type, c.ids.first()) else TapTarget.Cluster(c.ids) }
        var hit: MapItem? = null; var hitArea = Float.MAX_VALUE
        for (item in items) {
            if (MapOverview.layerOf(item.type) !in layers || item.type == ItemType.PIN || item.box.size != 4 || item.box.any { d -> !d.isFinite() }) continue
            val tl = v.toPx(item.box[2], item.box[1]); val br = v.toPx(item.box[0], item.box[3])
            val w = br.x - tl.x; val h = br.y - tl.y
            if (w < minShapePx || h < minShapePx) continue
            if (x !in tl.x..br.x || y !in tl.y..br.y) continue
            val area = w * h
            val cur = hit
            val better = cur == null || (item.type == ItemType.PAUSE && cur.type != ItemType.PAUSE) || (item.type == cur.type && area < hitArea)
            if (better) { hit = item; hitArea = area }
        }
        return hit?.let { TapTarget.Item(it.type, it.id) } ?: TapTarget.None
    }
}
