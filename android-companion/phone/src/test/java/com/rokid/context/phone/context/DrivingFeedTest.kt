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
    private var t = 5_000_000_000L
    private val tr = DrivingTracker { t }
    private fun beats(ms: Long) { var n = 0L; while (n < ms) { DrivingFeed.evaluated(ContextDecision(ContextState.UNKNOWN, 0f, t, emptySet(), null, ContextState.UNKNOWN, false), tr); t += 10_000; n += 10_000 } }
    private fun feed(e: Evidence) = DrivingFeed.accepted(e, tr)
    private fun s() = tr.assess().state

    @Test fun glassesWalkingAndEnoughStillnessAreCalm() {
        beats(600_000); feed(Evidence.GlassesMotion(t, MotionState.WALKING, 0.1f)); assertEquals(DrivingState.NOT_DRIVING, s())
    }
    @Test fun glassesStillNeedsCoverage() {
        beats(600_000); feed(Evidence.GlassesMotion(t, MotionState.STILL, 0.5f)); assertEquals(DrivingState.UNKNOWN, s())
        feed(Evidence.GlassesMotion(t, MotionState.STILL, -1f)); assertEquals(DrivingState.UNKNOWN, s())
        feed(Evidence.GlassesMotion(t, MotionState.STILL, 0.9f)); assertEquals(DrivingState.NOT_DRIVING, s())
    }
    @Test fun headMovingAndUnknownSayNothing() {
        beats(600_000); feed(Evidence.GlassesMotion(t, MotionState.HEAD_MOVING, 0.9f)); feed(Evidence.GlassesMotion(t, MotionState.UNKNOWN, 0.9f)); assertEquals(DrivingState.UNKNOWN, s())
    }
    @Test fun everyVehicleKindIsDriving() {
        val all = listOf<Evidence>(
            Evidence.GlassesMotion(t, MotionState.VEHICLE, 0f), Evidence.ActivityTransition(t, PhoneActivity.IN_VEHICLE, true),
            Evidence.CarLink(t, CarLinkKind.ANDROID_AUTO, true), Evidence.CarLink(t, CarLinkKind.MY_CAR_BT, true), Evidence.CarLink(t, CarLinkKind.CAR_CLASS_BT, true),
            Evidence.WatchMotion(t, WatchState.IN_VEHICLE, 0.9f), Evidence.Speed(t, 30f, true), Evidence.Airplane(t, true),
        )
        for (e in all) { val x = DrivingTracker { t }; beats(0); DrivingFeed.accepted(e, x); assertEquals(e.toString(), DrivingState.DRIVING, x.assess().state) }
    }
    @Test fun aDisconnectedCarOrAnExitIsNotAVehicleFact() {
        beats(600_000)
        feed(Evidence.CarLink(t, CarLinkKind.ANDROID_AUTO, false)); feed(Evidence.ActivityTransition(t, PhoneActivity.IN_VEHICLE, false)); feed(Evidence.Airplane(t, false))
        assertEquals(DrivingState.UNKNOWN, s())
    }
    @Test fun phoneActivityStepsAndWatchCalm() {
        beats(600_000)
        feed(Evidence.ActivityTransition(t, PhoneActivity.STILL, true)); assertEquals(DrivingState.NOT_DRIVING, s())
        beats(180_000); feed(Evidence.Steps(t, 30)); assertEquals(DrivingState.UNKNOWN, s())
        feed(Evidence.Steps(t, 90)); assertEquals(DrivingState.NOT_DRIVING, s())
    }
    @Test fun aSuspectedDecisionIsDrivingAndAVehicleStateToo() {
        beats(600_000)
        DrivingFeed.evaluated(ContextDecision(ContextState.UNKNOWN, 0f, t, emptySet(), null, ContextState.UNKNOWN, true), tr); assertEquals(DrivingState.DRIVING, s())
        val x = DrivingTracker { t }
        DrivingFeed.evaluated(ContextDecision(ContextState.VEHICLE_PASSENGER, 0.9f, t, emptySet(), null, ContextState.VEHICLE_PASSENGER, false), x); assertEquals(DrivingState.DRIVING, x.assess().state)
    }
}
