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
    /** Glasses to phone: a [MotionHint] (P3 hook; defined in wave 1, unused). */
    const val MOTION_HINT = "pc_motion_hint"
}

/** How the glasses decide when to capture: Smart = motion-driven keyframes, Fixed = the interval timer. */
@Serializable enum class CaptureMode { SMART, FIXED }

/** Coarse head/body motion class from the glasses IMU; an unknown wire value decodes to [UNKNOWN]. */
@Serializable enum class MotionState { STILL, HEAD_MOVING, WALKING, VEHICLE, UNKNOWN }

/** Glasses → phone on `pc_motion_hint` (P3 hook, unused in wave 1). */
@Serializable data class MotionHint(val state: MotionState, val atMs: Long)

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
    /**
     * [undoProposalId] (BUG-R2-5, additive): an auto-added action's undo window is open; the card shows the undo line
     * and a glasses double-tap undoes instead of dismissing. Older glasses ignore it and show the plain Answer.
     */
    @Serializable @SerialName("answer") data class Answer(
        val question: String, val text: String, val speaking: Boolean, val undoProposalId: String? = null,
    ) : Overlay
    @Serializable @SerialName("nudge") data class Nudge(val text: String) : Overlay
    @Serializable @SerialName("confirm") data class Confirm(val proposalId: String, val title: String, val detail: String) : Overlay
    @Serializable @SerialName("paused_splash") data object PausedSplash : Overlay
    /** [undoProposalId] (Phase 7, additive): an undo toast; a glasses double-tap then undoes instead of toggling pause. */
    @Serializable @SerialName("toast") data class Toast(val text: String, val undoProposalId: String? = null) : Overlay
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

/**
 * Phone → glasses on `pc_settings`. [pauses] is the merged pause set (spec §5.5). [askBlockedReason] is the short HUD
 * text for a tap = Ask while voice can't work (spec §5.1.1), null when it can; additive (Phase 6), no version bump.
 */
@Serializable
data class HudSettings(
    val v: Int = PROTOCOL_VERSION,
    val verbosity: Verbosity = Verbosity.MEDIUM,
    val scale: Float = 0.4f,
    val position: HudPosition = HudPosition.BOTTOM_CENTER,
    val gestures: Map<Gesture, GestureAction> = GestureDefaults.map,
    val captureIntervalSec: Int = 300,
    /** Ignored since keyframe capture ([captureMode] supersedes it); kept for wire compatibility. */
    val burstOnChange: Boolean = true,
    val quietHours: QuietHours? = null,
    val pauses: List<PauseRecord> = emptyList(),
    val askBlockedReason: String? = null,
    /** Phase 7, additive: ids the phone resumed (closed intervals ≤ 7 d, ≤ 200 newest); glasses drop them. */
    val resumedPauseIds: List<String> = emptyList(),
    /** Phase 7, additive: true = [pauses] were converted with a measured clock offset. */
    val pausesExact: Boolean = false,
    /** Phase 7, additive: the phone's ZoneId.id; the glasses evaluate quiet hours in it. */
    val zoneId: String? = null,
    /** Spec 2026-10-14 §3, additive: where the minimised icon sits. */
    val iconCorner: IconCorner = IconCorner.TOP_RIGHT,
    /** Spec 2026-10-14 §4, additive: how long a minimised pop-up stays, seconds ([POPUP_MIN_SEC]..[POPUP_MAX_SEC], clamped on use). */
    val popupSeconds: Int = POPUP_DEFAULT_SEC,
    /** Keyframe capture, additive: absent on the wire = Fixed (old phones). The phone sends SMART for new installs. */
    val captureMode: CaptureMode = CaptureMode.FIXED,
    /** Keyframe capture, additive: pause sampling when the glasses are not worn (proximity far > 5 s). */
    val pauseWhenNotWorn: Boolean = true,
    /** Work minutes before a rest: 30|40|45|50|60|75|90 (see [normalized]). */
    val workMinutes: Int = 45,
    /** Quick look, ms: 1000|1500|2000|3000 (see [normalized]). */
    val quickLookMs: Int = 1000,
    /** Rest minutes: 5|10|15|20; at least 15 when [workMinutes] > 45 (see [normalized]). */
    val restMinutes: Int = 15,
)

val WORK_MINUTES_ALLOWED = listOf(30, 40, 45, 50, 60, 75, 90)
val REST_MINUTES_ALLOWED = listOf(5, 10, 15, 20)
val QUICK_LOOK_MS_ALLOWED = listOf(1000, 1500, 2000, 3000)

private fun List<Int>.nearest(v: Int): Int = minByOrNull { kotlin.math.abs(it - v) } ?: first()

/**
 * Snaps the keyframe-capture values to their allowed sets (nearest, ties to the smaller) and enforces R >= 15 when
 * W > 45. Pure; used by the phone UI and again on the glasses so a wild wire value is never trusted.
 * [burstOnChange] is ignored (superseded by [captureMode]).
 */
fun HudSettings.normalized(): HudSettings {
    val w = WORK_MINUTES_ALLOWED.nearest(workMinutes)
    var r = REST_MINUTES_ALLOWED.nearest(restMinutes)
    if (w > 45 && r < 15) r = 15
    return copy(workMinutes = w, restMinutes = r, quickLookMs = QUICK_LOOK_MS_ALLOWED.nearest(quickLookMs))
}

/** The corner of the minimised icon on the glasses display. */
@Serializable enum class IconCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

const val POPUP_MIN_SEC = 3
const val POPUP_MAX_SEC = 20
const val POPUP_DEFAULT_SEC = 6

fun clampPopupSeconds(s: Int): Int = s.coerceIn(POPUP_MIN_SEC, POPUP_MAX_SEC)

/** The pop-up duration in ms, clamped: a wild value on the wire never keeps a pop-up up for long. */
fun HudSettings.popupMs(): Long = clampPopupSeconds(popupSeconds) * 1_000L

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
    /** Phase 7, additive: undo an executed proposal (glasses double-tap on an undo toast). */
    @Serializable @SerialName("undo") data class Undo(val proposalId: String) : Command
}

/** `pc_cmd` payload: commands are wrapped so every message carries [v]. */
@Serializable data class CommandEnvelope(val v: Int = PROTOCOL_VERSION, val command: Command)

/**
 * Glasses → phone on `pc_hello`: sent when a session opens and again after each buffered frame (a status report).
 * [glassesNowMs] gives the phone the clock offset; [pauses] and [nextCaptureAtMs] are in glasses clock.
 * [resumedAtMs] = pauseId → glasses-clock instant of every Resume the wearer made that the phone has not yet confirmed
 * (it still listed the id). Re-sent in every hello, so a Resume made while the link was down is never lost. Additive:
 * an older payload without it decodes to empty.
 */
@Serializable
data class Hello(
    val v: Int = PROTOCOL_VERSION,
    val glassesBatteryPct: Int?,
    val bufferSize: Int,
    val glassesNowMs: Long,
    val pauses: List<PauseRecord>,
    val nextCaptureAtMs: Long? = null,
    val resumedAtMs: Map<String, Long> = emptyMap(),
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
    /** Keyframe capture, additive: absent from an old glasses = [MotionState.UNKNOWN] and not a dwell. */
    val motion: MotionState = MotionState.UNKNOWN,
    val dwell: Boolean = false,
    val blur: Float? = null,
    /** 16 hex chars (the existing [phash] is also 16 hex chars). */
    val dhash: String? = null,
    val width: Int? = null,
    val sampleSeq: Long? = null,
    val sessionId: String? = null,
)

@Serializable data class FrameAck(val v: Int = PROTOCOL_VERSION, val frameId: String, val ok: Boolean, val reason: String? = null)
