package com.rokid.context.phone.context

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.CarLinkKind
import com.rokid.context.core.model.context.ContextDecision
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.PhoneActivity
import com.rokid.context.core.model.context.WatchState
import com.rokid.context.orchestration.proactive.Calm
import com.rokid.context.orchestration.proactive.CalmSource
import com.rokid.context.orchestration.proactive.DrivingSignal
import com.rokid.context.orchestration.proactive.DrivingTracker

/**
 * v3.0 T14: maps the evidence the context hub already collects onto the [DrivingTracker]. No new sensor, location request or permission; only
 * the kind and age of a reading is used, never a coordinate (the speed source emits km/h only). Anything not listed says nothing.
 *
 * Ages (Codex v3 r3): a reading's age is the phone's wall time now minus its stamp (the source stamped it a moment ago on the same clock), never a time the tracker
 * would have to compare with its monotonic clock. A calm reading stamped in the future is ignored (a vehicle one counts as now). A glasses hint is never calm evidence (shown in the debug line as
 * 'glasses hint (not used as calm evidence)'); only a vehicle hint counts, at receipt time. Each phone source counts once; NOT_DRIVING needs two.
 */
object DrivingFeed {
    private const val FUTURE_SLACK_MS = 5_000L

    fun accepted(e: Evidence, t: DrivingTracker = DrivingSignal.tracker, wallNow: Long = System.currentTimeMillis()) {
        val raw = wallNow - e.atMs
        val vehicleAge = raw.coerceAtLeast(0)
        val calmAge: Long? = if (raw < -FUTURE_SLACK_MS) null else vehicleAge
        fun calm(source: CalmSource, kind: Calm) { calmAge?.let { t.calm(source, kind, it) } }
        when (e) {
            // A glasses stamp is its own clock: age cannot be proven without a real clock sync, so a glasses hint is vehicle evidence only (receipt time), never calm.
            is Evidence.GlassesMotion -> if (e.state == MotionState.VEHICLE) t.vehicle(vehicleAge)
            is Evidence.ActivityTransition -> if (e.enter) when (e.activity) {
                PhoneActivity.IN_VEHICLE -> t.vehicle(vehicleAge)
                PhoneActivity.STILL -> calm(CalmSource.PHONE_ACTIVITY, Calm.STILL)
                PhoneActivity.WALKING, PhoneActivity.RUNNING -> calm(CalmSource.PHONE_ACTIVITY, Calm.WALKING)
                PhoneActivity.ON_BICYCLE -> {}
            }
            is Evidence.Steps -> if (e.stepsPerMin >= 60) calm(CalmSource.PHONE_STEPS, Calm.WALKING)
            is Evidence.Speed -> if (calmAge != null || e.kmh / 3.6f >= DrivingTracker.FAST_MPS) t.speed(e.kmh / 3.6f, vehicleAge)
            is Evidence.CarLink -> if (e.connected && (e.kind == CarLinkKind.ANDROID_AUTO || e.kind == CarLinkKind.MY_CAR_BT || e.kind == CarLinkKind.CAR_CLASS_BT)) t.vehicle(vehicleAge)
            is Evidence.WatchMotion -> when (e.state) {
                WatchState.IN_VEHICLE -> t.vehicle(vehicleAge)
                WatchState.WALKING, WatchState.RUNNING -> calm(CalmSource.WATCH, Calm.WALKING)
                else -> {}
            }
            is Evidence.Airplane -> if (e.on) t.vehicle(vehicleAge)
            is Evidence.Intent -> {}
        }
    }

    fun evaluated(d: ContextDecision, t: DrivingTracker = DrivingSignal.tracker) {
        t.heartbeat(d.vehicleSuspected || d.state == ContextState.VEHICLE_DRIVING || d.state == ContextState.VEHICLE_PASSENGER || d.state == ContextState.FLIGHT)
    }
}
