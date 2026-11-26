package com.rokid.context.phone.ui.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.list.ListItemCard
import com.rokid.context.phone.ui.settings.mapzones.DoubleTap
import com.rokid.context.phone.ui.settings.mapzones.MomentDensity
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import com.rokid.context.phone.ui.settings.privacy.zones.OnlineMapPanel
import com.rokid.context.phone.ui.settings.privacy.zones.decodeTileBounded
import com.rokid.context.phone.ui.settings.privacy.zones.drawGrid
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapDeps
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.TileCache
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.TileMath
import com.rokid.context.phone.ui.settings.privacy.zones.rememberOnlinePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val MapBackground = Color(0xFFF3F1EA)
private const val ONLINE_PARALLEL = 4
private const val MAX_ROWS_SHOWN = 50

/**
 * The Moments heat-map of the Activity page: a thin shell over [MomentHeat]. Street tiles only when a map key exists AND the street-map switch (Pause zones) is on, else the plain grid and a notice and no network.
 * Pinch, drag and double-tap zoom like the zones map. Tapping a hot cell lists its Moments; a tap on a row opens the usual preview. Positions are drawn only; nothing is logged.
 */
@Composable
fun ActivityMapPanel(vm: ActivityMapViewModel, state: ActivityUiState, zone: java.time.ZoneId, onlineMap: (() -> OnlineMapDeps)?, onPreview: (List<String>) -> Unit, network: kotlinx.coroutines.flow.Flow<Boolean>? = null, lastFix: (() -> com.rokid.context.core.model.zone.ZoneFix?)? = null, fixNow: com.rokid.context.phone.ui.settings.privacy.zones.tiles.CentreFix? = null, locationGranted: () -> Boolean = { true }, askLocation: () -> Unit = {}) {
    val networkOnline by remember(network) { network ?: kotlinx.coroutines.flow.flowOf(true) }.collectAsStateWithLifecycle(true)
    val map by vm.state.collectAsStateWithLifecycle()
    val range = MomentHeat.range(state, zone)
    LaunchedEffect(range, state.granularity) { vm.load(range, state.granularity) }
    val onlineDeps = com.rokid.context.phone.ui.settings.privacy.zones.online.rememberOnlineDeps(onlineMap) // built on IO (it reads the encrypted key vault)
    val online: OnlineMapPanel? = onlineDeps.deps?.let { d -> rememberOnlinePanel(d, { false }, {}) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {}
    ActivityHeatMap(map, state.granularity, online, vm, zone, onlineLoading = onlineDeps.loading, onPreview = onPreview, networkOnline = networkOnline, lastFix = lastFix,
        fixNow = onlineDeps.deps?.fix, locationGranted = { com.rokid.context.phone.ui.settings.privacy.zones.LOCATION_PERMISSIONS.any { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED } },
        askLocation = { ask.launch(com.rokid.context.phone.ui.settings.privacy.zones.LOCATION_PERMISSIONS) })
}

@Composable
internal fun ActivityHeatMap(map: ActivityMapState, g: Granularity, online: OnlineMapPanel?, vm: ActivityMapViewModel?, zone: java.time.ZoneId, onlineLoading: Boolean = false, onPreview: (List<String>) -> Unit, networkOnline: Boolean = true, lastFix: (() -> com.rokid.context.core.model.zone.ZoneFix?)? = null, elapsed: () -> Long = android.os.SystemClock::elapsedRealtime, fixNow: com.rokid.context.phone.ui.settings.privacy.zones.tiles.CentreFix? = null, locationGranted: () -> Boolean = { true }, askLocation: () -> Unit = {}) {
    val measurer = rememberTextMeasurer()
    var size by remember { mutableStateOf(Size.Zero) }
    var vp by remember { mutableStateOf<MapViewport?>(null) }
    var fitKey by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<HeatCell?>(null) }
    var you by remember { mutableStateOf<com.rokid.context.phone.ui.settings.privacy.zones.LatLon?>(null) } // screen state only: never stored, logged or sent
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val grantedNow by androidx.compose.runtime.rememberUpdatedState(locationGranted); val askNow by androidx.compose.runtime.rememberUpdatedState(askLocation)
    val lastFixNow by androidx.compose.runtime.rememberUpdatedState(lastFix); val elapsedNow by androidx.compose.runtime.rememberUpdatedState(elapsed)
    // Centre on me (design S2): a fresh fix the zone guard already has is used at once; else ask the permission on the tap and take ONE fix (GPS up to 20 s offline). Screen state only.
    val centre = remember(fixNow) { com.rokid.context.phone.ui.settings.privacy.zones.tiles.CentreFlow(scope, fixNow, { grantedNow() }, { askNow() }, { MomentHeat.centreOn(lastFixNow?.invoke(), elapsedNow()) }, MomentHeat.NO_FIX) }
    val centreUi by centre.state.collectAsStateWithLifecycle()
    val fixNote = centreUi.note
    val notice = if (!networkOnline) null /* the red banner below carries the offline wording: said once */ else if (onlineLoading) null else MomentHeat.tileNotice(online?.ui?.available == true, online?.ui?.enabled == true) // no "add a key" notice while the key is still being read
    LaunchedEffect(centreUi.request) {
        val at = centreUi.request?.at ?: return@LaunchedEffect
        you = at
        if (size.width >= 10f && size.height >= 10f) vp = MapViewport.fit(listOf(MapViewport.grown(at.lat, at.lon, at.lat, at.lon, MomentHeat.CENTRE_HALF_SPAN_M)), size.width, size.height)
    }
    LaunchedEffect(map.points) { picked = null } // review2 #6: a new set of points (a reload after a deletion) never keeps a cell of the old one selected
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (!networkOnline) com.rokid.context.phone.ui.settings.privacy.zones.NoNetworkBanner(tag = "activity_map_no_network", modifier = Modifier.padding(bottom = 6.dp), text = MomentHeat.NO_NETWORK) // this screen has no pause zones or seeding: only the plain grid and the phone-held Moments apply
        if (map.loaded && map.points.isEmpty()) {
            Text(MomentHeat.EMPTY, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(vertical = 12.dp).testTag("map_empty"))
            return@Column
        }
        LaunchedEffect(size, map.points, fitKey) {
            if (size.width < 10f || size.height < 10f || map.points.isEmpty()) return@LaunchedEffect
            vp = MomentHeat.fit(map.points, size.width, size.height); picked = null
        }
        val cells = vp?.let { v -> MomentHeat.cells(map.points, v.ppd) }.orEmpty()
        val tiles = online?.source?.takeIf { networkOnline } // no network: the plain grid only, cached tiles are not painted
        val cache = remember(tiles) { tiles?.let { t -> TileCache<ImageBitmap>(weigh = { it.width.toLong() * it.height * 4 }, rememberAbsent = false) { k -> t.tile(k)?.let { b -> decodeTileBounded(b) } } } }
        var loaded by remember(cache) { mutableStateOf(0) }
        val zoom = vp?.let { v -> tiles?.let { TileMath.zoomFor(v.ppd, it.minZoom, it.maxZoom) } }
        val wanted = if (vp != null && zoom != null) TileMath.visible(vp!!, zoom) else emptyList()
        LaunchedEffect(cache, wanted, online?.foreground) {
            val c = cache ?: return@LaunchedEffect
            if (wanted.isEmpty() || online?.foreground != true) return@LaunchedEffect
            withContext(Dispatchers.IO) { for (chunk in wanted.chunked(ONLINE_PARALLEL)) coroutineScope { chunk.map { k -> async { if (c.ensure(listOf(k))) loaded++ } }.awaitAll() } }
        }
        val tilesShown = tiles != null && cache != null && loaded >= 0 && wanted.any { cache.peek(it) != null }
        val summary = MomentDensity.summary(map.points.size, map.points.minOfOrNull { it.atMs }, map.points.maxOfOrNull { it.atMs }, zone).let { if (g == Granularity.SCENES) it.replace("Moment", "Scene") else it }
        Box(
            Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, PcColors.Line, RoundedCornerShape(12.dp))
                .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()); vp = vp?.resized(size.width, size.height) }
                .semantics { contentDescription = "Map of $summary" }.testTag("activity_map"),
        ) {
            Canvas(
                Modifier.fillMaxWidth().height(340.dp).background(if (networkOnline) MapBackground else Color.White)
                    .pointerInput(Unit) { detectTransformGestures { centroid, pan, zoomBy, _ -> vp = vp?.panned(pan.x, pan.y)?.zoomed(zoomBy.toDouble(), centroid.x, centroid.y) } }
                    .pointerInput(Unit) {
                        val touch = 48.dp.toPx()
                        detectTapGestures(
                            onDoubleTap = { p -> vp = vp?.zoomed(DoubleTap.FACTOR, p.x, p.y) },
                            onTap = { p -> picked = vp?.let { v -> MomentHeat.cellAt(MomentHeat.cells(map.points, v.ppd), v, p.x, p.y, touch) } },
                        )
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
                you?.let { y -> val p = v.toPx(y.lat, y.lon); if (p.x.isFinite() && p.y.isFinite()) { drawCircle(Color.White, 9.dp.toPx(), Offset(p.x, p.y)); drawCircle(PcColors.Sky, 6.dp.toPx(), Offset(p.x, p.y)) } }
                for (c in cells) {
                    val p = v.toPx(c.lat, c.lon)
                    if (!(p.x.isFinite() && p.y.isFinite()) || p.x < -80f || p.y < -80f || p.x > this.size.width + 80f || p.y > this.size.height + 80f) continue
                    val col = Color(MomentDensity.colorOf(c.level)); val rad = (20f + 24f * c.level).dp.toPx()
                    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.7f), col.copy(alpha = 0f)), Offset(p.x, p.y), rad), rad, Offset(p.x, p.y))
                }
            }
            if (tilesShown && tiles != null) Row(Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp).testTag("activity_map_attribution"), verticalAlignment = Alignment.CenterVertically) {
                tiles.logoText?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = PcColors.InkSoft, modifier = Modifier.padding(end = 4.dp)) }
                tiles.attribution?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft) }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                TextButton({ vp = vp?.zoomed(1.6) }, Modifier.testTag("activity_map_zoom_in")) { Text("+") }
                TextButton({ vp = vp?.zoomed(1 / 1.6) }, Modifier.testTag("activity_map_zoom_out")) { Text("-") }
                TextButton({ fitKey++ }, Modifier.testTag("activity_map_fit")) { Text("Fit") }
                if (centreUi.finding) TextButton(centre::cancel, Modifier.testTag("activity_map_centre_cancel")) { Text("Cancel") }
                if (lastFix != null || fixNow != null) TextButton(centre::tap, Modifier.testTag("activity_map_centre"), enabled = !centreUi.finding) { Text(if (centreUi.finding) com.rokid.context.phone.ui.settings.privacy.zones.tiles.CentreWords.FINDING else "Me") }

            }
        }
        Text(summary + if (map.capped) "\n" + MomentHeat.CAPPED else "", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft, modifier = Modifier.padding(top = 6.dp).testTag("activity_map_summary"))
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft, modifier = Modifier.padding(top = 4.dp).testTag("activity_map_notice")) }
        fixNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft, modifier = Modifier.padding(top = 4.dp).testTag("activity_map_centre_note")) }
        picked?.let { cell -> CellMoments(cell, vm, zone, onPreview) { picked = null } }
    }
}

/** The Moments of the tapped hot cell, newest first, as the usual rows; a tap opens the usual preview. */
@Composable
private fun CellMoments(cell: HeatCell, vm: ActivityMapViewModel?, zone: java.time.ZoneId, onPreview: (List<String>) -> Unit, close: () -> Unit) {
    val fmt = remember(zone) { java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.US) }
    val rows by produceRows(cell, vm) { ms -> fmt.format(java.time.Instant.ofEpochMilli(ms).atZone(zone)) }
    Column(Modifier.padding(top = 8.dp).testTag("activity_map_cell")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (cell.count == 1) "1 here" else "${cell.count} here", style = MaterialTheme.typography.titleSmall, color = PcColors.Ink, modifier = Modifier.weight(1f))
            TextButton(close, Modifier.testTag("activity_map_cell_close")) { Text("Close") }
        }
        rows.take(MAX_ROWS_SHOWN).forEach { r ->
            ListItemCard(r.item, onClick = { r.ref?.let { onPreview(listOf(it)) } }, modifier = Modifier.padding(vertical = 4.dp).testTag("activity_map_row"))
        }
        if (rows.size > MAX_ROWS_SHOWN) Text("and ${rows.size - MAX_ROWS_SHOWN} more. Zoom in to narrow it down.", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
    }
}

@Composable
private fun produceRows(cell: HeatCell, vm: ActivityMapViewModel?, clock: (Long) -> String): androidx.compose.runtime.State<List<MapMomentRow>> =
    androidx.compose.runtime.produceState(emptyList(), cell, vm) { value = vm?.rows(cell.momentIds, clock).orEmpty() }
