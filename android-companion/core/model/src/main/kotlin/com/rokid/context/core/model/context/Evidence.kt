package com.rokid.context.core.model.context

import com.rokid.context.core.model.MotionState
import kotlinx.serialization.Serializable

/** One timestamped fact from a source; the fusion engine consumes these. Not sent on the wire. */
sealed interface Evidence {
    val atMs: Long

    /** A glasses hint is vehicle evidence only (receipt time); a glasses clock cannot prove absolute age, so it is never calm evidence for NOT_DRIVING. */
    data class GlassesMotion(override val atMs: Long, val state: MotionState, val stillFrac5m: Float) : Evidence
    data class ActivityTransition(override val atMs: Long, val activity: PhoneActivity, val enter: Boolean) : Evidence
    data class Steps(override val atMs: Long, val stepsPerMin: Int) : Evidence
    data class Speed(override val atMs: Long, val kmh: Float, val precise: Boolean) : Evidence
    data class CarLink(override val atMs: Long, val kind: CarLinkKind, val connected: Boolean) : Evidence
    data class Airplane(override val atMs: Long, val on: Boolean) : Evidence
    data class WatchMotion(override val atMs: Long, val state: WatchState, val confidence: Float) : Evidence
    data class Intent(override val atMs: Long, val label: IntentLabel, val confidence: Float) : Evidence
}

enum class PhoneActivity { STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE }

enum class CarLinkKind { MY_CAR_BT, CAR_CLASS_BT, ANDROID_AUTO }

/** Galaxy Watch state via LiveFit; an unknown wire value decodes to [UNKNOWN]. */
@Serializable enum class WatchState { STILL, WALKING, RUNNING, CYCLING, IN_VEHICLE, EXERCISE, ASLEEP, UNKNOWN }
