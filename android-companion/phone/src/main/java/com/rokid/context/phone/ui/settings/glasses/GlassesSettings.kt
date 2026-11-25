package com.rokid.context.phone.ui.settings.glasses

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.rokid.context.core.model.IconCorner
import com.rokid.context.core.model.POPUP_MAX_SEC
import com.rokid.context.core.model.POPUP_MIN_SEC
import com.rokid.context.core.model.clampPopupSeconds
import com.rokid.context.core.model.Verbosity
import com.rokid.context.core.ui.components.HudPreview
import com.rokid.context.core.ui.components.SegmentedSwitch
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.HudSettingsControl
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SwitchRow
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
import kotlin.math.roundToInt

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
    fun setIconCorner(c: IconCorner) = hud.update { it.copy(iconCorner = c) }
    fun setPopupSeconds(s: Int) = hud.update { it.copy(popupSeconds = clampPopupSeconds(s)) }
    fun setWakeForCards(on: Boolean) = hud.update { it.copy(wakeForCards = on) }
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
    val ICON_CORNERS = mapOf(
        IconCorner.TOP_LEFT to "Top left", IconCorner.TOP_RIGHT to "Top right",
        IconCorner.BOTTOM_LEFT to "Bottom left", IconCorner.BOTTOM_RIGHT to "Bottom right",
    )
    const val OVERLAY_HINT = "For the minimised icon, allow 'display over other apps' for Proactive Context on the glasses."
    val VOICE_AVAILABLE = VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, canDownloadPack = true)

    /**
     * Why tap = Ask can't work from the glasses, in phone words (the HUD gets its own short text), or null.
     * [VoiceCapabilities.micHeld] is ignored on purpose: this screen is only visible with the app in front, where the
     * hub holds the mic, and a mic not held is a transient state the HUD toast (`askBlockedReason`) explains.
     */
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
    onIconCorner: (IconCorner) -> Unit = {},
    onPopupSeconds: (Int) -> Unit = {},
    onWakeForCards: (Boolean) -> Unit = {},
    onOpenNearby: () -> Unit = {},
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
                SecondaryAction("Nearby devices", onOpenNearby, tag = "open_nearby")
                NoticeText("Paired glasses, and whether the app keeps running near them", tag = "nearby_hint")
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
                // Phase 5 T16 carry: the finger's value wins while dragging, whatever the stored scale does; one write on release.
                // Fix round 1: after release it stays until the stored scale next changes (the write landing); a cancelled
                // gesture drops it, since onValueChangeFinished is not guaranteed then.
                var dragging by remember { mutableStateOf<Float?>(null) }
                var released by remember { mutableStateOf(false) }
                val interactions = remember { MutableInteractionSource() }
                LaunchedEffect(s.scale) { if (released) { dragging = null; released = false } }
                LaunchedEffect(interactions) {
                    interactions.interactions.collect { i -> if (i is DragInteraction.Cancel || i is PressInteraction.Cancel) { dragging = null; released = false } }
                }
                val shown = ScaleSlider.shown(dragging, s.scale)
                Text("HUD size ${String.format(Locale.US, "%.0f", shown * 100)} %", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = shown, onValueChange = { dragging = it; released = false },
                    onValueChangeFinished = { dragging?.let { v -> onScale(v); if (v == s.scale) dragging = null else released = true } },
                    interactionSource = interactions,
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
                    ChoiceRow(label, actions.map { GlassesLabels.ACTIONS.getValue(it) }, actions.indexOf(current), { onGesture(g, actions[it]) }, tag = "gesture_${g.name}", disabled = disabled, disabledReason = state.askUnavailable)
                }
                state.askUnavailable?.let { NoticeText("Ask by voice from the glasses: $it — see Phone camera & mic", tag = "ask_unavailable") }
                NoticeText("Long-press stays reserved for \"Hi Rokid\".")
            }
        }
        item {
            SettingsCard {
                Text("Minimised", style = MaterialTheme.typography.titleMedium)
                val corners = IconCorner.entries
                ChoiceRow("Icon corner", corners.map { GlassesLabels.ICON_CORNERS.getValue(it) }, corners.indexOf(s.iconCorner), { onIconCorner(corners[it]) }, tag = "icon_corner")
                // One write per release, like the HUD size: the drag stays local.
                var dragging by remember { mutableStateOf<Float?>(null) }
                LaunchedEffect(s.popupSeconds) { dragging = null }
                val shownSec = dragging?.roundToInt() ?: clampPopupSeconds(s.popupSeconds)
                Text("Pop-up time $shownSec s", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = dragging ?: clampPopupSeconds(s.popupSeconds).toFloat(), onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { onPopupSeconds(clampPopupSeconds(it.roundToInt())) } },
                    valueRange = POPUP_MIN_SEC.toFloat()..POPUP_MAX_SEC.toFloat(), steps = POPUP_MAX_SEC - POPUP_MIN_SEC - 1,
                    modifier = Modifier.testTag("popup_seconds").semantics { contentDescription = "Pop-up time" },
                )
                SwitchRow("Wake glasses for cards", "Briefly switch the screen on when a place card pops up while it is off (at most once a minute).", s.wakeForCards, onWakeForCards, tag = "wake_for_cards")
                NoticeText(GlassesLabels.OVERLAY_HINT, tag = "overlay_hint")
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

/** The HUD-size slider shows the finger's value while dragging and the stored scale otherwise. */
internal object ScaleSlider {
    fun shown(dragging: Float?, stored: Float): Float = dragging ?: stored
}
