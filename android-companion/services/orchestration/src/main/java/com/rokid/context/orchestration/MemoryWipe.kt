package com.rokid.context.orchestration

import android.content.Context
import android.os.SystemClock
import com.rokid.context.core.log.PcLog
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
    /** The plan state's generation counter: not personal data, and the glasses keep their generation across a Delete all, so the phone's must keep counting from it (else every later publish is stale there). */
    const val PLAN_GEN_KEY = "gen"

    /** 3b WP2: the laptop helper's capability grants (`py_exec`, default off). Delete all clears them, so a wipe always leaves every grant off; the phone's `HelperGrants.PREFS` is the same name (a phone test pins it). */
    const val HELPER_GRANTS_PREFS = "canvas_helper_grants"
    /** The phone's signal configuration (user-written expressions): cleared here on disk, and in memory by the `ForgetListener` the phone registers. */
    const val SIGNAL_CONFIG_PREFS = "signal_config"
    /** v3.0 proactive loop (shadow): the Suggestions switches and the encrypted would-show / never-list / counter store (ids and tokens only). Delete all clears both (ruling 6); the in-memory copies by a `ForgetListener`. */
    const val PROACTIVE_SETTINGS_PREFS = "proactive_settings"
    const val PROACTIVE_SHADOW_PREFS = "proactive_shadow"
    const val PROACTIVE_DISMISS_MARKER_PREFS = "proactive_dismiss_marker"

    /** Clears one of [WIPED_PREFS]; the plan state keeps only its generation counter. */
    fun clearWipedPrefs(prefs: android.content.SharedPreferences, name: String): Boolean {
        val keep = if (name == PLAN_STATE_PREFS) prefs.getLong(PLAN_GEN_KEY, 0L).takeIf { it > 0 } else null
        val e = prefs.edit().clear()
        if (keep != null) e.putLong(PLAN_GEN_KEY, keep)
        return e.commit()
    }

    /** v1.0.1 opt-in street map: its switch and consent (the phone's `OnlineMapStore.PREFS`) and its tile cache folder under `cacheDir` (the phone's `OnlineMapGraph.CACHE_DIR`); a phone test pins both names. Plain strings: this module cannot see phone classes. */
    const val ONLINE_MAP_PREFS = "online_map"
    const val ONLINE_TILES_DIR = "online_tiles"

    /** The prefs Delete all clears (a test pins it to [com.rokid.context.memory.forget.DeletionGraph.wipedPrefs]). */
    val WIPED_PREFS = listOf(PLAN_STATE_PREFS, com.rokid.context.canvas.trace.CollectSession.PREFS, HELPER_GRANTS_PREFS, MemoryExport.CLEANUP_PREFS, SIGNAL_CONFIG_PREFS, PROACTIVE_SETTINGS_PREFS, PROACTIVE_SHADOW_PREFS, PROACTIVE_DISMISS_MARKER_PREFS, ONLINE_MAP_PREFS)

    /**
     * Bumped first by every Delete all. A writer that read it before its work began (a queue job before its claim, an
     * ask before retrieval) re-checks it inside the transaction that writes, and drops the write if it moved
     * (PipelineRecords.appendFact, AnswerService.answerOrThrow). Process memory is enough: work that started in an
     * earlier process cannot still be writing, and its queued jobs are deleted with the queue file.
     */
    val generation = AtomicLong(0)

    private val resumeLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Start-up recovery (S57): a Delete all whose epoch was committed but whose cleanup did not finish (a crash, or a deletion that failed) is finished now. The cleanup
     * is idempotent. Returns whether a pending wipe was found. Safe to call from several places; one runs at a time.
     */
    suspend fun resumeIfPending(context: Context, db: ContextDatabase = MemoryGraph.get(context).db): Boolean {
        if (!DeletionEpochStore.of(context).wipePending()) return false
        if (!resumeLock.tryLock()) return true
        try { deleteAll(context, db, resume = true) } finally { resumeLock.unlock() }
        return true
    }

    /** Test seam: the wipe's own pin cascade (a test makes it fail to prove a failing pin deletion never keeps the whole wipe pending). */
    @Volatile internal var pinSettle: suspend (Context, String) -> Int? = { c, op -> com.rokid.context.work.frames.place.PinMomentLinks.settle(c, op) }

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
    suspend fun deleteAll(context: Context, db: ContextDatabase = MemoryGraph.get(context).db, resume: Boolean = false, confirmedPins: Set<String>? = null): Int = withContext(Dispatchers.IO + NonCancellable) {
        // The exclusive side of the DataOpGate: a running export is waited for (30 s), else DataOpGate.ExportInProgress and nothing is deleted (the caller says "try again").
        DataOpGate.shared.delete { wipeNow(context, db, resume, confirmedPins) }.also { runCatching { com.rokid.context.work.frames.place.PlaceRecovery.kick(context) } } // a place memory still marked is retried (its row is gone: the mark just clears)
    }

    private suspend fun wipeNow(context: Context, db: ContextDatabase, resume: Boolean, confirmedPins: Set<String>?): Int {
        com.rokid.context.work.frames.place.ShadowCaches.clearAll() // the Place Guide shadow's in-memory vectors (derived from photos) go first, before anything is deleted; the Forget listeners clear them again at the end
        // Slice 1.4.2: the pins with a reference Moment are armed (durably, for THIS wipe) before anything is deleted; if that cannot be stored nothing is deleted (PinArmFailed). A resume keeps its earlier committed arming.
        val pinOp = com.rokid.context.work.frames.place.PinMomentLinks.beginWipe(context, resume, confirmedPins)
        try { return wipeNowArmed(context, db, resume, pinOp) } finally { com.rokid.context.work.frames.place.PinMomentLinks.endWipe(pinOp) }
    }

    private suspend fun wipeNowArmed(context: Context, db: ContextDatabase, resume: Boolean, pinOp: String): Int = withContext(Dispatchers.IO + NonCancellable) {
        // S57: the persisted deletion epoch (`counter + 1`, `wipedAtMs`) is committed BEFORE anything is deleted, so a crash or power loss mid-wipe still
        // leaves a durable record that everything recorded under the old counter is stale. If it cannot be committed nothing is deleted.
        // The same commit writes the "wipe pending" marker: if the process dies before the cleanup below finished, the next start finishes it ([resumeIfPending]).
        var pinsCommitted = true
        if (!resume) {
            val epoch = try { DeletionEpochStore.of(context).commitWipe(System.currentTimeMillis()) } catch (t: Throwable) {
                runCatching { com.rokid.context.work.frames.place.PinMomentLinks.abortWipe(context, pinOp) } // nothing was deleted: the arming is dropped and the pins stay
                throw t
            }
            pinsCommitted = runCatching { com.rokid.context.work.frames.place.PinMomentLinks.wipeCommitted(context, pinOp) }.getOrDefault(false) // from here on the wipe is real: a crash is finished from the committed arming
            afterEpochCommit(epoch)
        } else pinsCommitted = com.rokid.context.work.frames.place.PinMomentLinks.resumePromoted(pinOp) // a resume whose promoted pin arming was not stored keeps the wipe pending: the pin deletion is still owed
        var leftovers = 0
        if (!pinsCommitted) { leftovers++; PcLog.w("MemoryWipe", "pin removal not marked: the resume completes it") } // the wipe stays pending, so the next start resumes and promotes the pin arming // artefacts that still exist after their deletion was attempted: the marker stays and the next start retries
        // Fix round 3: never import here (a failing import must not block Delete all). An import in progress is
        // waited for (it holds the lock until it finishes or throws); then it is marked done and never runs again.
        MemoryGraph.markLegacyImportDone(context)
        AskPhotoPolicy.set(context, false) // Delete all withdraws the consent to send photos with questions (like image intent); re-enabling is one tap
        generation.incrementAndGet()
        // A shadow attempt that read the OLD generation before the first clear (top of wipeNow) can still store a photo-derived vector until now; with the bump in place a new store is refused, so clear again.
        com.rokid.context.work.frames.place.ShadowCaches.clearAll()
        if (!com.rokid.context.memory.forget.lifecycle.ForgetV2.on) com.rokid.context.orchestration.forget.LeaseRevocation.all("delete all") // M3: Delete all revokes every read lease (the generation check is the second line); with forgetV2 the executor revokes in `wipeMemory`
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
            val proposals = if (com.rokid.context.memory.forget.lifecycle.ForgetV2.on) com.rokid.context.memory.forget.lifecycle.ForgetExecutor.wipeMemory(db, DataOpGate.shared.leases) else wipe.wipeMemory()
            com.rokid.context.orchestration.forget.ForgetActions.removals.incrementAndGet() // committed: an open canvas page reads again
            // P7xP8 I1: as Forget does (ForgetActions.apply), an armed reminder alarm and a posted notification still
            // hold the redacted text: cancel them for every proposal the wipe saw.
            try {
                proposals.forEach { ReminderExecutor.cancelAlarm(context, it) }
            } catch (e: Exception) {
                PcLog.w("MemoryWipe", "alarms not cancelled: ${SafeLog.describe(e)}")
            }
            try {
                ProposalNotifications.cancel(context, proposals)
            } catch (e: Exception) {
                PcLog.w("MemoryWipe", "notifications not cancelled: ${SafeLog.describe(e)}")
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
                val all = (listed + folders).distinctBy { it.absolutePath }
                val n = all.count { it.exists() && it.delete() }
                leftovers += all.count { it.exists() }
                // Fix round 1 I3: the deleted frames may have freed the space the fence record needs; else it fails closed.
                if (!marked && !WipeFence.markStarted(context.filesDir, wipedAt, wipedElapsed)) PcLog.w("MemoryWipe", "Delete all fence unknown: frames are held until it is recorded")
                n
            }
            LegacyImporter.LEGACY_FILES.forEach { File(context.filesDir, it).let { f -> f.delete(); if (f.exists()) leftovers++ } }
            MemoryExport.dir(context).listFiles()?.filter { it.isFile }?.forEach { f -> f.delete(); if (f.exists()) leftovers++ }
            // Final review I3: the debug photo test's labels (queries.json, the user's free text about their photos) and results.
            File(context.filesDir, PHOTO_EVAL_DIR).let { it.deleteRecursively(); if (it.exists()) leftovers++ }
            // v1.0.1: the street map's downloaded tiles (areas the user looked at) live in the cache folder.
            File(context.cacheDir, ONLINE_TILES_DIR).let { it.deleteRecursively(); if (it.exists()) leftovers++ }
            // Slice 3a: sealed traces, pulled imports and orphan tmp files (the tables went with wipeMemory). The epoch above already refuses a fenced publish.
            FencedFiles.DELETE_ALL_DIRS.forEach { File(context.filesDir, it).let { d -> d.deleteRecursively(); if (d.exists()) leftovers++ } }
            // Final pass 10: raw traces staged under the EXTERNAL files dir (adb push) are sensor data too; the resume of an interrupted wipe comes through here as well.
            context.getExternalFilesDir(null)?.let { File(it, FencedFiles.STAGING_DIR) }?.let { d -> d.deleteRecursively(); if (d.exists()) leftovers++ }
            // Slice 3a privacy: the persisted capture-plan state and the Collect session window go too.
            WIPED_PREFS.forEach { if (!clearWipedPrefs(context.applicationContext.getSharedPreferences(it, Context.MODE_PRIVATE), it)) leftovers++ }
            // Place Guide: the fetched places and their recency go; seeded areas stay and pause zones are never touched (they are not in places.db).
            try { com.rokid.context.work.frames.place.PlaceGraph.deleteAll(context) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: com.rokid.context.work.frames.place.HistoryDeletionPending) {
                // P1-1: the history deletion is a bounded OWED obligation (durable, retried at every start by ForgetStartup.settleHistory). It is NOT a wipe leftover: counting it would keep wipePending and re-run the whole wipe, erasing newer memories, at every start.
                PcLog.w("MemoryWipe", "place history deletion owed: retried at the next start")
            } catch (e: Exception) {
                leftovers++; PcLog.w("MemoryWipe", "place pack not cleared: ${SafeLog.describe(e)}")
            }
            // Slice 1.4.2: a pin whose reference Moment was just wiped is forgotten with it. Its deletion is owed by the DURABLE committed pin arming (marked above; a resume whose promotion was not stored already keeps the wipe
            // pending), which ForgetStartup.settle retries at every start. A failure here is therefore NOT a wipe leftover: counting it would re-run the whole wipe (erasing newer memories) at every start for as long as the pin deletion fails.
            try { if (pinSettle(context, pinOp) == null) PcLog.w("MemoryWipe", "pin cascade not finished: retried at the next start") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                PcLog.w("MemoryWipe", "pin cascade not done: ${SafeLog.describe(e)}")
            }
            MemoryMaintenance.vacuum(db)
            MemoryMaintenance.checkpoint(db)
            // Slice 3a (S58): files and devices follow through the registered listeners (the glasses get the NEW epoch). After everything above; never throws.
            if (leftovers == 0) DeletionEpochStore.of(context).clearWipePending() else PcLog.w("MemoryWipe", "Delete all left $leftovers artefact(s): the cleanup is retried at the next start")
            com.rokid.context.memory.forget.ForgetListeners.deleteAll()
            deleted
        } finally {
            if (AppConfigStore.isPipelineEnabled(context)) scheduler.resumePipeline()
        }
    }
}
