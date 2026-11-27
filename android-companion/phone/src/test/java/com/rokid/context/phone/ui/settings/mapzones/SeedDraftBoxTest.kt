package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.phone.ui.settings.privacy.zones.LatLon
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import com.rokid.context.phone.ui.settings.privacy.zones.SeedEstimate
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneDraft
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneDrafts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The draft seed box: side handles, move, live cell count, clear. Pure math; the drags themselves are on the device check. */
class SeedDraftBoxTest {
    private val v = MapViewport(27.3, 88.6, 2_000.0, 1000f, 1600f)
    private val r = DrawnShape.Rect(27.30, 88.60, 27.32, 88.63)

    @Test fun sideHandlesExistOnlyWhenAsked() {
        assertEquals(4, ShapeEdit.handlePoints(v, r)!!.size)
        val hs = ShapeEdit.handlePoints(v, r, edges = true)!!.map { it.first }.toSet()
        assertEquals(setOf(Handle.NW, Handle.NE, Handle.SW, Handle.SE, Handle.N, Handle.S, Handle.E, Handle.W), hs)
    }

    @Test fun aFingerOnASideMidpointHitsThatSideAndMissesWithoutEdges() {
        val mid = ShapeEdit.handlePoints(v, r, edges = true)!!.first { it.first == Handle.E }.second
        assertEquals(Hit.OnHandle(Handle.E), ShapeEdit.hit(v, r, mid.first, mid.second, 48f, edges = true))
        // without the side handles the same point is inside the box edge only
        assertTrue(ShapeEdit.hit(v, r, mid.first, mid.second, 48f) != Hit.OnHandle(Handle.E))
    }

    @Test fun aSideDragMovesOnlyThatSideAndKeepsTheMinimumSpan() {
        assertEquals(DrawnShape.Rect(27.30, 88.60, 27.35, 88.63), ShapeEdit.resize(r, Handle.N, LatLon(27.35, 88.9)))
        assertEquals(DrawnShape.Rect(27.28, 88.60, 27.32, 88.63), ShapeEdit.resize(r, Handle.S, LatLon(27.28, 88.1)))
        assertEquals(DrawnShape.Rect(27.30, 88.60, 27.32, 88.70), ShapeEdit.resize(r, Handle.E, LatLon(0.0, 88.70)))
        assertEquals(DrawnShape.Rect(27.30, 88.55, 27.32, 88.63), ShapeEdit.resize(r, Handle.W, LatLon(0.0, 88.55)))
        val crossed = ShapeEdit.resize(r, Handle.E, LatLon(0.0, 88.0)) as DrawnShape.Rect // dragged past the west side: stays a valid box
        assertEquals(88.60 + ShapeEdit.MIN_SPAN_DEG, crossed.east, 1e-12)
        assertNotNull(ShapeEdit.box(crossed))
    }

    @Test fun movingKeepsTheSizeSoTheCellCountOnlyChangesWithTheNewPlace() {
        val moved = ShapeEdit.move(r, 0.05, 0.05) as DrawnShape.Rect
        assertEquals(r.north - r.south, moved.north - moved.south, 1e-12)
        assertEquals(r.east - r.west, moved.east - moved.west, 1e-12)
    }

    @Test fun theLiveCellCountFollowsTheBoxAsASideIsDragged() {
        val small = SeedEstimate.cells(SeedEstimate.boxOf(ShapeEdit.box(r))!!)
        val grown = ShapeEdit.resize(ShapeEdit.resize(r, Handle.N, LatLon(28.5, 0.0)), Handle.E, LatLon(0.0, 90.5))
        val big = SeedEstimate.cells(SeedEstimate.boxOf(ShapeEdit.box(grown))!!)
        assertTrue("$small < $big", big > small)
        assertTrue(SeedEstimate.line(SeedEstimate.boxOf(ShapeEdit.box(grown))).contains("$big"))
    }

    @Test fun clearAndWithBoxOnTheTextDraft() {
        val d = ZoneDraft(name = "x", buffer = "100")
        val set = ZoneDrafts.withBox(d, doubleArrayOf(27.3, 88.6, 27.4, 88.7))
        assertEquals(doubleArrayOf(27.3, 88.6, 27.4, 88.7).toList(), set.box()!!.toList())
        assertEquals("x", set.name); assertEquals("100", set.buffer)
        assertEquals(d, ZoneDrafts.withBox(d, doubleArrayOf(27.4, 88.6, 27.3, 88.7))) // out of order: unchanged
        assertEquals(d, ZoneDrafts.withBox(d, doubleArrayOf(Double.NaN, 88.6, 27.3, 88.7)))
        val cleared = ZoneDrafts.withoutBox(set)
        assertEquals(null, cleared.box()); assertEquals("x", cleared.name); assertEquals("100", cleared.buffer)
    }
}
