package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.contract.ExtractOutput
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameState
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelImageInput
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars

/** One photo of a VISION_EXTRACT call: its [frameId] labels the image in the prompt, [description] is the metadata line. */
data class ExtractPhoto(val frameId: String, val description: String, val image: ModelImageInput)

data class ExtractedBatch(val output: ExtractOutput, val promptVersion: Int)

/** A memory [ExtractSave.saveExtraction] wrote: [index] is the position in the (blank-filtered) facts, or null for a caption. */
data class SavedMemory(val memoryId: String, val frameIds: List<String>, val index: Int?)

sealed interface ExtractSaveResult {
    data class Saved(val memories: List<SavedMemory>) : ExtractSaveResult
    /** A claim no longer held (abandoned and retaken, or the row is gone): the output was dropped, nothing was written. */
    data object StaleClaim : ExtractSaveResult
    /** Delete all ran since the work began: nothing was written. */
    data object Wiped : ExtractSaveResult
}

/** The VISION_EXTRACT call and save path of the provenance contract (spec P2.1, section C). Used by the drain worker and the keyframe pipeline. */
object ExtractSave {
    private const val TAG = "ExtractSave"

    private class Rollback(val result: ExtractSaveResult) : RuntimeException(null, null, false, false)

    /**
     * One VISION_EXTRACT call over [photos] (labelled `frame_id: <id>`, in order). More than one photo whose reply is
     * INVALID_OUTPUT (an id not sent, a missing caption) is retried one photo per call; any other failure is thrown as is.
     * A single-photo call uses the same schema.
     */
    suspend fun extract(runner: RoleRunner, photos: List<ExtractPhoto>, callClass: CallClass): ExtractedBatch {
        require(photos.isNotEmpty()) { "no photos" }
        if (photos.size > 1) {
            try {
                return call(runner, photos, callClass)
            } catch (e: ModelCallException) {
                if (e.kind != FailureKind.INVALID_OUTPUT) throw e
                Log.i(TAG, "batch of ${photos.size} rejected as INVALID_OUTPUT; retrying one photo per call")
            }
        }
        val parts = photos.map { call(runner, listOf(it), callClass) }
        return ExtractedBatch(
            ExtractOutput(parts.flatMap { it.output.facts }, captions = parts.flatMap { it.output.captions }, provenance = true),
            parts.last().promptVersion,
        )
    }

    private suspend fun call(runner: RoleRunner, photos: List<ExtractPhoto>, callClass: CallClass): ExtractedBatch {
        val lines = photos.map { "frame_id: ${it.frameId} - ${it.description}" }
        val r = runner.run(
            RoleCall(PromptKey.VISION_EXTRACT, PromptVars(recentContext = PromptComposer.dataBlock("frames", lines)),
                images = photos.map { it.image }, inputIds = photos.map { it.frameId }.toSet(), fromFrames = true, callClass = callClass),
        )
        return ExtractedBatch(r.output as ExtractOutput, r.promptVersion)
    }

    /**
     * The atomic save: the claim CAS for every sent frame in [claims] (frameId to claimId), then the facts and captions
     * as memories, then their `frame_source` rows (role FACT), all in ONE transaction. A CAS that updates 0 rows, or a
     * Delete all since [generation], rolls the whole transaction back and the output is dropped. A null [claims] (the
     * legacy queue path, whose frames have no `frame_state` claim) skips the CAS only.
     * Facts get ids `<idPrefix><n>`, captions `<idPrefix>c<n>`; a fact whose source photo was forgotten meanwhile is
     * skipped (the Forget fence in [PipelineRecords.appendFact], over every photo it cites).
     */
    suspend fun saveExtraction(
        context: Context, idPrefix: String, output: ExtractOutput, sentFrameIds: Set<String>, claims: Map<String, String>?,
        tier: MemoryTier, reviewState: ReviewState, generation: Long = MemoryWipe.generation.get(), nowMs: Long = System.currentTimeMillis(),
    ): ExtractSaveResult {
        val db = com.rokid.context.memory.MemoryGraph.get(context).db
        val facts = output.facts.filter { it.text.isNotBlank() }
        return try {
            db.withTransaction {
                if (MemoryWipe.generation.get() != generation) throw Rollback(ExtractSaveResult.Wiped)
                // The CAS comes first: a stale attempt must not write a single row.
                claims?.forEach { (frameId, claimId) ->
                    if (db.frameStateDao().completeIfClaimed(frameId, claimId, FrameState.EXTRACTED.name, nowMs) == 0) {
                        Log.i(TAG, "extraction dropped: claim on $frameId no longer holds")
                        throw Rollback(ExtractSaveResult.StaleClaim)
                    }
                }
                val saved = mutableListOf<SavedMemory>()
                facts.forEachIndexed { i, f ->
                    val ids = f.sourceFrameIds.filter { it in sentFrameIds }.distinct().ifEmpty { sentFrameIds.take(1) }
                    val id = "$idPrefix$i"
                    if (PipelineRecords.appendFact(context, id, ids.firstOrNull(), f.text, f.type, f.tags, f.confidence, tier, reviewState,
                            generation = generation, fenceFrameIds = ids)) {
                        saved += SavedMemory(id, ids, i)
                    }
                }
                output.captions.filter { it.caption.isNotBlank() && it.frameId in sentFrameIds }.distinctBy(FrameCaption::frameId).forEachIndexed { i, c ->
                    val id = "${idPrefix}c$i"
                    if (PipelineRecords.appendFact(context, id, c.frameId, c.caption, "caption", listOf("caption"), 1.0, tier, ReviewState.OK,
                            generation = generation, fenceFrameIds = listOf(c.frameId))) {
                        saved += SavedMemory(id, listOf(c.frameId), null)
                    }
                }
                // A re-run replaces the memory by id, so its earlier provenance goes first.
                saved.forEach { db.frameSourceDao().deleteForMemory(it.memoryId) }
                db.frameSourceDao().insertAll(saved.flatMap { m -> m.frameIds.map { FrameSourceEntity(m.memoryId, it, null, "FACT") } })
                ExtractSaveResult.Saved(saved)
            }
        } catch (r: Rollback) {
            r.result
        }
    }
}
