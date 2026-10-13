package com.rokid.context.voice

import java.util.Locale

/** Phone TTS (spec §3.6). [speak] false = nothing will be spoken; [onDone] runs exactly once (end, stop, replace, failure). */
interface Speaker {
    fun speak(id: String, text: String, onDone: () -> Unit): Boolean
    fun stop()
}

/**
 * The `TextToSpeech` calls [SpeakerCore] needs. setLanguage >= 0 = an installed on-device voice is selected (never a
 * network one: answer text stays on the phone); speak 0 = queued; shutdown releases a failed engine.
 */
interface TtsEngine {
    fun setLanguage(locale: Locale): Int
    fun speak(text: String, utteranceId: String): Int
    fun stop()
    fun shutdown()
}

/** Audio focus held while an utterance is out. [request] false = denied (a call, say): nothing is spoken. */
interface AudioFocus {
    fun request(): Boolean
    fun abandon()
}

/** Runs [block] after [delayMs] on the speaker's thread; the returned function cancels it. */
fun interface SpeechTimer {
    fun after(delayMs: Long, block: () -> Unit): () -> Unit
}

/** What the speaker may log (R-73): fixed event names, never text, ids or locales. */
enum class SpeakerEvent(val wire: String) {
    INIT_FAILED("init_failed"),
    LANG_MISSING("lang_missing"),
    FOCUS_DENIED("focus_denied"),
    FOCUS_LOST("focus_lost"),
    BECOMING_NOISY("becoming_noisy"),
    WATCHDOG_STOP("watchdog_stop"),
}

object TtsLocale {
    val HINDI: Locale = Locale.forLanguageTag("hi-IN")
    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")

    /** Devanagari text is spoken in Hindi, everything else in US English (spec §0.1: English primary, Hindi optional). */
    fun pick(text: String): Locale = if (DEVANAGARI.containsMatchIn(text)) HINDI else Locale.US
}

/**
 * Speaker rules over any [TtsEngine] (pure, so they are unit-tested). The engine is made on first use; an utterance
 * asked for before it is ready waits (a newer one replaces it); an engine that fails to start is shut down and the
 * next answer builds a new one; a language without an on-device voice is not spoken (an English voice reading
 * Devanagari helps nobody). Each utterance gets its own engine id, so a late callback for an old one never ends a
 * newer one, and a [watchdogMs] timer stops one the engine never reports on. [focus] is taken just before the engine
 * is asked to speak and given back as soon as nothing is out - before any onDone runs, so a callback that speaks
 * again takes it afresh. Call on Main.
 */
class SpeakerCore(
    private val focus: AudioFocus,
    private val timer: SpeechTimer,
    private val log: (SpeakerEvent) -> Unit,
    private val create: (onInit: (Boolean) -> Unit) -> TtsEngine,
) : Speaker {
    private class Utterance(val id: String, val text: String, val onDone: () -> Unit)
    private class Out(val callerId: String, val onDone: () -> Unit, val cancelWatchdog: () -> Unit)

    private var engine: TtsEngine? = null
    private var ready: Boolean? = null
    private var waiting: Utterance? = null
    private var focused = false
    private var seq = 0L
    private val out = LinkedHashMap<String, Out>()

    override fun speak(id: String, text: String, onDone: () -> Unit): Boolean {
        if (engine == null) {
            var made: TtsEngine? = null
            made = create { ok -> if (engine === made) onInit(ok) }
            engine = made
        }
        return when (ready) {
            false -> false
            null -> { val old = waiting; waiting = Utterance(id, text, onDone); old?.onDone?.invoke(); true }
            true -> say(Utterance(id, text, onDone))
        }
    }

    fun onInit(ok: Boolean) {
        if (!ok) {
            log(SpeakerEvent.INIT_FAILED)
            engine?.let { e -> runCatching { e.shutdown() } }
            engine = null
            ready = null
        } else {
            ready = true
        }
        val w = waiting ?: return
        waiting = null
        if (!ok || !say(w)) w.onDone()
    }

    fun onUtteranceDone(engineId: String) {
        val o = out.remove(engineId) ?: return
        o.cancelWatchdog()
        releaseIfIdle()
        o.onDone()
    }

    override fun stop() {
        val w = waiting
        waiting = null
        if (ready == true) engine?.stop()
        val pending = out.values.toList()
        out.clear()
        pending.forEach { it.cancelWatchdog() }
        releaseIfIdle()
        w?.onDone?.invoke()
        pending.forEach { it.onDone() }
    }

    private fun say(u: Utterance): Boolean {
        val e = engine ?: return false
        if (e.setLanguage(TtsLocale.pick(u.text)) < 0) { log(SpeakerEvent.LANG_MISSING); return false }
        if (!focused) {
            if (!focus.request()) { log(SpeakerEvent.FOCUS_DENIED); return false }
            focused = true
        }
        val replaced = out.entries.filter { it.value.callerId == u.id }.map { it.key }.mapNotNull { out.remove(it) }
        replaced.forEach { it.cancelWatchdog() }
        val engineId = "${u.id}#${++seq}"
        val cancel = timer.after(watchdogMs(u.text)) { if (engineId in out) { log(SpeakerEvent.WATCHDOG_STOP); stop() } }
        out[engineId] = Out(u.id, u.onDone, cancel)
        val queued = e.speak(u.text, engineId) == 0
        if (!queued) out.remove(engineId)?.cancelWatchdog()
        releaseIfIdle()
        replaced.forEach { it.onDone() }
        return queued
    }

    private fun releaseIfIdle() {
        if (focused && out.isEmpty()) { focused = false; focus.abandon() }
    }

    companion object {
        /** No onDone/onStop/onError within this long ends the utterance: 30 s, or ~8 characters a second plus 10 s. */
        fun watchdogMs(text: String): Long = maxOf(30_000L, 10_000L + text.length * 125L)
    }
}
