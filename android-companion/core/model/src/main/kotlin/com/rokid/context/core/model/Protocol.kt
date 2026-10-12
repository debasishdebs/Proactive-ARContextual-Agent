package com.rokid.context.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bump on any incompatible change to a message below (spec §1.5). */
const val PROTOCOL_VERSION = 1

object Channels {
    const val STATE = "pc_state"
    const val SETTINGS = "pc_settings"
    const val FRAME = "pc_frame"
    const val FRAME_ACK = "pc_frame_ack"
    const val CMD = "pc_cmd"
    const val AUDIO = "pc_audio"
    const val LISTEN_END = "pc_listen_end"
    const val HELLO = "pc_hello"
    /** Phone → glasses: please send a fresh `pc_hello` (the phone's clock offset looks stale). */
    const val HELLO_REQUEST = "pc_hello_request"
}

@Serializable enum class PauseOrigin { GLASSES, PHONE, QUIET_HOURS }

/** One reason capture is paused (spec §5.5). Capture is allowed only when no record is active. */
@Serializable
data class PauseRecord(val pauseId: String, val origin: PauseOrigin, val createdAtMs: Long, val untilMs: Long? = null) {
    fun isActiveAt(nowMs: Long): Boolean = untilMs == null || nowMs < untilMs
}

@Serializable
sealed interface CaptureState {
    @Serializable @SerialName("running") data object Running : CaptureState
    @Serializable @SerialName("paused") data class Paused(val records: List<PauseRecord>) : CaptureState
    @Serializable @SerialName("deferred") data class Deferred(val reason: String) : CaptureState
    @Serializable @SerialName("blocked") data class Blocked(val reason: String) : CaptureState
}

@Serializable
sealed interface Health {
    @Serializable @SerialName("ok") data object Ok : Health
    @Serializable @SerialName("degraded") data class Degraded(val text: String) : Health
    @Serializable @SerialName("blocked") data class Blocked(val text: String) : Health
}

@Serializable data class NudgeLine(val text: String, val atMs: Long)
@Serializable data class MemoryLine(val id: String, val atMs: Long, val text: String)

@Serializable
sealed interface Overlay {
    @Serializable @SerialName("listening") data class Listening(val partial: String) : Overlay
    @Serializable @SerialName("answer") data class Answer(val question: String, val text: String, val speaking: Boolean) : Overlay
    @Serializable @SerialName("nudge") data class Nudge(val text: String) : Overlay
    @Serializable @SerialName("confirm") data class Confirm(val proposalId: String, val title: String, val detail: String) : Overlay
    @Serializable @SerialName("paused_splash") data object PausedSplash : Overlay
    @Serializable @SerialName("toast") data class Toast(val text: String) : Overlay
}

/** Full HUD state pushed phone → glasses on `pc_state` (spec §1.6). Short human strings only. */
@Serializable
data class StateFrame(
    val v: Int = PROTOCOL_VERSION,
    val seq: Long,
    val capture: CaptureState,
    val nextCaptureAtMs: Long? = null,
    val intervalSec: Int,
    val glassesBatteryPct: Int? = null,
    val phoneLinked: Boolean,
    val aiHealth: Health,
    val queueDepth: Int = 0,
    val lastSentAtMs: Long? = null,
    val latestNudge: NudgeLine? = null,
    val today: List<MemoryLine> = emptyList(),
    val overlay: Overlay? = null,
    val error: String? = null,
)

@Serializable enum class HudPage { GLANCE, TODAY, STATUS }
@Serializable enum class Verbosity { SILENT, MEDIUM, MAX }

@Serializable
enum class HudPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER_LEFT, CENTER, CENTER_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

/** Long-press is reserved for "Hi Rokid" and vertical swipes are not sensed (spec §3.5), so they are not listed. */
@Serializable enum class Gesture { TAP, DOUBLE_TAP, SWIPE_FORWARD, SWIPE_BACK }
@Serializable enum class GestureAction { ASK, PAUSE_RESUME, NEXT_PAGE, PREV_PAGE, DISMISS, NONE }

object GestureDefaults {
    val map: Map<Gesture, GestureAction> = mapOf(
        Gesture.TAP to GestureAction.ASK,
        Gesture.DOUBLE_TAP to GestureAction.PAUSE_RESUME,
        Gesture.SWIPE_FORWARD to GestureAction.NEXT_PAGE,
        Gesture.SWIPE_BACK to GestureAction.PREV_PAGE,
    )
}

/** Local-time window in minutes after midnight; wraps past midnight when start > end; start == end = none. */
@Serializable
data class QuietHours(val startMinute: Int, val endMinute: Int) {
    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
}

/** Phone → glasses on `pc_settings`. [pauses] is the merged pause set (spec §5.5). */
@Serializable
data class HudSettings(
    val v: Int = PROTOCOL_VERSION,
    val verbosity: Verbosity = Verbosity.MEDIUM,
    val scale: Float = 0.4f,
    val position: HudPosition = HudPosition.BOTTOM_CENTER,
    val gestures: Map<Gesture, GestureAction> = GestureDefaults.map,
    val captureIntervalSec: Int = 300,
    val burstOnChange: Boolean = true,
    val quietHours: QuietHours? = null,
    val pauses: List<PauseRecord> = emptyList(),
)

@Serializable
sealed interface Command {
    @Serializable @SerialName("tap") data object Tap : Command
    @Serializable @SerialName("double_tap") data object DoubleTap : Command
    @Serializable @SerialName("swipe") data class Swipe(val forward: Boolean, val long: Boolean) : Command
    @Serializable @SerialName("confirm") data class Confirm(val proposalId: String, val yes: Boolean) : Command
    @Serializable @SerialName("pause") data class Pause(val pause: PauseRecord) : Command
    @Serializable @SerialName("resume") data class Resume(val clears: Set<String>) : Command
    @Serializable @SerialName("page_changed") data class PageChanged(val page: HudPage) : Command
    @Serializable @SerialName("listen_start") data object ListenStart : Command
    @Serializable @SerialName("cancel") data object Cancel : Command
}

/** `pc_cmd` payload: commands are wrapped so every message carries [v]. */
@Serializable data class CommandEnvelope(val v: Int = PROTOCOL_VERSION, val command: Command)

/**
 * Glasses → phone on `pc_hello`: sent when a session opens and again after each buffered frame (a status report).
 * [glassesNowMs] gives the phone the clock offset; [pauses] and [nextCaptureAtMs] are in glasses clock.
 */
@Serializable
data class Hello(
    val v: Int = PROTOCOL_VERSION,
    val glassesBatteryPct: Int?,
    val bufferSize: Int,
    val glassesNowMs: Long,
    val pauses: List<PauseRecord>,
    val nextCaptureAtMs: Long? = null,
)

/** Phone → glasses on `pc_hello_request`: the glasses answer with [Hello]. Sent rate-limited when a frame is held. */
@Serializable data class HelloRequest(val v: Int = PROTOCOL_VERSION)

/** First Caps string of every `pc_frame` message; the second string is one Base64 chunk (spec §1.8). */
@Serializable
data class FrameMeta(
    val v: Int = PROTOCOL_VERSION,
    val frameId: String,
    val chunkIdx: Int,
    val chunkCount: Int,
    val crc32: Long,
    val capturedAtMs: Long,
    val luma: Int? = null,
    val phash: String? = null,
    val batteryPct: Int? = null,
)

@Serializable data class FrameAck(val v: Int = PROTOCOL_VERSION, val frameId: String, val ok: Boolean, val reason: String? = null)
