package com.rokid.context.phone.ui.settings.mapzones

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsDeps
import com.rokid.context.phone.ui.settings.TextFieldRow
import com.rokid.context.phone.ui.settings.privacy.zones.ChipLevel
import com.rokid.context.phone.ui.settings.privacy.zones.LOCATION_PERMISSIONS
import com.rokid.context.phone.ui.settings.privacy.zones.OfflineMapCard
import com.rokid.context.phone.ui.settings.privacy.zones.OfflineMapPanel
import com.rokid.context.phone.ui.settings.privacy.zones.OnlineMapCard
import com.rokid.context.phone.ui.settings.privacy.zones.OnlineMapPanel
import com.rokid.context.phone.ui.settings.privacy.zones.PICK_TYPES
import com.rokid.context.phone.ui.settings.privacy.zones.SeedEstimate
import com.rokid.context.phone.ui.settings.privacy.zones.SeedLevel
import com.rokid.context.phone.ui.settings.privacy.zones.SeedMapControl
import com.rokid.context.phone.ui.settings.privacy.zones.SeedMapViewModel
import com.rokid.context.phone.ui.settings.privacy.zones.SeedPanel
import com.rokid.context.phone.ui.settings.privacy.zones.SeedOverlays
import com.rokid.context.phone.ui.settings.privacy.zones.StateChip
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneRow
import com.rokid.context.phone.ui.settings.privacy.zones.ZonesCallbacks
import com.rokid.context.phone.ui.settings.privacy.zones.ZonesUiState
import com.rokid.context.phone.ui.settings.privacy.zones.ZonesViewModel
import com.rokid.context.phone.ui.settings.privacy.zones.rememberOnlinePanel
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OfflineMapViewModel
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapWords

// Map zones P1 (flag mapZonesV2). The screen is a full-height map with a bottom panel. The old Pause zones screen is untouched and stays the default.
// Pause zones go through the existing zone store path only: the ViewModel's one upsert call, the existing enable / delete (with its confirm dialog) calls of ZonesViewModel.

class MapZonesCallbacks(
    val back: () -> Unit,
    val beginDraw: () -> Unit,
    val cancelDraw: () -> Unit,
    val setKind: (ShapeKind) -> Unit,
    val place: (DrawnShape?) -> Unit,
    val reshape: (DrawnShape) -> Unit,
    val next: () -> Unit,
    val choose: (SheetMode) -> Unit,
    val backSheet: () -> Unit,
    val setName: (String) -> Unit,
    val setBuffer: (String) -> Unit,
    val savePause: () -> Unit,
    val seedNow: () -> Unit,
    val toggleLayer: (MapLayer) -> Unit,
    val tap: (TapTarget) -> Unit,
    val select: (Selection?) -> Unit,
    val editPause: (String) -> Unit,
    val dismiss: () -> Unit,
)

@Composable
fun MapZonesRoute(deps: SettingsDeps, back: () -> Unit, seed: SeedMapControl? = null, seedMode: Boolean = false) {
    val make = deps.zoneEditor
    if (make == null) { Box(Modifier.fillMaxSize().testTag("zones_unavailable")) { NoticeText("Pause zones are not available in this build.") }; return }
    val zonesVm = viewModel { ZonesViewModel(make()) }
    val mapVm = viewModel(key = "map_zones_v2") { MapZonesViewModel(make()) }
    val zones by zonesVm.state.collectAsStateWithLifecycle()
    val ui by mapVm.state.collectAsStateWithLifecycle()
    fun granted() = deps.locationGranted()
    LaunchedEffect(Unit) { zonesVm.setLocationGranted(granted()); mapVm.setLocationGranted(granted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { zonesVm.setLocationGranted(granted()); mapVm.setLocationGranted(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { zonesVm.setLocationGranted(granted()); mapVm.setLocationGranted(granted()) }
    LaunchedEffect(zones.requestPermission) { if (zones.requestPermission) { zonesVm.permissionPromptShown(); launcher.launch(LOCATION_PERMISSIONS) } }
    LaunchedEffect(ui.savedTick) { if (ui.savedTick > 0 && !granted()) zonesVm.requestLocation() } // a saved zone with location off: the same prompt the shipped screen shows
    val offline = deps.offlineMap?.let { m -> remember { m() } }
    val online = deps.onlineMap?.let { m -> remember { m() } }?.let { d -> rememberOnlinePanel(d, ::granted, zonesVm::requestLocation) }
    val seedVm = seed?.let { c -> viewModel(key = "seed_map") { SeedMapViewModel(c) } }
    val seedState = seedVm?.state?.collectAsStateWithLifecycle()?.value
    if (seedVm != null) {
        LaunchedEffect(seedVm) { seedVm.refresh() }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { seedVm.refresh() }
        LaunchedEffect(seedState?.seeding) { if (seedState?.seeding == true) mapVm.cancelDraw() } // the sheet closes once the seed has really started
    }
    val seedPanel = if (seedVm != null && seedState != null) SeedPanel(seedState, seedMode, seedVm::requestSeed, seedVm::confirmSeed, seedVm::cancelConfirm, seedVm::askRemove, seedVm::confirmRemove, seedVm::cancelRemove, seedVm::dismissMessage) else null
    val view = remember { MapViewState() }
    val zoneCb = ZonesCallbacks.of(zonesVm, back)
    val offlinePanel = offline?.let { o ->
        val mapOffVm = viewModel(key = "offline_map") { OfflineMapViewModel(o.files, o.store) }
        val mapUi by mapOffVm.ui.collectAsStateWithLifecycle()
        val reader by mapOffVm.reader.collectAsStateWithLifecycle()
        val link = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) mapOffVm.link(u.toString()) }
        val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) mapOffVm.importCopy(u.toString()) }
        OfflineMapPanel(mapUi, reader, { link.launch(PICK_TYPES) }, { import.launch(PICK_TYPES) }, mapOffVm::remove, mapOffVm::dismissMessage)
    }
    val cb = MapZonesCallbacks(
        back, mapVm::beginDraw, mapVm::cancelDraw, mapVm::setKind, mapVm::place, mapVm::reshape, mapVm::next, mapVm::choose, mapVm::back, mapVm::setName, mapVm::setBuffer, mapVm::savePause,
        { seedPanel?.seedBox?.invoke(mapVm.seedBox()) }, { l -> mapVm.toggleLayer(l, zones.rows.any { it.chip.level == ChipLevel.PAUSED || it.chip.level == ChipLevel.WARN }) },
        { t -> onTap(t, view, zones, seedPanel, mapVm) }, mapVm::select, mapVm::editPause, mapVm::dismissMessage,
    )
    MapZonesScreen(ui, zones, seedPanel, offlinePanel, online, cb, zoneCb, view, mapVm.seedLevel())
}

private fun onTap(t: TapTarget, view: MapViewState, zones: ZonesUiState, seed: SeedPanel?, vm: MapZonesViewModel) {
    when (t) {
        is TapTarget.Item -> if (t.type == ItemType.PAUSE) vm.select(Selection(SelectionKind.PAUSE, t.id)) else if (t.type == ItemType.SEED) vm.select(Selection(SelectionKind.SEED, t.id)) else vm.select(null)
        is TapTarget.Cluster -> {
            val boxes = t.ids.mapNotNull { id -> zones.rows.firstOrNull { it.zone.id == id }?.zone?.let { z -> doubleArrayOf(z.south, z.west, z.north, z.east) } ?: seed?.ui?.areas?.firstOrNull { it.id == id }?.box }
            if (boxes.isNotEmpty() && view.size.width > 10f) view.vp = com.rokid.context.phone.ui.settings.privacy.zones.MapViewport.fit(boxes, view.size.width, view.size.height)
        }
        TapTarget.None -> vm.select(null)
    }
}

@Composable
fun MapZonesScreen(
    ui: MapZonesUiState, zones: ZonesUiState, seed: SeedPanel?, offline: OfflineMapPanel?, online: OnlineMapPanel?,
    on: MapZonesCallbacks, zoneOn: ZonesCallbacks, view: MapViewState, seedLevel: SeedLevel,
) {
    val seedAreas = seed?.ui?.areas.orEmpty()
    val pins = seed?.ui?.pins.orEmpty()
    val items = remember(zones.rows, seedAreas, pins) {
        zones.rows.map { r -> MapItem(r.zone.id, ItemType.PAUSE, doubleArrayOf(r.zone.south, r.zone.west, r.zone.north, r.zone.east), alert = r.zone.enabled && (r.chip.level == ChipLevel.PAUSED || r.chip.level == ChipLevel.WARN)) } +
            seedAreas.map { MapItem(it.id, ItemType.SEED, it.box) } + pins.mapIndexed { i, p -> MapItem("pin#$i", ItemType.PIN, doubleArrayOf(p.lat, p.lon, p.lat, p.lon)) }
    }
    val pauseLocked = items.any { it.type == ItemType.PAUSE && it.alert }
    val layers = MapOverview.effective(ui.layers, pauseLocked)
    val inside = zones.rows.any { it.inside }
    val fitBoxes = zones.rows.map { com.rokid.context.phone.ui.settings.privacy.zones.MapViewport.ringOf(it.zone) } + seedAreas.map { it.box }
    val reader = offline?.reader
    Box(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).testTag("mapzones_screen")) {
        MapCanvas(
            view, zones.rows, seedAreas, pins, items, layers, ui.shape, ui.selected, zones.you, fitBoxes, ui.kind, canDraw = ui.sheet == SheetMode.NONE,
            summary = MapOverview.summary(zones.rows.size, seedAreas.size, pins.size, inside), reader = reader, online = online,
            onNewShape = on.place, onReshape = on.reshape, onTap = on.tap, modifier = Modifier.fillMaxSize(),
        )
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(PcColors.SurfaceSoft.copy(alpha = 0.9f)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(on.back, Modifier.testTag("mapzones_back")) { Text("Back") }
            Text("Map zones", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton({ view.zoom(1.6) }, Modifier.testTag("zone_zoom_in")) { Text("+") }
            TextButton({ view.zoom(1 / 1.6) }, Modifier.testTag("zone_zoom_out")) { Text("-") }
            TextButton({ view.fit() }, Modifier.testTag("zone_fit")) { Text("Fit") }
            if (online?.canCentre == true) TextButton(online.centreOnMe, Modifier.testTag("zone_centre"), enabled = !online.centreBusy) { Text("Me") }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(PcColors.SurfaceSoft).verticalScroll(rememberScrollState()).padding(16.dp).testTag("mapzones_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (zones.protectedMode) NoticeText(com.rokid.context.phone.ui.settings.privacy.zones.ZoneWords.PROTECTED_BANNER, error = true, tag = "zone_protected")
            if (zones.deniedBanner) { NoticeText(com.rokid.context.phone.ui.settings.privacy.zones.ZoneWords.DENIED_BANNER, error = true, tag = "zone_denied"); PrimaryAction("Allow location", zoneOn.requestLocation, tag = "zone_allow_location") }
            ui.message?.let { m -> NoticeText(m, error = ui.messageIsError, tag = "zone_message"); SecondaryAction("Dismiss", on.dismiss, tag = "zone_message_dismiss") }
            zones.message?.let { m -> NoticeText(m, error = zones.messageIsError, tag = "zone_message2"); SecondaryAction("Dismiss", zoneOn.dismissMessage, tag = "zone_message2_dismiss") }
            seed?.ui?.message?.let { m -> NoticeText(m, error = seed.ui.messageIsError, tag = "seed_message"); SecondaryAction("Dismiss", seed.dismiss, tag = "seed_message_dismiss") }
            when {
                ui.sheet == SheetMode.CHOOSE -> ChooseSheet(ui, zones, on)
                ui.sheet == SheetMode.PAUSE -> PauseSheet(ui, on)
                ui.sheet == SheetMode.SEED -> SeedSheet(ui, seed, seedLevel, on)
                ui.shape != null -> DraftBar(ui, on)
                else -> BrowseSheet(view, ui, zones, seed, offline, online, on, zoneOn, layers, pauseLocked, seedAreas.size, pins.size)
            }
        }
    }
    online?.takeIf { it.ui.askFirst }?.let { p ->
        AlertDialog(
            onDismissRequest = p.cancelFirst, modifier = Modifier.testTag("online_first_dialog"),
            title = { Text(OnlineMapWords.DIALOG_TITLE) }, text = { Text(OnlineMapWords.dialogBody(p.ui.providerName)) },
            confirmButton = { Button(p.cancelFirst, Modifier.testTag("online_first_cancel")) { Text("Cancel") } },
            dismissButton = { TextButton(p.confirmFirst, Modifier.testTag("online_first_confirm")) { Text("Turn on and download tiles") } },
        )
    }
    seed?.ui?.confirmCells?.let { n ->
        AlertDialog(
            onDismissRequest = seed.cancelConfirm, modifier = Modifier.testTag("seed_confirm_dialog"),
            title = { Text("Seed a large area?") }, text = { Text(SeedEstimate.confirmText(n)) },
            confirmButton = { Button(seed.cancelConfirm, Modifier.testTag("seed_confirm_cancel")) { Text("Cancel") } },
            dismissButton = { TextButton(seed.confirmSeed, Modifier.testTag("seed_confirm_yes")) { Text("Seed it") } },
        )
    }
    seed?.ui?.pendingRemoveId?.let {
        AlertDialog(
            onDismissRequest = seed.cancelRemove, modifier = Modifier.testTag("seed_remove_dialog"),
            title = { Text("Remove seeded area?") }, text = { Text("The area is forgotten. Its places stay as ordinary fetched places and can be evicted by the pack budget.") },
            confirmButton = { TextButton(seed.confirmRemove, Modifier.testTag("seed_remove_confirm")) { Text("Remove") } },
            dismissButton = { TextButton(seed.cancelRemove, Modifier.testTag("seed_remove_cancel")) { Text("Keep") } },
        )
    }
    zones.pendingDeleteId?.let { id ->
        val name = zones.rows.firstOrNull { it.zone.id == id }?.zone?.name ?: "this zone"
        AlertDialog(
            onDismissRequest = zoneOn.cancelDelete, modifier = Modifier.testTag("zone_delete_dialog"),
            title = { Text("Delete zone?") }, text = { Text("\"$name\" will no longer pause capture. A zone you are inside cannot be deleted.") },
            confirmButton = { TextButton(zoneOn.confirmDelete, Modifier.testTag("zone_delete_confirm")) { Text("Delete") } },
            dismissButton = { TextButton(zoneOn.cancelDelete, Modifier.testTag("zone_delete_cancel")) { Text("Keep") } },
        )
    }
}

@Composable
private fun DraftBar(ui: MapZonesUiState, on: MapZonesCallbacks) {
    val shape = ui.shape ?: return
    Text(if (ui.editingId != null) "Reshape the zone" else "Drag the handles to size it, drag inside to move it", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(shape.kind == ShapeKind.RECT, { on.setKind(ShapeKind.RECT) }, { Text("Rectangle") }, Modifier.testTag("mapzones_kind_rect"), enabled = ui.editingId == null)
        FilterChip(shape.kind == ShapeKind.CIRCLE, { on.setKind(ShapeKind.CIRCLE) }, { Text("Circle") }, Modifier.testTag("mapzones_kind_circle"), enabled = ui.editingId == null)
    }
    NoticeText(ShapeEdit.sizeText(shape), tag = "mapzones_size")
    PrimaryAction("Next", on.next, tag = "mapzones_next", enabled = ui.box != null)
    SecondaryAction("Cancel", on.cancelDraw, tag = "mapzones_cancel")
}

@Composable
private fun ChooseSheet(ui: MapZonesUiState, zones: ZonesUiState, on: MapZonesCallbacks) {
    Text("Use this area for", style = MaterialTheme.typography.titleMedium)
    ui.sheetError?.let { NoticeText(it, error = true, tag = "mapzones_sheet_error") }
    PrimaryAction("Pause capture here", { on.choose(SheetMode.PAUSE) }, tag = "mapzones_type_pause", enabled = !zones.protectedMode)
    if (zones.protectedMode) NoticeText("Pause is unavailable while the saved zones cannot be read.", error = true, tag = "mapzones_pause_disabled")
    PrimaryAction("Seed places for offline cards", { on.choose(SheetMode.SEED) }, tag = "mapzones_type_seed")
    if (ui.shape?.kind == ShapeKind.CIRCLE) NoticeText("A circle is saved as the box around it.", tag = "mapzones_circle_note")
    SecondaryAction("Back", on.backSheet, tag = "mapzones_back_sheet")
}

@Composable
private fun PauseSheet(ui: MapZonesUiState, on: MapZonesCallbacks) {
    Text(if (ui.editingId != null) "Change pause zone" else "Pause zone", style = MaterialTheme.typography.titleMedium)
    TextFieldRow("Name", ui.name, on.setName, tag = "zone_name")
    TextFieldRow("Ring (metres, 0 to 50,000)", ui.bufferText, on.setBuffer, tag = "zone_buffer", number = true)
    NoticeText("Capture pauses inside the box and the ring. Within 2 km more, the glasses hold capture if the link drops. If location is off or stale, capture pauses (fail closed)." + if (ui.shape?.kind == ShapeKind.CIRCLE) " A circle is saved as the box around it." else "", tag = "mapzones_pause_note")
    ui.sheetError?.let { NoticeText(it, error = true, tag = "mapzones_sheet_error") }
    PrimaryAction(if (ui.saving) "Saving..." else "Save pause zone", on.savePause, tag = "mapzones_save_pause", enabled = !ui.saving)
    SecondaryAction("Back", on.backSheet, tag = "mapzones_back_sheet")
}

@Composable
private fun SeedSheet(ui: MapZonesUiState, seed: SeedPanel?, level: SeedLevel, on: MapZonesCallbacks) {
    val box = SeedEstimate.boxOf(ui.box)
    Text("Seed places", style = MaterialTheme.typography.titleMedium)
    NoticeText(SeedEstimate.line(box), error = level == SeedLevel.TOO_LARGE, tag = "seed_estimate")
    NoticeText("Only the 5 km cell keys leave the phone, never your position. Seeding needs free Wi-Fi and is refused under Offline only. The seed area has no name yet.", tag = "seed_note")
    if (seed == null) NoticeText("Place Guide is not available in this build.", error = true, tag = "mapzones_seed_unavailable")
    PrimaryAction("Seed now", on.seedNow, tag = "seed_box", enabled = seed != null && !seed.ui.seeding && box != null && level != SeedLevel.TOO_LARGE && level != SeedLevel.EMPTY)
    SecondaryAction("Back", on.backSheet, tag = "mapzones_back_sheet")
}

@Composable
private fun BrowseSheet(
    view: MapViewState, ui: MapZonesUiState, zones: ZonesUiState, seed: SeedPanel?, offline: OfflineMapPanel?, online: OnlineMapPanel?, on: MapZonesCallbacks, zoneOn: ZonesCallbacks,
    layers: Set<MapLayer>, pauseLocked: Boolean, seedCount: Int, pinCount: Int,
) {
    var expanded by remember { mutableStateOf(false) }
    // The counts row is also the legend: each chip toggles its layer. Pause cannot be hidden while a zone is inside or near.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("mapzones_legend")) {
        FilterChip(MapLayer.PAUSE in layers, { on.toggleLayer(MapLayer.PAUSE) }, { Text("${zones.rows.size} pause") }, Modifier.testTag("mapzones_legend_pause"))
        FilterChip(MapLayer.SEED in layers, { on.toggleLayer(MapLayer.SEED) }, { Text("$seedCount seed") }, Modifier.testTag("mapzones_legend_seed"))
        FilterChip(MapLayer.PINS in layers, { on.toggleLayer(MapLayer.PINS) }, { Text("$pinCount pin" + if (pinCount == 1) "" else "s") }, Modifier.testTag("mapzones_legend_pins"))
    }
    if (pauseLocked) NoticeText("The pause layer stays on while you are in or near a zone.", tag = "mapzones_pause_locked")
    if (zones.you == null) NoticeText("Your position is not shown: there is no fresh location fix.", tag = "zone_no_fix")
    PrimaryAction("+ Draw area", { on.place(view.vp?.let { ShapeEdit.defaultShape(it, ui.kind) }) }, tag = "mapzones_draw")
    NoticeText("Or press and hold on the map, then drag to draw.", tag = "mapzones_draw_hint")
    SelectedCard(ui, zones, seed, on, zoneOn)
    TextButton({ expanded = !expanded }, Modifier.testTag("mapzones_expand")) { Text(if (expanded) "Hide the list" else "Show the list and map settings") }
    if (expanded) {
        Text("Your zones", style = MaterialTheme.typography.titleMedium)
        if (zones.rows.isEmpty()) NoticeText("No pause zones yet.", tag = "zone_none")
        zones.rows.forEach { r -> ZoneRow(r, zoneOn) }
        seed?.let { p -> p.ui.areas.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, Modifier.weight(1f).testTag("seed_area_${a.id}"), style = MaterialTheme.typography.bodyMedium)
                SecondaryAction("Remove", { p.askRemove(a.id) }, tag = "seed_remove_${a.id}")
            }
        } }
        online?.let { OnlineMapCard(it) }
        offline?.let { OfflineMapCard(it) }
    }
}

@Composable
private fun SelectedCard(ui: MapZonesUiState, zones: ZonesUiState, seed: SeedPanel?, on: MapZonesCallbacks, zoneOn: ZonesCallbacks) {
    val sel = ui.selected ?: return
    when (sel.kind) {
        SelectionKind.PAUSE -> {
            val r = zones.rows.firstOrNull { it.zone.id == sel.id } ?: return
            Text(r.zone.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("mapzones_sel_name"))
            StateChip(r.chip, Modifier.testTag("mapzones_sel_chip"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(r.zone.enabled, { zoneOn.setEnabled(r.zone.id, it) }, modifier = Modifier.testTag("zone_switch_${r.zone.id}"))
                SecondaryAction("Edit shape", { on.editPause(r.zone.id) }, tag = "mapzones_sel_edit", enabled = !zones.protectedMode)
                SecondaryAction("Delete", { zoneOn.askDelete(r.zone.id) }, tag = "zone_delete_${r.zone.id}")
            }
        }
        SelectionKind.SEED -> {
            val s = seed ?: return
            val a = s.ui.areas.firstOrNull { it.id == sel.id } ?: return
            Text(a.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("mapzones_sel_name"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryAction("Seed again", { s.seedBox(SeedEstimate.boxOf(a.box)) }, tag = "mapzones_sel_seed_again", enabled = !s.ui.seeding)
                SecondaryAction("Remove", { s.askRemove(a.id) }, tag = "seed_remove_${a.id}")
            }
        }
    }
    SecondaryAction("Close", { on.select(null) }, tag = "mapzones_sel_close")
}
