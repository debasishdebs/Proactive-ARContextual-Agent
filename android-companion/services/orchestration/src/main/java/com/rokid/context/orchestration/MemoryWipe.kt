package com.rokid.context.orchestration

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.rokid.context.ai.SafeLog
import com.rokid.context.connectors.actions.ProposalNotifications
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemoryMaintenance
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.canvas.trace.FencedFiles
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Settings → Memory › Delete all (spec §6.1.1). Endpoints, keys, prompts, settings and user lists are kept. */
object MemoryWipe {
    /** `files/photo-eval`: the debug photo test's labels and results (also excluded from backup). */
    const val PHOTO_EVAL_DIR = "photo-eval"

    /** Plan-state prefs name (PlanSender.PREFS reads this) and the Collect prefs: both are cleared by Delete all. */
    const val PLAN_STATE_PREFS = "canvas_plan_state"
    private val WIPED_PREFS = listOf(PLAN_STATE_PREFS, com.rokid.context.canvas.trace.CollectSession.PREFS)

    /**
     * Bumped first by every Delete all. A writer that read it before its work began (a queue job before its claim, an
     * ask before retrieval) re-checks it inside the transaction that writes, and drops the write if it moved
     * (PipelineRecords.appendFact, AnswerService.answerOrThrow). Process memory is enough: work that started in an
     * earlier process cannot still be writing, and its queued jobs are deleted with the queue file.
     */
    val generation = AtomicLong(0)

    /** Test seam: runs right after the deletion epoch is committed and before anything is deleted (a test throws here to model a crash). */
    @Volatile internal var afterEpochCommit: (DeletionEpoch) -> Unit = {}

    /**
     * Stops the pipeline (fence, cancel the work, drop every queued job), deletes every memory row (with all vectors
     * and embedding spaces), every frame JPEG (the rows' paths plus [RokidFrameInbox.FRAME_DIRS]), the legacy logs and
     * the exports, then VACUUMs (crash-safe FTS rebuild) and truncates the WAL. The pipeline restarts unless it was
     * paused. Keeps `cxr_received_frames.txt`, so the glasses don't re-send frames from before the wipe.
     * Returns the frame files deleted. Not cancellable once started (final-review I2): it is a privacy action, so
     * leaving the Memory screen mid-wipe (ViewModel cleared) must not stop it part-way; the caller sees its
     * cancellation only after the wipe has finished.
     */
    suspend fun deleteAll(context: Context, db: ContextDatabase = MemoryGraph.get(context).db): Int = withContext(Dispatchers.IO + NonCancellable) {
        // S57: the persisted deletion epoch (`counter + 1`, `wipedAtMs`) is committed BEFORE anything is deleted, so a crash or power loss mid-wipe still
        // leaves a durable record that everything recorded under the old counter is stale. If it cannot be committed nothing is deleted.
        afterEpochCommit(DeletionEpochStore.of(context).commitWipe(System.currentTimeMillis()))
        // Fix round 3: never import here (a failing import must not block Delete all). An import in progress is
        // waited for (it holds the lock until it finishes or throws); then it is marked done and never runs again.
        MemoryGraph.markLegacyImportDone(context)
        AskPhotoPolicy.set(context, false) // Delete all withdraws the consent to send photos with questions (like image intent); re-enabling is one tap
        generation.incrementAndGet()
        val wipedAt = System.currentTimeMillis()
        val wipedElapsed = SystemClock.elapsedRealtime()
        // Fix round 2 I2: every file in the inbox when the fence is marked is pre-wipe, whatever its stamp says.
        val inboxAtMark = RokidFrameInbox.inboxDir(context).list().orEmpty().toSet()
        val marked = WipeFence.markStarted(context.filesDir, wipedAt, wipedElapsed)
        val scheduler = OrchestrationScheduler(context)
        scheduler.pausePipeline()
        try {
            QueueRuntimeStore.clearAll(context) // bumps the generation again, under the queue lock (fix round 2 N1)
            val wipe = db.wipeDao()
            val paths = wipe.framePaths()
            val proposals = wipe.wipeMemory()
            // P7xP8 I1: as Forget does (ForgetActions.apply), an armed reminder alarm and a posted notification still
            // hold the redacted text: cancel them for every proposal the wipe saw.
            try {
                proposals.forEach { ReminderExecutor.cancelAlarm(context, it) }
            } catch (e: Exception) {
                Log.w("MemoryWipe", "alarms not cancelled: ${SafeLog.describe(e)}")
            }
            try {
                ProposalNotifications.cancel(context, proposals)
            } catch (e: Exception) {
                Log.w("MemoryWipe", "notifications not cancelled: ${SafeLog.describe(e)}")
            }
            val deleted = WipeFence.mutex.withLock {
                val listed = paths.filter { it.isNotBlank() }.map(::File)
                val inbox = RokidFrameInbox.inboxDir(context)
                // Fix round 1/2 I2: spared only if stored after the mark: not in the snapshot and stamped at or after the fence.
                val folders = RokidFrameInbox.FRAME_DIRS.flatMap { name ->
                    val dir = File(context.filesDir, name)
                    dir.walkTopDown().filter { f ->
                        f.isFile && !(dir == inbox && f.parentFile == inbox && f.name !in inboxAtMark && f.lastModified() >= wipedAt)
                    }.toList()
                }
                val n = (listed + folders).distinctBy { it.absolutePath }.count { it.exists() && it.delete() }
                // Fix round 1 I3: the deleted frames may have freed the space the fence record needs; else it fails closed.
                if (!marked && !WipeFence.markStarted(context.filesDir, wipedAt, wipedElapsed)) Log.w("MemoryWipe", "Delete all fence unknown: frames are held until it is recorded")
                n
            }
            LegacyImporter.LEGACY_FILES.forEach { File(context.filesDir, it).delete() }
            MemoryExport.dir(context).listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            // Final review I3: the debug photo test's labels (queries.json, the user's free text about their photos) and results.
            File(context.filesDir, PHOTO_EVAL_DIR).deleteRecursively()
            // Slice 3a: sealed traces, pulled imports and orphan tmp files (the tables went with wipeMemory). The epoch above already refuses a fenced publish.
            FencedFiles.DELETE_ALL_DIRS.forEach { File(context.filesDir, it).deleteRecursively() }
            // Slice 3a privacy: the persisted capture-plan state and the Collect session window go too.
            WIPED_PREFS.forEach { context.applicationContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
            MemoryMaintenance.vacuum(db)
            MemoryMaintenance.checkpoint(db)
            deleted
        } finally {
            if (AppConfigStore.isPipelineEnabled(context)) scheduler.resumePipeline()
        }
    }
}
