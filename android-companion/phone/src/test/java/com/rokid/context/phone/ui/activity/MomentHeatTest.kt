package com.rokid.context.phone.ui.activity

import com.rokid.context.memory.SceneMember
import com.rokid.context.memory.db.LocatedMoment
import com.rokid.context.memory.db.SceneLocatedMoment
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure part of the Activity map view: which Moments weigh, the cells a zoom makes, the tap, the notice and the rows. */
class MomentHeatTest {
    private fun m(id: String, lat: Double, lon: Double, at: Long) = LocatedMoment(id, lat, lon, at)
    private fun p(id: String, lat: Double, lon: Double, at: Long = 0) = HeatPoint(id, lat, lon, at)

    @Test fun noPointsNoCells() {
        assertTrue(MomentHeat.cells(emptyList(), 1000.0).isEmpty())
        assertTrue(MomentHeat.cells(listOf(p("a", 1.0, 1.0)), 0.0).isEmpty())
        assertTrue(MomentHeat.cells(listOf(p("a", Double.NaN, 1.0)), 1000.0).isEmpty())
    }

    @Test fun onePointIsOneStrongCellWithItsMoment() {
        val c = MomentHeat.cells(listOf(p("a", 17.38, 78.48)), 10_000.0).single()
        assertEquals(1, c.count); assertEquals(1f, c.level, 0f); assertEquals(listOf("a"), c.momentIds)
    }

    @Test fun nearbyPointsShareACellAndTheBusierCellIsStronger() {
        val pts = listOf(p("a", 17.3800, 78.4800, 1), p("b", 17.3801, 78.4801, 3), p("c", 17.3802, 78.4802, 2), p("far", 18.5, 79.5, 9))
        val cells = MomentHeat.cells(pts, 20_000.0)
        assertEquals(2, cells.size)
        assertEquals(3, cells[0].count); assertEquals(1f, cells[0].level, 0f); assertTrue(cells[1].level in 0.15f..1f && cells[1].level < 1f)
        assertEquals("newest first", listOf("b", "c", "a"), cells[0].momentIds)
    }

    @Test fun zoomingOutMergesCellsAndZoomingInSplitsThem() {
        val pts = listOf(p("a", 17.38, 78.48), p("b", 17.40, 78.50))
        assertEquals(2, MomentHeat.cells(pts, 20_000.0).size)
        assertEquals(1, MomentHeat.cells(pts, 200.0).size)
        assertEquals(2, MomentHeat.cells(pts, 200.0).single().count)
    }

    @Test fun theDateRangeKeepsOnlyMomentsInsideIt() {
        val ms = listOf(m("a", 1.0, 1.0, 100), m("b", 1.0, 1.0, 200), m("c", 1.0, 1.0, 300))
        assertEquals(listOf("b"), MomentHeat.points(Granularity.MOMENTS, ms, emptyList(), 150L..250L).map { it.momentId })
        assertEquals(3, MomentHeat.points(Granularity.MOMENTS, ms, emptyList(), null).size)
        assertEquals("both ends included", listOf("a", "b", "c"), MomentHeat.points(Granularity.MOMENTS, ms, emptyList(), 100L..300L).map { it.momentId })
    }

    @Test fun scenesWeighOnePerSceneAtTheirFirstLocatedMoment() {
        val moments = listOf(m("a", 1.0, 1.0, 1), m("b", 2.0, 2.0, 2), m("c", 3.0, 3.0, 3))
        val scenes = listOf(SceneLocatedMoment("s1", "a", 1.0, 1.0, 1), SceneLocatedMoment("s1", "b", 2.0, 2.0, 2), SceneLocatedMoment("s2", "c", 3.0, 3.0, 3))
        assertEquals("one weight per Scene, at its first located Moment", listOf("a", "c"), MomentHeat.points(Granularity.SCENES, moments, scenes, null).map { it.momentId })
        assertEquals("Moments weigh every Moment", 3, MomentHeat.points(Granularity.MOMENTS, moments, scenes, null).size)
        assertEquals("Photos use the Moment's location", 3, MomentHeat.points(Granularity.PHOTOS, moments, scenes, null).size)
        assertTrue("a Scene without a located Moment has no weight", MomentHeat.points(Granularity.SCENES, moments, emptyList(), null).isEmpty())
    }

    @Test fun aTapOpensTheNearestCellWithinReachOnly() {
        val v = MapViewport(17.38, 78.48, 20_000.0, 400f, 400f)
        val cells = MomentHeat.cells(listOf(p("a", 17.38, 78.48)), v.ppd)
        val at = v.toPx(17.38, 78.48)
        assertNotNull(MomentHeat.cellAt(cells, v, at.x + 10f, at.y, 48f))
        assertNull(MomentHeat.cellAt(cells, v, at.x + 100f, at.y, 48f))
        assertNull(MomentHeat.cellAt(cells, v, Float.NaN, 0f, 48f))
    }

    @Test fun fitHoldsEveryPointAndFallsBackWithNone() {
        val v = MomentHeat.fit(listOf(p("a", 17.0, 78.0), p("b", 18.0, 79.0)), 400f, 400f)
        val a = v.toPx(17.0, 78.0); val b = v.toPx(18.0, 79.0)
        assertTrue(a.x in 0f..400f && a.y in 0f..400f && b.x in 0f..400f && b.y in 0f..400f)
        assertEquals(MapViewport.default(400f, 400f), MomentHeat.fit(emptyList(), 400f, 400f))
    }

    @Test fun theNoticeFollowsTheKeyAndTheStreetMapSwitch() {
        assertEquals(OnlineMapWords.UNAVAILABLE, MomentHeat.tileNotice(keyAvailable = false, streetMapOn = false))
        assertEquals(OnlineMapWords.UNAVAILABLE, MomentHeat.tileNotice(keyAvailable = false, streetMapOn = true))
        assertEquals(MomentHeat.NEEDS_CONSENT, MomentHeat.tileNotice(keyAvailable = true, streetMapOn = false))
        assertNull(MomentHeat.tileNotice(keyAvailable = true, streetMapOn = true))
        assertTrue(OnlineMapWords.UNAVAILABLE.startsWith("Add a map key in Settings"))
    }

    @Test fun aRowShowsTheCaptionElseThePhotoElseTheMark() {
        val clock = { _: Long -> "09:00" }
        assertEquals("memory:m1", MomentHeat.row(SceneMember("x", 1, listOf("m1"), listOf("f1"), emptyList()), "Lunch", "m1", clock).second)
        assertEquals("frame:f1", MomentHeat.row(SceneMember("x", 1, emptyList(), listOf("f1"), emptyList()), null, null, clock).second)
        assertEquals("mark:k1", MomentHeat.row(SceneMember("x", 1, emptyList(), emptyList(), listOf("k1")), null, null, clock).second)
        assertNull(MomentHeat.row(SceneMember("x", 1, emptyList(), emptyList(), emptyList()), null, null, clock).second)
    }

    @Test fun theWordsAreTheOwnersAndTheModeDefaultsToList() {
        assertEquals("No saved locations yet. Turn on Save location with Moments in Settings.", MomentHeat.EMPTY)
        assertEquals(ActivityViewMode.LIST, ActivityViewMode.DEFAULT)
        assertEquals(ActivityViewMode.LIST, ActivityViewMode.parse(null)); assertEquals(ActivityViewMode.LIST, ActivityViewMode.parse("SIDEWAYS")); assertEquals(ActivityViewMode.MAP, ActivityViewMode.parse("MAP"))
    }

    @Test fun centreOnMeUsesOnlyAFreshZoneGuardFix() {
        val fresh = com.rokid.context.core.model.zone.ZoneFix(1_000L, 17.38, 78.48, 10f)
        assertEquals(17.38, MomentHeat.centreOn(fresh, 2_000L)!!.lat, 0.0)
        assertNull(MomentHeat.centreOn(null, 2_000L))
        assertNull("a stale fix is not used", MomentHeat.centreOn(fresh, 1_000L + com.rokid.context.core.model.zone.ZoneEvaluator.STALE_MS + 1))
        assertNull(MomentHeat.centreOn(com.rokid.context.core.model.zone.ZoneFix(1_000L, Double.NaN, 78.48, 10f), 2_000L))
    }
}
