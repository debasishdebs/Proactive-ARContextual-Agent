package com.rokid.context.phone.ui.settings.capture

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.context.ContextDecision
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.phone.context.ContextHubStats
import com.rokid.context.phone.context.ContextSourceSettings
import com.rokid.context.phone.context.SourceStatus
import com.rokid.context.phone.context.WatchLinkSnapshot
import com.rokid.context.phone.context.WatchLinkState
import com.rokid.context.phone.context.WatchLinkStep
import com.rokid.context.phone.context.WatchMotionIntake
import com.rokid.context.phone.context.WatchNodeProbe
import com.rokid.context.phone.context.WatchNodes
import com.rokid.context.phone.context.WatchStatusKind
import com.rokid.context.phone.context.WatchStatusModel
import com.rokid.context.phone.context.WatchStatusRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import android.Manifest
import java.security.MessageDigest

/** The LiveFit signing certificate as lowercase hex SHA-256, or null when LiveFit is not installed (or has no readable certificate). */
fun interface LiveFitCertLookup { fun sha256(): String?; fun cannotPin(): Boolean = false }

/** The real lookup: the current signing certificate of `com.livear.fit` (spec §8). Never logged. */
class PackageLiveFitCertLookup(private val pm: PackageManager) : LiveFitCertLookup {
    override fun sha256(): String? = try {
        val info = pm.getPackageInfo(WatchMotionIntake.LIVEFIT_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val si = info.signingInfo
        val sig = when {
            si == null -> null
            si.hasMultipleSigners() -> null
            else -> si.signingCertificateHistory?.lastOrNull() ?: si.apkContentsSigners.firstOrNull()
        }
        sig?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
    } catch (e: Exception) { null }

    /** A multi-signer package can never match a single-certificate pin, so it cannot be pinned. */
    override fun cannotPin(): Boolean = try {
        pm.getPackageInfo(WatchMotionIntake.LIVEFIT_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.hasMultipleSigners() == true
    } catch (e: Exception) { false }
}

/** One phone or intent switch: [on] is the stored opt-in, [status] what the hub made of it, [denied] a permission answer of "no". */
data class SourceRow(val on: Boolean = false, val status: SourceStatus = SourceStatus.OFF, val denied: Boolean = false, val permissionMissing: Boolean = false)

/** A bonded device in the My car picker. The address is the stored key and is never shown or logged ([toString] hides it and the name). */
data class CarRow(val address: String, val name: String, val checked: Boolean) {
    override fun toString() = "CarRow(checked=$checked)"
}

data class ContextSourcesUiState(
    val applyToCapture: Boolean = true,
    val watchOn: Boolean = false,
    val watchMissing: Boolean = false,
    val cannotPin: Boolean = false,
    val reToggleHint: Boolean = false,
    val watchStatus: WatchStatusRow = WatchStatusRow(WatchStatusKind.OFF, ""),
    val watchLinkConfirmed: Boolean = false,
    val stateLine: String = "",
    val rows: Map<String, SourceRow> = emptyMap(),
    val speedApproximate: Boolean = false,
    val cars: List<CarRow> = emptyList(),
    val myCarListed: Boolean = false,
    val bluetoothOff: Boolean = false,
) {
    fun row(id: String) = rows[id] ?: SourceRow()

    companion object {
        fun word(s: ContextState): String = when (s) {
            ContextState.UNKNOWN -> "Unknown"
            ContextState.REST -> "Resting"
            ContextState.WALKING -> "Walking"
            ContextState.VEHICLE_PASSENGER -> "Passenger"
            ContextState.VEHICLE_DRIVING -> "Driving"
            ContextState.FLIGHT -> "Flying"
        }
    }
}

/**
 * Writes the opt-in settings only. The process-wide hub observes them through the settings listener and does the start, the stop
 * and the revocation of a published state; nothing here calls the hub. A switch turns on only after its permission is granted
 * (the screen launches the system dialog through [setSource]'s `ask`), so a source never starts before the user said yes.
 */
class ContextSourcesViewModel(
    private val settings: ContextSourceSettings,
    private val cert: LiveFitCertLookup,
    decision: StateFlow<ContextDecision>,
    status: StateFlow<Map<String, SourceStatus>>,
    private val stats: ContextHubStats? = null,
    /** Is this runtime permission granted now. Default: nothing is, so a missing wiring asks rather than assumes. */
    private val granted: (String) -> Boolean = { false },
    /** (address, display name) of the bonded devices; empty without the permission. */
    private val bonded: () -> List<Pair<String, String>> = { emptyList() },
    /** Re-reads the counters while the screen is shown (they are not part of any flow). */
    tick: Flow<Unit> = emptyFlow(),
    /** Bluetooth adapter on. Default true (unknown). */
    private val bluetoothOn: () -> Boolean = { true },
    /** The watch link ledger (process-wide by default). */
    private val link: StateFlow<WatchLinkSnapshot> = WatchLinkState.shared.snapshot,
    /** Data Layer nodes with the watch app; asked only while the screen is shown and the watch source is on. */
    private val probe: WatchNodeProbe = WatchNodeProbe { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val timeText: (Long) -> String = { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) },
) : ViewModel() {
    private data class Local(val missing: Boolean = false, val multiSigner: Boolean = false, val denied: Set<String> = emptySet(), val bonded: List<Pair<String, String>> = emptyList(), val nodes: WatchNodes? = null)
    private val local = MutableStateFlow(Local())
    private var pending: String? = null

    private fun of(d: ContextDecision, st: Map<String, SourceStatus>, l: Local): ContextSourcesUiState {
        val snap = stats?.snapshot().orEmpty()
        val line = "Now: ${ContextSourcesUiState.word(d.state)} · watch ${st[WATCH] ?: SourceStatus.OFF} · watch msgs ${snap["watch"] ?: 0} · rejected ${snap["watchRej"] ?: 0}"
        val on = settings.activeWatchSource == ContextSourceSettings.WATCH_APP
        val lk = link.value
        val rows = PHONE_IDS.associateWith { id -> SourceRow(settings.enabled(id), st[id] ?: SourceStatus.OFF, id in l.denied, settings.enabled(id) && !PermissionPlan.accepted(id, granted = granted)) }
        val approximate = settings.enabled(SPEED) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val mine = settings.myCars
        val cars = l.bonded.map { (addr, name) -> CarRow(addr, if (name == addr || name.isBlank()) "Unnamed device" else name, mine.any { it.equals(addr, ignoreCase = true) }) }
        val btOff = settings.enabled(CAR) && PermissionPlan.accepted(CAR, granted = granted) && !bluetoothOn()
        return ContextSourcesUiState(
            settings.applyToCapture, on, l.missing, l.multiSigner, false, WatchStatusModel.of(on, l.nodes, lk, now(), timeText), WatchLinkStep.confirmed(lk),
            line, rows, approximate, cars, mine.isNotEmpty(), btOff,
        )
    }

    val state: StateFlow<ContextSourcesUiState> = combine(decision, status, local, combine(settings.changes, link) { c, _ -> c }, tick.onStart { emit(Unit) }) { d, s, l, _, _ -> of(d, s, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), of(decision.value, status.value, local.value))

    init {
        if (settings.enabled(CAR)) refreshCars()
        // Poll the Data Layer only while the screen is shown (tick) and the watch source is on: source off means no GMS call at all.
        viewModelScope.launch { tick.collect { if (settings.activeWatchSource == ContextSourceSettings.WATCH_APP) { val n = probe.nodes(); local.update { it.copy(nodes = n) } } else local.update { it.copy(nodes = null) } } }
    }

    fun setApply(on: Boolean) { settings.applyToCapture = on }

    /** Re-reads the bonded devices (the picker shows names only). */
    fun refreshCars() { val list = if (settings.enabled(CAR)) bonded() else emptyList(); local.update { it.copy(bonded = list) } }

    /** A listed device is the wearer's car (ctx_hub_state only; Delete all clears it). */
    fun setMyCar(address: String, on: Boolean) { if (on) settings.addMyCar(address) else settings.removeMyCar(address) }

    /**
     * On: the permission flow first. Already granted (or, for speed, approximate granted) enables at once; otherwise `ask` launches the
     * system dialog and [onPermissionResult] decides. Off: disable. The watch pins its certificate instead of asking for a permission.
     */
    fun setSource(id: String, on: Boolean, ask: (Array<String>) -> Unit) {
        if (id == WATCH) { setWatch(on); return }
        local.update { it.copy(denied = it.denied - id) }
        if (!on) { if (pending == id) pending = null; disable(id); return }
        if (PermissionPlan.accepted(id, granted = granted)) enable(id)
        else { pending = id; ask(PermissionPlan.forSource(id).toTypedArray()) }
    }

    /** The answer of the system dialog started by [setSource]. */
    fun onPermissionResult(grants: Map<String, Boolean>) {
        val id = pending ?: return
        pending = null
        if (PermissionPlan.accepted(id) { grants[it] == true || granted(it) }) enable(id)
        else local.update { it.copy(denied = it.denied + id) }
    }

    private fun enable(id: String) { settings.setEnabled(id, true); if (id == CAR) refreshCars() }
    private fun disable(id: String) { settings.setEnabled(id, false); if (id == CAR) refreshCars() }

    /** The active watch source: `watch_app` or `none` (default). No certificate is pinned any more; the Data Layer pairs the apps. */
    fun setWatch(on: Boolean) {
        settings.activeWatchSource = if (on) ContextSourceSettings.WATCH_APP else ContextSourceSettings.NONE
        if (!on) local.update { it.copy(nodes = null) }
    }

    private companion object {
        const val WATCH = ContextSourceSettings.WATCH_APP
        const val SPEED = "phone_speed"
        const val CAR = "phone_car"
        val PHONE_IDS = listOf("phone_activity", "phone_steps", "phone_speed", "phone_car", "image_intent")
    }
}

/** Binds the system permission dialog (the platform contract) to the view model. */
@Composable
fun ContextSourcesRoute(vm: ContextSourcesViewModel, onBack: () -> Unit, onOpenAppSettings: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionResult(it) }
    ContextSourcesScreen(
        state, onBack, onApply = vm::setApply,
        onSource = { id, on -> vm.setSource(id, on) { launcher.launch(it) } },
        onToggleCar = vm::setMyCar, onRefreshCars = vm::refreshCars, onOpenAppSettings = onOpenAppSettings,
    )
}

private class SwitchSpec(val id: String, val title: String, val subtitle: String?)

private val PHONE_SWITCHES = listOf(
    SwitchSpec("phone_activity", "Phone activity (walking, in a vehicle)", "Uses the phone's activity recognition."),
    SwitchSpec("phone_steps", "Phone step counter", "Counts steps on the phone to tell standing from walking."),
    SwitchSpec("phone_speed", "Speed, only while in a vehicle (location stays on the phone)", "Asks for location only while a vehicle is suspected, also with the phone in your pocket while the app's service runs (its notification shows). The background-location permission is never requested. Location is not stored or sent."),
    SwitchSpec("phone_car", "Car Bluetooth and Android Auto", "Notices when the phone connects to a car."),
)

private fun deniedReason(id: String) = when (id) {
    "phone_activity", "phone_steps" -> "Permission needed to read physical activity."
    "phone_speed" -> "Permission needed to read location."
    "phone_car" -> "Permission needed to see Bluetooth connections."
    else -> "Permission needed."
}

@Composable
private fun SourceNotes(id: String, row: SourceRow, onOpenAppSettings: () -> Unit = {}) {
    if (row.denied) {
        NoticeText("${deniedReason(id)} Turn it on again to ask, or allow it in the phone's app settings.", tag = "ctx_denied_$id")
        TextButton(onClick = onOpenAppSettings, modifier = Modifier.testTag("ctx_open_settings_$id")) { Text("Open app settings") }
    }
    if (row.permissionMissing && !row.denied) NoticeText(
        if (id == "phone_car") "Bluetooth permission is missing, so a car connection cannot be seen. Turn it off and on again to allow it."
        else "The permission was removed, so this cannot read anything. Turn it off and on again to allow it.",
        tag = "ctx_perm_missing_$id",
    )
    when {
        row.on && row.status == SourceStatus.UNAVAILABLE -> NoticeText("Not available on this phone", tag = "ctx_unavailable_$id")
        row.on && row.status == SourceStatus.FAILED -> NoticeText("Could not start. Check the permission, then turn it off and on again to retry.", tag = "ctx_failed_$id")
    }
}

@Composable
fun ContextSourcesScreen(
    state: ContextSourcesUiState, onBack: () -> Unit, onApply: (Boolean) -> Unit,
    onSource: (String, Boolean) -> Unit,
    onToggleCar: (String, Boolean) -> Unit = { _, _ -> },
    onRefreshCars: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    val carOn = state.row("phone_car").on
    LaunchedEffect(carOn) { if (carOn) onRefreshCars() }
    SettingsPage("Phone, car, watch and photo context", onBack, tag = "context_sources") {
        item {
            SettingsCard {
                Text("Context", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Adjust capture to context", "Off: the state is only logged, capture is not changed.", state.applyToCapture, onApply, tag = "ctx_apply")
                NoticeText(state.stateLine, tag = "ctx_state")
            }
        }
        item {
            SettingsCard {
                Text("Phone", style = MaterialTheme.typography.titleMedium)
                for (sw in PHONE_SWITCHES) {
                    val row = state.row(sw.id)
                    SwitchRow(sw.title, sw.subtitle, row.on, { onSource(sw.id, it) }, tag = "ctx_${sw.id}")
                    SourceNotes(sw.id, row, onOpenAppSettings)
                    if (sw.id == "phone_car" && state.bluetoothOff) NoticeText("Bluetooth is off, so a car connection cannot be seen until you turn it on.", tag = "ctx_bt_off_phone_car")
                    if (sw.id == "phone_speed" && state.speedApproximate) NoticeText("Approximate location: only long fast trips are detected", tag = "ctx_speed_note")
                }
            }
        }
        if (carOn) item {
            SettingsCard(Modifier.testTag("ctx_my_cars")) {
                Text("My car", style = MaterialTheme.typography.titleMedium)
                NoticeText("Pick the paired devices that are your own car. Only these start a vehicle episode for photos. Names are shown here only.")
                if (state.cars.isEmpty()) NoticeText("No paired Bluetooth devices found.", tag = "ctx_my_cars_empty")
                state.cars.forEachIndexed { i, car ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = car.checked, role = Role.Checkbox, onValueChange = { onToggleCar(car.address, it) }).testTag("ctx_car_$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = car.checked, onCheckedChange = null)
                        Text(car.name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item {
            SettingsCard {
                Text("Watch and photos", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Watch app (Galaxy Watch)", "Motion from the Proactive Context watch app. Off until you turn it on.", state.watchOn, { onSource("watch_app", it) }, tag = "ctx_watch_app")
                if (state.watchOn && state.watchStatus.text.isNotEmpty()) NoticeText(state.watchStatus.text, tag = "ctx_watch_status")
                if (state.watchOn) {
                    var checking by remember { mutableStateOf(false) }
                    TextButton(onClick = { checking = !checking }, modifier = Modifier.testTag("ctx_watch_check")) { Text("Check the watch link") }
                    if (checking) NoticeText(WatchLinkStep.line(state.watchLinkConfirmed), tag = "ctx_watch_link_line")
                }
                val img = state.row("image_intent")
                SwitchRow("Activity from photos", "Tells driving from riding along.", img.on, { onSource("image_intent", it) }, tag = "ctx_image_intent")
                NoticeText(
                    "Photos are sent to the vision model only inside a vehicle episode (a car you listed under My car is connected), at most one a minute, and only while this is on. It never changes how often photos are taken.",
                    tag = "ctx_image_intent_note",
                )
                if (img.on && (!carOn || !state.myCarListed)) NoticeText("No car listed yet: nothing is sent. Turn on Car Bluetooth and pick your car under My car.", tag = "ctx_image_intent_nocar")
                SourceNotes("image_intent", img, onOpenAppSettings)
            }
        }
    }
}
