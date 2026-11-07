package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Role
import com.rokid.context.ai.SafeLog
import com.rokid.context.ai.contract.CriticOutput
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.domain.summary.SummaryStatus
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.model.CallClass
import com.rokid.context.model.PhotoLiveness
import com.rokid.context.orchestration.nudge.NudgeTrigger
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptVars
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * What follows an extraction, shared by the legacy drain job and the keyframe pipeline (review I6): the trust verdict (local critic,
 * then the model CRITIC as a second opinion on low confidence) and, after the save, embeddings, the nudge backstop (trusted) or a
 * PRIORITY_REPROCESS job (low confidence). One copy, so the two paths cannot drift apart.
 */
object ExtractFollowUp {
    private const val TAG = "ExtractFollowUp"

    /** What [afterSave] did, for the caller's traces and stats. */
    enum class Result { SKIPPED, TRUSTED, REVIEW_QUEUED, REVIEW_ONLY }

    fun frameLine(f: FrameEntity) = "id=${f.id} \u00b7 ts=${f.ts} \u00b7 source=${f.source} \u00b7 ${f.payload}"

    fun confidence(facts: List<Fact>): Double = if (facts.isEmpty()) 1.0 else facts.map { it.confidence }.average()

    /** True when nothing needs review: no facts, a local TRUSTED verdict, or the model critic passing. [id] only seeds the local critic. */
    suspend fun trusted(
        ai: AiGraph, critic: CriticWorker, id: String, facts: List<Fact>, frameLines: List<String>, frameIds: Set<String>, liveness: PhotoLiveness?,
        callClass: CallClass = CallClass.EXTRACT,
    ): Boolean = locallyTrusted(critic, id, facts) || modelCriticPasses(ai, facts, frameLines, frameIds, liveness, callClass)

    /** No facts, or the local critic's TRUSTED verdict: needs no model call. */
    fun locallyTrusted(critic: CriticWorker, id: String, facts: List<Fact>): Boolean =
        facts.isEmpty() || critic.validate(id, confidence(facts)).status == SummaryStatus.TRUSTED

    /** The CRITIC prompt variables for [facts] against [frameLines] (shared with the canvas runner). */
    fun criticVars(facts: List<Fact>, frameLines: List<String>): PromptVars =
        PromptVars(recentContext = PromptComposer.dataBlock("facts", facts.map { "${it.text} (confidence ${"%.2f".format(Locale.US, it.confidence)})" }) +
            "\n" + PromptComposer.dataBlock("source", frameLines))

    /**
     * spec §4.2 CRITIC as a second opinion on low-confidence facts. Unbound or failing → keep the local verdict. It runs under the
     * CALLER's [callClass]: an ask's extraction (USER) must not queue its critic behind the EXTRACT backlog, where the ask's cap
     * would strand it (final review I2).
     * Codex r11: the facts and [frameLines] are derived from the photos [frameIds]: the call carries them and [liveness], so the router re-checks the
     * photos inside the permit and a photo forgotten meanwhile drops the call unsent (the local verdict stands, nothing is upgraded).
     */
    suspend fun modelCriticPasses(
        ai: AiGraph, facts: List<Fact>, frameLines: List<String>, frameIds: Set<String>, liveness: PhotoLiveness?,
        callClass: CallClass = CallClass.EXTRACT, override: PromptDraft? = null,
    ): Boolean {
        if (ai.bindings.get(Role.CRITIC) == null) return false
        val vars = criticVars(facts, frameLines)
        return try {
            (ai.runner.run(RoleCall(PromptKey.CRITIC, vars, callClass = callClass, override = override, derivedFromFrames = frameIds, liveness = liveness)).output as CriticOutput).pass
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            PcLog.i(TAG, "critic unavailable, keeping the local verdict: ${SafeLog.describe(e)}")
            false
        }
    }

    /**
     * After [saved] (everything the transaction wrote, captions included). Nothing saved, or Delete all ran since [generation]:
     * SKIPPED (no reprocess job, it would carry fact text). Trusted: embeddings and the nudge backstop. Not trusted: embeddings and a
     * reprocess job for the facts that were written (captions alone need no review). [nudge] false (the night describe job's BACKFILL saves):
     * embeddings still run, the nudge backstop does not. [reprocessNudge] false (describe on demand): the reprocess job it queues for low confidence
     * does not wake the nudge engine either (the night BACKFILL path keeps today's behaviour: true).
     */
    suspend fun afterSave(context: Context, generation: Long, saved: List<SavedMemory>, trusted: Boolean, confidence: Double, personaSnapshot: String, nudge: Boolean = true, reprocessNudge: Boolean = true): Result {
        if (saved.isEmpty() || MemoryWipe.generation.get() != generation) return Result.SKIPPED
        EmbeddingWorker.enqueue(context)
        if (trusted) {
            if (nudge) NudgeTrigger.afterSummarizerPass(context) // never after a night / backfill save: its memories are about old photos
            return Result.TRUSTED
        }
        val written = saved.filter { it.index != null }
        if (written.isEmpty()) return Result.REVIEW_ONLY // every fact's photo was forgotten: the captions stay, nothing to review
        val reason = "Critic low confidence=${"%.2f".format(Locale.US, confidence)}"
        QueueRuntimeStore.enqueueIfCurrent(
            context = context, generation = generation, queue = QueuePriority.PRIORITY_REPROCESS,
            payload = QueuePayloads.reprocess(reason = reason, sourceIds = written.map { it.memoryId }, frameIds = written.flatMap { it.frameIds }.distinct(), nudge = reprocessNudge),
            personaSnapshot = personaSnapshot, ttlHours = 24,
        )
        return Result.REVIEW_QUEUED
    }
}
