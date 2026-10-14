package com.rokid.context.glasses.capture

import com.rokid.context.core.model.MotionState

/** One accepted keyframe leaving the sampling session (640 px JPEG plus the gate's metadata). */
data class KeyframeOut(
    val jpeg: ByteArray,
    val width: Int,
    val motion: MotionState,
    val dwell: Boolean,
    val blur: Float,
    val dhash: String,
    val phash: String,
    val sampleSeq: Long,
    val sessionId: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyframeOut) return false
        return jpeg.contentEquals(other.jpeg) && width == other.width && motion == other.motion && dwell == other.dwell &&
            blur == other.blur && dhash == other.dhash && phash == other.phash && sampleSeq == other.sampleSeq && sessionId == other.sessionId
    }

    override fun hashCode(): Int {
        var h = jpeg.contentHashCode()
        h = 31 * h + width
        h = 31 * h + motion.hashCode()
        h = 31 * h + dwell.hashCode()
        h = 31 * h + blur.hashCode()
        h = 31 * h + dhash.hashCode()
        h = 31 * h + phash.hashCode()
        h = 31 * h + sampleSeq.hashCode()
        h = 31 * h + sessionId.hashCode()
        return h
    }
}

/** Snapshot of the sampling session for the HUD status page. */
data class SessionStatus(
    val running: Boolean,
    val motion: MotionState,
    val keyframesLast10Min: Int,
    val restingUntilMs: Long?,
    val reason: String?,
)
