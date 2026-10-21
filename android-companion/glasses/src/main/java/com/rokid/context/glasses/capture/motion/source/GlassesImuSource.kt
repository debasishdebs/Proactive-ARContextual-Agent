package com.rokid.context.glasses.capture.motion.source

import com.rokid.context.glasses.capture.motion.model.MotionModel
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The glasses' own IMU as a [MotionSignalSource]: one evidence a second from the per-second features, always available. */
class GlassesImuSource(private val model: MotionModel) : MotionSignalSource {
    override val id = "glasses_imu"
    override val kind = SourceKind.GLASSES_IMU
    override val available: StateFlow<Boolean> = MutableStateFlow(true)
    private val out = MutableSharedFlow<MotionEvidence>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val evidence: Flow<MotionEvidence> = out

    fun onSecond(f: SecondFeatures, tMs: Long): MotionEvidence {
        val e = MotionEvidence(tMs, model.classify(f), 1.0f, id)
        out.tryEmit(e)
        return e
    }
}
