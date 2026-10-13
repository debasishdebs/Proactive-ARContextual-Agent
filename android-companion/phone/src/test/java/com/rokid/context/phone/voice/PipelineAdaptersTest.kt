package com.rokid.context.phone.voice

import com.rokid.context.voice.AnswerResult
import com.rokid.context.voice.Answerer
import com.rokid.context.voice.SpeechEngine
import com.rokid.context.voice.SpeechLanguage
import com.rokid.context.voice.Speaker
import com.rokid.context.voice.SttListener
import com.rokid.context.voice.SttSession
import com.rokid.context.voice.VoiceCapabilities
import com.rokid.context.voice.VoicePipeline
import com.rokid.context.voice.VoiceSettings
import com.rokid.context.voice.VoiceState
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineAdaptersTest {
    private var sessions = 0
    private var cancels = 0
    private var listener: SttListener? = null
    private val engine = object : SpeechEngine {
        private fun session() = object : SttSession { override fun feed(pcm: ByteArray) = Unit; override fun end() = Unit; override fun cancel() { cancels++ } }
        override fun startExternal(localeTag: String, listener: SttListener): SttSession { sessions++; this@PipelineAdaptersTest.listener = listener; return session() }
        override fun startPhoneMic(localeTag: String, listener: SttListener): SttSession { sessions++; this@PipelineAdaptersTest.listener = listener; return session() }
    }
    private val speaker = object : Speaker { override fun speak(id: String, text: String, onDone: () -> Unit) = false; override fun stop() = Unit }
    private val caps = MutableStateFlow(VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, true))

    @Test fun aPhoneMicTurnedOffInSettingsNeitherListensNorClaimsToBeOn() = runTest {
        val settings = MutableStateFlow(VoiceSettings(phoneMic = false))
        val pipeline = VoicePipeline(backgroundScope, engine, caps, { SpeechLanguage.ENGLISH }, Answerer { _, _ -> AnswerResult.Failed("x", "y") }, speaker, { false })
        val phone = PipelinePhoneVoice(pipeline, settings, backgroundScope)
        runCurrent()
        assertTrue(phone.state.value.micOff)
        phone.start(); runCurrent()
        assertEquals(0, sessions)
        settings.value = VoiceSettings(phoneMic = true); runCurrent()
        assertFalse(phone.state.value.micOff)
        phone.start(); runCurrent()
        assertEquals(1, sessions)
        assertTrue(phone.state.value.listening)
    }

    /** Carry (Task 8): micOff survives the pipeline publishing a new phone state (each publish starts from false). */
    @Test fun micOffStaysOnTopOfEveryPipelineUpdate() = runTest {
        val settings = MutableStateFlow(VoiceSettings(phoneMic = false))
        val pipeline = VoicePipeline(backgroundScope, engine, caps, { SpeechLanguage.ENGLISH }, Answerer { _, _ -> AnswerResult.Failed("x", "y") }, speaker, { false })
        val phone = PipelinePhoneVoice(pipeline, settings, backgroundScope)
        runCurrent()
        caps.value = VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.NEEDS_SPEECH_PACK, true); runCurrent()
        assertEquals(VoiceStatus.NEEDS_SPEECH_PACK, phone.state.value.status)
        assertTrue(phone.state.value.micOff)
    }

    /** Carry (Task 8): turning the phone mic off while Home is listening ends that ask; nothing is answered. */
    @Test fun turningThePhoneMicOffMidListenCancelsThePhoneAsk() = runTest {
        val settings = MutableStateFlow(VoiceSettings(phoneMic = true))
        var answered = 0
        val pipeline = VoicePipeline(backgroundScope, engine, caps, { SpeechLanguage.ENGLISH }, Answerer { _, _ -> answered++; AnswerResult.Answered("a1", "x") }, speaker, { false })
        val phone = PipelinePhoneVoice(pipeline, settings, backgroundScope)
        runCurrent()
        phone.start(); runCurrent()
        listener!!.onPartial("where are my keys")
        assertTrue(phone.state.value.listening)
        settings.value = VoiceSettings(phoneMic = false); runCurrent()
        assertEquals(VoiceState.Idle, pipeline.state.value)
        assertEquals(1, cancels)
        assertFalse(phone.state.value.listening)
        assertTrue(phone.state.value.micOff)
        listener!!.onFinal(com.rokid.context.voice.SttResult.Text("where are my keys")); runCurrent()
        assertEquals(0, answered)
    }

    /** A glasses ask is not the phone mic's: switching the phone mic off leaves it alone. */
    @Test fun turningThePhoneMicOffLeavesAGlassesListenAlone() = runTest {
        val settings = MutableStateFlow(VoiceSettings(phoneMic = true))
        val pipeline = VoicePipeline(backgroundScope, engine, caps, { SpeechLanguage.ENGLISH }, Answerer { _, _ -> AnswerResult.Failed("x", "y") }, speaker, { false })
        PipelinePhoneVoice(pipeline, settings, backgroundScope)
        runCurrent()
        pipeline.glassesListenStart(); runCurrent()
        settings.value = VoiceSettings(phoneMic = false); runCurrent()
        assertTrue(pipeline.state.value is VoiceState.Listening)
        assertEquals(0, cancels)
    }

    @Test fun theHubSeesTheGatesReason() = runTest {
        val pipeline = VoicePipeline(backgroundScope, engine, caps, { SpeechLanguage.ENGLISH }, Answerer { _, _ -> AnswerResult.Failed("x", "y") }, speaker, { false })
        val hub = PipelineHubVoice(pipeline, caps, backgroundScope)
        runCurrent()
        assertEquals(null, hub.askBlockedReason.value)
        caps.value = VoiceCapabilities(VoiceStatus.NEEDS_SPEECH_PACK, VoiceStatus.NEEDS_SPEECH_PACK, false); runCurrent()
        assertEquals("Download speech pack on phone", hub.askBlockedReason.value)
    }
}
