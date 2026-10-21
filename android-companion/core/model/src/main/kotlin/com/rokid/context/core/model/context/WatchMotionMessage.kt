package com.rokid.context.core.model.context

import com.rokid.context.core.model.Wire
import kotlinx.serialization.Serializable

/** Galaxy Watch state via LiveFit (spec §8). [v] is the watch contract version, independent of PROTOCOL_VERSION. */
@Serializable
data class WatchMotionMessage(
    val v: Int = 1,
    val seq: Long,
    val state: WatchState = WatchState.UNKNOWN,
    val confidence: Float = 0.8f,
    val changedAtMs: Long = 0,
    val sentAtMs: Long = 0,
    val source: String = "galaxy_watch",
    /** Random non-zero per sender install/process start (additive, default 0): a new epoch resets [seq]. */
    val epoch: Long = 0,
)

object WatchMotionCodec {
    const val VERSION = 1

    /** Null when malformed or `v != 1`; confidence is clamped to 0..1. */
    fun decode(json: String): WatchMotionMessage? {
        val m = runCatching { Wire.json.decodeFromString<WatchMotionMessage>(json) }.getOrNull() ?: return null
        if (m.v != VERSION) return null
        return m.copy(confidence = if (m.confidence.isNaN()) 0f else m.confidence.coerceIn(0f, 1f))
    }
}
