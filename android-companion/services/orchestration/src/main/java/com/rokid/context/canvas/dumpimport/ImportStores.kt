package com.rokid.context.canvas.dumpimport

import com.rokid.context.canvas.dump.DumpReader
import com.rokid.context.canvas.dump.DumpSource

import android.content.Context
import com.rokid.context.data.frames.InboxResult
import com.rokid.context.data.frames.ReceiptMeta
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.ModelAttribution
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.rokid.context.canvas.moment.SceneBuilder
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.ObjectDao
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.work.frames.place.HistoryKind
import com.rokid.context.work.frames.place.PlaceCardHistory
import com.rokid.context.work.frames.place.PlaceHistoryEvent

/**
 * Developer IMPORT, the production photo writer: the bytes land in the frame archive under the ORIGINAL frame id and are stamped with the ORIGINAL capture time, then go through the app's normal frame ingest
 * ([RokidFrameInbox.ingestFile] with a receipt, the same call a glasses photo takes): `frame`, `frame_meta` (capturedAtMs = the original second), `frame_state`, the Forget fence at commit, the Moment trigger.
 * Two things differ from a live capture and both are the owner's rulings: the frame state is written already EXTRACTED and `routedAt` is set, so no description or cluster-summary model call ever runs on it
 * (the caption comes from the import as written). [writeStamped] is the phone's atomic JPEG writer (it stamps the time before the rename).
 */
class ImportFrameStore(
    private val app: Context,
    private val writeStamped: (File, ByteArray, Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : ImportFrameWriter {
    override suspend fun store(frame: DumpImportParser.Frame): Boolean = store(frame, MemoryWipe.generation.get())

    /** [gen0] is the Delete all generation the IMPORT REQUEST started with (round 4): a wipe between the importer's check and this write is caught too. */
    override suspend fun store(frame: DumpImportParser.Frame, gen0: Long): Boolean = withContext(Dispatchers.IO) {
        val ext = when (DumpReader.sniff(frame.photo)) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val file = File(RokidFrameInbox.archiveDir(app).apply { mkdirs() }, "${frame.id}.$ext")
        // never replace an archive file this import did not create (a live capture may have committed or be committing the same id): publish by hard link (fails when the name exists), never by rename-over
        if (listOf("jpg", "png", "webp").any { File(file.parentFile, "${frame.id}.$it").exists() }) return@withContext false
        if (MemoryWipe.generation.get() != gen0) return@withContext false
        val tmp = File(file.parentFile, ".imp-${frame.id}-${System.currentTimeMillis()}.$ext") // the creation wall time is in the name: the sweep reads it, not the mtime (writeStamped stamps the ORIGINAL capture time)
        com.rokid.context.data.frames.ImportTempName.begin(tmp.name)
        try {
            writeStamped(tmp, FrameImages.forDisk(frame.photo), frame.capturedAtMs)
            try { java.nio.file.Files.createLink(file.toPath(), tmp.toPath()) }
            catch (e: java.nio.file.FileAlreadyExistsException) { return@withContext false }
            catch (e: Exception) { try { java.nio.file.Files.move(tmp.toPath(), file.toPath()) } catch (x: Exception) { return@withContext false } } // no hard links here: a move that refuses an existing target
        } finally { tmp.delete(); com.rokid.context.data.frames.ImportTempName.end(tmp.name) }
        // from here the file is OURS (we created the name): it is removed only on a refusal of this attempt
        val receipt = ReceiptMeta(
            wireFrameId = frame.id, capturedAtMs = frame.capturedAtMs, motion = frame.motion, dwell = frame.dwell, blur = frame.blur, dhash = frame.dhash, phash = frame.phash,
            width = frame.width, markRef = frame.markRef,
        )
        val t = now()
        val appender: com.rokid.context.data.frames.FrameAppender = { c, f, rows ->
            PipelineRecords.appendFrameFenced(c, f, rows?.let { it.copy(meta = it.meta.copy(routedAt = t), state = it.state.copy(state = "EXTRACTED", lastError = null)) }, wiped = { MemoryWipe.generation.get() != gen0 })
        }
        val r = RokidFrameInbox.ingestFile(app, file, frame.source, frame.placeLabel, appender, receipt, now, sceneActive = { false }, parkedForDataSaver = { false })
        if (r != InboxResult.STORED) { file.delete(); return@withContext false }
        // a Delete all after the commit: that wipe's frame sweep owns the row and the file
        true
    }
}

/**
 * Developer IMPORT, the production text writer: the memory goes through [PipelineRecords.appendFact] (the provenance gate, the Delete all generation, the search index, the attribution row) with the
 * attribution `imported` (no model call) and the original id, creation time and review state, then the `frame_source` edge the extraction writes, so a Forget of the photo reaches the text like any other.
 */
class ImportMemoryStore(private val app: Context) : ImportMemoryWriter {
    override suspend fun store(memory: DumpImportParser.Memory): Boolean {
        val ok = PipelineRecords.appendFact(
            app, memory.id, memory.frameId, memory.text, memory.type, memory.tags, memory.confidence, memory.tier, memory.review, memory.createdAt,
            derived = memory.derived, attribution = ModelAttribution(ATTRIBUTION),
        )
        if (ok) MemoryGraph.get(app).db.frameSourceDao().insertAll(listOf(FrameSourceEntity(memory.id, memory.frameId, null, "FACT")))
        return ok
    }

    companion object { const val ATTRIBUTION = "imported" }
}

/**
 * Developer IMPORT, the place card writer: a card-history row through the one guarded writer [PlaceCardHistory.add] (its fences: `frame_forget_rev`, Delete all generation, the Place Guide fence, a forgotten photo, a removed
 * pin; the `place_link` row of a single-place card of a user pin is made by that writer, nowhere else) with the ORIGINAL row id and time. A fixed token comes back: `ok`, `exists`, `expired` (older than the 90-day history),
 * `no_members` (a cluster card: the dump does not carry its members), `pin_missing` (a user pin that is not on the phone), `history_off` (the Developer flag), `moment_gone` (its Moment was forgotten or removed, checked INSIDE the add transaction), `refused` (a fence).
 */
class ImportCardStore(private val history: PlaceCardHistory, private val pinIds: () -> Set<String>, private val now: () -> Long = System::currentTimeMillis) : ImportCardWriter {
    override suspend fun store(card: DumpImportParser.Card, momentHere: suspend () -> Boolean): String {
        if (!history.isEnabled) return "history_off"
        if (card.kind == HistoryKind.PIN_CLUSTER) return "no_members"
        if (history.get(card.id) != null) return "exists"
        if (card.at < now() - PlaceCardHistory.RETAIN_MS) return "expired"
        if (UserPlace.isUserId(card.placeId) && card.placeId !in pinIds()) return "pin_missing"
        val e = PlaceHistoryEvent(card.kind, card.placeId, card.title, emptyList(), card.frameId, card.cardId, null, card.delivery, history.capture(), importedId = card.id, importedAt = card.at)
        var gone = false
        val id = history.add(e.copy(momentGuard = { momentHere().also { if (!it) gone = true } }))
        return if (id != null) "ok" else if (gone) "moment_gone" else "refused"
    }
}

/**
 * Developer IMPORT, the Scene writer: the normal [SceneBuilder] (a rebuild of the clock hours of a span; the same builder the 15-minute cadence runs, with its fences) and the guarded derived write
 * ([ObjectAudit.writeDerivedGuarded], the call a summary derive makes: Delete all generation, parent exists, inputs unchanged) with the attribution `imported`.
 */
class ImportSceneStore(private val db: ContextDatabase, private val builder: SceneBuilder, private val fence: () -> DaoFence, clock: () -> Long = System::currentTimeMillis) : ImportSceneWriter {
    private val audit = ObjectAudit(db.objectDao(), clock)
    override suspend fun rebuild(fromMs: Long, toMs: Long) { builder.rebuildWindow(fromMs, toMs) }
    override suspend fun derive(sceneId: String, field: String, text: String): Boolean {
        if (field != DerivedField.SUMMARY && field != DerivedField.CAPTION) return false
        val scene = db.sceneDao().scene(sceneId) ?: return false
        return audit.writeDerivedGuarded(scene, field, text, null, SceneBuilder.BUILD_REV, fence(), audit.inputSeq(scene), ModelAttribution(ImportMemoryStore.ATTRIBUTION)) == ObjectDao.WriteOutcome.WRITTEN
    }
}
