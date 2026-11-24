package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.phone.ui.settings.privacy.zones.LatLon
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MapGeometryTest {
    private val v = MapViewport(27.3, 88.6, 2_000.0, 1000f, 1600f) // about Sikkim
    private val rect = DrawnShape.Rect(27.30, 88.60, 27.32, 88.63)

    // --- viewport math (zero size, NaN, antimeridian) ---
    @Test fun viewportRoundTripAndEdgeSafety() {
        val p = v.toPx(27.31, 88.61); val back = v.toLatLon(p.x, p.y)
        assertEquals(27.31, back.lat, 1e-6); assertEquals(88.61, back.lon, 1e-6)
        val d = MapViewport.fit(emptyList(), 0f, 0f); assertTrue(d.ppd.isFinite() && d.centerLat.isFinite())
        val z = MapViewport.fit(listOf(doubleArrayOf(1.0, 1.0, 1.0, 1.0)), 400f, 400f); assertTrue(z.ppd.isFinite())
        val far = MapViewport(0.0, 179.999, 5_000.0, 400f, 400f).toLatLon(10_000f, 0f); assertTrue(far.lon <= 180.0)
    }

    // --- rectangle and circle shapes ---
    @Test fun rectFromDragAnyOrderRefusesTinyNanAndClamps() {
        val a = DrawnShape.Rect(10.0, 20.0, 11.0, 21.0)
        assertEquals(a, ShapeEdit.rectFromDrag(LatLon(11.0, 21.0), LatLon(10.0, 20.0)))
        assertEquals(a, ShapeEdit.rectFromDrag(LatLon(10.0, 21.0), LatLon(11.0, 20.0)))
        assertNull(ShapeEdit.rectFromDrag(LatLon(10.0, 20.0), LatLon(10.0001, 20.0001)))
        assertNull(ShapeEdit.rectFromDrag(LatLon(10.0, 20.0), LatLon(10.0, 25.0))) // zero height
        assertNull(ShapeEdit.rectFromDrag(LatLon(Double.NaN, 20.0), LatLon(11.0, 21.0)))
        assertNull(ShapeEdit.rectFromDrag(LatLon(10.0, Double.POSITIVE_INFINITY), LatLon(11.0, 21.0)))
        val big = ShapeEdit.rectFromDrag(LatLon(-89.0, -179.0), LatLon(89.0, 179.0))!!
        assertEquals(-85.0, big.south, 0.0); assertEquals(85.0, big.north, 0.0)
    }

    @Test fun circleBoxHoldsEverySampledCirclePoint() {
        for (lat in listOf(0.0, 27.0, 60.0)) for (r in listOf(100.0, 300.0, 5_000.0, 50_000.0)) {
            val b = CircleBox.of(lat, 88.0, r)!!
            val rad = 6_371_008.0
            for (deg in 0 until 360) {
                val th = Math.toRadians(deg.toDouble()); val d = r / rad
                val la1 = Math.toRadians(lat)
                val la2 = Math.asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(th))
                val lo2 = Math.toRadians(88.0) + Math.atan2(sin(th) * sin(d) * cos(la1), cos(d) - sin(la1) * sin(la2))
                val pla = Math.toDegrees(la2); val plo = Math.toDegrees(lo2)
                assertTrue("lat $lat r $r deg $deg", pla in b[0]..b[2] && plo in b[1]..b[3])
            }
        }
    }

    @Test fun circleBoxRefusesUnusableCircles() {
        assertNull(CircleBox.of(0.0, 0.0, 0.0)); assertNull(CircleBox.of(0.0, 0.0, -5.0))
        assertNull(CircleBox.of(Double.NaN, 0.0, 100.0)); assertNull(CircleBox.of(0.0, 0.0, Double.NaN)); assertNull(CircleBox.of(0.0, 0.0, Double.POSITIVE_INFINITY))
        assertNull(CircleBox.of(0.0, 179.99, 5_000.0)) // would cross the 180 degree line
        assertNull(CircleBox.of(0.0, -179.99, 5_000.0))
        assertNull(CircleBox.of(86.0, 0.0, 100.0)) // past the map's latitude limit
        assertNotNull(CircleBox.of(0.0, 179.0, 5_000.0))
    }

    @Test fun circleFromDragNeedsRadiusAndStaysUsable() {
        assertNull(ShapeEdit.circleFromDrag(LatLon(27.0, 88.0), LatLon(27.0, 88.0)))
        val c = ShapeEdit.circleFromDrag(LatLon(27.0, 88.0), LatLon(27.0, 88.01))!!
        assertEquals(990.0, c.radiusM, 30.0)
    }

    // --- hit test, 48 dp targets ---
    @Test fun handlesWinWithin48dpAndBodyIsInside() {
        val pts = ShapeEdit.handlePoints(v, rect)!!.toMap()
        val nw = pts.getValue(Handle.NW); val se = pts.getValue(Handle.SE)
        assertEquals(Hit.OnHandle(Handle.NW), ShapeEdit.hit(v, rect, nw.first + 23f, nw.second - 23f, 48f))
        assertEquals(Hit.OnHandle(Handle.SE), ShapeEdit.hit(v, rect, se.first - 20f, se.second + 20f, 48f))
        val cx = (nw.first + se.first) / 2; val cy = (nw.second + se.second) / 2
        assertEquals(Hit.OnBody, ShapeEdit.hit(v, rect, cx, cy, 48f))
        assertEquals(Hit.Miss, ShapeEdit.hit(v, rect, nw.first - 200f, nw.second - 200f, 48f))
        assertEquals(Hit.Miss, ShapeEdit.hit(v, rect, 5f, 5f, 0f)) // a zero target hits nothing
        assertEquals(Hit.Miss, ShapeEdit.hit(v, rect, Float.NaN, 5f, 48f))
    }

    @Test fun overlappingHandlesPickTheNearest() {
        val tiny = DrawnShape.Rect(27.30, 88.60, 27.3002, 88.6002) // handles a few px apart
        val pts = ShapeEdit.handlePoints(v, tiny)!!.toMap()
        val ne = pts.getValue(Handle.NE)
        assertEquals(Hit.OnHandle(Handle.NE), ShapeEdit.hit(v, tiny, ne.first + 1f, ne.second - 1f, 48f))
    }

    @Test fun circleHandles() {
        val c = DrawnShape.Circle(27.3, 88.6, 1_000.0)
        val p = ShapeEdit.handlePoints(v, c)!!.toMap()
        val r = p.getValue(Handle.RADIUS); val m = p.getValue(Handle.CENTRE)
        assertEquals(Hit.OnHandle(Handle.RADIUS), ShapeEdit.hit(v, c, r.first, r.second, 48f))
        assertEquals(Hit.OnHandle(Handle.CENTRE), ShapeEdit.hit(v, c, m.first + 3f, m.second + 3f, 48f))
        assertEquals(Hit.OnBody, ShapeEdit.hit(v, c, m.first + (r.first - m.first) / 2, m.second + 30f * 0, 8f))
        assertEquals(Hit.Miss, ShapeEdit.hit(v, c, m.first, m.second + (r.first - m.first) * 2, 48f))
    }

    // --- move and resize with clamping ---
    @Test fun moveKeepsSizeAndClampsAtTheMapLimits() {
        val m = ShapeEdit.move(rect, 0.01, 0.02) as DrawnShape.Rect
        assertEquals(rect.north - rect.south, m.north - m.south, 1e-9); assertEquals(rect.east - rect.west, m.east - m.west, 1e-9)
        val top = ShapeEdit.move(rect, 100.0, 0.0) as DrawnShape.Rect
        assertEquals(85.0, top.north, 1e-9); assertEquals(rect.north - rect.south, top.north - top.south, 1e-9)
        val east = ShapeEdit.move(rect, 0.0, 500.0) as DrawnShape.Rect
        assertEquals(180.0, east.east, 1e-9)
        assertEquals(rect, ShapeEdit.move(rect, Double.NaN, 1.0))
        val c = DrawnShape.Circle(0.0, 179.0, 5_000.0)
        assertEquals(c, ShapeEdit.move(c, 0.0, 5.0)) // would cross the 180 degree line: unchanged
    }

    @Test fun resizeCornerFollowsAndNeverCrossesOpposite() {
        val r = ShapeEdit.resize(rect, Handle.NE, LatLon(27.40, 88.70)) as DrawnShape.Rect
        assertEquals(27.40, r.north, 1e-9); assertEquals(88.70, r.east, 1e-9); assertEquals(rect.south, r.south, 0.0); assertEquals(rect.west, r.west, 0.0)
        val crossed = ShapeEdit.resize(rect, Handle.NE, LatLon(27.0, 88.0)) as DrawnShape.Rect // dragged past the opposite corner
        assertEquals(rect.south + ShapeEdit.MIN_SPAN_DEG, crossed.north, 1e-9); assertEquals(rect.west + ShapeEdit.MIN_SPAN_DEG, crossed.east, 1e-9)
        val sw = ShapeEdit.resize(rect, Handle.SW, LatLon(27.31, 88.62)) as DrawnShape.Rect
        assertTrue(sw.south < sw.north && sw.west < sw.east)
        assertEquals(rect, ShapeEdit.resize(rect, Handle.NE, LatLon(Double.NaN, 88.0)))
        assertEquals(rect, ShapeEdit.resize(rect, Handle.CENTRE, LatLon(27.0, 88.0)))
        val limit = ShapeEdit.resize(rect, Handle.NE, LatLon(500.0, 500.0)) as DrawnShape.Rect
        assertEquals(85.0, limit.north, 0.0); assertEquals(180.0, limit.east, 0.0)
    }

    @Test fun resizeCircleRadiusClamps() {
        val c = DrawnShape.Circle(27.3, 88.6, 1_000.0)
        assertEquals(ShapeEdit.MIN_RADIUS_M, (ShapeEdit.resize(c, Handle.RADIUS, LatLon(27.3, 88.6)) as DrawnShape.Circle).radiusM, 0.0)
        assertEquals(ShapeEdit.MAX_RADIUS_M, (ShapeEdit.resize(c, Handle.RADIUS, LatLon(27.3, 89.6 + 5)) as DrawnShape.Circle).radiusM, 0.0)
        assertEquals(2_000.0, (ShapeEdit.resize(c, Handle.RADIUS, LatLon(27.3, 88.6 + 2_000.0 / (111_320.0 * cos(Math.toRadians(27.3))))) as DrawnShape.Circle).radiusM, 5.0)
        assertEquals(c, ShapeEdit.resize(c, Handle.RADIUS, LatLon(Double.NaN, 0.0)))
        val moved = ShapeEdit.resize(c, Handle.CENTRE, LatLon(27.4, 88.7)) as DrawnShape.Circle
        assertEquals(27.4, moved.lat, 1e-9); assertEquals(1_000.0, moved.radiusM, 0.0)
    }

    @Test fun defaultShapeAndOutwardRounding() {
        assertNotNull(ShapeEdit.defaultShape(v, ShapeKind.RECT)); assertNotNull(ShapeEdit.defaultShape(v, ShapeKind.CIRCLE))
        assertNull(ShapeEdit.defaultShape(MapViewport(27.0, 88.0, 100.0, 0f, 0f), ShapeKind.RECT))
        val o = ShapeEdit.outward(doubleArrayOf(17.244349, 78.670849, 17.264351, 78.690851))
        assertEquals(17.24434, o[0], 1e-12); assertEquals(78.67084, o[1], 1e-12); assertEquals(17.26436, o[2], 1e-12); assertEquals(78.69086, o[3], 1e-12)
        val b = doubleArrayOf(-17.244349, -78.670849, -17.264351, -78.690851)
        val n = ShapeEdit.outward(b); assertTrue(n[0] <= b[0] && n[1] <= b[1] && n[2] >= b[2] && n[3] >= b[3])
        assertTrue(ShapeEdit.sizeText(rect).contains("km"))
    }
}
