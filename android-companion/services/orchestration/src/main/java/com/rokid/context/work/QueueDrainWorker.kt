package com.rokid.context.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.rokid.context.core.log.PcLog
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.ModelErrorMapper
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.contract.CriticOutput
import com.rokid.context.ai.contract.ExtractOutput
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.contract.SummaryOutput
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.data.runtime.QueueJobRecord
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.data.runtime.StalePayloadException
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.EmbeddingWorker
import com.rokid.context.orchestration.ExtractFollowUp
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.ExtractSaveResult
import com.rokid.context.orchestration.FrameInputs
import com.rokid.context.orchestration.NoMemoriesException
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.RetryClassifier
import com.rokid.context.orchestration.RetryPolicy
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars
import com.rokid.context.work.budget.BackgroundWork
import com.rokid.context.work.frames.FrameRecovery
import com.rokid.context.work.frames.KeyframeGraph
import com.rokid.context.work.frames.KeyframePipeline
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow

class QueueDrainWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val criticWorker = CriticWorker()

    override suspend fun doWork(): Result {
        // Paused (phone ⏸): nothing goes to the AI provider. Jobs stay queued; resuming drains them (resumePipeline).
        if (!AppConfigStore.isPipelineEnabled(applicationContext)) {
            PcLog.i(TAG, "pipeline paused: queue left for resume")
            return Result.success()
        }
        var processed = 0
        repeat(MAX_JOBS_PER_RUN) {
            // Stamped under the queue lock (review I1, fix round 2 N1): a job claimed before a Delete all writes nothing after it.
            val (job, generation) = QueueRuntimeStore.claim(applicationContext) ?: return@repeat
            processed += 1

            val policyError = policyDeferralReason(job)
            if (policyError != null) {
                QueueRuntimeStore.markPostponed(applicationContext, job.id, policyError, delayMs = POLICY_DEFER_MS)
                PipelineRecords.upsertTrace(applicationContext, job.id, "DEFERRED", policyError)
                return@repeat
            }

            runCatching {
                processJob(job, generation)
            }.onSuccess {
                QueueRuntimeStore.markSucceeded(applicationContext, job.id)
            }.onFailure { error ->
                if (error is StalePayloadException) {
                    // Its sources were forgotten or it is an old text payload: never retried, never sent (R-77).
                    QueueRuntimeStore.markFailed(applicationContext, job.id, STALE_NOTE)
                    PipelineRecords.upsertTrace(applicationContext, job.id, "FAILED", STALE_NOTE)
                    return@onFailure
                }
                val outcome = RetryClassifier.classify(error)
                if (outcome == RetryClassifier.Outcome.CANCELLED) {
                    // WorkManager stopped us: keep the job for the next run, then let cancellation propagate.
                    QueueRuntimeStore.markPostponed(applicationContext, job.id, "cancelled; will retry", delayMs = 0)
                    throw error
                }
                val decision = RetryPolicy.decide(outcome, job.attemptCount)
                // Detail for Diagnostics logs only: text, never the throwable (a ModelCallException's cause may echo a key).
                PcLog.w(TAG, "job ${outcome.name} attempt ${job.attemptCount + 1}/${RetryPolicy.MAX_ATTEMPTS}: ${SafeLog.describe(error)}")
                val shown = if (decision == RetryPolicy.Decision.FAIL && outcome == RetryClassifier.Outcome.RETRY) {
                    UserFacingErrors.AiGaveUp
                } else {
                    ModelErrorMapper.toUserFacing(error)
                }
                if (decision == RetryPolicy.Decision.DEFER) {
                    QueueRuntimeStore.markDeferred(context = applicationContext, id = job.id, error = shown.line, backoffMs = computeBackoffMs(job.attemptCount))
                } else {
                    QueueRuntimeStore.markFailed(applicationContext, job.id, shown.line)
                }
                PipelineRecords.upsertTrace(applicationContext, job.id, if (decision == RetryPolicy.Decision.DEFER) "RETRY" else "FAILED", shown.line)
            }
        }

        if (processed == 0) {
            PcLog.i(TAG, "queue drain idle")
        }
        return Result.success()
    }

    private suspend fun processJob(job: QueueJobRecord, generation: Long) {
        when (job.queue) {
            QueuePriority.FRAME_INGEST -> processFrameIngest(job)
            QueuePriority.SUMMARIZER -> claimAndSummarize(job, generation)
            QueuePriority.PRIORITY_REPROCESS -> processPriorityReprocess(job, generation)
            QueuePriority.EXPLICIT_WRITE -> processExplicitWrite(job, generation)
            QueuePriority.READ -> processReadContext(job, generation)
            QueuePriority.RETRY -> throw IllegalStateException("retry queue item must be re-queued as source queue")
        }
    }

    private suspend fun processFrameIngest(job: QueueJobRecord) {
        val payload = QueuePayloads.parse(job.payload)
        val source = QueuePayloads.string(payload, "source", "scheduled")
        // The same place lookup as FrameIngestWorker (null unless Location is on and granted): a "new place" nudge needs it on the frame.
        val place = com.rokid.context.connectors.sources.SourcesGraph.get(applicationContext).placeLabel(System.currentTimeMillis())
        val imported = RokidFrameInbox.ingestPending(applicationContext, maxItems = 16, placeLabel = place)
        if (imported > 0) KeyframeGraph.get(applicationContext).signal() // the pipeline owns new frames (spec P2.1); this job is the backstop
        val manualLiveRequest = source.equals("manual_live", ignoreCase = true)
        PipelineRecords.upsertTrace(applicationContext, job.id, "FRAME_INGEST_DONE", "source=$source imported=$imported")
        if (imported > 0 || manualLiveRequest) {
            val config = AppConfigStore.getWithoutSecret(applicationContext)
            QueueRuntimeStore.enqueue(
                context = applicationContext,
                queue = QueuePriority.SUMMARIZER,
                payload = QueuePayloads.batchedWrite(
                    source = if (imported > 0) "frame_ingest" else "manual_live_latest",
                    importedFrames = imported,
                    manual = manualLiveRequest,
                ),
                personaSnapshot = config.persona,
                ttlHours = 24,
            )
            PipelineRecords.upsertTrace(
                applicationContext,
                "${job.id}-followup",
                "ENQUEUED",
                if (imported > 0) {
                    "Summarizer queued from imported frame(s)"
                } else {
                    "No new inbox frames. Summarizer queued on latest stored frame window."
                },
            )
        }
    }

    private val ai get() = AiGraph.get(applicationContext)

    /** [callClassOverride]: an Ask-triggered extraction passes USER (P4); null = EXTRACT. */
    private suspend fun claimAndSummarize(job: QueueJobRecord, generation: Long, callClassOverride: com.rokid.context.model.CallClass? = null) {
        val payload = QueuePayloads.parse(job.payload)
        val importedFrames = QueuePayloads.int(payload, "imported_frames", 0)
        val frameIds = QueuePayloads.string(payload, "frame_ids_csv").split(",").map { it.trim() }.filter { it.isNotBlank() }
        val frames = if (frameIds.isNotEmpty()) PipelineRecords.framesByIds(applicationContext, frameIds).takeLast(12) else PipelineRecords.recentFrames(applicationContext, 10)
        if (frames.isEmpty()) throw IllegalStateException("No Rokid frames available for summarization")
        // Final review I1: one VISION_EXTRACT call per photo (up to MAX_VISION_FRAMES readable ones, oldest first, as
        // before), each listing only its own frame. Every fact is then stored on, and fenced by, the frame whose photo
        // the model saw, so Forget moment on that photo reaches it and Forget fact locks that photo.
        val seenAll = mutableListOf<Pair<com.rokid.context.memory.db.FrameEntity, com.rokid.context.model.ModelImageInput>>()
        for (f in frames.sortedBy { it.ts }) {
            if (seenAll.size >= MAX_VISION_FRAMES) break
            FrameInputs.fromFrames(listOf(f), maxImages = 1).singleOrNull()?.let { seenAll += f to it }
        }
        if (seenAll.isEmpty()) {
            PipelineRecords.upsertTrace(applicationContext, job.id, "NO_FACTS", "No readable photo in ${frames.size} frame(s)")
            return
        }
        // The keyframe pipeline owns frames with a frame_state row (spec P2.1): this backstop takes only what it can claim, and
        // leaves frames the pipeline got to recently to it. Legacy frames (no row) go as before; EXTRACTED, NEAR_DUP and live-claimed frames are skipped, manual runs included.
        val manual = QueuePayloads.bool(payload, "manual", default = false)
        val claimId = UUID.randomUUID().toString()
        val (owned, tracked) = claimBackstop(seenAll.map { it.first.id }, claimId, if (manual) 0L else PIPELINE_GRACE_MS)
        val seen = seenAll.filter { it.first.id in owned || it.first.id !in tracked }
        if (seen.isEmpty()) {
            PipelineRecords.upsertTrace(applicationContext, job.id, "NO_FACTS", "Frames belong to the keyframe pipeline")
            return
        }
        val claims = owned.associateWith { claimId }
        try {
            processBatchedSummarization(job, generation, callClassOverride, seen, claims, importedFrames)
        } finally {
            // Whatever ended the run (an exception, a stale save after a photo was forgotten mid-call, Delete all, a text-only no-op):
            // every claim still held goes back to PENDING with no attempt used. release() is claim-guarded, so frames the save
            // completed are untouched and the one stale frame is left alone (review I2).
            withContext(NonCancellable) { owned.forEach { MemoryGraph.get(applicationContext).db.frameStateDao().release(it, claimId, System.currentTimeMillis()) } }
        }
    }

    /** Claims the frames the pipeline has not touched for [PIPELINE_GRACE_MS]; returns (ids this job now holds, ids that have a frame_state row). */
    private suspend fun claimBackstop(ids: List<String>, claimId: String, graceMs: Long): Pair<Set<String>, Set<String>> {
        val dao = MemoryGraph.get(applicationContext).db.frameStateDao()
        val now = System.currentTimeMillis()
        val rows = dao.byIds(ids)
        // Not EXTRACTING (a live claim is the pipeline's), and idle for the grace period; abandoned claims are claim()'s to take.
        // A FAILED frame still inside its own backoff is left alone, except on a manual run (graceMs 0: the user asked now).
        val mine = rows.filter {
            (it.state == "EXTRACTING" || now - it.updatedAt >= graceMs) &&
                (graceMs == 0L || it.state != "FAILED" || now >= it.updatedAt + com.rokid.context.work.frames.KeyframePipeline.failureBackoffMs(it.attempts))
        }.map { it.frameId }
        val got = dao.claim(mine, claimId, now, now - FrameRecovery.CLAIM_TIMEOUT_MS).map { it.frameId }.toSet()
        return got to rows.map { it.frameId }.toSet()
    }

    private suspend fun processBatchedSummarization(
        job: QueueJobRecord, generation: Long, callClassOverride: com.rokid.context.model.CallClass?,
        seen: List<Pair<com.rokid.context.memory.db.FrameEntity, com.rokid.context.model.ModelImageInput>>,
        claims: Map<String, String>, importedFrames: Int,
    ) {
        val frameLines = seen.map { (f, _) -> ExtractFollowUp.frameLine(f) }
        // Still one call per photo here (Final review I1); the batch-then-singles fallback is ExtractSave.extract's.
        var promptVersion = 0
        var provenance = true
        val captions = mutableListOf<com.rokid.context.ai.contract.FrameCaption>()
        // Codex r10: every upload is re-checked live by the router (a photo forgotten meanwhile drops the call unsent: the job is stale, nothing written)
        val liveness = com.rokid.context.work.frames.FrameLiveness.forApp(applicationContext, generation)
        var delivered = true
        val facts = seen.flatMap { (frame, image) ->
            val r = try {
                ExtractSave.extract(ai.runner, listOf(ExtractPhoto(frame.id, "ts=${frame.ts} \u00b7 source=${frame.source} \u00b7 ${frame.payload}", image)),
                    callClassOverride ?: com.rokid.context.model.CallClass.EXTRACT, liveness)
            } catch (f: com.rokid.context.ai.PhotoForgottenException) {
                throw StalePayloadException()
            }
            promptVersion = r.promptVersion
            delivered = delivered && r.output.imagesDelivered
            provenance = provenance && r.output.provenance
            captions += r.output.captions
            r.output.facts.filter { it.text.isNotBlank() }
        }
        val factPrefix = factIdPrefix(job)
        PipelineRecords.dropFactsFrom(applicationContext, factPrefix, keep = facts.size, keepCaptions = captions.size)
        if (facts.isEmpty() && captions.isEmpty()) {
            PipelineRecords.upsertTrace(applicationContext, job.id, "NO_FACTS", "No facts in ${seen.size} photo(s)")
            val db = MemoryGraph.get(applicationContext).db
            claims.forEach { (id, c) -> db.frameStateDao().completeIfClaimed(id, c, "EXTRACTED", System.currentTimeMillis()) } // nothing in them to extract
            return
        }
        // No facts but captions (a photo with nothing durable in it): the captions are saved, with nothing to critique.
        val confidence = ExtractFollowUp.confidence(facts)
        val trusted = ExtractFollowUp.trusted(ai, criticWorker, job.id, facts, frameLines, callClassOverride ?: com.rokid.context.model.CallClass.EXTRACT)
        // Each fact is fenced on the photos it cites only: a photo forgotten during the calls drops just its facts.
        // Memories, frame_source rows (and, for pipeline callers, the claim CAS) are saved by saveExtraction in one transaction.
        val saved = ExtractSave.saveExtraction(
            applicationContext, factPrefix, ExtractOutput(facts, captions = captions, provenance = provenance, imagesDelivered = delivered), seen.map { it.first.id }.toSet(), claims = claims.ifEmpty { null },
            tier = MemoryTier.SHORT, reviewState = if (trusted) ReviewState.OK else ReviewState.NEEDS_REVIEW, generation = generation,
        )
        // no delivery proof: nothing was written; a retryable failure keeps the job (and its photos) queued with backoff
        if (saved == ExtractSaveResult.Undelivered) throw com.rokid.context.ai.ModelCallException("photos not delivered", retryable = true)
        val savedAll = (saved as? ExtractSaveResult.Saved)?.memories.orEmpty()
        when (ExtractFollowUp.afterSave(applicationContext, generation, savedAll, trusted, confidence, job.personaSnapshot)) {
            ExtractFollowUp.Result.SKIPPED, ExtractFollowUp.Result.REVIEW_ONLY -> Unit
            ExtractFollowUp.Result.TRUSTED -> {
                PipelineRecords.upsertTrace(applicationContext, job.id, "TRUSTED", "frames=${seen.size} imported=$importedFrames facts=${facts.size} v$promptVersion")
                PipelineStatsStore.incSummarizerSucceeded(applicationContext)
            }
            ExtractFollowUp.Result.REVIEW_QUEUED -> {
                PipelineRecords.upsertTrace(applicationContext, job.id, "NEEDS_REVIEW", "Reprocess queued: Critic low confidence=${"%.2f".format(Locale.US, confidence)}")
                PipelineStatsStore.incSummarizerFailed(applicationContext)
            }
        }
    }

    /**
     * PRIORITY_REPROCESS (v2, ids only) or a 👎 `critic_review`. Inputs are re-read now: forgotten (missing or
     * INVALIDATED) memories and tombstoned frames are dropped, and `recentFrames` is never used, so text forgotten after
     * the enqueue never reaches a model. A legacy `source_context` payload or one with no surviving source is stale.
     */
    private suspend fun processPriorityReprocess(job: QueueJobRecord, generation: Long) {
        val payload = QueuePayloads.parse(job.payload)
        if (QueuePayloads.string(payload, "kind") == QueuePayloads.KIND_CRITIC_REVIEW) return processCriticReview(job, payload)
        if (payload.containsKey("source_context") || QueuePayloads.int(payload, "v", 0) != 2) throw StalePayloadException()
        val reason = QueuePayloads.string(payload, "reason", "low confidence")
        val sources = PipelineRecords.liveMemories(applicationContext, QueuePayloads.ids(payload, "source_ids_csv"))
        if (sources.isEmpty()) throw StalePayloadException()
        val frames = PipelineRecords.framesByIds(applicationContext, QueuePayloads.ids(payload, "frame_ids_csv"))
        val vars = PromptVars(recentContext = PromptComposer.dataBlock("memories", sources.map { it.text }) +
            "\n" + PromptComposer.dataBlock("critic_notes", listOf(reason)))
        // Codex r10: the photos (and the frame ids in the prompt) are re-checked live by the router inside the call's permit; forgotten meanwhile = stale, unsent.
        val out = try {
            ai.runner.run(RoleCall(PromptKey.SUMMARIZE, vars, images = FrameInputs.fromFrames(frames, maxImages = 3), fromFrames = true, callClass = com.rokid.context.model.CallClass.EXTRACT,
                liveness = com.rokid.context.work.frames.FrameLiveness.forApp(applicationContext, generation))).output as SummaryOutput
        } catch (f: com.rokid.context.ai.PhotoForgottenException) {
            throw StalePayloadException()
        }
        // Final review I1: fenced on every frame whose photo was sent (any one forgotten during the call → nothing written).
        val written = PipelineRecords.appendFact(applicationContext, "rokid_mem_${job.id.takeLast(12)}", null, out.summary, "summary", out.entities,
            out.confidence.coerceAtLeast(0.75), if (out.candidateMemoryType == "medium_term") MemoryTier.MEDIUM else MemoryTier.SHORT, ReviewState.OK,
            generation = generation, derived = true, sourceIds = sources.map { it.id }, fenceFrameIds = frames.map { it.id })
        if (!written) return
        EmbeddingWorker.enqueue(applicationContext)
        PipelineRecords.upsertTrace(applicationContext, job.id, "REPROCESSED", "Re-summarized after critic feedback")
        if (QueuePayloads.bool(payload, "nudge", true)) com.rokid.context.orchestration.nudge.NudgeTrigger.afterSummarizerPass(applicationContext)
    }

    /**
     * 👎 (Phase 7 ruling): CRITIC over the cited memories still awaiting review; pass → OK, fail → they stay in the
     * Review queue. It never writes a summary, and forgotten (INVALIDATED) text is never included.
     */
    private suspend fun processCriticReview(job: QueueJobRecord, payload: kotlinx.serialization.json.JsonObject) {
        val reviews = PipelineRecords.liveMemories(applicationContext, QueuePayloads.ids(payload, "memory_ids_csv"))
            .filter { it.reviewState == ReviewState.NEEDS_REVIEW }
        if (reviews.isEmpty()) throw StalePayloadException()
        val frames = PipelineRecords.framesByIds(applicationContext, reviews.mapNotNull { it.frameId })
        val frameLines = frames.map(ExtractFollowUp::frameLine)
        val vars = PromptVars(recentContext = PromptComposer.dataBlock("facts", reviews.map { "${it.text} (confidence ${"%.2f".format(Locale.US, it.confidence)})" }) +
            "\n" + PromptComposer.dataBlock("source", frameLines))
        val pass = (ai.runner.run(RoleCall(PromptKey.CRITIC, vars, callClass = com.rokid.context.model.CallClass.EXTRACT)).output as CriticOutput).pass
        if (pass) {
            val view = com.rokid.context.memory.MemoryGraph.get(applicationContext).db.memoryViewDao()
            reviews.forEach { view.moveReviewState(it.id, ReviewState.NEEDS_REVIEW.name, ReviewState.OK.name) }
            com.rokid.context.orchestration.UserReviews.clear(applicationContext, reviews.map { it.id })
            EmbeddingWorker.enqueue(applicationContext)
        }
        PipelineRecords.upsertTrace(applicationContext, job.id, if (pass) "REVIEW_PASSED" else "NEEDS_REVIEW", "Critic review of ${reviews.size} memory(ies)")
    }

    private suspend fun processExplicitWrite(job: QueueJobRecord, generation: Long) {
        val payload = QueuePayloads.parse(job.payload)
        val rawInput = QueuePayloads.string(payload, "raw_input")
        require(rawInput.isNotBlank()) { "Empty explicit write input" }
        val extracted = (ai.runner.run(RoleCall(PromptKey.EXPLICIT_REMEMBER, PromptVars(recentContext = PromptComposer.dataBlock("user_said", listOf(rawInput))), callClass = com.rokid.context.model.CallClass.USER)).output as ExtractOutput)
            .facts.filter { it.text.isNotBlank() }
        // Fix I1: the user asked to remember this, so an empty reply keeps their own words as one note (no retry: it can
        // come back empty again).
        val facts = extracted.ifEmpty { listOf(Fact(rawInput, "note", RAW_NOTE_CONFIDENCE, emptyList())) }
        val factPrefix = factIdPrefix(job)
        PipelineRecords.dropFactsFrom(applicationContext, factPrefix, keep = facts.size)
        val written = facts.withIndex().all { (i, f) ->
            PipelineRecords.appendFact(applicationContext, "$factPrefix$i", null, f.text, f.type, f.tags, f.confidence,
                if (f.confidence >= 0.80) MemoryTier.LONG else MemoryTier.MEDIUM, ReviewState.OK, generation = generation)
        }
        if (!written) return
        EmbeddingWorker.enqueue(applicationContext)
        PipelineRecords.upsertTrace(applicationContext, job.id, "WRITE_CONTEXT_OK", "Explicit write saved (${facts.size} fact(s))")
    }

    private suspend fun processReadContext(job: QueueJobRecord, generation: Long) {
        val query = QueuePayloads.string(QueuePayloads.parse(job.payload), "query")
        require(query.isNotBlank()) { "Empty read context query" }
        val answered = try {
            // An ask queued long ago (offline, deferred) is not "just asked": the frames of the last minute are unrelated to it, and
            // extracting them as USER would only run up budget debt. Only a young ask extracts first (review M6).
            val young = System.currentTimeMillis() - job.originalEnqueuedAtEpochMs <= KeyframePipeline.ASK_WINDOW_MS
            AnswerService.forApp(applicationContext, extractFirst = young).answerOrThrow(query, source = "queue", askId = "ask_queue_${job.id}", generation = generation)
        } catch (e: NoMemoriesException) {
            PipelineRecords.upsertTrace(applicationContext, job.id, "CONTEXT_TEMP_UNAVAILABLE", "No trusted memory found; queue status can be checked from Queue card")
            throw e
        }
        PipelineRecords.upsertTrace(applicationContext, job.id, "READ_CONTEXT_OK", "Answered (${answered.askId})")
    }

    /** `rokid_mem_<job>_<i>`: the prefix must stay `rokid_` (legacy purges) and per job (re-runs replace, fix M5). */
    private fun factIdPrefix(job: QueueJobRecord) = "rokid_mem_${job.id.takeLast(12)}_"

    private fun computeBackoffMs(attempt: Int): Long {
        val power = attempt.coerceAtMost(8)
        val base = 30_000.0 * 2.0.pow(power.toDouble())
        return base.toLong().coerceAtMost(30L * 60L * 1000L)
    }

    private fun policyDeferralReason(job: QueueJobRecord): String? {
        val payload = QueuePayloads.parse(job.payload)
        val manual = QueuePayloads.bool(payload, "manual", default = false)

        // FRAME_INGEST makes no AI call (it files the inbox and wakes the pipeline): heat and battery never defer it (review M3).
        if (job.queue == QueuePriority.FRAME_INGEST) return null

        val saver = isBatterySaverEnabled()
        if (saver && (job.queue == QueuePriority.READ || job.queue == QueuePriority.EXPLICIT_WRITE || job.queue == QueuePriority.SUMMARIZER || job.queue == QueuePriority.PRIORITY_REPROCESS)) {
            return "Battery saver is ON. Deferred to TTL queue (24h)."
        }

        // Phone heat (spec P2.5): at MODERATE the caption queue and backfill pause, at SEVERE all background work does.
        if (!manual && job.queue != QueuePriority.READ && job.queue != QueuePriority.EXPLICIT_WRITE &&
            !KeyframeGraph.get(applicationContext).power.mayRunBackground(BackgroundWork.CAPTION_QUEUE)) {
            return "Phone is hot. Deferred until it cools down."
        }

        if (!manual) {
            val battery = batteryPercent()
            val charging = isCharging()
            if (battery in 0..30 && !charging && job.queue != QueuePriority.READ && job.queue != QueuePriority.EXPLICIT_WRITE) {
                return "Battery <=30% and not charging. Deferred to TTL queue (24h)."
            }
        }
        return null
    }

    private fun isBatterySaverEnabled(): Boolean {
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isPowerSaveMode == true
    }

    private fun batteryPercent(): Int {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceAtLeast(0) ?: -1
    }

    private fun isCharging(): Boolean {
        val intent = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        private const val TAG = "QueueDrainWorker"
        private const val MAX_JOBS_PER_RUN = 8
        /** The pipeline gets this long to claim a new frame before the backstop worker may take it. */
        private const val PIPELINE_GRACE_MS = 2 * 60_000L
        private const val POLICY_DEFER_MS = 15 * 60 * 1000L
        private const val RAW_NOTE_CONFIDENCE = 0.5
        /** Photos per batch sent to VISION_EXTRACT, one call each (final review I1; the batch sent 3 before). */
        private const val MAX_VISION_FRAMES = 3
        const val STALE_NOTE = "Skipped: sources changed"
    }
}
