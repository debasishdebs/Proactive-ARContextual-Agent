package com.rokid.context.phone.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import com.rokid.context.core.model.LinkState
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private fun vm(env: Env = Env(), saved: SavedStateHandle = SavedStateHandle()) =
        OnboardingViewModel(env.deps, work = UnconfinedTestDispatcher(), saved = saved)

    @Test fun progressHasEightSegmentsAndAdvances() {
        val v = vm()
        assertEquals(8, Step.entries.size)
        assertEquals(1, v.state.value.filledSegments)
        v.next(); v.next()
        assertEquals(3, v.state.value.filledSegments)
        assertEquals(Step.GLASSES, v.state.value.step)
    }

    @Test fun backFromPermissionsReturnsToWelcome() {
        val v = vm(); v.next()
        assertEquals(Step.PERMISSIONS, v.state.value.step)
        assertTrue(v.back())
        assertEquals(Step.WELCOME, v.state.value.step)
        assertEquals(false, v.back()) // on WELCOME the caller leaves the app
    }

    @Test fun probeRunsWithAllowProbeAfterMicDecision() {
        val e = Env(); val v = vm(e)
        assertEquals(0, e.voice.rechecks)
        assertNull(v.state.value.voice)
        v.onMicResult(granted = true, canAskAgain = true, foreground = true)
        assertEquals(1, e.voice.rechecks)
        assertEquals("Checking voice\u2026", v.state.value.voice?.text)
    }

    @Test fun probeNeverRunsWhileNotInFront() { // Phase 6 rule F2
        val e = Env(); val v = vm(e)
        v.onMicResult(granted = true, canAskAgain = true, foreground = false)
        assertEquals(0, e.voice.rechecks)
    }

    @Test fun deniedMicStillShowsVoiceStatusButDeniedForeverIsFlagged() {
        val e = Env(); val v = vm(e)
        e.voice.capabilities.value = caps(VoiceStatus.NEEDS_MIC_PERMISSION, VoiceStatus.NEEDS_MIC_PERMISSION)
        v.onMicResult(granted = false, canAskAgain = false, foreground = true)
        assertEquals(PermState.DENIED_FOREVER, v.state.value.mic)
        v.onMicResult(granted = false, canAskAgain = true, foreground = true)
        assertEquals(PermState.DENIED, v.state.value.mic)
    }

    @Test fun voiceStatusMapping() {
        val e = Env(); val v = vm(e); v.onMicResult(true, true, true)
        fun text(c: com.rokid.context.voice.VoiceCapabilities): String { e.voice.capabilities.value = c; return v.state.value.voice!!.text }
        assertEquals("Voice: Supported", text(caps(VoiceStatus.SUPPORTED)))
        assertEquals("Voice: Needs speech pack", text(caps(VoiceStatus.NEEDS_SPEECH_PACK, VoiceStatus.NEEDS_SPEECH_PACK, true)))
        assertEquals(VoiceAction.DOWNLOAD, v.state.value.voice!!.action)
        text(caps(VoiceStatus.NEEDS_SPEECH_PACK, VoiceStatus.NEEDS_SPEECH_PACK, false))
        assertEquals(VoiceAction.OPEN_SETTINGS, v.state.value.voice!!.action)
        assertEquals("Voice: Needs Android 13+ for glasses voice", text(caps(VoiceStatus.NEEDS_NEWER_ANDROID)))
        assertEquals("Voice: Not supported on this phone \u2014 you can type questions", text(caps(VoiceStatus.NO_ON_DEVICE_RECOGNIZER, VoiceStatus.NO_ON_DEVICE_RECOGNIZER)))
        assertEquals("Voice: Not supported on this phone \u2014 you can type questions", text(caps(VoiceStatus.EXTERNAL_AUDIO_UNSUPPORTED)))
    }

    @Test fun downloadAndOpenSettingsDelegateToVoiceControls() {
        val e = Env(); val v = vm(e)
        v.downloadPack(); assertEquals(1, e.voice.downloads)
        v.openSpeechSettings(); assertEquals(1, e.voice.speechSettingsOpened)
    }

    @Test fun notificationsAutoGrantedBelowApi33() {
        assertEquals(PermState.GRANTED, vm(Env(sdk = 32)).state.value.notifications)
        assertEquals(PermState.NOT_ASKED, vm(Env(sdk = 34)).state.value.notifications)
    }

    @Test fun glassesStepSkippable() {
        val v = vm(); v.next(); v.next()
        v.skipGlasses()
        assertEquals(Step.WATCH, v.state.value.step)
    }

    @Test fun watchStepIsOptionalAndNeverBlocks() {
        val e = Env(); val v = vm(e); v.next(); v.next(); v.next()
        assertEquals(Step.WATCH, v.state.value.step)
        assertEquals("Waiting for the watch\u2026", v.state.value.watchLine)
        assertEquals(false, v.state.value.watchLinked)
        v.secondary() // Skip
        assertEquals(Step.AI, v.state.value.step)
        v.back(); v.primary {} // Continue also works while still waiting
        assertEquals(Step.AI, v.state.value.step)
    }

    @Test fun watchStepTurnsConfirmedOnTheFirstAcceptedMessageAndStaysSo() {
        val e = Env(); val v = vm(e); v.next(); v.next(); v.next()
        v.enableWatch(); assertTrue(e.watchOn); assertTrue(v.state.value.watchOn)
        e.watchConfirmed.value = true
        assertEquals("Watch link confirmed", v.state.value.watchLine)
        e.watchConfirmed.value = false
        assertEquals("Watch link confirmed", v.state.value.watchLine)
    }

    @Test fun authorizeGlassesCallsDeps() {
        val e = Env(); vm(e).authorizeGlasses(); assertEquals(1, e.authorized)
    }

    @Test fun glassesLinkStateIsMirrored() {
        val e = Env(); val v = vm(e)
        e.link.value = LinkState.CONNECTED
        assertEquals(LinkState.CONNECTED, v.state.value.glasses)
    }

    @Test fun stepSurvivesRecreation() {
        val saved = SavedStateHandle()
        val a = vm(saved = saved); a.next(); a.next()
        assertEquals(Step.GLASSES, vm(saved = saved).state.value.step)
    }
}
