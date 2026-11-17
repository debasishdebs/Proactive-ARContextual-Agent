package com.rokid.context.phone.ui.settings.nexus

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.core.model.HudHost
import com.rokid.context.phone.nexus.NexusGraph
import com.rokid.context.phone.nexus.NexusHostStore
import com.rokid.context.phone.nexus.NexusInputs
import com.rokid.context.phone.nexus.NexusInstalled
import com.rokid.context.phone.nexus.NexusProbe
import com.rokid.context.phone.nexus.NexusState
import com.rokid.context.phone.nexus.NexusStatus
import com.rokid.context.phone.nexus.NexusSwitches
import com.rokid.context.phone.ui.settings.SwitchRow
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the screen shows. */
data class NexusUiState(val host: HudHost = HudHost.PCA, val installed: NexusInstalled = NexusInstalled(false, false, false), val state: NexusState = NexusState.BRIDGE_NOT_INSTALLED, val switches: NexusSwitches = NexusSwitches())

/** What the screen needs (production: [AndroidNexusControl]; tests: a fake). */
interface NexusControl {
    val state: StateFlow<NexusUiState>
    fun setHost(h: HudHost)
    /** The three Nexus opt-ins (all OFF by default); the graph pushes the feature bits when they change. */
    fun setSwitches(s: NexusSwitches)
    /** Reads which apps are installed again (they can change while the screen is open). */
    fun recheck()
}

class AndroidNexusControl(private val app: Context) : NexusControl {
    private val store = NexusHostStore.of(app)
    private val probe = NexusProbe(app.packageManager, app.packageName)
    private val installed = MutableStateFlow(probe.installed())

    // Collected only while the screen shows it (WhileSubscribed): no timer or collector runs otherwise.
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    override val state: StateFlow<NexusUiState> = combine(store.host, store.switches, installed, NexusGraph.evidence) { h, sw, inst, ev ->
        NexusUiState(h, inst, NexusStatus.of(NexusInputs(inst, h == HudHost.NEXUS, ev.bindRefused, ev.bound, ev.lastCode, ev.pcaLinkUp, ev.bridgeUntrusted)), sw)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), NexusUiState(store.current(), installed.value, NexusStatus.of(NexusInputs(installed.value, store.current() == HudHost.NEXUS)), store.switchesNow()))

    override fun setHost(h: HudHost) { store.set(h) }
    override fun setSwitches(s: NexusSwitches) { store.setSwitches(s) }
    override fun recheck() { installed.value = probe.installed() }
}

object NexusSettingsModel {
    const val HOST_NOTE = "PCA's own glasses app draws the HUD by default. Rokid Nexus is optional and off until you choose it here. Switching withdraws everything from the old host."
    const val VOICE_TITLE = "Nexus voice (assist button)"
    const val VOICE_NOTE = "Lets the glasses' assist button ask PCA a question through Nexus speech. Off by default; PCA's own push-to-talk always works. Needs Rokid Nexus as the HUD host."
    const val TTS_TITLE = "Speak answers (TTS)"
    const val TTS_NOTE = "Nexus reads PCA's answers aloud. Off by default."
    const val SKILLS_TITLE = "Skills"
    const val SKILLS_NOTE = "Lets the Nexus Assistant check capture status, pause, resume and mark a moment. It never reads your memories. Resume never ends a pause near a protected area. Off by default."
    const val UNAVAILABLE = "Install the PCA Nexus bridge and Rokid Nexus first. PCA never installs them for you."
    fun hostOptions(): List<String> = listOf(NexusStatus.HOST_PCA, NexusStatus.HOST_NEXUS)
    /** Nexus can be chosen only when both apps are there (leaving it is always allowed). */
    fun canChooseNexus(i: NexusInstalled): Boolean = i.bridge && i.hub
}

@Composable
fun NexusRoute(control: NexusControl, back: () -> Unit) {
    val state by control.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { control.recheck() }
    NexusScreen(state, onHost = control::setHost, onRecheck = control::recheck, back = back, onSwitches = control::setSwitches)
}

@Composable
fun NexusScreen(state: NexusUiState, onHost: (HudHost) -> Unit, onRecheck: () -> Unit, back: () -> Unit, onSwitches: (NexusSwitches) -> Unit = {}) {
    SettingsPage("Rokid Nexus", back, tag = "nexus_settings") {
        item {
            SettingsCard {
                ChoiceRow(
                    "Show cards in Nexus", NexusSettingsModel.hostOptions(), if (state.host == HudHost.NEXUS) 1 else 0,
                    { onHost(if (it == 1) HudHost.NEXUS else HudHost.PCA) }, tag = "nexus_host",
                    disabled = if (NexusSettingsModel.canChooseNexus(state.installed) || state.host == HudHost.NEXUS) emptySet() else setOf(1),
                    disabledReason = NexusSettingsModel.UNAVAILABLE,
                )
                NoticeText(NexusSettingsModel.HOST_NOTE, tag = "nexus_host_note")
            }
        }
        item {
            SettingsCard {
                SwitchRow(NexusSettingsModel.VOICE_TITLE, NexusSettingsModel.VOICE_NOTE, state.switches.voice, { onSwitches(state.switches.copy(voice = it)) }, tag = "nexus_voice_switch")
                SwitchRow(NexusSettingsModel.TTS_TITLE, NexusSettingsModel.TTS_NOTE, state.switches.tts, { onSwitches(state.switches.copy(tts = it)) }, tag = "nexus_tts_switch")
                SwitchRow(NexusSettingsModel.SKILLS_TITLE, NexusSettingsModel.SKILLS_NOTE, state.switches.skills, { onSwitches(state.switches.copy(skills = it)) }, tag = "nexus_skills_switch")
            }
        }
        item {
            SettingsCard {
                Text(NexusStatus.title(state.state), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("nexus_state_title"))
                NoticeText(NexusStatus.detail(state.state), error = state.state == NexusState.CAPABILITY_DENIED || state.state == NexusState.BRIDGE_REFUSED || state.state == NexusState.BRIDGE_NOT_INSTALLED_UNTRUSTED, tag = "nexus_state_detail")
                if (state.installed.rokidCoder) NoticeText(NexusStatus.ROKID_CODER_NOTE, tag = "nexus_rc_note")
                SecondaryAction("Check again", onRecheck, tag = "nexus_recheck")
            }
        }
        item {
            SettingsCard {
                Text("What this means for your privacy", style = MaterialTheme.typography.titleMedium)
                Column { NexusStatus.TRUST_LINES.forEachIndexed { i, line -> NoticeText(line, tag = "nexus_trust_$i") } }
            }
        }
    }
}
