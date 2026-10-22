package com.rokid.context.voice

import com.rokid.context.core.model.Overlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * One voice ask at a time (spec §5.1): glasses PCM or the phone mic → on-device recognizer → final transcript (null
 * final = last partial) → [Answerer] → Answer overlay + TTS → cleared after [VoiceTimings.answerHoldMs]. Every method
 * runs on [scope]'s thread (Main in the app) and every recognizer/speaker callback arrives there. A generation number
 * makes callbacks of a replaced ask harmless. Voice and answer text is never logged: [log] gets event names only.
 *
 * Bounds: [VoiceTimings.maxListenMs] (20 s) caps audio intake for any listen; the [VoiceTimings.finalTimeoutMs] (6 s)
 * final wait sits on top of it (worst case 26 s), and a recognizer that never sends a final is then cancelled.
 * Thinking is bounded by [VoiceTimings.answerTimeoutMs] (60 s).
 *
 * Every ask ends in exactly one `outcome=<[VoiceOutcome]> source=<[VoiceSource]>` log line (BUG-R3-3: a "couldn't
 * hear" must never look like a hang); the code is the reason, the text stays out.
 */
class VoicePipeline(
    private val scope: CoroutineScope,
    private val engine: SpeechEngine,
    private val capabilities: StateFlow<VoiceCapabilities>,
    private val language: () -> SpeechLanguage,
    private val answerer: Answerer,
    private val speaker: Speaker,
    private val bluetoothMedia: () -> Boolean,
    private val onFallbackSuspected: () -> Unit = {},
    private val timings: VoiceTimings = VoiceTimings(),
    private val log: (String) -> Unit = {},
    private val interceptor: UtteranceInterceptor = UtteranceInterceptor.PASS_THROUGH,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    private val _overlay = MutableStateFlow<Overlay?>(null)
    /** `StateFrame.overlay` for voice (the hub sends it): phone-mic asks show only their Answer on the HUD. */
    val overlay: StateFlow<Overlay?> = _overlay.asStateFlow()
    private val _phone = MutableStateFlow(PhoneVoiceState(status = capabilities.value.phone))
    val phone: StateFlow<PhoneVoiceState> = _phone.asStateFlow()

    private var gen = 0L
    private var session: SttSession? = null
    private var timer: Job? = null
    private var maxTimer: Job? = null
    private var work: Job? = null
    private var hudToast: String? = null
    private var hudToastJob: Job? = null
    private var phoneError: String? = null
    private var phoneErrorJob: Job? = null
    private var lastPhoneAskId: String? = null

    init { scope.launch { capabilities.collect { publish() } } }

    fun glassesListenStart() {
        capabilities.value.askBlockedReason?.let { reason ->
            log("glasses ask blocked"); outcome(VoiceOutcome.BLOCKED, VoiceSource.GLASSES); toastHud(reason); return
        }
        if (busyWith(VoiceSource.PHONE)) { outcome(VoiceOutcome.BUSY, VoiceSource.GLASSES); toastHud(VoiceCopy.PHONE_BUSY_HUD); return }
        begin(VoiceSource.GLASSES)
    }

    fun glassesAudio(pcm: ByteArray) {
        val s = _state.value
        if (s !is VoiceState.Listening || s.source != VoiceSource.GLASSES || s.ended) return
        session?.feed(pcm)
        armIdle(gen)
    }

    fun glassesListenEnd() {
        val s = _state.value
        if (s is VoiceState.Listening && s.source == VoiceSource.GLASSES) { log("listen end GLASSES"); endListening(gen) }
    }

    /** Double-tap while listening or thinking, or a dismissed Answer: stop everything, answer and say nothing. */
    fun glassesCancel() {
        val s = _state.value
        if (s.source == VoiceSource.GLASSES || s is VoiceState.Answer) reset("glasses cancel", VoiceOutcome.CANCELLED)
    }

    /** The glasses link dropped: an open utterance can't complete. A question already asked still lands in the feed. */
    fun linkLost() {
        val s = _state.value
        if (s is VoiceState.Listening && s.source == VoiceSource.GLASSES) reset("link lost", VoiceOutcome.LINK_LOST)
    }

    fun phoneStart() {
        showPhoneError(null)
        val status = capabilities.value.phone
        if (status != VoiceStatus.SUPPORTED) {
            outcome(VoiceOutcome.BLOCKED, VoiceSource.PHONE); showPhoneError(VoiceCopy.phoneHint(status)); publish(); return
        }
        if (busyWith(VoiceSource.GLASSES)) {
            outcome(VoiceOutcome.BUSY, VoiceSource.PHONE); showPhoneError(VoiceCopy.GLASSES_BUSY_PHONE); publish(); return
        }
        begin(VoiceSource.PHONE)
    }

    fun phoneStop() {
        val s = _state.value
        if (s is VoiceState.Listening && s.source == VoiceSource.PHONE) endListening(gen)
    }

    fun phoneCancel() { if (_state.value.source == VoiceSource.PHONE) reset("phone cancel", VoiceOutcome.CANCELLED) }

    /** A typed Ask's answer, mirrored to the HUD (spec §2.4) and not spoken; never over a voice ask in progress. */
    fun showTypedAnswer(askId: String, question: String, text: String) {
        val s = _state.value
        if (s is VoiceState.Listening || s is VoiceState.Thinking) return
        val g = newGeneration()
        set(VoiceState.Answer(VoiceSource.PHONE, askId, VoiceText.clean(question), VoiceText.clean(text, MAX_SPOKEN), speaking = false))
        hold(g)
    }

    private fun busyWith(source: VoiceSource): Boolean {
        val s = _state.value
        return s.source == source && (s is VoiceState.Listening || s is VoiceState.Thinking)
    }

    private fun begin(source: VoiceSource) {
        val prev = _state.value
        if (prev is VoiceState.Listening || prev is VoiceState.Thinking) prev.source?.let { outcome(VoiceOutcome.SUPERSEDED, it) }
        val g = newGeneration()
        hudToast = null
        hudToastJob?.cancel()
        set(VoiceState.Listening(source, ""))
        val listener = object : SttListener {
            override fun onPartial(text: String) = onPartial(g, text)
            override fun onFinal(result: SttResult) = onFinal(g, result)
        }
        session = try {
            val tag = language().tag // inside the try: a throw ends in short copy, never a Listening with no session
            if (source == VoiceSource.GLASSES) engine.startExternal(tag, listener) else engine.startPhoneMic(tag, listener)
        } catch (e: Exception) {
            log("recognizer did not start (${e.javaClass.simpleName})")
            problem(g, source, VoiceOutcome.RECOGNIZER_FAILED, VoiceCopy.COULDNT_HEAR_HUD, VoiceCopy.COULDNT_HEAR_PHONE)
            return
        }
        log("listening ${source.name}")
        if (source == VoiceSource.GLASSES) armIdle(g)
        maxTimer = scope.launch { delay(timings.maxListenMs); endListening(g) }
    }

    /** Stops whatever ran (session, timers, the answer call, speech in progress); returns the new generation. */
    private fun newGeneration(): Long {
        val speaking = (_state.value as? VoiceState.Answer)?.speaking == true
        val g = ++gen // first: callbacks of what is stopped below see an old generation and do nothing
        session?.cancel()
        session = null
        timer?.cancel(); maxTimer?.cancel(); work?.cancel()
        timer = null; maxTimer = null; work = null
        if (speaking) speaker.stop()
        return g
    }

    private fun armIdle(g: Long) {
        timer?.cancel()
        timer = scope.launch { delay(timings.audioIdleMs); log("glasses audio stopped"); endListening(g) }
    }

    private fun endListening(g: Long) {
        if (g != gen) return
        val s = _state.value as? VoiceState.Listening ?: return
        if (s.ended) return
        timer?.cancel(); maxTimer?.cancel()
        set(s.copy(ended = true))
        session?.end()
        timer = scope.launch {
            delay(timings.finalTimeoutMs)
            val heard = (_state.value as? VoiceState.Listening)?.partial.orEmpty()
            val silent = session
            onFinal(g, if (heard.isNotEmpty()) SttResult.Text(heard, genuineFinal = false) else SttResult.NoSpeech, finalTimedOut = true)
            // No final came: release the recognizer, its pipe and pump. After onFinal, so a late final it may send is inert.
            log("no final: session cancelled")
            silent?.cancel()
        }
    }

    private fun onPartial(g: Long, text: String) {
        if (g != gen) return
        val s = _state.value as? VoiceState.Listening ?: return
        set(s.copy(partial = VoiceText.clean(text)))
    }

    /** [finalTimedOut]: no final came within [VoiceTimings.finalTimeoutMs]; nothing heard is then NO_FINAL. */
    private fun onFinal(g: Long, result: SttResult, finalTimedOut: Boolean = false) {
        if (g != gen) return
        val s = _state.value as? VoiceState.Listening ?: return
        session = null
        timer?.cancel(); maxTimer?.cancel()
        when (result) {
            is SttResult.Text -> think(g, s.source, result.text, result.genuineFinal)
            SttResult.NoSpeech, SttResult.Failed -> {
                val why = when {
                    finalTimedOut -> VoiceOutcome.NO_FINAL
                    result == SttResult.Failed -> VoiceOutcome.STT_FAILED
                    else -> VoiceOutcome.NO_SPEECH
                }
                problem(g, s.source, why, VoiceCopy.COULDNT_HEAR_HUD, VoiceCopy.COULDNT_HEAR_PHONE)
            }
            SttResult.FallbackSuspected -> {
                log("fallback suspected: session aborted")
                problem(g, s.source, VoiceOutcome.FALLBACK_SUSPECTED, VoiceCopy.NOT_SUPPORTED_HUD, VoiceCopy.NOT_SUPPORTED_PHONE)
                onFallbackSuspected()
            }
            SttResult.PackMissing -> problem(g, s.source, VoiceOutcome.PACK_MISSING, VoiceCopy.PACK_HUD, VoiceCopy.PACK_PHONE)
            SttResult.NoPermission -> problem(g, s.source, VoiceOutcome.NO_PERMISSION, VoiceCopy.MIC_HUD, VoiceCopy.MIC_PHONE)
        }
    }

    private fun think(g: Long, source: VoiceSource, transcript: String, genuineFinal: Boolean) {
        var question = VoiceText.clean(transcript)
        if (question.isEmpty()) { problem(g, source, VoiceOutcome.NO_SPEECH, VoiceCopy.COULDNT_HEAR_HUD, VoiceCopy.COULDNT_HEAR_PHONE); return }
        // Before Thinking is published: a handled utterance never shows Thinking and never reaches the answerer.
        when (val r = try { interceptor.intercept(question, source, genuineFinal) } catch (e: Exception) {
            log("interceptor failed (${e.javaClass.simpleName})"); InterceptResult.PassThrough
        }) {
            InterceptResult.PassThrough -> Unit
            InterceptResult.Handled -> { outcome(VoiceOutcome.MARKED, source); set(VoiceState.Idle); return }
            is InterceptResult.Ask -> {
                question = VoiceText.clean(r.question)
                if (question.isEmpty()) { outcome(VoiceOutcome.MARKED, source); set(VoiceState.Idle); return }
            }
        }
        set(VoiceState.Thinking(source, question))
        work = scope.launch {
            val r = try {
                answerer.answer(question, source)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                log("answer failed (${e.javaClass.simpleName})")
                AnswerResult.Failed(VoiceCopy.FAILED_HUD, VoiceCopy.FAILED_PHONE)
            }
            if (g != gen) return@launch
            timer?.cancel() // the Thinking bound
            when (r) {
                is AnswerResult.Answered -> answered(g, source, question, r)
                is AnswerResult.Failed -> problem(g, source, VoiceOutcome.ANSWER_FAILED, r.hudText, r.phoneText)
            }
        }
        // A timer, not withTimeout: an answer path that ignores cancellation (a NonCancellable write) can't hold Thinking.
        timer = scope.launch {
            delay(timings.answerTimeoutMs)
            if (g != gen) return@launch
            log("answer timed out")
            // New generation: a late answer is ignored.
            problem(newGeneration(), source, VoiceOutcome.ANSWER_TIMEOUT, VoiceCopy.TOO_LONG, VoiceCopy.TOO_LONG)
        }
    }

    private fun answered(g: Long, source: VoiceSource, question: String, r: AnswerResult.Answered) {
        if (source == VoiceSource.PHONE) lastPhoneAskId = r.askId
        val text = VoiceText.clean(r.text, MAX_SPOKEN)
        val speak = source == VoiceSource.PHONE || bluetoothMedia()
        set(VoiceState.Answer(source, r.askId, question, text, speaking = speak, photoLabel = r.photoLabel))
        log("answered ${source.name} spoken=$speak")
        outcome(VoiceOutcome.ANSWERED, source)
        if (!speak || !speaker.speak(r.askId, text) { speechDone(g) }) speechDone(g)
    }

    private fun speechDone(g: Long) {
        if (g != gen) return
        val s = _state.value as? VoiceState.Answer ?: return
        if (s.speaking) set(s.copy(speaking = false))
        hold(g)
    }

    private fun hold(g: Long) {
        timer?.cancel()
        timer = scope.launch { delay(timings.answerHoldMs); if (g == gen) set(VoiceState.Idle) }
    }

    private fun problem(g: Long, source: VoiceSource, why: VoiceOutcome, hud: String, phoneText: String) {
        outcome(why, source)
        if (source == VoiceSource.PHONE) showPhoneError(phoneText)
        set(VoiceState.Problem(source, hud, phoneText))
        timer?.cancel()
        timer = scope.launch { delay(timings.toastMs); if (g == gen) set(VoiceState.Idle) }
    }

    private fun reset(why: String, code: VoiceOutcome) {
        val s = _state.value
        newGeneration()
        log(why)
        // A dismissed Answer is not a new end: its ask already logged ANSWERED.
        if (s is VoiceState.Listening || s is VoiceState.Thinking) s.source?.let { outcome(code, it) }
        set(VoiceState.Idle)
    }

    private fun outcome(code: VoiceOutcome, source: VoiceSource) = log("outcome=${code.name} source=${source.name}")

    /** Home's voice error clears after [VoiceTimings.toastMs], like the glasses toast. */
    private fun showPhoneError(text: String?) {
        phoneError = text
        phoneErrorJob?.cancel()
        phoneErrorJob = text?.let { scope.launch { delay(timings.toastMs); phoneError = null; publish() } }
    }

    private fun toastHud(text: String) {
        hudToast = text
        hudToastJob?.cancel()
        hudToastJob = scope.launch { delay(timings.toastMs); hudToast = null; publish() }
        publish()
    }

    private fun set(s: VoiceState) {
        _state.value = s
        publish()
    }

    private fun publish() {
        val s = _state.value
        _overlay.value = hudToast?.let { Overlay.Toast(it) } ?: overlayFor(s)
        val phoneListening = s is VoiceState.Listening && s.source == VoiceSource.PHONE
        _phone.value = PhoneVoiceState(
            status = capabilities.value.phone,
            listening = phoneListening && !(s as VoiceState.Listening).ended,
            partial = if (phoneListening) (s as VoiceState.Listening).partial else "",
            thinking = (s is VoiceState.Thinking && s.source == VoiceSource.PHONE) || (phoneListening && (s as VoiceState.Listening).ended),
            error = phoneError,
            lastAskId = lastPhoneAskId,
        )
    }

    private fun overlayFor(s: VoiceState): Overlay? = when (s) {
        VoiceState.Idle -> null
        is VoiceState.Listening -> if (s.source == VoiceSource.GLASSES) Overlay.Listening(VoiceText.clip(s.partial, HUD_TEXT)) else null
        is VoiceState.Thinking -> if (s.source == VoiceSource.GLASSES) Overlay.Listening(VoiceText.clip(s.question, HUD_TEXT)) else null
        is VoiceState.Answer -> Overlay.Answer(VoiceText.clip(s.question, HUD_TEXT), hudAnswer(s), s.speaking)
        is VoiceState.Problem -> if (s.source == VoiceSource.GLASSES) Overlay.Toast(VoiceText.clip(s.hudText, HUD_TEXT)) else null
    }

    /** The HUD text only (TTS speaks [VoiceState.Answer.text]): the answer clipped to leave room for " · <label>". */
    private fun hudAnswer(s: VoiceState.Answer): String {
        val label = s.photoLabel?.takeIf { it.isNotBlank() } ?: return VoiceText.clip(s.text, HUD_ANSWER)
        return VoiceText.clip(s.text, HUD_ANSWER - label.length - 3) + " \u00b7 " + label
    }

    private companion object {
        const val HUD_TEXT = 120
        const val HUD_ANSWER = 300
        const val MAX_SPOKEN = 2_000
    }
}

/** How one voice ask ended, for the `outcome=` log line (codes only, never user text). */
enum class VoiceOutcome {
    ANSWERED,
    /** The recognizer's final had no speech, or the transcript was empty after cleaning. */
    NO_SPEECH,
    /** Audio ended but no final arrived within [VoiceTimings.finalTimeoutMs] and no partial was heard. */
    NO_FINAL,
    STT_FAILED,
    RECOGNIZER_FAILED,
    FALLBACK_SUSPECTED,
    PACK_MISSING,
    NO_PERMISSION,
    ANSWER_FAILED,
    ANSWER_TIMEOUT,
    CANCELLED,
    LINK_LOST,
    /** Voice can't work here (capability gate): no listen started. */
    BLOCKED,
    /** The other source was listening or thinking: no listen started. */
    BUSY,
    /** An [UtteranceInterceptor] consumed the utterance: no model call. */
    MARKED,
    /** A new listen replaced one still listening or thinking. */
    SUPERSEDED,
}
