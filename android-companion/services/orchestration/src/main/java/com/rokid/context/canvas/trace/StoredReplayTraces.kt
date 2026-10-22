package com.rokid.context.canvas.trace

import com.rokid.context.canvas.plan.ReplayTrace
import com.rokid.context.canvas.plan.ReplayTraces
import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.TraceBounds
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.memory.db.CanvasSlice3Dao
import java.io.File

/**
 * Binds [ReplayTraces] to the stored traces (`files/canvas_traces/<traceId>`, published by [TraceImport]). A trace that is unknown, tombstoned or whose files are
 * unreadable loads as null (the replay then refuses it as an unknown trace). Events are re-read from the chunk files on every pass, one chunk at a time, never all at once.
 */
class StoredReplayTraces(
    private val dao: CanvasSlice3Dao,
    private val tracesRoot: File,
    /** The persisted deletion epoch NOW. A trace recorded under another install or an older counter (a Delete all cut short) is refused (S57). Null only in tests that do not model epochs. */
    private val epoch: (() -> com.rokid.context.memory.db.DeletionEpoch)? = null,
) : ReplayTraces {
    override suspend fun load(traceId: String): ReplayTrace? {
        if (!TraceBounds.isTraceId(traceId)) return null
        val row = dao.trace(traceId) ?: return null
        if (row.tombstoned) return null
        epoch?.invoke()?.let { e -> if (row.installId != e.installId || row.deletionCounter != e.counter) return null }
        val manifest = Manifest.decodeStrict(row.manifestJson).getOrNull() ?: return null
        val dir = File(tracesRoot, TraceFiles.dirName(traceId))
        if (!dir.isDirectory) return null
        val events = {
            (0 until manifest.chunkCount).asSequence().flatMap { seq ->
                val tc = TraceFiles.decodeFile(File(dir, TraceFiles.chunkFile(seq)).readText(Charsets.UTF_8)).getOrThrow()
                ChunkCodec.decode(tc).getOrThrow().events.asSequence()
            }
        }
        return ReplayTrace.of(manifest, row.revision, row.startedAtMs, row.endedAtMs ?: row.startedAtMs, events)
    }
}
