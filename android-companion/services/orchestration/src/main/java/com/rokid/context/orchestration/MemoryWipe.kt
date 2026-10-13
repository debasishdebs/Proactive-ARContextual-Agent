package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemoryMaintenance
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Settings → Memory › Delete all (spec §6.1.1). Endpoints, keys, prompts, settings and user lists are kept. */
object MemoryWipe {
    /**
     * Bumped first by every Delete all. A writer that read it before its work began (a queue job before its claim, an
     * ask before retrieval) re-checks it inside the transaction that writes, and drops the write if it moved
     * (PipelineRecords.appendFact, AnswerService.answerOrThrow). Process memory is enough: work that started in an
     * earlier process cannot still be writing, and its queued jobs are deleted with the queue file.
     */
    val generation = AtomicLong(0)

    /**
     * Stops the pipeline (fence, cancel the work, drop every queued job), deletes every memory row (with all vectors
     * and embedding spaces), every frame JPEG (the rows' paths plus [RokidFrameInbox.FRAME_DIRS]), the legacy logs and
     * the exports, then VACUUMs (crash-safe FTS rebuild) and truncates the WAL. The pipeline restarts unless it was
     * paused. Keeps `cxr_received_frames.txt`, so the glasses don't re-send frames from before the wipe.
     * Returns the frame files deleted.
     */
    suspend fun deleteAll(context: Context, db: ContextDatabase = MemoryGraph.get(context).db): Int = withContext(Dispatchers.IO) {
        generation.incrementAndGet()
        val scheduler = OrchestrationScheduler(context)
        scheduler.pausePipeline()
        try {
            QueueRuntimeStore.clearAll(context)
            val wipe = db.wipeDao()
            val paths = wipe.framePaths()
            wipe.wipeMemory()
            val listed = paths.filter { it.isNotBlank() }.map(::File)
            val folders = RokidFrameInbox.FRAME_DIRS.flatMap { name -> File(context.filesDir, name).walkTopDown().filter { it.isFile }.toList() }
            val deleted = (listed + folders).distinctBy { it.absolutePath }.count { it.exists() && it.delete() }
            LegacyImporter.LEGACY_FILES.forEach { File(context.filesDir, it).delete() }
            MemoryExport.dir(context).listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            MemoryMaintenance.vacuum(db)
            MemoryMaintenance.checkpoint(db)
            deleted
        } finally {
            if (AppConfigStore.isPipelineEnabled(context)) scheduler.resumePipeline()
        }
    }
}
