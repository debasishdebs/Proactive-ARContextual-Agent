package com.rokid.context.phone.ui.settings.inputs

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class FakeVoiceControls(caps: VoiceCapabilities, var download: PackDownload = PackDownload.DONE) : VoiceControls {
    override val capabilities = MutableStateFlow(caps)
    override val settings = MutableStateFlow(VoiceSettings())
    var rechecks = 0
    var opened = 0
    override fun setLanguage(language: SpeechLanguage) { settings.value = settings.value.copy(language = language) }
    override fun setPhoneMic(on: Boolean) { settings.value = settings.value.copy(phoneMic = on) }
    override fun recheck() { rechecks++ }
    override suspend fun downloadPack(): PackDownload = download
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
}
