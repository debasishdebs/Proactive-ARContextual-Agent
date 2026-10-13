package com.rokid.context.voice.android

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.voice.SpeakerEvent
import com.rokid.context.voice.TtsLocale
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec §3.6 on the Android side: phone TTS takes transient (may-duck) audio focus while it speaks and gives it back,
 * stops when focus is lost or the media route goes away (glasses A2DP dropping must not move speech to the phone
 * speaker), and only an A2DP output counts as glasses audio. The real route is PENDING-DEVICE.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidSpeakerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val done = mutableListOf<String>()
    private val events = mutableListOf<SpeakerEvent>()

    private fun voice(name: String, locale: Locale, network: Boolean = false, vararg features: String) =
        Voice(name, locale, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, network, features.toSet())

    private val usEmbedded = voice("en-us-local", Locale.US, false, AndroidSpeaker.EMBEDDED)

    @Before fun voices() { ShadowTextToSpeech.addLanguageAvailability(Locale.US); ShadowTextToSpeech.addVoice(usEmbedded) }
    @After fun reset() { ShadowTextToSpeech.reset() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ready(): Pair<AndroidSpeaker, TextToSpeech> {
        val speaker = AndroidSpeaker(ctx) { events += it }
        assertTrue(speaker.speak("warm", "ok") {})
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        return speaker to tts
    }

    private fun lastFocus(): AudioFocusRequest? = shadowOf(am).lastAudioFocusRequest?.audioFocusRequest

    @Test fun speechTakesTransientDuckingFocusAndGivesItBackWhenDone() {
        val (speaker, tts) = ready()
        shadowOf(am).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue(speaker.speak("a1", "Milk and eggs.") { done += "a1" })
        assertEquals("Milk and eggs.", shadowOf(tts).lastSpokenText)
        val req = lastFocus()!!
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, req.focusGain)
        assertEquals(android.media.AudioAttributes.CONTENT_TYPE_SPEECH, req.audioAttributes.contentType)
        idle() // the engine reports onDone for "warm" and "a1"
        assertEquals(listOf("a1"), done)
        assertEquals(req, shadowOf(am).lastAbandonedAudioFocusRequest)
    }

    @Test fun deniedFocusSpeaksNothing() {
        val (speaker, tts) = ready()
        shadowOf(tts).clearLastSpokenText()
        shadowOf(am).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(speaker.speak("a1", "Milk.") { done += "a1" })
        assertNull(shadowOf(tts).lastSpokenText)
        assertTrue(done.isEmpty())
        assertEquals(listOf(SpeakerEvent.FOCUS_DENIED), events)
    }

    // Fix round 1, Important 1: a failed init shuts the engine down; the next answer binds a new one.
    @Test fun aFailedInitShutsTheEngineDownAndTheNextAnswerRetries() {
        val speaker = AndroidSpeaker(ctx) { events += it }
        speaker.speak("a1", "Milk.") { done += "a1" }
        val first = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(first).onInitListener.onInit(TextToSpeech.ERROR)
        idle()
        assertEquals(listOf("a1"), done)
        assertTrue(shadowOf(first).isShutdown)
        assertEquals(listOf(SpeakerEvent.INIT_FAILED), events)
        assertTrue(speaker.speak("a2", "Eggs.") { done += "a2" })
        val second = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotSame(first, second)
        shadowOf(second).onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals("Eggs.", shadowOf(second).lastSpokenText)
    }

    // Fix round 1, Minor 5 (privacy ruling): answer text only ever goes to an installed, on-device voice.
    @Test fun aNetworkOnlyVoiceNeverReceivesTheAnswer() {
        val hindi = "\u091A\u093E\u092C\u0940 \u0936\u0947\u0932\u094D\u092B \u092A\u0930 \u0939\u0948"
        ShadowTextToSpeech.addLanguageAvailability(TtsLocale.HINDI)
        ShadowTextToSpeech.addVoice(voice("hi-in-network", TtsLocale.HINDI, true, AndroidSpeaker.NETWORK))
        ShadowTextToSpeech.addVoice(voice("hi-in-notinstalled", TtsLocale.HINDI, false,
            AndroidSpeaker.EMBEDDED, TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        val (speaker, tts) = ready()
        shadowOf(tts).clearLastSpokenText()
        assertFalse(speaker.speak("h1", hindi) { done += "h1" }, "HUD-only: no on-device Hindi voice")
        assertNull(shadowOf(tts).lastSpokenText)
        assertEquals(listOf(SpeakerEvent.LANG_MISSING), events)
        assertTrue(done.isEmpty())
    }

    @Test fun anInstalledOnDeviceVoiceIsSelectedBeforeSpeaking() {
        val hindi = "\u091A\u093E\u092C\u0940 \u0936\u0947\u0932\u094D\u092B \u092A\u0930 \u0939\u0948"
        val local = voice("hi-in-local", TtsLocale.HINDI, false, AndroidSpeaker.EMBEDDED)
        ShadowTextToSpeech.addLanguageAvailability(TtsLocale.HINDI)
        ShadowTextToSpeech.addVoice(voice("hi-in-network", TtsLocale.HINDI, true, AndroidSpeaker.NETWORK))
        ShadowTextToSpeech.addVoice(local)
        val (speaker, tts) = ready()
        assertTrue(speaker.speak("h1", hindi) { done += "h1" })
        assertEquals(local, shadowOf(tts).currentVoice)
        assertEquals(hindi, shadowOf(tts).lastSpokenText)
        assertTrue(speaker.speak("a1", "Keys.") { done += "a1" })
        assertEquals(usEmbedded, shadowOf(tts).currentVoice)
    }

    @Test fun losingFocusStopsSpeech() {
        val (speaker, tts) = ready()
        speaker.speak("a1", "Milk.") { done += "a1" }
        val listener = ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(lastFocus()!!, "getOnAudioFocusChangeListener")
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(listOf("a1"), done)
        assertTrue(shadowOf(tts).isStopped)
        assertEquals(listOf(SpeakerEvent.FOCUS_LOST), events)
    }

    @Test fun theMediaRouteGoingAwayStopsSpeech() {
        val (speaker, tts) = ready()
        speaker.speak("a1", "Milk.") { done += "a1" }
        ctx.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle() // the broadcast is delivered before the engine's own onDone hop
        assertEquals(listOf("a1"), done)
        assertTrue(shadowOf(tts).isStopped)
        assertEquals(listOf(SpeakerEvent.BECOMING_NOISY), events)
    }

    @Test fun theRouteWatchEndsWithTheSpeech() {
        val (speaker, tts) = ready()
        speaker.speak("a1", "Milk.") { done += "a1" }
        idle()
        assertEquals(listOf("a1"), done)
        ctx.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertFalse(shadowOf(tts).isStopped, "nothing is speaking, so nothing is stopped")
    }

    @Test fun stopEndsSpeechAndGivesFocusBack() {
        val (speaker, tts) = ready()
        speaker.speak("a1", "Milk.") { done += "a1" }
        val req = lastFocus()!!
        speaker.stop()
        assertEquals(listOf("a1"), done)
        assertTrue(shadowOf(tts).isStopped)
        assertEquals(req, shadowOf(am).lastAbandonedAudioFocusRequest)
        idle()
        assertEquals(listOf("a1"), done, "the engine's late callback is not a second onDone")
    }

    @Test fun onlyAnA2dpOutputIsGlassesMedia() {
        val sco = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        val a2dp = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
        val speakerOut = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        shadowOf(am).setOutputDevices(listOf(speakerOut, sco))
        assertFalse(AudioRoutes.hasBluetoothMedia(ctx))
        shadowOf(am).setOutputDevices(listOf(speakerOut, sco, a2dp))
        assertTrue(AudioRoutes.hasBluetoothMedia(ctx))
    }
}
