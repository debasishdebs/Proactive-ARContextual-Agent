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
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.domain.summary.SummaryStatus
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.EmbeddingWorker
import com.rokid.context.orchestration.FrameInputs
import com.rokid.context.orchestration.MemoryWipe
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
        if (!AppConfigStore.get(applicationContext).pipelineEnabled) {
            Log.i(TAG, "pipeline paused: queue left for resume")
            return Result.success()
        }
        var processed = 0
        repeat(MAX_JOBS_PER_RUN) {
            // Read before the claim (review I1): a job claimed before a Delete all then writes nothing after it.
            val generation = MemoryWipe.generation.get()
            val job = QueueRuntimeStore.claimNextReady(applicationContext) ?: return@repeat
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
            val config = AppConfigStore.get(applicationContext)
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

    private suspend fun processBatchedSummarization(job: QueueJobRecord, generation: Long) {
        val payload = QueuePayloads.parse(job.payload)
        val importedFrames = QueuePayloads.int(payload, "imported_frames", 0)
        val frameIds = QueuePayloads.string(payload, "frame_ids_csv").split(",").map { it.trim() }.filter { it.isNotBlank() }
        val frames = if (frameIds.isNotEmpty()) PipelineRecords.framesByIds(applicationContext, frameIds).takeLast(12) else PipelineRecords.recentFrames(applicationContext, 10)
        if (frames.isEmpty()) throw IllegalStateException("No Rokid frames available for summarization")
        val frameLines = frames.sortedBy { it.ts }.map { "id=${it.id} · ts=${it.ts} · source=${it.source} · ${it.payload}" }
        val result = ai.runner.run(
            RoleCall(PromptKey.VISION_EXTRACT, PromptVars(recentContext = PromptComposer.dataBlock("frames", frameLines)),
                images = FrameInputs.fromFrames(frames, maxImages = 3), fromFrames = true),
        )
        val facts = (result.output as ExtractOutput).facts.filter { it.text.isNotBlank() }
        val factPrefix = factIdPrefix(job)
        PipelineRecords.dropFactsFrom(applicationContext, factPrefix, keep = facts.size)
        if (facts.isEmpty()) {
            PipelineRecords.upsertTrace(applicationContext, job.id, "NO_FACTS", "No facts in ${frames.size} frame(s)")
            return
        }
        val confidence = facts.map { it.confidence }.average()
        val trusted = criticWorker.validate(job.id, confidence).status == SummaryStatus.TRUSTED || modelCriticPasses(facts, frameLines)
        val frameId = frames.maxBy { it.ts }.id
        val written = facts.withIndex().all { (i, f) ->
            PipelineRecords.appendFact(applicationContext, "$factPrefix$i", frameId, f.text, f.type, f.tags, f.confidence,
                MemoryTier.SHORT, if (trusted) ReviewState.OK else ReviewState.NEEDS_REVIEW, generation = generation)
        }
        if (!written) return // Delete all ran meanwhile: no reprocess job (it would carry the fact text)
        EmbeddingWorker.enqueue(applicationContext)
        if (trusted) {
            PipelineRecords.upsertTrace(applicationContext, job.id, "TRUSTED", "frames=${frames.size} imported=$importedFrames facts=${facts.size} v${result.promptVersion}")
            PipelineStatsStore.incSummarizerSucceeded(applicationContext)
        } else {
            val reason = "Critic low confidence=${"%.2f".format(Locale.US, confidence)}"
            QueueRuntimeStore.enqueue(
                context = applicationContext, queue = QueuePriority.PRIORITY_REPROCESS,
                payload = QueuePayloads.reprocess(reason = reason, sourceContext = (facts.map { it.text } + frameLines).joinToString("\n")),
                personaSnapshot = job.personaSnapshot, ttlHours = 24,
            )
            PipelineRecords.upsertTrace(applicationContext, job.id, "NEEDS_REVIEW", "Reprocess queued: $reason")
            PipelineStatsStore.incSummarizerFailed(applicationContext)
        }
    }

    /** spec §4.2 CRITIC as a second opinion on low-confidence facts. Unbound or failing → keep the local verdict. */
    private suspend fun modelCriticPasses(facts: List<Fact>, frameLines: List<String>): Boolean {
        if (ai.bindings.get(Role.CRITIC) == null) return false
        val vars = PromptVars(recentContext = PromptComposer.dataBlock("facts", facts.map { "${it.text} (confidence ${"%.2f".format(Locale.US, it.confidence)})" }) +
            "\n" + PromptComposer.dataBlock("source", frameLines))
        return try {
            (ai.runner.run(RoleCall(PromptKey.CRITIC, vars)).output as CriticOutput).pass
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "critic unavailable, keeping the local verdict: ${SafeLog.describe(e)}")
            false
        }
    }

    private suspend fun processPriorityReprocess(job: QueueJobRecord, generation: Long) {
        val payload = QueuePayloads.parse(job.payload)
        val sourceContext = QueuePayloads.string(payload, "source_context")
        val reason = QueuePayloads.string(payload, "reason", "low confidence")
        val frames = PipelineRecords.recentFrames(applicationContext, 6)
        val vars = PromptVars(recentContext = PromptComposer.dataBlock("memories", sourceContext.lines().filter { it.isNotBlank() }) +
            "\n" + PromptComposer.dataBlock("critic_notes", listOf(reason)))
        val out = ai.runner.run(RoleCall(PromptKey.SUMMARIZE, vars, images = FrameInputs.fromFrames(frames, maxImages = 3), fromFrames = true)).output as SummaryOutput
        val written = PipelineRecords.appendFact(applicationContext, "rokid_mem_${job.id.takeLast(12)}", null, out.summary, "summary", out.entities,
            out.confidence.coerceAtLeast(0.75), if (out.candidateMemoryType == "medium_term") MemoryTier.MEDIUM else MemoryTier.SHORT, ReviewState.OK,
            generation = generation)
        if (!written) return
        EmbeddingWorker.enqueue(applicationContext)
        PipelineRecords.upsertTrace(applicationContext, job.id, "REPROCESSED", "Re-summarized after critic feedback")
    }

    private suspend fun processExplicitWrite(job: QueueJobRecord, generation: Long) {
        val payload = QueuePayloads.parse(job.payload)
        val rawInput = QueuePayloads.string(payload, "raw_input")
        require(rawInput.isNotBlank()) { "Empty explicit write input" }
        val extracted = (ai.runner.run(RoleCall(PromptKey.EXPLICIT_REMEMBER, PromptVars(recentContext = PromptComposer.dataBlock("user_said", listOf(rawInput))))).output as ExtractOutput)
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
    }
}
