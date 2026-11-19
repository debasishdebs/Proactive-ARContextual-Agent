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
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
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
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.GlassesIcon
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.capture.CaptureIntervals
import com.rokid.context.phone.ui.shell.Routes
import com.rokid.context.voice.VoiceCapabilities
import com.rokid.context.voice.VoiceStatusText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

object SettingsModel {
    const val COMING_SOON = "Coming soon"
    const val MINIMISED_CAPTURING = "Minimised on glasses \u00b7 capturing"

    fun glassesSummary(s: GlassesStatus): String = when (s.link) {
        LinkState.CONNECTED -> (s.glassesBatteryPct ?: s.frame?.glassesBatteryPct)?.let { "Linked · $it%" } ?: "Linked"
        LinkState.MINIMISED -> MINIMISED_CAPTURING
        LinkState.CONNECTING -> "Connecting…"
        LinkState.VERSION_MISMATCH -> "Update the glasses app"
        LinkState.NOT_AUTHORIZED -> "Not authorized"
        else -> "Not connected"
    }

    fun captureSummary(s: HudSettings): String =
        if (s.captureMode == com.rokid.context.core.model.CaptureMode.SMART) "Smart"
        else "Every ${CaptureIntervals.label(CaptureIntervals.clamp(s.captureIntervalSec))}"

    fun voiceSummary(c: VoiceCapabilities): String = "Voice · ${VoiceStatusText.glasses(c.glasses)}"
}

data class SettingsRootUiState(val glassesSummary: String = "Not connected", val captureSummary: String = "", val voiceSummary: String = "Voice · Checking…",
    /** The Data saver row's subtitle ([com.rokid.context.phone.ui.settings.datasaver.DataSaverModel.rowSubtitle]); the default until the route says otherwise. */
    val dataSaver: String = "Photos on Wi-Fi and home mobile data",
    /** The Model privacy row's subtitle ([com.rokid.context.phone.ui.settings.modelprivacy.ModelPrivacyModel.rowSubtitle]). */
    val modelPrivacy: String = "Automatic")

class SettingsRootViewModel(
    glasses: GlassesStatusSource,
    hud: HudSettingsControl,
    voice: Flow<VoiceCapabilities> = flowOf(VoiceCapabilities.UNKNOWN),
) : ViewModel() {
    val state: StateFlow<SettingsRootUiState> = combine(glasses.status, hud.settings, voice) { g, h, v ->
        SettingsRootUiState(SettingsModel.glassesSummary(g), SettingsModel.captureSummary(h), SettingsModel.voiceSummary(v))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRootUiState())
}


private data class SettingRow(val icon: ImageVector, val colors: Pair<Color, Color>, val title: String, val subtitle: String, val route: String?)

/** Settings root (spec §2.4, mock settings.html): five groups; rows open only when [live] contains their route. */
@Composable
fun SettingsRootScreen(state: SettingsRootUiState, live: Set<String>, onOpen: (String) -> Unit, developerMode: Boolean = false) {
    val soon = SettingsModel.COMING_SOON
    val groups = listOf(
        "Devices" to listOf(
            SettingRow(GlassesIcon, PcColors.ChipMint, "Rokid Glasses", state.glassesSummary, Routes.SETTINGS_GLASSES),
            SettingRow(Icons.Outlined.PhoneAndroid, PcColors.ChipSky, "Phone camera & mic", state.voiceSummary, Routes.SETTINGS_PHONE_INPUTS),
            SettingRow(Icons.Outlined.Tune, PcColors.ChipAmber, "Mic and display", "Where flows listen and show", Routes.SETTINGS_DEVICE_CAPABILITIES),
        ) + if (developerMode) listOf(
            SettingRow(GlassesIcon, PcColors.ChipSlate, "Rokid Nexus", "Optional second HUD host (off)", Routes.SETTINGS_NEXUS),
        ) else emptyList(),
        "Capture & privacy" to listOf(
            SettingRow(Icons.Outlined.Schedule, PcColors.ChipAmber, "Capture schedule", state.captureSummary, Routes.SETTINGS_CAPTURE),
            SettingRow(Icons.Outlined.Shield, PcColors.ChipSlate, "Privacy", "Pause, quiet hours", Routes.SETTINGS_PRIVACY),
            SettingRow(Icons.Outlined.Cloud, PcColors.ChipSky, "Data saver", state.dataSaver, Routes.SETTINGS_DATA_SAVER),
            SettingRow(Icons.Outlined.Shield, PcColors.ChipSlate, "Model privacy", state.modelPrivacy, Routes.SETTINGS_MODEL_PRIVACY),
        ) + if (developerMode) listOf(
            SettingRow(Icons.Outlined.Search, PcColors.ChipMint, "Place Guide", "Monument cards, seed an area", Routes.SETTINGS_PLACE_GUIDE),
            SettingRow(Icons.Outlined.AutoAwesome, PcColors.ChipViolet, "Suggestions (shadow)", "Off. Only logs what it would suggest", Routes.SETTINGS_SUGGESTIONS),
        ) else emptyList(),
        "AI" to listOf(
            SettingRow(Icons.Outlined.AccountTree, PcColors.ChipViolet, "Agent flows", "See and tune each step of every agent", Routes.SETTINGS_FLOWS),
            SettingRow(Icons.Outlined.DownloadForOffline, PcColors.ChipMint, "On-device models", "Run search models on your phone", Routes.SETTINGS_LOCAL_MODELS),
            SettingRow(Icons.Outlined.Cloud, PcColors.ChipSky, "Model endpoints", "Provider, address, key and Test", Routes.SETTINGS_ENDPOINTS),
            SettingRow(Icons.Outlined.AutoAwesome, PcColors.ChipViolet, "Models by role", "Which model does what", Routes.SETTINGS_ROLES),
            SettingRow(Icons.Outlined.Edit, PcColors.ChipRose, "Prompts", "Templates, schemas, versions and test bed", Routes.SETTINGS_PROMPTS),
            SettingRow(Icons.Outlined.Search, PcColors.ChipMint, "Retrieval", "Lexical, semantic or hybrid search", Routes.SETTINGS_RETRIEVAL),
            SettingRow(Icons.Outlined.Face, PcColors.ChipAmber, "Persona & language", "How answers sound", Routes.SETTINGS_PERSONA),
        ) + if (developerMode) listOf(
            SettingRow(Icons.Outlined.Science, PcColors.ChipSlate, "Experiment canvas", "Build, run and compare pipelines on your photos", Routes.SETTINGS_CANVAS),
        ) else emptyList(),
        "Connectors" to listOf(
            SettingRow(Icons.Outlined.Event, PcColors.ChipSky, "Data sources", "Calendar, contacts, notes, location", Routes.SETTINGS_SOURCES),
            SettingRow(Icons.Outlined.Bolt, PcColors.ChipAmber, "Actions & tools", "Reminders, shopping list, calendar", Routes.SETTINGS_ACTIONS),
        ),
        "Memory & system" to listOf(
            SettingRow(Icons.Outlined.Memory, PcColors.ChipViolet, "Memory", "Retention, storage, encryption, export", Routes.SETTINGS_MEMORY),
            SettingRow(Icons.Outlined.MonitorHeart, PcColors.ChipCoral, "Diagnostics", "Queues, summarizer tracker, manual runs", Routes.SETTINGS_DIAGNOSTICS),
        ),
    )
    LazyColumn(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).testTag("settings_list")) {
        item { ScreenHeader("Settings", onBack = null) }
        groups.forEach { (label, rows) ->
            item(key = label) {
                SectionLabel(label)
                SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column {
                        rows.forEach { r ->
                            val open = r.route?.takeIf { it in live }
                            ChipRow(r.icon, r.colors, r.title, if (open == null && r.route != null) soon else r.subtitle, onClick = { open?.let(onOpen) }, enabled = open != null)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
