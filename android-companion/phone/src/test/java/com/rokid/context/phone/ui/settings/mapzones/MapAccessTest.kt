package com.rokid.context.phone.ui.settings.mapzones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapAccessTest {
    private val slop = 8f; private val lp = 400L

    // --- touch arbitration ---
    @Test fun aShortDragPansAndIsNeverSwallowedByTheLongPress() {
        assertEquals(TouchDecision.PAN, TouchArbiter.decide(1, false, 20f, slop, 50, lp))
        assertEquals(TouchDecision.PAN, TouchArbiter.decide(1, false, 9f, slop, 399, lp))
    }

    @Test fun aSecondFingerIsAlwaysPanOrPinchNeverADraw() {
        assertEquals(TouchDecision.PAN, TouchArbiter.decide(2, false, 0f, slop, 10, lp))
        assertEquals(TouchDecision.PAN, TouchArbiter.decide(2, false, 0f, slop, 5_000, lp)) // even after the long-press time
        assertEquals(TouchDecision.PAN, TouchArbiter.decide(3, true, 0f, slop, 0, lp))
    }

    @Test fun aLongPressThatMovesLessThanSlopStartsDrawing() {
        assertEquals(TouchDecision.WAIT, TouchArbiter.decide(1, false, 3f, slop, 399, lp))
        assertEquals(TouchDecision.DRAW, TouchArbiter.decide(1, false, 3f, slop, 400, lp))
        assertEquals(TouchDecision.DRAW, TouchArbiter.decide(1, false, 8f, slop, 600, lp)) // exactly the slop is still within it
    }

    @Test fun aLiftBeforeTheLongPressIsATapAndMovingPastSlopPansEvenLate() {
        assertEquals(TouchDecision.TAP, TouchArbiter.decide(1, true, 2f, slop, 100, lp))
        assertEquals(TouchDecision.PAN, TouchArbiter.decide(1, false, 30f, slop, 900, lp))
    }

    // --- TalkBack step actions ---
    @Test fun stepMoveMovesByAQuarterOfTheShapeAndKeepsItsSize() {
        val r = DrawnShape.Rect(10.0, 20.0, 10.4, 20.8)
        val n = ShapeSteps.move(r, StepDir.NORTH) as DrawnShape.Rect
        assertEquals(10.1, n.south, 1e-9); assertEquals(0.4, n.north - n.south, 1e-9)
        val w = ShapeSteps.move(r, StepDir.WEST) as DrawnShape.Rect
        assertEquals(19.8, w.west, 1e-9); assertEquals(0.8, w.east - w.west, 1e-9)
        val c = DrawnShape.Circle(27.0, 88.0, 1_000.0)
        val e = ShapeSteps.move(c, StepDir.EAST) as DrawnShape.Circle
        assertEquals(c.radiusM, e.radiusM, 0.0); assertTrue(e.lon > 88.0)
        assertTrue((ShapeSteps.move(c, StepDir.SOUTH) as DrawnShape.Circle).lat < 27.0)
    }

    @Test fun stepResizeScalesAboutTheCentreAndRespectsTheLimits() {
        val r = DrawnShape.Rect(10.0, 20.0, 10.4, 20.8)
        val g = ShapeSteps.resize(r, true) as DrawnShape.Rect
        assertEquals(0.5, g.north - g.south, 1e-9); assertEquals(10.2, (g.south + g.north) / 2, 1e-9)
        val small = DrawnShape.Rect(10.0, 20.0, 10.0006, 20.0006)
        assertEquals(small, ShapeSteps.resize(small, false)) // would pass the minimum span
        val max = DrawnShape.Circle(27.0, 88.0, ShapeEdit.MAX_RADIUS_M); assertEquals(max, ShapeSteps.resize(max, true))
        val min = DrawnShape.Circle(27.0, 88.0, ShapeEdit.MIN_RADIUS_M); assertEquals(min, ShapeSteps.resize(min, false))
        assertEquals(1_250.0, (ShapeSteps.resize(DrawnShape.Circle(27.0, 88.0, 1_000.0), true) as DrawnShape.Circle).radiusM, 1e-9)
    }

    @Test fun selectNextWalksPauseThenSeedWrapsAndSkipsPinsAndHiddenLayers() {
        val bx = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
        val items = listOf(MapItem("b", ItemType.PAUSE, bx), MapItem("a", ItemType.PAUSE, bx), MapItem("s", ItemType.SEED, bx), MapItem("pin#0", ItemType.PIN, doubleArrayOf(0.0, 0.0, 0.0, 0.0)))
        val all = MapLayer.values().toSet()
        val first = ShapeSteps.nextSelection(items, null, all)!!; assertEquals(Selection(SelectionKind.PAUSE, "a"), first)
        val second = ShapeSteps.nextSelection(items, first, all)!!; assertEquals(Selection(SelectionKind.PAUSE, "b"), second)
        val third = ShapeSteps.nextSelection(items, second, all)!!; assertEquals(Selection(SelectionKind.SEED, "s"), third)
        assertEquals(first, ShapeSteps.nextSelection(items, third, all))
        assertEquals(first, ShapeSteps.nextSelection(items, second, all - MapLayer.SEED)) // seed hidden: wraps from b to a
        assertNull(ShapeSteps.nextSelection(emptyList(), null, all))
    }

    // --- descriptions: no names, no coordinates ---
    @Test fun spokenTextHasCountsSizeAndPlaceButNoNameOrCoordinate() {
        val items = listOf(MapItem("zone-secret-home", ItemType.PAUSE, doubleArrayOf(12.3456, 92.1234, 12.4, 92.2)))
        val shape = DrawnShape.Rect(12.3456, 92.1234, 12.4, 92.2)
        val pos = ShapeSteps.position(items, Selection(SelectionKind.PAUSE, "zone-secret-home"), MapLayer.values().toSet())
        assertEquals("pause zone 1 of 1", pos)
        val text = MapSpeech.describe(MapOverview.summary(1, 0, 0, false), shape, pos)
        assertTrue(text.contains("Drawing a rectangle") && text.contains("Selected pause zone 1 of 1"))
        assertFalse(text.contains("secret") || text.contains("12.3") || text.contains("92.1") || Regex("""\d+\.\d{3,}""").containsMatchIn(text))
        assertEquals(MapOverview.summary(1, 0, 0, false), MapSpeech.describe(MapOverview.summary(1, 0, 0, false), null, null))
    }
}
