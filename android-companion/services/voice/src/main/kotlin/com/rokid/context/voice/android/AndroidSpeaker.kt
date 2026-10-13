package com.rokid.context.voice.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.rokid.context.voice.AudioFocus
import com.rokid.context.voice.Speaker
import com.rokid.context.voice.SpeakerCore
import com.rokid.context.voice.SpeakerEvent
import com.rokid.context.voice.SpeechTimer
import com.rokid.context.voice.TtsEngine
import java.util.Locale

/**
 * Phone `TextToSpeech` (spec §3.6): media goes to the active route, which is the glasses' A2DP link when connected
 * (phase 0). Built lazily on the first answer; every callback is posted to Main before it reaches [SpeakerCore].
 * While an utterance is out it holds transient may-duck focus and watches for the media route going away: losing
 * either stops speech, so a dropped A2DP link never moves an answer onto the phone speaker. Only an installed
 * on-device voice is ever selected, so answer text never reaches a network synthesizer (privacy ruling, fix round 1).
 * Logs fixed [SpeakerEvent] names only (R-73).
 */
class AndroidSpeaker(
    context: Context,
    private val log: (SpeakerEvent) -> Unit = { Log.i(TAG, it.wire) },
) : Speaker {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA) // the stream phase 0 heard on the glasses (TTS default: STREAM_MUSIC)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val timer = SpeechTimer { delayMs, block ->
        val r = Runnable(block)
        main.postDelayed(r, delayMs)
        ({ main.removeCallbacks(r) })
    }
    private val core = SpeakerCore(Focus(), timer, log) { onInit -> engine(onInit) }

    override fun speak(id: String, text: String, onDone: () -> Unit): Boolean = core.speak(id, text, onDone)
    override fun stop() = core.stop()

    private fun done(id: String?) { if (id != null) main.post { core.onUtteranceDone(id) } }

    private inner class Focus : AudioFocus {
        private val manager = app.getSystemService(AudioManager::class.java)
        private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audio)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { log(SpeakerEvent.FOCUS_LOST); core.stop() }
            }, main)
            .build()
        private val routeGone = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { log(SpeakerEvent.BECOMING_NOISY); core.stop() }
        }
        private var watching = false

        override fun request(): Boolean {
            val m = manager ?: return false
            if (m.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            ContextCompat.registerReceiver(
                app, routeGone, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), null, main, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            watching = true
            return true
        }

        override fun abandon() {
            if (watching) { watching = false; runCatching { app.unregisterReceiver(routeGone) } }
            manager?.abandonAudioFocusRequest(request)
        }
    }

    private fun engine(onInit: (Boolean) -> Unit): TtsEngine {
        val tts = TextToSpeech(app) { status -> main.post { onInit(status == TextToSpeech.SUCCESS) } }
        tts.setAudioAttributes(audio)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = done(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = done(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = done(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = done(utteranceId)
        })
        return object : TtsEngine {
            override fun setLanguage(locale: Locale): Int {
                val v = runCatching { onDeviceVoice(tts.voices, locale) }.getOrNull() ?: return TextToSpeech.LANG_NOT_SUPPORTED
                return if (tts.setVoice(v) == TextToSpeech.SUCCESS) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
            }
            override fun speak(text: String, utteranceId: String): Int = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            override fun stop() { tts.stop() }
            override fun shutdown() { tts.shutdown() }
        }
    }

    companion object {
        private const val TAG = "AndroidSpeaker"

        // Deprecated as speak() params since API 21, but still the feature strings Voice.getFeatures() reports.
        @Suppress("DEPRECATION") internal const val EMBEDDED = TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS
        @Suppress("DEPRECATION") internal const val NETWORK = TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS

        /**
         * The best installed on-device voice for [locale]: same language (same country first, then higher quality),
         * never one marked not-installed, never one that needs the network or lists network synthesis (even when it
         * also lists embedded synthesis). Null = HUD-only.
         */
        fun onDeviceVoice(voices: Set<Voice>?, locale: Locale): Voice? = voices.orEmpty()
            .filter { it.locale.language == locale.language }
            .filter { v ->
                val f = v.features.orEmpty()
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in f && !v.isNetworkConnectionRequired && NETWORK !in f
            }
            .sortedWith(compareByDescending<Voice> { it.locale.country == locale.country }.thenByDescending { it.quality })
            .firstOrNull()
    }
}
