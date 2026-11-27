package com.rokid.context.phone.ui.settings.privacy.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.memory.place.AREA_DONE
import com.rokid.context.memory.place.AREA_PARTIAL
import com.rokid.context.memory.place.AREA_SEEDING
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.phone.ui.settings.place.SeedOutcome
import com.rokid.context.phone.ui.settings.place.seedFailureText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import com.rokid.context.memory.place.SeedTally
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor

// Map zones P0: seeded areas and pins as read-only overlays on the pause-zones map, and "seed this drawn box" through the existing Place Guide seed path.
// Pause zones are NOT touched here: their store, evaluator and save path are unchanged.

/** A seeded area as the map needs it: id, box and the pack's status text. Held on screen only; never logged. */
data class SeedAreaInfo(val id: String, val south: Double, val west: Double, val north: Double, val east: Double, val status: String,
    /** v18 WP11b: the owner's name for the area, read only; null until a rename screen exists. Not shown yet. */
    val name: String? = null,
    /** S3: the background seed's derived state while it is active (queued, running NN%, waiting for a network); null = no live seed. */
    val seed: com.rokid.context.phone.place.SeedAreaState? = null,
    /** S5: seeded places in the box, their text bytes and the seed date (ms); read from places.db, so they show offline. null = not known. */
    val places: Int? = null, val bytes: Long = 0, val seededAt: Long = 0,
    /** The last seed ended with this many cells failed (after the automatic retries); 0 = none known. The row, the selected-area card and the progress area offer "Retry failed cells". */
    val failedCells: Int = 0,
    /** The last seed was cancelled and the stored cells do not cover the area (nothing is running; "Seed again" resumes). */
    val cancelled: Boolean = false,
    /** A done area: how many of its cells have no OSM extras (a count only). */
    val noOsm: Int = 0,
    /** The owner's saved colour (a palette index), or null = the default for the area's order. */
    val savedColour: Int? = null,
    /** B2: reference photos of this area's seeded places (counts only): with a photo, without one for good, and the phase's note ("waiting for free Wi-Fi"); null = not known. */
    val photos: AreaPhotos? = null) {
    val box: DoubleArray get() = doubleArrayOf(south, west, north, east)
}

/** B2: [ok] places with a photo, [none] without one (none free, skipped or failed for good) of [total] seeded places; [note] = why the rest waits (null = nothing to say). */
data class AreaPhotos(val ok: Int, val none: Int, val total: Int, val note: String? = null, /** Downloaded photos already matched, of all downloaded photos on the phone (counts only). */ val matched: Int = 0, val toMatch: Int = 0) {
    val done: Int get() = (ok + none).coerceAtMost(total)
    val complete: Boolean get() = total <= 0 || done >= total
    val matching: Boolean get() = complete && toMatch > 0 && matched < toMatch
    /** Work is going on right now (photos downloading, or matching), so the list keeps refreshing; false while it waits for free Wi-Fi. */
    val busy: Boolean get() = (!complete && note == null) || matching
}

/** A user pin as the map needs it: a dot (no name, no note). */
data class PinInfo(val lat: Double, val lon: Double, val radiusM: Int)

/** What the zones map needs of Place Guide: the seeded areas and pins to draw, the existing seed path and the area delete. */
interface SeedMapControl {
    suspend fun areas(): List<SeedAreaInfo>
    suspend fun pins(): List<PinInfo>
    /** The existing Place Guide seed (same Offline only, Wi-Fi and data saver refusals). */
    suspend fun seed(box: GeoBox): Result<SeedOutcome>
    /** Removes the seeded-area record. [withPlaces] false: its places stay as ordinary fetched places; true: its seeded places (not pins, not places another area covers) and their reference captions go too. */
    suspend fun remove(id: String, withPlaces: Boolean = false)
    /** Cancels the area's background seed (the area stays, partial). */
    suspend fun cancelSeed(id: String) {}
    /** S4: stores the owner's label for the area (the label only: box and places are untouched). Returns false when it could not be stored. */
    suspend fun rename(id: String, name: String?): Boolean = false
    /** "Retry failed cells": queues the area's own box again (cells that are fresh are skipped, so only the failed ones are fetched). Same Offline only refusal as a new seed. */
    suspend fun retry(id: String): Result<SeedOutcome> = Result.failure(UnsupportedOperationException())
    /** S4: what this phone's own pack says about places and bytes per seeded cell (read only, offline). The default knows nothing, so the estimate is marked rough. */
    suspend fun rates(): SeedRates = SeedRates.ROUGH
    /** B2 "Retry" of the photo phase: starts it again (free Wi-Fi only). Default: nothing. */
    suspend fun retryPhotos() {}
    /** Map zones "Show cells" (read only): the stored status of each 5 km cell touching [box]; a missing cell is absent. The default is empty (nothing known). */
    suspend fun cellStatuses(box: GeoBox): Map<String, String> = emptyMap()
    /** Saves the owner's colour (a [SeedPalette] index) for the area; kept in a small prefs file keyed by a hash of the id. */
    suspend fun setColour(id: String, index: Int) {}
    /**
     * "Edit box": the area [id] keeps its id, name and colour and takes the new [box]; only the cells that are not fresh are fetched (the pack's own freshness rule). Nothing is deleted: places fetched before stay,
     * also those that fall outside a smaller box. Same Offline only refusal as a new seed.
     */
    /** The stored places inside [box] as map dots (never user pins), at most [cap]; read from places.db, so it works with no network. */
    suspend fun dots(box: GeoBox, cap: Int): List<com.rokid.context.memory.place.PlaceDot> = emptyList()
    /** The label of one dot (name, kind, first line of the intro), or null when the place is gone. */
    suspend fun dotLabel(id: String): String? = null
    /** The "Show seeded places" switch (default ON), kept on the phone. */
    suspend fun showPlaces(): Boolean = true
    suspend fun setShowPlaces(on: Boolean) {}
    suspend fun reseed(id: String, box: GeoBox): Result<SeedOutcome> = Result.failure(UnsupportedOperationException())
}

/**
 * Places per cell and bytes per place for the estimate. Below [MIN_CELLS] seeded cells the defaults (0.5 place a cell, 1 KB a place) are used and marked rough. [secondsPerCell] is this phone's own
 * measured time for a fetched cell (null until it has timed enough cells): the estimate then uses it instead of the laptop figure.
 */
data class SeedRates(val placesPerCell: Double, val bytesPerPlace: Long, val rough: Boolean, val secondsPerCell: Double? = null) {
    companion object {
        const val MIN_CELLS = 20
        val ROUGH = SeedRates(0.5, 1024, true)
        /** From the pack: [cells] seeded cells, [places] seeded places in them, [bytes] their text; [secondsPerCell] from the phone's own seed timing, if any. */
        fun of(cells: Int, places: Int, bytes: Long, secondsPerCell: Double? = null): SeedRates =
            (if (cells < MIN_CELLS || places <= 0) ROUGH else SeedRates(places.toDouble() / cells, Math.max(1L, bytes / places), false)).copy(secondsPerCell = secondsPerCell?.takeIf { it.isFinite() && it > 0 })
    }
}

enum class SeedLevel { EMPTY, OK, CONFIRM, TOO_LARGE }

/** The cell count and time estimate for a drawn box, and the thresholds around it. Pure. */
object SeedEstimate {
    /** Above this many 5 km cells the app asks before seeding (design 3.2). */
    const val CONFIRM_CELLS = 100L
    /** Above this many cells the estimate and the confirm step say "this is large; it runs in the background and may take hours" (owner ruling 27 Nov: the size of the trip box). */
    const val LARGE_CELLS = 2_000L
    const val LARGE_WARNING = "This is large: it runs in the background and may take hours."
    /** The pack's own hard cap; a box above it is refused here before any request. */
    const val MAX_CELLS: Long = PlacePack.MAX_SEED_CELLS.toLong()
    /** The same cap for a BULK seed (owner decision 27 Nov): one query covers many cells. */
    const val MAX_CELLS_BULK: Long = PlacePack.MAX_SEED_CELLS_BULK.toLong()
    /** Whether the seed will run in bulk mode (the Developer `bulkSeed` flag, default ON; production sets it at app start, tests leave it OFF = the per-cell numbers). */
    @Volatile var bulkMode: () -> Boolean = { false }
    /** The cell cap a SEED is checked against now (the "Show cells" views keep [MAX_CELLS]). */
    fun seedMaxCells(): Long = if (bulkMode()) MAX_CELLS_BULK else MAX_CELLS
    /**
     * ESTIMATE of seconds a cell takes in BULK mode: measured on a Mac 27 Nov (design 2026-11-27-large-area-seeding-design.md section 10): a 650-cell region in 15 requests, about 60 s, so about 0.1 s a cell;
     * a dense city takes more (3 times slower per query), a sparse state much less. Not measured on the phone.
     */
    const val SECONDS_PER_CELL_BULK = 0.1
    /** Bulk mode: the time is an estimate and usually minutes, but it still runs in the background and may take longer on a slow network or in a dense area. */
    const val LARGE_WARNING_BULK = "This is large: it runs in the background. The time is an estimate and may be longer on a slow network or in a dense area."
    private fun largeWarning() = if (bulkMode()) LARGE_WARNING_BULK else LARGE_WARNING
    /** Seconds per cell used when none is given: the bulk ESTIMATE in bulk mode, else the laptop figure. */
    private fun defaultSecondsPerCell(): Double = if (bulkMode()) SECONDS_PER_CELL_BULK else SECONDS_PER_CELL
    /**
     * Seconds a cell takes, MEASURED on a laptop on 26 Nov (tools/measure/measure_seed_cells.py, 25 cells of a busy tourist area, same requests as PlacePack.seedArea): median 3.0 s, p90 8.8 s,
     * MEAN 7.4 s. The estimate multiplies by the mean (a total is cells times the mean; the mean carries the slow cells, such as Overpass mirrors timing out). Replaced by the phone's own
     * timing once it has timed 10 cells.
     */
    const val SECONDS_PER_CELL = 7.4

    const val TIME_FROM_LAPTOP = "(measured on a laptop; your phone will refine it)"
    const val TIME_FROM_PHONE = "(from this phone's earlier seeds)"

    /** The box as a [GeoBox], or null when it is missing or not a valid box. */
    fun boxOf(b: DoubleArray?): GeoBox? = b?.takeIf { it.size == 4 }?.let { GeoBox(it[0], it[1], it[2], it[3]) }?.takeIf { it.valid }

    /** How many 5 km cells touch [box], without building the list (a huge box must not allocate millions of keys). Equals `Geohash5.cellsCovering(box).size`. */
    fun cells(box: GeoBox): Long {
        if (!box.valid) return 0
        val la0 = latIndex(box.south); val la1 = latIndex(box.north); val lo0 = lonIndex(box.west); val lo1 = lonIndex(box.east)
        return (la1 - la0 + 1L) * (lo1 - lo0 + 1L)
    }

    fun level(cells: Long): SeedLevel = when {
        cells <= 0 -> SeedLevel.EMPTY
        cells > seedMaxCells() -> SeedLevel.TOO_LARGE
        cells > CONFIRM_CELLS -> SeedLevel.CONFIRM
        else -> SeedLevel.OK
    }

    fun seconds(cells: Long, secondsPerCell: Double = defaultSecondsPerCell()): Long = Math.round(cells * secondsPerCell)

    fun duration(cells: Long, secondsPerCell: Double = defaultSecondsPerCell()): String {
        val s = seconds(cells, secondsPerCell)
        return if (s < 10 && bulkMode()) "under 10 s" else if (s < 90) "about $s s" else if (s < 5_400) "about ${ceil(s / 60.0).toLong()} min" else "about ${ceil(s / 3_600.0).toLong()} h"
    }

    /** S4: the full estimate before seeding: cells (exact), places and text size (from [rates]), time (an ESTIMATE: the 10 s a cell is not measured). */
    fun estimate(box: GeoBox?, rates: SeedRates = SeedRates.ROUGH): String {
        if (box == null) return line(null)
        val n = cells(box)
        if (level(n) == SeedLevel.EMPTY || level(n) == SeedLevel.TOO_LARGE) return line(box)
        val places = Math.round(n * rates.placesPerCell)
        val bytes = Math.round(places * rates.bytesPerPlace.toDouble())
        val rough = if (rates.rough) " (rough: not enough seeded cells on this phone yet)" else ""
        return "About $n cell${if (n == 1L) "" else "s"} (5 km), about $places place${if (places == 1L) "" else "s"}, about ${sizeText(bytes)} of text, ${if (bulkMode()) "${duration(n, SECONDS_PER_CELL_BULK)} (estimate, bulk mode: one query covers many cells) on Wi-Fi" else "${duration(n, rates.secondsPerCell ?: SECONDS_PER_CELL)} on Wi-Fi ${if (rates.secondsPerCell != null) TIME_FROM_PHONE else TIME_FROM_LAPTOP}"}.$rough" + (if (n > LARGE_CELLS) " ${largeWarning()}" else "")
    }

    fun sizeText(bytes: Long): String = if (bytes < 100_000) "${Math.max(1, (bytes + 512) / 1024)} KB" else String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)

    /** The line under the map: the count, the time and the Wi-Fi rule; or why the box cannot be seeded. */
    fun line(box: GeoBox?): String {
        if (box == null) return "Draw a box to see how many 5 km cells it covers."
        val n = cells(box)
        return when (level(n)) {
            SeedLevel.EMPTY -> "Draw a box to see how many 5 km cells it covers."
            SeedLevel.TOO_LARGE -> "Too large: $n cells. Draw a smaller area (at most ${seedMaxCells()} cells)."
            else -> "$n cell${if (n == 1L) "" else "s"} (5 km each), ${duration(n)}${if (bulkMode()) " (estimate)" else ""}, runs in the background."
        }
    }

    fun confirmText(cells: Long): String = "This is a large area ($cells cells, ${duration(cells)}${if (bulkMode()) ", estimate" else ""})." + (if (cells > LARGE_CELLS) " ${largeWarning()}" else "") + " Seed it? Only the 5 km cell keys are sent, never your position."

    private fun latIndex(lat: Double) = minOf(LAT_CELLS - 1, floor((lat + 90.0) / Geohash5.LAT_STEP).toInt())
    private fun lonIndex(lon: Double) = minOf(LON_CELLS - 1, floor((lon + 180.0) / Geohash5.LON_STEP).toInt())
    private const val LAT_CELLS = 4096
    private const val LON_CELLS = 8192
}

enum class AreaLook { DONE, PARTIAL, SEEDING }

/** One seeded area to draw: its box, its look (blue / amber with a badge / moving dash) and the list label. */
data class SeedOverlay(val id: String, val box: DoubleArray, val look: AreaLook, val label: String, val failedCells: Int = 0,
    /** The [SeedPalette] index the area is drawn in (its saved choice, else the default for its order). */
    val colour: Int = 0)

/** The pure overlay model: what the canvas draws and the legend line. No Compose, no coordinates in any text. */
object SeedOverlays {
    /** Smaller than this on screen (dp) a shape is drawn as a chip of this size so a small area still shows when zoomed out. */
    const val MIN_SHAPE_DP = 16f

    fun look(status: String): AreaLook = when (status) { AREA_SEEDING -> AreaLook.SEEDING; AREA_PARTIAL -> AreaLook.PARTIAL; else -> AreaLook.DONE }

    fun of(areas: List<SeedAreaInfo>): List<SeedOverlay> = areas.mapIndexed { i, a ->
        val look = look(a.status)
        SeedOverlay(a.id, a.box, if (a.seed != null) AreaLook.SEEDING else look, (a.name?.takeIf { it.isNotBlank() } ?: "Seeded area ${i + 1}") + if (a.seed != null) " (${a.seed.text.replaceFirstChar { it.lowercase() }})" else when (look) { AreaLook.DONE -> ""; AreaLook.PARTIAL -> if (a.failedCells > 0) " (FAILED: ${a.failedCells} cell${if (a.failedCells == 1) "" else "s"})" else if (a.cancelled) " (cancelled, seed again)" else " (not finished, seed again)"; AreaLook.SEEDING -> " (not finished, seed again)" }, a.failedCells, SeedPalette.resolve(a.savedColour, i))
    }

    /** The legend: counts of what is drawn. Pause zones are counted by the caller's own list. */
    fun legend(pause: Int, seed: Int, pins: Int): String =
        "$pause pause zone${if (pause == 1) "" else "s"} · $seed seed area${if (seed == 1) "" else "s"} · $pins pin${if (pins == 1) "" else "s"}"

    /** The on-screen rectangle (x, y, w, h) of [box], widened around its centre to at least [minPx]. */
    fun screenRect(v: MapViewport, box: DoubleArray, minPx: Float): FloatArray {
        val tl = v.toPx(box[2], box[1]); val br = v.toPx(box[0], box[3])
        var x = tl.x; var y = tl.y; var w = br.x - tl.x; var h = br.y - tl.y
        if (w < minPx) { x -= (minPx - w) / 2; w = minPx }
        if (h < minPx) { y -= (minPx - h) / 2; h = minPx }
        return floatArrayOf(x, y, w, h)
    }

    /** True when the box is smaller than [minPx] in either direction on screen. */
    fun isTiny(v: MapViewport, box: DoubleArray, minPx: Float): Boolean {
        val tl = v.toPx(box[2], box[1]); val br = v.toPx(box[0], box[3])
        return (br.x - tl.x) < minPx || (br.y - tl.y) < minPx
    }

    /** Every box to fit when the map opens: the zones' rings plus the seeded areas, so a seeded area is never off screen at the start. */
    fun fitBoxes(zones: List<DoubleArray>, areas: List<SeedAreaInfo>): List<DoubleArray> = zones + areas.map { it.box }
}

data class SeedMapUiState(
    val areas: List<SeedOverlay> = emptyList(),
    val pins: List<PinInfo> = emptyList(),
    /** Places stored across the seeded areas (for the counts header). */
    val seededPlaces: Int = 0,
    /** B2: some seeded place still has no decided reference photo (the list shows "Retry photos"). */
    val photoRetry: Boolean = false,
    val seeding: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** A seed waiting for the user's yes (above [SeedEstimate.CONFIRM_CELLS]): the cell count to show. */
    val confirmCells: Long? = null,
    val pendingRemoveId: String? = null,
    /** The choice in the remove dialog: "the area and its places" is PRESELECTED (owner round 6g Q4), every time it opens. */
    val removeWithPlaces: Boolean = true,
    /** S3: the background seeds still to finish (area id and the line to show: "Seeding places 42%"). */
    val progress: List<SeedProgressRow> = emptyList(),
    /** S4: the owner's own pack rates for the estimate (rough until 20 seeded cells exist). */
    val rates: SeedRates = SeedRates.ROUGH,
    /** S5: the seeded-areas list rows (read from places.db, readable offline). */
    val rows: List<SeedAreaRow> = emptyList(),
    /** The header line above the list ("2 seeded areas · 120 places stored"); null while there are no areas. */
    val header: String? = null,
    /** The tapped seeded area (highlighted on the map, its name drawn); null = none. */
    val selectedId: String? = null,
    /** Counts up on every row tap (a second tap on the same row re-fits); the screen scrolls to the map card and fits [focusBox] when it changes. */
    val focusSeq: Int = 0,
    /** The selected area's box grown by a small margin ([SeedFocus.fitBox]): what the map fits. No text shows it. */
    val focusBox: DoubleArray? = null,
    /** "Edit box": the area whose rectangle is being reshaped in the draft box (null = a new seed). "Seed this box" then re-seeds THIS area; its places are kept. */
    val editingId: String? = null,
    /** Counts up on every "Edit box" tap; the screen loads [editBox] into its draft box when it changes. */
    val editSeq: Int = 0,
    val editBox: DoubleArray? = null,
    /** Counts up whenever the draft box must go (a seed was queued or started, a retry, Cancel edit): both screens drop their draft box when it changes. The box only comes back from "Draw a box" / "Edit box". */
    val clearDraftSeq: Int = 0,
    /** Seeded places as dots: the stored places in view (at most [SeedDots.CAP]) when the view is close enough. */
    val dots: List<com.rokid.context.memory.place.PlaceDot> = emptyList(),
    /** More places than [SeedDots.CAP] are in view: the map says to zoom in. */
    val dotsCapped: Boolean = false,
    /** The view is too wide to read the places (about 400 cells or more): none are drawn and the map says to zoom in. */
    val dotsTooFar: Boolean = false,
    /** The "Show seeded places" switch (default ON). */
    val showPlaces: Boolean = true,
    /** The label of the tapped dot (id and text); null = none. */
    val dotLabel: DotLabel? = null,
    val dotSelectedId: String? = null,
)

/** The pure part of "tap a seeded area": the box the map fits (the area plus a margin) and the row tap and map tap results. */
object SeedFocus {
    /** The share of the area's size added on every side so the outline is never against the map edge. */
    const val MARGIN = 0.15
    fun fitBox(box: DoubleArray): DoubleArray {
        val dLat = (box[2] - box[0]).coerceAtLeast(1e-4) * MARGIN; val dLon = (box[3] - box[1]).coerceAtLeast(1e-4) * MARGIN
        return doubleArrayOf((box[0] - dLat).coerceAtLeast(-90.0), (box[1] - dLon).coerceAtLeast(-180.0), (box[2] + dLat).coerceAtMost(90.0), (box[3] + dLon).coerceAtMost(180.0))
    }
}

/** One row of the seeded-areas list: the line to show and the area id. No name of a place, no coordinates. */
data class SeedAreaRow(val id: String, val text: String, val colour: Int = 0)

object SeedAreaList {
    const val EDIT_NOTE = "Drag the handles to change the box, or drag inside it to move it. Places already fetched are kept (nothing is deleted, also not when the box gets smaller); only the cells that are not fresh are fetched."
    const val ATTRIBUTION = "Place text from Wikipedia (CC BY-SA), Wikidata (CC0) and (c) OpenStreetMap contributors (ODbL). Licence names are from the projects' standard terms."
    /** The pure outcome of [truth]: the area status to show, the cells that did not seed, whether it was cancelled and how many stored cells have no OSM extras. */
    data class Truth(val status: String, val failed: Int, val cancelled: Boolean, val noOsm: Int)

    /** Above this many cells the stored-cell check is skipped (a huge area must not allocate a key per cell on every screen read). */
    const val TRUTH_MAX_CELLS = 20_000L

    /**
     * What an area truly is, from its stored cells and its work state, never from a promise: a cell stored OK or PARTIAL counts as seeded (PARTIAL is DONE for seeding, owner ruling 27 Nov).
     * [wanted] = the cell keys of the area, [stored] = cell key -> stored status (null = could not be read). Live work is left alone. Otherwise: every cell stored = DONE; else a FAILED run keeps its
     * own count (or the number of cells missing), a cancelled run is cancelled, and anything else (an area seeded by an older build) says how many cells did not seed. No automatic retry is ever implied.
     */
    fun truth(status: String, live: Boolean, phase: com.rokid.context.phone.place.SeedPhase?, phaseFailed: Int, wanted: Collection<String>, stored: Map<String, String>?): Truth {
        if (live) return Truth(status, 0, false, 0)
        val ok = stored?.let { m -> wanted.count { m[it] == com.rokid.context.memory.place.CELL_OK || m[it] == com.rokid.context.memory.place.CELL_PARTIAL } }
        val noOsm = stored?.let { m -> wanted.count { m[it] == com.rokid.context.memory.place.CELL_PARTIAL } } ?: 0
        if (ok != null && wanted.isNotEmpty() && ok == wanted.size) return Truth(AREA_DONE, 0, false, noOsm)
        if (status == AREA_DONE) return Truth(AREA_DONE, 0, false, noOsm)
        val missing = if (ok != null && wanted.isNotEmpty()) wanted.size - ok else 0
        if (phase == com.rokid.context.phone.place.SeedPhase.CANCELLED) return Truth(AREA_PARTIAL, 0, true, 0)
        return Truth(AREA_PARTIAL, if (phaseFailed > 0) phaseFailed else missing, false, 0)
    }

    private fun cellsText(n: Int) = "$n cell${if (n == 1) "" else "s"}"

    /** The state in words, from one shared source (the live seed text when seeding, so Pause zones and Map zones always read the same). */
    fun statusText(a: SeedAreaInfo): String = a.seed?.text ?: when {
        a.failedCells > 0 -> "FAILED: ${cellsText(a.failedCells)} did not seed: tap Retry failed cells"
        a.cancelled -> "Cancelled: seed again to resume"
        SeedOverlays.look(a.status) == AreaLook.DONE -> "Done" + (SeedTally.noOsmLine(a.noOsm)?.let { " · $it" } ?: "")
        else -> "Not finished: tap Seed again"
    }
    fun name(index: Int, a: SeedAreaInfo): String = a.name?.takeIf { it.isNotBlank() } ?: "Seeded area ${index + 1}"
    fun cellCount(a: SeedAreaInfo): Long = SeedEstimate.boxOf(a.box)?.let { SeedEstimate.cells(it) } ?: 0L
    /** "Photos: 412 of 980, 37 without a photo" (+ " (waiting for free Wi-Fi)"): counts only; null when the area has no places or nothing is known. */
    fun photosText(a: SeedAreaInfo): String? = a.photos?.takeIf { it.total > 0 }?.let {
        com.rokid.context.phone.place.PhotoStageText.line(a.seed != null, it.done, it.total, it.matched, it.toMatch, it.note)
            ?: ("Photos: ${it.done} of ${it.total}, ${it.none} without a photo" + if (it.toMatch > 0) " · Matched ${it.matched} of ${it.toMatch}" else "")
    }
    /** True while some area still has places without a decided photo: the list then offers "Retry photos". */
    fun photosIncomplete(areas: List<SeedAreaInfo>): Boolean = areas.any { it.photos?.complete == false }
    /** "Retry photos" is always there once an area has photo counts: it also queues the matching of photos that are downloaded but not matched. */
    fun photosActionable(areas: List<SeedAreaInfo>): Boolean = areas.any { (it.photos?.total ?: 0) > 0 }
    fun row(index: Int, a: SeedAreaInfo, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val parts = mutableListOf(name(index, a), statusText(a))
        photosText(a)?.let { parts += it }
        a.places?.let { parts += "$it place${if (it == 1) "" else "s"}" }
        val c = cellCount(a); if (c > 0) parts += cellsText(c.toInt())
        if (a.places != null) parts += SeedEstimate.sizeText(a.bytes)
        if (a.seededAt > 0) parts += java.time.Instant.ofEpochMilli(a.seededAt).atZone(zone).toLocalDate().toString()
        return parts.joinToString(" · ")
    }
    /** The one-line header above the list: "2 seeded areas · 120 places stored" (counts only; places are summed per area). */
    fun header(areas: List<SeedAreaInfo>): String {
        val places = areas.sumOf { it.places ?: 0 }
        return "${areas.size} seeded area${if (areas.size == 1) "" else "s"} · $places place${if (places == 1) "" else "s"} stored"
    }
    /** "N manual places · M seeded places" (the Place Guide header), shown above the list on both zones screens. */
    fun counts(manual: Int, seeded: Int): String = com.rokid.context.phone.ui.settings.place.PlaceGuideModel.countsHeader(manual, seeded)
    /** The progress area: every active seed, then every area that ended with failed cells (so a failure stays visible with its Retry button). */
    fun progress(areas: List<SeedAreaInfo>): List<SeedProgressRow> = areas.mapNotNull { x -> x.seed?.let { SeedProgressRow(x.id, it.text) } ?: x.takeIf { it.failedCells > 0 }?.let { SeedProgressRow(it.id, statusText(it), it.failedCells) } }
    fun rows(areas: List<SeedAreaInfo>): List<SeedAreaRow> = areas.mapIndexed { i, a -> SeedAreaRow(a.id, row(i, a), SeedPalette.resolve(a.savedColour, i)) }
}

/** One line of the progress area: an active seed (Cancel) or a FAILED one ([failed] > 0: "Retry failed cells"). */
data class SeedProgressRow(val id: String, val text: String, val failed: Int = 0)

/** The seed side of the zones map. It only reads areas and pins and calls the existing seed; it never writes a pause zone. */
class SeedMapViewModel(private val control: SeedMapControl, private val work: CoroutineDispatcher = Dispatchers.IO, private val settleMs: Long = SETTLE_MS) : ViewModel() {
    private val _state = MutableStateFlow(SeedMapUiState())
    val state: StateFlow<SeedMapUiState> = _state
    private var pendingBox: GeoBox? = null
    private var infos: List<SeedAreaInfo> = emptyList()

    /** The raw areas (for fitting the map). */
    fun areaInfos(): List<SeedAreaInfo> = infos

    /** A row tap: selects the area (highlight and name on the map) and asks the screen to scroll to the map and fit it. A second tap on the same row keeps it selected and fits again. */
    fun focusArea(id: String) {
        val a = infos.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(selectedId = id, focusBox = SeedFocus.fitBox(a.box), focusSeq = it.focusSeq + 1) }
    }

    /** "Edit box" on an area row: the area's rectangle goes into the draft box (the screens watch [SeedMapUiState.editSeq]) and the next "Seed this box" re-seeds THIS area with the new rectangle. Not while it is seeding. */
    fun editArea(id: String) {
        val a = infos.firstOrNull { it.id == id } ?: return
        if (a.seed != null || _state.value.seeding) { _state.update { it.copy(message = "This area is being seeded. Wait for it to finish or cancel it first.", messageIsError = true) }; return }
        _state.update { it.copy(selectedId = id, focusBox = SeedFocus.fitBox(a.box), focusSeq = it.focusSeq + 1, editingId = id, editBox = a.box, editSeq = it.editSeq + 1, message = null) }
    }

    private var viewBox: GeoBox? = null
    private var dotsJob: kotlinx.coroutines.Job? = null

    /**
     * The map's view settled (the screen calls this after the view stopped moving): reads the stored places inside it, once, when the switch is on and the view is close enough. Counts and coordinates only
     * (no text), at most [SeedDots.CAP] + 1 rows; nothing is read per frame, and nothing is logged.
     */
    fun viewportSettled(box: GeoBox?) { viewBox = box; loadDots(settleMs) }

    private fun loadDots(holdMs: Long = 0) {
        dotsJob?.cancel()
        val box = viewBox
        val on = _state.value.showPlaces
        if (!on || box == null) { _state.update { if (it.dots.isEmpty() && !it.dotsCapped && !it.dotsTooFar) it else it.copy(dots = emptyList(), dotsCapped = false, dotsTooFar = false) }; return }
        if (!SeedDots.closeEnough(box)) { _state.update { it.copy(dots = emptyList(), dotsCapped = false, dotsTooFar = true) }; return }
        dotsJob = viewModelScope.launch(work) {
            if (holdMs > 0) com.rokid.context.phone.ui.settings.settleDelay(holdMs) // a moving view reads nothing: only the view that held still
            val l = runCatching { control.dots(box, SeedDots.CAP + 1) }.getOrDefault(emptyList())
            _state.update { it.copy(dots = l.take(SeedDots.CAP), dotsCapped = l.size > SeedDots.CAP, dotsTooFar = false) }
        }
    }

    /** The "Show seeded places" switch: saved on the phone; off removes the dots at once, on reads the view again. */
    fun setShowPlaces(on: Boolean) {
        _state.update { it.copy(showPlaces = on, dotLabel = null, dotSelectedId = null) }
        viewModelScope.launch(work) { runCatching { control.setShowPlaces(on) } }
        loadDots()
    }

    /** A tap on a dot shows its small label (looked up by id, nothing heavy opens); a tap elsewhere ([id] null) hides it. */
    fun tapDot(id: String?) {
        if (id == null) { _state.update { if (it.dotLabel == null && it.dotSelectedId == null) it else it.copy(dotLabel = null, dotSelectedId = null) }; return }
        _state.update { it.copy(dotSelectedId = id) }
        viewModelScope.launch(work) {
            val t = runCatching { control.dotLabel(id) }.getOrNull()
            _state.update { if (it.dotSelectedId != id) it else it.copy(dotLabel = t?.let { x -> DotLabel(id, x) }) }
        }
    }

    /** Leaves "Edit box" (Cancel, Clear box): the area is untouched. */
    fun endEdit() = _state.update { it.copy(editingId = null, editBox = null, clearDraftSeq = it.clearDraftSeq + 1) }

    /** A tap on the map background clears the highlight (the map keeps its view). */
    fun clearFocus() = _state.update { if (it.selectedId == null) it else it.copy(selectedId = null, focusBox = null) }

    /** The owner picked colour [index] (a palette index) for the area: saved, then the overlay and the row redraw in it. */
    fun setColour(id: String, index: Int) {
        if (index !in SeedPalette.colours.indices) return
        viewModelScope.launch(work) { runCatching { control.setColour(id, index) }; refresh() }
    }

    fun refresh() {
        viewModelScope.launch(work) {
            val a = runCatching { control.areas() }.getOrDefault(emptyList())
            val p = runCatching { control.pins() }.getOrDefault(emptyList())
            val r = runCatching { control.rates() }.getOrDefault(SeedRates.ROUGH)
            infos = a
            val show = runCatching { control.showPlaces() }.getOrDefault(true)
            _state.update { it.copy(showPlaces = show) }
            _state.update { it.copy(areas = SeedOverlays.of(a), rows = SeedAreaList.rows(a), header = SeedAreaList.header(a).takeIf { a.isNotEmpty() }, seededPlaces = a.sumOf { x -> x.places ?: 0 }, photoRetry = SeedAreaList.photosActionable(a), rates = r, pins = p, progress = SeedAreaList.progress(a)).keepSelection(a) }
            loadDots()
            if (a.any { it.seed != null || it.photos?.busy == true }) startPoll()
        }
    }

    private fun SeedMapUiState.keepSelection(a: List<SeedAreaInfo>): SeedMapUiState {
        val s = if (selectedId != null && a.none { it.id == selectedId }) copy(selectedId = null, focusBox = null) else this
        return if (s.editingId != null && a.none { it.id == s.editingId }) s.copy(editingId = null, editBox = null) else s
    }

    private var poll: kotlinx.coroutines.Job? = null
    /** While a background seed is active the rows are re-read every few seconds; it stops when none is (and with the screen: viewModelScope). */
    private fun startPoll() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch(work) {
            com.rokid.context.phone.ui.settings.seedTicks(POLL_MS).takeWhile {
                val a = runCatching { control.areas() }.getOrDefault(emptyList())
                infos = a
                _state.update { it.copy(areas = SeedOverlays.of(a), rows = SeedAreaList.rows(a), header = SeedAreaList.header(a).takeIf { a.isNotEmpty() }, seededPlaces = a.sumOf { x -> x.places ?: 0 }, photoRetry = SeedAreaList.photosActionable(a), progress = SeedAreaList.progress(a)).keepSelection(a) }
                a.any { it.seed != null || it.photos?.busy == true }
            }.collect {}
            loadDots() // the seed ended: its new places show
        }
    }

    /** Cancel on a row or in the sheet: the work stops, the area stays (partial) and seeding it again resumes. */
    /** "Retry photos": starts the photo phase again (free Wi-Fi only; Offline only still stops it). */
    fun retryPhotos() { viewModelScope.launch(work) { runCatching { control.retryPhotos() }; refresh() } }

    /** "Retry failed cells" (row, selected-area card or progress area): queues only the cells that did not seed. */
    fun retryFailed(id: String) {
        viewModelScope.launch(work) {
            val r = runCatching { control.retry(id) }.getOrElse { Result.failure(it) }
            _state.update { it.copy(message = r.fold({ o -> o.message }, { t -> seedFailureText(t) }), messageIsError = r.isFailure, clearDraftSeq = it.clearDraftSeq + 1) }
            refresh()
        }
    }

    /** The draft box is dropped (Clear box, leaving): the screens watch [SeedMapUiState.clearDraftSeq]. */
    fun clearDraft() = _state.update { it.copy(clearDraftSeq = it.clearDraftSeq + 1) }

    fun cancelSeed(id: String) { viewModelScope.launch(work) { runCatching { control.cancelSeed(id) }; refresh() } }

    /** The user taps "Seed places in this box" (or "Seed this box" while editing an area: then it is THAT area that is re-seeded). Too large is refused here; above the confirm threshold it waits for a yes. */
    fun requestSeed(box: GeoBox?) = request(box, _state.value.editingId)

    /** "Seed again" on an area row: the area's own box, never an edit of another area. */
    fun requestSeedAgain(box: GeoBox?) = request(box, null)

    private var pendingEdit: String? = null

    private fun request(box: GeoBox?, edit: String?) {
        if (_state.value.seeding) return
        if (box == null) { _state.update { it.copy(message = "Draw a box on the map first.", messageIsError = true) }; return }
        val n = SeedEstimate.cells(box)
        when (SeedEstimate.level(n)) {
            SeedLevel.EMPTY -> _state.update { it.copy(message = "Draw a box on the map first.", messageIsError = true) }
            SeedLevel.TOO_LARGE -> _state.update { it.copy(message = SeedEstimate.line(box), messageIsError = true) }
            SeedLevel.CONFIRM -> { pendingBox = box; pendingEdit = edit; _state.update { it.copy(confirmCells = n) } }
            SeedLevel.OK -> start(box, edit)
        }
    }

    fun confirmSeed() {
        val b = pendingBox ?: return
        pendingBox = null
        val e = pendingEdit; pendingEdit = null
        _state.update { it.copy(confirmCells = null) }
        start(b, e)
    }

    fun cancelConfirm() { pendingBox = null; pendingEdit = null; _state.update { it.copy(confirmCells = null) } }

    private fun start(box: GeoBox, edit: String? = null) {
        if (_state.value.seeding) return
        _state.update { it.copy(seeding = true, message = "Starting…", messageIsError = false, clearDraftSeq = it.clearDraftSeq + 1) }
        viewModelScope.launch(work) {
            val r = if (edit != null) control.reseed(edit, box) else control.seed(box)
            if (r.isSuccess && edit != null) _state.update { it.copy(editingId = null, editBox = null) }
            val byRemoval = (r.exceptionOrNull() as? com.rokid.context.memory.place.SeedStopped)?.reason == "area removed" // the removal's own message says it
            _state.update { if (byRemoval) it.copy(seeding = false) else it.copy(seeding = false, message = r.fold({ o -> o.message }, { t -> seedFailureText(t) }), messageIsError = r.isFailure) }
            refresh()
        }
    }

    fun askRemove(id: String) = _state.update { it.copy(pendingRemoveId = id, removeWithPlaces = true) }
    fun setRemoveWithPlaces(on: Boolean) = _state.update { it.copy(removeWithPlaces = on) }
    fun cancelRemove() = _state.update { it.copy(pendingRemoveId = null) }

    fun confirmRemove() {
        val id = _state.value.pendingRemoveId ?: return
        val withPlaces = _state.value.removeWithPlaces
        _state.update { it.copy(pendingRemoveId = null) }
        viewModelScope.launch(work) {
            val ok = runCatching { control.remove(id, withPlaces) }.isSuccess
            _state.update { it.copy(message = if (ok) (if (withPlaces) "Seeded area and its places removed." else "Seeded area removed. Its places stay as ordinary fetched places.") else "Could not remove the seeded area.", messageIsError = !ok) }
            refresh()
        }
    }

    /** S4: renames the area (stored label only). A blank name goes back to "Seeded area N". */
    fun rename(id: String, name: String) {
        viewModelScope.launch(work) {
            val ok = runCatching { control.rename(id, name) }.getOrDefault(false)
            _state.update { it.copy(message = if (ok) "Area renamed." else "Could not rename the area.", messageIsError = !ok) }
            refresh()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object { private const val POLL_MS = 2_000L; /** The view must hold still this long before the places in it are read. */ const val SETTLE_MS = 350L }
}
