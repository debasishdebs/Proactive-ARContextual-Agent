package com.rokid.context.phone.context

import com.rokid.context.core.model.context.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepSourceTest {
    private val clock = FakeObsClock(el = 600_000L) // minute boundary: buckets are [600_000, 660_000) ...
    private val got = ArrayList<Evidence>()
    private var present = true
    private var permitted = true
    private var registered = 0
    private var unregistered = 0
    private var sinkTaken = 0

    private fun src() = StepSource(
        sinkFor = { id -> assertEquals("phone_steps", id); sinkTaken++; EvidenceSink { got += it } },
        time = clock.time(),
        platform = object : StepSource.Platform {
            override fun present() = present
            override fun permitted() = permitted
            override fun register(): Boolean { registered++; return true }
            override fun unregister() { unregistered++ }
        },
    )

    private fun started() = src().also { assertTrue(it.start()) }
    private fun at(offsetMs: Long) = 600_000L + offsetMs
    /** A step observed (and delivered) at [offsetMs]: the clock moves up to it first, as it would in real time. */
    private fun StepSource.feed(n: Int, offsetMs: Long) { clock.el = maxOf(clock.el, at(offsetMs)); onSteps(n, at(offsetMs)) }

    @Test fun availabilityFollowsTheSensor() {
        assertEquals("phone_steps", src().id)
        assertTrue(src().available()); present = false; assertFalse(src().available())
    }

    @Test fun absentSensorOrPermissionFailsStart() {
        present = false; assertFalse(src().start()); assertEquals(0, registered)
        present = true; permitted = false; assertFalse(src().start()); assertEquals(0, registered)
    }

    @Test fun seventyStepsThenOneInTheNextBucketEmitsSeventyPerMin() {
        val s = started()
        for (i in 0 until 70) s.feed(1, i * 800L)
        assertTrue(got.isEmpty())
        s.feed(1, 60_010)
        val e = got.single() as Evidence.Steps
        assertEquals(70, e.stepsPerMin)
        assertEquals(clock.wall() - 10, e.atMs) // the bucket end (660_000) in the fusion clock, not the callback time
        assertEquals(1, s.events)
    }

    @Test fun noStepsEmitsNothing() {
        val s = started(); clock.el = at(300_000)
        s.flush(clock.el)
        assertTrue(got.isEmpty())
    }

    @Test fun flushClosesACompletedBucketButNotARunningOne() {
        val s = started()
        s.feed(40, 5_000)
        s.flush(at(30_000)); assertTrue(got.isEmpty())
        clock.el = at(61_000)
        s.flush(at(60_000))
        assertEquals(40, (got.single() as Evidence.Steps).stepsPerMin)
        s.flush(at(120_000)); assertEquals(1, got.size) // closed once
    }

    @Test fun bucketsBySensorTimeNotBySeenTime() {
        val s = started()
        clock.el = at(150_000)
        // a delayed batch delivered at +150 s: events from three different minutes
        s.onSteps(10, at(10_000)); s.onSteps(20, at(70_000)); s.onSteps(30, at(130_000))
        s.flush(at(180_000))
        assertEquals(listOf(10, 20, 30), got.map { (it as Evidence.Steps).stepsPerMin })
        assertEquals(clock.wall() - 90_000, got[0].atMs) // bucket end 660_000 in the fusion clock
    }

    @Test fun anEventForAClosedBucketIsDropped() {
        val s = started()
        s.feed(5, 10_000); s.feed(5, 65_000)
        s.onSteps(7, at(20_000)) // older than the bucket already closed
        s.flush(at(200_000))
        assertEquals(listOf(5, 5), got.map { (it as Evidence.Steps).stepsPerMin }); assertEquals(1, s.stale)
    }

    @Test fun historyBeforeTheRunIsNotReplayed() {
        val s = started()
        s.onSteps(50, at(-30_000)); s.flush(at(200_000))
        assertTrue(got.isEmpty()); assertEquals(1, s.stale)
    }

    @Test fun nothingAfterStopAndPendingBucketIsDiscarded() {
        val s = started(); s.feed(9, 1_000); s.stop()
        assertEquals(1, unregistered)
        s.onSteps(9, at(2_000)); s.flush(at(300_000)); assertTrue(got.isEmpty())
        clock.el = at(400_000); assertTrue(s.start()); s.flush(at(500_000)); assertTrue(got.isEmpty()); assertEquals(2, sinkTaken)
    }

    @Test fun anEmptyGapSkipsWithoutEmittingZeroBuckets() {
        val s = started()
        s.feed(30, 1_000)
        s.feed(1, 10 * 60_000L)
        assertEquals(listOf(30), got.map { (it as Evidence.Steps).stepsPerMin })
    }
}
