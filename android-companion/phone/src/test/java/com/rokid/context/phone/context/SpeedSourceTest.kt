package com.rokid.context.phone.context

import com.rokid.context.core.model.context.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeedSourceTest {
    private val clock = FakeObsClock(el = 1_000_000L)
    private val got = ArrayList<Evidence>()
    private var permitted = true
    private var sinkTaken = 0
    private val calls = ArrayList<Pair<Boolean, Boolean>>() // setUpdates(wanted, quick)

    private fun src() = SpeedSource(
        sinkFor = { id -> assertEquals("phone_speed", id); sinkTaken++; EvidenceSink { got += it } },
        time = clock.time(),
        platform = object : SpeedSource.Platform {
            override fun permitted() = permitted
            override fun setUpdates(wanted: Boolean, quick: Boolean) { calls += wanted to quick }
        },
    )

    private fun started() = src().also { assertTrue(it.start()) }
    private fun speeds() = got.map { it as Evidence.Speed }

    /** About 111.2 km per degree of latitude; the same longitude. */
    private fun lat(km: Double) = km / 111.195

    @Test fun idAvailabilityAndPermission() {
        assertEquals("phone_speed", src().id); assertTrue(src().available())
        permitted = false; assertFalse(src().start()); assertEquals(0, sinkTaken)
    }

    @Test fun reportedSpeedIsPreciseKmh() {
        val s = started(); s.setWanted(true)
        s.onFix(clock.el, 12.0, 77.0, 10f, 10f)
        val e = speeds().single()
        assertEquals(36f, e.kmh, 0.01f); assertTrue(e.precise); assertEquals(clock.wall(), e.atMs)
        assertEquals(1, s.fixes); assertEquals(1, s.events)
    }

    @Test fun derivedSpeedFromTwoApproximateFixes() {
        val s = started(); s.setWanted(true)
        s.onFix(clock.el, 12.0, 77.0, 1000f, null)
        clock.el += 300_000
        s.onFix(clock.el, 12.0 + lat(10.0), 77.0, 1000f, null) // 10 km in 5 min
        val e = speeds().single()
        assertEquals(120f, e.kmh, 2f); assertFalse(e.precise)
    }

    @Test fun belowThreeTimesAccuracyIsNothing() {
        val s = started(); s.setWanted(true)
        s.onFix(clock.el, 12.0, 77.0, 1000f, null)
        clock.el += 300_000
        s.onFix(clock.el, 12.0 + lat(3.0), 77.0, 1000f, null) // 3 km < 3 x (1 + 1) km
        assertTrue(got.isEmpty())
    }

    @Test fun lessThanThirtySecondsIsNothing() {
        val s = started(); s.setWanted(true)
        s.onFix(clock.el, 12.0, 77.0, 10f, null)
        clock.el += 20_000
        s.onFix(clock.el, 12.0 + lat(5.0), 77.0, 10f, null)
        assertTrue(got.isEmpty())
    }

    @Test fun setWantedFalseDropsTheBaseline() {
        val s = started(); s.setWanted(true)
        s.onFix(clock.el, 12.0, 77.0, 100f, null)
        s.setWanted(false); clock.el += 100_000; s.setWanted(true)
        clock.el += 100_000
        s.onFix(clock.el, 12.0 + lat(20.0), 77.0, 100f, null) // would be 180 km/h against the dropped fix
        assertTrue(got.isEmpty())
    }

    @Test fun aStreamOfIntermediateFixesKeepsTheBaselineUntilTheDistancePasses() {
        val s = started(); s.setWanted(true)
        s.onFix(clock.el, 12.0, 77.0, 1000f, null)
        // a car at 90 km/h with a fix every 30 s: 750 m per step, threshold 6 km from the baseline
        for (i in 1..12) {
            clock.el += 30_000
            s.onFix(clock.el, 12.0 + lat(0.75 * i), 77.0, 1000f, null)
        }
        val e = speeds().single() // only the 9th fix (6.75 km) clears 3 x (1 + 1) km; averaged over the whole 270 s
        assertEquals(90f, e.kmh, 3f)
    }

    @Test fun aStaleBaselineIsReplacedNotUsed() {
        val s = started(); s.setWanted(true)
        s.onFix(clock.el, 12.0, 77.0, 100f, null)
        clock.el += 30 * 60_000 // half an hour of nothing, then a fix far away: not a speed
        s.onFix(clock.el, 12.0 + lat(40.0), 77.0, 100f, null)
        assertTrue(got.isEmpty())
        clock.el += 60_000
        s.onFix(clock.el, 12.0 + lat(40.0 + 3.0), 77.0, 100f, null) // 3 km in 1 min against the new baseline: 180 km/h
        assertEquals(180f, speeds().single().kmh, 3f)
    }

    @Test fun aPreciseFixResetsTheBaseline() {
        val s = started(); s.setWanted(true)
        s.onFix(clock.el, 12.0, 77.0, 10f, null)
        clock.el += 60_000
        s.onFix(clock.el, 12.0 + lat(0.1), 77.0, 10f, 1f) // precise
        clock.el += 60_000
        s.onFix(clock.el, 12.0 + lat(1.1), 77.0, 10f, null) // 1 km in 1 min against the precise fix's position
        assertEquals(listOf(true, false), speeds().map { it.precise }); assertEquals(60f, speeds()[1].kmh, 2f)
    }

    @Test fun outOfOrderAndBeforeWantedAreDropped() {
        val s = started()
        s.onFix(clock.el, 12.0, 77.0, 10f, 5f) // not wanted yet
        assertTrue(got.isEmpty())
        s.setWanted(true); clock.el += 60_000
        s.onFix(clock.el, 12.0, 77.0, 10f, 5f)
        s.onFix(clock.el - 30_000, 12.0, 77.0, 10f, 5f) // older than the last
        s.onFix(clock.el - 59_000, 12.0, 77.0, 10f, 5f)
        assertEquals(1, got.size); assertEquals(2, s.stale)
    }

    @Test fun aCachedFixFromBeforeTheRequestIsNotReplayed() {
        val s = started(); clock.el += 10_000; s.setWanted(true)
        s.onFix(clock.el - 5_000, 12.0, 77.0, 10f, 20f)
        assertTrue(got.isEmpty()); assertEquals(1, s.stale)
    }

    @Test fun observationTimeIsTranslatedNotCallbackTime() {
        val s = started(); s.setWanted(true); clock.el += 60_000
        s.onFix(clock.el - 20_000, 12.0, 77.0, 10f, 5f)
        assertEquals(clock.wall() - 20_000, got.single().atMs)
    }

    @Test fun onMsGrowsOnlyWhileWanted() {
        val s = started()
        clock.el += 60_000; assertEquals(0L, s.onMs)
        s.setWanted(true); clock.el += 45_000; assertEquals(45_000L, s.onMs)
        s.setWanted(false); clock.el += 600_000; assertEquals(45_000L, s.onMs)
        s.setWanted(true); clock.el += 5_000; s.setWanted(true); assertEquals(50_000L, s.onMs)
    }

    @Test fun platformIsToldOnlyOnChangesAndStopTurnsItOff() {
        val s = started()
        s.setWanted(true); s.setWanted(true)
        assertEquals(listOf(true to false), calls)
        s.setWanted(false); s.setWanted(false)
        assertEquals(listOf(true to false, false to false), calls)
        s.setWanted(true); s.stop()
        assertEquals(false to false, calls.last())
        s.setWanted(true); assertEquals(false to false, calls.last()) // a stopped source cannot be wanted
    }

    @Test fun aFastReadingAsksForAQuickFollowUp() {
        val s = started(); s.setWanted(true)
        s.onFix(clock.el, 12.0, 77.0, 10f, 10f)  // 36 km/h
        assertEquals(true to true, calls.last())
        clock.el += 15_000
        s.onFix(clock.el, 12.0, 77.0, 10f, 1f)   // slow again
        assertEquals(true to false, calls.last())
    }

    @Test fun noPermissionNoRequest() {
        val s = started(); permitted = false
        s.setWanted(true)
        assertTrue(calls.isEmpty()); assertFalse(s.wanted)
    }

    @Test fun nothingBeforeStart() {
        val s = src(); s.setWanted(true); s.onFix(clock.el, 12.0, 77.0, 10f, 5f)
        assertTrue(got.isEmpty()); assertTrue(calls.isEmpty())
    }

    @Test fun haversineIsAccurate() {
        assertEquals(111_195.0, SpeedSource.haversineM(0.0, 0.0, 1.0, 0.0), 300.0)
        assertTrue(abs(SpeedSource.haversineM(10.0, 20.0, 10.0, 20.0)) < 1e-6)
    }
}
