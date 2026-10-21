package com.rokid.context.glasses.capture.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context

class WorkRestTest {
    private val min = 60_000L
    private var now = 0L
    private var work = 45
    private var rest = 15
    private val wr = WorkRest({ now }, { work }, { rest })

    private fun sampleFor(minutes: Int) { // a tick every minute
        repeat(minutes + 1) { wr.onSampling(); if (it < minutes) now += min }
    }

    @Test fun restsAfter45() {
        sampleFor(44); assertNull(wr.resting.value)
        now += min; wr.onSampling()
        assertEquals(now + 15 * min, wr.resting.value)
    }

    @Test fun honours60and20() {
        work = 60; rest = 20
        sampleFor(59); assertNull(wr.resting.value)
        now += min; wr.onSampling()
        assertEquals(now + 20 * min, wr.resting.value)
    }

    @Test fun pauseLongerThanRestResetsCycle() {
        sampleFor(40)
        wr.onPaused(); now += 16 * min; wr.onSampling() // a 16 min pause >= R
        now += 10 * min; wr.onSampling()
        assertNull(wr.resting.value) // only 10 min worked since the reset, not 50
        now += 35 * min; wr.onSampling()
        assertEquals(now + 15 * min, wr.resting.value)
    }

    @Test fun shortPauseKeepsTheWorkClock() {
        sampleFor(40)
        wr.onPaused(); now += 2 * min; wr.onSampling()
        now += 3 * min; wr.onSampling()
        assertEquals(now + 15 * min, wr.resting.value)
    }

    @Test fun restEndsAndNextCycleStartsFresh() {
        sampleFor(45)
        val until = wr.resting.value!!
        now = until; wr.onSampling()
        assertNull(wr.resting.value)
        now += 44 * min; wr.onSampling()
        assertNull(wr.resting.value)
    }

    @Test fun clampsRestTo15WhenWorkOver45() {
        work = 60; rest = 5
        sampleFor(59); now += min; wr.onSampling()
        assertEquals(now + 15 * min, wr.resting.value)
        // at W <= 45 a short rest stands
        val wr2 = WorkRest({ now }, { 45 }, { 5 })
        wr2.onSampling(); now += 45 * min; wr2.onSampling()
        assertEquals(now + 5 * min, wr2.resting.value)
    }

    @Test fun acceptsAllWorkAndRestOptions() {
        // literal expectations (minutes of rest): R as chosen, but at least 15 once W > 45
        val expected = mapOf(
            30 to listOf(5, 10, 15, 20), 40 to listOf(5, 10, 15, 20), 45 to listOf(5, 10, 15, 20),
            50 to listOf(15, 15, 15, 20), 60 to listOf(15, 15, 15, 20), 75 to listOf(15, 15, 15, 20), 90 to listOf(15, 15, 15, 20),
        )
        val rests = listOf(5, 10, 15, 20)
        for ((w, restFor) in expected) for ((i, r) in rests.withIndex()) {
            now = 0L
            val c = WorkRest({ now }, { w }, { r })
            c.onSampling(); now = (w - 1) * min; c.onSampling()
            assertNull(c.resting.value, "w=$w r=$r")
            now = w * min; c.onSampling()
            val expectedRest = restFor[i]
            assertEquals(now + expectedRest * min, c.resting.value, "w=$w r=$r")
        }
    }

    @Test fun aClockThatWentBackRestartsTheWorkClock() {
        now = 100 * min; wr.onSampling()
        now = 120 * min; wr.onSampling() // 20 min worked
        now = 10 * min; wr.onSampling()  // back: the cycle restarts at the new time
        now = 54 * min; wr.onSampling()  // 44 min since the restart
        assertNull(wr.resting.value)
        now = 55 * min; wr.onSampling()
        assertEquals(now + 15 * min, wr.resting.value)
    }

    @Test fun aClockThatWentBackDuringRestDoesNotExtendIt() {
        sampleFor(45)
        val until = wr.resting.value!!
        now -= 600 * min // far back
        wr.onPaused()
        assertEquals(true, wr.resting.value!! - now <= 15 * min)
        assertEquals(true, until > 0)
    }

    @Test fun disabledNeverRests() {
        var on = false
        val d = WorkRest({ now }, { work }, { rest }, enabled = { on })
        repeat(180) { d.onSampling(); now += min; assertNull(d.resting.value) } // 3 h
    }

    @Test fun disablingMidRestClears() {
        var on = true
        val d = WorkRest({ now }, { work }, { rest }, enabled = { on })
        repeat(46) { d.onSampling(); now += min }
        assertNotNull(d.resting.value)
        on = false
        d.onPaused()
        assertNull(d.resting.value)
        on = true
        now += min; d.onSampling()
        assertNull(d.resting.value, "the work clock restarted")
    }

    private class FakeStore : WorkRestStore {
        var snap: WorkRestSnapshot? = null
        var saves = 0
        var clears = 0
        override fun load() = snap
        override fun save(s: WorkRestSnapshot) { snap = s; saves++ }
        override fun clear() { snap = null; clears++ }
    }

    private fun persisted(store: WorkRestStore, boot: () -> Int = { 7 }, on: () -> Boolean = { true }) =
        WorkRest({ now }, { work }, { rest }, enabled = on, store = store, bootCount = boot)

    @Test fun killDuringRestResumesRest() {
        val store = FakeStore()
        val a = persisted(store)
        repeat(46) { a.onSampling(); now += min }
        val until = a.resting.value!!
        now += 2 * min
        val b = persisted(store) // process death: a fresh instance, same clock and store
        b.onPaused()
        assertEquals(until, b.resting.value)
    }

    @Test fun killDuringWorkKeepsWorkStart() {
        val store = FakeStore()
        val a = persisted(store)
        repeat(30) { a.onSampling(); now += min }
        now += 1 * min // a short gap, less than R
        val b = persisted(store)
        b.onSampling()
        now += 13 * min; b.onSampling()
        assertNull(b.resting.value)
        now += 1 * min; b.onSampling() // 45 min since the first tick
        assertNotNull(b.resting.value)
    }

    @Test fun rebootDiscardsState() {
        val store = FakeStore()
        val a = persisted(store, boot = { -1 })
        repeat(46) { a.onSampling(); now += min }
        assertNotNull(a.resting.value)
        now = 0 // uptime restarted below savedAt
        val b = persisted(store, boot = { -1 })
        b.onPaused()
        assertNull(b.resting.value)
        assertEquals(null, store.snap?.restUntilMs, "the stale rest is not carried over")
    }

    @Test fun rebootWithLongerUptimeDiscards() {
        val store = FakeStore()
        val a = persisted(store, boot = { 7 })
        repeat(46) { a.onSampling(); now += min }
        assertNotNull(a.resting.value)
        now += 600 * min // the new boot's uptime is already past savedAt
        val b = persisted(store, boot = { 8 })
        b.onPaused()
        assertNull(b.resting.value)
    }

    @Test fun unknownBootCountFallsBackToUptime() {
        val store = FakeStore()
        val a = persisted(store, boot = { -1 })
        repeat(46) { a.onSampling(); now += min }
        val until = a.resting.value!!
        val b = persisted(store, boot = { 7 }) // either count unknown: only the uptime test applies, which passes
        b.onPaused()
        assertEquals(until, b.resting.value)
    }

    @Test fun noWritePerTick() {
        val store = FakeStore()
        val a = persisted(store)
        repeat(600) { a.onSampling(); now += 10_000L }
        assertTrue(store.saves <= 3, "saves=${store.saves}")
    }

    @Test fun disabledClearsStore() {
        val store = FakeStore()
        store.snap = WorkRestSnapshot(0, null, null, 0, 7)
        var on = false
        val d = persisted(store, on = { on })
        repeat(5) { d.onSampling(); now += min }
        assertEquals(1, store.clears)
        assertEquals(0, store.saves)
        assertNull(store.snap)
    }
}

@RunWith(AndroidJUnit4::class)
class PrefsWorkRestStoreTest {
    @Test fun prefsStoreRoundTrip() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("work_rest_test", Context.MODE_PRIVATE)
        val store = PrefsWorkRestStore(prefs)
        assertNull(store.load())
        store.save(WorkRestSnapshot(1L, null, 3L, 4L, 9))
        assertEquals(WorkRestSnapshot(1L, null, 3L, 4L, 9), store.load())
        store.clear()
        assertNull(store.load())
    }
}
