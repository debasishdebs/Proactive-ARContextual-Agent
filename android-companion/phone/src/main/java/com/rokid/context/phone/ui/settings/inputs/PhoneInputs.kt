package com.rokid.context.phone.ui.settings.inputs

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import com.rokid.context.phone.voice.PackDownload
import com.rokid.context.phone.voice.VoiceControls
import com.rokid.context.voice.SpeechLanguage
import com.rokid.context.voice.VoiceStatus
import com.rokid.context.voice.VoiceStatusText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class PhoneInputsUiState(
    val glassesVoice: String = VoiceStatusText.glasses(VoiceStatus.CHECKING),
    val phoneVoice: String = VoiceStatusText.phone(VoiceStatus.CHECKING),
    val needsMic: Boolean = false,
    val needsPack: Boolean = false,
    val canDownload: Boolean = false,
    val language: SpeechLanguage = SpeechLanguage.ENGLISH,
    val phoneMic: Boolean = true,
    val busy: Boolean = false,
    val notice: String? = null,
)

/** spec §2.4 Devices › Phone camera & mic and §5.1.1 Voice status (Supported / Needs speech pack / Needs Android 13+). */
class PhoneInputsViewModel(private val voice: VoiceControls) : ViewModel() {
    private data class Local(val busy: Boolean = false, val notice: String? = null)
    private val local = MutableStateFlow(Local())

    val state: StateFlow<PhoneInputsUiState> = combine(voice.capabilities, voice.settings, local) { c, s, l ->
        PhoneInputsUiState(
            glassesVoice = VoiceStatusText.glasses(c.glasses), phoneVoice = VoiceStatusText.phone(c.phone),
            needsMic = c.phone == VoiceStatus.NEEDS_MIC_PERMISSION,
            needsPack = c.phone == VoiceStatus.NEEDS_SPEECH_PACK || c.glasses == VoiceStatus.NEEDS_SPEECH_PACK,
            canDownload = c.canDownloadPack, language = s.language, phoneMic = s.phoneMic, busy = l.busy, notice = l.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhoneInputsUiState())

    /** Opening the screen re-checks the gate (and probes external audio when needed: the app is in front). */
    init { voice.recheck() }

    fun setLanguage(language: SpeechLanguage) { local.update { it.copy(notice = null) }; voice.setLanguage(language) }
    fun setPhoneMic(on: Boolean) = voice.setPhoneMic(on)
    fun recheck() { local.update { it.copy(notice = CHECKING) }; voice.recheck() }
    /** The RECORD_AUDIO dialog closed (granted or not): the gate re-reads it. */
    fun onMicResult() = voice.recheck()
    fun openSettings() = voice.openSpeechSettings()

    fun download() {
        if (local.value.busy) return
        local.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val r = try {
                voice.downloadPack()
            } catch (c: CancellationException) {
                local.update { it.copy(busy = false) }
                throw c
            } catch (e: Exception) {
                PackDownload.FAILED
            }
            local.update { it.copy(busy = false, notice = noticeFor(r)) }
        }
    }

    companion object {
        const val CHECKING = "Checking voice…"

        fun noticeFor(r: PackDownload): String = when (r) {
            PackDownload.DONE -> "Speech pack installed"
            PackDownload.SCHEDULED -> "Download scheduled — it finishes in the background"
            PackDownload.FAILED -> "Download failed — try Open speech settings"
            PackDownload.NEEDS_SETTINGS -> "Open speech settings to download the pack"
        }
    }
}

@Composable
fun PhoneInputsScreen(
    state: PhoneInputsUiState,
    onBack: () -> Unit,
    onAllowMic: () -> Unit,
    onDownload: () -> Unit,
    onOpenSettings: () -> Unit,
    onRecheck: () -> Unit,
    onLanguage: (SpeechLanguage) -> Unit,
    onPhoneMic: (Boolean) -> Unit,
) {
    SettingsPage("Phone camera & mic", onBack, tag = "phone_inputs") {
        item {
            SettingsCard {
                Text("Voice", style = MaterialTheme.typography.titleMedium)
                StatusLine("Glasses voice", state.glassesVoice, tag = "voice_glasses")
                StatusLine("Phone voice", state.phoneVoice, tag = "voice_phone")
                if (state.needsMic) PrimaryAction("Allow microphone", onAllowMic, tag = "allow_mic")
                if (state.needsPack) {
                    if (state.canDownload) PrimaryAction("Download speech pack", onDownload, tag = "download_pack", enabled = !state.busy)
                    else SecondaryAction("Open speech settings", onOpenSettings, tag = "open_speech_settings")
                }
                SecondaryAction("Check again", onRecheck, tag = "voice_recheck", enabled = !state.busy)
                state.notice?.let { NoticeText(it, tag = "voice_notice") }
                NoticeText("Speech is recognised on this phone; audio never leaves it.", tag = "voice_privacy")
            }
        }
        item {
            SettingsCard {
                val langs = SpeechLanguage.entries
                ChoiceRow("Speech language", langs.map { it.label }, langs.indexOf(state.language), { onLanguage(langs[it]) }, tag = "voice_language")
                NoticeText("Hindi needs the Hindi speech pack on this phone.", tag = "voice_language_note")
                SwitchRow("Use the phone mic for Ask", "Ask from Home with your voice", state.phoneMic, onPhoneMic, tag = "phone_mic")
            }
        }
        item {
            SettingsCard {
                Text("Phone camera", style = MaterialTheme.typography.titleMedium)
                NoticeText("Capture now with the phone camera — coming soon", tag = "phone_camera")
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.testTag(tag))
    }
}
