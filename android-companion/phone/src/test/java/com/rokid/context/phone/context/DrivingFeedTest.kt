package com.rokid.context.phone.context

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.CarLinkKind
import com.rokid.context.core.model.context.ContextDecision
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.PhoneActivity
import com.rokid.context.core.model.context.WatchState
import com.rokid.context.orchestration.proactive.DrivingState
import com.rokid.context.orchestration.proactive.DrivingTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class DrivingFeedTest {
    /** [t] is the monotonic clock, [w] the wall clock the evidence is stamped with; real time moves both. */
    private var t = 5_000_000_000L
    private var w = 1_790_000_000_000L
    private val tr = DrivingTracker { t }
    private fun adv(ms: Long) { t += ms; w += ms }
    private fun beats(ms: Long) { var n = 0L; while (n < ms) { DrivingFeed.evaluated(ContextDecision(ContextState.UNKNOWN, 0f, w, emptySet(), null, ContextState.UNKNOWN, false), tr); adv(10_000); n += 10_000 } }
    private fun feed(e: Evidence) = DrivingFeed.accepted(e, tr, w)
    private fun s() = tr.assess().state
    private fun glasses(st: MotionState, frac: Float, age: Long?) = Evidence.GlassesMotion(w, st, frac, age)

    @Test fun glassesWalkingAloneIsOneSourceSoUnknown() {
        beats(600_000); feed(glasses(MotionState.WALKING, 0.1f, 0)); assertEquals(DrivingState.UNKNOWN, s())
        assertEquals("Unknown: only one motion source: suggestions are paused", tr.assess().reason)
    }
    @Test fun aGlassesStillHintAloneStaysUnknown() {
        beats(600_000); feed(glasses(MotionState.STILL, 0.95f, 0)); assertEquals(DrivingState.UNKNOWN, s())
        assertEquals("Unknown: only one motion source: suggestions are paused", tr.assess().reason)
    }
    @Test fun aStillHintPlusPhoneStepsIsNotDriving() {
        beats(600_000); feed(glasses(MotionState.STILL, 0.95f, 0)); feed(Evidence.Steps(w, 90)); assertEquals(DrivingState.NOT_DRIVING, s())
    }
    @Test fun glassesStillNeedsCoverage() {
        beats(600_000); feed(Evidence.Steps(w, 90))
        feed(glasses(MotionState.STILL, 0.5f, 0)); assertEquals(DrivingState.UNKNOWN, s())
        feed(glasses(MotionState.STILL, -1f, 0)); assertEquals(DrivingState.UNKNOWN, s())
        feed(glasses(MotionState.STILL, 0.9f, 0)); assertEquals(DrivingState.NOT_DRIVING, s())
    }
    @Test fun aGlassesCalmHintOfUnknownAgeIsNeverCalm() {
        beats(600_000); feed(Evidence.Steps(w, 90))
        feed(glasses(MotionState.STILL, 0.95f, null)); feed(glasses(MotionState.WALKING, 0f, null)); assertEquals(DrivingState.UNKNOWN, s())
    }
    @Test fun aDelayedGlassesHintIsBackdatedByItsAge() {
        beats(600_000); feed(Evidence.Steps(w, 90))
        feed(glasses(MotionState.WALKING, 0f, 150_000)); assertEquals("already 150 s old: stale", DrivingState.UNKNOWN, s())
        feed(glasses(MotionState.WALKING, 0f, 100_000)); assertEquals(DrivingState.NOT_DRIVING, s())
        adv(30_000); feed(Evidence.Steps(w, 90)); assertEquals("the glasses reading aged out", DrivingState.UNKNOWN, s())
    }
    @Test fun aVehicleHintUsesReceiptTimeWhateverItsAge() {
        beats(600_000)
        feed(glasses(MotionState.VEHICLE, 0f, null)); assertEquals(DrivingState.DRIVING, s())
    }
    @Test fun headMovingAndUnknownSayNothing() {
        beats(600_000); feed(glasses(MotionState.HEAD_MOVING, 0.9f, 0)); feed(glasses(MotionState.UNKNOWN, 0.9f, 0)); feed(Evidence.Steps(w, 90)); assertEquals(DrivingState.UNKNOWN, s())
    }
    @Test fun everyVehicleKindIsDriving() {
        val all = listOf<Evidence>(
            glasses(MotionState.VEHICLE, 0f, 0), Evidence.ActivityTransition(w, PhoneActivity.IN_VEHICLE, true),
            Evidence.CarLink(w, CarLinkKind.ANDROID_AUTO, true), Evidence.CarLink(w, CarLinkKind.MY_CAR_BT, true), Evidence.CarLink(w, CarLinkKind.CAR_CLASS_BT, true),
            Evidence.WatchMotion(w, WatchState.IN_VEHICLE, 0.9f), Evidence.Speed(w, 30f, true), Evidence.Airplane(w, true),
        )
        for (e in all) { val x = DrivingTracker { t }; DrivingFeed.accepted(e, x, w); assertEquals(e.toString(), DrivingState.DRIVING, x.assess().state) }
    }
    @Test fun aDisconnectedCarOrAnExitIsNotAVehicleFact() {
        beats(600_000)
        feed(Evidence.CarLink(w, CarLinkKind.ANDROID_AUTO, false)); feed(Evidence.ActivityTransition(w, PhoneActivity.IN_VEHICLE, false)); feed(Evidence.Airplane(w, false))
        assertEquals(DrivingState.UNKNOWN, s())
    }
    @Test fun phoneActivityStepsSpeedAndWatchEachCountOnce() {
        beats(600_000)
        feed(Evidence.ActivityTransition(w, PhoneActivity.STILL, true)); assertEquals("one source", DrivingState.UNKNOWN, s())
        feed(Evidence.Steps(w, 30)); assertEquals(DrivingState.UNKNOWN, s())
        feed(Evidence.Steps(w, 90)); assertEquals("still activity plus steps", DrivingState.NOT_DRIVING, s())
        val x = DrivingTracker { t }; repeat(61) { DrivingFeed.evaluated(ContextDecision(ContextState.UNKNOWN, 0f, w, emptySet(), null, ContextState.UNKNOWN, false), x); adv(10_000) }
        DrivingFeed.accepted(Evidence.Speed(w, 3f, true), x, w); DrivingFeed.accepted(Evidence.WatchMotion(w, WatchState.WALKING, 0.9f), x, w)
        assertEquals(DrivingState.NOT_DRIVING, x.assess().state)
    }
    @Test fun aCalmReadingStampedInTheFutureIsIgnoredButAVehicleCountsAsNow() {
        beats(600_000)
        feed(Evidence.Steps(w + 60_000, 90)); feed(Evidence.ActivityTransition(w + 60_000, PhoneActivity.STILL, true)); assertEquals(DrivingState.UNKNOWN, s())
        feed(Evidence.CarLink(w + 60_000, CarLinkKind.ANDROID_AUTO, true)); assertEquals(DrivingState.DRIVING, s())
    }
    @Test fun aWallClockJumpBackDoesNotMakeAVehicleReadingOlder() {
        beats(600_000); w -= 3_600_000
        feed(Evidence.CarLink(w + 3_600_000, CarLinkKind.ANDROID_AUTO, true)); assertEquals("a reading from 'the future' counts as now", DrivingState.DRIVING, s())
    }
    @Test fun aSuspectedDecisionIsDrivingAndAVehicleStateToo() {
        beats(600_000)
        DrivingFeed.evaluated(ContextDecision(ContextState.UNKNOWN, 0f, w, emptySet(), null, ContextState.UNKNOWN, true), tr); assertEquals(DrivingState.DRIVING, s())
        val x = DrivingTracker { t }
        DrivingFeed.evaluated(ContextDecision(ContextState.VEHICLE_PASSENGER, 0.9f, w, emptySet(), null, ContextState.VEHICLE_PASSENGER, false), x); assertEquals(DrivingState.DRIVING, x.assess().state)
    }
}
