package com.rokid.context.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.GlassesIcon
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

object SettingsModel {
    const val COMING_SOON = "Coming soon"

    fun glassesSummary(s: GlassesStatus): String = when (s.link) {
        LinkState.CONNECTED -> (s.glassesBatteryPct ?: s.frame?.glassesBatteryPct)?.let { "Linked · $it%" } ?: "Linked"
        LinkState.CONNECTING -> "Connecting…"
        LinkState.VERSION_MISMATCH -> "Update the glasses app"
        LinkState.NOT_AUTHORIZED -> "Not authorized"
        else -> "Not connected"
    }
}

data class SettingsRootUiState(val glassesSummary: String = "Not connected")

class SettingsRootViewModel(glasses: GlassesStatusSource) : ViewModel() {
    val state: StateFlow<SettingsRootUiState> = glasses.status.map { SettingsRootUiState(SettingsModel.glassesSummary(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRootUiState())
}

private data class SettingRow(val icon: ImageVector, val colors: Pair<Color, Color>, val title: String, val subtitle: String, val onClick: (() -> Unit)?)

/** Settings root (spec §2.4, mock settings.html). Phase 4: only Model endpoints + Diagnostics open; the rest is Phase 5. */
@Composable
fun SettingsRootScreen(state: SettingsRootUiState, onOpenEndpoints: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val soon = SettingsModel.COMING_SOON
    val groups = listOf(
        "Devices" to listOf(
            SettingRow(GlassesIcon, PcColors.ChipMint, "Rokid Glasses", state.glassesSummary, null),
            SettingRow(Icons.Outlined.PhoneAndroid, PcColors.ChipSky, "Phone camera & mic", soon, null),
        ),
        "Capture & privacy" to listOf(
            SettingRow(Icons.Outlined.Schedule, PcColors.ChipAmber, "Capture schedule", soon, null),
            SettingRow(Icons.Outlined.Shield, PcColors.ChipSlate, "Privacy", soon, null),
        ),
        "AI" to listOf(
            SettingRow(Icons.Outlined.Cloud, PcColors.ChipSky, "Model endpoints", "Provider, address, key and Test", onOpenEndpoints),
            SettingRow(Icons.Outlined.AutoAwesome, PcColors.ChipViolet, "Models by role", soon, null),
            SettingRow(Icons.Outlined.Edit, PcColors.ChipRose, "Prompts", soon, null),
            SettingRow(Icons.Outlined.Search, PcColors.ChipMint, "Retrieval", soon, null),
            SettingRow(Icons.Outlined.Face, PcColors.ChipAmber, "Persona & language", soon, null),
        ),
        "Connectors" to listOf(
            SettingRow(Icons.Outlined.Event, PcColors.ChipSky, "Data sources", soon, null),
            SettingRow(Icons.Outlined.Bolt, PcColors.ChipAmber, "Actions & tools", soon, null),
        ),
        "Memory & system" to listOf(
            SettingRow(Icons.Outlined.Memory, PcColors.ChipViolet, "Memory", soon, null),
            SettingRow(Icons.Outlined.MonitorHeart, PcColors.ChipCoral, "Diagnostics", "Queues, summarizer tracker, manual runs", onOpenDiagnostics),
        ),
    )
    LazyColumn(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).testTag("settings_list")) {
        item { ScreenHeader("Settings", onBack = null) }
        groups.forEach { (label, rows) ->
            item(key = label) {
                SectionLabel(label)
                SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column {
                        rows.forEach { r -> ChipRow(r.icon, r.colors, r.title, r.subtitle, onClick = r.onClick ?: {}, enabled = r.onClick != null) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
