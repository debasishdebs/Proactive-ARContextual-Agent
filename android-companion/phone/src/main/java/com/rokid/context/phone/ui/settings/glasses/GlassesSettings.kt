package com.rokid.context.phone.ui.settings.glasses

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.hud.Hud
import com.rokid.context.core.hud.clampHudScale
import com.rokid.context.core.model.Gesture
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.HudPosition
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Verbosity
import com.rokid.context.core.ui.components.HudPreview
import com.rokid.context.core.ui.components.SegmentedSwitch
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.HudSettingsControl
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsModel
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.voice.VoiceCapabilities
import com.rokid.context.voice.VoiceStatus
import com.rokid.context.voice.VoiceStatusText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

data class GlassesSettingsUiState(val linkSummary: String = "Not connected", val settings: HudSettings = HudSettings(), val askUnavailable: String? = null)

/** spec §2.4 Devices › Rokid Glasses; every change goes to the hub, which persists and pushes `pc_settings`. */
class GlassesSettingsViewModel(
    glasses: GlassesStatusSource,
    private val hud: HudSettingsControl,
    voice: Flow<VoiceCapabilities> = flowOf(GlassesLabels.VOICE_AVAILABLE),
    private val reauthorizeAction: () -> Unit,
) : ViewModel() {
    val state: StateFlow<GlassesSettingsUiState> = combine(glasses.status, hud.settings, voice) { g, h, v ->
        GlassesSettingsUiState(SettingsModel.glassesSummary(g), h, GlassesLabels.askUnavailable(v))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlassesSettingsUiState(settings = hud.settings.value))

    fun setVerbosity(v: Verbosity) = hud.update { it.copy(verbosity = v) }
    fun setScale(s: Float) = hud.update { it.copy(scale = clampHudScale(s)) }
    fun setPosition(p: HudPosition) = hud.update { it.copy(position = p) }
    fun setGesture(g: Gesture, a: GestureAction) = hud.update { it.copy(gestures = it.gestures + (g to a)) }
    fun reauthorize() = reauthorizeAction()
}

object GlassesLabels {
    /** "Top left", "Bottom center", … for TalkBack. */
    fun position(p: HudPosition): String = p.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    val VERBOSITY = listOf("Silent", "Medium", "Max")
    val GESTURES = mapOf(Gesture.TAP to "Tap", Gesture.DOUBLE_TAP to "Double-tap", Gesture.SWIPE_FORWARD to "Swipe forward", Gesture.SWIPE_BACK to "Swipe back")
    val ACTIONS = mapOf(
        GestureAction.ASK to "Ask", GestureAction.PAUSE_RESUME to "Pause / resume", GestureAction.NEXT_PAGE to "Next page",
        GestureAction.PREV_PAGE to "Previous page", GestureAction.DISMISS to "Dismiss", GestureAction.NONE to "Nothing",
    )
    val VOICE_AVAILABLE = VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, canDownloadPack = true)

    /** Why tap = Ask can't work from the glasses, in phone words (the HUD gets its own short text), or null. */
    fun askUnavailable(c: VoiceCapabilities): String? = c.glasses.takeUnless { it == VoiceStatus.SUPPORTED }?.let(VoiceStatusText::glasses)
}

@Composable
fun GlassesSettingsScreen(
    state: GlassesSettingsUiState,
    onBack: () -> Unit,
    onVerbosity: (Verbosity) -> Unit,
    onScale: (Float) -> Unit,
    onPosition: (HudPosition) -> Unit,
    onGesture: (Gesture, GestureAction) -> Unit,
    onReauthorize: () -> Unit,
) {
    val s = state.settings
    SettingsPage("Rokid Glasses", onBack, tag = "glasses_settings") {
        item {
            SettingsCard {
                Text(state.linkSummary, style = MaterialTheme.typography.titleMedium)
                SecondaryAction("Re-authorize", onReauthorize, tag = "reauthorize")
            }
        }
        item {
            SettingsCard {
                Text("Verbosity", style = MaterialTheme.typography.titleMedium)
                SegmentedSwitch(GlassesLabels.VERBOSITY, s.verbosity.ordinal, { onVerbosity(Verbosity.entries[it]) })
                HudPreview(s, Modifier.fillMaxWidth(0.6f).align(Alignment.CenterHorizontally))
                NoticeText(when (s.verbosity) {
                    Verbosity.SILENT -> "REC dot only; overlays only for what you start (Ask, Confirm, Pause)."
                    Verbosity.MEDIUM -> "Glance page when idle; nudges as a one-liner."
                    Verbosity.MAX -> "Glance and Status pages; nudges pop up as toasts."
                })
            }
        }
        item {
            SettingsCard {
                // Fix round 1 (I2): the drag stays local; one write (one `pc_settings` push) when the finger lifts.
                var dragging by remember(s.scale) { mutableFloatStateOf(s.scale) }
                Text("HUD size ${String.format(Locale.US, "%.0f", dragging * 100)} %", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = dragging, onValueChange = { dragging = it }, onValueChangeFinished = { onScale(dragging) },
                    valueRange = Hud.MIN_SCALE..Hud.MAX_SCALE, modifier = Modifier.testTag("hud_scale").semantics { contentDescription = "HUD size" },
                )
                Text("Position", style = MaterialTheme.typography.titleMedium)
                PositionGrid(s.position, onPosition)
            }
        }
        item {
            SettingsCard {
                Text("Gestures", style = MaterialTheme.typography.titleMedium)
                val actions = GestureAction.entries
                val disabled = if (state.askUnavailable != null) setOf(actions.indexOf(GestureAction.ASK)) else emptySet()
                GlassesLabels.GESTURES.forEach { (g, label) ->
                    val current = s.gestures[g] ?: GestureAction.NONE
                    ChoiceRow(label, actions.map { GlassesLabels.ACTIONS.getValue(it) }, actions.indexOf(current), { onGesture(g, actions[it]) }, tag = "gesture_${g.name}", disabled = disabled)
                }
                state.askUnavailable?.let { NoticeText("Ask by voice from the glasses: $it — see Phone camera & mic", tag = "ask_unavailable") }
                NoticeText("Long-press stays reserved for \"Hi Rokid\".")
            }
        }
    }
}

@Composable
private fun PositionGrid(selected: HudPosition, onPick: (HudPosition) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HudPosition.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { p ->
                    val on = p == selected
                    Box(
                        Modifier.size(48.dp).border(if (on) 3.dp else 1.dp, if (on) PcColors.MintDeep else PcColors.Line, RoundedCornerShape(8.dp))
                            .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(p) })
                            .semantics { contentDescription = GlassesLabels.position(p) }.testTag("pos_${p.name}"),
                    )
                }
            }
        }
    }
}
