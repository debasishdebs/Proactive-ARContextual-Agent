package com.rokid.context.work.frames

import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.ExtractOutput
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.ExtractFollowUp
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.ExtractSaveResult
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.SavedMemory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * One VISION_EXTRACT over up to 3 photos, saved and followed up (embeddings, critic). The pipeline's extractor and describe on demand's no-claim
 * path are the same code: only [claimId] (null = no claim CAS) and [idPrefix] (`rokid_kf_<claim>_` or `rokid_desc_<uuid>_`) differ.
 */
internal object KeyframeExtract {
    /**
     * [claimId] non-null: the claim CAS for every sent frame runs inside the save transaction. Null: no claim (a frame in a state nobody owns).
     * [afterSave] runs inside that same transaction with the frames that were sent and the memories written (a throw rolls the save back).
     * [nudge] false: the saved memories never wake the nudge backstop (describe on demand; also every BACKFILL save).
     * [quiet] true (describe on demand): the low-confidence reprocess job it may queue does not wake the nudge engine either.
     * [online] is asked only when the call fails, to tell "offline" from a transient server error.
     */
    suspend fun run(
        app: Context, frameIds: List<String>, claimId: String?, callClass: CallClass, idPrefix: String, online: () -> Boolean,
        nudge: Boolean = callClass != CallClass.BACKFILL,
        quiet: Boolean = false,
        afterSave: (suspend (sent: Set<String>, saved: List<SavedMemory>) -> Unit)? = null,
    ): ExtractOutcome {
        val generation = MemoryWipe.generation.get()
        val loaded = FramePhotos.load(app, frameIds)
        val photos = loaded.photos
        if (photos.isEmpty()) return ExtractOutcome.Failed("unreadable", unreadable = loaded.gone, unseen = loaded.unseen)
        val ai = AiGraph.get(app)
        val batch = try {
            ExtractSave.extract(ai.runner, photos, callClass)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return ExtractOutcome.classify(e, online = online()).copy(unreadable = loaded.gone, unseen = loaded.unseen)
        }
        val facts = batch.output.facts.filter { it.text.isNotBlank() }
        val sent = photos.map { it.frameId }
        // The same trust verdict (local critic, then the model critic) and follow-up as the legacy job (review I6). Final review I2: the
        // paid extraction is SAVED first (NEEDS_REVIEW when the local critic is unsure); the model critic runs after, under the caller's
        // own CallClass, and only upgrades the memories. An ask's cap that lands during the critic therefore cannot lose the extraction.
        val sentFrames = PipelineRecords.framesByIds(app, sent)
        val critic = CriticWorker()
        val localTrusted = ExtractFollowUp.locallyTrusted(critic, claimId ?: idPrefix, facts)
        val saved = ExtractSave.saveExtraction(
            app, idPrefix, ExtractOutput(facts, captions = batch.output.captions, provenance = batch.output.provenance), sent.toSet(),
            claims = claimId?.let { id -> sent.associateWith { id } }, tier = MemoryTier.SHORT, reviewState = if (localTrusted) ReviewState.OK else ReviewState.NEEDS_REVIEW,
            generation = generation, inTransaction = afterSave?.let { hook -> { memories -> hook(sent.toSet(), memories) } },
        )
        return when (saved) {
            is ExtractSaveResult.Saved -> {
                var trusted = localTrusted
                try {
                    if (!localTrusted) {
                        trusted = ExtractFollowUp.modelCriticPasses(ai, facts, sentFrames.map(ExtractFollowUp::frameLine), callClass)
                        if (trusted) {
                            val view = MemoryGraph.get(app).db.memoryViewDao()
                            saved.memories.filter { it.index != null }.forEach { view.moveReviewState(it.memoryId, ReviewState.NEEDS_REVIEW.name, ReviewState.OK.name) }
                        }
                    }
                } finally {
                    // Embeddings, the nudge backstop (trusted) or a PRIORITY_REPROCESS job (low confidence), exactly as the legacy job did;
                    // also when the critic was cancelled (the memories are saved and the claim is done).
                    withContext(NonCancellable) {
                        ExtractFollowUp.afterSave(app, generation, saved.memories, trusted, ExtractFollowUp.confidence(facts), AppConfigStore.getWithoutSecret(app).persona, nudge = nudge, reprocessNudge = !quiet)
                    }
                }
                ExtractOutcome.Saved(saved.memories, loaded.gone, loaded.unseen)
            }
            ExtractSaveResult.Wiped -> ExtractOutcome.Wiped(loaded.gone, loaded.unseen)
            ExtractSaveResult.StaleClaim -> ExtractOutcome.Stale(loaded.gone, loaded.unseen)
        }
    }
}
