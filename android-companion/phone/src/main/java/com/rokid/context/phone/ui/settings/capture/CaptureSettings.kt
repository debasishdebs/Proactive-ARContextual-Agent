package com.rokid.context.phone.ui.settings.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.HudSettings
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.HudSettingsControl
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Glasses `CaptureCadence` accepts 30 s – 1 h (Phase 3 ruling); everything shown or saved is clamped to that. */
object CaptureIntervals {
    const val MIN_SEC = 30
    const val MAX_SEC = 3_600
    val PRESETS_SEC = listOf(30, 60, 120, 300, 600, 900, 1_800, 3_600)

    fun clamp(sec: Int): Int = sec.coerceIn(MIN_SEC, MAX_SEC)
    fun label(sec: Int): String = when {
        sec >= 3_600 && sec % 3_600 == 0 -> "${sec / 3_600} h"
        sec >= 60 && sec % 60 == 0 -> "${sec / 60} min"
        else -> "$sec s"
    }
    /** The presets, plus a stored custom value so it stays selectable. */
    fun options(current: Int): List<Int> = (PRESETS_SEC + clamp(current)).distinct().sorted()
}

data class CaptureSettingsUiState(val intervalSec: Int = 300, val burstOnChange: Boolean = true)

class CaptureSettingsViewModel(private val hud: HudSettingsControl) : ViewModel() {
    private fun of(h: HudSettings) = CaptureSettingsUiState(CaptureIntervals.clamp(h.captureIntervalSec), h.burstOnChange)
    val state: StateFlow<CaptureSettingsUiState> = hud.settings.map(::of).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), of(hud.settings.value))

    fun setInterval(sec: Int) = hud.update { it.copy(captureIntervalSec = CaptureIntervals.clamp(sec)) }
    fun setBurst(on: Boolean) = hud.update { it.copy(burstOnChange = on) }
}

@Composable
fun CaptureSettingsScreen(state: CaptureSettingsUiState, onBack: () -> Unit, onInterval: (Int) -> Unit, onBurst: (Boolean) -> Unit) {
    val options = CaptureIntervals.options(state.intervalSec)
    SettingsPage("Capture schedule", onBack, tag = "capture_settings") {
        item {
            SettingsCard {
                Text("How often the glasses take a photo", style = MaterialTheme.typography.titleMedium)
                ChoiceRow("Every", options.map(CaptureIntervals::label), options.indexOf(state.intervalSec), { onInterval(options[it]) }, tag = "interval")
                NoticeText("Doubled automatically when the glasses battery is low.")
                SwitchRow("Burst on change", "Take an extra photo when the scene changes", state.burstOnChange, onBurst, tag = "burst")
            }
        }
    }
}
