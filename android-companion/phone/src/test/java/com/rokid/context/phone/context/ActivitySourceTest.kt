package com.rokid.context.phone.context

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.PhoneActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fake wall + elapsed clocks that tick together; `wallJump` moves only the wall clock (a user clock change). */
class FakeObsClock(var el: Long = 500_000L, var wallOffset: Long = 1_000_000_000L) {
    fun time() = ObsTime(wall = { el + wallOffset }, elapsed = { el })
    fun wall() = el + wallOffset
}

class ActivitySourceTest {
    private val clock = FakeObsClock()
    private val got = ArrayList<Evidence>()
    private var sinkTaken = 0
    private var registered = 0
    private var unregistered = 0
    private var permitted = true
    private var play = true
    private var registerOk = true
    private var failed = 0
    private var failCb: (() -> Unit)? = null

    @org.junit.After fun clearHolder() { ActivitySource.current = null }
    @org.junit.Before fun resetHolder() { ActivitySource.current = null }

    private fun src() = ActivitySource(
        sinkFor = { id -> assertEquals("phone_activity", id); sinkTaken++; EvidenceSink { got += it } },
        failFor = { id -> assertEquals("phone_activity", id); { failed++ } },
        time = clock.time(),
        platform = object : ActivitySource.Platform {
            override fun playServices() = play
            override fun permitted() = permitted
            override fun register(onFailed: () -> Unit): Boolean { registered++; failCb = onFailed; return registerOk }
            override fun unregister() { unregistered++ }
        },
    )

    private fun started(): ActivitySource = src().also { assertTrue(it.start()) }

    @Test fun idAndAvailabilityFollowPlayServices() {
        assertEquals("phone_activity", src().id)
        assertTrue(src().available()); play = false; assertFalse(src().available())
    }

    @Test fun startWithoutPermissionRegistersNothing() {
        permitted = false
        val s = src()
        assertFalse(s.start()); assertEquals(0, registered); assertNull(ActivitySource.current)
    }

    @Test fun startTakesTheSinkInsideStartAndPublishesTheHolder() {
        val s = src()
        assertEquals(0, sinkTaken)
        assertTrue(s.start()); assertEquals(1, sinkTaken); assertEquals(1, registered)
        assertTrue(ActivitySource.current === s)
        s.stop(); assertEquals(1, unregistered); assertNull(ActivitySource.current)
    }

    @Test fun refusedRegistrationFailsTheStart() {
        registerOk = false
        assertFalse(src().start()); assertNull(ActivitySource.current)
    }

    @Test fun vehicleEnterMapsToEvidence() {
        val s = started()
        s.onTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        assertEquals(listOf<Evidence>(Evidence.ActivityTransition(clock.wall(), PhoneActivity.IN_VEHICLE, true)), got)
        assertEquals(1, s.events)
    }

    @Test fun exitAndAllFiveTypesMap() {
        val s = started()
        val pairs = mapOf(
            DetectedActivity.STILL to PhoneActivity.STILL, DetectedActivity.WALKING to PhoneActivity.WALKING,
            DetectedActivity.RUNNING to PhoneActivity.RUNNING, DetectedActivity.ON_BICYCLE to PhoneActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE to PhoneActivity.IN_VEHICLE,
        )
        pairs.forEach { (t, a) -> s.onTransition(t, ActivityTransition.ACTIVITY_TRANSITION_EXIT); clock.el += 1 }
        assertEquals(pairs.values.toList(), got.map { (it as Evidence.ActivityTransition).activity })
        assertTrue(got.none { (it as Evidence.ActivityTransition).enter })
    }

    @Test fun otherTypesAreIgnoredAndCounted() {
        val s = started()
        s.onTransition(DetectedActivity.TILTING, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        assertTrue(got.isEmpty()); assertEquals(1, s.ignored); assertEquals(0, s.events)
    }

    @Test fun unknownTransitionTypeIsIgnored() {
        val s = started(); s.onTransition(DetectedActivity.WALKING, 9)
        assertTrue(got.isEmpty()); assertEquals(1, s.ignored)
    }

    @Test fun nothingBeforeStartOrAfterStop() {
        val s = src()
        s.onTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        assertTrue(got.isEmpty())
        s.start(); s.stop()
        s.onTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        assertTrue(got.isEmpty())
    }

    @Test fun observationTimeNotCallbackTimeIsUsed() {
        val s = started()
        clock.el += 120_000
        s.onTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, obsElapsedMs = clock.el - 45_000)
        assertEquals(clock.wall() - 45_000, got.single().atMs)
    }

    @Test fun aWallClockJumpDoesNotMoveTheObservation() {
        val s = started()
        clock.el += 60_000
        val obs = clock.el - 10_000
        clock.wallOffset += 3_600_000 // user sets the clock an hour forward between the observation and the callback
        s.onTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, obs)
        assertEquals(clock.wall() - 10_000, got.single().atMs)
    }

    @Test fun historyBeforeTheRunStartedIsNotReplayed() {
        val s = started()
        clock.el += 30_000
        s.onTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, obsElapsedMs = 400_000)
        assertTrue(got.isEmpty()); assertEquals(1, s.stale)
    }

    @Test fun olderThanTheLastAcceptedIsDropped() {
        val s = started()
        clock.el += 100_000
        s.onTransition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER, obsElapsedMs = clock.el - 10_000)
        s.onTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, obsElapsedMs = clock.el - 50_000)
        assertEquals(1, got.size); assertEquals(1, s.stale)
    }

    @Test fun veryOldObservationIsStale() {
        val s = started()
        clock.el += 2 * 3_600_000L
        s.onTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, obsElapsedMs = 600_000)
        assertTrue(got.isEmpty()); assertEquals(1, s.stale)
    }

    @Test fun aFutureObservationIsClampedToNow() {
        val s = started()
        s.onTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, obsElapsedMs = clock.el + 5_000)
        assertEquals(clock.wall(), got.single().atMs)
    }

    @Test fun firstResultAdmitsTheCurrentActivityUpToFifteenMinutesOld() {
        val s = started(); clock.el += 20 * 60_000L
        // Play services answers a new registration with the current activity, stamped when it began (before start())
        s.onResult(listOf(ActivitySource.Transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, 500_000L + 20 * 60_000L - 600_000L)))
        assertEquals(1, got.size); assertEquals(clock.wall() - 600_000L, got.single().atMs)
        // later results keep the strict gate: nothing from before the run
        s.onResult(listOf(ActivitySource.Transition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, 400_000L)))
        assertEquals(1, got.size); assertEquals(1, s.stale)
    }

    @Test fun firstResultStillDropsWhatIsOlderThanFifteenMinutes() {
        val s = started(); clock.el += 40 * 60_000L
        s.onResult(listOf(ActivitySource.Transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, clock.el - 16 * 60_000L)))
        assertTrue(got.isEmpty())
    }

    @Test fun aFutureStampDoesNotMakeLaterRealEventsOutOfOrder() {
        val s = started(); clock.el += 60_000
        s.onResult(listOf(
            ActivitySource.Transition(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER, clock.el - 30_000),
            ActivitySource.Transition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, clock.el + 5_000), // clock skew: future
            ActivitySource.Transition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, clock.el - 10_000),
        ))
        assertEquals(3, got.size); assertEquals(0, s.stale)
    }

    @Test fun anAsyncRequestFailureIsReportedToTheHub() {
        started(); assertEquals(0, failed)
        failCb!!.invoke(); assertEquals(1, failed)
    }

    @Test fun receiverUnregistersOnlyWhenTheSourceIsOffAndNotRunning() {
        assertTrue(ActivityTransitionReceiver.shouldUnregister(current = null, enabled = false))
        assertFalse(ActivityTransitionReceiver.shouldUnregister(current = null, enabled = true))
        assertFalse(ActivityTransitionReceiver.shouldUnregister(current = src(), enabled = false))
    }

    @Test fun restartTakesAFreshSinkAndResetsTheWindow() {
        val s = started(); s.stop()
        clock.el += 10_000
        assertTrue(s.start()); assertEquals(2, sinkTaken)
        s.onTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, obsElapsedMs = clock.el - 5_000)
        assertTrue(got.isEmpty()) // observed before the second run began
        s.onTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        assertEquals(1, got.size)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActivityTransitionReceiverManifestTest {
    @Test fun receiverResolvesNotExported() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val info = ctx.packageManager.getReceiverInfo(
            android.content.ComponentName(ctx.packageName, "com.rokid.context.phone.context.ActivityTransitionReceiver"), 0,
        )
        assertFalse(info.exported)
    }
}
