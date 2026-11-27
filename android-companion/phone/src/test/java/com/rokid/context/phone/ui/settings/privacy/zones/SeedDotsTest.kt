package com.rokid.context.phone.ui.settings.privacy.zones

import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlaceDot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seeded places as dots: when to draw, which colour, the cap, the label and the tap. Pure; the drawing itself is on the device check. */
class SeedDotsTest {
    private val v = MapViewport(27.3, 88.6, 20_000.0, 1000f, 1600f) // about 0.05 x 0.08 degrees in view
    private fun area(id: String, s: Double, w: Double, n: Double, e: Double, colour: Int) = SeedOverlay(id, doubleArrayOf(s, w, n, e), AreaLook.DONE, id, colour = colour)

    @Test fun theViewIsReadOnlyWhenFewerThan400CellsAreInIt() {
        val near = SeedDots.visibleBox(v, 1000f, 1600f)!!
        assertTrue(SeedDots.closeEnough(near))
        val far = SeedDots.visibleBox(MapViewport(27.3, 88.6, 10.0, 1000f, 1600f), 1000f, 1600f)!!
        assertFalse(SeedDots.closeEnough(far))
        // the boundary is exactly 400 cells: below it the places are read, at or above it they are not
        val counts = (190..210).map { h -> GeoBox(10.0, 70.0, 10.0 + h * Geohash5.LAT_STEP * 0.99, 70.0 + 0.5 * Geohash5.LON_STEP).let { b -> SeedEstimate.cells(b) to SeedDots.closeEnough(b) } }
        assertTrue(counts.toString(), counts.any { it.second } && counts.any { !it.second }) // both sides of the line occur in the range
        assertTrue(counts.all { (n, ok) -> ok == (n < SeedDots.MAX_VISIBLE_CELLS) })
        assertFalse(SeedDots.closeEnough(null))
        assertNull(SeedDots.visibleBox(null, 100f, 100f)); assertNull(SeedDots.visibleBox(v, 0f, 0f))
    }

    @Test fun aDotTakesTheColourOfTheAreaThatHoldsItAndOtherPlacesAreGrey() {
        val a = area("a", 27.0, 88.0, 27.2, 88.2, colour = 1)
        val b = area("b", 27.1, 88.1, 27.3, 88.3, colour = 2) // overlaps a
        val dots = listOf(PlaceDot("in-a", 27.05, 88.05, "monument"), PlaceDot("in-both", 27.15, 88.15, "park"), PlaceDot("in-b", 27.25, 88.25, "temple"), PlaceDot("out", 28.0, 89.0, "hill"))
        val p = SeedDots.paint(dots, listOf(a, b)).associateBy { it.id }
        assertEquals(SeedPalette.argb(1), p["in-a"]!!.argb)
        assertEquals(SeedPalette.argb(1), p.getValue("in-both").argb) // overlap: the first area in the list order
        assertEquals(SeedPalette.argb(2), p["in-b"]!!.argb)
        assertEquals(SeedDots.GREY, p["out"]!!.argb); assertFalse(p["out"]!!.inArea)
        // an edited (smaller) area leaves the places it used to hold grey, still shown
        assertEquals(SeedDots.GREY, SeedDots.paint(dots, listOf(area("a", 27.0, 88.0, 27.01, 88.01, 1))).first { it.id == "in-a" }.argb)
        // the grey is not a palette colour and not the pin purple
        assertFalse(SeedPalette.colours.any { it.argb == SeedDots.GREY }); assertTrue(SeedDots.GREY != 0xFF7B3FC4.toInt())
    }

    @Test fun neverMoreThanTheCapIsPainted() {
        val many = (0 until SeedDots.CAP + 300).map { PlaceDot("p$it", 27.0 + it * 1e-5, 88.0, "x") }
        assertEquals(SeedDots.CAP, SeedDots.paint(many, emptyList()).size)
        assertEquals(1_500, SeedDots.CAP)
    }

    @Test fun theLabelHasNameKindAndOnlyTheFirstLineOfTheIntro() {
        assertEquals("Old fort · fort\nBuilt in 1590.", SeedDots.label("Old fort", "FORT", "\n  Built in 1590.\nMore text.\nEven more."))
        assertEquals("Place · hill", SeedDots.label("  ", "hill", ""))
        val long = SeedDots.label("N", "k", "x".repeat(500)).substringAfter("\n")
        assertEquals(120, long.length)
        assertEquals("Water tower · water tower", SeedDots.label("Water tower", "WATER_TOWER", "").substringBefore("\n").let { it.replace("Water tower · water tower", it) })
    }

    @Test fun aTapPicksTheNearestDotWithinTheTouchRadiusOnly() {
        val painted = SeedDots.paint(listOf(PlaceDot("a", 27.30, 88.60, "x"), PlaceDot("b", 27.3005, 88.6005, "x")), emptyList())
        val pa = v.toPx(27.30, 88.60)
        assertEquals("a", SeedDots.hit(v, painted, pa.x + 1f, pa.y + 1f, 40f))
        assertNull(SeedDots.hit(v, painted, pa.x + 500f, pa.y, 40f))
        assertNull(SeedDots.hit(v, emptyList(), pa.x, pa.y, 40f))
    }
}
