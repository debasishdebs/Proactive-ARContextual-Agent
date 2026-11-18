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
    /** Glasses to phone: a [MotionHint] (wave B: sent on WALKING changes and every 60 s). */
    const val MOTION_HINT = "pc_motion_hint"
    /** Wave B, phone to glasses: a [com.rokid.context.core.model.context.MotionContextMessage]. Additive. */
    const val MOTION_CONTEXT = "pc_motion_ctx"
    /** Fix wave H, phone to glasses: a [WearMessage] (the real wear signal from the Rokid SDK). Additive. */
    const val WEAR = "pc_wear"
    /** Fix wave H, glasses to phone: a [UserExit] (the wearer exited the app on purpose; do not reopen it). Additive. */
    const val USER_EXIT = "pc_user_exit"
    /** Fix wave I, phone to glasses: an [AutoOpen] (this start of the glasses app was the phone's automatic reopen: keep it minimised). Additive. */
    const val AUTO_OPEN = "pc_auto_open"
    /** Battery wave A, phone to glasses: a [PhoneContextMessage] (the phone is in use). Additive. */
    const val PHONE_CONTEXT = "pc_phone_ctx"
    /** Slice 3a, phone to glasses: a [CapturePlanMessage] (a desired capture plan or "use settings"). Additive. */
    const val CAPTURE_PLAN = "pc_capture_plan"
    /** Slice 3a, glasses to phone: a [CapturePlanAck] (also sent unsolicited at gen 0 on every session open, carrying the caps). Additive. */
    const val CAPTURE_PLAN_ACK = "pc_capture_plan_ack"
    /** Slice 3a, glasses to phone: a [MarkMessage] ("mark this moment"). Additive. */
    const val MARK = "pc_mark"
    /** Slice 3a, phone to glasses: a [MarkAck]. Additive. */
    const val MARK_ACK = "pc_mark_ack"
    /** v0.9.6 slice C, glasses to phone: a [MarkPhotoResult] (a mark's photo could not be taken). Additive; an old phone ignores the channel. */
    const val MARK_PHOTO = "pc_mark_photo"
    /** v0.9.7 slice D, phone to glasses: a [MarkPhotoRequest] (a mark made on the phone, by voice or in the chat, wants the glasses' photo). Additive; old glasses ignore the channel. */
    const val MARK_PHOTO_REQUEST = "pc_mark_photo_request"
    /** Slice 3a, phone to glasses: a [TraceControl] (collect, tombstones, send). Additive. */
    const val TRACE_CTL = "pc_trace_ctl"
    /** Slice 3a, glasses to phone: a [TraceChunk]. Additive. */
    const val TRACE_CHUNK = "pc_trace_chunk"
    /** Slice 3a, phone to glasses: a [TraceAck]. Additive. */
    const val TRACE_ACK = "pc_trace_ack"
}

/** How the glasses decide when to capture: Smart = motion-driven keyframes, Fixed = the interval timer. */
@Serializable enum class CaptureMode { SMART, FIXED }

/** Coarse head/body motion class from the glasses IMU; an unknown wire value decodes to [UNKNOWN]. */
@Serializable enum class MotionState { STILL, HEAD_MOVING, WALKING, VEHICLE, UNKNOWN }

/** Glasses → phone on `pc_motion_hint`. [stillFrac5m]: share of the last 300 s the classifier was STILL; -1 = not yet known. */
@Serializable data class MotionHint(
    val state: MotionState = MotionState.UNKNOWN,
    val atMs: Long,
    val v: Int = PROTOCOL_VERSION,
    val stillFrac5m: Float = -1f,
    /** v0.9.7 A, additive: the hash of the classifier thresholds the glasses run (`default` or 12 hex); null from an old glasses build. */
    val classifierHash: String? = null,
    /** v1.0 wear fallback, additive: true = the glasses estimate "not worn (estimated)" ([WearEstimate]); false = no estimate; null = an old glasses build (never a guess). */
    val wearEst: Boolean? = null,
    /** v3.0 driving signal (Codex v3 r3 P1-1), additive: the phone's `sessionStartMs` the glasses know when sending ([atMs] is the glasses' own elapsedRealtime, never comparable to the phone's clock); 0 = unknown / an old glasses build. A hint of another session is not calm evidence. */
    val sessionStartMs: Long = 0L,
)

/** Phone to glasses on `pc_wear`: the Rokid SDK's `wearingStatus` (true = worn) and when the phone saw it (phone clock). */
@Serializable data class WearMessage(
    val v: Int = PROTOCOL_VERSION,
    /** v1.0: null = the wear value is unknown and this message only carries [wearCheck] (an old glasses build cannot decode it and ignores it, as before). */
    val worn: Boolean? = null,
    val atMs: Long,
    /** Fix wave I, additive: when the phone's current glasses session began (phone clock). A value seen before it is stale and ignored; 0 = not sent (old phone). */
    val sessionStartMs: Long = 0L,
    /** v1.0 wear fallback, additive: the glasses' system wear check as the phone last read it (true = on, false = off); null = not known (or an old phone). */
    val wearCheck: Boolean? = null,
    /** v1.1 (Codex wear r2), additive: the phone's send order within [sessionStartMs]; the glasses ignore a message older than one they applied (a delayed "check off" never beats a newer "check on"). 0 = not sent (old phone). */
    val seq: Long = 0L,
    /** v1.1 (Codex wear r4), additive: a random number per phone hub start, and the session count within it. A session number lower than one the glasses applied in the same epoch is a late message of a retired session and is dropped (no wall clock involved). 0 = not sent. */
    val phoneEpoch: Long = 0L,
    val sessionNo: Long = 0L,
)

/** Phone to glasses on `pc_auto_open` (fix wave I): the phone reopened the app by itself a moment ago; it must stay in icon mode. */
@Serializable data class AutoOpen(val v: Int = PROTOCOL_VERSION, val atMs: Long)

/** Glasses to phone on `pc_user_exit`: the wearer exited the glasses app completely (triple double-tap). */
@Serializable data class UserExit(val v: Int = PROTOCOL_VERSION)

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
        /** Additive (rc1 fix): the stored ask this card shows, so a later [StateFrame.withdrawnAskIds] can clear it; null from an old phone. */
        val askId: String? = null,
    ) : Overlay
    /**
     * [place] (Place Guide PG3, additive): present = this nudge is a place card. [text] then carries "title: line", so an older glasses build (which ignores [place]) shows an ordinary
     * nudge (Max only). A new glasses build shows the card at Medium and Max for [PlaceCardInfo.SHOW_MS] and never during a pause zone.
     */
    @Serializable @SerialName("nudge") data class Nudge(val text: String, val place: PlaceCardInfo? = null) : Overlay
    @Serializable @SerialName("confirm") data class Confirm(val proposalId: String, val title: String, val detail: String) : Overlay
    @Serializable @SerialName("paused_splash") data object PausedSplash : Overlay
    /** [undoProposalId] (Phase 7, additive): an undo toast; a glasses double-tap then undoes instead of toggling pause. */
    @Serializable @SerialName("toast") data class Toast(val text: String, val undoProposalId: String? = null) : Overlay
}

/** Where the Place Guide card sits on the glasses HUD (B2). Default [CENTRE] (the layout before B2). */
enum class PlaceSide {
    LEFT, CENTRE, RIGHT;
    companion object {
        /** Wire value for a side: null for [CENTRE] (nothing is sent), else the name. */
        fun toWire(side: PlaceSide): String? = if (side == CENTRE) null else side.name
        /** A wire value back to a side; null, blank or an unknown value is [CENTRE] (never fails). */
        fun parse(wire: String?): PlaceSide = entries.firstOrNull { it.name == wire } ?: CENTRE
    }
}

/** Place Guide card on the wire (PG3). [line] is at most 140 chars, built from the place pack; [nearby] = only the nearest place, not checked against the photo. */
@Serializable data class PlaceCardInfo(
    val title: String, val line: String, val source: String, val nearby: Boolean = false,
    /** Additive: this card instance (the phone numbers every published card); null from an old phone. A dismissal names it so a later card with the same title is never cleared by it. */
    val id: String? = null,
    /**
     * Additive, optional (B2, PROTOCOL_VERSION stays 1): where the HUD card sits, a [PlaceSide] name ("LEFT" or "RIGHT"). Null = the centre, the layout before B2; the phone sends null for CENTRE so the
     * default frame is byte-for-byte unchanged. An older glasses build ignores it (ignoreUnknownKeys); a new one reads it with [PlaceSide.parse] (absent or unknown = CENTRE).
     */
    val side: String? = null,
    /**
     * Additive (v3.1 r5 P1-2): a hard cap on how long the GLASSES show this card, counted on the glasses' own clock from the moment it first draws it (never longer than [SHOW_MS]; 0 or less = not at all). The phone sets
     * it for a suggestion card so it ends at the next quiet-hours start even if the phone link or process dies. Null from an old phone or for a place card = [SHOW_MS]. An older glasses build ignores it.
     */
    val maxShowMs: Long? = null,
    /**
     * Additive (v3.1 r6 P1-2): an ABSOLUTE deadline, in the GLASSES' wall clock (epoch ms), after which the card must not be drawn (the next quiet-hours start). The glasses hold no phone/glasses offset of their own: the PHONE
     * owns the measured offset (`ClockOffset.fromHello`, the same one it converts pauses with) and sends the boundary already converted. The glasses evaluate it at the FIRST DRAW ([remainingMs]), not at publication, so a
     * delayed broadcast, delivery or render cannot outlive the boundary. Null = no boundary known (or an old phone); an older glasses build ignores it. [maxShowMs] stays as the duration fallback.
     */
    val hideAtMs: Long? = null,
) {
    /**
     * How long the glasses may show this card if they draw it at [glassesNowMs] (their wall clock): [limitMs] cut to the absolute [hideAtMs] when one is carried, clamped to 0..[SHOW_MS]. 0 = draw nothing.
     * [glassesNowMs] must be read at the moment of drawing.
     */
    fun remainingMs(glassesNowMs: Long): Long = hideAtMs?.let { minOf(limitMs(), it - glassesNowMs) }?.coerceIn(0L, SHOW_MS) ?: limitMs()

    /** The glasses' hard lifetime of this card: the cap (fail closed: never above [SHOW_MS], never below 0). */
    fun limitMs(): Long = minOf(SHOW_MS, maxShowMs ?: SHOW_MS).coerceAtLeast(0L)

    companion object {
        /** How long the glasses show a card; the phone clears its copy at the same time. */
        const val SHOW_MS = 12_000L
        /** A head-motion onset dismisses a card only once it has been up this long. */
        const val MOTION_DISMISS_AFTER_MS = 2_500L
    }
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
    /**
     * Additive (rc1 fix): asks a Forget redacted or deleted in the last minute (newest first, bounded). Glasses showing or holding an Answer for one of
     * them drop it at once, whatever [overlay] says. Old glasses ignore it (the overlay of every frame already replaces theirs).
     */
    val withdrawnAskIds: List<String> = emptyList(),
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
    /** Keyframe capture, additive. Fix wave H: gated by the real wear signal (`pc_wear`) only; never when the wear state is unknown. */
    val pauseWhenNotWorn: Boolean = true,
    /** Fix wave H, additive: blink a small mark on the HUD for ~600 ms per saved keyframe. Old glasses ignore it. */
    val captureCue: Boolean = true,
    /** Work minutes before a rest: 30|40|45|50|60|75|90 (see [normalized]). */
    val workMinutes: Int = 45,
    /** Quick look, ms: 1000|1500|2000|3000 (see [normalized]). */
    val quickLookMs: Int = 1000,
    /** Rest minutes: 5|10|15|20; at least 15 when [workMinutes] > 45 (see [normalized]). */
    val restMinutes: Int = 15,
    /** Battery wave A, additive: what wakes the camera from a quiet spell; default SETTLE (wave A2). Unknown wire value = SETTLE. */
    val cameraWake: CameraWake = CameraWake.SETTLE,
    /** Battery wave A, additive: target battery life in hours, 0 = Off, default Off (wave A2: last-resort lever) (see [BATTERY_TARGET_HOURS_ALLOWED], [normalized]). */
    val batteryTargetHours: Int = 0,
    /** Battery wave A, additive: pause capture while the phone is in use (see [PhoneContextMessage]). */
    val pauseWhenPhoneInUse: Boolean = false,
    /** Wave A3: WorkRest default Off. */
    val workRestEnabled: Boolean = false,
    /** Wave A3: training label, enum only. Unknown wire value = NONE. */
    val motionLabel: MotionLabel = MotionLabel.NONE,
    /** Wave A3: source ids the user turned off; glasses_imu is ignored here (see [normalized]). */
    val motionSourcesOff: List<String> = emptyList(),
    /** Slice 3a, additive: a new phone sets true (it speaks `pc_capture_plan`). Glasses holding a persisted plan that see false revert to settings (`auto:old_phone`). */
    val planAware: Boolean = false,
    /** Wear fallback, additive: park (pause) after a still run when no wear signal says otherwise. false = never by stillness (`pc_wear` still works). */
    val wearParkEnabled: Boolean = true,
    /** Wear fallback, additive: still seconds before parking while the wear signal is unknown ([WEAR_PARK_MIN_SEC]..[WEAR_PARK_MAX_SEC], see [normalized]). */
    val wearParkUnknownSec: Int = 120,
    /** Wear fallback, additive: still seconds before parking while Rokid says worn (same range). */
    val wearParkWornSec: Int = 600,
    /**
     * Always-on diagnostic recording (LGC4), additive: the phone's Developer mode as the glasses should see it. Absent on the wire (an old phone) = false = OFF.
     * Deliberately NOT part of the trace settings hash. It is only a REQUEST: the glasses record only once a deletion epoch is also saved (three-state bootstrap).
     */
    val diagRecord: Boolean = false,
    /** v0.9.6 slice C, additive: how long the camera stays open for the photo of a mark, ms ([MARK_PHOTO_HOLD_MIN_MS]..[MARK_PHOTO_HOLD_MAX_MS], see [normalized]). Old glasses ignore it. */
    val markPhotoHoldMs: Int = MARK_PHOTO_HOLD_DEFAULT_MS,
    /** v0.9.7 A, additive: edited classifier thresholds (only non-default keys, see [com.rokid.context.core.model.signals.ClassifierThresholds]). Empty = defaults. Old glasses ignore it. */
    val classifier: Map<String, Double> = emptyMap(),
    /**
     * v0.9.7 B, additive: developer-only RECORDING of raw glasses sensor samples (gyro, linear acceleration) at this rate, in batches on the glasses (0 = off, else one of [SENSOR_REC_PRESETS_HZ]; see [normalized]).
     * Never read by the motion classifier, the capture decisions or the sampling-rate policy, and not part of any settings hash. Old glasses ignore it.
     */
    val sensorRecHz: Int = 0,
    /**
     * Pause zones, additive: the phone is within a pause zone's approach band (or inside it). While true and the phone link is down the glasses pause capture locally (fail closed),
     * so the camera stops even without the phone. Absent on the wire (an old phone) = false. Old glasses ignore it. The zone itself is never sent: only this flag and `zone:` pause records.
     */
    val zoneApproach: Boolean = false,
    /**
     * Pause zones, additive: the phone could not durably record a zone pause (or cannot read its zones) and holds capture paused in memory; the glasses pause while this is true. Re-sent at every
     * push, so it ends with the first push that omits it. Absent (an old phone) = false. Old glasses ignore it.
     */
    val zoneEnforce: Boolean = false,
    /**
     * v2.0 (Rokid Nexus), additive: who draws the HUD popups and cards. Absent on the wire (an old phone) = [HudHost.PCA]; an unknown value decodes to PCA too (coerceInputValues).
     * The phone sends NEXUS only when the user chose it (default PCA). A glasses build that knows it draws no popups or cards while it is NEXUS (the phone hands them to Nexus instead); an old glasses build ignores it. PROTOCOL_VERSION stays 1.
     */
    val hudHost: HudHost = HudHost.PCA,
)

/** Who draws the HUD popups and cards: PCA's own glasses app (default) or the Rokid Nexus bridge (opt-in, one host at a time). */
@Serializable enum class HudHost { PCA, NEXUS }

/** The recording rates the phone offers (Hz); the sensors run at 50 Hz and more in the classifier's FULL mode, so these are inside their real capability. */
val SENSOR_REC_PRESETS_HZ: List<Int> = listOf(5, 10, 25)

const val MARK_PHOTO_HOLD_MIN_MS = 2_000
const val MARK_PHOTO_HOLD_MAX_MS = 4_000
const val MARK_PHOTO_HOLD_DEFAULT_MS = 3_000
/** What Settings offers for the hold (any value in the range is accepted on the wire; the screen shows the nearest). */
val MARK_PHOTO_HOLD_OPTIONS_MS = listOf(2_000, 2_500, 3_000, 3_500, 4_000)

const val WEAR_PARK_MIN_SEC = 30
const val WEAR_PARK_MAX_SEC = 1_800

/** Wave A3, additive: training label for the motion feature log. */
@Serializable enum class MotionLabel { NONE, SITTING, GLANCING, CHORES, EATING, WALKING, NOT_WORN }

/** Battery wave A, additive: what wakes the camera. */
@Serializable enum class CameraWake { MOTION, SETTLE }

/** Battery wave A, additive: phone to glasses on [Channels.PHONE_CONTEXT]; [inUse] = the phone is being used right now. */
@Serializable
data class PhoneContextMessage(
    val v: Int = PROTOCOL_VERSION,
    val inUse: Boolean,
    val atMs: Long,
    val sessionStartMs: Long = 0L,
)

/** Battery target hours; 0 = Off. */
val BATTERY_TARGET_HOURS_ALLOWED = listOf(0, 3, 4, 5, 6)

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
    return copy(
        workMinutes = w, restMinutes = r, quickLookMs = QUICK_LOOK_MS_ALLOWED.nearest(quickLookMs),
        batteryTargetHours = BATTERY_TARGET_HOURS_ALLOWED.nearest(batteryTargetHours),
        motionSourcesOff = motionSourcesOff.filter { it != "glasses_imu" },
        wearParkUnknownSec = wearParkUnknownSec.coerceIn(WEAR_PARK_MIN_SEC, WEAR_PARK_MAX_SEC),
        wearParkWornSec = wearParkWornSec.coerceIn(WEAR_PARK_MIN_SEC, WEAR_PARK_MAX_SEC),
        markPhotoHoldMs = markPhotoHoldMs.coerceIn(MARK_PHOTO_HOLD_MIN_MS, MARK_PHOTO_HOLD_MAX_MS),
        classifier = com.rokid.context.core.model.signals.ClassifierThresholds.clean(classifier),
        sensorRecHz = sensorRecHz.takeIf { it in SENSOR_REC_PRESETS_HZ } ?: 0,
    )
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
    /** Place Guide PG3, additive: the glasses dismissed (or let expire) the place card titled [title]; the phone clears its copy only if that is still its card (a newer one is kept). [cardId] (additive) names the card instance: with it the phone clears only that instance; without it (an older glasses build) it falls back to the title. An older phone fails to decode it (Malformed) and ignores it. */
    @Serializable @SerialName("dismiss_place") data class DismissPlace(val title: String, val cardId: String? = null) : Command
}

/** `pc_cmd` payload: commands are wrapped so every message carries [v]. */
@Serializable data class CommandEnvelope(
    val v: Int = PROTOCOL_VERSION,
    val command: Command,
    /** Slice 3a, additive: ties a `ListenStart` to the `pc_mark` that opened it (`MarkMessage.listenId`). An old decoder ignores it. */
    val listenId: String? = null,
)

/** Slice 3a: how the glasses run a capture plan. An unknown wire value decodes to [SETTINGS]. */
@Serializable enum class PlanMode { SETTINGS, SHADOW, TRIAL, LIVE }

/** Slice 3a: the state a [CapturePlanAck] reports. An unknown wire value decodes to [REJECTED]. */
@Serializable enum class PlanAckState { REJECTED, STAGED, EFFECTIVE, REVERTED }

/**
 * Slice 3a, phone to glasses on [Channels.CAPTURE_PLAN]. [gen] is the phone's durable desired-state counter; [mode] =
 * SETTINGS with no [planJson] means use settings. [atMs] (phone clock) before [sessionStartMs] = a stale transport copy.
 */
@Serializable
data class CapturePlanMessage(
    val v: Int = PROTOCOL_VERSION,
    val gen: Long,
    val publishId: String? = null,
    val mode: PlanMode = PlanMode.SETTINGS,
    val configHash: String? = null,
    val planJson: String? = null,
    val trialEndsAtMs: Long? = null,
    val trialMaxMs: Long? = null,
    /** The phone install this gen belongs to; echoed in the ack so the phone can tell a stale ack from this install's. */
    val phoneInstall: String = "",
    val atMs: Long = 0,
    val sessionStartMs: Long = 0,
)

/**
 * Slice 3a, glasses to phone on [Channels.CAPTURE_PLAN_ACK]. The phone matches it by [gen], [phoneInstall] and
 * [sessionStartMs] (all echoed from the message). The unsolicited caps ack on session open has gen 0 and no plan.
 */
@Serializable
data class CapturePlanAck(
    val v: Int = PROTOCOL_VERSION,
    val gen: Long,
    val publishId: String? = null,
    val mode: PlanMode = PlanMode.SETTINGS,
    val effectiveHash: String? = null,
    val state: PlanAckState = PlanAckState.REJECTED,
    val reason: String? = null,
    val planSchemaMax: Int = 1,
    val caps: List<String> = emptyList(),
    val phoneInstall: String = "",
    val atMs: Long = 0,
    val sessionStartMs: Long = 0,
)

/**
 * Slice 3a, glasses to phone on [Channels.MARK]. [atMs] and [atElapsedMs] are the occurrence time, never a freshness
 * test: a queued mark older than [sessionStartMs] is still accepted.
 */
@Serializable
data class MarkMessage(
    val v: Int = PROTOCOL_VERSION,
    val epoch: String,
    val seq: Long,
    val atMs: Long,
    val atElapsedMs: Long,
    val bootCount: Int = -1,
    val sessionStartMs: Long = 0,
    val src: String = "tap",
    val listen: Boolean = false,
    val listenId: String? = null,
    val undo: Long? = null,
    val deletionCounter: Long = -1,
    /** v0.9.6 slice C, additive: true = this glasses build will take a photo for the mark and report it (a frame with [FrameMeta.markRef], or a [MarkPhotoResult]). Absent = an old glasses build (reason `no_photo_support`). */
    val photo: Boolean = false,
)

/** v0.9.6 slice C, glasses to phone on [Channels.MARK_PHOTO]: the photo of mark `(epoch, seq)` could not be taken; [reason] is a [MarkPhotoReason] token, never text. */
@Serializable data class MarkPhotoResult(val v: Int = PROTOCOL_VERSION, val epoch: String, val seq: Long, val reason: String)

/**
 * v0.9.7 slice D, phone to glasses on [Channels.MARK_PHOTO_REQUEST]: take the photo of the phone-origin mark [ref] (a [MarkRef] `"<epoch>:<seq>"` the phone chose; the epoch is `phone`).
 * The glasses answer exactly like a tap mark: a frame carrying `FrameMeta.markRef = ref`, or a [MarkPhotoResult] with a reason. Old glasses ignore it.
 */
@Serializable data class MarkPhotoRequest(
    val v: Int = PROTOCOL_VERSION, val ref: String,
    /** Phone clock when it was sent (informational: the glasses do not judge freshness by it) and the phone clock of its session start: the glasses refuse a request of another or an unknown session (0). */
    val atMs: Long = 0, val sessionStartMs: Long = 0,
)

/** The bound of the phone-origin photo request on the glasses: by the glasses' own clock, not by phone time or cache size. */
object MarkPhotoRequestLimits {
    const val PER_MINUTE = 6
}

/** The tokens of why a mark has no photo (stored on the label, shown to the user as words by the phone). A token the phone does not know is shown as is. */
object MarkPhotoReason {
    const val PENDING = "pending"
    const val OK = "ok"
    const val NO_PHOTO_SUPPORT = "no_photo_support"
    const val RETRO = "retro_no_photo"
    const val PHONE_MARK = "phone_mark_no_photo"
    const val TIMEOUT = "photo_timeout"
    const val NO_CAPTURE_SERVICE = "no_capture_service"
    const val CAMERA_BUSY = "camera_busy"
    const val CAMERA_BIND_FAILED = "camera_bind_failed"
    const val NO_FRAME = "no_frame"
    const val HEAT = "heat"
    const val NOT_STORED = "not_stored"
    const val CANCELLED = "cancelled"
    /** v0.9.7 slice D (phone-origin marks, decided on the phone before any request): */
    const val GLASSES_NOT_CONNECTED = "glasses_not_connected"
    const val GLASSES_NOT_WORN = "glasses_not_worn"
    /** v0.9.7 slice D review: the glasses refused a phone request (more than [MarkPhotoRequestLimits.PER_MINUTE] in a minute) / got it after its session closed. */
    const val RATE_LIMITED = "rate_limited"
    const val SESSION_CLOSED = "session_closed"
    const val UNKNOWN_SESSION = "unknown_session"
    /** Pause zones: a mark photo is refused while a `zone:` pause record is active (photographing is not allowed there). */
    const val PAUSE_ZONE = "pause_zone"
    /** Only a short token is ever stored: a wild value from the wire is cut and cleaned. */
    fun clean(raw: String): String = raw.filter { it.isLetterOrDigit() || it == '_' }.take(32).ifEmpty { NO_FRAME }
}

/** Slice 3a, phone to glasses on [Channels.MARK_ACK]: the marks of [epoch] with these [seqs] are stored. */
@Serializable data class MarkAck(val v: Int = PROTOCOL_VERSION, val epoch: String, val seqs: List<Long> = emptyList())

/** Slice 3a, phone to glasses on [Channels.TRACE_CTL]: the "collect for tuning" session, deletion tombstones and the send request. */
@Serializable
data class TraceControl(
    val v: Int = PROTOCOL_VERSION,
    val collect: Boolean = false,
    val collectUntilMs: Long? = null,
    val installId: String = "",
    val deletionCounter: Long = 0,
    val tombstones: List<String> = emptyList(),
    val forgetFromMs: Long? = null,
    val forgetToMs: Long? = null,
    val send: Boolean = false,
    val atMs: Long = 0,
    val sessionStartMs: Long = 0,
)

/** Slice 3a, glasses to phone on [Channels.TRACE_CHUNK]: one base64 gzip chunk of a trace. */
@Serializable
data class TraceChunk(
    val v: Int = PROTOCOL_VERSION,
    val traceId: String,
    val kind: String = "",
    val seq: Int = 0,
    val count: Int = 0,
    val installId: String = "",
    val deletionCounter: Long = 0,
    val sha256: String = "",
    val b64: String = "",
    val atMs: Long = 0,
    val sessionStartMs: Long = 0,
)

/** Slice 3a, phone to glasses on [Channels.TRACE_ACK]: the trace was stored ([ok]) or not ([reason]). */
@Serializable
data class TraceAck(
    val v: Int = PROTOCOL_VERSION,
    val traceId: String,
    val ok: Boolean,
    val reason: String? = null,
    val atMs: Long = 0,
    val sessionStartMs: Long = 0,
)

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
    /** Fix wave H4, additive: how many times Rokid force-stopped the glasses app (counts only; diagnostics). */
    val rokidCloses: Int = 0,
    /** Fix wave H4, additive: seconds between the last heartbeat and the latest restart after such a stop. */
    val lastRestartGapSec: Int? = null,
    /** Wave 3, additive (counts only): force-stops whose caller could not be verified (not counted in [rokidCloses]). */
    val unverifiedCloses: Int = 0,
    /** Wave 3, additive (counts only): force-stops by a verified non-Rokid caller (Settings, adb). */
    val otherCloses: Int = 0,
    /** Fix wave I, additive: glasses clock of the wearer's last deliberate exit (the third double-tap); repeats a `pc_user_exit` that may have been lost. */
    val userExitAtMs: Long? = null,
    /** Fix wave I, additive: the app went to icon mode but "display over other apps" is off, so no icon shows (capture runs unseen). */
    val iconBlocked: Boolean = false,
    /**
     * LGC6-min, additive: the glasses' diagnostic flag state, one of `unprovisioned`, `armed_no_epoch`, `enabled`, `disabled` (a fixed vocabulary, never free text).
     * Null from an older glasses; a phone treats null or an unknown word as "unknown".
     */
    val diagFlagState: String? = null,
    /** LGC6-min, additive: why no trace can start right now, one fixed word (`none`, `gate_off`, `armed_no_epoch`, ...); never free text. Null from an older glasses. */
    val traceBlocker: String? = null,
    /** v0.9.7 slice D, additive: this glasses build answers a `pc_mark_photo_request`. False/absent from an older glasses: the phone records `no_photo_support` at once instead of waiting. */
    val markPhotoRequest: Boolean = false,
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
    /** v0.9.6 slice C, additive: `"<epoch>:<seq>"` of the mark this photo was taken for (a mark touches capture). Absent = an ordinary frame; an old phone ignores it. */
    val markRef: String? = null,
)

@Serializable data class FrameAck(val v: Int = PROTOCOL_VERSION, val frameId: String, val ok: Boolean, val reason: String? = null)
