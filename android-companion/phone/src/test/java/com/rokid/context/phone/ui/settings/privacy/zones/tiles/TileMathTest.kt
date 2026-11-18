package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {
    @Test fun zoomIsTheClosestToOneToOneAndClampedToTheArchive() {
        for (z in 0..18) assertEquals(z, TileMath.zoomFor(TileMath.ppdAt(z), 0, 20))
        assertEquals(10, TileMath.zoomFor(TileMath.ppdAt(4), 10, 16)) // zoomed far out: lowest zoom the archive has
        assertEquals(16, TileMath.zoomFor(TileMath.ppdAt(19), 10, 16)) // zoomed far in: stretch the top zoom
    }

    @Test fun tileCornersRoundTripThroughTheViewport() {
        // a Sri Lanka view at z10: the tile that holds the centre is on screen and 256 px wide at 1:1
        val z = 10
        val vp = MapViewport(7.9, 80.7, TileMath.ppdAt(z), 800f, 600f)
        val keys = TileMath.visible(vp, z)
        assertTrue(keys.isNotEmpty())
        val n = Math.pow(2.0, z.toDouble())
        val rad = Math.toRadians(7.9)
        val c = TileKey(z, Math.floor((80.7 + 180) / 360 * n).toInt(), Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * n).toInt())
        assertTrue(c in keys)
        val r = TileMath.screenRect(vp, c)
        assertEquals(256f, r[2], 0.6f); assertEquals(256f, r[3], 0.6f)
        assertTrue(r[0] < 400f && r[0] + r[2] > 400f && r[1] < 300f && r[1] + r[3] > 300f) // the centre pixel lies inside the tile
    }

    @Test fun visibleTilesCoverEveryCornerOfTheViewAndNothingFarOutside() {
        val z = 12
        val vp = MapViewport(6.9271, 79.8612, TileMath.ppdAt(z), 720f, 900f)
        val keys = TileMath.visible(vp, z).toSet()
        for ((px, py) in listOf(0f to 0f, 719f to 0f, 0f to 899f, 719f to 899f, 360f to 450f)) {
            val ll = vp.toLatLon(px, py)
            val n = Math.pow(2.0, z.toDouble())
            val x = Math.floor((ll.lon + 180) / 360 * n).toInt()
            val rad = Math.toRadians(ll.lat)
            val y = Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * n).toInt()
            assertTrue("corner $px,$py", TileKey(z, x, y) in keys)
        }
        assertTrue(keys.size in 6..20)
        // at 1:1 the grid is about ceil(w/256)+1 by ceil(h/256)+1 at most
        assertTrue(keys.size <= 5 * 5)
    }

    @Test fun tooManyTilesIsRefusedNotLoaded() {
        val vp = MapViewport(7.9, 80.7, TileMath.ppdAt(12), 8000f, 8000f)
        assertTrue(TileMath.visible(vp, 12).isEmpty())
        assertTrue(TileMath.visible(vp, 12, cap = 2_000).isNotEmpty())
        assertTrue(TileMath.visible(MapViewport(0.0, 0.0, 1.0, 0f, 0f), 3).isEmpty())
    }

    @Test fun tilesAtTheEdgesOfTheWorldAreClamped() {
        val vp = MapViewport(0.0, 179.9, TileMath.ppdAt(3), 800f, 400f)
        assertTrue(TileMath.visible(vp, 3).all { it.x in 0..7 && it.y in 0..7 })
    }

    @Test fun cacheLoadsOnceRemembersAbsentAndCountsProblems() {
        var loads = 0
        val cache = TileCache<String> { k -> loads++; when (k.x) { 0 -> "t0"; 1 -> null; else -> error("boom") } }
        val keys = listOf(TileKey(1, 0, 0), TileKey(1, 1, 0), TileKey(1, 2, 0))
        assertTrue(cache.ensure(keys))
        assertEquals("t0", cache.peek(keys[0])); assertNull(cache.peek(keys[1])); assertNull(cache.peek(keys[2]))
        assertEquals(1, cache.problems)
        assertEquals(false, cache.ensure(keys)) // nothing new, no second read
        assertEquals(3, loads)
    }

    @Test fun cacheEvictsTheLeastRecentlyUsed() {
        val cache = TileCache<String>(capacity = 2) { "v${it.x}" }
        cache.ensure(listOf(TileKey(1, 0, 0), TileKey(1, 1, 0)))
        cache.peek(TileKey(1, 0, 0))
        cache.ensure(listOf(TileKey(1, 2, 0)))
        assertEquals("v0", cache.peek(TileKey(1, 0, 0))); assertNull(cache.peek(TileKey(1, 1, 0)))
    }
}
