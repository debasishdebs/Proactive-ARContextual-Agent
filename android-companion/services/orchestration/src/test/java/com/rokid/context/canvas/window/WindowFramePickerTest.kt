package com.rokid.context.canvas.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowFramePickerTest {
    private val from = 1_000_000L
    private val to = from + 1_000_000L

    private fun fr(i: Int, dwell: Boolean = false, nearDup: Boolean = false, tomb: Boolean = false, photo: Boolean = true, thinned: Boolean = false) =
        PickerFrame("f$i", from + i * 1_000L, dwell, nearDup, tomb, photo, thinned)

    private val many = (0 until 400).map { fr(it, dwell = it % 10 == 0) }

    @Test fun sameSeedSameIds() {
        val a = WindowFramePicker.pick(many, from, to, PickMode.EVEN, 50, "s1")
        val b = WindowFramePicker.pick(many.reversed(), from, to, PickMode.EVEN, 50, "s1")
        assertEquals(a.ids, b.ids)
        val c = WindowFramePicker.pick(many, from, to, PickMode.EVEN, 50, "s2")
        assertFalse(a.ids == c.ids)
    }

    @Test fun capsAtFiftyAndAsksNoMore() {
        val r = WindowFramePicker.pick(many, from, to, PickMode.EVEN, 500, "s")
        assertEquals(50, r.ids.size)
        assertEquals(400, r.of)
        assertEquals("picked 50 of 400", r.note)
        assertEquals(r.ids.toSet().size, r.ids.size)
    }

    @Test fun bucketsCoverTheWindow() {
        val sparse = (0 until 1000).map { PickerFrame("g$it", from + it * 1_000L, false, false, false, true) }
        val r = WindowFramePicker.pick(sparse, from, to, PickMode.EVEN, 10, "s")
        val times = r.ids.map { sparse.first { f -> f.frameId == it }.capturedAtMs }
        val bucket = (to - from) / 10
        assertEquals((0 until 10).toList(), times.map { ((it - from) / bucket).toInt() })
    }

    @Test fun excludesTombstonedNearDupPhotoLess() {
        val fs = listOf(fr(1), fr(2, tomb = true), fr(3, nearDup = true), fr(4, photo = false), fr(5, photo = false, thinned = true), fr(6))
        val r = WindowFramePicker.pick(fs, from, to, PickMode.EVEN, 50, "s")
        assertEquals(listOf("f1", "f6"), r.ids)
        assertEquals(1, r.excludedTombstoned); assertEquals(1, r.excludedNoPhoto); assertEquals(1, r.excludedRetention); assertEquals(1, r.excludedNearDup)
        val withDup = WindowFramePicker.pick(fs, from, to, PickMode.EVEN, 50, "s", includeNearDup = true)
        assertTrue("f3" in withDup.ids)
    }

    @Test fun outsideWindowIgnoredHalfOpen() {
        val fs = listOf(PickerFrame("in", from, false, false, false, true), PickerFrame("end", to, false, false, false, true))
        assertEquals(listOf("in"), WindowFramePicker.pick(fs, from, to, PickMode.EVEN, 5, "s").ids)
    }

    @Test fun dwellFirstThenFill() {
        val r = WindowFramePicker.pick(many, from, to, PickMode.DWELL, 50, "s")
        assertEquals(50, r.ids.size)
        val dwellIds = many.filter { it.dwell }.map { it.frameId }.toSet()
        assertEquals(40, r.ids.count { it in dwellIds })
        val few = WindowFramePicker.pick(many, from, to, PickMode.DWELL, 20, "s")
        assertTrue(few.ids.all { it in dwellIds })
    }

    @Test fun allDwellFirstTakesEarliestDwell() {
        val r = WindowFramePicker.pick(many, from, to, PickMode.ALL_DWELL_FIRST, 5, "s")
        assertEquals(listOf("f0", "f10", "f20", "f30", "f40"), r.ids)
    }

    @Test fun defaultSeedIsStableAndPipelineBound() {
        assertEquals(WindowFramePicker.defaultSeed(1, 2, "h"), WindowFramePicker.defaultSeed(1, 2, "h"))
        assertFalse(WindowFramePicker.defaultSeed(1, 2, "h") == WindowFramePicker.defaultSeed(1, 2, "h2"))
    }

    @Test fun emptyAndZero() {
        assertTrue(WindowFramePicker.pick(emptyList(), from, to, PickMode.EVEN, 5, "s").ids.isEmpty())
        assertTrue(WindowFramePicker.pick(many, from, to, PickMode.EVEN, 0, "s").ids.isEmpty())
    }

    @Test fun policyFailsClosed() {
        val ctx = TestRunPolicy.Ctx(flagOn = true, developerMode = true, busy = false, offlineOnly = false, hasOnlineVision = false, hasPyFn = false, zoneHeldOrUnknown = false)
        val w = TestRunSource.Window(from, to, PickMode.EVEN, 10, null)
        assertEquals(null, TestRunPolicy.refusal(w, ctx))
        assertEquals("test_run_off", TestRunPolicy.refusal(w, ctx.copy(flagOn = false)))
        assertEquals("test_run_off", TestRunPolicy.refusal(w, ctx.copy(developerMode = false)))
        assertEquals("busy", TestRunPolicy.refusal(w, ctx.copy(busy = true)))
        assertEquals("offline_only", TestRunPolicy.refusal(w, ctx.copy(offlineOnly = true, hasOnlineVision = true)))
        assertEquals("zone_held", TestRunPolicy.refusal(w, ctx.copy(zoneHeldOrUnknown = true)))
        assertEquals("bad_window", TestRunPolicy.refusal(w.copy(toMs = from), ctx))
        assertEquals("window_too_long", TestRunPolicy.refusal(w.copy(toMs = from + 25L * 3_600_000L), ctx))
        assertEquals("bad_count", TestRunPolicy.refusal(w.copy(n = 51), ctx))
        val tap = TestRunSource.Tap(5, 20, 0)
        assertEquals(null, TestRunPolicy.refusal(tap, ctx))
        assertEquals("tap_py_fn", TestRunPolicy.refusal(tap, ctx.copy(hasPyFn = true)))
        assertEquals("tap_no_photo_budget", TestRunPolicy.refusal(tap, ctx.copy(hasOnlineVision = true)))
        assertEquals("bad_bounds", TestRunPolicy.refusal(tap.copy(maxMin = 16), ctx))
    }
}
