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
    override suspend fun store(frame: DumpImportParser.Frame): Boolean = withContext(Dispatchers.IO) {
        val ext = when (DumpReader.sniff(frame.photo)) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val file = File(RokidFrameInbox.archiveDir(app).apply { mkdirs() }, "${frame.id}.$ext")
        val gen = MemoryWipe.generation.get()
        writeStamped(file, FrameImages.forDisk(frame.photo), frame.capturedAtMs)
        val receipt = ReceiptMeta(
            wireFrameId = frame.id, capturedAtMs = frame.capturedAtMs, motion = frame.motion, dwell = frame.dwell, blur = frame.blur, dhash = frame.dhash, phash = frame.phash,
            width = frame.width, markRef = frame.markRef,
        )
        val t = now()
        val appender: com.rokid.context.data.frames.FrameAppender = { c, f, rows ->
            PipelineRecords.appendFrame(c, f, rows?.let { it.copy(meta = it.meta.copy(routedAt = t), state = it.state.copy(state = "EXTRACTED", lastError = null)) })
        }
        val r = RokidFrameInbox.ingestFile(app, file, frame.source, frame.placeLabel, appender, receipt, now, sceneActive = { false }, parkedForDataSaver = { false })
        if (r != InboxResult.STORED) { file.delete(); return@withContext false }
        if (MemoryWipe.generation.get() != gen) { file.delete(); return@withContext false } // Delete all ran during the write: the frame sweep of that wipe owns the rest
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
 * `no_members` (a cluster card: the dump does not carry its members), `pin_missing` (a user pin that is not on the phone), `history_off` (the Developer flag), `refused` (a fence).
 */
class ImportCardStore(private val history: PlaceCardHistory, private val pinIds: () -> Set<String>, private val now: () -> Long = System::currentTimeMillis) : ImportCardWriter {
    override suspend fun store(card: DumpImportParser.Card): String {
        if (!history.isEnabled) return "history_off"
        if (card.kind == HistoryKind.PIN_CLUSTER) return "no_members"
        if (history.get(card.id) != null) return "exists"
        if (card.at < now() - PlaceCardHistory.RETAIN_MS) return "expired"
        if (UserPlace.isUserId(card.placeId) && card.placeId !in pinIds()) return "pin_missing"
        val e = PlaceHistoryEvent(card.kind, card.placeId, card.title, emptyList(), card.frameId, card.cardId, null, card.delivery, history.capture(), importedId = card.id, importedAt = card.at)
        return if (history.add(e) != null) "ok" else "refused"
    }
}
