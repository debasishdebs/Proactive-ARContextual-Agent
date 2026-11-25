package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

private class FakeDensity(var isOnNow: Boolean = false, var pts: List<DensityPoint> = emptyList(), var range: Pair<Long, Long>? = null) : MomentDensityControl {
    var reads = 0; var rangeReads = 0; var last: List<Any>? = null
    override fun isOn() = isOnNow
    override fun setOn(on: Boolean) { isOnNow = on }
    override suspend fun points(south: Double, west: Double, north: Double, east: Double, fromMs: Long, toMs: Long, limit: Int): List<DensityPoint> {
        reads++; last = listOf(south, west, north, east, fromMs, toMs, limit)
        return pts.filter { it.lat in south..north && it.lon in west..east && it.atMs in fromMs..toMs }
    }
    override suspend fun range(): Pair<Long, Long>? { rangeReads++; return range }
}

class MapDensityTest {
    @get:Rule val main = MainDispatcherRule()
    private val vms = mutableListOf<MomentDensityViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }
    private val vp = MapViewport(10.0, 20.0, 2_000.0, 400f, 400f)
    private val day = MomentDensity.DAY_MS
    private fun vm(c: FakeDensity, now: Long = 100 * day) = MomentDensityViewModel(c, work = main.dispatcher, clock = { now }, zone = { ZoneId.of("UTC") }).also { vms += it }

    @Test fun visibleBoxIsTheViewAndRefusesBadViews() {
        val b = MomentDensity.visibleBox(vp)!!
        assertTrue(b[0] < 10.0 && b[2] > 10.0 && b[1] < 20.0 && b[3] > 20.0)
        assertNull(MomentDensity.visibleBox(vp.copy(width = 0f)))
        assertNull(MomentDensity.visibleBox(vp.copy(ppd = Double.NaN)))
        assertTrue(MomentDensity.visibleBox(MapViewport(0.0, 179.99, 20.0, 400f, 400f))!![3] <= 180.0) // clamped to the map, never wraps
    }

    @Test fun blobsGroupByScreenCellAndScaleByLogCount() {
        val pts = List(100) { DensityPoint(10.0, 20.0, 1) } + List(10) { DensityPoint(10.05, 20.05, 1) } + listOf(DensityPoint(9.95, 19.95, 1), DensityPoint(Double.NaN, 1.0, 1))
        val bl = MomentDensity.blobs(pts, vp)
        assertEquals(3, bl.size)
        assertEquals(100, bl[0].count); assertEquals(1f, bl[0].level, 1e-6f)
        assertTrue(bl[1].level < 1f && bl[1].level > bl[2].level - 1e-6f)
        assertTrue(bl.all { it.level in 0.15f..1f })
        assertEquals(emptyList<DensityBlob>(), MomentDensity.blobs(emptyList(), vp))
        assertEquals(1, MomentDensity.blobs(pts, vp, maxBlobs = 1).size)
        assertEquals(1f, MomentDensity.blobs(listOf(DensityPoint(10.0, 20.0, 1)), vp).single().level, 0f)
    }

    @Test fun colourRampIsPurpleToOrangeAndClamped() {
        assertEquals(0xFF7B3FC4, MomentDensity.colorOf(0f)); assertEquals(0xFFF58C1E, MomentDensity.colorOf(1f))
        assertEquals(MomentDensity.colorOf(1f), MomentDensity.colorOf(5f)); assertEquals(MomentDensity.colorOf(0f), MomentDensity.colorOf(Float.NaN))
    }

    @Test fun windowEndsAtTheNewestLocationAndIsClamped() {
        val range = 10 * day to 90 * day
        assertEquals(10 * day to 90 * day, MomentDensity.window(DateWindow.ALL, range, 100 * day))
        assertEquals(83 * day to 90 * day, MomentDensity.window(DateWindow.LAST_7_DAYS, range, 100 * day))
        assertEquals(60 * day to 90 * day, MomentDensity.window(DateWindow.LAST_30_DAYS, range, 100 * day))
        assertEquals(10 * day to 12 * day, MomentDensity.window(DateWindow.LAST_30_DAYS, 10 * day to 12 * day, 100 * day))
        assertNull(MomentDensity.window(DateWindow.ALL, null, 1))
    }

    @Test fun summaryHasCountsAndDatesOnly() {
        val z = ZoneId.of("UTC")
        fun ms(d: Int) = java.time.LocalDate.of(2026, 11, d).atStartOfDay(z).toInstant().toEpochMilli()
        assertEquals("1,153 Moments - 19 Nov 2026 to 24 Nov 2026", MomentDensity.summary(1153, ms(19), ms(24), z))
        assertEquals("1 Moment", MomentDensity.summary(1, null, null, z))
    }

    @Test fun offMeansNoDatabaseReadAndNoBlobsHeld() {
        val c = FakeDensity(isOnNow = false, pts = listOf(DensityPoint(10.0, 20.0, 5 * day)), range = 1L to 9 * day)
        val v = vm(c)
        assertFalse(v.state.value.on)
        v.refresh(vp); v.setWindow(DateWindow.LAST_7_DAYS)
        assertEquals(0, c.reads); assertEquals(0, c.rangeReads); assertTrue(v.state.value.blobs.isEmpty())
    }

    @Test fun readErrorOnTheSwitchMeansOff() {
        val c = object : MomentDensityControl {
            override fun isOn(): Boolean = throw IllegalStateException("x")
            override fun setOn(on: Boolean) {}
            override suspend fun points(south: Double, west: Double, north: Double, east: Double, fromMs: Long, toMs: Long, limit: Int) = emptyList<DensityPoint>()
            override suspend fun range(): Pair<Long, Long>? = null
        }
        assertFalse(vm2(c).state.value.on)
    }
    private fun vm2(c: MomentDensityControl) = MomentDensityViewModel(c, work = main.dispatcher).also { vms += it }

    @Test fun onReadsTheViewAndWindowAndSummarises() {
        val c = FakeDensity(isOnNow = true, pts = List(3) { DensityPoint(10.0, 20.0, (50 + it) * day) } + DensityPoint(40.0, 40.0, 50 * day), range = 1L to 60 * day)
        val v = vm(c)
        v.refresh(vp)
        val s = v.state.value
        assertEquals(3, s.count); assertEquals(1, s.blobs.size); assertEquals(3, s.blobs[0].count); assertTrue(s.hasAny)
        assertTrue(s.summary.startsWith("3 Moments - ")); assertEquals(1, c.reads)
        assertEquals(MomentDensity.MAX_POINTS, c.last!![6])
    }

    @Test fun noLocationsAtAllAndNoneInViewSayDifferentThings() {
        val none = FakeDensity(isOnNow = true, range = null)
        val v = vm(none); v.refresh(vp)
        assertEquals(MomentDensity.NONE_TEXT, v.state.value.summary); assertFalse(v.state.value.hasAny); assertEquals(0, none.reads)
        val elsewhere = FakeDensity(isOnNow = true, pts = listOf(DensityPoint(40.0, 40.0, 5)), range = 1L to 10L)
        val w = vm(elsewhere); w.refresh(vp)
        assertEquals(MomentDensity.NONE_IN_VIEW, w.state.value.summary); assertTrue(w.state.value.blobs.isEmpty())
    }

    @Test fun switchingOffClearsEverythingAndStopsReads() {
        val c = FakeDensity(isOnNow = true, pts = listOf(DensityPoint(10.0, 20.0, 5 * day)), range = 1L to 9 * day)
        val v = vm(c); v.refresh(vp)
        assertTrue(v.state.value.blobs.isNotEmpty())
        v.setOn(false)
        assertFalse(c.isOnNow); assertTrue(v.state.value.blobs.isEmpty()); assertEquals(0, v.state.value.count)
        val reads = c.reads
        v.refresh(vp); assertEquals(reads, c.reads)
        v.setOn(true); assertTrue(c.isOnNow); assertTrue(v.state.value.blobs.isNotEmpty()) // turning it on re-reads for the last view
    }

    @Test fun clearDropsBlobsAndTheNextRefreshAsksAgain() {
        val c = FakeDensity(isOnNow = true, pts = listOf(DensityPoint(10.0, 20.0, 5 * day)), range = 1L to 9 * day)
        val v = vm(c); v.refresh(vp); val first = c.reads
        v.clear(); assertTrue(v.state.value.blobs.isEmpty())
        c.pts = emptyList() // a Forget happened
        v.refresh(vp)
        assertTrue(c.reads > first); assertTrue(v.state.value.blobs.isEmpty())
    }
}
