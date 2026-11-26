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
