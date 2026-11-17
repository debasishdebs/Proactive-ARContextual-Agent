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
data class NexusUiState(val host: HudHost = HudHost.PCA, val installed: NexusInstalled = NexusInstalled(false, false, false), val state: NexusState = NexusState.BRIDGE_NOT_INSTALLED)

/** What the screen needs (production: [AndroidNexusControl]; tests: a fake). */
interface NexusControl {
    val state: StateFlow<NexusUiState>
    fun setHost(h: HudHost)
    /** Reads which apps are installed again (they can change while the screen is open). */
    fun recheck()
}

class AndroidNexusControl(private val app: Context) : NexusControl {
    private val store = NexusHostStore.of(app)
    private val probe = NexusProbe(app.packageManager)
    private val installed = MutableStateFlow(probe.installed())

    // Collected only while the screen shows it (WhileSubscribed): no timer or collector runs otherwise.
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    override val state: StateFlow<NexusUiState> = combine(store.host, installed, NexusGraph.evidence) { h, inst, ev ->
        NexusUiState(h, inst, NexusStatus.of(NexusInputs(inst, h == HudHost.NEXUS, ev.bindRefused, ev.bound, ev.lastCode, ev.pcaLinkUp)))
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), NexusUiState(store.current(), installed.value, NexusStatus.of(NexusInputs(installed.value, store.current() == HudHost.NEXUS))))

    override fun setHost(h: HudHost) { store.set(h) }
    override fun recheck() { installed.value = probe.installed() }
}

object NexusSettingsModel {
    const val HOST_NOTE = "PCA's own glasses app draws the HUD by default. Rokid Nexus is optional and off until you choose it here. Switching withdraws everything from the old host."
    const val UNAVAILABLE = "Install the PCA Nexus bridge and Rokid Nexus first. PCA never installs them for you."
    fun hostOptions(): List<String> = listOf(NexusStatus.HOST_PCA, NexusStatus.HOST_NEXUS)
    /** Nexus can be chosen only when both apps are there (leaving it is always allowed). */
    fun canChooseNexus(i: NexusInstalled): Boolean = i.bridge && i.hub
}

@Composable
fun NexusRoute(control: NexusControl, back: () -> Unit) {
    val state by control.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { control.recheck() }
    NexusScreen(state, onHost = control::setHost, onRecheck = control::recheck, back = back)
}

@Composable
fun NexusScreen(state: NexusUiState, onHost: (HudHost) -> Unit, onRecheck: () -> Unit, back: () -> Unit) {
    SettingsPage("Rokid Nexus", back, tag = "nexus_settings") {
        item {
            SettingsCard {
                ChoiceRow(
                    "HUD host", NexusSettingsModel.hostOptions(), if (state.host == HudHost.NEXUS) 1 else 0,
                    { onHost(if (it == 1) HudHost.NEXUS else HudHost.PCA) }, tag = "nexus_host",
                    disabled = if (NexusSettingsModel.canChooseNexus(state.installed) || state.host == HudHost.NEXUS) emptySet() else setOf(1),
                    disabledReason = NexusSettingsModel.UNAVAILABLE,
                )
                NoticeText(NexusSettingsModel.HOST_NOTE, tag = "nexus_host_note")
            }
        }
        item {
            SettingsCard {
                Text(NexusStatus.title(state.state), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("nexus_state_title"))
                NoticeText(NexusStatus.detail(state.state), error = state.state == NexusState.CAPABILITY_DENIED || state.state == NexusState.BRIDGE_REFUSED, tag = "nexus_state_detail")
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
