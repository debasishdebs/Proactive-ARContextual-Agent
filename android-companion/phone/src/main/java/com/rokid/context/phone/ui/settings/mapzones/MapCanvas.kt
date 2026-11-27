package com.rokid.context.phone.ui.settings.mapzones

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.privacy.zones.LatLon
import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import com.rokid.context.phone.ui.settings.privacy.zones.OnlineMapPanel
import com.rokid.context.phone.ui.settings.privacy.zones.PinInfo
import com.rokid.context.phone.ui.settings.privacy.zones.ReaderTiles
import com.rokid.context.phone.ui.settings.privacy.zones.SeedOverlay
import com.rokid.context.phone.ui.settings.privacy.zones.SeedOverlays
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneRowUi
import com.rokid.context.phone.ui.settings.privacy.zones.decodeTileBounded
import com.rokid.context.phone.ui.settings.privacy.zones.drawGrid
import com.rokid.context.phone.ui.settings.privacy.zones.drawLabel
import com.rokid.context.phone.ui.settings.privacy.zones.drawPin
import com.rokid.context.phone.ui.settings.privacy.zones.drawSeedArea
import com.rokid.context.phone.ui.settings.privacy.zones.drawZone
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.PmTilesReader
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.RasterTileSource
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.TileCache
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.TileMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Map zones P1 (flag mapZonesV2): the full-height reusable map. It draws the tile chain the shipped screen draws (offline file, then the opt-in online source, then the grid), the overlays by type,
// the draft shape with its handles, and turns gestures into callbacks. It owns no zone state and makes no store call; no coordinate is logged.

/** The view of the map, held by the screen so buttons and "+ Draw area" can read and move it. */
class MapViewState {
    var vp by mutableStateOf<MapViewport?>(null)
    var size by mutableStateOf(Size.Zero)
    var fitKey by mutableStateOf(0)
    fun zoom(factor: Double) { vp = vp?.zoomed(factor) }
    fun fit() { fitKey++ }
}

/** The TalkBack custom actions: step the draft, resize it, and walk through the shapes. Each does what a drag does, with no gesture. */
class MapAccessActions(val move: (StepDir) -> Unit, val resize: (Boolean) -> Unit, val selectNext: () -> Unit)

private val MapBackground = Color(0xFFF3F1EA)
private val DraftInk = Color(0xFF111827)
private const val ONLINE_PARALLEL = 4

@Composable
fun MapCanvas(
    view: MapViewState,
    zones: List<ZoneRowUi>,
    seedAreas: List<SeedOverlay>,
    pins: List<PinInfo>,
    items: List<MapItem>,
    layers: Set<MapLayer>,
    shape: DrawnShape?,
    selected: Selection?,
    you: LatLon?,
    fitBoxes: List<DoubleArray>,
    drawKind: ShapeKind,
    canDraw: Boolean,
    summary: String,
    reader: PmTilesReader?,
    online: OnlineMapPanel?,
    onNewShape: (DrawnShape?) -> Unit,
    onReshape: (DrawnShape) -> Unit,
    onTap: (TapTarget) -> Unit,
    modifier: Modifier = Modifier,
    access: MapAccessActions? = null,
    /** P2 and P3 (flag mapZonesP23), all default off: edges to snap a drag to, double-tap zoom, the Moments density blobs and the per-cell colours of one seed area. */
    snapTargets: List<DoubleArray>? = null,
    doubleTapZoom: Boolean = false,
    density: List<DensityBlob> = emptyList(),
    cells: Coverage? = null,
    /** No network (owner 6e): the plain grid is drawn on white. */
    blank: Boolean = false,
    /** A rectangle draft also gets a handle on each side (new drafts only; a stored pause zone being reshaped keeps its four corners). */
    edgeHandles: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val fitSig = items.map { it.id }
    LaunchedEffect(view.size, fitSig, view.fitKey) {
        val s = view.size
        if (s.width < 10f || s.height < 10f) return@LaunchedEffect
        val boxes = fitBoxes.ifEmpty { you?.let { listOf(MapViewport.grown(it.lat, it.lon, it.lat, it.lon, 2_000.0)) } ?: emptyList() }
        view.vp = MapViewport.fit(boxes, s.width, s.height)
    }
    // Same source order as the shipped screen: an offline PMTiles file, then the opt-in online source (only while the screen is in the foreground), then the plain grid.
    val onlineSource = online?.source?.takeIf { reader == null && !blank } // no network: the plain grid only, cached online tiles are not painted
    val tiles: RasterTileSource? = remember(reader, onlineSource) { reader?.let { r -> ReaderTiles(r) } ?: onlineSource }
    val cache = remember(tiles) { tiles?.let { t -> TileCache<ImageBitmap>(weigh = { it.width.toLong() * it.height * 4 }, rememberAbsent = onlineSource == null) { k -> t.tile(k)?.let { b -> decodeTileBounded(b) } } } }
    var loaded by remember(cache) { mutableStateOf(0) }
    val vp = view.vp
    val zoom = vp?.let { v -> tiles?.let { TileMath.zoomFor(v.ppd, it.minZoom, it.maxZoom) } }
    val wanted = if (vp != null && zoom != null) TileMath.visible(vp, zoom) else emptyList()
    val canFetch = onlineSource == null || online?.foreground == true
    LaunchedEffect(cache, wanted, canFetch) {
        val c = cache ?: return@LaunchedEffect
        if (wanted.isEmpty() || !canFetch) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            if (onlineSource == null) { if (c.ensure(wanted)) loaded++ }
            else for (chunk in wanted.chunked(ONLINE_PARALLEL)) coroutineScope { chunk.map { k -> async { if (c.ensure(listOf(k))) loaded++ } }.awaitAll() }
        }
    }
    val centreReq = online?.centre
    LaunchedEffect(centreReq?.id, view.size.width >= 10f && view.size.height >= 10f) {
        val r = centreReq ?: return@LaunchedEffect
        if (view.size.width < 10f || view.size.height < 10f) return@LaunchedEffect
        view.vp = MapViewport.fit(listOf(MapViewport.grown(r.at.lat, r.at.lon, r.at.lat, r.at.lon, 250.0)), view.size.width, view.size.height)
    }
    val offlineAttribution = remember(reader) { reader?.attribution() }
    val onlineShown = onlineSource != null && loaded >= 0 && cache != null && wanted.any { cache.peek(it) != null }

    val shapeNow by rememberUpdatedState(shape)
    val canDrawNow by rememberUpdatedState(canDraw)
    val kindNow by rememberUpdatedState(drawKind)
    val itemsNow by rememberUpdatedState(items)
    val layersNow by rememberUpdatedState(layers)
    val onNewShapeNow by rememberUpdatedState(onNewShape)
    val onReshapeNow by rememberUpdatedState(onReshape)
    val onTapNow by rememberUpdatedState(onTap)
    val snapNow by rememberUpdatedState(snapTargets)
    val dblNow by rememberUpdatedState(doubleTapZoom)
    val edgesNow by rememberUpdatedState(edgeHandles)
    var lastTap by remember { mutableStateOf<Triple<Long, Float, Float>?>(null) }
    var preview by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }

    Box(Modifier.then(modifier).onSizeChanged { view.size = Size(it.width.toFloat(), it.height.toFloat()); view.vp = view.vp?.resized(view.size.width, view.size.height) }.semantics {
        contentDescription = summary
        access?.let { a ->
            val acts = ArrayList<CustomAccessibilityAction>()
            if (shape != null) {
                acts += CustomAccessibilityAction("Move shape north") { a.move(StepDir.NORTH); true }
                acts += CustomAccessibilityAction("Move shape south") { a.move(StepDir.SOUTH); true }
                acts += CustomAccessibilityAction("Move shape east") { a.move(StepDir.EAST); true }
                acts += CustomAccessibilityAction("Move shape west") { a.move(StepDir.WEST); true }
                acts += CustomAccessibilityAction("Make shape larger") { a.resize(true); true }
                acts += CustomAccessibilityAction("Make shape smaller") { a.resize(false); true }
            }
            if (items.any { it.type != ItemType.PIN }) acts += CustomAccessibilityAction("Select next shape") { a.selectNext(); true }
            customActions = acts
        }
    }.testTag("mapzones_map")) {
        Canvas(
            Modifier.fillMaxSize().background(if (blank) Color.White else MapBackground).pointerInput(Unit) {
                val touch = ShapeEdit.HANDLE_TOUCH_DP.dp.toPx()
                val minShape = SeedOverlays.MIN_SHAPE_DP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val v0 = view.vp ?: return@awaitEachGesture
                    val sh = shapeNow
                    val hit = if (sh != null) ShapeEdit.hit(v0, sh, down.position.x, down.position.y, touch, edgesNow) else Hit.Miss
                    if (sh != null && hit !is Hit.Miss) {
                        // Edit drag: a handle resizes, the body moves. The first finger on a handle never also pans the map.
                        var cur: DrawnShape = sh
                        var last = down.position
                        down.consume()
                        drag(down.id) { change ->
                            val v = view.vp ?: return@drag
                            val raw = if (hit is Hit.OnHandle) ShapeEdit.resize(cur, hit.handle, v.toLatLon(change.position.x, change.position.y))
                            else { val a = v.toLatLon(last.x, last.y); val b = v.toLatLon(change.position.x, change.position.y); ShapeEdit.move(cur, b.lat - a.lat, b.lon - a.lon) }
                            val next = snapNow?.let { t -> ShapeSnap.snap(cur, raw, t, v, ShapeSnap.SNAP_DP.dp.toPx()) } ?: raw
                            last = change.position
                            if (next != cur) { cur = next; onReshapeNow(next) }
                            change.consume()
                        }
                        return@awaitEachGesture
                    }
                    // Arbitrate slop, extra fingers and the long-press time TOGETHER (see TouchArbiter): a second finger or a move past the slop is a pan / pinch at once; a lift first is a tap; only one finger held still draws.
                    val slop = viewConfiguration.touchSlop
                    val lpMs = viewConfiguration.longPressTimeoutMillis
                    var decision = TouchDecision.WAIT
                    var decidedEvent: androidx.compose.ui.input.pointer.PointerEvent? = null
                    withTimeoutOrNull(lpMs) {
                        while (decision == TouchDecision.WAIT) {
                            val ev = awaitPointerEvent()
                            val mine = ev.changes.firstOrNull { it.id == down.id }
                            val moved = mine?.let { (it.position - down.position).getDistance() } ?: 0f
                            decision = TouchArbiter.decide(ev.changes.count { it.pressed }, mine == null || !mine.pressed, moved, slop, 0L, lpMs)
                            if (decision != TouchDecision.WAIT) decidedEvent = ev
                        }
                    }
                    if (decision == TouchDecision.WAIT) decision = if (canDrawNow) TouchDecision.DRAW else TouchDecision.PAN
                    if (decision == TouchDecision.TAP) {
                        val now = System.currentTimeMillis()
                        val prevTap = lastTap
                        if (dblNow && DoubleTap.isDouble(prevTap?.first, prevTap?.second ?: 0f, prevTap?.third ?: 0f, now, down.position.x, down.position.y, viewConfiguration.touchSlop * 4)) {
                            lastTap = null
                            view.vp = view.vp?.zoomed(DoubleTap.FACTOR, down.position.x, down.position.y)
                            return@awaitEachGesture
                        }
                        lastTap = Triple(now, down.position.x, down.position.y)
                        onTapNow(MapTap.at(itemsNow, v0, down.position.x, down.position.y, minShape, touch, layersNow))
                        return@awaitEachGesture
                    }
                    if (decision == TouchDecision.DRAW) {
                        // Long press then drag: a new rectangle (corner to corner) or circle (centre to edge).
                        val start = down.position
                        preview = start to start
                        drag(down.id) { change -> preview = start to change.position; change.consume() }
                        val end = preview?.second ?: start
                        preview = null
                        val v = view.vp ?: return@awaitEachGesture
                        val a = v.toLatLon(start.x, start.y); val b = v.toLatLon(end.x, end.y)
                        onNewShapeNow(if (kindNow == ShapeKind.RECT) ShapeEdit.rectFromDrag(a, b) else ShapeEdit.circleFromDrag(a, b))
                        return@awaitEachGesture
                    }
                    // Pan / pinch: the event that decided it is applied first, then the rest of the gesture.
                    decidedEvent?.let { ev ->
                        val z = ev.calculateZoom(); val p = ev.calculatePan(); val c = ev.calculateCentroid()
                        if ((z != 1f || p != Offset.Zero) && c.isSpecified) {
                            view.vp = view.vp?.panned(p.x, p.y)?.zoomed(z.toDouble(), c.x, c.y)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        if (ev.changes.none { it.pressed }) return@awaitEachGesture
                    }
                    do {
                        val ev = awaitPointerEvent()
                        val z = ev.calculateZoom(); val p = ev.calculatePan(); val c = ev.calculateCentroid()
                        if ((z != 1f || p != Offset.Zero) && c.isSpecified) {
                            view.vp = view.vp?.panned(p.x, p.y)?.zoomed(z.toDouble(), c.x, c.y)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        ) {
            val v = view.vp ?: return@Canvas
            var painted = false
            if (cache != null && loaded >= 0) for (k in wanted) {
                val img = cache.peek(k) ?: continue
                val r = TileMath.screenRect(v, k)
                drawImage(img, dstOffset = IntOffset(r[0].roundToInt(), r[1].roundToInt()), dstSize = IntSize(r[2].roundToInt() + 1, r[3].roundToInt() + 1))
                painted = true
            }
            if (!painted) drawGrid(v, measurer)
            val minPx = SeedOverlays.MIN_SHAPE_DP.dp.toPx()
            for (b in density) {
                val p = v.toPx(b.lat, b.lon)
                if (!(p.x.isFinite() && p.y.isFinite()) || p.x < -80f || p.y < -80f || p.x > size.width + 80f || p.y > size.height + 80f) continue
                val col = Color(MomentDensity.colorOf(b.level)); val rad = (20f + 24f * b.level).dp.toPx()
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(col.copy(alpha = 0.6f), col.copy(alpha = 0f)), Offset(p.x, p.y), rad), rad, Offset(p.x, p.y))
            }
            if (MapLayer.SEED in layers) for (a in seedAreas) if (!SeedOverlays.isTiny(v, a.box, minPx)) { drawSeedArea(v, a, measurer, selected = selected?.kind == SelectionKind.SEED && selected.id == a.id); drawDots(v, a.box, TypeLooks.SEED) }
            if (MapLayer.PAUSE in layers) for (r in zones) {
                val z = r.zone
                val box = doubleArrayOf(z.south, z.west, z.north, z.east)
                if (SeedOverlays.isTiny(v, box, minPx)) continue
                drawZone(v, z, measurer, active = z.enabled)
                if (z.enabled) drawHatch(v, box, TypeLooks.PAUSE)
            }
            cells?.cells?.forEach { c ->
                val tl = v.toPx(c.box[2], c.box[1]); val br = v.toPx(c.box[0], c.box[3])
                if (!(tl.x.isFinite() && tl.y.isFinite() && br.x.isFinite() && br.y.isFinite()) || br.x < 0f || tl.x > size.width || br.y < 0f || tl.y > size.height) return@forEach
                val col = when (c.state) { CellState.OK -> Color(0xFF1E6FD9); CellState.PARTIAL -> Color(0xFFF59E0B); CellState.MISSING -> Color(0xFFD32F2F) }
                if (c.state != CellState.MISSING) drawRect(col.copy(alpha = 0.3f), Offset(tl.x, tl.y), Size(br.x - tl.x, br.y - tl.y))
                drawRect(col, Offset(tl.x, tl.y), Size(br.x - tl.x, br.y - tl.y), style = Stroke(1.dp.toPx(), pathEffect = if (c.state == CellState.OK) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            }
            for (c in MapOverview.clusters(items, v, minPx, layers)) drawChip(v, c, measurer)
            selected?.let { s -> items.firstOrNull { it.id == s.id && (it.type == ItemType.PAUSE) == (s.kind == SelectionKind.PAUSE) }?.let { drawSelected(v, it.box, minPx) } }
            shape?.let { drawDraft(v, it, edgeHandles) }
            preview?.let { (a, b) ->
                if (kindNow == ShapeKind.RECT) drawRect(DraftInk, Offset(min(a.x, b.x), min(a.y, b.y)), Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                else drawCircle(DraftInk, kotlin.math.hypot(b.x - a.x, b.y - a.y), a, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
            }
            (centreReq?.at ?: you)?.let { y -> val p = v.toPx(y.lat, y.lon); drawCircle(Color.White, 9.dp.toPx(), Offset(p.x, p.y)); drawCircle(PcColors.Sky, 6.dp.toPx(), Offset(p.x, p.y)) }
        }
        offlineAttribution?.let { a -> Text(a, style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft, modifier = Modifier.align(Alignment.TopStart).padding(top = 56.dp).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp).testTag("zone_map_attribution")) }
        // The provider's terms: its attribution and logo text on every map that shows its tiles.
        if (onlineShown && onlineSource != null) Row(Modifier.align(Alignment.TopStart).padding(top = 56.dp).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp).testTag("zone_map_attribution"), verticalAlignment = Alignment.CenterVertically) {
            onlineSource.logoText?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = PcColors.InkSoft, modifier = Modifier.padding(end = 4.dp).testTag("zone_map_logo")) }
            onlineSource.attribution?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft) }
        }
    }
}

private fun DrawScope.visibleRect(tl: Offset, br: Offset): androidx.compose.ui.geometry.Rect? {
    val l = max(tl.x, 0f); val t = max(tl.y, 0f); val r = min(br.x, size.width); val b = min(br.y, size.height)
    return if (r - l >= 4f && b - t >= 4f) androidx.compose.ui.geometry.Rect(l, t, r, b) else null
}

/** Diagonal hatch over a pause zone (the pattern cue next to the red colour). Capped so a zone bigger than the screen costs the same as a small one. */
private fun DrawScope.drawHatch(v: MapViewport, box: DoubleArray, look: TypeLook) {
    val tl = v.toPx(box[2], box[1]); val br = v.toPx(box[0], box[3])
    val r = visibleRect(Offset(tl.x, tl.y), Offset(br.x, br.y)) ?: return
    val col = Color(look.argb).copy(alpha = 0.45f)
    clipRect(r.left, r.top, r.right, r.bottom) {
        val step = 12.dp.toPx(); val h = r.height
        var x = r.left - h; var n = 0
        while (x < r.right && n++ < 200) { drawLine(col, Offset(x, r.bottom), Offset(x + h, r.top), 1.dp.toPx()); x += step }
    }
}

/** Dot grid over a seed area (its pattern cue). Capped. */
private fun DrawScope.drawDots(v: MapViewport, box: DoubleArray, look: TypeLook) {
    val tl = v.toPx(box[2], box[1]); val br = v.toPx(box[0], box[3])
    val r = visibleRect(Offset(tl.x, tl.y), Offset(br.x, br.y)) ?: return
    val col = Color(look.argb).copy(alpha = 0.5f)
    val step = 14.dp.toPx(); var n = 0
    var y = r.top + step / 2
    while (y < r.bottom && n < 300) { var x = r.left + step / 2; while (x < r.right && n < 300) { drawCircle(col, 1.5.dp.toPx(), Offset(x, y)); x += step; n++ }; y += step }
}

/** A chip for tiny or merged items: a type-coloured disc with a pattern mark (pause: slash, seed: dot) and the count when merged. A lone pin is the purple dot. */
private fun DrawScope.drawChip(v: MapViewport, c: MapCluster, tm: TextMeasurer) {
    val p = v.toPx(c.centerLat, c.centerLon)
    if (!p.x.isFinite() || !p.y.isFinite()) return
    if (c.isSingle && c.type == ItemType.PIN) { drawPin(v, PinInfo(c.centerLat, c.centerLon, 0)); return }
    val col = Color(TypeLooks.of(c.type).argb)
    val r = 12.dp.toPx(); val o = Offset(p.x, p.y)
    drawCircle(Color.White, r + 2.dp.toPx(), o)
    drawCircle(col, r, o)
    if (c.isSingle) {
        if (c.type == ItemType.PAUSE) drawLine(Color.White, Offset(o.x - r * 0.5f, o.y + r * 0.5f), Offset(o.x + r * 0.5f, o.y - r * 0.5f), 2.dp.toPx())
        else drawCircle(Color.White, r * 0.3f, o)
    } else {
        val layout = tm.measure(c.count.toString(), TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold), softWrap = false)
        drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f))
    }
}

private fun DrawScope.drawSelected(v: MapViewport, box: DoubleArray, minPx: Float) {
    val r = SeedOverlays.screenRect(v, box, minPx)
    if (!r.all { it.isFinite() }) return
    drawRect(DraftInk, Offset(r[0] - 3.dp.toPx(), r[1] - 3.dp.toPx()), Size(r[2] + 6.dp.toPx(), r[3] + 6.dp.toPx()), style = Stroke(2.dp.toPx()))
}

/** The shape being drawn: a dark dashed outline with white handles (drawn at 12 dp, touched at 48 dp). */
private fun DrawScope.drawDraft(v: MapViewport, s: DrawnShape, edges: Boolean = false) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
    val b = ShapeEdit.box(s) ?: return
    val tl = v.toPx(b[2], b[1]); val br = v.toPx(b[0], b[3])
    when (s) {
        is DrawnShape.Rect -> {
            drawRect(DraftInk.copy(alpha = 0.08f), Offset(tl.x, tl.y), Size(br.x - tl.x, br.y - tl.y))
            drawRect(DraftInk, Offset(tl.x, tl.y), Size(br.x - tl.x, br.y - tl.y), style = Stroke(2.dp.toPx(), pathEffect = dash))
        }
        is DrawnShape.Circle -> {
            val c = v.toPx(s.lat, s.lon); val r = (br.x - tl.x) / 2 / CircleBox.SAFETY.toFloat()
            drawCircle(DraftInk.copy(alpha = 0.08f), r, Offset(c.x, c.y))
            drawCircle(DraftInk, r, Offset(c.x, c.y), style = Stroke(2.dp.toPx(), pathEffect = dash))
        }
    }
    for ((_, p) in ShapeEdit.handlePoints(v, s, edges).orEmpty()) {
        drawCircle(DraftInk, (ShapeEdit.HANDLE_DRAW_DP / 2 + 2).dp.toPx(), Offset(p.first, p.second))
        drawCircle(Color.White, (ShapeEdit.HANDLE_DRAW_DP / 2).dp.toPx(), Offset(p.first, p.second))
    }
}
