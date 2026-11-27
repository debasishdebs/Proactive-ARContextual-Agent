package com.rokid.context.phone.ui.settings.privacy.zones

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import com.rokid.context.phone.ui.settings.mapzones.DrawnShape
import com.rokid.context.phone.ui.settings.mapzones.Hit
import com.rokid.context.phone.ui.settings.mapzones.ShapeEdit
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.CentreRequest
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapDeps
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapModel
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapUi
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapWords
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.RasterTileSource
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OfflineMapUi
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OfflineMapViewModel
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OfflineMapWords
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.MapMode
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.PmTilesReader
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.TileCache
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.TileDecode
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.TileKey
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.TileMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.CentreFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsDeps
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.TextFieldRow
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

private val ZoneRed = Color(0xFFD32F2F)
private val MapBackground = Color(0xFFF3F1EA)

/** The red no-network banner of the map screens (owner 6e). The map area itself is a plain white grid meanwhile; nothing is stored. */
@Composable
fun NoNetworkBanner(tag: String = "no_network_banner", modifier: Modifier = Modifier, text: String = ZoneWords.NO_NETWORK_BANNER) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = modifier.fillMaxWidth().background(Color(0xFFC62828)).padding(horizontal = 12.dp, vertical = 8.dp).testTag(tag))
}

class ZonesCallbacks(
    val back: () -> Unit,
    val setDrawing: (Boolean) -> Unit,
    val drawn: (LatLon, LatLon) -> Unit,
    val editDraft: ((ZoneDraft) -> ZoneDraft) -> Unit,
    val save: () -> Unit,
    val clearDraft: () -> Unit,
    val setEnabled: (String, Boolean) -> Unit,
    val askDelete: (String) -> Unit,
    val confirmDelete: () -> Unit,
    val cancelDelete: () -> Unit,
    val addPreset: () -> Unit,
    val requestLocation: () -> Unit,
    val dismissMessage: () -> Unit,
) {
    companion object {
        fun of(vm: ZonesViewModel, back: () -> Unit) = ZonesCallbacks(
            back, vm::setDrawing, vm::drawn, vm::editDraft, vm::save, vm::clearDraft, vm::setEnabled, vm::askDelete, vm::confirmDelete, vm::cancelDelete,
            vm::addPreset, vm::requestLocation, vm::dismissMessage,
        )
    }
}

/** Settings > Privacy > Pause zones: the map, the zone list and the new-zone form. The engine ([com.rokid.context.phone.zone.ZoneController]) judges and pauses; this screen only edits zones. */
@Composable
fun ZonesRoute(deps: SettingsDeps, back: () -> Unit, seed: SeedMapControl? = null, seedMode: Boolean = false) {
    val make = deps.zoneEditor
    if (make == null) { SettingsPage("Pause zones", back, tag = "zones_unavailable") { item { NoticeText("Pause zones are not available in this build.") } }; return }
    val vm = viewModel { ZonesViewModel(make()) }
    val state by vm.state.collectAsStateWithLifecycle()
    fun granted() = deps.locationGranted()
    LaunchedEffect(Unit) { vm.setLocationGranted(granted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.setLocationGranted(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.setLocationGranted(granted()) }
    LaunchedEffect(state.requestPermission) {
        if (state.requestPermission) { vm.permissionPromptShown(); launcher.launch(LOCATION_PERMISSIONS) }
    }
    val networkOnline by remember(deps) { deps.networkOnline() }.collectAsStateWithLifecycle(true)
    val offline = deps.offlineMap?.let { make -> remember { make() } }
    var keyEpoch by remember { mutableStateOf(0) } // a saved or cleared map key rebuilds the street-map wiring
    val onlineDeps = com.rokid.context.phone.ui.settings.privacy.zones.online.rememberOnlineDeps(deps.onlineMap, keyEpoch) // built on IO
    val online = onlineDeps.deps?.let { d -> rememberOnlinePanel(d, ::granted, vm::requestLocation) { keyEpoch++ } }
    val seedPanel = seed?.let { c ->
        val seedVm = viewModel(key = "seed_map") { SeedMapViewModel(c) }
        val seedState by seedVm.state.collectAsStateWithLifecycle()
        LaunchedEffect(seedVm) { seedVm.refresh() }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { seedVm.refresh() }
        SeedPanel(seedState, seedMode, seedVm::requestSeed, seedVm::confirmSeed, seedVm::cancelConfirm, seedVm::askRemove, seedVm::confirmRemove, seedVm::cancelRemove, seedVm::dismissMessage, seedVm::cancelSeed, seedVm::setRemoveWithPlaces, seedVm::rename, seedVm::retryFailed, seedVm::focusArea, seedVm::clearFocus, seedVm::setColour, seedVm::requestSeedAgain, seedVm::editArea, seedVm::endEdit)
    }
    if (offline == null) { ZonesScreen(state, ZonesCallbacks.of(vm, back), null, online, seedPanel, networkOnline); return }
    val mapVm = viewModel(key = "offline_map") { OfflineMapViewModel(offline.files, offline.store) }
    val mapUi by mapVm.ui.collectAsStateWithLifecycle()
    val reader by mapVm.reader.collectAsStateWithLifecycle()
    val link = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) mapVm.link(u.toString()) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) mapVm.importCopy(u.toString()) }
    val panel = OfflineMapPanel(mapUi, reader, { link.launch(PICK_TYPES) }, { import.launch(PICK_TYPES) }, mapVm::remove, mapVm::dismissMessage)
    ZonesScreen(state, ZonesCallbacks.of(vm, back), panel, online, seedPanel, networkOnline)
}

/**
 * Builds the street-map panel: the switch state, the tile source (started only while this screen is resumed, stopped and its requests cancelled on pause, stop and leaving) and the
 * one-shot "Centre on me". Location is read ONLY here, on the tap, with the existing permission; the result is held in screen state and never stored or logged.
 */
@Composable
internal fun rememberOnlinePanel(d: OnlineMapDeps, granted: () -> Boolean, askPermission: () -> Unit, onKeyChanged: () -> Unit = {}): OnlineMapPanel {
    val ui by d.model.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var foreground by remember { mutableStateOf(false) }
    val grantedNow by rememberUpdatedState(granted); val askNow by rememberUpdatedState(askPermission)
    val flow = remember(d.fix) { CentreFlow(scope, d.fix, { grantedNow() }, { askNow() }) }
    val cu by flow.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(d.source) { d.source?.newVisit(); onDispose {} } // entering the screen: the per-visit request cap starts again (a resume does not)
    DisposableEffect(owner, ui.enabled) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { foreground = true; if (ui.enabled) d.source?.start() }
                Lifecycle.Event.ON_PAUSE -> { foreground = false; d.source?.stop() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); d.source?.stop() }
    }
    return OnlineMapPanel(ui, d.source.takeIf { ui.enabled && ui.available }, foreground, cu.request, cu.finding, cu.note, d.model::requestEnabled, d.model::confirmFirst, d.model::cancelFirst, flow::tap, flow::dismissNote, d.fix != null, d.keys, onKeyChanged, flow::cancel)
}

/** A PMTiles file has no registered MIME type, so the picker is not narrowed. */
internal val PICK_TYPES = arrayOf("*/*")

/** What the zones screen needs to offer the offline map (null where the build has none). [reader] is the open archive, null for the plain grid. */
class OfflineMapPanel(
    val ui: OfflineMapUi,
    val reader: PmTilesReader?,
    val chooseLink: () -> Unit,
    val chooseImport: () -> Unit,
    val remove: () -> Unit,
    val dismiss: () -> Unit,
)

/** What the zones screen needs to offer the opt-in street map and "Centre on me". [source] is non-null only while the switch is ON; [foreground] is whether this screen is resumed. */
class OnlineMapPanel(
    val ui: OnlineMapUi,
    val source: RasterTileSource?,
    val foreground: Boolean,
    val centre: CentreRequest?,
    val centreBusy: Boolean,
    val note: String?,
    val setEnabled: (Boolean) -> Unit,
    val confirmFirst: () -> Unit,
    val cancelFirst: () -> Unit,
    val centreOnMe: () -> Unit,
    val dismissNote: () -> Unit,
    val canCentre: Boolean = true,
    val keys: com.rokid.context.phone.ui.settings.privacy.zones.online.MapKeyControl? = null,
    val keyChanged: () -> Unit = {},
    /** Cancel on the "Finding you..." line (the fix request is cancelled). */
    val centreCancel: () -> Unit = {},
)

/** What the zones screen needs to show seeded areas and pins and to seed the drawn box (null where the build has no Place Guide). [seedMode] = opened from Place Guide "Pick on the map". */
class SeedPanel(
    val ui: SeedMapUiState,
    val seedMode: Boolean,
    val seedBox: (com.rokid.context.memory.place.GeoBox?) -> Unit,
    val confirmSeed: () -> Unit,
    val cancelConfirm: () -> Unit,
    val askRemove: (String) -> Unit,
    val confirmRemove: () -> Unit,
    val cancelRemove: () -> Unit,
    val dismiss: () -> Unit,
    val cancelSeed: (String) -> Unit = {},
    val setRemoveWithPlaces: (Boolean) -> Unit = {},
    /** S4: rename an area (label only). */
    val rename: (String, String) -> Unit = { _, _ -> },
    /** "Retry failed cells": queues only the cells of the area that did not seed. */
    val retryFailed: (String) -> Unit = {},
    /** A tap on a row: selects the area (highlight, name on the map) and asks the screen to scroll to the map and fit it. */
    val focus: (String) -> Unit = {},
    /** A tap on the map background: the highlight goes away. */
    val clearFocus: () -> Unit = {},
    /** The owner's colour choice for an area (a [SeedPalette] index). */
    val setColour: (String, Int) -> Unit = { _, _ -> },
    /** "Seed again" on a row: the area's own box (never an edit). */
    val seedAgain: (com.rokid.context.memory.place.GeoBox?) -> Unit = seedBox,
    /** "Edit box": loads the area's rectangle into the draft box. */
    val editArea: (String) -> Unit = {},
    /** Leaves "Edit box" without seeding. */
    val endEdit: () -> Unit = {},
)

/** The remove-area dialog (both map screens): "the area and its places" is preselected; the other choice keeps the places as ordinary fetched places. */
@Composable
internal fun SeedRemoveDialog(seed: SeedPanel) {
    val both = seed.ui.removeWithPlaces
    AlertDialog(
        onDismissRequest = seed.cancelRemove, modifier = Modifier.testTag("seed_remove_dialog"),
        title = { Text("Remove seeded area?") },
        text = {
            androidx.compose.foundation.layout.Column {
                Row(Modifier.fillMaxWidth().clickable { seed.setRemoveWithPlaces(true) }.testTag("seed_remove_with_places"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(both, { seed.setRemoveWithPlaces(true) }); Text("The area and its places (and their reference captions). Pinned places stay.")
                }
                Row(Modifier.fillMaxWidth().clickable { seed.setRemoveWithPlaces(false) }.testTag("seed_remove_area_only"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(!both, { seed.setRemoveWithPlaces(false) }); Text("Only the area. Its places stay as ordinary fetched places and can be evicted by the pack budget.")
                }
            }
        },
        confirmButton = { TextButton(seed.confirmRemove, Modifier.testTag("seed_remove_confirm")) { Text("Remove") } },
        dismissButton = { TextButton(seed.cancelRemove, Modifier.testTag("seed_remove_cancel")) { Text("Keep") } },
    )
}

/** S3: one line per background seed still to finish ("Seeding places 42%") with its Cancel button. Never a name or a position. */
@Composable
internal fun SeedProgressRows(p: SeedPanel) {
    p.ui.progress.forEachIndexed { i, r ->
        NoticeText(r.text, error = r.failed > 0, tag = "seed_progress_$i")
        if (r.failed > 0) SecondaryAction("Retry failed cells", { p.retryFailed(r.id) }, tag = "seed_retry_$i", enabled = !p.ui.seeding)
        else SecondaryAction("Cancel seeding", { p.cancelSeed(r.id) }, tag = "seed_cancel_$i")
    }
}

internal val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

@Composable
fun ZonesScreen(state: ZonesUiState, on: ZonesCallbacks, offline: OfflineMapPanel? = null, online: OnlineMapPanel? = null, seed: SeedPanel? = null, networkOnline: Boolean = true) {
    val seedMode = seed?.seedMode == true
    // Offline: drawing and the new-zone form are read-only. Viewing, pause and the guard are untouched. A drawing in progress is cancelled.
    LaunchedEffect(networkOnline, state.drawing) { if (!networkOnline && state.drawing) on.setDrawing(false) }
    val mapState = if (networkOnline) state else state.copy(drawing = false)
    // Tap on a seeded-area row: the map above fits that area (works with no network: the blank grid is drawn from the phone's own data).
    // The tap is the view-model's (selection, margin box, sequence); the screen scrolls to the map card (animated) and the map fits the box.
    val focusSeq = seed?.ui?.focusSeq ?: 0
    val focusBox = seed?.ui?.focusBox
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(focusSeq) { if (focusSeq > 0) listState.animateScrollToItem(MAP_ITEM_INDEX) }
    SettingsPage(if (seedMode) "Pick an area on the map" else "Pause zones", on.back, tag = "zones_screen", listState = listState) {
        item {
            SettingsCard {
                Text((if (seedMode) "Draw a box, then seed places for it. Seeded areas are blue, your pins purple, pause zones red. " else "Capture pauses inside a red box and in the ring around it. ") + if (offline?.reader != null) "The map is drawn from your offline file." else if (online?.source != null) "The map is drawn from online street tiles; the grid shows where they are missing." else "The map has no tiles; the grid shows latitude and longitude.", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
                if (!networkOnline) NoNetworkBanner()
                ZoneMap(mapState, on, offline?.reader, online, seed?.ui, blank = !networkOnline, focusBox = focusBox, focusSeq = focusSeq, clearFocus = seed?.clearFocus ?: {})
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryAction(if (state.drawing) "Cancel drawing" else "Draw a box", { on.setDrawing(!state.drawing) }, tag = "zone_draw", enabled = networkOnline && (seedMode || !state.protectedMode))
                    if (online?.canCentre == true) SecondaryAction("Centre on me", online.centreOnMe, tag = "zone_centre", enabled = !online.centreBusy)
                    if (online?.centreBusy == true) SecondaryAction("Cancel", online.centreCancel, tag = "zone_centre_cancel")
                }
                if (online?.centreBusy == true) NoticeText(com.rokid.context.phone.ui.settings.privacy.zones.tiles.CentreWords.FINDING, tag = "zone_centre_finding")
                online?.note?.let { n -> NoticeText(n, tag = "zone_centre_note"); SecondaryAction("Dismiss", online.dismissNote, tag = "zone_centre_note_dismiss") }
                if (!networkOnline) NoticeText(ZoneWords.OFFLINE_NOTICE, tag = "zone_offline")
                if (state.drawing) NoticeText("Drag on the map from one corner to the opposite corner.", tag = "zone_draw_hint")
                if (state.you == null) NoticeText("Your position is not shown: there is no fresh location fix.", tag = "zone_no_fix")
            }
        }
        if (state.protectedMode) item { SettingsCard { NoticeText(ZoneWords.PROTECTED_BANNER, error = true, tag = "zone_protected") } }
        if (state.deniedBanner) item {
            SettingsCard {
                NoticeText(ZoneWords.DENIED_BANNER, error = true, tag = "zone_denied")
                PrimaryAction("Allow location", on.requestLocation, tag = "zone_allow_location")
            }
        }
        state.message?.let { m -> item { SettingsCard { NoticeText(m, error = state.messageIsError, tag = "zone_message"); SecondaryAction("Dismiss", on.dismissMessage, tag = "zone_message_dismiss") } } }
        seed?.let { p -> item { SeedCard(state, p, on) } }
        online?.let { p -> item { OnlineMapCard(p) } }
        offline?.let { p -> item { OfflineMapCard(p) } }
        if (!seedMode) item { NewZoneCard(state, on, networkOnline) }
        seed?.let { p -> if (p.ui.areas.isNotEmpty()) item { SeededAreasCard(p) { a -> p.focus(a.id) } } }
        item {
            SettingsCard {
                Text("Your zones", style = MaterialTheme.typography.titleMedium)
                if (state.rows.isEmpty()) NoticeText("No pause zones yet.", tag = "zone_none")
            }
        }
        state.rows.forEach { r -> item(key = "zone_${r.zone.id}") { ZoneRow(r, on) } }
        if (!seedMode) item {
            SettingsCard {
                Text("Preset", style = MaterialTheme.typography.titleMedium)
                NoticeText(ZoneWords.PRESET_NOTE, tag = "zone_preset_note")
                if (state.presetAvailable) PrimaryAction(ZoneWords.PRESET_LABEL, on.addPreset, tag = "zone_preset", enabled = !state.protectedMode)
                else NoticeText("The Jarawa preset is already in your list.", tag = "zone_preset_added")
            }
        }
    }
    online?.takeIf { it.ui.askFirst }?.let { p ->
        AlertDialog(
            onDismissRequest = p.cancelFirst, modifier = Modifier.testTag("online_first_dialog"),
            title = { Text(OnlineMapWords.DIALOG_TITLE) },
            text = { Text(OnlineMapWords.dialogBody(p.ui.providerName)) },
            // Cancel is the prominent (default) choice; turning the download on is the plain text button.
            confirmButton = { Button(p.cancelFirst, Modifier.testTag("online_first_cancel")) { Text("Cancel") } },
            dismissButton = { TextButton(p.confirmFirst, Modifier.testTag("online_first_confirm")) { Text("Turn on and download tiles") } },
        )
    }
    seed?.ui?.confirmCells?.let { n ->
        AlertDialog(
            onDismissRequest = seed.cancelConfirm, modifier = Modifier.testTag("seed_confirm_dialog"),
            title = { Text("Seed a large area?") },
            text = { Text(SeedEstimate.confirmText(n)) },
            confirmButton = { Button(seed.cancelConfirm, Modifier.testTag("seed_confirm_cancel")) { Text("Cancel") } },
            dismissButton = { TextButton(seed.confirmSeed, Modifier.testTag("seed_confirm_yes")) { Text("Seed it") } },
        )
    }
    seed?.ui?.pendingRemoveId?.let { SeedRemoveDialog(seed) }
    state.pendingDeleteId?.let { id ->
        val name = state.rows.firstOrNull { it.zone.id == id }?.zone?.name ?: "this zone"
        AlertDialog(
            onDismissRequest = on.cancelDelete, modifier = Modifier.testTag("zone_delete_dialog"),
            title = { Text("Delete zone?") },
            text = { Text("\"$name\" will no longer pause capture. A zone you are inside cannot be deleted.") },
            confirmButton = { TextButton(on.confirmDelete, Modifier.testTag("zone_delete_confirm")) { Text("Delete") } },
            dismissButton = { TextButton(on.cancelDelete, Modifier.testTag("zone_delete_cancel")) { Text("Keep") } },
        )
    }
}

/** The box drawn above, its cell estimate and the seed button. The seed itself is the existing Place Guide seed, with its Offline only and Wi-Fi rules. */
@Composable
private fun SeedCard(state: ZonesUiState, p: SeedPanel, on: ZonesCallbacks) {
    val box = SeedEstimate.boxOf(state.draft.box())
    val cells = box?.let { SeedEstimate.cells(it) } ?: 0L
    val editing = p.ui.editingId != null
    // "Edit box": the area's rectangle is loaded into the draft by the screen; the same handles then reshape it.
    LaunchedEffect(p.ui.editSeq) { if (p.ui.editSeq > 0) p.ui.editBox?.let { b -> on.editDraft { d -> ZoneDrafts.withBox(d, b) } } }
    SettingsCard {
        Text(if (editing) "Edit the seeded area's box" else "Seed places in the box", style = MaterialTheme.typography.titleMedium)
        if (editing) NoticeText(SeedAreaList.EDIT_NOTE, tag = "seed_edit_note")
        NoticeText(SeedEstimate.estimate(box, p.ui.rates), error = SeedEstimate.level(cells) == SeedLevel.TOO_LARGE, tag = "seed_estimate")
        PrimaryAction(if (editing) "Seed this box" else "Seed places in this box", { p.seedBox(box) }, tag = "seed_box", enabled = !p.ui.seeding && box != null && SeedEstimate.level(cells) != SeedLevel.TOO_LARGE)
        if (editing) SecondaryAction("Cancel edit", { p.endEdit(); on.editDraft { ZoneDrafts.withoutBox(it) } }, tag = "seed_edit_cancel", enabled = !p.ui.seeding)
        else if (box != null) SecondaryAction("Clear box", { on.editDraft { ZoneDrafts.withoutBox(it) } }, tag = "seed_clear_box", enabled = !p.ui.seeding)
        else NoticeText("Draw a box with Draw a box above, then drag its handles to resize it or drag inside it to move it.", tag = "seed_box_hint")
        p.ui.message?.let { m -> NoticeText(m, error = p.ui.messageIsError, tag = "seed_message"); SecondaryAction("Dismiss", p.dismiss, tag = "seed_message_dismiss") }
        SeedProgressRows(p)
        NoticeText(SeedAreaList.ATTRIBUTION, tag = "seed_attribution")
        NoticeText("Only the 5 km cell keys leave the phone, never your position. Seeding runs in the background and needs a network (free Wi-Fi only when your Data saver says Wi-Fi only); it is refused under Offline only.", tag = "seed_note")
    }
}

@Composable
private fun SeededAreasCard(p: SeedPanel, focus: (SeedOverlay) -> Unit) {
    SettingsCard { SeededAreasContent(p, focus) }
}

@Composable
internal fun OnlineMapCard(p: OnlineMapPanel) {
    SettingsCard {
        Text("Street map", style = MaterialTheme.typography.titleMedium)
        if (!p.ui.available) { NoticeText(OnlineMapWords.UNAVAILABLE, tag = "online_unavailable"); p.keys?.let { MapKeyField(it, p.keyChanged) }; return@SettingsCard }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(OnlineMapWords.switchLabel(p.ui.providerName), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(p.ui.enabled, { p.setEnabled(it) }, modifier = Modifier.testTag("online_switch"))
        }
        NoticeText("Off by default. When on, the tile provider receives requests for the area you are looking at, and your IP address, only while this screen is open. Tiles are kept on the phone (up to 50 MB) until you turn this off or use Delete all. An offline file you choose below is used instead.", tag = "online_note")
        p.keys?.let { MapKeyField(it, p.keyChanged) }
    }
}

@Composable
internal fun OfflineMapCard(p: OfflineMapPanel) {
    val u = p.ui
    SettingsCard {
        Text("Offline map", style = MaterialTheme.typography.titleMedium)
        val sel = u.selection
        if (sel == null) NoticeText(OfflineMapWords.NONE, tag = "offline_none")
        else {
            val where = if (sel.mode == MapMode.IMPORTED) "Copied into the app (private, not backed up)" else "Read in place from where you keep it (read-only)"
            Text(sel.name.ifBlank { "Map file" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("offline_name"))
            NoticeText(where + (u.sizeBytes?.takeIf { it > 0 }?.let { " · " + OfflineMapWords.size(it) } ?: "") + if (u.ready) " · zoom ${u.minZoom} to ${u.maxZoom}" else "", tag = "offline_where")
        }
        if (u.busy) NoticeText("Working...", tag = "offline_busy")
        u.message?.let { m -> NoticeText(m, error = u.messageIsError, tag = "offline_message"); SecondaryAction("Dismiss", p.dismiss, tag = "offline_message_dismiss") }
        PrimaryAction("Choose a .pmtiles file", p.chooseLink, tag = "offline_choose", enabled = !u.busy)
        SecondaryAction("Import (copy) into the app", p.chooseImport, tag = "offline_import", enabled = !u.busy)
        if (sel != null) SecondaryAction(if (sel.mode == MapMode.IMPORTED) "Delete the copy" else "Stop using this file", p.remove, tag = "offline_remove", enabled = !u.busy)
        NoticeText("Choose reads the file where it is, with no storage permission. Import copies it into the app only if you ask. Online tiles are used only if you turn on the street map above.", tag = "offline_note")
    }
}

@Composable
private fun NewZoneCard(state: ZonesUiState, on: ZonesCallbacks, networkOnline: Boolean) {
    val fieldsOn = networkOnline && !state.protectedMode
    val d = state.draft
    SettingsCard {
        Text("New zone", style = MaterialTheme.typography.titleMedium)
        TextFieldRow("Name", d.name, { v -> on.editDraft { it.copy(name = v) } }, tag = "zone_name", enabled = fieldsOn)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { TextFieldRow("South (lat)", d.south, { v -> on.editDraft { it.copy(south = v) } }, tag = "zone_south", number = true, enabled = fieldsOn) }
            Box(Modifier.weight(1f)) { TextFieldRow("North (lat)", d.north, { v -> on.editDraft { it.copy(north = v) } }, tag = "zone_north", number = true, enabled = fieldsOn) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { TextFieldRow("West (lon)", d.west, { v -> on.editDraft { it.copy(west = v) } }, tag = "zone_west", number = true, enabled = fieldsOn) }
            Box(Modifier.weight(1f)) { TextFieldRow("East (lon)", d.east, { v -> on.editDraft { it.copy(east = v) } }, tag = "zone_east", number = true, enabled = fieldsOn) }
        }
        TextFieldRow("Buffer (metres, 0 to 50,000)", d.buffer, { v -> on.editDraft { it.copy(buffer = v) } }, tag = "zone_buffer", number = true, enabled = fieldsOn)
        state.draftError?.let { NoticeText(it, error = true, tag = "zone_draft_error") }
        PrimaryAction("Add zone", on.save, tag = "zone_save", enabled = fieldsOn)
        SecondaryAction("Clear", on.clearDraft, tag = "zone_clear", enabled = !d.isEmpty)
    }
}

@Composable
internal fun ZoneRow(r: ZoneRowUi, on: ZonesCallbacks) {
    val z = r.zone
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(z.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("zone_name_${z.id}"))
                Text("Buffer ${ZoneWords.distance(z.bufferM.toDouble())}" + if (z.preset != null) " · preset, UNVERIFIED" else "", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
            }
            Switch(z.enabled, { on.setEnabled(z.id, it) }, modifier = Modifier.testTag("zone_switch_${z.id}"))
        }
        StateChip(r.chip, Modifier.testTag("zone_chip_${z.id}"))
        SecondaryAction("Delete", { on.askDelete(z.id) }, tag = "zone_delete_${z.id}")
    }
}

@Composable
internal fun StateChip(chip: ZoneChip, modifier: Modifier = Modifier) {
    val (bg, fg) = when (chip.level) {
        ChipLevel.OFF -> PcColors.ChipSlate
        ChipLevel.CLEAR -> PcColors.ChipMint
        ChipLevel.WARN -> PcColors.ChipAmber
        ChipLevel.PAUSED -> PcColors.ChipCoral
    }
    Text(chip.text, color = fg, style = MaterialTheme.typography.labelLarge, modifier = modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp))
}

/** The tile-less map: plain background, a latitude/longitude grid, red boxes with their buffer ring, the draft box and (only with a fresh fix) a blue dot for the user. */
@Composable
private fun ZoneMap(state: ZonesUiState, on: ZonesCallbacks, reader: PmTilesReader? = null, online: OnlineMapPanel? = null, seed: SeedMapUiState? = null, blank: Boolean = false, focusBox: DoubleArray? = null, focusSeq: Int = 0, clearFocus: () -> Unit = {}) {
    val measurer = rememberTextMeasurer()
    var size by remember { mutableStateOf(Size.Zero) }
    var vp by remember { mutableStateOf<MapViewport?>(null) }
    var fitKey by remember { mutableStateOf(0) }
    var dragA by remember { mutableStateOf<Offset?>(null) }
    var dragB by remember { mutableStateOf<Offset?>(null) }
    val draftNow by rememberUpdatedState(state.draft)
    val drawingNow by rememberUpdatedState(state.drawing)
    val editDraftNow by rememberUpdatedState(on.editDraft)
    val zones = state.rows.map { it.zone }
    val areas = seed?.areas.orEmpty()
    val pins = seed?.pins.orEmpty()
    val fitSig = zones.map { it.id } + areas.map { it.id }
    LaunchedEffect(size, fitSig, fitKey) {
        if (size.width < 10f || size.height < 10f) return@LaunchedEffect
        val boxes = (zones.map { MapViewport.ringOf(it) } + areas.map { it.box }).ifEmpty { state.you?.let { listOf(MapViewport.grown(it.lat, it.lon, it.lat, it.lon, 2_000.0)) } ?: emptyList() }
        vp = MapViewport.fit(boxes, size.width, size.height)
    }
    // An offline PMTiles file wins; otherwise the opt-in online source (only while this screen is in the foreground); otherwise the plain grid.
    val onlineSource = online?.source?.takeIf { reader == null && !blank } // no network: the plain grid only, cached online tiles are not painted
    val tiles: RasterTileSource? = remember(reader, onlineSource) { reader?.let { r -> ReaderTiles(r) } ?: onlineSource }
    val cache = remember(tiles) { tiles?.let { t -> TileCache<ImageBitmap>(weigh = { it.width.toLong() * it.height * 4 }, rememberAbsent = onlineSource == null) { k -> t.tile(k)?.let { b -> decodeTileBounded(b) } } } }
    var loaded by remember(cache) { mutableStateOf(0) }
    val zoom = vp?.let { v -> tiles?.let { TileMath.zoomFor(v.ppd, it.minZoom, it.maxZoom) } }
    val wanted = if (vp != null && zoom != null) TileMath.visible(vp!!, zoom) else emptyList()
    val canFetch = onlineSource == null || online?.foreground == true
    LaunchedEffect(cache, wanted, canFetch) {
        val c = cache ?: return@LaunchedEffect
        if (wanted.isEmpty() || !canFetch) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            if (onlineSource == null) { if (c.ensure(wanted)) loaded++ }
            else for (chunk in wanted.chunked(ONLINE_PARALLEL)) coroutineScope { chunk.map { k -> async { if (c.ensure(listOf(k))) loaded++ } }.awaitAll() }
        }
    }
    LaunchedEffect(focusSeq, size.width >= 10f && size.height >= 10f) {
        val b = focusBox ?: return@LaunchedEffect
        if (size.width < 10f || size.height < 10f) return@LaunchedEffect
        vp = MapViewport.fit(listOf(b), size.width, size.height)
    }
    val centreReq = online?.centre
    LaunchedEffect(centreReq?.id, size.width >= 10f && size.height >= 10f) {
        val r = centreReq ?: return@LaunchedEffect
        if (size.width < 10f || size.height < 10f) return@LaunchedEffect
        vp = MapViewport.fit(listOf(MapViewport.grown(r.at.lat, r.at.lon, r.at.lat, r.at.lon, CENTRE_HALF_SPAN_M)), size.width, size.height)
    }
    val offlineAttribution = remember(reader) { reader?.attribution() }
    val onlineShown = onlineSource != null && loaded >= 0 && cache != null && wanted.any { cache.peek(it) != null }
    val summary = (if (seed == null) "Map with ${zones.size} pause zone" + (if (zones.size == 1) "" else "s") else "Map with " + SeedOverlays.legend(zones.size, areas.size, pins.size)) + if (state.you != null) " and your position" else ""
    Box(
        Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, PcColors.Line, RoundedCornerShape(12.dp))
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()); vp = vp?.resized(size.width, size.height) }
            .semantics { contentDescription = summary }.testTag("zone_map"),
    ) {
        Canvas(
            Modifier.fillMaxWidth().height(300.dp).background(if (blank) Color.White else MapBackground)
                .pointerInput(seed?.selectedId, areas) { detectTapGestures { p -> if (seed?.selectedId != null && vp.let { v -> v == null || areas.none { a -> SeedOverlays.screenRect(v, a.box, 0f).let { r -> p.x in r[0]..(r[0] + r[2]) && p.y in r[1]..(r[1] + r[3]) } } }) clearFocus() } } // a tap on the map background clears the highlight
                .pointerInput(state.drawing) {
                    if (state.drawing) {
                        detectDragGestures(
                            onDragStart = { dragA = it; dragB = it },
                            onDrag = { change, _ -> dragB = change.position },
                            onDragEnd = {
                                val a = dragA; val b = dragB; val v = vp
                                if (a != null && b != null && v != null) on.drawn(v.toLatLon(a.x, a.y), v.toLatLon(b.x, b.y)) else on.setDrawing(false)
                                dragA = null; dragB = null
                            },
                            onDragCancel = { dragA = null; dragB = null },
                        )
                    } else {
                        detectTransformGestures { centroid, pan, zoom, _ -> vp = vp?.panned(pan.x, pan.y)?.zoomed(zoom.toDouble(), centroid.x, centroid.y) }
                    }
                }
                // Innermost: a finger that lands on a handle of the drawn box (corner or side) resizes it, inside the box moves it; it never also pans the map. The box is only the draft: nothing is saved by a drag.
                .pointerInput(Unit) {
                    val touch = ShapeEdit.HANDLE_TOUCH_DP.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val v0 = vp ?: return@awaitEachGesture
                        val b0 = if (drawingNow) null else draftNow.box()
                        if (b0 == null) return@awaitEachGesture
                        var cur: DrawnShape = DrawnShape.Rect(b0[0], b0[1], b0[2], b0[3])
                        val hit = ShapeEdit.hit(v0, cur, down.position.x, down.position.y, touch, edges = true)
                        if (hit is Hit.Miss) return@awaitEachGesture
                        var last = down.position
                        down.consume()
                        drag(down.id) { change ->
                            val v = vp ?: return@drag
                            val next = if (hit is Hit.OnHandle) ShapeEdit.resize(cur, hit.handle, v.toLatLon(change.position.x, change.position.y))
                            else { val a = v.toLatLon(last.x, last.y); val c = v.toLatLon(change.position.x, change.position.y); ShapeEdit.move(cur, c.lat - a.lat, c.lon - a.lon) }
                            last = change.position
                            if (next != cur) { cur = next; ShapeEdit.box(next)?.let { nb -> editDraftNow { d -> ZoneDrafts.withBox(d, nb) } } }
                            change.consume()
                        }
                    }
                },
        ) {
            val v = vp ?: return@Canvas
            var painted = false
            if (cache != null && loaded >= 0) for (k in wanted) {
                val img = cache.peek(k) ?: continue
                val r = TileMath.screenRect(v, k)
                drawImage(img, dstOffset = IntOffset(r[0].roundToInt(), r[1].roundToInt()), dstSize = IntSize(r[2].roundToInt() + 1, r[3].roundToInt() + 1))
                painted = true
            }
            if (!painted) drawGrid(v, measurer)
            for (a in areas) if (a.id != seed?.selectedId) drawSeedArea(v, a)
            areas.firstOrNull { it.id == seed?.selectedId }?.let { drawSeedArea(v, it, measurer, selected = true) } // the tapped area on top, with its name
            for (r in state.rows) drawZone(v, r.zone, measurer, active = r.zone.enabled)
            for (p in pins) drawPin(v, p)
            state.draft.box()?.let { b ->
                drawBox(v, b, ZoneRed, filled = false, dashed = true)
                if (!state.drawing) for ((_, p) in ShapeEdit.handlePoints(v, DrawnShape.Rect(b[0], b[1], b[2], b[3]), edges = true).orEmpty()) {
                    drawCircle(Color(0xFF111827), (ShapeEdit.HANDLE_DRAW_DP / 2 + 2).dp.toPx(), Offset(p.first, p.second)); drawCircle(Color.White, (ShapeEdit.HANDLE_DRAW_DP / 2).dp.toPx(), Offset(p.first, p.second))
                }
            }
            val a = dragA; val b = dragB
            if (a != null && b != null) drawRect(ZoneRed, Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y)), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
            (centreReq?.at ?: state.you)?.let { y -> val p = v.toPx(y.lat, y.lon); drawCircle(Color.White, 9.dp.toPx(), Offset(p.x, p.y)); drawCircle(PcColors.Sky, 6.dp.toPx(), Offset(p.x, p.y)) }
        }
        offlineAttribution?.let { a -> Text(a, style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft, modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp).testTag("zone_map_attribution")) }
        // The provider's terms: its attribution and logo text on every map that shows its tiles.
        if (onlineShown && onlineSource != null) Row(Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp).testTag("zone_map_attribution"), verticalAlignment = Alignment.CenterVertically) {
            onlineSource.logoText?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = PcColors.InkSoft, modifier = Modifier.padding(end = 4.dp).testTag("zone_map_logo")) }
            onlineSource.attribution?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft) }
        }
        if (seed != null) Text(SeedOverlays.legend(zones.size, areas.size, pins.size), style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft, modifier = Modifier.align(Alignment.BottomStart).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp).testTag("zone_map_legend"))
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            MapButton("+", "zone_zoom_in") { vp = vp?.zoomed(1.6) }
            MapButton("-", "zone_zoom_out") { vp = vp?.zoomed(1 / 1.6) }
            MapButton("Fit", "zone_fit") { fitKey++ }
        }
    }
}

@Composable
private fun MapButton(text: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.testTag(tag)) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * Lays [text] out with explicit, never-negative constraints and draws it. A tick label ([shift] false) is one line starting at [at] (skipped when off-canvas);
 * a zone name ([shift] true) is wrapped at a sensible max width and slid left when it would run off the right edge, so it is never one character wide.
 * Returns the label's right edge (canvas px), or null when it was skipped.
 */
internal fun DrawScope.drawLabel(tm: TextMeasurer, text: String, at: Offset, style: TextStyle, shift: Boolean = false): Float? {
    if (!at.x.isFinite() || !at.y.isFinite() || size.height - at.y < 1f || at.x + 4096f < 0f) return null
    val maxW = if (shift) minOf(size.width - 4f, LABEL_MAX_WIDTH_DP.dp.toPx()) else size.width - at.x
    if (maxW < 1f) return null
    val layout = tm.measure(text, style, softWrap = shift, constraints = Constraints(maxWidth = maxW.toInt().coerceAtLeast(1)))
    val x = if (shift) at.x.coerceAtMost(size.width - layout.size.width).coerceAtLeast(0f) else at.x
    drawText(layout, topLeft = Offset(x, at.y))
    return x + layout.size.width
}

private const val LABEL_MAX_WIDTH_DP = 160
/** The map card is the second item of the Pause zones list (after the header): the tap scrolls here. */
private const val MAP_ITEM_INDEX = 1
private const val ONLINE_PARALLEL = 4
/** "Centre on me": the box is 2 x this around the fix, so the view is about 500 m across. */
private const val CENTRE_HALF_SPAN_M = 250.0

/** The open PMTiles archive as a tile source: it never goes online and its attribution is drawn from the archive itself. */
internal class ReaderTiles(private val r: PmTilesReader) : RasterTileSource {
    override val minZoom get() = r.minZoom
    override val maxZoom get() = r.maxZoom
    override val attribution: String? get() = null
    override fun tile(k: TileKey): ByteArray? = r.tile(k.z, k.x, k.y)
}
private const val TICK_GAP_PX = 6f

/** One longitude gridline: its x, and its label (null when the label would overlap the previous kept one) with the label's [left, right] edges. */
internal class LonTick(val x: Float, val text: String?, val left: Float, val right: Float)

internal fun DrawScope.lonTicks(v: MapViewport, tm: TextMeasurer, label: TextStyle): List<LonTick> {
    val step = v.gridStepDeg()
    val tl = v.toLatLon(0f, 0f); val br = v.toLatLon(size.width, size.height)
    val decimals = if (step < 0.001) 4 else if (step < 0.01) 3 else if (step < 0.1) 2 else if (step < 1.0) 1 else 0
    val out = ArrayList<LonTick>()
    var lon = floor(tl.lon / step) * step
    var n = 0
    var lastRight = Float.NEGATIVE_INFINITY
    while (lon <= br.lon && n++ < 200) {
        val x = v.toPx(v.centerLat, lon).x
        val text = degText(lon, decimals, "E", "W")
        val w = tm.measure(text, label, softWrap = false).size.width
        val keep = x + 2f >= lastRight + TICK_GAP_PX
        if (keep) lastRight = x + 2f + w
        out += LonTick(x, if (keep) text else null, x + 2f, x + 2f + w)
        lon += step
    }
    return out
}

internal fun DrawScope.drawGrid(v: MapViewport, tm: TextMeasurer) {
    val step = v.gridStepDeg()
    val tl = v.toLatLon(0f, 0f); val br = v.toLatLon(size.width, size.height)
    val label = TextStyle(fontSize = 10.sp, color = PcColors.InkSoft)
    val decimals = if (step < 0.001) 4 else if (step < 0.01) 3 else if (step < 0.1) 2 else if (step < 1.0) 1 else 0
    for (t in lonTicks(v, tm, label)) {
        drawLine(PcColors.Line, Offset(t.x, 0f), Offset(t.x, size.height), 1f)
        t.text?.let { drawLabel(tm, it, Offset(t.x + 2f, size.height - 14.sp.toPx()), label) }
    }
    var lat = floor(br.lat / step) * step
    var n = 0
    while (lat <= tl.lat && n++ < 200) {
        val y = v.toPx(lat, v.centerLon).y
        drawLine(PcColors.Line, Offset(0f, y), Offset(size.width, y), 1f)
        drawLabel(tm, degText(lat, decimals, "N", "S"), Offset(2f, y + 1f), label)
        lat += step
    }
}

internal fun degText(v: Double, decimals: Int, pos: String, neg: String): String = String.format(java.util.Locale.US, "%.${decimals}f", abs(v)) + "°" + (if (v >= 0) pos else neg)

internal fun DrawScope.drawZone(v: MapViewport, z: PauseZone, tm: TextMeasurer, active: Boolean) {
    val c = if (active) ZoneRed else ZoneRed.copy(alpha = 0.4f)
    drawBox(v, MapViewport.ringOf(z), c.copy(alpha = 0.7f), filled = false, dashed = true)
    drawBox(v, doubleArrayOf(z.south, z.west, z.north, z.east), c, filled = true, dashed = false)
    val p = v.toPx(z.north, z.west)
    drawLabel(tm, z.name, Offset(p.x + 4f, p.y + 2f), TextStyle(fontSize = 11.sp, color = Color(0xFF7A1010)), shift = true)
}

private val SeedAmber = Color(0xFFE08A00)
private val PinPurple = Color(0xFF7B3FC4)

/**
 * A seeded area in ITS OWN colour ([SeedOverlay.colour], a palette index): a translucent fill and an outline (dotted when done, a long dash while seeding), so it reads zoomed out and stays apart from the red pause zones and
 * purple pins. [selected] (the tapped row): a thicker solid outline, a stronger fill and its name label ([tm] given). A partial area keeps its amber "!" badge. Tiny on screen = a 16 dp chip so it still shows zoomed out.
 */
internal fun DrawScope.drawSeedArea(v: MapViewport, a: SeedOverlay, tm: TextMeasurer? = null, selected: Boolean = false) {
    val c = Color(SeedPalette.argb(a.colour))
    val r = SeedOverlays.screenRect(v, a.box, SeedOverlays.MIN_SHAPE_DP.dp.toPx())
    if (!r.all { it.isFinite() }) return
    val tl = Offset(r[0], r[1]); val sz = Size(r[2], r[3])
    drawRect(c.copy(alpha = if (selected) 0.35f else 0.22f), tl, sz)
    val dash = if (selected) null else if (a.look == AreaLook.SEEDING) floatArrayOf(24f, 8f) else floatArrayOf(2f, 8f)
    drawRect(c, tl, sz, style = Stroke((if (selected) 5 else 2).dp.toPx(), pathEffect = dash?.let { PathEffect.dashPathEffect(it) }))
    if (selected) drawRect(Color.White.copy(alpha = 0.9f), Offset(tl.x - 1.dp.toPx(), tl.y - 1.dp.toPx()), Size(sz.width + 2.dp.toPx(), sz.height + 2.dp.toPx()), style = Stroke(1.dp.toPx()))
    if (selected && tm != null) drawLabel(tm, a.label, Offset(tl.x + 6.dp.toPx(), tl.y + 6.dp.toPx()), TextStyle(fontSize = 12.sp, color = Color(0xFF15181D), background = Color.White.copy(alpha = 0.85f)), shift = true)
    if (a.look == AreaLook.PARTIAL) { // the badge: a filled amber disc with a white bar and dot
        val cx = tl.x + sz.width - 8.dp.toPx(); val cy = tl.y + 8.dp.toPx()
        drawCircle(SeedAmber, 7.dp.toPx(), Offset(cx, cy))
        drawLine(Color.White, Offset(cx, cy - 4.dp.toPx()), Offset(cx, cy + 1.dp.toPx()), 2.dp.toPx())
        drawCircle(Color.White, 1.2.dp.toPx(), Offset(cx, cy + 4.dp.toPx()))
    }
}

/** A user pin: a purple dot with a white rim. No name is drawn. */
internal fun DrawScope.drawPin(v: MapViewport, p: PinInfo) {
    val q = v.toPx(p.lat, p.lon)
    drawCircle(Color.White, 6.dp.toPx(), Offset(q.x, q.y))
    drawCircle(PinPurple, 4.dp.toPx(), Offset(q.x, q.y))
}

private fun DrawScope.drawBox(v: MapViewport, b: DoubleArray, color: Color, filled: Boolean, dashed: Boolean) {
    val tl = v.toPx(b[2], b[1]); val br = v.toPx(b[0], b[3])
    val tlo = Offset(tl.x, tl.y); val sz = Size(br.x - tl.x, br.y - tl.y)
    if (filled) drawRect(color.copy(alpha = 0.25f), tlo, sz)
    drawRect(color, tlo, sz, style = Stroke(if (filled) 2.dp.toPx() else 1.5.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
}

/** Decodes one raster tile with a pixel cap: reads the size first, subsamples past 16 megapixels, refuses absurd sizes. Returns null (plain grid) on anything unusable. */
internal fun decodeTileBounded(b: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
    val sample = TileDecode.sampleSize(bounds.outWidth, bounds.outHeight)
    if (sample == 0) return null
    return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}
