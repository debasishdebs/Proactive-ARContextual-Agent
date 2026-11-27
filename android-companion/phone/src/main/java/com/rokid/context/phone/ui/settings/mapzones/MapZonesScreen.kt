package com.rokid.context.phone.ui.settings.mapzones

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import com.rokid.context.phone.ui.settings.privacy.zones.SeedOverlay
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
import androidx.compose.runtime.DisposableEffect
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
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
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

/** The P2 and P3 tools (flag mapZonesP23, nested under mapZonesV2). null = all of them off, and the screen is exactly the P1 screen. */
class MapP23(
    val undo: () -> Unit, val redo: () -> Unit, val applyCoords: (CoordText) -> Unit,
    val alsoSeedDraft: () -> Unit, val alsoSeedStored: (String) -> Unit,
    val cells: CellCoverageState?, val toggleCells: (String, DoubleArray) -> Unit, val dismissCells: () -> Unit,
    val density: MomentDensityState?, val setDensity: (Boolean) -> Unit, val setWindow: (DateWindow) -> Unit, val refreshDensity: (MapViewport) -> Unit,
)

@Composable
fun MapZonesRoute(deps: SettingsDeps, back: () -> Unit, seed: SeedMapControl? = null, seedMode: Boolean = false, p23On: Boolean = false, density: MomentDensityControl? = null) {
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
    val networkOnline by remember(deps) { deps.networkOnline() }.collectAsStateWithLifecycle(true)
    val offline = deps.offlineMap?.let { m -> remember { m() } }
    var keyEpoch by remember { mutableStateOf(0) } // a saved or cleared map key rebuilds the street-map wiring
    val onlineDeps = com.rokid.context.phone.ui.settings.privacy.zones.online.rememberOnlineDeps(deps.onlineMap, keyEpoch) // built on IO
    val online = onlineDeps.deps?.let { d -> rememberOnlinePanel(d, ::granted, zonesVm::requestLocation) { keyEpoch++ } }
    // p23On is the nested Developer flag mapZonesP23 (default OFF), read by the destination; density is the production control.
    LaunchedEffect(p23On, zones.rows, zones.protectedMode) {
        mapVm.setEditGuard(if (p23On) zones.rows.associate { it.zone.id to ZoneEditPolicy.reshapeBlock(it.chip.level, it.inside, zones.protectedMode) } else null)
    }
    val seedVm = seed?.let { c -> viewModel(key = "seed_map") { SeedMapViewModel(c) } }
    val seedState = seedVm?.state?.collectAsStateWithLifecycle()?.value
    if (seedVm != null) {
        LaunchedEffect(seedVm) { seedVm.refresh() }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { seedVm.refresh() }
        LaunchedEffect(seedState?.seeding) { if (seedState?.seeding == true) mapVm.cancelDraw() } // the sheet closes once the seed has really started
        LaunchedEffect(seedState?.clearDraftSeq) { if ((seedState?.clearDraftSeq ?: 0) > 0) mapVm.cancelDraw() } // a seed was queued, started or retried: no draft box stays over the area (a fast seed never shows `seeding`)
        DisposableEffect(mapVm) { onDispose { mapVm.cancelDraw() } }
        LaunchedEffect(seedState?.editSeq) { if ((seedState?.editSeq ?: 0) > 0) mapVm.editSeedBox(seedState?.editBox) } // "Edit box": the area's rectangle becomes the draft
    }
    val seedPanel = if (seedVm != null && seedState != null) SeedPanel(seedState, seedMode, seedVm::requestSeed, seedVm::confirmSeed, seedVm::cancelConfirm, seedVm::askRemove, seedVm::confirmRemove, seedVm::cancelRemove, seedVm::dismissMessage, seedVm::cancelSeed, seedVm::setRemoveWithPlaces, seedVm::rename, seedVm::retryFailed, seedVm::focusArea, seedVm::clearFocus, seedVm::setColour, seedVm::requestSeedAgain, seedVm::editArea, seedVm::endEdit, seedVm::viewportSettled, seedVm::setShowPlaces, seedVm::tapDot, retryPhotos = seedVm::retryPhotos) else null
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
    val p23 = if (!p23On || density == null) null else {
        val cellsVm = seed?.let { c -> viewModel(key = "map_cells") { CellCoverageViewModel(c) } }
        val cellsUi = cellsVm?.state?.collectAsStateWithLifecycle()?.value
        val densVm = viewModel(key = "map_density") { MomentDensityViewModel(density) }
        val densUi by densVm.state.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { view.vp?.let { densVm.refresh(it) } } // every visit asks again, so a Forget shows at once
        androidx.compose.runtime.DisposableEffect(densVm) { onDispose { densVm.clear() } }
        MapP23(mapVm::undo, mapVm::redo, mapVm::applyCoordinates, mapVm::alsoSeedFromDraft, mapVm::alsoSeedFromStored, cellsUi, { id, b -> cellsVm?.toggle(id, b) }, { cellsVm?.dismissMessage() }, densUi, densVm::setOn, densVm::setWindow, densVm::onViewChanged)
    }
    val cb = MapZonesCallbacks(
        back, mapVm::beginDraw, { mapVm.cancelDraw(); seedVm?.endEdit() }, mapVm::setKind, mapVm::place, mapVm::reshape, mapVm::next, mapVm::choose, mapVm::back, mapVm::setName, mapVm::setBuffer, mapVm::savePause,
        { seedPanel?.seedBox?.invoke(mapVm.seedBox()) }, { l -> mapVm.toggleLayer(l, zones.rows.any { it.chip.level == ChipLevel.PAUSED || it.chip.level == ChipLevel.WARN }) },
        { t -> onTap(t, view, zones, seedPanel, mapVm) }, mapVm::select, mapVm::editPause, mapVm::dismissMessage,
    )
    MapZonesScreen(ui, zones, seedPanel, offlinePanel, online, cb, zoneCb, view, mapVm.seedLevel(), p23, networkOnline)
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
    on: MapZonesCallbacks, zoneOn: ZonesCallbacks, view: MapViewState, seedLevel: SeedLevel, p23: MapP23? = null, networkOnline: Boolean = true,
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
    // P2: while a shape is being drawn, its drag snaps to the edges of the other zones and seed areas. P3: the density blobs follow the view (debounced); both are off with the flag.
    val snapTargets = if (p23 != null && ui.shape != null) items.filter { it.type != ItemType.PIN && it.id != ui.editingId }.map { it.box } else null
    val dens = p23?.density
    val vpNow = view.vp
    // Seeded places as dots (the seed layer's switch and the "Show seeded places" switch): read once the view settled, coloured by the area that holds them.
    val paintedDots = remember(seed?.ui?.dots, seedAreas, seed?.ui?.showPlaces, MapLayer.SEED in layers) { if (seed != null && seed.ui.showPlaces && MapLayer.SEED in layers) com.rokid.context.phone.ui.settings.privacy.zones.SeedDots.paint(seed.ui.dots, seedAreas) else emptyList() }
    if (seed != null) LaunchedEffect(vpNow?.centerLat, vpNow?.centerLon, vpNow?.ppd, view.size) { seed.viewportSettled(com.rokid.context.phone.ui.settings.privacy.zones.SeedDots.visibleBox(view.vp, view.size.width, view.size.height)) }
    if (p23 != null && dens?.on == true && vpNow != null) LaunchedEffect(vpNow.centerLat, vpNow.centerLon, vpNow.ppd, dens.window, dens.on) { p23.refreshDensity(vpNow) }
    // While a pause save is in flight the screen cannot be left: its answer (a refusal especially) must reach the sheet it was asked from.
    androidx.activity.compose.BackHandler(enabled = ui.saving) { }
    Box(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).testTag("mapzones_screen")) {
        MapCanvas(
            view, zones.rows, seedAreas, pins, items, layers, ui.shape, ui.selected, zones.you, fitBoxes, ui.kind, canDraw = ui.sheet == SheetMode.NONE && networkOnline, blank = !networkOnline,
            summary = MapSpeech.describe(MapOverview.summary(zones.rows.size, seedAreas.size, pins.size, inside), ui.shape, ShapeSteps.position(items, ui.selected, layers)), reader = reader, online = online,
            onNewShape = on.place, onReshape = on.reshape, onTap = on.tap, modifier = Modifier.fillMaxSize(),
            access = MapAccessActions(
                move = { d -> ui.shape?.let { on.reshape(ShapeSteps.move(it, d)) } },
                resize = { g -> ui.shape?.let { on.reshape(ShapeSteps.resize(it, g)) } },
                selectNext = { ShapeSteps.nextSelection(items, ui.selected, layers)?.let { on.select(it) } },
            ),
            snapTargets = snapTargets, doubleTapZoom = p23 != null, density = if (dens?.on == true) dens.blobs else emptyList(), cells = p23?.cells?.coverage, edgeHandles = ui.editingId == null,
            dots = paintedDots, selectedDot = seed?.ui?.dotSelectedId, onDot = seed?.tapDot ?: {},
        )
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(PcColors.SurfaceSoft.copy(alpha = 0.9f)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(on.back, Modifier.testTag("mapzones_back"), enabled = !ui.saving) { Text("Back") }
            Text("Map zones", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton({ view.zoom(1.6) }, Modifier.testTag("zone_zoom_in")) { Text("+") }
            TextButton({ view.zoom(1 / 1.6) }, Modifier.testTag("zone_zoom_out")) { Text("-") }
            TextButton({ view.fit() }, Modifier.testTag("zone_fit")) { Text("Fit") }
            if (online?.canCentre == true) TextButton(online.centreOnMe, Modifier.testTag("zone_centre"), enabled = !online.centreBusy) { Text(if (online.centreBusy) com.rokid.context.phone.ui.settings.privacy.zones.tiles.CentreWords.FINDING else "Me") }
            if (online?.centreBusy == true) TextButton(online.centreCancel, Modifier.testTag("zone_centre_cancel")) { Text("Cancel") }
        }
        seed?.ui?.dotLabel?.let { l -> Text(l.text, style = MaterialTheme.typography.labelMedium, color = PcColors.Ink, modifier = Modifier.align(Alignment.TopCenter).padding(top = 52.dp).background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp).testTag("seed_dot_label")) }
        if (!networkOnline) com.rokid.context.phone.ui.settings.privacy.zones.NoNetworkBanner(tag = "mapzones_no_network", modifier = Modifier.align(Alignment.TopStart).padding(top = 48.dp))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(PcColors.SurfaceSoft).verticalScroll(rememberScrollState()).padding(16.dp).testTag("mapzones_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (zones.protectedMode) NoticeText(com.rokid.context.phone.ui.settings.privacy.zones.ZoneWords.PROTECTED_BANNER, error = true, tag = "zone_protected")
            online?.note?.let { n -> NoticeText(n, tag = "zone_centre_note"); SecondaryAction("Dismiss", online.dismissNote, tag = "zone_centre_note_dismiss") }
            if (zones.deniedBanner) { NoticeText(com.rokid.context.phone.ui.settings.privacy.zones.ZoneWords.DENIED_BANNER, error = true, tag = "zone_denied"); PrimaryAction("Allow location", zoneOn.requestLocation, tag = "zone_allow_location") }
            ui.message?.let { m -> NoticeText(m, error = ui.messageIsError, tag = "zone_message"); SecondaryAction("Dismiss", on.dismiss, tag = "zone_message_dismiss") }
            zones.message?.let { m -> NoticeText(m, error = zones.messageIsError, tag = "zone_message2"); SecondaryAction("Dismiss", zoneOn.dismissMessage, tag = "zone_message2_dismiss") }
            seed?.ui?.message?.let { m -> NoticeText(m, error = seed.ui.messageIsError, tag = "seed_message"); SecondaryAction("Dismiss", seed.dismiss, tag = "seed_message_dismiss") }
            seed?.let { com.rokid.context.phone.ui.settings.privacy.zones.SeedProgressRows(it) }
            when {
                ui.sheet == SheetMode.CHOOSE -> ChooseSheet(ui, zones, on)
                ui.sheet == SheetMode.PAUSE -> PauseSheet(ui, zones.protectedMode, on, p23, ZoneOverlap.of(ui.box, items.filter { it.type == ItemType.PAUSE }, emptyList(), ui.editingId), zones.rows.firstOrNull { it.zone.id == ui.editingId }?.zone?.let { doubleArrayOf(it.south, it.west, it.north, it.east) })
                ui.sheet == SheetMode.SEED -> SeedSheet(ui, seed, seedLevel, on, if (p23 != null) ZoneOverlap.seedNote(ZoneOverlap.of(ui.box, items.filter { it.type == ItemType.PAUSE }, emptyList())) else null)
                ui.shape != null -> DraftBar(ui, on, p23)
                else -> BrowseSheet(view, ui, zones, seed, offline, online, on, zoneOn, layers, pauseLocked, seedAreas.size, pins.size, p23)
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
    seed?.ui?.pendingRemoveId?.let { com.rokid.context.phone.ui.settings.privacy.zones.SeedRemoveDialog(seed) }
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
private fun DraftBar(ui: MapZonesUiState, on: MapZonesCallbacks, p23: MapP23?) {
    val shape = ui.shape ?: return
    Text(if (ui.editingId != null) "Reshape the zone" else "Drag the handles to size it, drag inside to move it", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(shape.kind == ShapeKind.RECT, { on.setKind(ShapeKind.RECT) }, { Text("Rectangle") }, Modifier.testTag("mapzones_kind_rect"), enabled = ui.editingId == null)
        FilterChip(shape.kind == ShapeKind.CIRCLE, { on.setKind(ShapeKind.CIRCLE) }, { Text("Circle") }, Modifier.testTag("mapzones_kind_circle"), enabled = ui.editingId == null)
    }
    NoticeText(ShapeEdit.sizeText(shape), tag = "mapzones_size")
    // The live cell count of the box (it follows every drag of a handle): what seeding it would cover.
    if (ui.editingId == null) SeedEstimate.boxOf(ui.box).let { NoticeText(SeedEstimate.line(it), tag = "mapzones_cells_live") }
    if (p23 != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryAction("Undo", p23.undo, tag = "mapzones_undo", enabled = ui.history.canUndo && !ui.saving)
            SecondaryAction("Redo", p23.redo, tag = "mapzones_redo", enabled = ui.history.canRedo && !ui.saving)
        }
        CoordinateEditor(ui, shape, p23)
    }
    PrimaryAction("Next", on.next, tag = "mapzones_next", enabled = ui.box != null)
    SecondaryAction(if (ui.editingId == null) "Clear box" else "Cancel", on.cancelDraw, tag = "mapzones_cancel", enabled = !ui.saving)
}

@Composable
private fun CoordinateEditor(ui: MapZonesUiState, shape: DrawnShape, p23: MapP23) {
    var open by remember { mutableStateOf(false) }
    TextButton({ open = !open }, Modifier.testTag("mapzones_coords_toggle")) { Text(EditWords.COORDS) }
    if (!open) return
    val t = remember(shape) { CoordinateFields.textOf(shape) } // refreshed whenever the shape changes
    var a by remember(t) { mutableStateOf(t.a) }; var b by remember(t) { mutableStateOf(t.b) }; var c by remember(t) { mutableStateOf(t.c) }; var d by remember(t) { mutableStateOf(t.d) }
    val circle = shape is DrawnShape.Circle
    TextFieldRow(if (circle) "Centre latitude" else "South", a, { a = it }, tag = "mapzones_coord_a", number = true)
    TextFieldRow(if (circle) "Centre longitude" else "West", b, { b = it }, tag = "mapzones_coord_b", number = true)
    TextFieldRow(if (circle) "Radius (metres)" else "North", c, { c = it }, tag = "mapzones_coord_c", number = true)
    if (!circle) TextFieldRow("East", d, { d = it }, tag = "mapzones_coord_d", number = true)
    ui.coordError?.let { NoticeText(it, error = true, tag = "mapzones_coord_error") }
    SecondaryAction("Apply", { p23.applyCoords(CoordText(a, b, c, d)) }, tag = "mapzones_coord_apply", enabled = !ui.saving)
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
private fun PauseSheet(ui: MapZonesUiState, unreadable: Boolean, on: MapZonesCallbacks, p23: MapP23?, overlap: Overlap, oldBox: DoubleArray?) {
    Text(if (ui.editingId != null) "Change pause zone" else "Pause zone", style = MaterialTheme.typography.titleMedium)
    TextFieldRow("Name", ui.name, on.setName, tag = "zone_name")
    TextFieldRow("Ring (metres, 0 to 50,000)", ui.bufferText, on.setBuffer, tag = "zone_buffer", number = true)
    NoticeText("Capture pauses inside the box and the ring. Within 2 km more, the glasses hold capture if the link drops. If location is off or stale, capture pauses (fail closed)." + if (ui.shape?.kind == ShapeKind.CIRCLE) " A circle is saved as the box around it." else "", tag = "mapzones_pause_note")
    ui.sheetError?.let { NoticeText(it, error = true, tag = "mapzones_sheet_error") }
    if (p23 != null) {
        ZoneOverlap.pauseNote(overlap)?.let { NoticeText(it, tag = "mapzones_overlap_note") }
        val blocked = ui.editingId?.let { id -> ui.editGuard?.let { g -> g[id] ?: ZoneEditPolicy.Block.INSIDE_OR_UNKNOWN } }?.takeIf { it != ZoneEditPolicy.Block.NONE }
        blocked?.let { b -> ZoneEditPolicy.words(b)?.let { NoticeText(it, error = true, tag = "mapzones_edit_blocked") } }
        val nb = ui.box
        if (ui.editingId != null && oldBox != null && nb != null && ZoneEditPolicy.shrinks(oldBox, nb)) NoticeText(ZoneEditPolicy.SHRINK_NOTICE, tag = "mapzones_shrink_note")
        SecondaryAction(EditWords.ALSO_SEED, p23.alsoSeedDraft, tag = "mapzones_also_seed", enabled = !ui.saving && ui.box != null)
    }
    if (unreadable) NoticeText("Pause cannot be saved while the saved zones cannot be read.", error = true, tag = "mapzones_save_disabled")
    PrimaryAction(if (ui.saving) "Saving..." else "Save pause zone", on.savePause, tag = "mapzones_save_pause", enabled = PauseSave.enabled(ui.saving, unreadable) && !(p23 != null && ui.editingId != null && (ui.editGuard?.get(ui.editingId) ?: ZoneEditPolicy.Block.INSIDE_OR_UNKNOWN) != ZoneEditPolicy.Block.NONE))
    SecondaryAction("Back", on.backSheet, tag = "mapzones_back_sheet", enabled = !ui.saving)
}

@Composable
private fun SeedSheet(ui: MapZonesUiState, seed: SeedPanel?, level: SeedLevel, on: MapZonesCallbacks, overlapNote: String? = null) {
    val box = SeedEstimate.boxOf(ui.box)
    val editing = seed?.ui?.editingId != null
    Text(if (editing) "Edit the seeded area's box" else "Seed places", style = MaterialTheme.typography.titleMedium)
    if (editing) NoticeText(com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaList.EDIT_NOTE, tag = "seed_edit_note")
    NoticeText(SeedEstimate.estimate(box, seed?.ui?.rates ?: com.rokid.context.phone.ui.settings.privacy.zones.SeedRates.ROUGH), error = level == SeedLevel.TOO_LARGE, tag = "seed_estimate")
    NoticeText(com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaList.ATTRIBUTION, tag = "seed_attribution")
    NoticeText("Only the 5 km cell keys leave the phone, never your position. Seeding runs in the background and needs a network (free Wi-Fi only when your Data saver says Wi-Fi only); it is refused under Offline only. The seed area has no name yet.", tag = "seed_note")
    overlapNote?.let { NoticeText(it, tag = "mapzones_seed_overlap_note") }
    if (seed == null) NoticeText("Place Guide is not available in this build.", error = true, tag = "mapzones_seed_unavailable")
    PrimaryAction(if (editing) "Seed this box" else "Seed now", on.seedNow, tag = "seed_box", enabled = seed != null && !seed.ui.seeding && box != null && level != SeedLevel.TOO_LARGE && level != SeedLevel.EMPTY)
    SecondaryAction("Back", on.backSheet, tag = "mapzones_back_sheet")
    SecondaryAction("Clear box", on.cancelDraw, tag = "mapzones_seed_clear", enabled = !ui.saving)
}

@Composable
private fun BrowseSheet(
    view: MapViewState, ui: MapZonesUiState, zones: ZonesUiState, seed: SeedPanel?, offline: OfflineMapPanel?, online: OnlineMapPanel?, on: MapZonesCallbacks, zoneOn: ZonesCallbacks,
    layers: Set<MapLayer>, pauseLocked: Boolean, seedCount: Int, pinCount: Int, p23: MapP23? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // The counts row is also the legend: each chip toggles its layer. Pause cannot be hidden while a zone is inside or near.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("mapzones_legend")) {
        FilterChip(MapLayer.PAUSE in layers, { on.toggleLayer(MapLayer.PAUSE) }, { Text("${zones.rows.size} pause") }, Modifier.testTag("mapzones_legend_pause"))
        FilterChip(MapLayer.SEED in layers, { on.toggleLayer(MapLayer.SEED) }, { Text("$seedCount seed") }, Modifier.testTag("mapzones_legend_seed"))
        FilterChip(MapLayer.PINS in layers, { on.toggleLayer(MapLayer.PINS) }, { Text("$pinCount pin" + if (pinCount == 1) "" else "s") }, Modifier.testTag("mapzones_legend_pins"))
    }
    seed?.let { com.rokid.context.phone.ui.settings.privacy.zones.SeedDotsSwitch(it) }
    p23?.density?.let { d -> DensityControls(d, p23) }
    if (pauseLocked) NoticeText("The pause layer stays on while you are in or near a zone.", tag = "mapzones_pause_locked")
    if (zones.you == null) NoticeText("Your position is not shown: there is no fresh location fix.", tag = "zone_no_fix")
    PrimaryAction("+ Draw area", { on.place(view.vp?.let { ShapeEdit.defaultShape(it, ui.kind) }) }, tag = "mapzones_draw")
    NoticeText("Or press and hold on the map, then drag to draw.", tag = "mapzones_draw_hint")
    SelectedCard(ui, zones, seed, on, zoneOn, p23)
    p23?.cells?.message?.let { NoticeText(it, tag = "mapzones_cells_message"); SecondaryAction("Dismiss", p23.dismissCells, tag = "mapzones_cells_message_dismiss") }
    TextButton({ expanded = !expanded }, Modifier.testTag("mapzones_expand")) { Text(if (expanded) "Hide the list" else "Show the list and map settings") }
    if (expanded) {
        Text("Your zones", style = MaterialTheme.typography.titleMedium)
        if (zones.rows.isEmpty()) NoticeText("No pause zones yet.", tag = "zone_none")
        zones.rows.forEach { r -> ZoneRow(r, zoneOn) }
        seed?.let { p -> if (p.ui.areas.isNotEmpty()) com.rokid.context.phone.ui.settings.privacy.zones.SeededAreasContent(p) { a -> focusOn(view, a.box); on.select(Selection(SelectionKind.SEED, a.id)) } }
        online?.let { OnlineMapCard(it) }
        offline?.let { OfflineMapCard(it) }
    }
}

@Composable
private fun DensityControls(d: MomentDensityState, p23: MapP23) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(EditWords.DENSITY, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(d.on, p23.setDensity, modifier = Modifier.testTag("mapzones_density_switch"))
    }
    if (!d.on) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(d.window == DateWindow.LAST_7_DAYS, { p23.setWindow(DateWindow.LAST_7_DAYS) }, { Text("7 days") }, Modifier.testTag("mapzones_density_7"))
        FilterChip(d.window == DateWindow.LAST_30_DAYS, { p23.setWindow(DateWindow.LAST_30_DAYS) }, { Text("30 days") }, Modifier.testTag("mapzones_density_30"))
        FilterChip(d.window == DateWindow.ALL, { p23.setWindow(DateWindow.ALL) }, { Text("All") }, Modifier.testTag("mapzones_density_all"))
    }
    if (d.summary.isNotEmpty()) NoticeText(d.summary, tag = "mapzones_density_summary")
    if (d.capped) NoticeText(MomentDensity.CAPPED, tag = "mapzones_density_capped")
}

@Composable
private fun SelectedCard(ui: MapZonesUiState, zones: ZonesUiState, seed: SeedPanel?, on: MapZonesCallbacks, zoneOn: ZonesCallbacks, p23: MapP23? = null) {
    val sel = ui.selected ?: return
    when (sel.kind) {
        SelectionKind.PAUSE -> {
            val r = zones.rows.firstOrNull { it.zone.id == sel.id } ?: return
            Text(r.zone.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("mapzones_sel_name"))
            StateChip(r.chip, Modifier.testTag("mapzones_sel_chip"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(r.zone.enabled, { zoneOn.setEnabled(r.zone.id, it) }, modifier = Modifier.testTag("zone_switch_${r.zone.id}"))
                SecondaryAction("Edit shape", { on.editPause(r.zone.id) }, tag = "mapzones_sel_edit", enabled = !zones.protectedMode && (p23 == null || ZoneEditPolicy.reshapeBlock(r.chip.level, r.inside, zones.protectedMode) == ZoneEditPolicy.Block.NONE))
                SecondaryAction("Delete", { zoneOn.askDelete(r.zone.id) }, tag = "zone_delete_${r.zone.id}")
            }
            if (p23 != null) {
                ZoneEditPolicy.words(ZoneEditPolicy.reshapeBlock(r.chip.level, r.inside, zones.protectedMode))?.let { NoticeText(it, tag = "mapzones_sel_edit_blocked") }
                SecondaryAction(EditWords.ALSO_SEED, { p23.alsoSeedStored(r.zone.id) }, tag = "mapzones_sel_also_seed")
            }
        }
        SelectionKind.SEED -> {
            val s = seed ?: return
            val a = s.ui.areas.firstOrNull { it.id == sel.id } ?: return
            Text(a.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("mapzones_sel_name"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryAction("Seed again", { s.seedAgain(SeedEstimate.boxOf(a.box)) }, tag = "mapzones_sel_seed_again", enabled = !s.ui.seeding)
                SecondaryAction("Edit box", { s.editArea(a.id) }, tag = "mapzones_sel_edit_box", enabled = !s.ui.seeding && a.look != com.rokid.context.phone.ui.settings.privacy.zones.AreaLook.SEEDING)
                if (a.failedCells > 0) SecondaryAction("Retry failed cells (${a.failedCells})", { s.retryFailed(a.id) }, tag = "mapzones_sel_retry", enabled = !s.ui.seeding)
                SecondaryAction("Remove", { s.askRemove(a.id) }, tag = "seed_remove_${a.id}")
            }
            if (p23 != null) {
                val shown = p23.cells?.areaId == a.id
                SecondaryAction(if (shown) EditWords.HIDE_CELLS else EditWords.SHOW_CELLS, { p23.toggleCells(a.id, a.box) }, tag = "mapzones_sel_cells")
                p23.cells?.coverage?.takeIf { shown }?.let { NoticeText(it.summary + ". Blue: ready. Amber: partial. Red dashed: not fetched.", tag = "mapzones_cells_summary") }
            }
        }
    }
    SecondaryAction("Close", { on.select(null) }, tag = "mapzones_sel_close")
}

/**
 * One seeded-areas list row (S4): tap the text to focus the map on the area (centre and fit its box; the map section works with no network, a blank grid then), Rename (label only),
 * Seed again (the pack skips cells that are fresh) and Remove. No coordinates in any text.
 */
@Composable
internal fun SeedAreaListRow(view: MapViewState, p: SeedPanel, a: SeedOverlay, select: (Selection) -> Unit) =
    com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaRowContent(p, a) { focusOn(view, a.box); select(Selection(SelectionKind.SEED, a.id)) }

/** Centres the map on [box] and fits it (works with no network: the grid is drawn from the phone's own data). */
internal fun focusOn(view: MapViewState, box: DoubleArray) {
    val s = view.size
    if (s.width >= 10f && s.height >= 10f) view.vp = MapViewport.fit(listOf(box), s.width, s.height)
}
