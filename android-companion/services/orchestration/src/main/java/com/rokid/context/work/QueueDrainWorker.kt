package com.rokid.context.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.ModelErrorMapper
import com.rokid.context.ai.Role
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
import com.rokid.context.domain.summary.SummaryStatus
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.EmbeddingWorker
import com.rokid.context.orchestration.FrameInputs
import com.rokid.context.orchestration.NoMemoriesException
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.RetryClassifier
import com.rokid.context.orchestration.RetryPolicy
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars
import java.util.Locale
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
            Log.i(TAG, "pipeline paused: queue left for resume")
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
                Log.w(TAG, "job ${job.id} ${outcome.name} attempt ${job.attemptCount + 1}/${RetryPolicy.MAX_ATTEMPTS}: ${SafeLog.describe(error)}")
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
            Log.i(TAG, "queue drain idle")
        }
        return Result.success()
    }

    private suspend fun processJob(job: QueueJobRecord, generation: Long) {
        when (job.queue) {
            QueuePriority.FRAME_INGEST -> processFrameIngest(job)
            QueuePriority.SUMMARIZER -> processBatchedSummarization(job, generation)
            QueuePriority.PRIORITY_REPROCESS -> processPriorityReprocess(job, generation)
            QueuePriority.EXPLICIT_WRITE -> processExplicitWrite(job, generation)
            QueuePriority.READ -> processReadContext(job, generation)
            QueuePriority.RETRY -> throw IllegalStateException("retry queue item must be re-queued as source queue")
        }
    }

    private suspend fun processFrameIngest(job: QueueJobRecord) {
        val payload = QueuePayloads.parse(job.payload)
        val source = QueuePayloads.string(payload, "source", "scheduled")
        val imported = RokidFrameInbox.ingestPending(applicationContext, maxItems = 16)
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
    private suspend fun processBatchedSummarization(job: QueueJobRecord, generation: Long, callClassOverride: com.rokid.context.model.CallClass? = null) {
        val payload = QueuePayloads.parse(job.payload)
        val importedFrames = QueuePayloads.int(payload, "imported_frames", 0)
        val frameIds = QueuePayloads.string(payload, "frame_ids_csv").split(",").map { it.trim() }.filter { it.isNotBlank() }
        val frames = if (frameIds.isNotEmpty()) PipelineRecords.framesByIds(applicationContext, frameIds).takeLast(12) else PipelineRecords.recentFrames(applicationContext, 10)
        if (frames.isEmpty()) throw IllegalStateException("No Rokid frames available for summarization")
        // Final review I1: one VISION_EXTRACT call per photo (up to MAX_VISION_FRAMES readable ones, oldest first, as
        // before), each listing only its own frame. Every fact is then stored on, and fenced by, the frame whose photo
        // the model saw, so Forget moment on that photo reaches it and Forget fact locks that photo.
        val seen = mutableListOf<Pair<com.rokid.context.memory.db.FrameEntity, com.rokid.context.model.ModelImageInput>>()
        for (f in frames.sortedBy { it.ts }) {
            if (seen.size >= MAX_VISION_FRAMES) break
            FrameInputs.fromFrames(listOf(f), maxImages = 1).singleOrNull()?.let { seen += f to it }
        }
        if (seen.isEmpty()) {
            PipelineRecords.upsertTrace(applicationContext, job.id, "NO_FACTS", "No readable photo in ${frames.size} frame(s)")
            return
        }
        val frameLines = seen.map { (f, _) -> frameLine(f) }
        var promptVersion = 0
        val facts = seen.flatMap { (frame, image) ->
            val r = ai.runner.run(
                RoleCall(PromptKey.VISION_EXTRACT, PromptVars(recentContext = PromptComposer.dataBlock("frames", listOf(frameLine(frame)))),
                    images = listOf(image), fromFrames = true, callClass = callClassOverride ?: com.rokid.context.model.CallClass.EXTRACT),
            )
            promptVersion = r.promptVersion
            (r.output as ExtractOutput).facts.filter { it.text.isNotBlank() }.map { frame.id to it }
        }
        val factPrefix = factIdPrefix(job)
        PipelineRecords.dropFactsFrom(applicationContext, factPrefix, keep = facts.size)
        if (facts.isEmpty()) {
            PipelineRecords.upsertTrace(applicationContext, job.id, "NO_FACTS", "No facts in ${seen.size} photo(s)")
            return
        }
        val confidence = facts.map { it.second.confidence }.average()
        val trusted = criticWorker.validate(job.id, confidence).status == SummaryStatus.TRUSTED || modelCriticPasses(facts.map { it.second }, frameLines)
        // Each fact is fenced on its own frame only: a photo forgotten during the calls drops just its facts.
        val written = facts.withIndex().filter { (i, pf) ->
            val (frameId, f) = pf
            PipelineRecords.appendFact(applicationContext, "$factPrefix$i", frameId, f.text, f.type, f.tags, f.confidence,
                MemoryTier.SHORT, if (trusted) ReviewState.OK else ReviewState.NEEDS_REVIEW, generation = generation)
        }
        // Delete all ran meanwhile (or every photo was forgotten): no reprocess job (it would carry the fact text).
        if (written.isEmpty() || com.rokid.context.orchestration.MemoryWipe.generation.get() != generation) return
        EmbeddingWorker.enqueue(applicationContext)
        if (trusted) {
            PipelineRecords.upsertTrace(applicationContext, job.id, "TRUSTED", "frames=${seen.size} imported=$importedFrames facts=${facts.size} v$promptVersion")
            com.rokid.context.orchestration.nudge.NudgeTrigger.afterSummarizerPass(applicationContext)
            PipelineStatsStore.incSummarizerSucceeded(applicationContext)
        } else {
            val reason = "Critic low confidence=${"%.2f".format(Locale.US, confidence)}"
            QueueRuntimeStore.enqueueIfCurrent(
                context = applicationContext, generation = generation, queue = QueuePriority.PRIORITY_REPROCESS,
                payload = QueuePayloads.reprocess(reason = reason, sourceIds = written.map { "$factPrefix${it.index}" }, frameIds = written.map { it.value.first }.distinct()),
                personaSnapshot = job.personaSnapshot, ttlHours = 24,
            )
            PipelineRecords.upsertTrace(applicationContext, job.id, "NEEDS_REVIEW", "Reprocess queued: $reason")
            PipelineStatsStore.incSummarizerFailed(applicationContext)
        }
    }

    private fun frameLine(f: com.rokid.context.memory.db.FrameEntity) = "id=${f.id} · ts=${f.ts} · source=${f.source} · ${f.payload}"

    /** spec §4.2 CRITIC as a second opinion on low-confidence facts. Unbound or failing → keep the local verdict. */
    private suspend fun modelCriticPasses(facts: List<Fact>, frameLines: List<String>): Boolean {
        if (ai.bindings.get(Role.CRITIC) == null) return false
        val vars = PromptVars(recentContext = PromptComposer.dataBlock("facts", facts.map { "${it.text} (confidence ${"%.2f".format(Locale.US, it.confidence)})" }) +
            "\n" + PromptComposer.dataBlock("source", frameLines))
        return try {
            (ai.runner.run(RoleCall(PromptKey.CRITIC, vars, callClass = com.rokid.context.model.CallClass.EXTRACT)).output as CriticOutput).pass
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "critic unavailable, keeping the local verdict: ${SafeLog.describe(e)}")
            false
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
        val out = ai.runner.run(RoleCall(PromptKey.SUMMARIZE, vars, images = FrameInputs.fromFrames(frames, maxImages = 3), fromFrames = true, callClass = com.rokid.context.model.CallClass.EXTRACT)).output as SummaryOutput
        // Final review I1: fenced on every frame whose photo was sent (any one forgotten during the call → nothing written).
        val written = PipelineRecords.appendFact(applicationContext, "rokid_mem_${job.id.takeLast(12)}", null, out.summary, "summary", out.entities,
            out.confidence.coerceAtLeast(0.75), if (out.candidateMemoryType == "medium_term") MemoryTier.MEDIUM else MemoryTier.SHORT, ReviewState.OK,
            generation = generation, derived = true, sourceIds = sources.map { it.id }, fenceFrameIds = frames.map { it.id })
        if (!written) return
        EmbeddingWorker.enqueue(applicationContext)
        PipelineRecords.upsertTrace(applicationContext, job.id, "REPROCESSED", "Re-summarized after critic feedback")
        com.rokid.context.orchestration.nudge.NudgeTrigger.afterSummarizerPass(applicationContext)
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
        val frameLines = frames.map(::frameLine)
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
            AnswerService.forApp(applicationContext).answerOrThrow(query, source = "queue", askId = "ask_queue_${job.id}", generation = generation)
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

        val saver = isBatterySaverEnabled()
        if (saver && (job.queue == QueuePriority.READ || job.queue == QueuePriority.EXPLICIT_WRITE || job.queue == QueuePriority.SUMMARIZER || job.queue == QueuePriority.PRIORITY_REPROCESS)) {
            return "Battery saver is ON. Deferred to TTL queue (24h)."
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
        private const val POLICY_DEFER_MS = 15 * 60 * 1000L
        private const val RAW_NOTE_CONFIDENCE = 0.5
        /** Photos per batch sent to VISION_EXTRACT, one call each (final review I1; the batch sent 3 before). */
        private const val MAX_VISION_FRAMES = 3
        const val STALE_NOTE = "Skipped: sources changed"
    }
}
