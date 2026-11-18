package com.rokid.context.phone.context

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.CarLinkKind
import com.rokid.context.core.model.context.ContextDecision
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.PhoneActivity
import com.rokid.context.core.model.context.WatchState
import com.rokid.context.orchestration.proactive.Calm
import com.rokid.context.orchestration.proactive.DrivingSignal
import com.rokid.context.orchestration.proactive.DrivingTracker

/**
 * v3.0 T14: maps the evidence the context hub already collects onto the [DrivingTracker]. No new sensor, location request or permission; only
 * the kind and time of a reading is used, never a coordinate (the speed source emits km/h only). Anything not listed says nothing.
 */
object DrivingFeed {
    fun accepted(e: Evidence, t: DrivingTracker = DrivingSignal.tracker) {
        val at = e.atMs
        when (e) {
            is Evidence.GlassesMotion -> when (e.state) {
                MotionState.WALKING -> t.calm(at, Calm.WALKING)
                MotionState.STILL -> if (e.stillFrac5m >= DrivingTracker.STILL_FRAC) t.calm(at, Calm.STILL)
                MotionState.VEHICLE -> t.vehicle(at)
                else -> {}
            }
            is Evidence.ActivityTransition -> if (e.enter) when (e.activity) {
                PhoneActivity.IN_VEHICLE -> t.vehicle(at)
                PhoneActivity.STILL -> t.calm(at, Calm.STILL)
                PhoneActivity.WALKING, PhoneActivity.RUNNING -> t.calm(at, Calm.WALKING)
                PhoneActivity.ON_BICYCLE -> {}
            }
            is Evidence.Steps -> if (e.stepsPerMin >= 60) t.calm(at, Calm.WALKING)
            is Evidence.Speed -> t.speed(at, e.kmh / 3.6f)
            is Evidence.CarLink -> if (e.connected && (e.kind == CarLinkKind.ANDROID_AUTO || e.kind == CarLinkKind.MY_CAR_BT || e.kind == CarLinkKind.CAR_CLASS_BT)) t.vehicle(at)
            is Evidence.WatchMotion -> when (e.state) {
                WatchState.IN_VEHICLE -> t.vehicle(at)
                WatchState.WALKING, WatchState.RUNNING -> t.calm(at, Calm.WALKING)
                else -> {}
            }
            is Evidence.Airplane -> if (e.on) t.vehicle(at)
            is Evidence.Intent -> {}
        }
    }

    fun evaluated(d: ContextDecision, t: DrivingTracker = DrivingSignal.tracker) {
        t.heartbeat(d.vehicleSuspected || d.state == ContextState.VEHICLE_DRIVING || d.state == ContextState.VEHICLE_PASSENGER || d.state == ContextState.FLIGHT)
    }
}
