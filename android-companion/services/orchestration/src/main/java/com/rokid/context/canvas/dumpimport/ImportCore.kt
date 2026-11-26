package com.rokid.context.canvas.dumpimport

import com.rokid.context.canvas.dump.DumpReader
import com.rokid.context.canvas.dump.DumpSource

import androidx.room.withTransaction
import com.rokid.context.core.model.MarkRef
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MomentLocationEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.moment.MomentId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Developer IMPORT (owner round 7, 26 Nov): the strict reader of the body of `POST /api/import`. The body is `{"items":[...]}` where every item is an object exactly as `GET /api/dump/items` emits it (a Moment
 * object carries frames with capture metadata, memories, marks, place cards, `location`), plus ONE extra key on a Moment: `photos`, an object `{ "<frameId>": "<base64 of the stored image>" }` (standard base64, at
 * most [DumpSource.MAX_PHOTO_BYTES] decoded). Scenes, pins, standalone marks and place cards are NOT imported (scenes rebuild; marks travel inside their Moment; see the handoff). Unknown keys are ignored.
 * Any invalid field of an item makes the WHOLE request a 400 (nothing is written, only the item INDEX is reported, never content).
 */
object DumpImportParser {
    const val MAX_ITEMS = 50
    const val MAX_FRAMES = 20
    const val MAX_MEMORIES = 50
    const val MAX_MARKS = 20
    const val MAX_TEXT = 2000
    private val FRAME_ID = Regex("^[A-Za-z0-9_-]{1,100}$")
    private val MEMORY_ID = Regex("^[A-Za-z0-9_.:-]{1,100}$")
    private val MARK_ID = Regex("^(mk:[A-Za-z0-9-]{1,64}:[0-9]{1,18}|vm:[0-9]{1,18}:g)$")
    private val MARK_SOURCES = setOf("tap", "voice", "phone_voice", "retro", "ask_tap", "phone_chat")
    private val SOURCE = Regex("^rokid[A-Za-z0-9_]{0,40}$")
    private val HEX = Regex("^[0-9a-fA-F]{1,64}$")
    private val CLASSES = setOf("STATIONARY", "WALKING", "VEHICLE", "UNKNOWN")
    private val METHODS = setOf("INTERPOLATED", "NEAREST")
    const val DEFAULT_SOURCE = "rokid_import"

    class Frame(
        val id: String, val capturedAtMs: Long, val source: String, val motion: String, val dwell: Boolean, val blur: Float?, val phash: String?, val dhash: String?,
        val width: Int?, val markRef: String?, val placeLabel: String?, val photo: ByteArray,
    )
    class Memory(val id: String, val frameId: String, val text: String, val type: String, val tags: List<String>, val tier: MemoryTier, val confidence: Double, val review: ReviewState, val createdAt: Long, val derived: Boolean)
    /** A mark as the dump emits it ([source] is the real kind: tap, voice, phone_voice, retro, ask_tap or phone_chat; [photo] its photo state token). */
    class Mark(val id: String, val source: String, val atMs: Long, val photo: String?, val createdAt: Long)
    class Location(val lat: Double, val lon: Double, val accuracyM: Double, val atMs: Long, val fixAgeMs: Long, val speedClass: String?, val method: String?)
    class MomentItem(
        val id: String, val startMs: Long, val frames: List<Frame>, val memories: List<Memory>, val marks: List<Mark>, val location: Location?,
        /** Counts of what is deliberately not imported from this item (never content). */
        val skippedPlaceCards: Int, val skippedFramesNoPhoto: Int, val skippedMemories: Int,
    )

    /** One parsed item: a Moment to import, or an object that is skipped with a fixed reason token (`scene`, `pin`, `mark_item`). */
    sealed class Entry {
        class Moment(val item: MomentItem) : Entry()
        class Skip(val reason: String) : Entry()
        /** A standalone mark item; [momentId] is the Moment the dump says it belongs to (that Moment restores it). */
        class StandaloneMark(val mark: Mark, val momentId: String?) : Entry()
    }

    class Bad(val index: Int, val reason: String) : Exception(null, null, false, false)

    /** The entries of [body], or throws [Bad] (index -1 = the envelope). */
    fun parse(body: ByteArray): List<Entry> {
        val root = try { Json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject } catch (e: Exception) { null } ?: throw Bad(-1, "not_json")
        val items = root["items"] as? JsonArray ?: throw Bad(-1, "no_items")
        if (items.isEmpty() || items.size > MAX_ITEMS) throw Bad(-1, "item_count")
        return items.mapIndexed { i, e ->
            val o = e as? JsonObject ?: throw Bad(i, "not_object")
            when (str(o, "object_type", i) ?: "moment") {
                "moment" -> Entry.Moment(moment(o, i))
                "scene" -> Entry.Skip("scene")
                "pin" -> Entry.Skip("pin")
                "mark" -> markItem(o, i)
                else -> throw Bad(i, "object_type")
            }
        }
    }

    private fun str(o: JsonObject, k: String, i: Int, max: Int = 200): String? {
        val v = o[k] ?: return null
        if (v is JsonNull) return null
        val p = v as? JsonPrimitive ?: throw Bad(i, k)
        if (!p.isString || p.content.length > max) throw Bad(i, k)
        return p.content
    }
    private fun long(o: JsonObject, k: String, i: Int): Long? {
        val v = o[k] ?: return null
        if (v is JsonNull) return null
        val p = v as? JsonPrimitive ?: throw Bad(i, k)
        if (p.isString) throw Bad(i, k)
        return p.longOrNull ?: throw Bad(i, k)
    }
    private fun dbl(o: JsonObject, k: String, i: Int): Double? {
        val v = o[k] ?: return null
        if (v is JsonNull) return null
        val p = v as? JsonPrimitive ?: throw Bad(i, k)
        if (p.isString) throw Bad(i, k)
        return (p.doubleOrNull ?: throw Bad(i, k)).also { if (!it.isFinite()) throw Bad(i, k) }
    }
    private fun bool(o: JsonObject, k: String, i: Int): Boolean? {
        val v = o[k] ?: return null
        if (v is JsonNull) return null
        val p = v as? JsonPrimitive ?: throw Bad(i, k)
        if (p.isString) throw Bad(i, k)
        return p.booleanOrNull ?: throw Bad(i, k)
    }
    private fun arr(o: JsonObject, k: String, i: Int, max: Int): List<JsonObject> {
        val v = o[k] ?: return emptyList()
        if (v is JsonNull) return emptyList()
        val a = v as? JsonArray ?: throw Bad(i, k)
        if (a.size > max) throw Bad(i, k)
        return a.map { it as? JsonObject ?: throw Bad(i, k) }
    }

    private fun mark(m: JsonObject, i: Int): Mark {
        val mid = str(m, "id", i, 130)?.takeIf { MARK_ID.matches(it) } ?: throw Bad(i, "mark_id")
        val at = long(m, "atMs", i)?.takeIf { it >= 0 } ?: throw Bad(i, "mark_atMs")
        val source = str(m, "source", i, 20)?.also { if (it !in MARK_SOURCES) throw Bad(i, "mark_source") } ?: "tap"
        val photo = str(m, "photo", i, 40)?.takeIf { it.isNotBlank() }
        val created = long(m, "createdAt", i) ?: at
        if (created < 0) throw Bad(i, "mark_createdAt")
        return Mark(mid, source, at, photo, created)
    }

    private fun markItem(o: JsonObject, i: Int): Entry.StandaloneMark {
        val k = mark(o, i)
        val mo = str(o, "momentId", i, 20)?.also { if (MomentId.parse(it) == null) throw Bad(i, "momentId") }
        return Entry.StandaloneMark(k, mo)
    }

    private fun moment(o: JsonObject, i: Int): MomentItem {
        val id = str(o, "id", i) ?: throw Bad(i, "id")
        val start = MomentId.startMs(id) ?: throw Bad(i, "id")
        val given = long(o, "startMs", i)
        if (given != null && given != start) throw Bad(i, "startMs")
        val range = start..(start + 999)
        val photos = (o["photos"] as? JsonObject).also { if (o["photos"] != null && o["photos"] !is JsonNull && it == null) throw Bad(i, "photos") }
        var noPhoto = 0
        val frames = ArrayList<Frame>()
        val rawFrames = arr(o, "frames", i, MAX_FRAMES)
        for (f in rawFrames) {
            val fid = str(f, "id", i)?.takeIf { FRAME_ID.matches(it) } ?: throw Bad(i, "frame_id")
            if (frames.any { it.id == fid }) throw Bad(i, "frame_id")
            val cap = long(f, "capturedAtMs", i) ?: long(f, "ts", i) ?: throw Bad(i, "capturedAtMs")
            if (cap !in range) throw Bad(i, "capturedAtMs")
            val photoText = (photos?.get(fid) as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (bool(f, "hasPhoto", i) == false && photoText == null) { noPhoto++; continue }
            val text = photoText ?: throw Bad(i, "photo")
            if (text.length > DumpSource.MAX_PHOTO_BYTES / 3 * 4 + 8) throw Bad(i, "photo_size")
            val bytes = try { java.util.Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) { throw Bad(i, "photo") }
            if (bytes.isEmpty() || bytes.size > DumpSource.MAX_PHOTO_BYTES || DumpReader.sniff(bytes) == null) throw Bad(i, "photo")
            val src = str(f, "source", i)?.takeIf { SOURCE.matches(it) } ?: DEFAULT_SOURCE
            val width = long(f, "width", i)?.also { if (it !in 1..20000) throw Bad(i, "width") }?.toInt()
            val blur = dbl(f, "blur", i)?.toFloat()
            val hash = { k: String -> str(f, k, i, 64)?.also { if (!HEX.matches(it)) throw Bad(i, k) } }
            frames += Frame(
                fid, cap, src, str(f, "motion", i, 20)?.takeIf { it.isNotBlank() } ?: "UNKNOWN", bool(f, "dwell", i) ?: false, blur, hash("phash"), hash("dhash"), width,
                str(f, "markRef", i, 100)?.takeIf { MarkRef.isValid(it) }, str(f, "placeLabel", i, 100), bytes,
            )
        }
        if (photos != null && photos.keys.any { k -> rawFrames.none { f -> (f["id"] as? JsonPrimitive)?.content == k } }) throw Bad(i, "photos")
        var skippedMem = 0
        val memories = ArrayList<Memory>()
        for (m in arr(o, "memories", i, MAX_MEMORIES)) {
            val mid = str(m, "id", i)?.takeIf { MEMORY_ID.matches(it) } ?: throw Bad(i, "memory_id")
            if (memories.any { it.id == mid }) throw Bad(i, "memory_id")
            val text = str(m, "text", i, MAX_TEXT)?.trim()
            val review = str(m, "reviewState", i, 20)?.let { r -> ReviewState.values().firstOrNull { it.name == r } ?: throw Bad(i, "reviewState") } ?: ReviewState.OK
            val fid = str(m, "frameId", i, 100)
            // invalidated, empty or unlinked text is never imported (an unlinked memory would belong to no Moment)
            if (review == ReviewState.INVALIDATED || text.isNullOrEmpty() || fid == null || frames.none { it.id == fid }) { skippedMem++; continue }
            val tier = str(m, "tier", i, 10)?.let { t -> MemoryTier.values().firstOrNull { it.name == t } ?: throw Bad(i, "tier") } ?: MemoryTier.SHORT
            val conf = dbl(m, "confidence", i) ?: 0.5
            if (conf !in 0.0..1.0) throw Bad(i, "confidence")
            val created = long(m, "createdAt", i) ?: start
            if (created < 0) throw Bad(i, "createdAt")
            val tags = (str(m, "tags", i, 200) ?: "").split(' ').filter { it.isNotBlank() }.take(5)
            memories += Memory(mid, fid, text, str(m, "type", i, 20)?.takeIf { it.isNotBlank() } ?: "note", tags, tier, conf, review, created, bool(m, "derived", i) ?: false)
        }
        val marks = ArrayList<Mark>()
        for (m in arr(o, "marks", i, MAX_MARKS)) {
            val k = mark(m, i)
            if (k.atMs !in range || marks.any { it.id == k.id }) throw Bad(i, "mark_atMs")
            marks += k
        }
        if (frames.isEmpty() && marks.isEmpty()) throw Bad(i, "empty")
        val loc = (o["location"] as? JsonObject)?.let { l ->
            val lat = dbl(l, "lat", i) ?: throw Bad(i, "lat"); val lon = dbl(l, "lon", i) ?: throw Bad(i, "lon")
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw Bad(i, "lat")
            val acc = dbl(l, "accuracyM", i) ?: throw Bad(i, "accuracyM")
            if (acc <= 0.0 || acc > 5000.0) throw Bad(i, "accuracyM")
            val at = long(l, "atMs", i) ?: start
            if (Math.abs(at - start) > 60_000L) throw Bad(i, "location_atMs")
            val age = long(l, "fixAgeMs", i) ?: 0L
            if (age !in 0..3_600_000L) throw Bad(i, "fixAgeMs")
            val sc = str(l, "speedClass", i, 20)?.also { if (it !in CLASSES) throw Bad(i, "speedClass") }
            val me = str(l, "method", i, 20)?.also { if (it !in METHODS) throw Bad(i, "method") }
            Location(lat, lon, acc, at, age, sc, me)
        }.also { if (o["location"] != null && o["location"] !is JsonNull && o["location"] !is JsonObject) throw Bad(i, "location") }
        val cards = (o["placeCards"] as? JsonArray)?.size ?: 0
        return MomentItem(id, start, frames, memories, marks, loc, cards, noPhoto, skippedMem)
    }
}

/** What the importer writes through. The production implementations ([ImportFrameStore], [ImportMemoryStore]) call the app's normal ingest; tests pass fakes over the same intake. */
fun interface ImportFrameWriter { suspend fun store(frame: DumpImportParser.Frame): Boolean }
fun interface ImportMemoryWriter { suspend fun store(memory: DumpImportParser.Memory): Boolean }

/** The counts of one request (never ids, text or coordinates). [status] per item in order: `created`, `would_create`, `skipped`, `conflict`, `failed`. */
class ImportReport(val items: List<Pair<String, String>>, val frames: Int, val memories: Int, val marks: Int, val locations: Int, val skippedPlaceCards: Int, val skippedNotImported: Int, val stopped: Boolean) {
    fun count(status: String) = items.count { it.first == status }
}

/**
 * Developer IMPORT, the writer. Everything goes through the app's NORMAL write paths (no raw SQL, no DAO insert of a Moment, memory, frame or mark): photos through the frame ingest (frame, `frame_meta`,
 * `frame_state`; original capture time, never "now"), memory text through the memory writer (attribution `imported`, no model call), marks through the [MarkRecorder] (`recordMark`), the Moment through
 * [MomentBuilder] (`build`; a Moment is a projection of its frames, memories and marks, so its id and time are the original ones), and the location through `moment_location` exactly as the recorder and the
 * GPS backfill write it (same table, same forget hooks). No new table or column. The pause-zone guard does NOT re-check imported rows (owner default, unconfirmed); the "Save location with Moments" switch does.
 * A Moment that is forgotten (tombstone or Forget range) is never recreated. Delete all landing mid-import stops the request (`stopped`).
 */
class DumpImporter(
    private val db: ContextDatabase,
    private val frames: ImportFrameWriter,
    private val memories: ImportMemoryWriter,
    /** The mark recorder's import entry ([com.rokid.context.canvas.marks.MarkRecorder.importMark]): original id, kind, time and photo state. */
    private val recordMark: suspend (DumpImportParser.Mark, String) -> Boolean,
    private val build: suspend (List<String>) -> Unit,
    private val locationEnabled: () -> Boolean,
    private val generation: () -> Long,
    private val wall: () -> Long = System::currentTimeMillis,
) {
    private class Stopped : Exception(null, null, false, false)

    suspend fun run(entries: List<DumpImportParser.Entry>, dedup: Boolean, dryRun: Boolean): ImportReport {
        val out = ArrayList<Pair<String, String>>()
        var nf = 0; var nm = 0; var nk = 0; var nl = 0; var cards = 0; var notImported = 0; var stopped = false
        val gen0 = generation()
        for (e in entries) {
            if (stopped) { out += "failed" to "stopped"; continue }
            if (e is DumpImportParser.Entry.Skip) { out += "skipped" to e.reason; notImported++; continue }
            if (e is DumpImportParser.Entry.StandaloneMark) {
                try {
                    val r = standaloneMark(e, dedup, dryRun, gen0)
                    out += r; if (r.first == "created" || r.first == "would_create") nk++
                } catch (s: Stopped) { stopped = true; out += "failed" to "stopped"
                } catch (c: kotlinx.coroutines.CancellationException) { throw c
                } catch (x: Exception) { out += "failed" to "error" }
                continue
            }
            val m = (e as DumpImportParser.Entry.Moment).item
            cards += m.skippedPlaceCards
            try {
                val verdict = verdict(m)
                if (verdict != null) {
                    out += if (verdict == "conflict") (if (dedup) "skipped" to "dedup" else "conflict" to "exists") else "skipped" to verdict
                    continue
                }
                if (dryRun) { out += "would_create" to "ok"; nf += m.frames.size; nm += m.memories.size; nk += m.marks.size; if (m.location != null) nl++; continue }
                check(gen0)
                val c = write(m, gen0)
                nf += c[0]; nm += c[1]; nk += c[2]; nl += c[3]
                out += if (c[4] == 1) "created" to "ok" else "failed" to "partial"
            } catch (s: Stopped) { stopped = true; out += "failed" to "stopped"
            } catch (c: kotlinx.coroutines.CancellationException) { throw c
            } catch (x: Exception) { out += "failed" to "error" }
        }
        return ImportReport(out, nf, nm, nk, nl, cards, notImported, stopped)
    }

    /** The photo state a restored mark gets: `pending` only when a photo of this very mark comes with the import (the frame commit links it), else the fixed token `no_frame` or the original reason. */
    private fun photoState(k: DumpImportParser.Mark, frames: List<DumpImportParser.Frame>): String {
        val p = k.photo
        val ref = k.id.removePrefix("mk:")
        if (p == null) return "no_photo_support"
        if (p == "ok" || p == "pending") return if (frames.any { it.markRef == ref }) "pending" else "no_frame"
        return p
    }

    /**
     * A mark item of the dump. One that names its Moment (`momentId`) is restored BY that Moment item (`skipped: in_moment`, never a conflict, so a whole dump can be pushed). A mark with no Moment is restored through the
     * recorder with its real kind, time and photo state; the Moment of its second is rebuilt (a mark-only second is a MARK Moment). A forgotten second is never restored.
     */
    private suspend fun standaloneMark(e: DumpImportParser.Entry.StandaloneMark, dedup: Boolean, dryRun: Boolean, gen0: Long): Pair<String, String> {
        val k = e.mark
        if (e.momentId != null) return "skipped" to "in_moment"
        val momentId = try { MomentId.ofMs(k.atMs) } catch (x: IllegalArgumentException) { return "skipped" to "bad_time" }
        if (db.momentDao().tombstonedAmong(listOf(momentId)).isNotEmpty() || db.canvasSlice3Dao().momentFenced(k.atMs, k.atMs)) return "skipped" to "forgotten"
        if (db.canvasSlice3Dao().label(k.id) != null) return if (dedup) "skipped" to "dedup" else "conflict" to "exists"
        if (dryRun) return "would_create" to "ok"
        check(gen0)
        val ok = recordMark(k, photoState(k, emptyList())) && db.canvasSlice3Dao().label(k.id)?.atMs == k.atMs
        if (ok) build(listOf(momentId))
        return if (ok) "created" to "ok" else "failed" to "not_stored"
    }

    private fun check(gen0: Long) { if (generation() != gen0) throw Stopped() }

    /** null = free to create; `conflict` = something with an original id exists; `forgotten` = a Forget fence covers the second. */
    private suspend fun verdict(m: DumpImportParser.MomentItem): String? {
        if (db.momentDao().tombstonedAmong(listOf(m.id)).isNotEmpty() || db.canvasSlice3Dao().momentFenced(m.startMs, m.startMs + 999)) return "forgotten"
        if (db.momentDao().moment(m.id) != null) return "conflict"
        if (db.frameDao().byIds(m.frames.map { it.id }).isNotEmpty()) return "conflict"
        if (m.memories.isNotEmpty() && db.dumpDao().memoriesByIds(m.memories.map { it.id }).isNotEmpty()) return "conflict"
        if (m.marks.any { db.canvasSlice3Dao().label(it.id) != null }) return "conflict"
        return null
    }

    /** [frames, memories, marks, locations, 1 when complete]. */
    private suspend fun write(m: DumpImportParser.MomentItem, gen0: Long): IntArray {
        var nf = 0; var nm = 0; var nk = 0; var nl = 0; var ok = true
        val written = ArrayList<String>()
        // marks first: a photo that names its mark (frame_meta.markRef) links to it when it is committed
        for (k in m.marks) {
            check(gen0)
            if (recordMark(k, photoState(k, m.frames)) && db.canvasSlice3Dao().label(k.id)?.atMs == k.atMs) nk++ else ok = false
        }
        for (f in m.frames) {
            check(gen0)
            if (frames.store(f)) { nf++; written += f.id } else ok = false
        }
        for (x in m.memories) {
            check(gen0)
            if (x.frameId !in written) { ok = false; continue }
            if (memories.store(x)) nm++ else ok = false
        }
        check(gen0)
        val loc = m.location
        if (loc != null && locationEnabled()) {
            val anchor = written.firstOrNull()?.let { "FRAME" to it } ?: m.marks.firstOrNull()?.let { "MARK" to it.id }
            if (anchor != null && saveLocation(m, loc, anchor.first, anchor.second, gen0)) nl++
        }
        build(listOf(m.id))
        return intArrayOf(nf, nm, nk, nl, if (ok) 1 else 0)
    }

    private suspend fun saveLocation(m: DumpImportParser.MomentItem, l: DumpImportParser.Location, kind: String, anchorId: String, gen0: Long): Boolean {
        val dao = db.momentLocationDao(); val s3 = db.canvasSlice3Dao()
        val rev = s3.frameForgetRev()
        return db.withTransaction {
            when {
                !locationEnabled() || generation() != gen0 || s3.frameForgetRev() != rev -> false
                dao.momentTombstoned(m.id) > 0 || (kind == "FRAME" && dao.frameTombstoned(anchorId) > 0) -> false
                (if (kind == "FRAME") dao.frameAnchorExists(anchorId) else dao.markAnchorExists(anchorId)) == 0 -> false
                else -> dao.insertIfAbsent(MomentLocationEntity(m.id, l.atMs, kind, anchorId, l.lat, l.lon, l.accuracyM, l.fixAgeMs, SOURCE, l.speedClass, l.method, wall())) >= 0
            }
        }
    }

    companion object { const val SOURCE = "IMPORTED" }
}
