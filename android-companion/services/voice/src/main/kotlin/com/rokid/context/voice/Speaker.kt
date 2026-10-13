package com.rokid.context.voice

import java.util.Locale

/** Phone TTS (spec §3.6). [speak] false = nothing will be spoken; [onDone] runs exactly once (end, stop, replace, failure). */
interface Speaker {
    fun speak(id: String, text: String, onDone: () -> Unit): Boolean
    fun stop()
}

/** The `TextToSpeech` calls [SpeakerCore] needs. setLanguage >= 0 = usable; speak 0 = queued. */
interface TtsEngine {
    fun setLanguage(locale: Locale): Int
    fun speak(text: String, utteranceId: String): Int
    fun stop()
}

/** Audio focus held while an utterance is out. [request] false = denied (a call, say): nothing is spoken. */
interface AudioFocus {
    fun request(): Boolean
    fun abandon()
}

object TtsLocale {
    val HINDI: Locale = Locale("hi", "IN")
    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")

    /** Devanagari text is spoken in Hindi, everything else in US English (spec §0.1: English primary, Hindi optional). */
    fun pick(text: String): Locale = if (DEVANAGARI.containsMatchIn(text)) HINDI else Locale.US
}

/**
 * Speaker rules over any [TtsEngine] (pure, so they are unit-tested). The engine is made on first use; an utterance
 * asked for before it is ready waits (a newer one replaces it); a language without a voice is not spoken (an English
 * voice reading Devanagari helps nobody). [focus] is taken just before the engine is asked to speak and given back
 * as soon as nothing is out - before any onDone runs, so a callback that speaks again takes it afresh. Call on Main.
 */
class SpeakerCore(
    private val focus: AudioFocus,
    private val create: (onInit: (Boolean) -> Unit) -> TtsEngine,
) : Speaker {
    private class Utterance(val id: String, val text: String, val onDone: () -> Unit)

    private var engine: TtsEngine? = null
    private var ready: Boolean? = null
    private var waiting: Utterance? = null
    private var focused = false
    private val callbacks = LinkedHashMap<String, () -> Unit>()

    override fun speak(id: String, text: String, onDone: () -> Unit): Boolean {
        if (engine == null) engine = create(::onInit)
        return when (ready) {
            false -> false
            null -> { val old = waiting; waiting = Utterance(id, text, onDone); old?.onDone?.invoke(); true }
            true -> say(Utterance(id, text, onDone))
        }
    }

    fun onInit(ok: Boolean) {
        ready = ok
        val w = waiting ?: return
        waiting = null
        if (!ok || !say(w)) w.onDone()
    }

    fun onUtteranceDone(id: String) {
        val cb = callbacks.remove(id) ?: return
        releaseIfIdle()
        cb()
    }

    override fun stop() {
        val w = waiting
        waiting = null
        if (ready == true) engine?.stop()
        val pending = callbacks.values.toList()
        callbacks.clear()
        releaseIfIdle()
        w?.onDone?.invoke()
        pending.forEach { it() }
    }

    private fun say(u: Utterance): Boolean {
        val e = engine ?: return false
        if (e.setLanguage(TtsLocale.pick(u.text)) < 0) return false
        if (!focused) { if (!focus.request()) return false; focused = true }
        val replaced = callbacks.remove(u.id)
        callbacks[u.id] = u.onDone
        val queued = e.speak(u.text, u.id) == 0
        if (!queued) callbacks.remove(u.id)
        releaseIfIdle()
        replaced?.invoke()
        return queued
    }

    private fun releaseIfIdle() {
        if (focused && callbacks.isEmpty()) { focused = false; focus.abandon() }
    }
}
