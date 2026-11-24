package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOverviewTest {
    private val all = MapLayer.values().toSet()
    // country zoom: 30 px per degree, so a 300 m zone is a speck
    private val v = MapViewport(27.3, 88.6, 30.0, 400f, 800f)
    private fun pt(id: String, t: ItemType, lat: Double, lon: Double, alert: Boolean = false) = MapItem(id, t, doubleArrayOf(lat, lon, lat + 0.003, lon + 0.003), alert)

    @Test fun nearbySpecksMergeAndPauseWinsTheType() {
        val items = listOf(pt("a", ItemType.PIN, 27.30, 88.60), pt("b", ItemType.SEED, 27.301, 88.601), pt("c", ItemType.PAUSE, 27.302, 88.602), pt("far", ItemType.PIN, 30.0, 85.0))
        val c = MapOverview.clusters(items, v, 16f, all)
        assertEquals(2, c.size)
        val merged = c.first { it.count == 3 }
        assertEquals(ItemType.PAUSE, merged.type); assertEquals(listOf("a", "b", "c"), merged.ids)
        assertTrue(c.first { it.count == 1 }.isSingle)
    }

    @Test fun seedBeatsPinWhenNoPause() {
        val c = MapOverview.clusters(listOf(pt("a", ItemType.PIN, 27.30, 88.60), pt("b", ItemType.SEED, 27.301, 88.601)), v, 16f, all)
        assertEquals(ItemType.SEED, c.single().type)
    }

    @Test fun bigShapesAreNotChipsAndHiddenLayersAreDropped() {
        val big = MapItem("big", ItemType.SEED, doubleArrayOf(26.0, 87.0, 28.0, 90.0))
        assertTrue(MapOverview.clusters(listOf(big), v, 16f, all).isEmpty())
        val c = MapOverview.clusters(listOf(pt("a", ItemType.PIN, 27.30, 88.60)), v, 16f, all - MapLayer.PINS)
        assertTrue(c.isEmpty())
    }

    @Test fun groupingDoesNotChangeWhenYouPan() {
        val items = (0 until 20).map { pt("i$it", ItemType.PIN, 27.0 + it * 0.02, 88.0 + (it % 5) * 0.03) }
        val a = MapOverview.clusters(items, v, 16f, all).map { it.ids }.toSet()
        val panned = MapViewport(27.3, 88.6, 30.0, 400f, 800f).panned(0f, 0f)
        assertEquals(a, MapOverview.clusters(items, panned, 16f, all).map { it.ids }.toSet())
        // a pan of exactly one cell keeps every group intact (same members), only their screen place moves
        val shifted = MapOverview.clusters(items, v.panned(MapOverview.CLUSTER_CELL_PX, 0f), 16f, all).map { it.ids }.toSet()
        val inView = a.filter { g -> g.all { id -> shifted.any { id in it } } }
        assertTrue(inView.all { it in shifted })
    }

    @Test fun hostileInputIsSafe() {
        val bad = listOf(MapItem("n", ItemType.PIN, doubleArrayOf(Double.NaN, 0.0, 1.0, 1.0)), MapItem("s", ItemType.PIN, doubleArrayOf(1.0)))
        assertTrue(MapOverview.clusters(bad, v, 16f, all).isEmpty())
        assertTrue(MapOverview.clusters(listOf(pt("a", ItemType.PIN, 27.3, 88.6)), MapViewport(0.0, 0.0, Double.NaN, 0f, 0f), 16f, all).isEmpty())
        assertTrue(MapOverview.clusters(emptyList(), v, 16f, all).isEmpty())
    }

    @Test fun countsRowAndSummary() {
        assertEquals("2 pause · 4 seed · 17 pins", MapOverview.counts(2, 4, 17))
        assertEquals("0 pause · 1 seed · 1 pin", MapOverview.counts(0, 1, 1))
        assertTrue(MapOverview.summary(1, 0, 0, true).contains("you are inside"))
    }

    @Test fun pauseLayerCannotBeHiddenWhileLocked() {
        assertEquals(all - MapLayer.SEED, MapOverview.toggle(all, MapLayer.SEED, true))
        assertEquals(all, MapOverview.toggle(all, MapLayer.PAUSE, true))
        assertEquals(all - MapLayer.PAUSE, MapOverview.toggle(all, MapLayer.PAUSE, false))
        assertEquals(all, MapOverview.toggle(all - MapLayer.PAUSE, MapLayer.PAUSE, true))
        assertTrue(MapLayer.PAUSE in MapOverview.effective(emptySet(), true))
        assertTrue(MapLayer.PAUSE !in MapOverview.effective(emptySet(), false))
    }

    @Test fun everyTypeHasADistinctColourAndPattern() {
        val looks = ItemType.values().map { TypeLooks.of(it) }
        assertEquals(3, looks.map { it.argb }.toSet().size)
        assertNotEquals(TypeLooks.PAUSE.pattern, TypeLooks.SEED.pattern)
        assertNotEquals(TypeLooks.PAUSE.edge, TypeLooks.SEED.edge)
    }
}
