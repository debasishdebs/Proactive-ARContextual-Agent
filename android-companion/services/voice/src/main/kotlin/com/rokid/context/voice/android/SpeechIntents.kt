package com.rokid.context.voice.android

import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.rokid.context.core.model.audio.Pcm
import com.rokid.context.voice.SttSessionCore

internal object SpeechIntents {
    /** For `checkRecognitionSupport`: no language, so every on-device language is listed. */
    fun anyLanguage(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    fun base(localeTag: String): Intent = anyLanguage()
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

    /** Glasses audio injected through the pipe's read end (phase 0: verified on the target phone). */
    @RequiresApi(33)
    fun external(localeTag: String, read: ParcelFileDescriptor): Intent = base(localeTag)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Pcm.SAMPLE_RATE)
}

/** Platform callbacks (Main) → [SttSessionCore]. */
internal class RecognitionBridge(private val core: SttSessionCore) : RecognitionListener {
    override fun onPartialResults(b: Bundle?) = core.onPartial(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
    override fun onResults(b: Bundle?) = core.onResults(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
    override fun onError(error: Int) = core.onError(error)
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
