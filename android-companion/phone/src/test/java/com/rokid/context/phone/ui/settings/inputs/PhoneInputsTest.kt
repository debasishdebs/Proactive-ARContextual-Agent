package com.rokid.context.phone.ui.settings.inputs

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.voice.PackDownload
import com.rokid.context.phone.voice.VoiceControls
import com.rokid.context.voice.SpeechLanguage
import com.rokid.context.voice.VoiceCapabilities
import com.rokid.context.voice.VoiceSettings
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class FakeVoiceControls(caps: VoiceCapabilities, var download: PackDownload = PackDownload.DONE) : VoiceControls {
    var throwOnDownload = false
    var downloadGate: CompletableDeferred<Unit>? = null
    override val capabilities = MutableStateFlow(caps)
    override val settings = MutableStateFlow(VoiceSettings())
    var rechecks = 0
    var opened = 0
    override fun setLanguage(language: SpeechLanguage) { settings.value = settings.value.copy(language = language) }
    override fun setPhoneMic(on: Boolean) { settings.value = settings.value.copy(phoneMic = on) }
    override fun recheck() { rechecks++ }
    override suspend fun downloadPack(): PackDownload {
        downloadGate?.await()
        if (throwOnDownload) throw IllegalStateException("boom")
        return download
    }
    override fun openSpeechSettings() { opened++ }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PhoneInputsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    @Test fun rowsFollowTheGateAndOpeningTheScreenRechecks() = runBlocking {
        val voice = FakeVoiceControls(VoiceCapabilities(VoiceStatus.NOT_VERIFIED, VoiceStatus.SUPPORTED, canDownloadPack = true))
        val vm = PhoneInputsViewModel(voice)
        try {
            val s = withTimeout(5_000) { vm.state.first { it.phoneVoice == "Supported" } }
            assertEquals("Not checked yet", s.glassesVoice)
            assertEquals(1, voice.rechecks)
            voice.capabilities.value = VoiceCapabilities(VoiceStatus.NEEDS_NEWER_ANDROID, VoiceStatus.SUPPORTED, canDownloadPack = false)
            assertEquals("Needs Android 13+ for glasses voice", withTimeout(5_000) { vm.state.first { it.glassesVoice != "Not checked yet" } }.glassesVoice)
        } finally { vm.cancelScope() }
    }

    @Test fun aMissingPackDownloadsOnFourteenAndSaysWhatHappened() = runBlocking {
        val voice = FakeVoiceControls(VoiceCapabilities(VoiceStatus.NEEDS_SPEECH_PACK, VoiceStatus.NEEDS_SPEECH_PACK, canDownloadPack = true), PackDownload.SCHEDULED)
        val vm = PhoneInputsViewModel(voice)
        try {
            assertEquals(true, withTimeout(5_000) { vm.state.first { it.needsPack } }.canDownload)
            vm.download()
            assertEquals("Download scheduled — it finishes in the background", withTimeout(5_000) { vm.state.first { it.notice != null && !it.busy } }.notice)
            vm.setLanguage(SpeechLanguage.HINDI); vm.setPhoneMic(false)
            assertEquals(VoiceSettings(SpeechLanguage.HINDI, phoneMic = false), voice.settings.value)
        } finally { vm.cancelScope() }
    }

    @Test fun belowFourteenThePackIsDownloadedInSystemSettings() {
        var opened = 0
        compose.setContent {
            PcTheme {
                PhoneInputsScreen(PhoneInputsUiState(glassesVoice = "Needs speech pack", phoneVoice = "Needs speech pack", needsPack = true, canDownload = false),
                    onBack = {}, onAllowMic = {}, onDownload = {}, onOpenSettings = { opened++ }, onRecheck = {}, onLanguage = {}, onPhoneMic = {})
            }
        }
        compose.onNodeWithTag("phone_inputs").performScrollToNode(hasTestTag("open_speech_settings"))
        compose.onNodeWithTag("open_speech_settings").performClick()
        assertEquals(1, opened)
    }

    @Test fun aMissingPermissionOffersAllowMicrophoneAndThePrivacyNoteShows() {
        var allow = 0
        compose.setContent {
            PcTheme {
                PhoneInputsScreen(PhoneInputsUiState(glassesVoice = "Needs microphone permission", phoneVoice = "Needs microphone permission", needsMic = true),
                    onBack = {}, onAllowMic = { allow++ }, onDownload = {}, onOpenSettings = {}, onRecheck = {}, onLanguage = {}, onPhoneMic = {})
            }
        }
        compose.onNodeWithTag("phone_inputs").performScrollToNode(hasTestTag("allow_mic"))
        compose.onNodeWithTag("allow_mic").performClick()
        assertEquals(1, allow)
        compose.onNodeWithTag("phone_inputs").performScrollToNode(hasTestTag("voice_privacy"))
        compose.onNodeWithText("Speech is recognised on this phone; audio never leaves it.").assertIsDisplayed()
    }

    // Fix round 1

    @Test fun aPermanentlyDeniedMicOffersAppSettingsAndAResumeRechecks() = runBlocking {
        val voice = FakeVoiceControls(VoiceCapabilities(VoiceStatus.NEEDS_MIC_PERMISSION, VoiceStatus.NEEDS_MIC_PERMISSION, canDownloadPack = true))
        val vm = PhoneInputsViewModel(voice)
        try {
            withTimeout(5_000) { vm.state.first { it.needsMic } }
            vm.onMicResult(granted = false, canAskAgain = true)
            assertEquals(false, withTimeout(5_000) { vm.state.first { it.needsMic } }.micBlocked)
            vm.onMicResult(granted = false, canAskAgain = false)
            assertEquals(true, withTimeout(5_000) { vm.state.first { it.micBlocked } }.micBlocked)
            assertEquals(3, voice.rechecks)
            vm.onResume() // the first resume follows init's own re-check
            assertEquals(3, voice.rechecks)
            vm.onResume() // back from app settings: permission and capabilities are read again
            assertEquals(4, voice.rechecks)
        } finally { vm.cancelScope() }
    }

    @Test fun callStatePermissionStatusModel() = runBlocking {
        var granted = false
        val voice = FakeVoiceControls(VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, canDownloadPack = false))
        val vm = PhoneInputsViewModel(voice, callStateGranted = { granted })
        try {
            assertEquals(false, withTimeout(5_000) { vm.state.first() }.callStateGranted)
            vm.onCallStateResult(granted = false, canAskAgain = true)
            assertEquals(false, vm.state.first { !it.callStateGranted }.callStateBlocked)
            vm.onCallStateResult(granted = false, canAskAgain = false)
            assertEquals(true, withTimeout(5_000) { vm.state.first { it.callStateBlocked } }.callStateBlocked)
            granted = true
            vm.onResume() // back from app settings: granted, no longer blocked
            val s = withTimeout(5_000) { vm.state.first { it.callStateGranted } }
            assertEquals(false, s.callStateBlocked)
        } finally { vm.cancelScope() }
    }

    @Test fun callStateRowShowsStatusAndTheRightButton() {
        var allow = 0
        var app = 0
        var st by androidx.compose.runtime.mutableStateOf(PhoneInputsUiState())
        compose.setContent {
            PcTheme {
                PhoneInputsScreen(st, onBack = {}, onAllowMic = {}, onDownload = {}, onOpenSettings = {}, onRecheck = {}, onLanguage = {}, onPhoneMic = {},
                    onOpenAppSettings = { app++ }, onAllowCallState = { allow++ })
            }
        }
        compose.onNodeWithTag("phone_inputs").performScrollToNode(hasTestTag("call_state_allow"))
        compose.onNodeWithText("Not granted").assertIsDisplayed()
        compose.onNodeWithTag("call_state_allow").performClick()
        assertEquals(1, allow)
        compose.runOnIdle { st = PhoneInputsUiState(callStateBlocked = true) }
        compose.onNodeWithTag("call_state_allow").assertDoesNotExist()
        compose.onNodeWithTag("phone_inputs").performScrollToNode(hasTestTag("call_state_open_settings"))
        compose.onNodeWithTag("call_state_open_settings").performClick()
        assertEquals(1, app)
        compose.runOnIdle { st = PhoneInputsUiState(callStateGranted = true) }
        compose.onNodeWithTag("call_state_allow").assertDoesNotExist()
        compose.onNodeWithTag("call_state_open_settings").assertDoesNotExist()
        compose.onNodeWithText("Granted").assertIsDisplayed()
    }

    @Test fun aBlockedMicShowsOpenAppSettingsInsteadOfAllow() {
        var app = 0
        compose.setContent {
            PcTheme {
                PhoneInputsScreen(PhoneInputsUiState(glassesVoice = "Needs microphone permission", phoneVoice = "Needs microphone permission", needsMic = true, micBlocked = true),
                    onBack = {}, onAllowMic = {}, onDownload = {}, onOpenSettings = {}, onRecheck = {}, onLanguage = {}, onPhoneMic = {}, onOpenAppSettings = { app++ })
            }
        }
        compose.onNodeWithTag("allow_mic").assertDoesNotExist()
        compose.onNodeWithTag("phone_inputs").performScrollToNode(hasTestTag("open_app_settings"))
        compose.onNodeWithTag("open_app_settings").performClick()
        assertEquals(1, app)
    }

    @Test fun theScreenRechecksOnResume() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        var resumes = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PcTheme {
                    PhoneInputsScreen(PhoneInputsUiState(), onBack = {}, onAllowMic = {}, onDownload = {}, onOpenSettings = {}, onRecheck = {},
                        onLanguage = {}, onPhoneMic = {}, onResume = { resumes++ })
                }
            }
        }
        compose.runOnIdle { assertEquals(0, resumes); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(1, resumes); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(2, resumes) }
    }

    @Test fun aFailedDownloadAlwaysOffersSpeechSettings() = runBlocking {
        val voice = FakeVoiceControls(VoiceCapabilities(VoiceStatus.NEEDS_SPEECH_PACK, VoiceStatus.NEEDS_SPEECH_PACK, canDownloadPack = true), PackDownload.FAILED)
        val vm = PhoneInputsViewModel(voice)
        try {
            assertEquals(false, withTimeout(5_000) { vm.state.first { it.needsPack } }.offerSettings)
            vm.download()
            assertEquals(true, withTimeout(5_000) { vm.state.first { it.notice != null && !it.busy } }.offerSettings)
        } finally { vm.cancelScope() }
        val needs = FakeVoiceControls(VoiceCapabilities(VoiceStatus.NEEDS_SPEECH_PACK, VoiceStatus.NEEDS_SPEECH_PACK, canDownloadPack = true), PackDownload.NEEDS_SETTINGS)
        val vm2 = PhoneInputsViewModel(needs)
        try {
            withTimeout(5_000) { vm2.state.first { it.needsPack } }
            vm2.download()
            assertEquals(true, withTimeout(5_000) { vm2.state.first { it.notice != null && !it.busy } }.offerSettings)
        } finally { vm2.cancelScope() }
    }

    @Test fun offeredSpeechSettingsShowNextToDownload() {
        var opened = 0
        compose.setContent {
            PcTheme {
                PhoneInputsScreen(PhoneInputsUiState(needsPack = true, canDownload = true, offerSettings = true),
                    onBack = {}, onAllowMic = {}, onDownload = {}, onOpenSettings = { opened++ }, onRecheck = {}, onLanguage = {}, onPhoneMic = {})
            }
        }
        compose.onNodeWithTag("phone_inputs").performScrollToNode(hasTestTag("download_pack"))
        compose.onNodeWithTag("download_pack").assertIsDisplayed()
        compose.onNodeWithTag("phone_inputs").performScrollToNode(hasTestTag("open_speech_settings"))
        compose.onNodeWithTag("open_speech_settings").performClick()
        assertEquals(1, opened)
    }

    @Test fun checkingClearsWhenTheCapabilitiesUpdate() = runBlocking {
        val voice = FakeVoiceControls(VoiceCapabilities(VoiceStatus.NOT_VERIFIED, VoiceStatus.SUPPORTED, canDownloadPack = true))
        val vm = PhoneInputsViewModel(voice)
        try {
            withTimeout(5_000) { vm.state.first { it.glassesVoice == "Not checked yet" } }
            vm.recheck()
            assertEquals(PhoneInputsViewModel.CHECKING, withTimeout(5_000) { vm.state.first { it.notice != null } }.notice)
            voice.capabilities.value = VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, canDownloadPack = true)
            assertNull(withTimeout(5_000) { vm.state.first { it.glassesVoice == "Supported" && it.notice == null } }.notice)
        } finally { vm.cancelScope() }
    }

    @Test fun aDownloadShowsProgressAndAThrowSaysFailed() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val voice = FakeVoiceControls(VoiceCapabilities(VoiceStatus.NEEDS_SPEECH_PACK, VoiceStatus.NEEDS_SPEECH_PACK, canDownloadPack = true))
        voice.downloadGate = gate; voice.throwOnDownload = true
        val vm = PhoneInputsViewModel(voice)
        try {
            withTimeout(5_000) { vm.state.first { it.needsPack } }
            vm.download()
            assertEquals("Downloading speech pack\u2026", withTimeout(5_000) { vm.state.first { it.busy } }.notice)
            gate.complete(Unit)
            val done = withTimeout(5_000) { vm.state.first { !it.busy && it.notice != null } }
            assertEquals("Download failed \u2014 try Open speech settings", done.notice)
            assertEquals(true, done.offerSettings)
        } finally { vm.cancelScope() }
    }

    @Test fun aStatusLineReadsAsOneItem() {
        compose.setContent {
            PcTheme {
                PhoneInputsScreen(PhoneInputsUiState(glassesVoice = "Supported"),
                    onBack = {}, onAllowMic = {}, onDownload = {}, onOpenSettings = {}, onRecheck = {}, onLanguage = {}, onPhoneMic = {})
            }
        }
        compose.onNodeWithTag("voice_glasses").assert(hasText("Glasses voice")).assert(hasText("Supported"))
    }

    // Phase 7 C2: phone "Capture now"

    @Test fun captureNowDisabledWhilePaused() {
        compose.setContent {
            PcTheme {
                PhoneInputsScreen(PhoneInputsUiState(capturePaused = true), onBack = {}, onAllowMic = {}, onDownload = {}, onOpenSettings = {}, onRecheck = {}, onLanguage = {}, onPhoneMic = {})
            }
        }
        compose.onNodeWithTag("phone_inputs").performScrollToNode(hasTestTag("capture_now"))
        compose.onNodeWithTag("capture_now").assertIsNotEnabled()
        compose.onNodeWithTag("phone_inputs").performScrollToNode(hasTestTag("capture_paused"))
        compose.onNodeWithText("Paused \u2014 resume to capture").assertIsDisplayed()
    }

    @Test fun captureNowFollowsThePauseAndSaysWhatHappened() = runBlocking {
        val capture = FakeCaptureNow()
        val vm = PhoneInputsViewModel(FakeVoiceControls(VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, canDownloadPack = false)), capture)
        try {
            capture.paused.value = true
            withTimeout(5_000) { vm.state.first { it.capturePaused } }
            capture.paused.value = false
            withTimeout(5_000) { vm.state.first { !it.capturePaused } }
            var launched: android.net.Uri? = null
            vm.captureNow { launched = it }
            withTimeout(5_000) { while (launched == null) kotlinx.coroutines.yield() }
            vm.onPictureTaken(false)
            assertEquals(listOf(false), capture.finished)
            assertNull(vm.state.value.captureNotice)
            vm.captureNow { launched = it }
            vm.onPictureTaken(true)
            assertEquals("Captured", withTimeout(5_000) { vm.state.first { it.captureNotice != null } }.captureNotice)
            capture.ok = false
            vm.captureNow {}
            vm.onPictureTaken(true)
            assertEquals("Couldn't save the photo", withTimeout(5_000) { vm.state.first { it.captureNotice != "Captured" && it.captureNotice != null } }.captureNotice)
        } finally { vm.cancelScope() }
    }

    /** Fix round 1 (I2): a pause that began during the shot ingests nothing and says so; a launch failure frees the button. */
    @Test fun aPauseDuringTheShotShowsThePausedCopyAndALaunchFailureResetsBusy() = runBlocking {
        val capture = FakeCaptureNow()
        val vm = PhoneInputsViewModel(FakeVoiceControls(VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, canDownloadPack = false)), capture)
        try {
            assertTrue("opening the screen re-reads the pause", capture.refreshes >= 1)
            capture.pausedAtFinish = true
            vm.captureNow {}
            vm.onPictureTaken(true)
            assertEquals("Paused \u2014 resume to capture", withTimeout(5_000) { vm.state.first { it.captureNotice != null } }.captureNotice)
            vm.captureNow { throw IllegalStateException("no camera") }
            assertEquals(false, withTimeout(5_000) { vm.state.first { !it.captureBusy && it.captureNotice == "Couldn't save the photo" } }.captureBusy)
        } finally { vm.cancelScope() }
    }
}

class FakeCaptureNow : com.rokid.context.phone.capture.CaptureNowControl {
    override val paused = MutableStateFlow(false)
    var ok = true
    var pausedAtFinish = false
    var throwOnLaunch = false
    var refreshes = 0
    val finished = mutableListOf<Boolean>()
    override fun refresh() { refreshes++ }
    override suspend fun prepare(): com.rokid.context.phone.capture.CapturePrepare =
        if (paused.value) com.rokid.context.phone.capture.CapturePrepare.Paused
        else com.rokid.context.phone.capture.CapturePrepare.Ready(android.net.Uri.parse("content://x/capture/phone_1.jpg"))
    override suspend fun finish(saved: Boolean): com.rokid.context.phone.capture.CaptureResult {
        finished += saved
        return when {
            !saved -> com.rokid.context.phone.capture.CaptureResult.CANCELLED
            pausedAtFinish -> com.rokid.context.phone.capture.CaptureResult.PAUSED
            ok -> com.rokid.context.phone.capture.CaptureResult.SAVED
            else -> com.rokid.context.phone.capture.CaptureResult.FAILED
        }
    }
}
