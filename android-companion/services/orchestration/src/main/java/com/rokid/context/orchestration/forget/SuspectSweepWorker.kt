package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.embed.LocalModelPower
import com.rokid.context.core.log.PcLog
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.model.CallClass
import com.rokid.context.model.PayloadSources
import com.rokid.context.orchestration.BulkGate
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.provenance.ProvenanceGate
import com.rokid.context.work.budget.BackgroundWork
import com.rokid.context.work.frames.KeyframeGraph
import com.rokid.context.work.frames.KeyframePipeline
import com.rokid.context.work.frames.place.PlaceGraph
import kotlin.coroutines.cancellation.CancellationException

/**
 * Roadmap row 8, user ruling 2026-11-16: a "suspect description" is a pre-fix photo description of unknown delivery that the user MARKED for review
 * (Diagnostics > Suspect descriptions; the mark is the confirmation, nothing unmarked is touched). The night job treats each marked suspect by its photo:
 *  - photo expired (no `frame` row left): the description is removed ([ForgetService.removeDescriptions]);
 *  - photo still stored: the photo is queued for the ordinary describe path (the mark already stops the old text from counting as a description) and
 *    described with a BACKFILL vision call through the normal gates; once every stored photo of the suspect has a description that carries delivery proof, the suspect is removed.
 * One unique 24 h job (charger and battery not low), cancelled with the pipeline (it makes AI calls), sharing the night job's run lock so the two never overlap.
 * Idempotent: the work is derived from the database each run; a failed describe stays queued with the pipeline's own attempts and cooldown, the suspect stays until replaced.
 * A failed run retries with WorkManager's backoff, at most [MAX_ATTEMPTS] runs, then waits for the next night.
 */
class SuspectSweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!AppConfigStore.isPipelineEnabled(applicationContext)) return Result.success()
        return NightRunLock.tryRun { locked() } ?: run { PcLog.i(TAG, "PcSuspect skipped: a night run is in progress"); Result.success() }
    }

    private suspend fun locked(): Result = try {
        val graph = MemoryGraph.get(applicationContext)
        val keyframes = KeyframeGraph.get(applicationContext)
        val gate = BulkGate({ LocalModelPower.read(applicationContext) }, { keyframes.power.mayRunBackground(BackgroundWork.BACKFILL) })
        val service = ForgetService(graph.db, graph.proposals)
        val sweep = SuspectSweep(
            db = graph.db, pipeline = keyframes.pipeline, bulkAllowed = gate::allowed,
            zoneBlocked = PlaceGraph.fence::blocked,
            remove = { ids ->
                // the exclusive side of the DataOpGate (an export in progress is waited for), like retention; the ForgetActions follow the committed cascade
                DataOpGate.shared.delete {
                    val r = service.removeDescriptions(ids)
                    ForgetActions.apply(applicationContext, r)
                    r.forgotten.size
                }
            },
        )
        val o = sweep.run()
        if (o.failed && runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.success()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        PcLog.w(TAG, "suspect sweep failed: ${SafeLog.describe(e)}")
        if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "SuspectSweepWorker"
        const val NAME = "suspect_sweep"
        const val MAX_ATTEMPTS = 3
    }
}

/** The sweep's logic, without WorkManager or the app graphs so that tests drive it with fakes. */
internal class SuspectSweep(
    private val db: ContextDatabase,
    private val pipeline: KeyframePipeline,
    /** The bulk gate: charging or battery above 70 %, thermal below MODERATE. Closed: nothing is described (removals of expired descriptions need no model and still run). */
    private val bulkAllowed: () -> Boolean = { true },
    /** The Place Guide zone fence (production: `PlaceGraph.fence::blocked`): while held nothing is queued or described. */
    private val zoneBlocked: () -> Boolean = { false },
    /** Removes the descriptions [ids] (ForgetService + the post-commit actions); returns how many were removed. */
    private val remove: suspend (List<String>) -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val drainMs: Long = DRAIN_MS,
    /** At most this many model calls a night (a call carries up to 3 photos): the thousands of old rows drain over many nights, not one. */
    private val maxCalls: Int = MAX_CALLS,
    private val callClass: CallClass = CallClass.BACKFILL,
) {
    /** [failed]: photos wait in the describe queue, the gate was open, and the drain described none (an outage or a refusal): the worker retries with backoff. */
    data class Outcome(val removedExpired: Int, val queued: Int, val replaced: Int, val described: Int, val left: Int, val failed: Boolean, val gate: Boolean)

    private val dao get() = db.forgetDao()
    private val gate = ProvenanceGate(db, { zoneBlocked() }) { MemoryWipe.generation.get() }

    suspend fun run(): Outcome {
        val generation = MemoryWipe.generation.get()
        // 1. Expired photos: the description goes (no model call, so the battery gate does not apply).
        val removedExpired = removeAll(dao.markedSuspectsExpired(SCAN), generation)
        // 2. Suspects an earlier run (or the night job) already re-described.
        var replaced = replaceDescribed(generation)
        // 3. Stored photos: queue and describe, only on a charged, cool phone, never in a zone, never during a capture burst.
        var queued = 0
        var described = 0
        var gateOpen = true
        var waiting = false
        if (!bulkAllowed() || zoneBlocked()) {
            gateOpen = false
            PcLog.i(TAG, "PcSuspect gate closed: nothing queued or described")
        } else {
            queued = queue(generation)
            waiting = queued > 0 || db.frameStateDao().describeQueued(clock(), 1).isNotEmpty()
            if (waiting) {
                val before = db.frameStateDao().describedCount()
                pipeline.drainCaptionQueue(drainMs, callClass, { bulkAllowed() && !zoneBlocked() && !captureBurst() }, maxCalls)
                described = (db.frameStateDao().describedCount() - before).coerceAtLeast(0)
                // a describe that saved no memory is a failed attempt: cooldown 1, 2, 4, 8 days, then parked (the same rule as the night job)
                db.frameStateDao().noteUncitedDescribed(clock())
            }
            // 4. Remove what the drain just replaced.
            replaced += replaceDescribed(generation)
        }
        val left = dao.markedSuspectsStored(SCAN).size
        val failed = gateOpen && waiting && described == 0
        PcLog.i(TAG, "PcSuspect removed=$removedExpired queued=$queued described=$described replaced=$replaced left=$left")
        return Outcome(removedExpired, queued, replaced, described, left, failed, gateOpen)
    }

    /** Marked suspects with stored photos: each photo that passes the provenance gate and no pause or zone interval is queued for the describe path. Returns how many photos moved. */
    private suspend fun queue(generation: Long): Int {
        var moved = 0
        val seen = HashSet<String>()
        for (memoryId in dao.markedSuspectsStored(SCAN)) {
            if (moved >= QUEUE_MAX) break
            for (frameId in dao.storedPhotosOf(memoryId)) {
                if (moved >= QUEUE_MAX) break
                if (!seen.add(frameId) || !describable(frameId, generation)) continue
                if (db.frameStateDao().queueForDescribe(frameId, clock() + moved) == 1) moved++
            }
        }
        return moved
    }

    /** True when [frameId] may be described now: live by the provenance choke point (not forgotten, not wiped, no zone, readable) and not taken inside a pause or zone interval. Any doubt is no. */
    private suspend fun describable(frameId: String, generation: Long): Boolean {
        if (gate.check(PayloadSources.frames(listOf(frameId)), generation, ProvenanceGate.Zone.ENFORCE, requirePresent = true) != ProvenanceGate.Verdict.Live) return false
        val ts = db.frameDao().byIds(listOf(frameId)).firstOrNull()?.ts ?: return false
        return try { db.pauseDao().countCovering(ts) == 0 } catch (c: CancellationException) { throw c } catch (_: Exception) { false }
    }

    /** Removes every marked suspect whose stored photos ALL have another description with delivery proof. Returns how many were removed. */
    private suspend fun replaceDescribed(generation: Long): Int {
        val ready = ArrayList<String>()
        for (memoryId in dao.markedSuspectsStored(SCAN)) {
            val photos = dao.storedPhotosOf(memoryId)
            if (photos.isNotEmpty() && photos.all { dao.describedWithProof(it, memoryId) > 0 }) ready += memoryId
        }
        return removeAll(ready, generation)
    }

    private suspend fun removeAll(ids: List<String>, generation: Long): Int {
        var n = 0
        for (chunk in ids.chunked(CHUNK)) {
            if (gate.check(PayloadSources.NONE, generation) != ProvenanceGate.Verdict.Live) break // Delete all ran: nothing left to do
            n += remove(chunk)
        }
        return n
    }

    private suspend fun captureBurst(): Boolean = db.frameMetaDao().countSince(clock() - BURST_MS) > 0

    companion object {
        private const val TAG = "SuspectSweep"
        const val DRAIN_MS = 9 * 60_000L
        const val MAX_CALLS = 30
        /** Photos queued per night (about [MAX_CALLS] calls of 3). */
        const val QUEUE_MAX = 90
        private const val SCAN = 1_000
        private const val CHUNK = 100
        private const val BURST_MS = 2 * 60_000L
    }
}
