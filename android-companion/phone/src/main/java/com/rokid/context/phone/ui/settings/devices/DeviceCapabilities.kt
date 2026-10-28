package com.rokid.context.phone.ui.settings.devices

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.DeviceCapability
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import com.rokid.context.phone.ui.settings.capture.DeviceCapabilityStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Settings > Devices > Mic and display (v0.9.8 slice D): the four switches of [DeviceCapabilityStore]. */
class DeviceCapabilitiesViewModel(private val store: DeviceCapabilityStore) : ViewModel() {
    val state: StateFlow<List<DeviceCapability>> = store.capabilities.map { it.list() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.capabilities.value.list())

    fun set(device: String, capability: String, on: Boolean) { store.set(device, capability, on) }
}

@Composable
fun DeviceCapabilitiesScreen(state: List<DeviceCapability>, onBack: () -> Unit, onChange: (String, String, Boolean) -> Unit = { _, _, _ -> }) {
    SettingsPage("Mic and display", onBack, tag = "device_capabilities") {
        for (device in listOf("phone", "glasses")) item {
            SettingsCard {
                Text(if (device == "phone") "Phone" else "Glasses", style = MaterialTheme.typography.titleMedium)
                for (c in state.filter { it.device == device }) {
                    val mic = c.capability == "mic"
                    SwitchRow(
                        if (mic) "Microphone" else if (c.constraint == "hud") "Display (the HUD)" else "Display",
                        if (mic) "Questions asked by voice on the $device" else "Answers and nudges that show on the $device",
                        c.enabled, { onChange(c.device, c.capability, it) }, tag = "cap_${c.device}_${c.capability}",
                    )
                }
            }
        }
        item {
            SettingsCard {
                NoticeText(
                    "A switched-off display is skipped by delivery: the answer falls back to another surface and the reason is recorded. A switched-off mic ignores that surface's voice and says so; typing still works. All on by default.",
                    tag = "cap_note",
                )
            }
        }
    }
}
