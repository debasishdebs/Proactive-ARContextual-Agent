package com.rokid.context.glasses.link

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.hud.render.HudConnection
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.MarkRef
import com.rokid.context.core.model.AutoOpen
import com.rokid.context.core.model.UserExit
import com.rokid.context.core.model.PhoneContextMessage
import com.rokid.context.core.model.MotionHint
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.context.MotionContextMessage
import com.rokid.context.core.model.WearMessage
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.HelloRequest
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


/** Pure: what the HUD shows about the phone link (spec §3.6; adapted from Live Fit `connectionFor`). */
fun connectionFor(everReceived: Boolean, online: Boolean, outdated: Boolean, sinceStartMs: Long): HudConnection = when {
    outdated -> HudConnection.OUTDATED
    online -> HudConnection.LIVE
    everReceived || sinceStartMs >= 12_000 -> HudConnection.OFFLINE
    else -> HudConnection.CONNECTING
}

/**
 * Glasses side of the CXR link (spec §3.1): renders phone frames and sends commands, `pc_hello` and frame chunks.
 * Session-open is tracked locally (spec §1.7): set on the first phone message of a session (and then `pc_hello` goes
 * out), cleared on a bridge disconnect, a silent phone, a version mismatch or when the activity exits. Frames go out
 * only while it is open; a send result of 0 never means delivered, only `pc_frame_ack` does.
 */
class HudController(
    private val scope: CoroutineScope,
    private val bridge: GlassesBridge,
    private val cache: HudSettingsCache,
    private val hello: () -> Hello,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val liveness = LivenessMonitor(clock)
    private val startedAt = clock()
    @Volatile private var everReceived = false
    @Volatile private var outdated = false
    /** Latched by [onActivityExit] until [onActivityStart]: the phone's heartbeat must not reopen the session (spec §1.7). */
    @Volatile private var exited = false

    private val _frame = MutableStateFlow<StateFrame?>(null)
    val frame: StateFlow<StateFrame?> = _frame
    private val _settings = MutableStateFlow(cache.read())
    val settings: StateFlow<HudSettings> = _settings
    private val _connection = MutableStateFlow(HudConnection.CONNECTING)
    val connection: StateFlow<HudConnection> = _connection
    private val _sessionOpen = MutableStateFlow(false)
    val sessionOpen: StateFlow<Boolean> = _sessionOpen

    /** All three are called on a bridge thread. */
    @Volatile var onFrameAck: (FrameAck) -> Unit = {}
    @Volatile var onSessionOpened: () -> Unit = {}
    /**
     * Slice 3a (S74): the unsolicited `pc_capture_plan_ack` sent on every session open and every hello request: gen 0, no plan, no
     * install, no session, no reason; only the caps. Task 11 must NOT put the applied plan, gen or install in it: the phone
     * classifies that shape as the caps announcement, anything else is matched like an ordinary ack. (Real acks come from
     * `PlanReceiver`.)
     */
    @Volatile var capsAck: () -> com.rokid.context.core.model.CapturePlanAck =
        { com.rokid.context.core.model.CapturePlanAck(gen = 0, caps = com.rokid.context.capture.plan.PlanReceiver.CAPS_EXPR) }
    /** The state of the plan held (null = none ever): sent as a SEPARATE ack after the caps announcement, which stays gen 0 and caps-only (S74). */
    @Volatile var statusAck: () -> com.rokid.context.core.model.CapturePlanAck? = { null }
    /** `pc_capture_plan` (slice 3a): a plan message of this session; the runtime's plan controller answers with acks through [sendPlanAck]. */
    @Volatile var onCapturePlan: (com.rokid.context.core.model.CapturePlanMessage) -> Unit = {}
    /** `pc_trace_ctl` (slice 3a): collect on/off, the deletion epoch; the IMU trace recorder consumes it. */
    @Volatile var onTraceControl: (com.rokid.context.core.model.TraceControl) -> Unit = {}
    /** `pc_mark_ack` (slice 3a): the phone stored these marks; the outbox drops them. */
    @Volatile var onMarkAck: (com.rokid.context.core.model.MarkAck) -> Unit = {}
    /** v0.9.7 slice D: the phone asks for the photo of a phone-origin mark. */
    @Volatile var onMarkPhotoRequest: (com.rokid.context.core.model.MarkPhotoRequest) -> Unit = {}
    /** The phone's session start (phone clock) as its last accepted message named it; 0 = none yet. A photo request of another session is refused. */
    @Volatile private var phoneSessionStartMs = 0L
    /** Codex wear r2-r4: the phone hub epoch and session number the last applied `pc_wear` named, and its newest send order. They survive a HUD session close on purpose (a retired session's late message must stay dropped). Under [ctxLock]. */
    private var lastWearEpoch = 0L
    private var lastWearSessionNo = 0L
    private var lastWearSeq = 0L
    /** The session is open and the phone is talking (session open, or a hello request on an open session): re-send unacked marks. */
    @Volatile var onMarksDue: () -> Unit = {}
    /** `pc_wear`: the Rokid SDK's wear state from the phone; null = the session closed (unknown again). */
    @Volatile var onWear: (Boolean?) -> Unit = {}
    /** `pc_wear` wearCheck (v1.0 wear fallback): the glasses' system wear check as the phone read it; null = not known / an old phone. */
    @Volatile var onWearCheck: (Boolean?) -> Unit = {}
    /** `pc_phone_ctx`: whether the phone is in use (screen on, unlocked); null = the session closed (unknown again). */
    @Volatile var onPhoneUse: (Boolean?) -> Unit = {}
    /** `pc_auto_open` (fix wave I): the phone reopened the app by itself; a launcher-looking start of the last seconds was really that. */
    @Volatile var onAutoOpen: () -> Unit = {}
    /**
     * Each phone push: `pc_settings` as sent; a `pc_state` pause list as the last settings with those pauses, inexact
     * (a frame carries no clock flag) and with no resumes.
     */
    @Volatile var onPhoneSettings: (HudSettings) -> Unit = {}
    /** Only a real `pc_settings` push (not the cached copy a `pc_state` pause list re-applies through [onPhoneSettings]): what the persisted diagnostic request is driven from (LGC4). */
    @Volatile var onPhoneSettingsPush: (HudSettings) -> Unit = {}

    /** `pc_motion_ctx` (wave B): a fresh message of this session; null = the session closed (the gate resets). */
    @Volatile var onMotionContext: (MotionContextMessage?) -> Unit = {}
    /** The newest accepted `atMs` of this session (phone clock); an older message is a late arrival and is dropped. */
    private var lastCtxAtMs = Long.MIN_VALUE // guarded by ctxLock
    private val ctxLock = Any()

    fun start() {
        bridge.subscribe(Channels.STATE) { onState(it) }
        bridge.subscribe(Channels.SETTINGS) { onSettings(it) }
        bridge.subscribe(Channels.FRAME_ACK) { onAck(it) }
        bridge.subscribe(Channels.HELLO_REQUEST) { onHelloRequest(it) }
        bridge.subscribe(Channels.WEAR) { text -> (Wire.decode<WearMessage>(text) as? Decoded.Ok)?.value?.let(::onWearMessage) }
        bridge.subscribe(Channels.PHONE_CONTEXT) { text -> (Wire.decode<PhoneContextMessage>(text) as? Decoded.Ok)?.value?.let(::onPhoneContextMessage) }
        bridge.subscribe(Channels.MOTION_CONTEXT) { text -> (Wire.decode<MotionContextMessage>(text) as? Decoded.Ok)?.value?.let(::onMotionContextMessage) }
        bridge.subscribe(Channels.CAPTURE_PLAN) { text -> (Wire.decode<com.rokid.context.core.model.CapturePlanMessage>(text) as? Decoded.Ok)?.value?.let(::onPlanMessage) }
        bridge.subscribe(Channels.TRACE_CTL) { text -> (Wire.decode<com.rokid.context.core.model.TraceControl>(text) as? Decoded.Ok)?.value?.let { m -> try { onTraceControl(m) } catch (e: Exception) { PcLog.w(TAG, "trace control failed: ${e.javaClass.simpleName}") } } }
        bridge.subscribe(Channels.MARK_ACK) { text -> (Wire.decode<com.rokid.context.core.model.MarkAck>(text) as? Decoded.Ok)?.value?.let { a -> try { onMarkAck(a) } catch (e: Exception) { PcLog.w(TAG, "mark ack failed: ${e.javaClass.simpleName}") } } }
        bridge.subscribe(Channels.MARK_PHOTO_REQUEST) { text -> (Wire.decode<com.rokid.context.core.model.MarkPhotoRequest>(text) as? Decoded.Ok)?.value?.let { r -> try { acceptMarkPhotoRequest(r) } catch (e: Exception) { PcLog.w(TAG, "mark photo request failed: ${e.javaClass.simpleName}") } } }
        bridge.subscribe(Channels.AUTO_OPEN) { text -> if (Wire.decode<AutoOpen>(text) is Decoded.Ok) { PcLog.i(TAG, "phone says: automatic reopen"); onAutoOpen() } }
        bridge.setConnectionListener(connected = { PcLog.i(TAG, "phone connected") }, disconnected = { closeSession("bt disconnected") })
        scope.launch { while (isActive) { refreshConnection(); expireAnswer(); delay(1_000) } }
    }

    /**
     * rc1 fix (2026-11-13): the answer card is phone state and is cleared explicitly and idempotently here, whatever the presenter does:
     * - a frame whose overlay is not that Answer (the phone's hold ended, a cancel, a withdrawal) replaces it (as before);
     * - a frame naming its ask in `withdrawnAskIds` (a Forget redacted it) drops it, even when the frame itself still carries it;
     * - a session close (BT lost, phone silent for 25 s, exit) drops it: a reconnect shows only what the phone's next frame says, never a stale answer;
     * - the glasses' own bounded hold [ANSWER_MAX_MS] ends one the phone keeps sending (a backstop: the phone's worst case is speech plus 30 s).
     */
    private val answerLock = Any()
    /**
     * Codex r1 (P1): moves at every session close, under [answerLock] with the clear. A `pc_state` reads it on receipt and is published only under the same lock and only
     * if it has not moved: a frame of a closed (or since reopened) session never lands after the close, so a close can never be undone by a delayed publication.
     */
    @Volatile private var sessionGen = 0L
    /** Tests only: runs right after a frame's receipt, before its decode and the session open (the race window of Codex r1/r2 P1). */
    @Volatile internal var afterReceipt: () -> Unit = {}
    /**
     * Codex r2 (P1): every ask id a frame named in `withdrawnAskIds` (newest last, bounded by [WITHDRAWN_SEEN_MAX]). An Answer of one of them is never published again, in this
     * session or a later one: ask ids are unique, and a withdrawn ask never comes back. Guarded by [answerLock].
     */
    private val withdrawnSeen = LinkedHashSet<String>()
    /** The Answer on the card (without its speaking flag and undo), and when the glasses first saw it (glasses clock). */
    private var answerKey: Overlay.Answer? = null
    private var answerSinceMs = 0L
    /** An Answer that ran past [ANSWER_MAX_MS] or was withdrawn: further frames carrying it show without it until the phone sends something else. */
    private val dropped = LinkedHashSet<Overlay.Answer>()

    private fun keyOf(a: Overlay.Answer) = a.copy(speaking = false, undoProposalId = null)

    /**
     * Publishes a received frame (bridge thread) atomically with respect to [closeSession]: a newer session generation drops it whole (it is stale); a session that is not open
     * (the activity exited) gets it without an answer card (a closed session holds none).
     */
    private fun publish(f: StateFrame, gen: Long) = synchronized(answerLock) {
        // the withdrawals a frame names count even when the frame itself is stale
        f.withdrawnAskIds.forEach { withdrawnSeen.remove(it); withdrawnSeen += it }
        while (withdrawnSeen.size > WITHDRAWN_SEEN_MAX) withdrawnSeen.remove(withdrawnSeen.first())
        if (gen != sessionGen) { PcLog.i(TAG, "pc_state of a closed session dropped"); return@synchronized }
        val shown = cleared(f)
        _frame.value = if (!_sessionOpen.value && shown.overlay is Overlay.Answer) shown.copy(overlay = null) else shown
    }

    /** Applies the rules above to an incoming frame; called with [answerLock] held. */
    private fun cleared(f: StateFrame): StateFrame {
        val prev = (_frame.value?.overlay as? Overlay.Answer)?.let(::keyOf)
        val withdrawn: Set<String> = withdrawnSeen
        if (prev?.askId != null && prev.askId in withdrawn) { dropped += prev; PcLog.milestone(TAG, "answer card cleared: redaction") }
        val a = (f.overlay as? Overlay.Answer)?.let(::keyOf)
        if (a == null) { answerKey = null; dropped.clear(); return f }
        if (a.askId != null && a.askId in withdrawn) dropped += a
        if (a in dropped) return f.copy(overlay = null)
        if (a != answerKey) { answerKey = a; answerSinceMs = clock() }
        return f
    }

    /**
     * The bounded glasses hold (every second, with the liveness check). Key-independent (Codex r1): whatever answer is on the card goes once [ANSWER_MAX_MS] passed since an
     * answer was last published as new; a card whose key is unknown (never expected) counts from that time too, so it can never outlive the bound.
     */
    private fun expireAnswer() = synchronized(answerLock) {
        val f = _frame.value ?: return@synchronized
        val a = (f.overlay as? Overlay.Answer)?.let(::keyOf) ?: return@synchronized
        if (clock() - answerSinceMs < ANSWER_MAX_MS) return@synchronized
        dropped += a; answerKey = null
        _frame.value = f.copy(overlay = null)
        PcLog.milestone(TAG, "answer card cleared: glasses hold ended")
    }

    /** A closed session holds no answer: the card goes now, and the next session shows only its own frames. Bumps [sessionGen] in the same critical section. */
    private fun dropAnswerOnClose() = synchronized(answerLock) {
        sessionGen++
        val f = _frame.value ?: return@synchronized
        if (f.overlay !is Overlay.Answer) return@synchronized
        answerKey = null
        _frame.value = f.copy(overlay = null)
        PcLog.milestone(TAG, "answer card cleared: session closed")
    }

    /**
     * v0.9.7 slice D: a photo request runs only inside an OPEN session of a live (not exited) app, and only when it names the phone session this session belongs to (the session start of the
     * phone's other messages). Anything else is ignored and, best effort, answered `session_closed` (a closed session usually cannot carry it; nothing is sent after the wearer exited).
     */
    private fun acceptMarkPhotoRequest(r: com.rokid.context.core.model.MarkPhotoRequest) {
        val known = phoneSessionStartMs
        // FAIL CLOSED: the phone session identity must be known (a wear/plan/phone-context message of this session arrived) and the request must name exactly it
        val sameSession = known != 0L && r.sessionStartMs != 0L && r.sessionStartMs == known
        if (!exited && _sessionOpen.value && sameSession) { onMarkPhotoRequest(r); return }
        PcLog.i(TAG, "pc_mark_photo_request outside its session ignored")
        if (exited || !MarkRef.isValid(r.ref)) return
        val seq = r.ref.substringAfterLast(':').toLongOrNull() ?: return
        try { bridge.send(Channels.MARK_PHOTO, Wire.encode(com.rokid.context.core.model.MarkPhotoResult(epoch = r.ref.substringBeforeLast(':'), seq = seq, reason = if (_sessionOpen.value && known == 0L) com.rokid.context.core.model.MarkPhotoReason.UNKNOWN_SESSION else com.rokid.context.core.model.MarkPhotoReason.SESSION_CLOSED))) } catch (e: Exception) { /* best effort */ }
    }

    private fun onPhoneContextMessage(m: PhoneContextMessage) {
        if (m.atMs < m.sessionStartMs) { PcLog.i(TAG, "stale pc_phone_ctx ignored"); return }
        phoneSessionStartMs = m.sessionStartMs
        onPhoneUse(m.inUse)
    }

    private fun onPlanMessage(m: com.rokid.context.core.model.CapturePlanMessage) {
        // A transport copy older than this session is stale; the phone re-sends its current gen on the new session.
        if (m.atMs < m.sessionStartMs) { PcLog.i(TAG, "stale pc_capture_plan ignored"); return }
        phoneSessionStartMs = m.sessionStartMs
        onCapturePlan(m)
    }

    private fun onMotionContextMessage(m: MotionContextMessage) {
        if (m.atMs < m.sessionStartMs) { PcLog.i(TAG, "stale pc_motion_ctx ignored"); return }
        // One lock for the session check, the ordering check, the gate update and the close reset: a delayed older
        // message can neither pass before a newer revoke and land after it, nor outlive the session close. The gate
        // callback is non-blocking, so holding the lock across it is safe.
        synchronized(ctxLock) {
            if (!_sessionOpen.value) { PcLog.i(TAG, "pc_motion_ctx of a closed session ignored"); return }
            if (m.atMs < lastCtxAtMs) { PcLog.i(TAG, "older pc_motion_ctx ignored"); return }
            lastCtxAtMs = m.atMs
            onMotionContext(m)
        }
    }

    /** Fix wave I1: a wear value the phone saw before its current session began is stale (a reconnect must never resurrect it). */
    private fun onWearMessage(m: WearMessage) {
        if (m.atMs < m.sessionStartMs) { PcLog.i(TAG, "stale pc_wear ignored"); return }
        // Codex wear r3/r4: the order is kept per WEAR identity, not through [phoneSessionStartMs] that every channel overwrites, and never through the wall clock. Within one phone
        // epoch a lower session number is a retired session's late message (dropped, also after the HUD session closed), a higher one starts the numbering again, an equal one is
        // ordered by seq. A closed HUD session fences nothing else, so a silence recovery or reconnect of the same phone session keeps working. A new epoch (the phone's hub
        // restarted) or an old phone (epoch 0) cannot be ordered: it is applied.
        synchronized(ctxLock) {
            if (m.phoneEpoch != 0L && m.phoneEpoch == lastWearEpoch) {
                if (m.sessionNo < lastWearSessionNo) { PcLog.i(TAG, "pc_wear of a retired session ignored"); return }
                if (m.sessionNo > lastWearSessionNo) { lastWearSessionNo = m.sessionNo; lastWearSeq = 0L }
                else if (m.seq != 0L && m.seq < lastWearSeq) { PcLog.i(TAG, "older pc_wear ignored"); return }
            } else { lastWearEpoch = m.phoneEpoch; lastWearSessionNo = m.sessionNo; lastWearSeq = 0L }
            if (m.seq != 0L) lastWearSeq = m.seq
            phoneSessionStartMs = m.sessionStartMs
            onWearCheck(m.wearCheck)
            // worn = null: the message only carries the wear check; the real wear value is left alone (onWear(null) means a closed session)
            m.worn?.let { onWear(it) }
        }
    }

    private fun onState(text: String) {
        // Codex r2: the generation is read at RECEIPT, before the decode and the session open; a close at any later point moves it and this frame is then dropped whole
        val gen = sessionGen
        afterReceipt()
        when (val d = Wire.decode<StateFrame>(text)) {
            is Decoded.Ok -> {
                phoneMessage()
                publish(d.value, gen)
                (d.value.capture as? CaptureState.Paused)?.let { onPhoneSettings(_settings.value.copy(pauses = it.records, pausesExact = false, resumedPauseIds = emptyList())) }
            }
            is Decoded.VersionMismatch -> markOutdated(d.remote)
            is Decoded.Malformed -> PcLog.w(TAG, "bad pc_state (${d.reason})") // a class name only: the text could quote memory lines
        }
        refreshConnection()
    }

    private fun onSettings(text: String) {
        when (val d = Wire.decode<HudSettings>(text)) {
            is Decoded.Ok -> {
                phoneMessage()
                val v = HudSettingsCache.validQuiet(d.value)
                _settings.value = v
                cache.write(v)
                onPhoneSettings(v)
                onPhoneSettingsPush(v)
            }
            is Decoded.VersionMismatch -> markOutdated(d.remote)
            is Decoded.Malformed -> PcLog.w(TAG, "bad pc_settings (${d.reason})")
        }
        refreshConnection()
    }

    /** The phone holds a frame on a stale clock offset and asks for a fresh `pc_hello` (same path as the session hello). */
    private fun onHelloRequest(text: String) {
        when (val d = Wire.decode<HelloRequest>(text)) {
            is Decoded.Ok -> {
                if (exited) return
                val wasOpen = _sessionOpen.value
                phoneMessage() // opens the session (and says hello) if this is its first message
                if (wasOpen) { sendCapsAck(); sendHello(); dueMarks() } // a new phone session on a long-lived glasses session needs the caps again (also retries a failed send)
            }
            is Decoded.VersionMismatch -> markOutdated(d.remote)
            is Decoded.Malformed -> PcLog.w(TAG, "bad pc_hello_request (${d.reason})")
        }
        refreshConnection()
    }

    private fun onAck(text: String) { (Wire.decode<FrameAck>(text) as? Decoded.Ok)?.value?.let { onFrameAck(it) } }

    /** First message of a session: say hello (clock, battery, buffer, pauses) before any frame is sent. */
    private fun phoneMessage() {
        everReceived = true
        outdated = false
        liveness.onFrame()
        if (!exited && _sessionOpen.compareAndSet(false, true)) {
            PcLog.i(TAG, "session open")
            sessionStartMs = clock()
            sendHello()
            sendCapsAck()
            try { statusAck()?.let { bridge.send(Channels.CAPTURE_PLAN_ACK, Wire.encode(it)) } } catch (e: Exception) { PcLog.w(TAG, "plan status ack failed: ${e.javaClass.simpleName}") }
            onSessionOpened()
            dueMarks()
        }
    }

    private fun dueMarks() { try { onMarksDue() } catch (e: Exception) { PcLog.w(TAG, "marks due failed: ${e.javaClass.simpleName}") } }

    /** Plumbing only; the phone shows "too old" for a peer that never sends it. A failed send or a throwing [capsAck] is retried at the next hello request. */
    private fun sendCapsAck() {
        val ok = runCatching { bridge.send(Channels.CAPTURE_PLAN_ACK, Wire.encode(capsAck())) == 0 }.getOrDefault(false)
        if (!ok) PcLog.w(TAG, "caps ack not sent")
    }

    private fun markOutdated(remote: Int?) {
        PcLog.w(TAG, "phone protocol v=$remote, glasses v=$PROTOCOL_VERSION")
        outdated = true
        closeSession("version mismatch")
    }

    private fun closeSession(why: String) {
        liveness.onTransportDisconnected()
        synchronized(ctxLock) {
            if (_sessionOpen.compareAndSet(true, false)) { PcLog.i(TAG, "session closed: $why"); phoneSessionStartMs = 0L; onWear(null); onPhoneUse(null); lastCtxAtMs = Long.MIN_VALUE; onMotionContext(null) }
        }
        dropAnswerOnClose()
        refreshConnection()
    }

    /** The activity is going away: the phone's CUSTOM_APP session closes with it (spec §1.7). */
    fun onActivityExit() { exited = true; closeSession("activity exit") }

    /** The activity (re)started: phone messages may open a session again. */
    fun onActivityStart() { exited = false }

    fun send(command: Command): Boolean = bridge.send(Channels.CMD, Wire.encode(CommandEnvelope(command = command))) == 0

    /** Wall clock of this session's opening: `MarkMessage.sessionStartMs` (transport freshness; the phone never drops a mark for it). */
    @Volatile private var sessionStartMs = 0L

    /** `pc_mark` (slice 3a): only while the session is open; a false result keeps the mark queued. Occurrence time is in the message, never rewritten. */
    fun sendMark(m: com.rokid.context.core.model.MarkMessage): Boolean =
        _sessionOpen.value && bridge.send(Channels.MARK, Wire.encode(m.copy(sessionStartMs = sessionStartMs))) == 0

    /** `pc_mark_photo` (v0.9.6 slice C): the photo of a mark could not be taken; only while the session is open, best effort (a lost report is the phone's photo timeout). */
    fun sendMarkPhoto(r: com.rokid.context.core.model.MarkPhotoResult): Boolean = _sessionOpen.value && bridge.send(Channels.MARK_PHOTO, Wire.encode(r)) == 0

    /** `pc_capture_plan_ack`: only while the session is open (a closed session gets the status ack of [capsAck] on its next open). */
    fun sendPlanAck(a: com.rokid.context.core.model.CapturePlanAck): Boolean = _sessionOpen.value && bridge.send(Channels.CAPTURE_PLAN_ACK, Wire.encode(a)) == 0

    /** `pc_motion_hint`: the glasses classifier state for the phone's hub, only while the session is open. */
    fun sendMotionHint(h: MotionHint): Boolean = _sessionOpen.value && bridge.send(Channels.MOTION_HINT, Wire.encode(h)) == 0

    /** `pc_user_exit`: the wearer exited the app on purpose; the phone must not reopen it. */
    fun sendUserExit(): Boolean = bridge.send(Channels.USER_EXIT, Wire.encode(UserExit())) == 0

    fun sendHello(): Boolean = bridge.send(Channels.HELLO, Wire.encode(hello())) == 0

    /** pc_audio: one Base64 PCM chunk, only while the session is open (a send result of 0 is not delivery). */
    fun sendAudio(base64: String): Boolean = _sessionOpen.value && bridge.send(Channels.AUDIO, base64) == 0

    /** pc_listen_end (spec §1.5): the end-of-utterance marker, only while the session is open; its body carries only the protocol version (preflight F3). */
    fun sendListenEnd(): Boolean = _sessionOpen.value && bridge.send(Channels.LISTEN_END, """{"v":$PROTOCOL_VERSION}""") == 0

    /** -1 while the session is closed; otherwise CXR's code (0 = handed over, not delivered). */
    fun sendFrameChunk(chunk: FrameChunk): Int =
        if (!_sessionOpen.value) -1 else bridge.send(Channels.FRAME, Wire.encode(chunk.meta), chunk.chunk)

    private fun refreshConnection() {
        if (_sessionOpen.value && !liveness.isOnline()) { closeSession("phone silent"); return }
        _connection.value = connectionFor(everReceived, liveness.isOnline(), outdated, clock() - startedAt)
    }

    companion object {
        const val TAG = "PcHud"
        /** The glasses' own bound on one answer card (the phone's is its speech, watchdog-bounded, plus a 30 s hold): 5 min. */
        const val ANSWER_MAX_MS = 300_000L
        /** How many withdrawn ask ids the glasses remember (the phone names at most 20 per frame, each for a minute). */
        const val WITHDRAWN_SEEN_MAX = 64
    }
}
