package com.rokid.context.phone.ui.settings.place

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import com.rokid.context.phone.place.PhotoResult
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PackStats
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.TextFieldRow
import com.rokid.context.work.frames.place.ManualPlace
import com.rokid.context.work.frames.place.ManualResult
import com.rokid.context.work.frames.place.ParentChoice
import com.rokid.context.work.frames.place.PinForm
import com.rokid.context.work.frames.place.PinRefusal
import com.rokid.context.work.frames.place.PinResult
import com.rokid.context.work.frames.place.PlacePinning
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The pin dialog: [editingId] null = a new pin at the current position ([parents] are the nearby sites it may hang under), else an edit of that pin. */
data class PinDialogState(val editingId: String? = null, val parents: List<ParentChoice> = emptyList(), val name: String = "", val kindIndex: Int = 0, val radius: String = UserPlace.DEFAULT_RADIUS_M.toString(), val parentIndex: Int = 0, val note: String = "", val error: String? = null,
    /** Slice 1.4.2: the reference Moment attached to a NEW pin (or shown on an edit), its thumbnail source, a hint line, and the two steps of "Save without a Moment". */
    val momentId: String? = null, val moment: MomentChoice? = null, val momentHint: String? = null, val askSkipMoment: Boolean = false, val skipMoment: Boolean = false)

/** Developer "Add place manually": what was typed (user data, memory only, never logged), the kind picker index, and the form's error line. */
data class ManualFormState(
    val name: String = "", val note: String = "", val kindIndex: Int = 0, val coordinates: String = "", val radius: String = "", val error: String? = null,
    /** The optional reference photo: the pasted link (memory only), the checked and re-encoded photo, and whether a fetch or the save is running. */
    val photoLink: String = "", val photo: com.rokid.context.work.frames.place.PreparedPhoto? = null, val photoBusy: Boolean = false,
    /** The photo's own line (under the link field): the fixed reason of the last fetch or pick, and whether Add found a link it could not fetch (then "Add without photo" is offered). */
    val photoError: String? = null, val askNoPhoto: Boolean = false,
)

/** Developer "Add photo" on a pin that needs a Moment: the pin, what was typed (memory only), the checked photo, whether a fetch or the save runs, and the photo's line. */
data class PinPhotoState(val pinId: String, val link: String = "", val photo: com.rokid.context.work.frames.place.PreparedPhoto? = null, val busy: Boolean = false, val error: String? = null)

/** Place object S2, mode (a): the open "Delete place?" confirmation: the pin and how many Moments it points at (the reference Moment plus the card links). Names no coordinate. */
data class PinDeleteConfirm(val pinId: String, val name: String, val momentCount: Int,
    /** Place object S3, mode (b): the preview of "the place and its Moments"; null = the choice is hidden (`forgetV2` OFF or 0 Moments). */
    val cascade: com.rokid.context.work.frames.place.PlaceDelete.Preview? = null, val cascadeChosen: Boolean = false, val acknowledged: Boolean = false, val running: Boolean = false)

/** A photo Moment as the pin screen shows it: its id (whatever created it), the photo path for the thumbnail and its capture time. */
data class MomentChoice(val id: String, val path: String, val atMs: Long)

/** The "Pick from recent Moments" list. [pinId] null = for the pin dialog (a new pin), else that existing pin. */
data class MomentPicker(val pinId: String?, val items: List<MomentChoice>, /** Local midnight of the day shown (epoch ms). */ val dayStartMs: Long = 0L, val hasMore: Boolean = false)

data class PlaceGuideUiState(
    val enabled: Boolean = false, val cardSide: com.rokid.context.core.model.PlaceSide = com.rokid.context.core.model.PlaceSide.CENTRE, val developerMode: Boolean = false, val seeding: Boolean = false, val seedLines: List<String> = emptyList(), val message: String? = null, val messageIsError: Boolean = false, val packSummary: String = "",
    val stats: PackStats? = null, val budgetMb: Int = 50, val confirmClear: Boolean = false,
    val pins: List<UserPlace> = emptyList(), val moments: Map<String, MomentChoice> = emptyMap(), val momentPicker: MomentPicker? = null, val pinBusy: Boolean = false, val pinMessage: String? = null, val pinMessageIsError: Boolean = false, val pinDialog: PinDialogState? = null, val pinDeleteConfirm: PinDeleteConfirm? = null,
    /** B2: the phone-only place card history (newest first), how many rows the list shows, and the open detail. */
    val history: List<PlaceHistoryRowUi> = emptyList(), val historyShown: Int = PlaceHistoryModel.PAGE, val historyDetail: PlaceHistoryDetail? = null,
    /** The history Developer flag (off by default): the list collapses to one line. */
    val historyOn: Boolean = true,
    /** V2b "Offline place guess (Maybe)": the user's switch (default OFF) and its two Advanced fields (cut-off, candidate radius in metres). */
    /** 27 Nov: the "Extra place types" switches that are ON (all ON by default; see ExtraPlaceTypes). */
    val extraTypes: Set<String> = com.rokid.context.connectors.place.ExtraPlaceTypes.DEFAULT_ON,
    val confirmedOnly: Boolean = true, val pinCardsNearby: Boolean = false, val checkMode: com.rokid.context.work.frames.place.CheckMode = com.rokid.context.work.frames.place.CheckMode.AUTOMATIC,
    val photoMatch: com.rokid.context.memory.retrieval.PhotoMatchConfig = com.rokid.context.memory.retrieval.PhotoMatchConfig.DEFAULT, val offlineModelReady: Boolean = false,
    val layerPhoto: Boolean = true, val layerOnline: Boolean = true, val layerOffline: Boolean = true, val multiFrame: Boolean = true, val bestOfK: Boolean = false, val showAllAttempts: Boolean = false,
    val offlineGuess: Boolean = true, val maybeCutoff: Float = com.rokid.context.work.frames.place.OfflineTier.DEFAULT_CUTOFF, val candidateRadiusM: Int = com.rokid.context.work.frames.place.OfflineTier.DEFAULT_RADIUS_M,
    /** Developer only: the open "Add place manually" form (null = closed). */
    val manualForm: ManualFormState? = null,
    /** Developer only: the open "Add photo" dialog of one pin (null = closed). */
    val pinPhoto: PinPhotoState? = null,
    /** Developer reference photos: for the pins whose Moment is a photo, whether its image is indexed yet (Moment id to indexed). */
    val refIndexed: Map<String, Boolean> = emptyMap(),
)

/**
 * A named seed area for a trip: one or more [boxes] (seeded one after the other), its distinct 5 km cell count shown before seeding, and the short label that says the box is approximate.
 * [help] is the only rule text the app shows ("Check local photography rules."): the rules themselves are in docs/ only.
 */
data class SeedPreset(val id: String, val name: String, val boxes: List<GeoBox>, val help: String = "") {
    val cellCount: Int get() = boxes.flatMap { Geohash5.cellsCovering(it) }.toSet().size
    val buttonLabel: String get() = "Seed $name ($cellCount cells)"
    val note: String get() = if (help.isEmpty()) APPROXIMATE else "$APPROXIMATE $help"
    companion object { const val APPROXIMATE = "Approximate: UNVERIFIED." }
}

/** Roaming data saver: a user-started seed (hundreds of requests) was refused because the network is not free Wi-Fi. */
class SeedNeedsWifiException : Exception("Seeding needs Wi-Fi (data saver). Connect to Wi-Fi and try again.")

/** Model privacy "Offline only": a user-started seed was refused because no place may be fetched from the internet. */
class SeedOfflineOnlyException : Exception("Offline only is on, so places are not fetched. Switch Model privacy to Automatic to seed an area.")

/** The screen text for a failed seed: the Wi-Fi and Offline-only refusals say so, a seed stopped mid-way says "stopped: <reason>", anything else is the generic connection hint. */
internal fun seedFailureText(t: Throwable): String = when {
    t is SeedNeedsWifiException || t is SeedOfflineOnlyException -> t.message.orEmpty()
    t is com.rokid.context.memory.place.SeedStopped -> "Seeding stopped: ${t.reason}. Places fetched so far are kept; seed the area again to finish."
    else -> "Could not fetch places. Check the connection and try again."
}

/** One box's seed answer: the screen text and how many cells still failed after the retry (0 = nothing to retry). */
data class SeedOutcome(val message: String, val failedCells: Int = 0)

/** What the screen needs from the pack and the switch (production: [PlaceGraph], [PlaceGuideSettings]; tests: fakes). */
interface PlaceGuideControl {
    var enabled: Boolean
    suspend fun seed(box: GeoBox): Result<SeedOutcome>
    /** S3: the background seeds still to finish (queued, running NN%, waiting for a network). */
    suspend fun seedStates(): List<com.rokid.context.phone.place.SeedAreaState> = emptyList()
    /** S3: cancels every active background seed (the areas stay, partial; seeding again resumes). */
    suspend fun cancelSeeds() {}
    suspend fun summary(): String
    /** The pack's counts and bytes (null = unavailable). */
    suspend fun stats(): PackStats? = null
    /** HUD card position (B2). Not on the wire unless changed from CENTRE. */
    var cardSide: com.rokid.context.core.model.PlaceSide
        get() = com.rokid.context.core.model.PlaceSide.CENTRE
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var budgetMb: Int
        get() = 50
        set(@Suppress("UNUSED_PARAMETER") v) {}
    /** V2b: the "Offline place guess (Maybe)" switch (default OFF), the "Maybe cut-off" and the "Candidate radius" (metres). Stored by the app; a fake keeps them in memory. */
    var offlineGuess: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") v) {}
    /** The "Extra place types" switches that are ON (default: all). A change applies to areas seeded from now on; places already stored are not removed. */
    val extraTypes: Set<String> get() = com.rokid.context.connectors.place.ExtraPlaceTypes.DEFAULT_ON
    fun setExtraType(key: String, on: Boolean) {}
    var maybeCutoff: Float
        get() = com.rokid.context.work.frames.place.OfflineTier.DEFAULT_CUTOFF
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var candidateRadiusM: Int
        get() = com.rokid.context.work.frames.place.OfflineTier.DEFAULT_RADIUS_M
        set(@Suppress("UNUSED_PARAMETER") v) {}
    /** Layered confirmation (27 Nov): "Cards on glasses: only when the photo matches" (default ON), "Also show pin cards when nearby" (default OFF), "How to check the photo" (Automatic by default), the nine Layer 1 numbers (example values, not calibrated). A fake keeps them in memory. */
    var confirmedOnly: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var pinCardsNearby: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var checkMode: com.rokid.context.work.frames.place.CheckMode
        get() = com.rokid.context.work.frames.place.CheckMode.AUTOMATIC
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var photoMatch: com.rokid.context.memory.retrieval.PhotoMatchConfig
        get() = com.rokid.context.memory.retrieval.PhotoMatchConfig.DEFAULT
        set(@Suppress("UNUSED_PARAMETER") v) {}
    fun resetPhotoMatch() {}
    /** The offline photo model (546 MB) is downloaded, so the offline guess can run. */
    val offlineModelReady: Boolean get() = false
    /** Developer switches (all ON by default except best-of-k and show-all-attempts): Layer 1 (kill switch), Layer 2, Layer 3, several frames per glance, best-of-k instead of the mean, show NO_MATCH rows in Activity. */
    var layerPhoto: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var layerOnline: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var layerOffline: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var multiFrame: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var bestOfK: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") v) {}
    var showAllAttempts: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") v) {}
    /** Applies [budgetMb] to the open pack now (evicts least-recently-used fetched places). */
    suspend fun applyBudget(): Int = 0
    /** Removes the fetched places; seeded and pinned ones stay. Returns how many went. */
    suspend fun clearFetched(): Int = 0
    /** User pins; null = not available on this build. */
    val pinning: PlacePinning? get() = null
    /** Developer reference photo: fetches the https [link] through the safe downloader (null-safe: a refusal is a fixed reason). Default: not available. */
    suspend fun fetchPhoto(link: String): PhotoResult = PhotoResult.Fail(com.rokid.context.work.frames.place.PhotoFault.FAILED)
    /** Developer reference photo: reads the picked document (its uri string) with the same checks. Default: not available. */
    suspend fun readPhoto(uri: String): PhotoResult = PhotoResult.Fail(com.rokid.context.work.frames.place.PhotoFault.FAILED)
    /** Developer reference photo: makes the pin's reference Moment from [photo] (description = [description]) and links it. False = nothing left behind, the pin stays without a link. */
    suspend fun addReferencePhoto(pinId: String, photo: com.rokid.context.work.frames.place.PreparedPhoto, description: String): Boolean = false
    /** Developer reference photo: for each Moment id, true = its photo is indexed, false = waiting. A forgotten Moment is missing. */
    suspend fun referenceIndexed(momentIds: List<String>): Map<String, Boolean> = emptyMap()
    /** Slice 1.4.2: the newest photo Moments, newest first, at most [limit] (no text). Empty = none (or not available on this build). */
    suspend fun recentMoments(limit: Int): List<MomentChoice> = emptyList()
    /** The Moments among [ids] that still have a photo (a forgotten one is missing from the map). */
    suspend fun momentsOf(ids: List<String>): Map<String, MomentChoice> = emptyMap()
    /** The photo Moments of one day ([fromMs] inclusive, [toMs] exclusive), newest first, [limit] of them older than [beforeId] (keyset paging, no offset); forgotten ones are never listed. */
    suspend fun momentsBetween(fromMs: Long, toMs: Long, limit: Int, beforeId: String?): List<MomentChoice> = emptyList()
    /** B2: the place card history; null = not available on this build. */
    val history: PlaceHistoryControl? get() = null
    /** Attaches (or with null removes) the Moment link of an existing pin. */
    suspend fun setMoment(pinId: String, momentId: String?): Boolean = false
    /** Clears the links of Moments that were forgotten (read-time repair; the Forget listener does it at the time). */
    suspend fun sweepMoments() {}
    /** Place object S2: how many Moments the pin points at (its card links plus its reference Moment, distinct). 0 = none or not available on this build. */
    suspend fun placeMomentCount(pinId: String): Int = 0
    /** Place object S3: the mode (b) preview, or null = the choice is hidden. */
    suspend fun placeDeletePreview(pinId: String): com.rokid.context.work.frames.place.PlaceDelete.Preview? = null
    /** Place object S3: runs mode (b) exactly as previewed (the pin is removed by mode (a) only after the Forget committed). */
    suspend fun placeDeleteRun(preview: com.rokid.context.work.frames.place.PlaceDelete.Preview, acknowledged: Boolean): com.rokid.context.work.frames.place.PlaceDelete.Result? = null
}

object PlaceGuideModel {
    /** Mode (a) confirmation body (place object design section 3). [moments] = the pin's Moment pointers; 0 drops the link clause. */
    fun deletePlaceText(moments: Int): String {
        val links = if (moments > 0) ", and its links to $moments Moment" + (if (moments == 1) "" else "s") else ""
        return "Its name, note and card history go$links. The Moments, photos and notes stay; they may now be cleaned up automatically like any other photo. This cannot be undone."
    }
    /** Mode (b) body (place object design section 3): counts from the preview, other pins named, scenes, and that it cannot be undone. */
    fun cascadeText(name: String, moments: Int, photos: Int, memories: Int, marks: Int, otherPins: List<String>): String {
        fun n(c: Int, w: String) = "$c $w" + if (c == 1) "" else "s"
        val also = if (otherPins.isEmpty()) "" else " Also deleted: pinned place " + otherPins.joinToString(", ") { "\"$it\"" } + " (" + (if (otherPins.size == 1) "its Moment is" else "their Moments are") + " one of these)."
        return "Forgets ${n(photos, "photo")}, ${n(memories, "note")} and ${n(marks, "mark")} from ${n(moments, "Moment")}, then the place.$also Scenes keep their other Moments. This cannot be undone."
    }
    const val CASCADE_TOO_LARGE = "That is more than 5,000 items: too many to forget at once. Use \"Only the place\", or forget the Moments in Activity first."
    const val SRI_LANKA_HELP = "Check local photography rules."
    /** Charminar and Golconda (Hyderabad): about 11 km by 15 km, about a dozen 5 km cells. */
    val HYDERABAD = GeoBox(17.34, 78.38, 17.41, 78.52)
    /** Ramoji Film City (23 Nov), 17.2543 N 78.6808 E (Wikidata Q1786944), plus or minus 0.01 degree: one 5 km cell (tepfn), outside [HYDERABAD]. */
    val RAMOJI = GeoBox(17.2443, 78.6708, 17.2643, 78.6908)
    /**
     * Trip seed presets (2026-11-18). EVERY box is approximate: UNVERIFIED on the ground. Each is the bounding box of the sites' Wikidata P625 coordinates (read back from wikidata.org on
     * 2026-11-18, ids in the comments) plus 0.01 degree, rounded outward to 3 decimals. That is tighter than the planned 0.05 to 0.1 degree: one 5 km cell is about 0.044 degree, a 0.1 degree
     * box already touches 9 to 16 cells, and a seed of a dozen cells is what Wikipedia's rate limit (HTTP 429) tolerates in about 2 minutes. A preset with distant sites has two boxes,
     * seeded one after the other. Coverage: docs/superpowers/reviews/2026-11/trip-seed-coverage-2026-11-18.md.
     */
    val GANGTOK = SeedPreset("gangtok", "Gangtok and surroundings", listOf(GeoBox(27.278, 88.551, 27.361, 88.638))) // Gangtok Q186141, Enchey Q5375417, Do-drul Chorten Q5285886, Rumtek Q1546234, Banjhakri Falls Q23038925
    val DARJEELING = SeedPreset("darjeeling", "Darjeeling", listOf(GeoBox(27.001, 88.237, 27.059, 88.274), GeoBox(26.984, 88.275, 27.005, 88.296))) // Darjeeling Q169997, Batasia Loop Q4868648, Ghum Monastery Q3320284, Happy Valley Q5653142, Peace Pagoda Q7157413, station Q24949017; Tiger Hill Q16901632
    val WEST_SIKKIM = SeedPreset("west_sikkim", "Pelling/Yuksom (West Sikkim)", listOf(GeoBox(27.29, 88.223, 27.315, 88.263), GeoBox(27.359, 88.198, 27.384, 88.233))) // Pelling Q7161579, Pemayangtse Q7161856; Yuksom Q839046, Khecheopalri Q3214979
    val SIGIRIYA = SeedPreset("sigiriya", "Sri Lanka: Sigiriya/Dambulla", listOf(GeoBox(7.946, 80.749, 7.967, 80.77), GeoBox(7.846, 80.638, 7.867, 80.659)), SRI_LANKA_HELP) // Sigiriya Q272153; Dambulla cave temple Q45690
    val ANURADHAPURA = SeedPreset("anuradhapura", "Sri Lanka: Anuradhapura", listOf(GeoBox(8.325, 80.386, 8.362, 80.421)), SRI_LANKA_HELP) // Anuradhapura Q5724, Ruwanwelisaya Q3534755, Jetavanaramaya Q1961579, Sri Maha Bodhi Q944175
    val POLONNARUWA = SeedPreset("polonnaruwa", "Sri Lanka: Polonnaruwa", listOf(GeoBox(7.923, 80.99, 7.976, 81.015)), SRI_LANKA_HELP) // Polonnaruwa Q394443, Gal Vihara Q5517744, Vatadage Q7225955
    val KANDY = SeedPreset("kandy", "Sri Lanka: Kandy", listOf(GeoBox(7.261, 80.585, 7.304, 80.652)), SRI_LANKA_HELP) // Kandy Q203197, Temple of the Tooth Q289175, Peradeniya botanical garden Q3119056
    val GALLE = SeedPreset("galle", "Sri Lanka: Galle and south coast", listOf(GeoBox(6.017, 80.207, 6.038, 80.228), GeoBox(5.935, 80.419, 5.985, 80.47)), SRI_LANKA_HELP) // Galle Fort Q2507050; Weligama Q3534843, Mirissa Q1345652
    val TRIP_PRESETS: List<SeedPreset> = listOf(GANGTOK, DARJEELING, WEST_SIKKIM, SIGIRIYA, ANURADHAPURA, POLONNARUWA, KANDY, GALLE)
    const val SEED_NOTE = "Fetches place text (Wikipedia, Wikidata, OpenStreetMap) for the area while you are online. Only 5 km cell keys leave the phone, never your position."
    val BUDGET_CHOICES_MB: List<Int> = PlacePack.BUDGET_CHOICES_MB
    const val PIN_NOTE = "Pins use your position right now (it is not stored or logged apart from the pin itself) and are refused inside or near a pause zone. The name and note are yours: they stay on this phone (not in any backup or device transfer; only a settings export you make yourself contains them), and the note is the card text, labelled \"Your note\". Pins are kept by Delete all, are in the settings export, and are never evicted."
    const val CLEAR_NOTE = "Removes places that were fetched while you walked around. Seeded areas and your pins stay."

    /** The seed's answer as the screen says it: cells ok, failed (after the retries) and cells without OSM extras (Overpass did not answer; they are done), then the pack size. */
    fun seedMessage(r: com.rokid.context.memory.place.SeedResult): String {
        val ok = r.ok + r.skipped + r.partial // a cell without OSM extras counts as ok (Wikipedia and Wikidata answered)
        val counts = "Cells: $ok ok, ${r.failed} failed" + (if (r.retried > 0) " (${r.retried} retried)" else "") + (com.rokid.context.memory.place.SeedTally.noOsmLine(r.withoutOsm)?.let { ", $it" } ?: "") + "."
        val tail = if (r.failed > 0) " ${r.failed} of ${r.cells} areas failed, will retry. Seed again when online to retry now." else ""
        return "$counts ${r.places} places in the pack.$tail"
    }

    fun seedOutcome(r: com.rokid.context.memory.place.SeedResult) = SeedOutcome(seedMessage(r), r.failed)

    fun mb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    /** Every user pin reads "manual" wherever a source is shown (the stored mirror value is the same). */
    fun sourceLine(): String = "Source: " + UserPlace.SOURCE

    /** The header of the seeded-areas section and the pack card: counts only. */
    fun countsHeader(manual: Int, seeded: Int): String = "$manual manual place${if (manual == 1) "" else "s"} \u00b7 $seeded seeded place${if (seeded == 1) "" else "s"}"

    const val MANUAL_ZONE = "That spot is inside or near a pause zone, or the zone state is unknown: no place can be added there."
    const val PHOTO_ADDED = "Place added with a reference photo. It is waiting to be indexed; this happens later, while the phone is charging."
    const val PHOTO_NOT_ADDED = "Place added, but the photo could not be saved. The place has no reference photo."
    const val PHOTO_NOTE = "Optional. A photo of the place (a file, or the https link of an image). It becomes a reference Moment of this pin, made now with no location, and is indexed later like any photo. A web photo may not match a real glance at the place (angle, light)."
    const val PHOTO_WAITING = "Photo added, waiting to be indexed"
    const val PHOTO_READY = "Reference photo indexed"
    fun looksLikeHttps(link: String): Boolean = link.trim().let { it.length > 8 && it.startsWith("https://", ignoreCase = true) }
    const val LINK_NOT_HTTPS = "The link must start with https://"
    const val PIN_PHOTO_NONE = "Choose a photo or paste an https link first."
    const val PHOTO_NOT_SAVED = "The photo could not be saved. Nothing was changed."
    const val NO_PHOTO_CHOICE = "The link could not be fetched, so the place was not added. Fix the link, or add the place without the photo."
    fun photoSize(p: com.rokid.context.work.frames.place.PreparedPhoto): String = "Photo ready: ${p.width} x ${p.height}, ${if (p.size >= 1024 * 1024) "%.1f MB".format(java.util.Locale.ROOT, p.size / 1048576.0) else "${(p.size + 1023) / 1024} KB"}"
    const val MANUAL_NOTE = "Developer: adds a real pin at coordinates you type, to try the place guide where you are not. It is a normal pinned place (source: manual) and is deleted like any pin."

    fun kindLabel(kind: String): String = kind.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun refusalText(why: PinRefusal): String = when (why) {
        PinRefusal.ZONE -> "A pause zone is active or near, or its state is unknown: no place can be pinned here."
        PinRefusal.NO_FRESH_FIX -> "No fresh, accurate position yet. Keep the app open outdoors for a moment and try again."
        PinRefusal.DRAFT_EXPIRED -> "That took too long: your position is no longer fresh. Start again."
        PinRefusal.INVALID -> "Check the name (up to 80 characters), the radius (10 to 1500 m) and the note (up to 140 characters)."
        PinRefusal.NOT_STORED -> "The pin could not be saved."
        PinRefusal.MOMENT_GONE -> "That Moment is gone, pick another or save without a Moment."
    }

    /** "Attach latest Moment" takes the newest photo Moment only when it is at most this old. */
    const val LATEST_WINDOW_MS = 30 * 60_000L
    const val PICK_LIMIT = 20
    const val PICK_PAGE = 30
    const val NO_MOMENT_YET = "No Moment yet: capture one with the glasses first."
    const val NEEDS_MOMENT = "Needs a Moment"
    const val NO_MOMENT_NOTE = "No reference Moment. The pin still works by location."
    const val ATTACH_FIRST = "Attach a Moment first (the latest one, or pick from recent Moments), so you can check the capture later."
    const val MOMENT_NOTE = "A Moment is only a reference to check what was captured here: it does not change which place is named. If you Forget that Moment, or Delete all, the pin is deleted with it (you are asked first). If the Moment only expires, the link goes and the pin stays with \"Needs a Moment\"."

    /** The newest Moment of [recent] (newest first) that is at most [LATEST_WINDOW_MS] old at [now]; null = none. */
    fun latestWithin(recent: List<MomentChoice>, now: Long): MomentChoice? = recent.firstOrNull { now - it.atMs <= LATEST_WINDOW_MS }

    fun dayLabel(dayStartMs: Long): String = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(dayStartMs))

    fun momentTime(atMs: Long): String = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(atMs))

    /** A pin shows the "Needs a Moment" badge when it has no link, or its Moment is no longer there (Forgotten). */
    fun needsMoment(p: UserPlace, moments: Map<String, MomentChoice>): Boolean = p.momentId == null || p.momentId !in moments

    val SIDE_LABELS = listOf("Left", "Centre", "Right")
    const val CONFIRMED_TITLE = "Cards on glasses: only when the photo matches"
    const val CONFIRMED_NOTE = "A card appears on the glasses only when the photo from your glasses matches the place, on the phone or by the online check. An offline guess shows as 'Maybe'. Places that are only nearby are listed in Settings > Place cards with the reason."
    const val PIN_NEARBY_TITLE = "Also show pin cards when nearby"
    const val PIN_NEARBY_NOTE = "Your own pin gets its card as soon as you stand inside it, without checking the photo. Off: the pin waits for the photo to match."
    const val CHECK_TITLE = "How to check the photo"
    val CHECK_LABELS = listOf("Automatic", "Online only", "On the phone only")
    const val CHECK_NOTE = "Automatic: on the phone first, then online when the network and Data saver allow, then the offline guess (also when an online call fails). Online only: never the offline guess. On the phone only: nothing is sent online. Model privacy 'Offline only' always wins."
    const val MODEL_NEEDED = "Needs the offline photo model (546 MB). Download it on Wi-Fi in Settings > Models."
    const val THRESH_TITLE = "Photo match numbers (Developer)"
    const val THRESH_NOTE = "Example values, not calibrated. Scores are cosine x 1000. High: accept at once. Floor: below it the place is dropped from the list (0 = off). Margin: the lead needed over the next place. Clamped to 500-999 (margin 0-200). Every change applies to the next photo."
    val THRESH_KINDS = listOf("Own photo" to "own", "Scene centroid" to "centroid", "Web photo" to "web")
    const val SIDE_NOTE = "Where the card sits on the glasses. The visible area of the glasses was not measured: the margins are cautious and the side must be tuned while worn."

    /** V2b ranges: the Maybe cut-off 0.30..0.90 (0.55 default), the candidate radius 50..500 m (150 default). */
    fun clampCutoff(v: Float): Float = if (v.isFinite()) v.coerceIn(com.rokid.context.work.frames.place.OfflineTier.MIN_CUTOFF, com.rokid.context.work.frames.place.OfflineTier.MAX_CUTOFF) else com.rokid.context.work.frames.place.OfflineTier.DEFAULT_CUTOFF
    /** Slider snaps: 0.05 and 50 m grids (both defaults sit on them). */
    const val CUTOFF_STEPS = 11
    const val RADIUS_STEPS = 8
    fun snapCutoff(v: Float): Float = clampCutoff(Math.round(v / 0.05f) * 0.05f)
    fun snapRadius(v: Float): Int = clampRadius(Math.round(v / 50f) * 50)
    fun clampRadius(m: Int): Int = m.coerceIn(com.rokid.context.work.frames.place.OfflineTier.MIN_RADIUS_M, com.rokid.context.work.frames.place.OfflineTier.WIDE_RADIUS_M)
    const val EXTRA_TYPES_NOTE = "On by default. These add places on top of the monuments and sights. Turn one off to leave it out of areas you seed from now on; places already stored stay."
    const val MOUNTAINS_LABEL = "Include mountains and peaks (adds many places; good for a mountain trip)"
    const val OFFLINE_NOTE = "When there is no network and the photo could not be matched, the phone describes the photo itself and may show one card marked \"Maybe\". It is only a guess and is not checked. Needs the offline photo captions (Developer) and the SmolVLM2 download."

    const val ZONE_NOTE = "Place cards stay off whenever a pause zone is active or you are near one."

    /** "south, west, north, east" in degrees; null when not four numbers or not a valid box. */
    fun parseBox(south: String, west: String, north: String, east: String): GeoBox? {
        fun d(s: String) = s.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        val b = GeoBox(d(south) ?: return null, d(west) ?: return null, d(north) ?: return null, d(east) ?: return null)
        return b.takeIf { it.valid && Geohash5.cellsCovering(it).size in 1..PlacePack.MAX_SEED_CELLS }
    }
}

class PlaceGuideViewModel(private val control: PlaceGuideControl, private val work: CoroutineDispatcher = Dispatchers.IO, developerMode: Boolean = false, private val now: () -> Long = System::currentTimeMillis, private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) : ViewModel() {
    private val _state = MutableStateFlow(PlaceGuideUiState(enabled = control.enabled, cardSide = control.cardSide, developerMode = developerMode, budgetMb = control.budgetMb, extraTypes = control.extraTypes, offlineGuess = control.offlineGuess, maybeCutoff = control.maybeCutoff, candidateRadiusM = control.candidateRadiusM,
        confirmedOnly = control.confirmedOnly, pinCardsNearby = control.pinCardsNearby, checkMode = control.checkMode, photoMatch = control.photoMatch, offlineModelReady = control.offlineModelReady,
        layerPhoto = control.layerPhoto, layerOnline = control.layerOnline, layerOffline = control.layerOffline, multiFrame = control.multiFrame, bestOfK = control.bestOfK, showAllAttempts = control.showAllAttempts))
    val state: StateFlow<PlaceGuideUiState> = _state

    init { refreshSummary() }

    /** Where the glasses HUD shows the place card; only offered in Developer mode. */
    fun setCardSide(side: com.rokid.context.core.model.PlaceSide) { control.cardSide = side; _state.update { it.copy(cardSide = side) } }

    fun setEnabled(on: Boolean) { control.enabled = on; _state.update { it.copy(enabled = on) } }

    /** V2b: the user's switch; the screen shows what was stored (a value is clamped to its range on the way in). */
    fun setExtraType(key: String, on: Boolean) { control.setExtraType(key, on); _state.update { it.copy(extraTypes = control.extraTypes) } }
    fun setOfflineGuess(on: Boolean) { control.offlineGuess = on; _state.update { it.copy(offlineGuess = control.offlineGuess) } }
    fun setMaybeCutoff(v: Float) { control.maybeCutoff = PlaceGuideModel.clampCutoff(v); _state.update { it.copy(maybeCutoff = control.maybeCutoff) } }
    fun setConfirmedOnly(on: Boolean) { control.confirmedOnly = on; _state.update { it.copy(confirmedOnly = control.confirmedOnly) } }
    fun setPinCardsNearby(on: Boolean) { control.pinCardsNearby = on; _state.update { it.copy(pinCardsNearby = control.pinCardsNearby) } }
    fun setCheckMode(m: com.rokid.context.work.frames.place.CheckMode) { control.checkMode = m; _state.update { it.copy(checkMode = control.checkMode) } }
    /** The nine numbers: stored clamped, the screen shows what was stored. */
    fun setPhotoMatch(c: com.rokid.context.memory.retrieval.PhotoMatchConfig) { control.photoMatch = c; _state.update { it.copy(photoMatch = control.photoMatch) } }
    fun resetPhotoMatch() { control.resetPhotoMatch(); _state.update { it.copy(photoMatch = control.photoMatch) } }
    fun setLayer(which: String, on: Boolean) {
        when (which) { "photo" -> control.layerPhoto = on; "online" -> control.layerOnline = on; "offline" -> control.layerOffline = on; "multi" -> control.multiFrame = on; "bestk" -> control.bestOfK = on; "all" -> control.showAllAttempts = on }
        _state.update { it.copy(layerPhoto = control.layerPhoto, layerOnline = control.layerOnline, layerOffline = control.layerOffline, multiFrame = control.multiFrame, bestOfK = control.bestOfK, showAllAttempts = control.showAllAttempts) }
        if (which == "all") refreshHistory()
    }
    fun setCandidateRadius(m: Int) { control.candidateRadiusM = PlaceGuideModel.clampRadius(m); _state.update { it.copy(candidateRadiusM = control.candidateRadiusM) } }

    fun seed(box: GeoBox?) {
        if (box == null) { _state.update { it.copy(message = "Type four numbers: south, west, north, east (degrees).", messageIsError = true) }; return }
        if (_state.value.seeding) return
        _state.update { it.copy(seeding = true, message = "Starting…", messageIsError = false) }
        viewModelScope.launch(work) {
            val r = control.seed(box)
            _state.update { it.copy(seeding = false, message = r.fold({ o -> o.message }, { seedFailureText(it) }), messageIsError = r.isFailure) }
            refreshSummary()
        }
    }

    /** Seeds a preset's boxes one after the other (never in parallel: Wikipedia's rate limit), stopping at the first failure or the first box with cells that failed after the retry. */
    fun seedPreset(preset: SeedPreset) {
        if (_state.value.seeding) return
        _state.update { it.copy(seeding = true, message = "Starting…", messageIsError = false) }
        viewModelScope.launch(work) {
            val msgs = ArrayList<String>()
            var failure: Throwable? = null
            var stoppedAt = 0 // 1-based box where a cell still failed after its retry
            for ((i, box) in preset.boxes.withIndex()) {
                val r = control.seed(box)
                r.onSuccess { msgs += it.message; if (it.failedCells > 0) stoppedAt = i + 1 }.onFailure { failure = it }
                if (failure != null || stoppedAt > 0) break
            }
            val n = preset.boxes.size
            val text = when {
                failure != null -> seedFailureText(failure!!)
                stoppedAt > 0 -> "Stopped at box $stoppedAt of $n: some cells failed after a retry (the server may be rate limiting). ${msgs.last()} Tap seed again to retry."
                msgs.size == 1 -> msgs[0]
                else -> msgs.withIndex().joinToString(" ") { "Part ${it.index + 1} of ${msgs.size}: ${it.value}" }
            }
            _state.update { it.copy(seeding = false, message = text, messageIsError = failure != null || stoppedAt > 0) }
            refreshSummary()
        }
    }

    /** One of 25/50/100/200 MB; anything else is ignored. Evicts at once when the pack is over the new budget. */
    fun setBudget(mb: Int) {
        if (mb !in PlaceGuideModel.BUDGET_CHOICES_MB) return
        control.budgetMb = mb
        _state.update { it.copy(budgetMb = mb) }
        viewModelScope.launch(work) {
            val n = runCatching { control.applyBudget() }.getOrDefault(0)
            if (n > 0) _state.update { it.copy(message = "Budget set to $mb MB: $n least recently used places were removed.", messageIsError = false) }
            refreshSummary()
        }
    }

    fun askClear() { _state.update { it.copy(confirmClear = true) } }
    fun cancelClear() { _state.update { it.copy(confirmClear = false) } }
    fun confirmClear() {
        _state.update { it.copy(confirmClear = false) }
        viewModelScope.launch(work) {
            val r = runCatching { control.clearFetched() }
            _state.update { it.copy(message = r.fold({ "Removed $it fetched places. Seeded places and your pins were kept." }, { "Could not clear the fetched places." }), messageIsError = r.isFailure) }
            refreshSummary()
        }
    }

    // ---- user pins ----

    /** "Pin a place here": takes a fresh position (refused inside or near a pause zone, or when the zone state is unknown) and opens the form with the nearby sites. */
    fun beginPin() {
        val pinning = control.pinning ?: return
        if (_state.value.pinBusy) return
        _state.update { it.copy(pinBusy = true, pinMessage = null, pinMessageIsError = false) }
        viewModelScope.launch(work) {
            val r = runCatching { pinning.begin() }.getOrElse { Result.failure(it) }
            r.fold(
                { parents -> _state.update { it.copy(pinBusy = false, pinDialog = PinDialogState(parents = parents)) } },
                { e -> _state.update { it.copy(pinBusy = false, pinMessage = PlaceGuideModel.refusalText((e as? PlacePinning.PinRefused)?.why ?: PinRefusal.NO_FRESH_FIX), pinMessageIsError = true) } },
            )
        }
    }

    fun editPin(id: String) {
        val p = control.pinning?.pins()?.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(pinDialog = PinDialogState(editingId = id, name = p.name, kindIndex = PlacePinning.KINDS.indexOf(p.kind).coerceAtLeast(0), radius = p.radiusM.toString(), note = p.note, momentId = p.momentId, moment = p.momentId?.let { m -> it.moments[m] }), pinMessage = null) }
    }

    fun updatePinDialog(f: (PinDialogState) -> PinDialogState) { _state.update { s -> s.copy(pinDialog = s.pinDialog?.let(f)?.copy(error = null)) } }

    override fun onCleared() { control.pinning?.cancel(); super.onCleared() }

    fun cancelPin() { control.pinning?.cancel(); _state.update { it.copy(pinDialog = null) } }

    /** Developer only: opens the "Add place manually" form. A build or screen without Developer mode never opens it. */
    fun openManual() { if (_state.value.developerMode && control.pinning != null) _state.update { it.copy(manualForm = ManualFormState(), pinMessage = null) } }
    fun cancelManual() { _state.update { it.copy(manualForm = null) } }
    fun updateManual(f: (ManualFormState) -> ManualFormState) { _state.update { s -> s.copy(manualForm = s.manualForm?.let(f)?.copy(error = null, photoError = null, askNoPhoto = false)) } }

    /** One download for the form and the pin dialog: a link that is not https never leaves the phone. */
    private suspend fun loadLink(link: String): PhotoResult =
        if (!PlaceGuideModel.looksLikeHttps(link)) PhotoResult.Fail(com.rokid.context.work.frames.place.PhotoFault.BAD_LINK)
        else runCatching { control.fetchPhoto(link.trim()) }.getOrElse { PhotoResult.Fail(com.rokid.context.work.frames.place.PhotoFault.FAILED) }

    /** Developer only: fetches the pasted link into the form's photo. A refusal or a bad photo is a fixed sentence under the link; nothing of the link is kept after a success. */
    fun fetchManualPhoto() {
        val d = _state.value.manualForm ?: return
        if (!_state.value.developerMode || d.photoBusy || d.photoLink.isBlank()) return
        _state.update { s -> s.copy(manualForm = s.manualForm?.copy(photoBusy = true, error = null, photoError = null, askNoPhoto = false)) }
        viewModelScope.launch(work) { takePhoto(loadLink(d.photoLink), clearLink = true) }
    }

    /** Developer only: the document the picker returned (null = cancelled). It gets the same checks as a link. */
    fun pickManualPhoto(uri: String?) {
        val d = _state.value.manualForm ?: return
        if (uri == null || !_state.value.developerMode || d.photoBusy) return
        _state.update { s -> s.copy(manualForm = s.manualForm?.copy(photoBusy = true, error = null, photoError = null, askNoPhoto = false)) }
        viewModelScope.launch(work) { takePhoto(runCatching { control.readPhoto(uri) }.getOrElse { PhotoResult.Fail(com.rokid.context.work.frames.place.PhotoFault.UNREADABLE) }, clearLink = false) }
    }

    fun removeManualPhoto() { _state.update { s -> s.copy(manualForm = s.manualForm?.takeIf { !it.photoBusy }?.copy(photo = null, error = null, photoError = null, askNoPhoto = false) ?: s.manualForm) } }

    private fun takePhoto(r: PhotoResult, clearLink: Boolean) {
        _state.update { s ->
            val f = s.manualForm ?: return@update s
            s.copy(manualForm = when (r) {
                is PhotoResult.Ok -> f.copy(photo = r.photo, photoBusy = false, photoLink = if (clearLink) "" else f.photoLink, error = null, photoError = null, askNoPhoto = false)
                is PhotoResult.Fail -> f.copy(photoBusy = false, photoError = r.fault.text)
            })
        }
    }

    /** "Add": a typed link that was not fetched yet is fetched first; when that fails no pin is made (the form offers "Add without photo" or a fixed link). */
    fun saveManual() = save(withoutPhoto = false)
    /** "Add without photo": the link and any photo are ignored. */
    fun saveManualWithoutPhoto() = save(withoutPhoto = true)

    private fun save(withoutPhoto: Boolean) {
        val pinning = control.pinning ?: return
        val d = _state.value.manualForm ?: return
        if (!_state.value.developerMode || d.photoBusy) return
        val link = d.photoLink.trim()
        val fetchFirst = !withoutPhoto && d.photo == null && link.isNotEmpty()
        val photo = if (withoutPhoto) null else d.photo
        _state.update { s -> s.copy(manualForm = s.manualForm?.copy(photoBusy = photo != null || fetchFirst, error = null, photoError = null, askNoPhoto = false)) } // while the photo is fetched or the Moment is written the form is locked
        val kind = ManualPlace.KINDS.getOrElse(d.kindIndex) { ManualPlace.KINDS.first() }
        viewModelScope.launch(work) {
            var use = photo
            if (fetchFirst) {
                when (val r = loadLink(link)) {
                    is PhotoResult.Ok -> { use = r.photo; _state.update { s -> s.copy(manualForm = s.manualForm?.copy(photo = r.photo, photoLink = "")) } }
                    is PhotoResult.Fail -> { _state.update { s -> s.copy(manualForm = s.manualForm?.copy(photoBusy = false, photoError = r.fault.text, askNoPhoto = true)) }; return@launch } // no pin without the photo the user asked for
                }
            }
            val res = runCatching { ManualPlace.create(pinning, d.name, d.note, kind, d.coordinates, d.radius) }.getOrElse { ManualResult.Refused(PinRefusal.NOT_STORED) }
            when (res) {
                is ManualResult.Saved -> {
                    // the photo, when there is one: the pin exists already; a failed Moment write leaves it without a link and nothing else behind
                    val linked = use?.let { runCatching { control.addReferencePhoto(res.place.id, it, d.note) }.getOrDefault(false) }
                    val msg = when (linked) { null -> "Place added."; true -> PlaceGuideModel.PHOTO_ADDED; false -> PlaceGuideModel.PHOTO_NOT_ADDED }
                    _state.update { it.copy(manualForm = null, pins = pinning.pins(), pinMessage = msg, pinMessageIsError = linked == false) }
                }
                is ManualResult.Invalid -> _state.update { s -> s.copy(manualForm = s.manualForm?.copy(photoBusy = false, error = ManualPlace.faultText(res.fault))) }
                is ManualResult.Refused -> _state.update { s -> s.copy(manualForm = s.manualForm?.copy(photoBusy = false, error = if (res.why == PinRefusal.ZONE) PlaceGuideModel.MANUAL_ZONE else PlaceGuideModel.refusalText(res.why))) }
            }
            refreshSummary()
        }
    }

    // ---- Developer "Add photo" on an existing pin that needs a Moment ----
    fun openPinPhoto(pinId: String) {
        val s = _state.value
        if (!s.developerMode || control.pinning == null || s.pins.none { it.id == pinId } || !PlaceGuideModel.needsMoment(s.pins.first { it.id == pinId }, s.moments)) return
        _state.update { it.copy(pinPhoto = PinPhotoState(pinId), pinMessage = null) }
    }
    fun cancelPinPhoto() { _state.update { s -> s.copy(pinPhoto = s.pinPhoto?.takeIf { it.busy }) } }
    fun updatePinPhoto(f: (PinPhotoState) -> PinPhotoState) { _state.update { s -> s.copy(pinPhoto = s.pinPhoto?.let(f)?.copy(error = null)) } }
    fun removePinPhoto() { _state.update { s -> s.copy(pinPhoto = s.pinPhoto?.takeIf { !it.busy }?.copy(photo = null, error = null) ?: s.pinPhoto) } }

    private fun takePinPhoto(r: PhotoResult, clearLink: Boolean) {
        _state.update { s ->
            val f = s.pinPhoto ?: return@update s
            s.copy(pinPhoto = when (r) {
                is PhotoResult.Ok -> f.copy(photo = r.photo, busy = false, link = if (clearLink) "" else f.link, error = null)
                is PhotoResult.Fail -> f.copy(busy = false, error = r.fault.text)
            })
        }
    }

    fun fetchPinPhoto() {
        val d = _state.value.pinPhoto ?: return
        if (!_state.value.developerMode || d.busy || d.link.isBlank()) return
        _state.update { s -> s.copy(pinPhoto = s.pinPhoto?.copy(busy = true, error = null)) }
        viewModelScope.launch(work) { takePinPhoto(loadLink(d.link), clearLink = true) }
    }

    fun pickPinPhoto(uri: String?) {
        val d = _state.value.pinPhoto ?: return
        if (uri == null || !_state.value.developerMode || d.busy) return
        _state.update { s -> s.copy(pinPhoto = s.pinPhoto?.copy(busy = true, error = null)) }
        viewModelScope.launch(work) { takePinPhoto(runCatching { control.readPhoto(uri) }.getOrElse { PhotoResult.Fail(com.rokid.context.work.frames.place.PhotoFault.UNREADABLE) }, clearLink = false) }
    }

    /** "Add photo": a typed link not fetched yet is fetched first; then exactly one reference Moment is made and linked (the pin's note is its description). A failure leaves nothing behind and keeps the dialog open. */
    fun savePinPhoto() {
        val d = _state.value.pinPhoto ?: return
        val pin = _state.value.pins.firstOrNull { it.id == d.pinId } ?: return
        if (!_state.value.developerMode || d.busy) return
        val link = d.link.trim()
        if (d.photo == null && link.isEmpty()) { _state.update { s -> s.copy(pinPhoto = s.pinPhoto?.copy(error = PlaceGuideModel.PIN_PHOTO_NONE)) }; return }
        _state.update { s -> s.copy(pinPhoto = s.pinPhoto?.copy(busy = true, error = null)) }
        viewModelScope.launch(work) {
            var use = d.photo
            if (use == null) {
                when (val r = loadLink(link)) {
                    is PhotoResult.Ok -> { use = r.photo; _state.update { s -> s.copy(pinPhoto = s.pinPhoto?.copy(photo = r.photo, link = "")) } }
                    is PhotoResult.Fail -> { _state.update { s -> s.copy(pinPhoto = s.pinPhoto?.copy(busy = false, error = r.fault.text)) }; return@launch }
                }
            }
            val ok = runCatching { control.addReferencePhoto(pin.id, use!!, pin.note) }.getOrDefault(false)
            if (ok) _state.update { it.copy(pinPhoto = null, pinMessage = PlaceGuideModel.PHOTO_ADDED, pinMessageIsError = false) }
            else _state.update { s -> s.copy(pinPhoto = s.pinPhoto?.copy(busy = false, error = PlaceGuideModel.PHOTO_NOT_SAVED)) }
            refreshSummaryNow()
        }
    }

    fun savePin() {
        val pinning = control.pinning ?: return
        val d = _state.value.pinDialog ?: return
        val radius = d.radius.trim().toIntOrNull()
        if (radius == null || d.name.isBlank()) { _state.update { it.copy(pinDialog = d.copy(error = PlaceGuideModel.refusalText(PinRefusal.INVALID))) }; return }
        // Slice 1.4.2: a NEW pin needs a Moment unless the user explicitly chose "Save without a Moment" (an edit of an existing pin never blocks)
        if (d.editingId == null && d.momentId == null && !d.skipMoment) { _state.update { it.copy(pinDialog = d.copy(error = PlaceGuideModel.ATTACH_FIRST)) }; return }
        val kind = PlacePinning.KINDS.getOrElse(d.kindIndex) { PlacePinning.KINDS.first() }
        viewModelScope.launch(work) {
            val res = if (d.editingId != null) {
                val old = pinning.pins().firstOrNull { it.id == d.editingId }
                pinning.edit(d.editingId, PinForm(d.name, kind, radius, old?.parentId, d.note, d.momentId))
            } else pinning.create(PinForm(d.name, kind, radius, d.parents.getOrNull(d.parentIndex - 1)?.id, d.note, d.momentId))
            when (res) {
                is PinResult.Saved -> _state.update { it.copy(pinDialog = null, pins = pinning.pins(), pinMessage = "Saved.", pinMessageIsError = false) }.also { refreshSummary() }
                is PinResult.Refused -> _state.update { s ->
                    // a refused zone or expired position ends the dialog (the position is gone); a bad form stays open to fix
                    if (res.why == PinRefusal.MOMENT_GONE) s.copy(pinDialog = d.copy(momentId = null, moment = null, error = PlaceGuideModel.refusalText(res.why))) // the Moment was forgotten while the dialog was open
                    else if (res.why == PinRefusal.INVALID || res.why == PinRefusal.NOT_STORED) s.copy(pinDialog = d.copy(error = PlaceGuideModel.refusalText(res.why)))
                    else s.copy(pinDialog = null, pinMessage = PlaceGuideModel.refusalText(res.why), pinMessageIsError = true)
                }
            }
            refreshSummary()
        }
    }

    /** Mode (a), step 1: "Delete" only opens the confirmation (with the live count of the pin's Moments); nothing is removed yet. */
    fun deletePin(id: String) {
        val pin = control.pinning?.pins()?.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(work) {
            val n = runCatching { control.placeMomentCount(id) }.getOrDefault(0)
            val cascade = runCatching { control.placeDeletePreview(id) }.getOrNull() // null = forgetV2 OFF, 0 Moments or a failure: the second choice stays hidden
            _state.update { it.copy(pinDeleteConfirm = PinDeleteConfirm(id, pin.name, n, cascade)) }
        }
    }

    fun cancelDeletePin() { _state.update { if (it.pinDeleteConfirm?.running == true) it else it.copy(pinDeleteConfirm = null) } }

    /** S3: picks mode (a) (false, the default) or mode (b) (true; only when the preview exists). */
    fun chooseDeleteMode(cascade: Boolean) { _state.update { s -> s.copy(pinDeleteConfirm = s.pinDeleteConfirm?.let { c -> c.copy(cascadeChosen = cascade && c.cascade != null, acknowledged = false) }) } }
    fun acknowledgeDelete(on: Boolean) { _state.update { s -> s.copy(pinDeleteConfirm = s.pinDeleteConfirm?.copy(acknowledged = on)) } }

    /** Mode (a), step 2: removes the pin, its caption (name and note), its card history and its links; every Moment, photo, note and mark stays. */
    fun confirmDeletePin() {
        val c = _state.value.pinDeleteConfirm ?: return
        val id = c.pinId
        val pinning = control.pinning ?: return
        val preview = c.cascade
        if (c.cascadeChosen && preview != null) { // mode (b): only on an acknowledged, not-too-large preview
            if (c.running || preview.tooLarge || (preview.needsAck && !c.acknowledged)) return
            _state.update { it.copy(pinDeleteConfirm = c.copy(running = true)) }
            viewModelScope.launch(work) {
                val r = runCatching { control.placeDeleteRun(preview, c.acknowledged) }.getOrNull()
                val msg = when {
                    r == null -> "Nothing was deleted: it could not be done." to true
                    r.outcome.committed && r.pinDeleted && r.outcome == com.rokid.context.orchestration.forget.ForgetActions.Outcome.DONE -> "Place and its Moments deleted." to false
                    r.outcome.committed && r.pinDeleted -> PlaceDeleteMessages.COMMITTED_CLEANUP_OWED to true
                    r.outcome.committed -> "Moments deleted. The place itself could not be removed: delete it again." to true
                    r.outcome == com.rokid.context.orchestration.forget.ForgetActions.Outcome.CONTENT_CHANGED -> "Your Moments changed. Nothing was deleted: open Delete again." to true
                    r.outcome == com.rokid.context.orchestration.forget.ForgetActions.Outcome.TOO_LARGE -> "Too many items to forget at once. Nothing was deleted." to true
                    else -> "Nothing was deleted: try again." to true
                }
                _state.update { it.copy(pinDeleteConfirm = null, pins = pinning.pins(), pinMessage = msg.first, pinMessageIsError = msg.second) }
                refreshSummary()
            }
            return
        }
        _state.update { it.copy(pinDeleteConfirm = null) }
        viewModelScope.launch(work) {
            val ok = pinning.delete(id)
            _state.update { it.copy(pins = pinning.pins(), pinMessage = if (ok) "Pin deleted." else "The pin could not be deleted.", pinMessageIsError = !ok) }
            refreshSummary()
        }
    }

    // ---- slice 1.4.2: the pin's reference Moment ----

    /** "Attach latest Moment": the newest photo Moment, if it is at most 30 minutes old. [pinId] null = the open pin dialog (a new pin). */
    fun attachLatest(pinId: String?) {
        viewModelScope.launch(work) {
            val recent = runCatching { control.recentMoments(PlaceGuideModel.PICK_LIMIT) }.getOrDefault(emptyList())
            val m = PlaceGuideModel.latestWithin(recent, now())
            if (m != null) applyMoment(pinId, m)
            else momentHint(pinId, if (recent.isEmpty()) PlaceGuideModel.NO_MOMENT_YET else "No Moment in the last 30 minutes. Pick from recent Moments.")
        }
    }

    /** "Pick from recent Moments": opens the list of the last 20 photo Moments (thumbnail and time). */
    fun openMomentPicker(pinId: String?) { loadDay(pinId, dayStart(now()), append = false) }

    /** The picker's day selector: [delta] = -1 previous day, +1 next day (never past today). */
    fun pickerDay(delta: Int) {
        val pk = _state.value.momentPicker ?: return
        val day = java.time.Instant.ofEpochMilli(pk.dayStartMs).atZone(zone).toLocalDate().plusDays(delta.toLong())
        if (day.isAfter(java.time.Instant.ofEpochMilli(now()).atZone(zone).toLocalDate())) return
        loadDay(pk.pinId, day.atStartOfDay(zone).toInstant().toEpochMilli(), append = false)
    }

    /** "Load more": the next page of the same day. */
    fun pickerMore() { val pk = _state.value.momentPicker ?: return; if (pk.hasMore) loadDay(pk.pinId, pk.dayStartMs, append = true) }

    private fun dayStart(ms: Long): Long = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Each load takes a generation number; a result is applied only if it is still the newest and the picker still shows the day it was started for, so a slow earlier load never overwrites a later day. */
    private val pickerGen = java.util.concurrent.atomic.AtomicInteger(0)

    private fun loadDay(pinId: String?, dayStartMs: Long, append: Boolean) {
        val gen = pickerGen.incrementAndGet()
        val before = if (append) _state.value.momentPicker?.takeIf { it.dayStartMs == dayStartMs }?.items?.lastOrNull()?.id else null
        if (append && before == null) return
        viewModelScope.launch(work) {
            val end = java.time.Instant.ofEpochMilli(dayStartMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val page = runCatching { control.momentsBetween(dayStartMs, end, PlaceGuideModel.PICK_PAGE + 1, before) }.getOrDefault(emptyList())
            val more = page.size > PlaceGuideModel.PICK_PAGE
            _state.update { s ->
                val cur = s.momentPicker
                if (gen != pickerGen.get() || (append && (cur == null || cur.dayStartMs != dayStartMs))) s // superseded: a newer day was asked for, or the picker closed
                else s.copy(momentPicker = MomentPicker(pinId, (if (append) cur!!.items else emptyList()) + page.take(PlaceGuideModel.PICK_PAGE), dayStartMs, more))
            }
        }
    }

    fun closeMomentPicker() { pickerGen.incrementAndGet(); _state.update { it.copy(momentPicker = null) } }

    fun pickMoment(m: MomentChoice) {
        val target = _state.value.momentPicker ?: return
        pickerGen.incrementAndGet()
        _state.update { it.copy(momentPicker = null) }
        viewModelScope.launch(work) { applyMoment(target.pinId, m) }
    }

    /** "Remove link": the pin stays, it only has no reference Moment any more. */
    fun removeMoment(pinId: String?) {
        if (pinId == null) { _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(momentId = null, moment = null, momentHint = null)) }; return }
        viewModelScope.launch(work) {
            val ok = runCatching { control.setMoment(pinId, null) }.getOrDefault(false)
            _state.update { it.copy(pinMessage = if (ok) "Moment link removed." else "The link could not be removed.", pinMessageIsError = !ok) }
            refreshSummary()
        }
    }

    /** "Save without a Moment": asks first (a confirm dialog), then saves the new pin flagged "No reference Moment". */
    fun askSkipMoment() { _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(askSkipMoment = true)) } }
    fun cancelSkipMoment() { _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(askSkipMoment = false)) } }
    fun confirmSkipMoment() { _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(askSkipMoment = false, skipMoment = true)) }; savePin() }

    private fun momentHint(pinId: String?, text: String) {
        if (pinId == null) _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(momentHint = text)) }
        else _state.update { it.copy(pinMessage = text, pinMessageIsError = true) }
    }

    private suspend fun applyMoment(pinId: String?, m: MomentChoice) {
        if (pinId == null) { _state.update { s -> s.copy(pinDialog = s.pinDialog?.copy(momentId = m.id, moment = m, momentHint = null, error = null)) }; return }
        val ok = runCatching { control.setMoment(pinId, m.id) }.getOrDefault(false)
        _state.update { it.copy(pinMessage = if (ok) "Moment attached." else "That Moment is gone: pick another.", pinMessageIsError = !ok) }
        refreshSummaryNow()
    }

    private fun refreshSummary() { viewModelScope.launch(work) { refreshSummaryNow() } }

    /** Cancel on the progress line: stops the background seed(s); the screen's lines refresh at once. */
    fun cancelSeeds() { viewModelScope.launch(work) { runCatching { control.cancelSeeds() }; refreshSummaryNow() } }

    private var poll: kotlinx.coroutines.Job? = null
    /** While a background seed is active its % is re-read every few seconds (stops with the screen and when none is active). */
    private fun pollSeeds() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch(work) {
            com.rokid.context.phone.ui.settings.seedTicks(2_000L).takeWhile { refreshSeedLines() != 0 }.collect {}
        }
    }

    private fun lines(s: List<com.rokid.context.phone.place.SeedAreaState>) = s.map { it.text }

    private suspend fun refreshSeedLines(): Int {
        val l = lines(runCatching { control.seedStates() }.getOrDefault(emptyList()))
        _state.update { it.copy(seedLines = l) }
        return l.size
    }

    // ---- B2: the place card history ----

    /** Re-reads the list (and the open detail: a Forget while it is shown closes it, a delivery change updates it). Called when the history changes and on resume (the route collects [historyChanges]); never on a timer. */
    /** The history's change events (empty when this build has no history). */
    val historyChanges: kotlinx.coroutines.flow.Flow<Unit> get() = control.history?.changes ?: kotlinx.coroutines.flow.emptyFlow()

    fun refreshHistory() { viewModelScope.launch(work) { refreshHistoryNow() } }

    private suspend fun refreshHistoryNow() {
        val h = control.history ?: return
        val on = h.historyOn
        val rows = if (!on) emptyList() else runCatching { h.rows() }.getOrDefault(emptyList())
        val showAll = control.showAllAttempts
        val ui = rows.filter { it.kind != "NO_MATCH" || showAll }.map { it.toUi() } // a NO_MATCH attempt row is a Developer view ("Show all attempts")
        val open = _state.value.historyDetail
        val detail = if (open == null) null else rows.firstOrNull { it.id == open.row.id }?.let { buildDetail(h, it, open.message) }
        _state.update { it.copy(history = ui, historyOn = on, historyDetail = if (!on) null else if (open == null) it.historyDetail else detail) }
    }

    private suspend fun buildDetail(h: PlaceHistoryControl, r: com.rokid.context.memory.db.PlaceCardLogEntity, message: String? = null): PlaceHistoryDetail {
        val line = runCatching { h.line(r) }.getOrNull()
        val photo = r.frameId?.takeIf { r.photoGone == 0 }?.let { runCatching { h.photoPath(it) }.getOrNull() } // retention v3: a gone photo is never loaded (its id is kept only as lineage)
        return PlaceHistoryDetail(r.toUi(), line, photo, h.showAgainBlock(r) ?: if (line == null) PlaceHistoryModel.GONE_LINE else null, message)
    }

    fun openHistory(id: String) {
        val h = control.history ?: return
        viewModelScope.launch(work) {
            val row = runCatching { h.rows() }.getOrDefault(emptyList()).firstOrNull { it.id == id } ?: return@launch
            val d = buildDetail(h, row)
            _state.update { it.copy(historyDetail = d) }
        }
    }

    fun closeHistory() { _state.update { it.copy(historyDetail = null) } }
    fun historyMore() { _state.update { it.copy(historyShown = it.historyShown + PlaceHistoryModel.PAGE) } }

    fun deleteHistory() {
        val h = control.history ?: return
        val id = _state.value.historyDetail?.row?.id ?: return
        viewModelScope.launch(work) {
            runCatching { h.delete(id) }
            _state.update { it.copy(historyDetail = null) }
            refreshHistoryNow()
        }
    }

    /** "Show on glasses again": a user act (never a re-identification). The result is said in the detail; the list shows the new row. */
    fun showAgain() {
        val h = control.history ?: return
        val d = _state.value.historyDetail ?: return
        if (d.busy) return
        _state.update { it.copy(historyDetail = d.copy(busy = true, message = null)) }
        viewModelScope.launch(work) {
            val row = runCatching { h.rows() }.getOrDefault(emptyList()).firstOrNull { it.id == d.row.id }
            val ok = row != null && h.showAgainBlock(row) == null && runCatching { h.showAgain(row) }.getOrDefault(false)
            val msg = if (ok) "Sent to the glasses." else "Not shown: it could not be sent right now."
            _state.update { s -> s.copy(historyDetail = s.historyDetail?.copy(busy = false, message = msg)) }
            refreshHistoryNow()
        }
    }

    private suspend fun refreshSummaryNow() {
        val s = runCatching { control.summary() }.getOrDefault("")
        val st = runCatching { control.stats() }.getOrNull()
        runCatching { control.sweepMoments() }
        val pins = runCatching { control.pinning?.pins() }.getOrNull().orEmpty()
        val moments = runCatching { control.momentsOf(pins.mapNotNull { it.momentId }) }.getOrDefault(emptyMap())
        val indexed = if (_state.value.developerMode) runCatching { control.referenceIndexed(moments.keys.toList()) }.getOrDefault(emptyMap()) else emptyMap()
        _state.update { it.copy(packSummary = s, stats = st, pins = pins, moments = moments, refIndexed = indexed) }
        if (refreshSeedLines() > 0) pollSeeds()
        refreshHistoryNow()
    }
}

@Composable
fun PlaceGuideRoute(control: PlaceGuideControl, back: () -> Unit, changingConfigurations: () -> Boolean = { false }, developerMode: Boolean = false, pickOnMap: (() -> Unit)? = null, openFrame: ((String) -> Unit)? = null) {
    val vm = viewModel { PlaceGuideViewModel(control, developerMode = developerMode) }
    val state by vm.state.collectAsStateWithLifecycle()
    // B2: the history follows Forgets and delivery changes through the store's change events, only while the screen is started; each (re)start re-reads once (the refresh on resume).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { vm.historyChanges.collect { vm.refreshHistory() } }
    }
    // Leaving the screen drops an unused position and the draft; a rotation (configuration change) is not leaving: the ViewModel (name, note, kind, radius, in memory only, never in a bundle) and the in-memory fix with its timer stay, and Save re-checks the draft's age.
    androidx.compose.runtime.DisposableEffect(vm) { onDispose { if (dropsDraftOnDispose(changingConfigurations())) vm.cancelPin() } }
    PlaceGuideScreen(state, back, vm::setEnabled, vm::seed, PlaceGuideActions(vm::setBudget, vm::askClear, vm::confirmClear, vm::cancelClear, vm::beginPin, vm::editPin, vm::deletePin, vm::confirmDeletePin, vm::cancelDeletePin, vm::chooseDeleteMode, vm::acknowledgeDelete, vm::updatePinDialog, vm::savePin, vm::cancelPin, vm::seedPreset, vm::setCardSide, vm::attachLatest, vm::openMomentPicker, vm::closeMomentPicker, vm::pickMoment, vm::pickerDay, vm::pickerMore, vm::removeMoment, vm::askSkipMoment, vm::cancelSkipMoment, vm::confirmSkipMoment, pickOnMap, openHistory = vm::openHistory, closeHistory = vm::closeHistory, historyMore = vm::historyMore, showAgain = vm::showAgain, deleteHistory = vm::deleteHistory, openMoment = openFrame, cancelSeeds = vm::cancelSeeds, setExtraType = vm::setExtraType, setOfflineGuess = vm::setOfflineGuess, setMaybeCutoff = vm::setMaybeCutoff, setCandidateRadius = vm::setCandidateRadius, openManual = vm::openManual, updateManual = vm::updateManual, saveManual = vm::saveManual, cancelManual = vm::cancelManual, pickManualPhoto = vm::pickManualPhoto, fetchManualPhoto = vm::fetchManualPhoto, removeManualPhoto = vm::removeManualPhoto, saveManualWithoutPhoto = vm::saveManualWithoutPhoto, openPinPhoto = vm::openPinPhoto, updatePinPhoto = vm::updatePinPhoto, pickPinPhoto = vm::pickPinPhoto, fetchPinPhoto = vm::fetchPinPhoto, removePinPhoto = vm::removePinPhoto, savePinPhoto = vm::savePinPhoto, cancelPinPhoto = vm::cancelPinPhoto,
        setConfirmedOnly = vm::setConfirmedOnly, setPinCardsNearby = vm::setPinCardsNearby, setCheckMode = vm::setCheckMode, setPhotoMatch = vm::setPhotoMatch, resetPhotoMatch = vm::resetPhotoMatch, setLayer = vm::setLayer))
}

/**
 * "Place cards" (v18, owner decision): the card history list as a normal Settings entry, visible without Developer mode. It reuses the Place Guide view model and the same list, detail dialog, "Show on glasses again",
 * "Open Moment" and "Delete from history"; nothing else of the Place Guide (pins, seeding, card position) is on this page.
 */
@Composable
fun PlaceCardsRoute(control: PlaceGuideControl, back: () -> Unit, openFrame: ((String) -> Unit)? = null) {
    val vm = viewModel { PlaceGuideViewModel(control) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { vm.historyChanges.collect { vm.refreshHistory() } }
    }
    PlaceCardsScreen(state, back, PlaceGuideActions(openHistory = vm::openHistory, closeHistory = vm::closeHistory, historyMore = vm::historyMore, showAgain = vm::showAgain, deleteHistory = vm::deleteHistory, openMoment = openFrame))
}

@Composable
fun PlaceCardsScreen(state: PlaceGuideUiState, back: () -> Unit, on: PlaceGuideActions = PlaceGuideActions()) {
    SettingsPage("Place cards", back, tag = "place_cards_settings") {
        placeHistoryItems(state.history, state.historyShown, java.time.ZoneId.systemDefault(), on.openHistory, on.historyMore, state.historyOn)
    }
    state.historyDetail?.let { d -> PlaceHistoryDetailDialog(d, java.time.ZoneId.systemDefault(), on.closeHistory, on.showAgain, on.deleteHistory, on.openMoment) }
}

/** A disposed route drops the pin draft unless the disposal is a configuration change (rotation); [PlaceGuideViewModel.onCleared] still cancels when the screen is really left. */
internal fun dropsDraftOnDispose(changingConfigurations: Boolean): Boolean = !changingConfigurations

class PlaceGuideActions(
    val setBudget: (Int) -> Unit = {}, val askClear: () -> Unit = {}, val confirmClear: () -> Unit = {}, val cancelClear: () -> Unit = {},
    val beginPin: () -> Unit = {}, val editPin: (String) -> Unit = {}, val deletePin: (String) -> Unit = {}, val confirmDeletePin: () -> Unit = {}, val cancelDeletePin: () -> Unit = {}, val chooseDeleteMode: (Boolean) -> Unit = {}, val acknowledgeDelete: (Boolean) -> Unit = {},
    val updatePinDialog: ((PinDialogState) -> PinDialogState) -> Unit = {}, val savePin: () -> Unit = {}, val cancelPin: () -> Unit = {},
    val seedPreset: (SeedPreset) -> Unit = {},
    val setCardSide: (com.rokid.context.core.model.PlaceSide) -> Unit = {},
    val attachLatest: (String?) -> Unit = {}, val openMomentPicker: (String?) -> Unit = {}, val closeMomentPicker: () -> Unit = {}, val pickMoment: (MomentChoice) -> Unit = {},
    val pickerDay: (Int) -> Unit = {}, val pickerMore: () -> Unit = {}, val removeMoment: (String?) -> Unit = {}, val askSkipMoment: () -> Unit = {}, val cancelSkipMoment: () -> Unit = {}, val confirmSkipMoment: () -> Unit = {},
    /** Opens the zones map to draw the box to seed; null hides the button (a build without the map). */
    val pickOnMap: (() -> Unit)? = null,
    /** B2: the place card history: open a row, close the detail, show more rows, "Show on glasses again", "Delete from history", and "Open Moment" (a live frame; null hides the button). */
    val openHistory: (String) -> Unit = {}, val closeHistory: () -> Unit = {}, val historyMore: () -> Unit = {}, val showAgain: () -> Unit = {}, val deleteHistory: () -> Unit = {},
    val openMoment: ((String) -> Unit)? = null,
    /** S3: cancels the active background seed(s). */
    val cancelSeeds: () -> Unit = {},
    /** V2b: the "Offline place guess (Maybe)" switch and its two Advanced fields. */
    val setExtraType: (String, Boolean) -> Unit = { _, _ -> }, val setOfflineGuess: (Boolean) -> Unit = {}, val setMaybeCutoff: (Float) -> Unit = {}, val setCandidateRadius: (Int) -> Unit = {},
    /** Developer "Add place manually": open, edit, save, cancel. */
    val openManual: () -> Unit = {}, val updateManual: ((ManualFormState) -> ManualFormState) -> Unit = {}, val saveManual: () -> Unit = {}, val cancelManual: () -> Unit = {},
    /** Developer reference photo: the picked document (null = cancelled), the Fetch button and Remove. */
    val pickManualPhoto: (String?) -> Unit = {}, val fetchManualPhoto: () -> Unit = {}, val removeManualPhoto: () -> Unit = {}, val saveManualWithoutPhoto: () -> Unit = {},
    /** Developer "Add photo" on a pin that needs a Moment. */
    val openPinPhoto: (String) -> Unit = {}, val updatePinPhoto: ((PinPhotoState) -> PinPhotoState) -> Unit = {}, val pickPinPhoto: (String?) -> Unit = {}, val fetchPinPhoto: () -> Unit = {}, val removePinPhoto: () -> Unit = {}, val savePinPhoto: () -> Unit = {}, val cancelPinPhoto: () -> Unit = {},
    /** Layered confirmation: the user switches, the check mode, the nine numbers and the Developer layer switches. */
    val setConfirmedOnly: (Boolean) -> Unit = {}, val setPinCardsNearby: (Boolean) -> Unit = {}, val setCheckMode: (com.rokid.context.work.frames.place.CheckMode) -> Unit = {},
    val setPhotoMatch: (com.rokid.context.memory.retrieval.PhotoMatchConfig) -> Unit = {}, val resetPhotoMatch: () -> Unit = {}, val setLayer: (String, Boolean) -> Unit = { _, _ -> },
)

@Composable
fun PlaceGuideScreen(state: PlaceGuideUiState, back: () -> Unit, onEnabled: (Boolean) -> Unit, onSeed: (GeoBox?) -> Unit, on: PlaceGuideActions = PlaceGuideActions()) {
    var s by remember { mutableStateOf("") }; var w by remember { mutableStateOf("") }; var n by remember { mutableStateOf("") }; var e by remember { mutableStateOf("") }
    SettingsPage("Place Guide", back, tag = "place_guide_settings") {
        item {
            SettingsCard {
                SwitchRow("Place Guide", "Names the monument you are looking at after you pause on it", state.enabled, onEnabled, tag = "place_guide_switch")
                NoticeText(PlaceGuideModel.ZONE_NOTE, tag = "place_guide_zone_note")
                if (state.packSummary.isNotEmpty()) NoticeText(state.packSummary, tag = "place_guide_pack")
                SwitchRow("Offline place guess (Maybe)", PlaceGuideModel.OFFLINE_NOTE, state.offlineGuess, on.setOfflineGuess, tag = "place_guide_offline_guess")
            }
        }
        item {
            SettingsCard {
                SwitchRow(PlaceGuideModel.CONFIRMED_TITLE, PlaceGuideModel.CONFIRMED_NOTE, state.confirmedOnly, on.setConfirmedOnly, tag = "place_confirmed_only")
                if (state.confirmedOnly) SwitchRow(PlaceGuideModel.PIN_NEARBY_TITLE, PlaceGuideModel.PIN_NEARBY_NOTE, state.pinCardsNearby, on.setPinCardsNearby, tag = "place_pin_nearby")
                ChoiceRow(PlaceGuideModel.CHECK_TITLE, PlaceGuideModel.CHECK_LABELS, state.checkMode.ordinal, { on.setCheckMode(com.rokid.context.work.frames.place.CheckMode.entries[it]) }, tag = "place_check_mode")
                NoticeText(PlaceGuideModel.CHECK_NOTE, tag = "place_check_mode_note")
                if (!state.offlineModelReady) NoticeText(PlaceGuideModel.MODEL_NEEDED, tag = "place_model_needed")
            }
        }
        item {
            SettingsCard {
                Text("Extra place types", style = MaterialTheme.typography.titleMedium)
                NoticeText(PlaceGuideModel.EXTRA_TYPES_NOTE, tag = "place_guide_extra_note")
                for (t in com.rokid.context.connectors.place.ExtraPlaceTypes.SHOWN) SwitchRow(t.title, t.note.replaceFirstChar { it.uppercase() }.let { if (it.isEmpty()) t.category else "${t.category}: $it" }, t.key in state.extraTypes, { on2 -> on.setExtraType(t.key, on2) }, tag = "place_guide_extra_${t.key}")
            }
        }
        if (state.offlineGuess) item {
            SettingsCard {
                Text("Advanced: offline guess", style = MaterialTheme.typography.titleMedium)
                Text("Maybe cut-off ${String.format(java.util.Locale.US, "%.2f", state.maybeCutoff)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("maybe_cutoff_label"))
                Slider(value = state.maybeCutoff, onValueChange = { on.setMaybeCutoff(PlaceGuideModel.snapCutoff(it)) }, valueRange = com.rokid.context.work.frames.place.OfflineTier.MIN_CUTOFF..com.rokid.context.work.frames.place.OfflineTier.MAX_CUTOFF, steps = PlaceGuideModel.CUTOFF_STEPS, modifier = Modifier.testTag("maybe_cutoff"))
                NoticeText("Higher shows fewer, safer guesses. Default 0.55.", tag = "maybe_cutoff_note")
                Text("Candidate radius ${state.candidateRadiusM} m", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("candidate_radius_label"))
                Slider(value = state.candidateRadiusM.toFloat(), onValueChange = { on.setCandidateRadius(PlaceGuideModel.snapRadius(it)) }, valueRange = com.rokid.context.work.frames.place.OfflineTier.MIN_RADIUS_M.toFloat()..com.rokid.context.work.frames.place.OfflineTier.WIDE_RADIUS_M.toFloat(), steps = PlaceGuideModel.RADIUS_STEPS, modifier = Modifier.testTag("candidate_radius"))
                NoticeText("Places this close are compared with the photo. Default 150 m, up to 500 m.", tag = "candidate_radius_note")
            }
        }
        placeHistoryItems(state.history, state.historyShown, java.time.ZoneId.systemDefault(), on.openHistory, on.historyMore, state.historyOn)
        if (state.developerMode) item {
            SettingsCard {
                Text("Photo check layers (Developer)", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Layer 1: photo vs reference photos", "On the phone. Off = kill switch: the photo is never compared with reference photos.", state.layerPhoto, { on.setLayer("photo", it) }, tag = "place_layer_photo")
                SwitchRow("Layer 2: online vision check", "Off = the online vision model is never called for places.", state.layerOnline, { on.setLayer("online", it) }, tag = "place_layer_online")
                SwitchRow("Layer 3: offline guess", "Off = no offline guess (Maybe). The user switch above and the downloaded model still apply.", state.layerOffline, { on.setLayer("offline", it) }, tag = "place_layer_offline")
                SwitchRow("Several frames per glance", "Averages up to 4 recent frames (last 20 s, similar, no movement) into one query.", state.multiFrame, { on.setLayer("multi", it) }, tag = "place_layer_multi")
                SwitchRow("Best of the frames instead of the average", "More eager and less precise.", state.bestOfK, { on.setLayer("bestk", it) }, tag = "place_layer_bestk")
                SwitchRow("Show all attempts in Place cards", "Also lists the attempts that found no place at all.", state.showAllAttempts, { on.setLayer("all", it) }, tag = "place_layer_all")
            }
        }
        if (state.developerMode) item {
            SettingsCard {
                Text(PlaceGuideModel.THRESH_TITLE, style = MaterialTheme.typography.titleMedium)
                NoticeText(PlaceGuideModel.THRESH_NOTE, tag = "place_thresholds_note")
                PhotoMatchFields(state.photoMatch, on.setPhotoMatch)
                SecondaryAction("Reset to defaults", on.resetPhotoMatch, tag = "place_thresholds_reset")
            }
        }
        if (state.developerMode) item {
            SettingsCard {
                Text("Card position (Developer)", style = MaterialTheme.typography.titleMedium)
                ChoiceRow("Position", PlaceGuideModel.SIDE_LABELS, state.cardSide.ordinal, { on.setCardSide(com.rokid.context.core.model.PlaceSide.entries[it]) }, tag = "place_card_side")
                NoticeText(PlaceGuideModel.SIDE_NOTE, tag = "place_card_side_note")
            }
        }
        item {
            SettingsCard {
                Text("Pack size and budget", style = MaterialTheme.typography.titleMedium)
                state.stats?.let { st ->
                    NoticeText("${PlaceGuideModel.mb(st.bytes)} of ${st.budgetBytes / 1_048_576} MB used", tag = "pack_size")
                    NoticeText("${st.seeded} seeded, ${st.fetched} fetched, ${st.pinned} pinned", tag = "pack_counts")
                    NoticeText(PlaceGuideModel.countsHeader(state.pins.size, st.seeded), tag = "pack_counts_header")
                }
                ChoiceRow("Budget", PlaceGuideModel.BUDGET_CHOICES_MB.map { "$it MB" }, PlaceGuideModel.BUDGET_CHOICES_MB.indexOf(state.budgetMb), { on.setBudget(PlaceGuideModel.BUDGET_CHOICES_MB[it]) }, tag = "pack_budget")
                NoticeText("When the pack is over the budget the least recently used fetched places go first. Seeded and pinned places are never removed.", tag = "pack_budget_note")
                NoticeText(PlaceGuideModel.CLEAR_NOTE, tag = "pack_clear_note")
                SecondaryAction("Clear fetched places", on.askClear, tag = "pack_clear")
            }
        }
        item {
            SettingsCard {
                Text("Your pinned places", style = MaterialTheme.typography.titleMedium)
                NoticeText(PlaceGuideModel.PIN_NOTE, tag = "pin_note")
                PrimaryAction("Pin a place here", on.beginPin, tag = "pin_here", enabled = !state.pinBusy)
                if (state.developerMode) SecondaryAction("Add place manually (Developer)", on.openManual, tag = "manual_add", enabled = !state.pinBusy)
                state.pinMessage?.let { NoticeText(it, error = state.pinMessageIsError, tag = "pin_message") }
            }
        }
        state.pins.forEach { p ->
            item(key = "pin_${p.id}") {
                SettingsCard {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("pin_name_${p.id}"))
                    NoticeText("${PlaceGuideModel.kindLabel(p.kind)}, ${p.radiusM} m" + if (p.parentId != null) ", part of a site" else "", tag = "pin_detail_${p.id}")
                    NoticeText(PlaceGuideModel.sourceLine(), tag = "pin_source_${p.id}")
                    if (p.note.isNotEmpty()) NoticeText("Your note: ${p.note}", tag = "pin_note_${p.id}")
                    MomentSection(p.id, state.moments[p.momentId], PlaceGuideModel.needsMoment(p, state.moments), on)
                    if (state.developerMode) state.refIndexed[p.momentId]?.let { ready -> NoticeText(if (ready) PlaceGuideModel.PHOTO_READY else PlaceGuideModel.PHOTO_WAITING, tag = "pin_photo_state_${p.id}") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.developerMode && PlaceGuideModel.needsMoment(p, state.moments)) SecondaryAction("Add photo", { on.openPinPhoto(p.id) }, tag = "pin_add_photo_${p.id}", enabled = !state.pinBusy)
                        SecondaryAction("Edit", { on.editPin(p.id) }, tag = "pin_edit_${p.id}")
                        SecondaryAction("Delete", { on.deletePin(p.id) }, tag = "pin_delete_${p.id}")
                    }
                }
            }
        }
        item {
            SettingsCard {
                Text("Seed this area", style = MaterialTheme.typography.titleMedium)
                NoticeText(PlaceGuideModel.SEED_NOTE, tag = "place_guide_seed_note")
                NoticeText(com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaList.ATTRIBUTION, tag = "place_guide_attribution")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s, { s = it }, label = { Text("South") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_south"))
                    OutlinedTextField(w, { w = it }, label = { Text("West") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_west"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(n, { n = it }, label = { Text("North") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_north"))
                    OutlinedTextField(e, { e = it }, label = { Text("East") }, singleLine = true, modifier = Modifier.weight(1f).testTag("seed_east"))
                }
                state.seedLines.forEachIndexed { i, t -> NoticeText(t, tag = "seed_progress_$i") }
                if (state.seedLines.isNotEmpty()) SecondaryAction("Cancel seeding", on.cancelSeeds, tag = "seed_cancel")
                PrimaryAction("Seed this area", { onSeed(PlaceGuideModel.parseBox(s, w, n, e)) }, tag = "seed_area", enabled = !state.seeding)
                on.pickOnMap?.let { pick -> SecondaryAction("Pick on the map", pick, tag = "seed_pick_on_map") }
                SecondaryAction("Seed Hyderabad (Charminar, Golconda)", { onSeed(PlaceGuideModel.HYDERABAD) }, tag = "seed_hyderabad", enabled = !state.seeding)
                SecondaryAction("Seed Ramoji Film City (23 Nov)", { onSeed(PlaceGuideModel.RAMOJI) }, tag = "seed_ramoji", enabled = !state.seeding)
                for (p in PlaceGuideModel.TRIP_PRESETS) {
                    SecondaryAction(p.buttonLabel, { on.seedPreset(p) }, tag = "seed_preset_${p.id}", enabled = !state.seeding)
                    NoticeText(p.note, tag = "seed_preset_note_${p.id}")
                }
                state.message?.let { NoticeText(it, error = state.messageIsError, tag = "place_guide_message") }
            }
        }
    }
    if (state.confirmClear) AlertDialog(
        onDismissRequest = on.cancelClear, modifier = Modifier.testTag("pack_clear_dialog"),
        title = { Text("Clear fetched places?") },
        text = { Text(PlaceGuideModel.CLEAR_NOTE + " They are fetched again when you look at something.") },
        confirmButton = { TextButton(on.confirmClear, Modifier.testTag("pack_clear_confirm")) { Text("Clear") } },
        dismissButton = { TextButton(on.cancelClear, Modifier.testTag("pack_clear_cancel")) { Text("Keep") } },
    )
    state.historyDetail?.let { d -> PlaceHistoryDetailDialog(d, java.time.ZoneId.systemDefault(), on.closeHistory, on.showAgain, on.deleteHistory, on.openMoment) }
    state.pinDialog?.let { d -> PinDialog(d, on) }
    if (state.developerMode) state.manualForm?.let { d -> ManualPlaceDialog(d, on) }
    if (state.developerMode) state.pinPhoto?.let { d -> PinPhotoDialog(d, on) }
    state.pinDeleteConfirm?.let { c -> PinDeleteDialog(c, on) }
    state.momentPicker?.let { pk -> MomentPickerDialog(pk, on) }
}

/** The pin's reference Moment: its thumbnail and time, or the "Needs a Moment" badge, and the attach / pick / remove actions. [pinId] null = inside the pin dialog of a new pin. */
@Composable
private fun MomentSection(pinId: String?, moment: MomentChoice?, needs: Boolean, on: PlaceGuideActions, hint: String? = null) {
    val key = pinId ?: "new"
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (moment != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            com.rokid.context.core.ui.components.FrameThumb(moment.path, Modifier.testTag("pin_moment_thumb_$key"), size = 56.dp)
            NoticeText("Moment ${PlaceGuideModel.momentTime(moment.atMs)}", tag = "pin_moment_time_$key")
        } else if (needs) {
            NoticeText(if (pinId == null) PlaceGuideModel.NO_MOMENT_NOTE else PlaceGuideModel.NEEDS_MOMENT, error = pinId != null, tag = "pin_moment_badge_$key")
        }
        hint?.let { NoticeText(it, tag = "pin_moment_hint_$key") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryAction("Attach latest Moment", { on.attachLatest(pinId) }, tag = "pin_moment_latest_$key")
            SecondaryAction("Pick from recent Moments", { on.openMomentPicker(pinId) }, tag = "pin_moment_pick_$key")
        }
        if (moment != null) SecondaryAction("Remove link", { on.removeMoment(pinId) }, tag = "pin_moment_remove_$key")
    }
}

@Composable
private fun MomentPickerDialog(pk: MomentPicker, on: PlaceGuideActions) {
    AlertDialog(
        onDismissRequest = on.closeMomentPicker, modifier = Modifier.testTag("pin_moment_picker"),
        title = { Text("Pick a Moment") },
        text = {
            androidx.compose.foundation.layout.Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton({ on.pickerDay(-1) }, Modifier.testTag("pin_moment_day_prev")) { Text("\u2039 Previous") }
                    Text(PlaceGuideModel.dayLabel(pk.dayStartMs), Modifier.testTag("pin_moment_day"))
                    TextButton({ on.pickerDay(1) }, Modifier.testTag("pin_moment_day_next")) { Text("Next \u203A") }
                }
                if (pk.items.isEmpty()) Text("No Moments on this day.", Modifier.testTag("pin_moment_day_empty"))
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pk.items.size) { i ->
                    val m = pk.items[i]
                    Row(Modifier.fillMaxWidth().clickable { on.pickMoment(m) }.testTag("pin_moment_item_${m.id}"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        com.rokid.context.core.ui.components.FrameThumb(m.path, size = 56.dp)
                        Text(PlaceGuideModel.momentTime(m.atMs))
                    }
                }
                if (pk.hasMore) item { TextButton(on.pickerMore, Modifier.testTag("pin_moment_more")) { Text("Load more") } }
            }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(on.closeMomentPicker, Modifier.testTag("pin_moment_picker_close")) { Text("Close") } },
    )
}

/** Place object S2/S3: mode (a) "Only the place" (default) and, when offered, mode (b) "The place and its N Moments" with the preview text before its confirm button is enabled. */
@Composable
private fun PinDeleteDialog(c: PinDeleteConfirm, on: PlaceGuideActions) {
    val p = c.cascade
    val b = c.cascadeChosen && p != null
    AlertDialog(
        onDismissRequest = on.cancelDeletePin, modifier = Modifier.testTag("pin_delete_dialog"),
        title = { Text("Delete place \"${c.name}\"?") },
        text = {
            Column {
                if (p != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!b, { on.chooseDeleteMode(false) }, Modifier.testTag("pin_delete_mode_only")); Text("Only the place") }
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(b, { on.chooseDeleteMode(true) }, Modifier.testTag("pin_delete_mode_cascade")); Text("The place and its ${p.moments} Moment" + if (p.moments == 1) "" else "s") }
                }
                if (b && p != null) {
                    Text(if (p.tooLarge) PlaceGuideModel.CASCADE_TOO_LARGE else PlaceGuideModel.cascadeText(p.name, p.moments, p.photos, p.memories, p.marks, p.otherPinNames), Modifier.testTag("pin_delete_text"))
                    if (p.needsAck && !p.tooLarge) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(c.acknowledged, { on.acknowledgeDelete(it) }, Modifier.testTag("pin_delete_ack")); Text("I understand: this forgets many items") }
                } else Text(PlaceGuideModel.deletePlaceText(c.momentCount), Modifier.testTag("pin_delete_text"))
            }
        },
        confirmButton = { TextButton(on.confirmDeletePin, Modifier.testTag("pin_delete_confirm"), enabled = !c.running && (!b || (p != null && !p.tooLarge && (!p.needsAck || c.acknowledged)))) { Text(if (b) "Delete all of it" else "Delete place") } },
        dismissButton = { TextButton(on.cancelDeletePin, Modifier.testTag("pin_delete_cancel"), enabled = !c.running) { Text("Cancel") } },
    )
}

@Composable
private fun PinDialog(d: PinDialogState, on: PlaceGuideActions) {
    AlertDialog(
        onDismissRequest = on.cancelPin, modifier = Modifier.testTag("pin_dialog"),
        title = { Text(if (d.editingId == null) "Pin a place here" else "Edit pin") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextFieldRow("Name (stays on this phone, never backed up)", d.name, { v -> on.updatePinDialog { it.copy(name = v) } }, tag = "pin_f_name")
                ChoiceRow("Kind", PlacePinning.KINDS.map(PlaceGuideModel::kindLabel), d.kindIndex, { i -> on.updatePinDialog { it.copy(kindIndex = i) } }, tag = "pin_f_kind")
                TextFieldRow("Radius (metres, 10 to 1500)", d.radius, { v -> on.updatePinDialog { it.copy(radius = v) } }, tag = "pin_f_radius", number = true)
                if (d.editingId == null) ChoiceRow("Part of", listOf("No site") + d.parents.map { it.name }, d.parentIndex, { i -> on.updatePinDialog { it.copy(parentIndex = i) } }, tag = "pin_f_parent")
                TextFieldRow("Your note (up to 140 characters, shown on the card)", d.note, { v -> on.updatePinDialog { it.copy(note = v.take(UserPlace.NOTE_MAX)) } }, tag = "pin_f_note", singleLine = false)
                if (d.editingId == null) {
                    NoticeText(PlaceGuideModel.MOMENT_NOTE, tag = "pin_moment_note")
                    MomentSection(null, d.moment, d.momentId == null, on, d.momentHint)
                }
                d.error?.let { NoticeText(it, error = true, tag = "pin_f_error") }
            }
        },
        confirmButton = { TextButton(on.savePin, Modifier.testTag("pin_f_save")) { Text("Save") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (d.editingId == null && d.momentId == null) TextButton(on.askSkipMoment, Modifier.testTag("pin_f_skip_moment")) { Text("Save without a Moment") }
                TextButton(on.cancelPin, Modifier.testTag("pin_f_cancel")) { Text("Cancel") }
            }
        },
    )
    if (d.askSkipMoment) AlertDialog(
        onDismissRequest = on.cancelSkipMoment, modifier = Modifier.testTag("pin_skip_dialog"),
        title = { Text("Save without a Moment?") },
        text = { Text("The pin will be flagged \"Needs a Moment\". It still works by location, and you can attach a Moment later.") },
        confirmButton = { TextButton(on.confirmSkipMoment, Modifier.testTag("pin_skip_confirm")) { Text("Save without") } },
        dismissButton = { TextButton(on.cancelSkipMoment, Modifier.testTag("pin_skip_cancel")) { Text("Back") } },
    )
}


/**
 * The optional photo, shared by "Add place manually" and "Add photo": the system document picker (no storage permission), the https link field, a visible Fetch button right under it (the keyboard's Done does the same),
 * the state line (Fetching..., Photo ready with a small thumbnail, or the plain reason it failed) and Remove. [tag] prefixes the test tags.
 */
@Composable
private fun PhotoPicker(link: String, photo: com.rokid.context.work.frames.place.PreparedPhoto?, busy: Boolean, error: String?, tag: String, onLink: (String) -> Unit, onPick: (String?) -> Unit, onFetch: () -> Unit, onRemove: () -> Unit, extra: @Composable () -> Unit = {}) {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> onPick(uri?.toString()) }
    val canFetch = !busy && PlaceGuideModel.looksLikeHttps(link)
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SecondaryAction(if (photo != null) "Replace with a file" else "Choose a photo", { picker.launch(arrayOf("image/*")) }, tag = "${tag}_choose", enabled = !busy)
        TextFieldRow("Paste an image link (https)", link, { v -> onLink(v.take(2000)) }, tag = "${tag}_link", onDone = { if (canFetch) onFetch() })
        if (link.isNotBlank() && !PlaceGuideModel.looksLikeHttps(link)) NoticeText(PlaceGuideModel.LINK_NOT_HTTPS, tag = "${tag}_link_hint")
        PrimaryAction(if (busy) "Fetching..." else if (photo != null) "Fetch and replace" else "Fetch", onFetch, tag = "${tag}_fetch", enabled = canFetch)
        if (busy) NoticeText("Fetching...", tag = "${tag}_status")
        if (photo != null) {
            val bmp = androidx.compose.runtime.remember(photo) { android.graphics.BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                bmp?.let { androidx.compose.foundation.Image(it, null, Modifier.size(72.dp).testTag("${tag}_thumb")) }
                NoticeText(PlaceGuideModel.photoSize(photo), tag = "${tag}_size")
            }
            SecondaryAction("Remove photo", onRemove, tag = "${tag}_remove", enabled = !busy)
        }
        error?.let { NoticeText(it, error = true, tag = "${tag}_error") }
        extra()
    }
}

/** "Add place manually" > Photo. */
@Composable
private fun ManualPhotoSection(d: ManualFormState, on: PlaceGuideActions) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NoticeText("Photo", tag = "manual_photo_title")
        NoticeText(PlaceGuideModel.PHOTO_NOTE, tag = "manual_photo_note")
        PhotoPicker(d.photoLink, d.photo, d.photoBusy, d.photoError, "manual_photo", { v -> on.updateManual { it.copy(photoLink = v) } }, on.pickManualPhoto, on.fetchManualPhoto, on.removeManualPhoto) {
            if (d.askNoPhoto) {
                NoticeText(PlaceGuideModel.NO_PHOTO_CHOICE, error = true, tag = "manual_photo_choice")
                SecondaryAction("Add without photo", on.saveManualWithoutPhoto, tag = "manual_f_save_nophoto", enabled = !d.photoBusy)
            }
        }
    }
}

/** The dialog body scrolls (a small screen or the open keyboard never hides a field) and keeps room under the last field for the keyboard. */
@Composable
private fun ManualPlaceDialog(d: ManualFormState, on: PlaceGuideActions) {
    AlertDialog(
        onDismissRequest = on.cancelManual, modifier = Modifier.testTag("manual_dialog"),
        title = { Text("Add place manually") },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.testTag("manual_scroll").verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoticeText(PlaceGuideModel.MANUAL_NOTE, tag = "manual_note")
                TextFieldRow("Name (stays on this phone, never backed up)", d.name, { v -> on.updateManual { it.copy(name = v) } }, tag = "manual_f_name")
                TextFieldRow("Description (up to 140 characters, shown on the card)", d.note, { v -> on.updateManual { it.copy(note = v.take(UserPlace.NOTE_MAX)) } }, tag = "manual_f_note", singleLine = false)
                ChoiceRow("Kind", ManualPlace.KINDS.map(PlaceGuideModel::kindLabel), d.kindIndex, { i -> on.updateManual { it.copy(kindIndex = i) } }, tag = "manual_f_kind")
                TextFieldRow("Coordinates: latitude, longitude (paste from a maps app)", d.coordinates, { v -> on.updateManual { it.copy(coordinates = v.take(80)) } }, tag = "manual_f_coords", placeholder = "12.9716, 77.5946")
                TextFieldRow("Radius (metres, 10 to 1500; empty = ${ManualPlace.defaultRadius(ManualPlace.KINDS.getOrElse(d.kindIndex) { ManualPlace.KINDS.first() })} for this kind)", d.radius, { v -> on.updateManual { it.copy(radius = v) } }, tag = "manual_f_radius", number = true)
                ManualPhotoSection(d, on)
                d.error?.let { NoticeText(it, error = true, tag = "manual_f_error") }
                androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
            }
        },
        confirmButton = { TextButton(on.saveManual, Modifier.testTag("manual_f_save"), enabled = !d.photoBusy) { Text("Add") } },
        dismissButton = { TextButton(on.cancelManual, Modifier.testTag("manual_f_cancel"), enabled = !d.photoBusy) { Text("Cancel") } },
    )
}

/** "Add photo" on a pin that needs a Moment: the same choose / link / Fetch / thumbnail flow; "Add" makes the reference Moment and links it. */
@Composable
private fun PinPhotoDialog(d: PinPhotoState, on: PlaceGuideActions) {
    AlertDialog(
        onDismissRequest = on.cancelPinPhoto, modifier = Modifier.testTag("pin_photo_dialog"),
        title = { Text("Add photo") },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.testTag("pin_photo_scroll").verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoticeText(PlaceGuideModel.PHOTO_NOTE, tag = "pin_photo_note")
                PhotoPicker(d.link, d.photo, d.busy, d.error, "pin_photo", { v -> on.updatePinPhoto { it.copy(link = v) } }, on.pickPinPhoto, on.fetchPinPhoto, on.removePinPhoto)
                androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
            }
        },
        confirmButton = { TextButton(on.savePinPhoto, Modifier.testTag("pin_photo_save"), enabled = !d.busy) { Text("Add") } },
        dismissButton = { TextButton(on.cancelPinPhoto, Modifier.testTag("pin_photo_cancel"), enabled = !d.busy) { Text("Cancel") } },
    )
}

/** Texts of the mode (b) delete that are worth a test (a committed delete whose cleanup is still owed must not read as plain success). */
object PlaceDeleteMessages {
    const val COMMITTED_CLEANUP_OWED = "Place and its Moments deleted, but some cleanup (cached copies or pin removal) did not finish. It is retried at the next start."
}


/** The nine Layer 1 numbers as editable fields (3 kinds x High / Floor / Margin). A field is committed as soon as it parses; the model clamps it, the text you typed stays until you leave. */
@Composable
private fun PhotoMatchFields(cfg: com.rokid.context.memory.retrieval.PhotoMatchConfig, set: (com.rokid.context.memory.retrieval.PhotoMatchConfig) -> Unit) {
    var cur by remember { mutableStateOf(cfg) }
    androidx.compose.runtime.LaunchedEffect(cfg) { cur = cfg } // a reset (or a clamp) from outside refreshes the values
    PlaceGuideModel.THRESH_KINDS.forEachIndexed { i, (label, key) ->
        val shown = listOf(cfg.own, cfg.ownCentroid, cfg.web)[i]
        Text(label, style = MaterialTheme.typography.bodyMedium)
        var h by remember(key) { mutableStateOf(shown.high.toString()) }
        var f by remember(key) { mutableStateOf(shown.floor.toString()) }
        var m by remember(key) { mutableStateOf(shown.margin.toString()) }
        // Re-sync a field only when its text no longer produces the stored (clamped) value: a reset from outside, never while typing.
        androidx.compose.runtime.LaunchedEffect(shown) {
            fun k(nh: Int, nf: Int, nm: Int) = com.rokid.context.memory.retrieval.KindThresholds(nh, nf, nm).clamped()
            if (h.toIntOrNull()?.let { k(it, 0, 0).high } != shown.high) h = shown.high.toString()
            if (f.toIntOrNull()?.let { k(shown.high, it, 0).floor } != shown.floor) f = shown.floor.toString()
            if (m.toIntOrNull()?.let { k(shown.high, 0, it).margin } != shown.margin) m = shown.margin.toString()
        }
        fun commit(nh: String, nf: String, nm: String) {
            val t = listOf(cur.own, cur.ownCentroid, cur.web)[i]
            val k = com.rokid.context.memory.retrieval.KindThresholds(nh.toIntOrNull() ?: t.high, nf.toIntOrNull() ?: t.floor, nm.toIntOrNull() ?: t.margin)
            val c = when (i) { 0 -> cur.copy(own = k); 1 -> cur.copy(ownCentroid = k); else -> cur.copy(web = k) }
            cur = c; set(c)
        }
        TextFieldRow("High", h, { h = it; commit(it, f, m) }, tag = "place_thr_${key}_high", number = true)
        TextFieldRow("Floor (0 = off)", f, { f = it; commit(h, it, m) }, tag = "place_thr_${key}_floor", number = true)
        TextFieldRow("Margin", m, { m = it; commit(h, f, it) }, tag = "place_thr_${key}_margin", number = true)
    }
}
