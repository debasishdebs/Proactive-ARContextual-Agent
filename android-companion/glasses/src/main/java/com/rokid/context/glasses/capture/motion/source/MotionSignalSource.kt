package com.rokid.context.glasses.capture.motion.source

import com.rokid.context.core.model.MotionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SourceKind { GLASSES_IMU, PHONE, WATCH, RING, HOME_CAMERA }

/** One source's opinion of the wearer's motion at [atMs] (elapsed-realtime ms). [sourceId] is e.g. `glasses_imu`, `phone_motion`, `watch_livefit`. */
data class MotionEvidence(val atMs: Long, val state: MotionState, val confidence: Float, val sourceId: String)

/** A rest-vs-motion signal provider (glasses IMU now; phone, watch, ring, home camera later). Plain Kotlin, no Android types. */
interface MotionSignalSource {
    val id: String
    val kind: SourceKind
    val available: StateFlow<Boolean>
    val evidence: Flow<MotionEvidence>
}
