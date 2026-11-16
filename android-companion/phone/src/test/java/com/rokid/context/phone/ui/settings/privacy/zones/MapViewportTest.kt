package com.rokid.context.phone.ui.settings.privacy.zones

import com.rokid.context.core.model.zone.ZonePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewportTest {
    private val v = MapViewport(12.0, 92.7, 1_000.0, 800f, 600f)

    @Test fun centerMapsToViewCenterAndBack() {
        val p = v.toPx(12.0, 92.7)
        assertEquals(400f, p.x, 0.01f); assertEquals(300f, p.y, 0.01f)
        val ll = v.toLatLon(500f, 200f)
        val back = v.toPx(ll.lat, ll.lon)
        assertEquals(500f, back.x, 0.01f); assertEquals(200f, back.y, 0.01f)
    }

    @Test fun northIsUp() { assertTrue(v.toPx(12.1, 92.7).y < v.toPx(12.0, 92.7).y) }

    @Test fun zoomKeepsTheAnchorFixed() {
        val before = v.toLatLon(600f, 100f)
        val z = v.zoomed(2.0, 600f, 100f)
        val after = z.toLatLon(600f, 100f)
        assertEquals(before.lat, after.lat, 1e-6); assertEquals(before.lon, after.lon, 1e-6)
        assertEquals(2_000.0, z.ppd, 1e-9)
    }

    @Test fun fitHoldsTheJarawaRing() {
        val box = MapViewport.ringOf(ZonePresets.JARAWA)
        val f = MapViewport.fit(listOf(box), 800f, 600f)
        val tl = f.toPx(box[2], box[1]); val br = f.toPx(box[0], box[3])
        assertTrue(tl.x >= 0f && tl.y >= 0f && br.x <= 800f && br.y <= 600f)
    }

    @Test fun ringGrowsByTheBuffer() {
        val z = ZonePresets.JARAWA
        val ring = MapViewport.ringOf(z)
        assertEquals(5_000.0 / 111_320.0, z.south - ring[0], 1e-6)
        assertTrue(ring[1] < z.west && ring[3] > z.east && ring[2] > z.north)
    }

    @Test fun gridLinesAreFarEnoughApart() {
        val step = v.gridStepDeg()
        assertTrue(step * v.ppd >= MapViewport.MIN_GRID_PX)
    }

    @Test fun degreeLabels() {
        assertEquals("12.50°N", degText(12.5, 2, "N", "S"))
        assertEquals("92.7°W", degText(-92.7, 1, "E", "W"))
    }
}
