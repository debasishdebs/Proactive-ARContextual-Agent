package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.core.log.PcLog
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
import com.rokid.context.model.PhotoLiveness
import com.rokid.context.model.ModelImageInput
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptVars
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One photo of a VISION_EXTRACT call: its [frameId] labels the image in the prompt, [description] is the metadata line. */
data class ExtractPhoto(val frameId: String, val description: String, val image: ModelImageInput)

/** One provider attempt of an extraction: its tokens (null when the provider did not say), model and latency. */
data class CallUsage(val promptTokens: Int?, val completionTokens: Int?, val model: String?, val latencyMs: Long)

/**
 * [attempts]: one entry per provider attempt behind this output, rejected-output retries and single-photo fallback calls included (S32).
 * Empty by default (unknown), so every existing construction is unchanged.
 */
data class ExtractedBatch(val output: ExtractOutput, val promptVersion: Int, val attempts: List<CallUsage> = emptyList())

/** A memory [ExtractSave.saveExtraction] wrote: [index] is the position in the (blank-filtered) facts, or null for a caption. */
data class SavedMemory(val memoryId: String, val frameIds: List<String>, val index: Int?)

sealed interface ExtractSaveResult {
    data class Saved(val memories: List<SavedMemory>) : ExtractSaveResult
    /** A claim no longer held (abandoned and retaken, or the row is gone): the output was dropped, nothing was written. */
    data object StaleClaim : ExtractSaveResult
    /** Delete all ran since the work began: nothing was written. */
    data object Wiped : ExtractSaveResult
    /** Codex r10: the output carries no proof its photos reached the model ([ExtractOutput.imagesDelivered]): nothing was written, the caller retries later. */
    data object Undelivered : ExtractSaveResult
}

/** The VISION_EXTRACT call and save path of the provenance contract (spec P2.1, section C). Used by the drain worker and the keyframe pipeline. */
object ExtractSave {
    private const val TAG = "ExtractSave"

    private class Rollback(val result: ExtractSaveResult) : RuntimeException(null, null, false, false)

    /** The most photos one VISION_EXTRACT call carries (the schema caps captions at 3). */
    const val MAX_BATCH = 3

    /**
     * True when the stored VISION_EXTRACT output [schema] is the provenance one (per-photo `captions` and per-fact
     * `source_frame_ids`). An older or edited prompt that kept the facts-only schema is out of date: it still extracts,
     * single photos only (Diagnostics shows "VISION_EXTRACT prompt is out of date - reset to default").
     */
    fun promptIsCurrent(schema: String?): Boolean {
        val root = schema?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return false
        val props = root["properties"] as? JsonObject ?: return false
        val captions = props["captions"] as? JsonObject ?: return false
        if ((captions["items"] as? JsonObject)?.get("properties") == null) return false
        val factProps = ((props["facts"] as? JsonObject)?.get("items") as? JsonObject)?.get("properties") as? JsonObject ?: return false
        return factProps.containsKey("source_frame_ids")
    }

    /**
     * VISION_EXTRACT over [photos] (labelled `frame_id: <id>`, in order), at most [MAX_BATCH] per call (more are split
     * into batches). A multi-photo batch whose reply is INVALID_OUTPUT (an id not sent, a missing caption) is retried one
     * photo per call; any other failure is thrown as is. When the stored prompt schema is not current
     * ([promptIsCurrent]) every photo is a single call and its facts are attributed to that one photo (captions optional).
     */
    /**
     * [liveness]: the photos' liveness (codex r10; `FrameLiveness`), attached to every call so the router re-checks the photos inside each call's permit.
     * A forgotten photo aborts the whole extraction with `PhotoForgottenException` (no single-photo fallback, no text retry). Null = every call is refused.
     */
    suspend fun extract(
        runner: RoleRunner, photos: List<ExtractPhoto>, callClass: CallClass, liveness: PhotoLiveness?, override: PromptDraft? = null, contextSources: String = "",
    ): ExtractedBatch {
        require(photos.isNotEmpty()) { "no photos" }
        val current = promptIsCurrent(
            if (override != null) (override.outputSchema ?: DefaultSchemas.text(PromptKey.VISION_EXTRACT)) else runner.activeSchema(PromptKey.VISION_EXTRACT),
        )
        val parts = mutableListOf<ExtractedBatch>()
        val attempts = mutableListOf<CallUsage>() // chronological: rejected batch calls, then their single-photo fallbacks (S32)
        for (chunk in if (current) photos.chunked(MAX_BATCH) else photos.map { listOf(it) }) {
            if (chunk.size > 1) {
                try {
                    parts += call(runner, chunk, callClass, liveness, override, contextSources).also { attempts += it.attempts }; continue
                } catch (e: ModelCallException) {
                    if (e.kind != FailureKind.INVALID_OUTPUT) throw e
                    attempts += usageOf(e.replies)
                    PcLog.i(TAG, "batch of ${chunk.size} rejected as INVALID_OUTPUT; retrying one photo per call")
                }
            }
            chunk.forEach { p ->
                val r = call(runner, listOf(p), callClass, liveness, override, contextSources).also { attempts += it.attempts }
                // Legacy reply (no source_frame_ids): the one photo sent is trivially the provenance.
                parts += if (r.output.provenance) r else r.copy(output = r.output.copy(facts = r.output.facts.map {
                    if (it.sourceFrameIds.isEmpty()) it.copy(sourceFrameIds = listOf(p.frameId)) else it
                }))
            }
        }
        return ExtractedBatch(
            ExtractOutput(parts.flatMap { it.output.facts }, captions = parts.flatMap { it.output.captions }, provenance = current, imagesDelivered = parts.all { it.output.imagesDelivered }),
            parts.last().promptVersion,
            attempts.toList(),
        )
    }

    private fun usageOf(replies: List<com.rokid.context.ai.RoutedReply>) = replies.map { CallUsage(it.promptTokens, it.completionTokens, it.model, it.latencyMs) }

    private suspend fun call(runner: RoleRunner, photos: List<ExtractPhoto>, callClass: CallClass, liveness: PhotoLiveness?, override: PromptDraft?, contextSources: String): ExtractedBatch {
        val lines = photos.map { "frame_id: ${it.frameId} - ${it.description}" }
        val r = runner.run(
            RoleCall(PromptKey.VISION_EXTRACT, PromptVars(recentContext = PromptComposer.dataBlock("frames", lines), contextSources = contextSources),
                images = photos.map { it.image.copy(ref = it.frameId) }, inputIds = photos.map { it.frameId }.toSet(), fromFrames = true, callClass = callClass,
                override = override, liveness = liveness),
        )
        return ExtractedBatch((r.output as ExtractOutput).copy(imagesDelivered = r.imagesDelivered), r.promptVersion, usageOf(r.replies))
    }

    /**
     * The atomic save: the claim CAS for every sent frame in [claims] (frameId to claimId), then the facts and captions
     * as memories, then their `frame_source` rows (role FACT), all in ONE transaction. A CAS that updates 0 rows, or a
     * Delete all since [generation], rolls the whole transaction back and the output is dropped. A null [claims] (the
     * legacy queue path, whose frames have no `frame_state` claim) skips the CAS only.
     * Facts get ids `<idPrefix><n>`, captions `<idPrefix>c<n>` (always the LONG tier: a caption is the only text left once its photo is deleted); a fact whose source photo was forgotten meanwhile is
     * skipped (the Forget fence in [PipelineRecords.appendFact], over every photo it cites).
     */
    suspend fun saveExtraction(
        context: Context, idPrefix: String, output: ExtractOutput, sentFrameIds: Set<String>, claims: Map<String, String>?,
        tier: MemoryTier, reviewState: ReviewState, generation: Long = MemoryWipe.generation.get(), nowMs: Long = System.currentTimeMillis(),
        /** Runs inside the save transaction after the memories and their provenance are written (describe-on-demand moves the photo's state here, S4); a throw rolls everything back. */
        inTransaction: (suspend (saved: List<SavedMemory>) -> Unit)? = null,
    ): ExtractSaveResult {
        val db = com.rokid.context.memory.MemoryGraph.get(context).db
        // Codex r10: a description (a caption, or anything saved trusted) is never persisted unless the router proved the photos reached the model on the
        // call that produced it. The one proof-less save is a canvas run's facts (no captions), saved NEEDS_REVIEW at the user's request (CanvasRunSave).
        if (!output.imagesDelivered && (output.captions.isNotEmpty() || reviewState != ReviewState.NEEDS_REVIEW)) {
            PcLog.w(TAG, "extraction refused: no image delivery proof")
            return ExtractSaveResult.Undelivered
        }
        val facts = output.facts.filter { it.text.isNotBlank() }
        return try {
            db.withTransaction {
                if (MemoryWipe.generation.get() != generation) throw Rollback(ExtractSaveResult.Wiped)
                // The CAS comes first: a stale attempt must not write a single row.
                claims?.forEach { (frameId, claimId) ->
                    if (db.frameStateDao().completeIfClaimed(frameId, claimId, FrameState.EXTRACTED.name, nowMs) == 0) {
                        PcLog.i(TAG, "extraction dropped: claim no longer holds")
                        throw Rollback(ExtractSaveResult.StaleClaim)
                    }
                }
                val saved = mutableListOf<SavedMemory>()
                facts.forEachIndexed { i, f ->
                    val ids = f.sourceFrameIds.filter { it in sentFrameIds }.distinct().ifEmpty { sentFrameIds.take(1) }
                    val id = "$idPrefix$i"
                    if (PipelineRecords.appendFact(context, id, ids.firstOrNull(), f.text, f.type, f.tags, f.confidence, tier, reviewState,
                            generation = generation, fenceFrameIds = ids, createdAt = nowMs)) {
                        saved += SavedMemory(id, ids, i)
                    }
                }
                output.captions.filter { it.caption.isNotBlank() && it.frameId in sentFrameIds }.distinctBy(FrameCaption::frameId).forEachIndexed { i, c ->
                    val id = "${idPrefix}c$i"
                    if (PipelineRecords.appendFact(context, id, c.frameId, c.caption, "caption", listOf("caption"), 1.0, MemoryTier.LONG, ReviewState.OK,
                            generation = generation, fenceFrameIds = listOf(c.frameId), createdAt = nowMs)) {
                        saved += SavedMemory(id, listOf(c.frameId), null)
                    }
                }
                // A re-run replaces the memory by id, so its earlier provenance goes first.
                saved.forEach { db.frameSourceDao().deleteForMemory(it.memoryId) }
                db.frameSourceDao().insertAll(saved.flatMap { m -> m.frameIds.map { FrameSourceEntity(m.memoryId, it, null, "FACT") } })
                inTransaction?.invoke(saved)
                ExtractSaveResult.Saved(saved)
            }
        } catch (r: Rollback) {
            r.result
        }
    }
}
