package com.rokid.context.core.hud.render

import com.rokid.context.core.model.PlaceSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B2: the placement math is pure. Display sizes: the glasses (240x320 dp as read, 320 wide when landscape) and smaller ones. */
class PlaceCardLayoutTest {
    private val sizes = listOf(320f, 240f, 200f, 120f, 80f, 40f)

    @Test fun centreIsTheLayoutBeforeB2() {
        for (w in sizes) assertEquals(PlaceCardBox(PlaceSide.CENTRE, null, 0f), PlaceCardLayout.compute(PlaceSide.CENTRE, w))
    }

    @Test fun leftAndRightOn240And320() {
        assertEquals(PlaceCardBox(PlaceSide.LEFT, 168f, 24f), PlaceCardLayout.compute(PlaceSide.LEFT, 240f))
        assertEquals(PlaceCardBox(PlaceSide.RIGHT, 168f, 24f), PlaceCardLayout.compute(PlaceSide.RIGHT, 240f))
        assertEquals(PlaceCardBox(PlaceSide.LEFT, 224f, 24f), PlaceCardLayout.compute(PlaceSide.LEFT, 320f))
    }

    @Test fun aSideCardIsAlwaysInsideTheDisplayWithItsMargins() {
        for (side in listOf(PlaceSide.LEFT, PlaceSide.RIGHT)) for (w in sizes) {
            val b = PlaceCardLayout.compute(side, w)
            val width = b.widthDp!!
            assertTrue(width > 0f, "width at $w"); assertTrue(b.insetDp >= 0f)
            assertTrue(b.insetDp + width + b.insetDp <= w + 0.001f, "no clipping at $w") // the card plus the same margin on the far side still fits
            assertEquals(side, b.side)
        }
    }

    @Test fun aSmallDisplayShrinksTheCardAndTheMargin() {
        assertEquals(PlaceCardBox(PlaceSide.RIGHT, 72f, 24f), PlaceCardLayout.compute(PlaceSide.RIGHT, 120f))
        assertEquals(PlaceCardBox(PlaceSide.LEFT, 20f, 10f), PlaceCardLayout.compute(PlaceSide.LEFT, 40f))
    }

    @Test fun sideSourceDropsTheFetchedDate() {
        assertEquals("Wikipedia", PlaceCardLayout.shortSource("Wikipedia \u00b7 fetched 12 Nov"))
        assertEquals("Suggestion \u00b7 Wikipedia", PlaceCardLayout.shortSource("Suggestion \u00b7 Wikipedia \u00b7 fetched 12 Nov"))
        assertEquals("nearby \u00b7 not checked", PlaceCardLayout.shortSource("nearby \u00b7 not checked"))
        assertEquals("fetched 1 Nov", PlaceCardLayout.shortSource("fetched 1 Nov"))
    }

    @Test fun anUnusableDisplayFallsBackToCentre() {
        for (w in listOf(0f, -5f, Float.NaN, Float.POSITIVE_INFINITY)) assertNull(PlaceCardLayout.compute(PlaceSide.LEFT, w).widthDp)
    }
}
