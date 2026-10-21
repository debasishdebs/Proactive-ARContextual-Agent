package com.rokid.context.core.model.context

import com.rokid.context.core.model.PROTOCOL_VERSION
import kotlinx.serialization.Serializable

/** Coarse context the phone fuses from its signals; an unknown wire value decodes to [UNKNOWN]. */
@Serializable enum class ContextState { UNKNOWN, REST, WALKING, VEHICLE_PASSENGER, VEHICLE_DRIVING, FLIGHT }

/** Why a [ContextState] was chosen. On the wire these travel as strings (see [MotionContextMessage.reasons]). */
@Serializable
enum class ContextReason {
    GLASSES_WALK, PHONE_WALK, STEPS, WATCH_WALK, SPEED, ANDROID_AUTO, MY_CAR_BT, CAR_BT, AR_VEHICLE, WATCH_VEHICLE,
    IMAGE_VEHICLE, IMAGE_DRIVER, IMAGE_PASSENGER, BT_CONTRADICTED, AIRPLANE, IMAGE_FLIGHT, GLASSES_STILL, IMAGE_IDLE, WATCH_ASLEEP,
}

/** What the image-intent step says the wearer is doing. */
@Serializable
enum class IntentLabel {
    SITTING_IDLE, WORKOUT, COMPUTER_WORK, DRIVING, VEHICLE_PASSENGER, FLIGHT_CABIN, WALKING, OTHER;

    companion object {
        /** Exact enum names only; null or anything else is [OTHER]. */
        fun parse(s: String?): IntentLabel = entries.firstOrNull { it.name == s } ?: OTHER
    }
}

/** Phone to glasses on `pc_motion_ctx` (spec 2026-10-21 §4). The glasses measure the TTL from receipt time. */
@Serializable
data class MotionContextMessage(
    val v: Int = PROTOCOL_VERSION,
    val state: ContextState = ContextState.UNKNOWN,
    val confidence: Float = 0f,
    /** Phone clock, when the state was entered. */
    val sinceMs: Long = 0L,
    /** Phone clock, when sent. */
    val atMs: Long,
    /** The glasses drop the message this long after receipt. */
    val validForMs: Long = 180_000L,
    /** false = shadow mode (logged, not applied). */
    val apply: Boolean = true,
    /** [ContextReason] names; strings so a new reason never breaks an old decoder. */
    val reasons: List<String> = emptyList(),
    val sessionStartMs: Long = 0L,
)
