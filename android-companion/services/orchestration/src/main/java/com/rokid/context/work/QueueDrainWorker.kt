package com.rokid.context.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.ModelErrorMapper
import com.rokid.context.ai.Role
import com.rokid.context.ai.SafeLog
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
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.RetryClassifier
import com.rokid.context.orchestration.RetryPolicy
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptHash
import com.rokid.context.prompt.PromptTemplateId
import com.rokid.context.prompt.PromptTemplateRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import kotlin.math.pow

class QueueDrainWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val criticWorker = CriticWorker()

    override suspend fun doWork(): Result {
        var processed = 0
        repeat(MAX_JOBS_PER_RUN) {
            val job = QueueRuntimeStore.claimNextReady(applicationContext) ?: return@repeat
            processed += 1

            val policyError = policyDeferralReason(job)
            if (policyError != null) {
                QueueRuntimeStore.markDeferred(
                    context = applicationContext,
                    id = job.id,
                    error = policyError,
                    backoffMs = computeBackoffMs(job.attemptCount),
                )
                PipelineRecords.upsertTrace(applicationContext, job.id, "DEFERRED", policyError)
                return@repeat
            }

            runCatching {
                processJob(job)
            }.onSuccess {
                QueueRuntimeStore.markSucceeded(applicationContext, job.id)
            }.onFailure { error ->
                val outcome = RetryClassifier.classify(error)
                if (outcome == RetryClassifier.Outcome.CANCELLED) {
                    // WorkManager stopped us: keep the job for the next run, then let cancellation propagate.
                    QueueRuntimeStore.markDeferred(applicationContext, job.id, "cancelled; will retry", backoffMs = 0)
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

    private suspend fun processJob(job: QueueJobRecord) {
        when (job.queue) {
            QueuePriority.FRAME_INGEST -> processFrameIngest(job)
            QueuePriority.SUMMARIZER -> processBatchedSummarization(job)
            QueuePriority.PRIORITY_REPROCESS -> processPriorityReprocess(job)
            QueuePriority.EXPLICIT_WRITE -> processExplicitWrite(job)
            QueuePriority.READ -> processReadContext(job)
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

    private suspend fun processBatchedSummarization(job: QueueJobRecord) {
        val payload = QueuePayloads.parse(job.payload)
        val importedFrames = QueuePayloads.int(payload, "imported_frames", 0)
        val frameIdsCsv = QueuePayloads.string(payload, "frame_ids_csv")
        val frameIds = frameIdsCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val frames = if (frameIds.isNotEmpty()) {
            PipelineRecords.framesByIds(applicationContext, frameIds).takeLast(12)
        } else {
            PipelineRecords.recentFrames(applicationContext, 10)
        }
        if (frames.isEmpty()) {
            throw IllegalStateException("No Rokid frames available for summarization")
        }

        val frameWindow = frames.joinToString(separator = "\n") {
            "id=${it.id}, ts=${it.ts}, source=${it.source}, payload=${it.payload}"
        }

        val prompt = PromptComposer(PromptTemplateRepository(applicationContext)).compose(
            templateId = PromptTemplateId.WRITE_CONTEXT_BATCHED,
            variables = mapOf(
                "persona" to job.personaSnapshot,
                "input_language" to "auto",
                "frame_window" to frameWindow,
                "attached_images" to buildAttachedImagesLabel(frames),
            ),
        )
        Log.i(TAG, "batched write promptHash=${PromptHash.sha256(prompt)}")

        val modelOutput = generateBatchedSummary(prompt, frames)
        val confidence = extractConfidence(modelOutput)
        val critic = criticWorker.validate(job.id, confidence)

        if (critic.status == SummaryStatus.TRUSTED) {
            PipelineRecords.appendMemory(
                applicationContext,
                id = "rokid_mem_${job.id.takeLast(12)}",
                type = "short_term",
                content = modelOutput.take(260),
                confidence = confidence,
                createdAt = System.currentTimeMillis(),
            )
            PipelineRecords.upsertTrace(applicationContext, job.id, "TRUSTED", "frames=${frames.size} imported=$importedFrames")
            PipelineStatsStore.incSummarizerSucceeded(applicationContext)
        } else {
            val reason = "Critic low confidence=${"%.2f".format(Locale.US, confidence)}"
            QueueRuntimeStore.enqueue(
                context = applicationContext,
                queue = QueuePriority.PRIORITY_REPROCESS,
                payload = QueuePayloads.reprocess(reason = reason, sourceContext = frameWindow),
                personaSnapshot = job.personaSnapshot,
                ttlHours = 24,
            )
            PipelineRecords.appendMemory(
                applicationContext,
                id = "rokid_review_${job.id.takeLast(12)}",
                type = "review",
                content = "$reason | ${modelOutput.take(200)}",
                confidence = confidence,
                createdAt = System.currentTimeMillis(),
            )
            PipelineRecords.upsertTrace(applicationContext, job.id, "NEEDS_REVIEW", "Reprocess queued: $reason")
            PipelineStatsStore.incSummarizerFailed(applicationContext)
        }
    }

    private suspend fun processPriorityReprocess(job: QueueJobRecord) {
        val payload = QueuePayloads.parse(job.payload)
        val sourceContext = QueuePayloads.string(payload, "source_context")
        val reason = QueuePayloads.string(payload, "reason", "low confidence")
        val frames = PipelineRecords.recentFrames(applicationContext, 6)

        val prompt = PromptComposer(PromptTemplateRepository(applicationContext)).compose(
            templateId = PromptTemplateId.RE_SUMMARIZE,
            variables = mapOf(
                "prior_summary" to "See source context",
                "critic_reasoning" to reason,
                "source_context" to sourceContext,
                "attached_images" to buildAttachedImagesLabel(frames),
            ),
        )
        Log.i(TAG, "re-summarize promptHash=${PromptHash.sha256(prompt)}")

        val output = generateBatchedSummary(prompt, frames)
        val confidence = extractConfidence(output).coerceAtLeast(0.75)

        PipelineRecords.appendMemory(
            applicationContext,
            id = "rokid_mem_${job.id.takeLast(12)}",
            type = "short_term",
            content = output.take(260),
            confidence = confidence,
            createdAt = System.currentTimeMillis(),
        )
        PipelineRecords.upsertTrace(applicationContext, job.id, "REPROCESSED", "Re-summarized after critic feedback")
    }

    private suspend fun processExplicitWrite(job: QueueJobRecord) {
        val payload = QueuePayloads.parse(job.payload)
        val rawInput = QueuePayloads.string(payload, "raw_input")
        val inputLanguage = QueuePayloads.string(payload, "input_language", "auto")
        require(rawInput.isNotBlank()) { "Empty explicit write input" }

        val prompt = PromptComposer(PromptTemplateRepository(applicationContext)).compose(
            templateId = PromptTemplateId.WRITE_CONTEXT_EXPLICIT,
            variables = mapOf(
                "persona" to job.personaSnapshot,
                "input_language" to inputLanguage,
                "timestamp_iso" to java.time.Instant.now().toString(),
                "raw_input" to rawInput,
            ),
        )
        Log.i(TAG, "explicit write promptHash=${PromptHash.sha256(prompt)}")

        val output = generateWithFallback(Role.EXPLICIT_REMEMBER, prompt)
        val confidence = extractConfidence(output)

        PipelineRecords.appendMemory(
            applicationContext,
            id = "rokid_mem_${job.id.takeLast(12)}",
            type = if (confidence >= 0.80) "long_term" else "short_term",
            content = output.take(260),
            confidence = confidence,
            createdAt = System.currentTimeMillis(),
        )
        PipelineRecords.upsertTrace(applicationContext, job.id, "WRITE_CONTEXT_OK", "Explicit write saved")
    }

    private suspend fun processReadContext(job: QueueJobRecord) {
        val payload = QueuePayloads.parse(job.payload)
        val query = QueuePayloads.string(payload, "query")
        val inputLanguage = QueuePayloads.string(payload, "input_language", "auto")
        require(query.isNotBlank()) { "Empty read context query" }

        val trusted = PipelineRecords.recentMemories(applicationContext, 60)
            .filter { !it.type.contains("review", ignoreCase = true) }

        if (trusted.isEmpty()) {
            PipelineRecords.upsertTrace(applicationContext, job.id, "CONTEXT_TEMP_UNAVAILABLE", "No trusted memory found; queue status can be checked from Queue card")
            throw IllegalStateException("Context temporarily unavailable")
        }

        val retrieved = trusted.take(10).joinToString(separator = "\n") {
            "${it.type} | conf=${"%.2f".format(Locale.US, it.confidence)} | ${it.text}"
        }

        val prompt = PromptComposer(PromptTemplateRepository(applicationContext)).compose(
            templateId = PromptTemplateId.READ_CONTEXT_ANSWER,
            variables = mapOf(
                "persona" to job.personaSnapshot,
                "input_language" to inputLanguage,
                "query" to query,
                "retrieved_context" to retrieved,
            ),
        )
        Log.i(TAG, "read context promptHash=${PromptHash.sha256(prompt)}")

        val answer = generateWithFallback(Role.ANSWER, prompt)
        PipelineRecords.upsertTrace(applicationContext, job.id, "READ_CONTEXT_OK", answer.take(180))
    }

    private suspend fun generateWithFallback(role: Role, prompt: String): String =
        AiGraph.get(applicationContext).router.generate(role, prompt)

    private suspend fun generateBatchedSummary(prompt: String, frames: List<FrameEntity>): String =
        AiGraph.get(applicationContext).router.generateFromFrames(prompt, images = buildVisionInputs(frames, maxImages = 3))

    private fun buildAttachedImagesLabel(frames: List<FrameEntity>): String {
        val withPreview = frames.count { it.path.isNotBlank() }
        return "$withPreview candidate image(s) in current frame window"
    }

    private fun buildVisionInputs(
        frames: List<FrameEntity>,
        maxImages: Int,
    ): List<ModelImageInput> {
        if (maxImages <= 0) return emptyList()
        return frames.asSequence()
            .mapNotNull { frame ->
                val path = frame.path
                if (path.isBlank()) return@mapNotNull null
                val file = File(path)
                if (!file.exists() || !file.canRead() || file.length() <= 0L) return@mapNotNull null
                val raw = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
                val mime = when (file.extension.lowercase(Locale.US)) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> "image/jpeg"
                }
                ModelImageInput(
                    mimeType = mime,
                    base64Data = Base64.encodeToString(raw, Base64.NO_WRAP),
                    detail = "low",
                )
            }
            .take(maxImages)
            .toList()
    }

    private fun extractConfidence(output: String): Double {
        val regex = Regex("\\\"confidence\\\"\\s*:\\s*([0-9]*\\.?[0-9]+)")
        val fromJson = regex.find(output)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return (fromJson ?: 0.76).coerceIn(0.0, 1.0)
    }

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
    }
}
