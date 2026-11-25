package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.memory.place.CELL_OK
import com.rokid.context.memory.place.CELL_PARTIAL
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.phone.ui.settings.privacy.zones.ChipLevel
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapEditTest {
    private fun r(s: Double, w: Double, n: Double, e: Double) = DrawnShape.Rect(s, w, n, e)
    private val a = r(10.0, 20.0, 10.1, 20.1)
    private val b = r(10.0, 20.0, 10.2, 20.1)
    private val c = r(10.0, 20.0, 10.3, 20.1)

    // --- undo and redo ---
    @Test fun undoRedoWalkTheShapes() {
        var h = DraftHistory().record(a, 0)
        h = h.record(b, 1_000)
        val (back1, h1) = h.undo(c)!!
        assertEquals(b, back1)
        val (back2, h2) = h1.undo(back1)!!
        assertEquals(a, back2); assertFalse(h2.canUndo); assertNull(h2.undo(back2))
        val (fwd, h3) = h2.redo(back2)!!
        assertEquals(b, fwd); assertTrue(h3.canUndo)
    }

    @Test fun aDragBurstIsOneUndoStep() {
        var h = DraftHistory()
        var t = 0L
        h = h.record(a, t) // first event of the drag pushes the shape it started from
        repeat(20) { t += 16; h = h.record(r(10.0, 20.0, 10.0 + it, 20.1), t) }
        assertEquals(1, h.past.size)
        t += 2_000
        h = h.record(b, t)
        assertEquals(2, h.past.size)
    }

    @Test fun aNewEditClearsRedoAndHistoryIsCapped() {
        var h = DraftHistory().record(a, 0).record(b, 1_000)
        h = h.undo(c)!!.second
        assertTrue(h.canRedo)
        h = h.record(c, 5_000)
        assertFalse(h.canRedo)
        var big = DraftHistory(); var t = 0L
        repeat(200) { t += 1_000; big = big.record(r(1.0, 1.0, 2.0 + it * 0.001, 2.0), t) }
        assertEquals(DraftHistory.MAX_STEPS, big.past.size)
        assertEquals(a, DraftHistory().record(null, 0).let { a }) // a null shape records nothing
        assertTrue(DraftHistory().record(null, 5).past.isEmpty())
    }

    // --- the edit policy (fails closed) ---
    @Test fun reshapeIsAllowedOnlyWhenClearlyOutsideOrOff() {
        val B = ZoneEditPolicy.Block::class.java.enumConstants.associateBy { it.name }
        assertEquals(B.getValue("NONE"), ZoneEditPolicy.reshapeBlock(ChipLevel.CLEAR, false, false))
        assertEquals(B.getValue("NONE"), ZoneEditPolicy.reshapeBlock(ChipLevel.OFF, false, false))
        assertEquals(B.getValue("INSIDE_OR_UNKNOWN"), ZoneEditPolicy.reshapeBlock(ChipLevel.PAUSED, false, false))
        assertEquals(B.getValue("INSIDE_OR_UNKNOWN"), ZoneEditPolicy.reshapeBlock(ChipLevel.WARN, false, false))
        assertEquals(B.getValue("INSIDE_OR_UNKNOWN"), ZoneEditPolicy.reshapeBlock(ChipLevel.CLEAR, true, false)) // inside wins over a clear chip
        assertEquals(B.getValue("INSIDE_OR_UNKNOWN"), ZoneEditPolicy.reshapeBlock(null, false, false)) // no row = unknown
        assertEquals(B.getValue("UNREADABLE"), ZoneEditPolicy.reshapeBlock(ChipLevel.CLEAR, false, true))
        for (b in B.values) assertEquals(b == B.getValue("NONE"), ZoneEditPolicy.words(b) == null)
    }

    @Test fun shrinkIsDetectedAndUnknownCountsAsShrink() {
        val old = doubleArrayOf(10.0, 20.0, 11.0, 21.0)
        assertFalse(ZoneEditPolicy.shrinks(old, doubleArrayOf(9.9, 19.9, 11.1, 21.1)))
        assertFalse(ZoneEditPolicy.shrinks(old, old))
        assertTrue(ZoneEditPolicy.shrinks(old, doubleArrayOf(10.1, 20.0, 11.0, 21.0)))
        assertTrue(ZoneEditPolicy.shrinks(old, doubleArrayOf(10.0, 20.0, 11.0, 20.9)))
        assertTrue(ZoneEditPolicy.shrinks(old, doubleArrayOf(Double.NaN, 20.0, 11.0, 21.0)))
        assertTrue(ZoneEditPolicy.shrinks(old, doubleArrayOf(1.0)))
    }

    // --- snap ---
    private val vp = MapViewport(10.0, 20.0, 2_000.0, 400f, 400f)

    @Test fun movingARectSnapsOneEdgePerAxisAndKeepsItsSize() {
        val other = doubleArrayOf(10.0, 20.0, 10.05, 20.05)
        val prev = r(10.1, 20.1, 10.2, 20.2)
        val moved = r(10.0502, 20.0501, 10.1502, 20.1501) // its south and west edges are within a pixel of the other's north / east
        val out = ShapeSnap.snap(prev, moved, listOf(other), vp, 10f) as DrawnShape.Rect
        assertEquals(10.05, out.south, 1e-9); assertEquals(20.05, out.west, 1e-9)
        assertEquals(0.1, out.north - out.south, 1e-9); assertEquals(0.1, out.east - out.west, 1e-9)
    }

    @Test fun resizingSnapsOnlyTheMovingEdge() {
        val other = doubleArrayOf(10.0, 20.0, 10.05, 20.05)
        val prev = r(10.1, 20.1, 10.2, 20.2)
        val next = r(10.0504, 20.1, 10.2, 20.2) // only south moved
        val out = ShapeSnap.snap(prev, next, listOf(other), vp, 10f) as DrawnShape.Rect
        assertEquals(10.05, out.south, 1e-9); assertEquals(20.1, out.west, 0.0); assertEquals(10.2, out.north, 0.0)
    }

    @Test fun farEdgesDoNotSnapAndBadInputIsHarmless() {
        val prev = r(10.1, 20.1, 10.2, 20.2); val next = r(10.12, 20.1, 10.2, 20.2)
        assertEquals(next, ShapeSnap.snap(prev, next, listOf(doubleArrayOf(11.0, 21.0, 11.1, 21.1)), vp, 10f))
        assertEquals(next, ShapeSnap.snap(prev, next, emptyList(), vp, 10f))
        assertEquals(next, ShapeSnap.snap(prev, next, listOf(doubleArrayOf(Double.NaN, 1.0, 2.0, 3.0)), vp, 10f))
        assertEquals(next, ShapeSnap.snap(prev, next, listOf(doubleArrayOf(10.0, 20.0, 10.1, 20.1)), vp, 0f))
    }

    @Test fun snapNeverMakesAnInvalidShape() {
        // the other box's north edge lies just BELOW this rect's south edge's partner: snapping would invert the span
        val other = doubleArrayOf(0.0, 0.0, 10.1002, 1.0)
        val prev = r(10.1, 20.1, 10.2, 20.2); val next = r(10.1, 20.1, 10.1003, 20.2)
        val out = ShapeSnap.snap(prev, next, listOf(other), vp, 20f)
        assertNotNull(ShapeEdit.box(out))
    }

    @Test fun aMovedCircleSnapsItsCentre() {
        val other = doubleArrayOf(10.0, 20.0, 10.05, 20.05)
        val prev = DrawnShape.Circle(10.2, 20.2, 500.0)
        val next = DrawnShape.Circle(10.0501, 20.0502, 500.0)
        val out = ShapeSnap.snap(prev, next, listOf(other), vp, 10f) as DrawnShape.Circle
        assertEquals(10.05, out.lat, 1e-9); assertEquals(20.05, out.lon, 1e-9); assertEquals(500.0, out.radiusM, 0.0)
        val grown = DrawnShape.Circle(10.2, 20.2, 900.0)
        assertEquals(grown, ShapeSnap.snap(prev, grown, listOf(other), vp, 10f)) // a resize is not snapped
    }

    // --- overlap ---
    @Test fun overlapIgnoresTouchingEdgesAndTheZoneBeingEdited() {
        val box = doubleArrayOf(10.0, 20.0, 11.0, 21.0)
        assertTrue(ZoneOverlap.intersects(box, doubleArrayOf(10.5, 20.5, 12.0, 22.0)))
        assertFalse(ZoneOverlap.intersects(box, doubleArrayOf(11.0, 20.0, 12.0, 21.0))) // touching
        assertFalse(ZoneOverlap.intersects(box, doubleArrayOf(Double.NaN, 0.0, 1.0, 1.0)))
        assertEquals(0.25, ZoneOverlap.fraction(box, doubleArrayOf(10.5, 20.5, 12.0, 22.0)), 1e-9)
        val pause = listOf(MapItem("p1", ItemType.PAUSE, doubleArrayOf(10.5, 20.5, 12.0, 22.0)), MapItem("p2", ItemType.PAUSE, box.copyOf()), MapItem("p3", ItemType.PAUSE, doubleArrayOf(50.0, 50.0, 51.0, 51.0)))
        val o = ZoneOverlap.of(box, pause, emptyList(), ignoreId = "p2")
        assertEquals(listOf("p1"), o.pauseIds)
        assertEquals("This overlaps another pause zone. Both stay in force.", ZoneOverlap.pauseNote(o))
        assertEquals("Part of this area is a pause zone: place cards stay off there.", ZoneOverlap.seedNote(o))
        assertNull(ZoneOverlap.seedNote(Overlap(emptyList(), listOf("s"))))
        assertNull(ZoneOverlap.pauseNote(Overlap(emptyList(), emptyList())))
        assertEquals(Overlap(emptyList(), emptyList()), ZoneOverlap.of(null, pause, emptyList()))
    }

    // --- coordinates ---
    @Test fun coordinateFieldsAcceptGoodNumbersAndRefuseBadOnes() {
        val ok = CoordinateFields.parseRect("17.2443", "78,6708", "17.2643", " 78.6908 ") as CoordResult.Ok
        assertEquals(r(17.2443, 78.6708, 17.2643, 78.6908), ok.shape)
        for ((s, w, n, e) in listOf(
            listOf("", "1", "2", "3"), listOf("NaN", "1", "2", "3"), listOf("Infinity", "1", "2", "3"), listOf("1e2", "1", "2", "3"), listOf("abc", "1", "2", "3"),
            listOf("2", "1", "1", "3"), // south above north
            listOf("1", "3", "2", "1"), // west past east
            listOf("1", "170", "2", "190"), listOf("-90", "1", "2", "3"), listOf("1", "2", "86", "3"),
            listOf("1", "2", "1.0001", "2.0001"), // too small
            listOf("1.1234567", "2", "3", "4"), // more than 6 decimals
        )) assertTrue("$s $w $n $e", CoordinateFields.parseRect(s, w, n, e) is CoordResult.Error)
        assertTrue(CoordinateFields.parseCircle("10", "20", "500") is CoordResult.Ok)
        for ((la, lo, rad) in listOf(listOf("10", "20", "10"), listOf("10", "20", "9999999"), listOf("99", "20", "500"), listOf("10", "999", "500"), listOf("x", "20", "500"))) assertTrue(CoordinateFields.parseCircle(la, lo, rad) is CoordResult.Error)
    }

    @Test fun coordinateTextRoundTrips() {
        val t = CoordinateFields.textOf(a)
        assertEquals(a, (CoordinateFields.parseRect(t.a, t.b, t.c, t.d) as CoordResult.Ok).shape)
        val c = DrawnShape.Circle(10.5, 20.5, 750.0)
        val tc = CoordinateFields.textOf(c)
        assertEquals(c, (CoordinateFields.parseCircle(tc.a, tc.b, tc.c) as CoordResult.Ok).shape)
    }

    // --- double tap and Also seed ---
    @Test fun doubleTapNeedsTimeAndPlace() {
        assertTrue(DoubleTap.isDouble(1_000, 100f, 100f, 1_200, 110f, 95f, 24f))
        assertFalse(DoubleTap.isDouble(1_000, 100f, 100f, 1_400, 100f, 100f, 24f)) // too slow
        assertFalse(DoubleTap.isDouble(1_000, 100f, 100f, 1_200, 300f, 100f, 24f)) // too far
        assertFalse(DoubleTap.isDouble(null, 0f, 0f, 1_200, 0f, 0f, 24f))
        assertFalse(DoubleTap.isDouble(1_000, 100f, 100f, 1_000, 100f, 100f, 24f)) // same instant is not a second tap
        assertFalse(DoubleTap.isDouble(1_000, 100f, 100f, 1_200, Float.NaN, 100f, 24f))
    }

    @Test fun alsoSeedMakesARectFromTheBoxOnly() {
        assertEquals(r(10.0, 20.0, 11.0, 21.0), AlsoSeed.draftOf(doubleArrayOf(10.0, 20.0, 11.0, 21.0)))
        assertNull(AlsoSeed.draftOf(null)); assertNull(AlsoSeed.draftOf(doubleArrayOf(1.0, 2.0))); assertNull(AlsoSeed.draftOf(doubleArrayOf(2.0, 2.0, 1.0, 3.0)))
    }

    // --- show cells ---
    @Test fun coverageColoursEachCellByStatus() {
        val box = GeoBox(17.30, 78.40, 17.42, 78.55)
        val keys = Geohash5.cellsCovering(box)
        assertTrue(keys.size >= 4)
        val st = mapOf(keys[0] to CELL_OK, keys[1] to CELL_PARTIAL, keys[2] to "WEIRD")
        val cov = SeedCoverage.of(box, st)!!
        assertEquals(keys.size, cov.cells.size)
        assertEquals(1, cov.ok); assertEquals(1, cov.partial); assertEquals(keys.size - 2, cov.missing) // an unknown status counts as not fetched
        assertEquals(CellState.OK, cov.cells.first { it.key == keys[0] }.state)
        assertTrue(cov.summary.contains("1 ready, 1 partial"))
        assertNull(SeedCoverage.of(GeoBox(5.0, 5.0, 1.0, 1.0), emptyMap()))
        assertNull(SeedCoverage.of(GeoBox(-80.0, -170.0, 80.0, 170.0), emptyMap())) // far above the 4,000 cell cap
    }

    @Test fun wordsCarryNoNameOrCoordinate() {
        assertEquals(EditWords.UNDO_NONE, EditWords.undoLabel(DraftHistory()))
        assertEquals(EditWords.UNDO, EditWords.undoLabel(DraftHistory().record(a, 0)))
    }
}
