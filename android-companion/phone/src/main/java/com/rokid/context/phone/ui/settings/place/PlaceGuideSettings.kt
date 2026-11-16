package com.rokid.context.phone.ui.settings.place

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaceGuideUiState(val enabled: Boolean = false, val seeding: Boolean = false, val message: String? = null, val messageIsError: Boolean = false, val packSummary: String = "")

/** What the screen needs from the pack and the switch (production: [PlaceGraph], [PlaceGuideSettings]; tests: fakes). */
interface PlaceGuideControl {
    var enabled: Boolean
    suspend fun seed(box: GeoBox): Result<String>
    suspend fun summary(): String
}

object PlaceGuideModel {
    /** Charminar and Golconda (Hyderabad): about 11 km by 15 km, about a dozen 5 km cells. */
    val HYDERABAD = GeoBox(17.34, 78.38, 17.41, 78.52)
    const val SEED_NOTE = "Fetches place text (Wikipedia, Wikidata, OpenStreetMap) for the area while you are online. Only 5 km cell keys leave the phone, never your position."
    const val ZONE_NOTE = "Place cards stay off whenever a pause zone is active or you are near one."

    /** "south, west, north, east" in degrees; null when not four numbers or not a valid box. */
    fun parseBox(south: String, west: String, north: String, east: String): GeoBox? {
        fun d(s: String) = s.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        val b = GeoBox(d(south) ?: return null, d(west) ?: return null, d(north) ?: return null, d(east) ?: return null)
        return b.takeIf { it.valid && Geohash5.cellsCovering(it).size in 1..PlacePack.MAX_SEED_CELLS }
    }
}

class PlaceGuideViewModel(private val control: PlaceGuideControl, private val work: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val _state = MutableStateFlow(PlaceGuideUiState(enabled = control.enabled))
    val state: StateFlow<PlaceGuideUiState> = _state

    init { refreshSummary() }

    fun setEnabled(on: Boolean) { control.enabled = on; _state.update { it.copy(enabled = on) } }

    fun seed(box: GeoBox?) {
        if (box == null) { _state.update { it.copy(message = "Type four numbers: south, west, north, east (degrees).", messageIsError = true) }; return }
        if (_state.value.seeding) return
        _state.update { it.copy(seeding = true, message = "Fetching places…", messageIsError = false) }
        viewModelScope.launch(work) {
            val r = control.seed(box)
            _state.update { it.copy(seeding = false, message = r.getOrElse { "Could not fetch places. Check the connection and try again." }, messageIsError = r.isFailure) }
            refreshSummary()
        }
    }

    private fun refreshSummary() { viewModelScope.launch(work) { val s = runCatching { control.summary() }.getOrDefault(""); _state.update { it.copy(packSummary = s) } } }
}

@Composable
fun PlaceGuideRoute(control: PlaceGuideControl, back: () -> Unit) {
    val vm = viewModel { PlaceGuideViewModel(control) }
    val state by vm.state.collectAsStateWithLifecycle()
    PlaceGuideScreen(state, back, vm::setEnabled, vm::seed)
}

@Composable
fun PlaceGuideScreen(state: PlaceGuideUiState, back: () -> Unit, onEnabled: (Boolean) -> Unit, onSeed: (GeoBox?) -> Unit) {
    var s by remember { mutableStateOf("") }; var w by remember { mutableStateOf("") }; var n by remember { mutableStateOf("") }; var e by remember { mutableStateOf("") }
    SettingsPage("Place Guide", back, tag = "place_guide_settings") {
        item {
            SettingsCard {
                SwitchRow("Place Guide", "Names the monument you are looking at after you pause on it", state.enabled, onEnabled, tag = "place_guide_switch")
                NoticeText(PlaceGuideModel.ZONE_NOTE, tag = "place_guide_zone_note")
                if (state.packSummary.isNotEmpty()) NoticeText(state.packSummary, tag = "place_guide_pack")
            }
        }
        item {
            SettingsCard {
                Text("Seed this area", style = MaterialTheme.typography.titleMedium)
                NoticeText(PlaceGuideModel.SEED_NOTE, tag = "place_guide_seed_note")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s, { s = it }, label = { Text("South") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_south"))
                    OutlinedTextField(w, { w = it }, label = { Text("West") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_west"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(n, { n = it }, label = { Text("North") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_north"))
                    OutlinedTextField(e, { e = it }, label = { Text("East") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_east"))
                }
                PrimaryAction("Seed this area", { onSeed(PlaceGuideModel.parseBox(s, w, n, e)) }, tag = "seed_area", enabled = !state.seeding)
                SecondaryAction("Seed Hyderabad (Charminar, Golconda)", { onSeed(PlaceGuideModel.HYDERABAD) }, tag = "seed_hyderabad", enabled = !state.seeding)
                state.message?.let { NoticeText(it, error = state.messageIsError, tag = "place_guide_message") }
            }
        }
    }
}
