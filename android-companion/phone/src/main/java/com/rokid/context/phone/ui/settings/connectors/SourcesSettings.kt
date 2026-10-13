package com.rokid.context.phone.ui.settings.connectors

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.context.connectors.sources.SourceKind
import com.rokid.context.connectors.sources.SourcePermissions
import com.rokid.context.connectors.sources.SourceSettingsStore
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsDeps
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › Data sources as the screen sees it (R-64). The first read of [enabled] reads prefs: do it off Main. */
interface SourcesControl {
    val enabled: StateFlow<Set<SourceKind>>
    val nudgesPerDay: StateFlow<Int>
    fun set(k: SourceKind, on: Boolean)
    fun setNudgesPerDay(n: Int)
    /** Every permission [k] needs is granted right now. */
    fun granted(k: SourceKind): Boolean

    companion object {
        val None: SourcesControl = object : SourcesControl {
            override val enabled: StateFlow<Set<SourceKind>> = MutableStateFlow(emptySet())
            override val nudgesPerDay: StateFlow<Int> = MutableStateFlow(SourceSettingsStore.DEFAULT_CAP)
            override fun set(k: SourceKind, on: Boolean) = Unit
            override fun setNudgesPerDay(n: Int) = Unit
            override fun granted(k: SourceKind) = false
        }
    }
}

data class SourcesUiState(
    val loading: Boolean = true,
    val enabled: Set<SourceKind> = emptySet(),
    /** On but revoked, or denied with "don't ask again": the row offers Open settings. */
    val needsPermission: Set<SourceKind> = emptySet(),
    val nudgesPerDay: Int = SourceSettingsStore.DEFAULT_CAP,
)

/**
 * Turning a source on asks for its permissions **then** (on demand); it is switched on only once they are granted.
 * Denied: it stays off. Denied with "don't ask again": "Permission needed · Open settings".
 */
class SourcesViewModel(private val control: SourcesControl, private val work: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private data class Local(val blocked: Set<SourceKind> = emptySet(), val revoked: Set<SourceKind> = emptySet(), val pending: SourceKind? = null)
    private val local = MutableStateFlow(Local())
    private val ui = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = ui.asStateFlow()

    init {
        viewModelScope.launch {
            val (enabled, cap) = withContext(work) { control.enabled to control.nudgesPerDay } // first touch reads prefs
            combine(enabled, cap, local) { e, n, l -> SourcesUiState(false, e, (l.blocked - e) + (l.revoked intersect e), n) }
                .collect { ui.value = it }
        }
        onResume()
    }

    fun onToggle(k: SourceKind, on: Boolean, request: (List<String>) -> Unit) {
        if (!on) { control.set(k, false); local.update { it.copy(revoked = it.revoked - k) }; return }
        if (control.granted(k)) { control.set(k, true); local.update { it.copy(blocked = it.blocked - k, revoked = it.revoked - k) }; return }
        local.update { it.copy(pending = k) }
        request(SourcePermissions.of(k))
    }

    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        val k = local.value.pending ?: return
        val ok = granted && control.granted(k)
        if (ok) control.set(k, true)
        local.update { l ->
            val blocked = if (ok || canAskAgain) l.blocked - k else l.blocked + k
            l.copy(pending = null, blocked = blocked, revoked = l.revoked - k)
        }
    }

    /** Back from system settings: a grant clears the flag; a revoke of an enabled source sets it. */
    fun onResume() {
        viewModelScope.launch {
            val revoked = withContext(work) { control.enabled.value.filterNot(control::granted).toSet() }
            val nowGranted = SourceKind.entries.filter(control::granted).toSet()
            local.update { it.copy(revoked = revoked, blocked = it.blocked - nowGranted) }
        }
    }

    fun setNudgesPerDay(n: Int) = control.setNudgesPerDay(n)
}

private data class SourceRow(val kind: SourceKind, val title: String, val subtitle: String)

private val ROWS = listOf(
    SourceRow(SourceKind.CALENDAR, "Calendar", "Next 24 hours of events"),
    SourceRow(SourceKind.CONTACTS, "Contacts", "Match names you mention"),
    SourceRow(SourceKind.NOTES, "Notes", "Your shopping list and notes"),
    SourceRow(SourceKind.LOCATION, "Location", "Label photos with a rough place"),
)

@Composable
fun SourcesScreen(
    state: SourcesUiState,
    onBack: () -> Unit,
    onToggle: (SourceKind, Boolean) -> Unit,
    onNudgesPerDay: (Int) -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    SettingsPage("Data sources", onBack, tag = "sources") {
        item {
            SettingsCard {
                ROWS.forEach { r ->
                    val tag = "source_" + r.kind.name.lowercase()
                    SwitchRow(r.title, r.subtitle, r.kind in state.enabled && r.kind !in state.needsPermission, { onToggle(r.kind, it) }, tag = tag, enabled = !state.loading)
                    if (r.kind in state.needsPermission) SecondaryAction(PERMISSION_NEEDED, onOpenAppSettings, tag = "${tag}_permission")
                }
                NoticeText(FOOTER, tag = "sources_footer")
                NoticeText(LOCATION_NOTE, tag = "sources_location_note")
            }
        }
        item {
            SettingsCard {
                Text("Nudges", style = MaterialTheme.typography.titleMedium)
                val caps = SourceSettingsStore.CAPS
                ChoiceRow(
                    "Nudges per day", caps.map { if (it == 0) "Off" else it.toString() }, caps.indexOf(state.nudgesPerDay).coerceAtLeast(0),
                    { onNudgesPerDay(caps[it]) }, tag = "nudges_per_day", enabled = !state.loading,
                )
            }
        }
    }
}

const val PERMISSION_NEEDED = "Permission needed \u00b7 Open settings"
const val LOCATION_NOTE = "Location names come from the phone's built-in geocoder, which may look the place up online."
const val FOOTER = "Used only on this phone to answer and nudge. Never sent to your glasses."

/**
 * Settings › Data sources (R-64, R-17). [canAskAgain]: after a denial, whether the system will still show the dialog
 * (the destination answers it with the activity's rationale check; true when unknown).
 */
@Composable
fun SourcesRoute(deps: SettingsDeps, back: () -> Unit, canAskAgain: (String) -> Boolean = { true }) {
    val vm = viewModel { SourcesViewModel(deps.sources) }
    val state by vm.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.isNotEmpty() && result.values.all { it }
        vm.onPermissionResult(granted, canAskAgain = granted || result.keys.all(canAskAgain))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    SourcesScreen(
        state, onBack = back,
        onToggle = { k, on -> vm.onToggle(k, on) { perms -> launcher.launch(perms.toTypedArray()) } },
        onNudgesPerDay = vm::setNudgesPerDay, onOpenAppSettings = deps.openAppSettings,
    )
}
