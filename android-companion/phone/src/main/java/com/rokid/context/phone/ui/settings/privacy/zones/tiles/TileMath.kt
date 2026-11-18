package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh

data class TileKey(val z: Int, val x: Int, val y: Int)

/** Slippy-map tile maths over the same Web Mercator the canvas uses ([MapViewport.ppd] is pixels per degree of longitude). Pure, so it is unit tested. */
object TileMath {
    const val TILE_PX = 256.0

    /** Pixels per degree of longitude when one 256 px tile covers 1/2^z of the world. */
    fun ppdAt(z: Int): Double = TILE_PX * 2.0.pow(z) / 360.0

    /** The zoom whose tiles are closest to 1:1 on screen, kept inside the archive's [minZoom]..[maxZoom] (lower zooms are stretched, higher ones shrunk). */
    fun zoomFor(ppd: Double, minZoom: Int, maxZoom: Int): Int {
        val ideal = (ln(ppd * 360.0 / TILE_PX) / ln(2.0)).roundToInt()
        return ideal.coerceIn(minZoom, maxZoom)
    }

    fun lonOf(x: Double, z: Int): Double = x / 2.0.pow(z) * 360.0 - 180.0
    fun latOf(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * y / 2.0.pow(z)))))

    /** The tiles that cover the viewport at zoom [z], in reading order; empty when more than [cap] would be needed (zoomed far out of the archive's range). */
    fun visible(vp: MapViewport, z: Int, cap: Int = MAX_VISIBLE): List<TileKey> {
        if (vp.width <= 0f || vp.height <= 0f) return emptyList()
        val tl = vp.toLatLon(0f, 0f); val br = vp.toLatLon(vp.width, vp.height)
        val n = 2.0.pow(z)
        val last = n.toInt() - 1
        fun xOf(lon: Double) = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, last)
        fun yOf(lat: Double): Int {
            val r = Math.toRadians(lat.coerceIn(-MapViewport.MAX_LAT, MapViewport.MAX_LAT))
            return floor((1.0 - ln(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, last)
        }
        val x0 = xOf(tl.lon); val x1 = xOf(br.lon); val y0 = yOf(tl.lat); val y1 = yOf(br.lat)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > cap) return emptyList()
        return buildList { for (y in y0..y1) for (x in x0..x1) add(TileKey(z, x, y)) }
    }

    /** The tile's top-left corner and size on screen (px), from its north-west and south-east corners. */
    fun screenRect(vp: MapViewport, k: TileKey): FloatArray {
        val a = vp.toPx(latOf(k.y.toDouble(), k.z), lonOf(k.x.toDouble(), k.z))
        val b = vp.toPx(latOf(k.y + 1.0, k.z), lonOf(k.x + 1.0, k.z))
        return floatArrayOf(a.x, a.y, b.x - a.x, b.y - a.y)
    }

    const val MAX_VISIBLE = 64
}

/**
 * Decoded tiles, newest-used kept (at most [capacity]); a tile the archive does not have is remembered as absent so it is not looked up again.
 * [load] reads and decodes one tile (blocking: call [ensure] from a background dispatcher). A read that throws counts one [problems] and the tile is treated as absent.
 */
class TileCache<T : Any>(private val capacity: Int = 96, private val load: (TileKey) -> T?) {
    private val lock = Any()
    private val map = object : LinkedHashMap<TileKey, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<TileKey, Any>) = size > capacity
    }
    @Volatile var problems: Int = 0
        private set

    /** The decoded tile when already loaded, else null (absent or not loaded yet). Cheap: for the draw pass. */
    @Suppress("UNCHECKED_CAST")
    fun peek(k: TileKey): T? = synchronized(lock) { map[k]?.takeIf { it !== ABSENT } as T? }

    /** Loads every key not yet known. Returns whether anything new became drawable. */
    fun ensure(keys: List<TileKey>): Boolean {
        var changed = false
        for (k in keys) {
            if (synchronized(lock) { map.containsKey(k) }) continue
            val v: T? = try { load(k) } catch (e: Exception) { problems++; null }
            synchronized(lock) { map[k] = v ?: ABSENT }
            if (v != null) changed = true
        }
        return changed
    }

    private companion object { val ABSENT = Any() }
}
