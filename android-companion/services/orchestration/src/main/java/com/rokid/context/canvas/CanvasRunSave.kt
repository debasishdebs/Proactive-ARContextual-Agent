package com.rokid.context.canvas

import android.content.Context
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.SummaryNode
import com.rokid.context.ai.contract.ExtractOutput
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.CanvasSaveMarker
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.ExtractFollowUp
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.ExtractSaveResult
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.SavedMemory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Playground vs Run-and-Save (v0.9.6, user ruling 2026-10-25). A canvas run never writes a memory: `memory.write` is a preview ([MemoryWritePreviewExecutor]). THIS is the one place a
 * canvas run's memories are saved, on the user's explicit request, through the REAL write path of the live extraction ([ExtractSave.saveExtraction]: the same fences, `frame_source`
 * provenance and embeddings), never through the preview.
 *
 * What is saved: what the pipeline's `memory.write` node RECEIVED for each photo (rebuilt from the stored run rows with the runner's own edge rules), as the preview shows it:
 * facts (SHORT tier; review state OK only when the pipeline's check trusted them, otherwise NEEDS_REVIEW, like the live path) and captions (LONG, OK). Valid memories only: a fact or
 * caption whose source photo is no longer live (forgotten, tombstoned, expired) is left out; a `summary` fact is saved as a derived memory with its same-photo memories as sources.
 * Provenance: ids are `rokid_cv_<runId>_<n>` (facts) and `..._c<n>` (captions) and every fact carries the tag `canvas`, so the canvas run is readable from the id.
 *
 * Idempotent: the `canvas_save:<runId>` marker is written in the SAME transaction as the memories, so a retry or a double click saves nothing twice (even after the user forgot one of the
 * saved memories). A save is a write under the [DataOpGate]: a running export or delete is waited for (bounded), then the typed [Result.Busy] says which and when to retry.
 */
class CanvasRunSave(
    private val app: Context,
    private val db: ContextDatabase,
    private val gate: () -> DataOpGate = { DataOpGate.shared },
    private val clock: () -> Long = System::currentTimeMillis,
    private val generation: () -> Long = MemoryWipe.generation::get,
    /** After the commit: the embeddings (the memories become searchable). No nudge and no model call. */
    private val afterSave: suspend (generation: Long, saved: List<SavedMemory>) -> Unit = { g, saved ->
        ExtractFollowUp.afterSave(app, g, saved, trusted = true, confidence = 1.0, personaSnapshot = "", nudge = false)
    },
) {
    /** One memory a save would write. [kind] is `fact` or `caption`; [review] is the review state it would get. */
    data class Proposed(val frameId: String, val kind: String, val text: String, val type: String, val review: String)

    enum class Reason(val message: String) {
        NOT_DONE("Only a finished run can be saved."),
        NO_MEMORY_NODE("This pipeline has no Save memory step, so a run only shows its output (playground)."),
        NOT_PHOTO_RUN("Only runs over photos can be saved."),
    }

    /** [saved] is the number of memories already saved from this run (null: not yet). [reason] is set when the run cannot be saved at all. */
    data class Proposal(val reason: Reason?, val saved: Int?, val memories: List<Proposed>) { val saveable get() = reason == null }

    sealed interface Result {
        val message: String
        /** The fixed word the canvas page switches on. */
        val code: String
        data class Saved(val count: Int, val needReview: Int) : Result {
            override val code get() = "SAVED"
            override val message get() = "Saved $count memor${if (count == 1) "y" else "ies"}" + if (needReview > 0) " ($needReview need review in Memory)." else "."
        }
        data class AlreadySaved(val count: Int) : Result { override val code get() = "ALREADY_SAVED"; override val message get() = "Already saved ($count). Nothing was written again." }
        data class NotSaveable(val reason: Reason) : Result { override val code get() = "NOT_SAVEABLE"; override val message get() = "Not saved: " + reason.message }
        data object NothingToSave : Result { override val code = "NOTHING"; override val message = "Nothing to save: this run has no valid memory (its photos may have been forgotten)." }
        /** The wait for a running export ([byExport]) or delete ran out. */
        data class Busy(val byExport: Boolean) : Result {
            override val code get() = "BUSY"
            override val message get() = if (byExport) "Not saved: an export is running. Try again in a minute, or when it finishes."
            else "Not saved: a delete (Forget or Delete all) is running. Try again in a few seconds."
        }
        data object Wiped : Result { override val code = "WIPED"; override val message = "Not saved: Delete all ran while saving. Nothing was written." }
    }

    private class Marked : RuntimeException(null, null, false, false)
    /** The run or one of its photos vanished between the load and the commit: the save rolls back whole. */
    private class Gone(val result: Result) : RuntimeException(null, null, false, false)

    /** Test seam: runs right after the load, before the save starts (a test wipes or deletes there). Null in production. */
    @Volatile internal var testAfterLoad: (suspend () -> Unit)? = null

    /** [carried]: per fact (same index), the photos the output item that produced it was derived from (codex r16: per saved memory, never the run's union). */
    /** [delivered]: per fact (same index), the stored run's proof that its photo reached the model (Room v16); [captions] are only proved ones. */
    private class Built(val facts: List<Fact>, val ok: List<Boolean>, val captions: List<FrameCaption>, val frames: Set<String>, val carried: List<Set<String>> = emptyList(),
                        val delivered: List<Boolean> = emptyList())

    private class Loaded(val refusal: Reason?, val built: Built?)

    /** What a save would write now; read-only. Null when the run is unknown. */
    suspend fun proposal(runId: String): Proposal? {
        db.canvasDao().run(runId) ?: return null
        val saved = db.canvasSlice3Dao().meta(CanvasSaveMarker.key(runId))?.toIntOrNull()
        val l = load(runId)
        val b = l.built
        // Room v16 (user ruling 2026-11-10): a caption is proposed and saved only when the stored run PROVES its photo reached the model; a run stored
        // before v16 (or a caption of an unproved call) keeps the codex r10 refusal
        val rows = if (b == null) emptyList() else b.facts.mapIndexed { i, f -> Proposed(f.sourceFrameIds.first(), "fact", f.text, f.type, if (b.ok[i]) "OK" else "NEEDS_REVIEW") } +
            b.captions.map { Proposed(it.frameId, "caption", it.caption, "caption", "OK") }
        return Proposal(l.refusal, saved, rows)
    }

    /** Saves the run's memories (see the class comment). Failures other than the typed ones propagate: the caller shows them, nothing is dropped silently. */
    suspend fun save(runId: String): Result {
        db.canvasDao().run(runId) ?: throw NoSuchElementException("run")
        val key = CanvasSaveMarker.key(runId)
        db.canvasSlice3Dao().meta(key)?.let { return Result.AlreadySaved(it.toIntOrNull() ?: 0) }
        // the wipe generation is read BEFORE the run, its outputs and its photos are loaded: a Delete all that ran after this point makes the save refuse (Wiped), never commit what it loaded
        val gen = generation()
        val l = load(runId)
        testAfterLoad?.invoke()
        l.refusal?.let { PcLog.i(TAG, "canvas save refused"); return Result.NotSaveable(it) }
        val b = l.built ?: return Result.NothingToSave
        if (b.facts.isEmpty() && b.captions.isEmpty()) return Result.NothingToSave
        val prefix = PREFIX + runId + "_"
        var needReview = 0
        val res = try {
            gate().write {
                ExtractSave.saveExtraction(
                    app, prefix, ExtractOutput(b.facts, captions = b.captions, provenance = true, imagesDelivered = b.captions.isNotEmpty()), b.frames, claims = null,
                    deliveredOf = { _, index -> index?.let { b.delivered.getOrNull(it) } ?: true }, // a caption is saved only with proof; a fact gets its item's proof
                    tier = MemoryTier.SHORT, reviewState = ReviewState.NEEDS_REVIEW, generation = gen, nowMs = clock(),
                    carriedOf = { ids, index -> index?.let { b.carried.getOrNull(it) } ?: ids.toSet() }, // codex r16: only the item that produced this memory
                    inTransaction = { saved ->
                        if (db.canvasSlice3Dao().meta(key) != null) throw Marked() // a concurrent save won: roll this one back
                        // re-read inside the transaction what the load saw: the run must still be DONE and every source photo must still be a live row (a forgotten one is fenced by the tombstone check)
                        val run = db.canvasDao().run(runId)
                        if (run == null || run.status != "DONE") throw Gone(Result.NotSaveable(Reason.NOT_DONE))
                        if (b.frames.toList().chunked(500).sumOf { db.frameDao().byIds(it).size } != b.frames.size) throw Gone(Result.NothingToSave)
                        val view = db.memoryViewDao()
                        saved.filter { it.index != null && b.ok[it.index!!] }.forEach { view.moveReviewState(it.memoryId, ReviewState.NEEDS_REVIEW.name, ReviewState.OK.name) }
                        needReview = saved.count { it.index != null && !b.ok[it.index!!] }
                        // a derived (summary) memory cites the other memories saved from the same photos
                        val summaries = saved.filter { it.index != null && b.facts[it.index!!].type == SummaryNode.FACT_TYPE }
                        val edges = summaries.flatMap { s -> saved.filter { o -> o !in summaries && o.frameIds.any { it in s.frameIds } }.map { MemorySourceEntity(s.memoryId, it.memoryId) } }
                        if (edges.isNotEmpty()) db.forgetDao().insertEdges(edges)
                        if (saved.isNotEmpty()) db.canvasSlice3Dao().putMeta(key, saved.size.toString())
                    },
                )
            }
        } catch (e: DataOpGate.WriteBlocked) {
            PcLog.i(TAG, "canvas save blocked by an export or delete")
            return Result.Busy(e.byExport)
        } catch (g: Gone) {
            return g.result
        } catch (m: Marked) {
            return Result.AlreadySaved(db.canvasSlice3Dao().meta(key)?.toIntOrNull() ?: 0)
        }
        return when (res) {
            is ExtractSaveResult.Saved -> {
                if (res.memories.isEmpty()) return Result.NothingToSave
                try {
                    afterSave(gen, res.memories)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    PcLog.w(TAG, "canvas save: follow-up failed, the memories are saved")
                }
                PcLog.i(TAG, "canvas run saved")
                Result.Saved(res.memories.size, needReview)
            }
            ExtractSaveResult.Wiped -> Result.Wiped
            ExtractSaveResult.StaleClaim, ExtractSaveResult.Undelivered -> Result.NothingToSave
        }
    }

    private suspend fun load(runId: String): Loaded {
        val run = db.canvasDao().run(runId) ?: return Loaded(Reason.NOT_DONE, null)
        if (run.status != "DONE") return Loaded(Reason.NOT_DONE, null)
        // the runner stores the canonical (hash) form, whose schema-2 types carry their version (`memory.write@1`): compare on the bare type
        val p: Pipeline = PipelineJson.decode(run.pipelineJson).getOrNull()?.let { d -> d.copy(nodes = d.nodes.map { it.copy(type = it.type.substringBefore('@')) }) }
            ?: return Loaded(Reason.NO_MEMORY_NODE, null)
        val sinks = p.nodes.filter { it.type == NodeTypes.MEMORY_WRITE }
        if (sinks.isEmpty()) return Loaded(Reason.NO_MEMORY_NODE, null)
        if (p.nodes.any { it.type == NodeTypes.INPUT_MOMENTS || it.type == NodeTypes.INPUT_SCENES || it.type == NodeTypes.SRC_WATCH_MOTION }) return Loaded(Reason.NOT_PHOTO_RUN, null)
        // what each node produced per photo, as the runner held it while the run went
        val outputs = HashMap<String, MutableMap<String, ItemFlow.Out>>()
        // codex r15/r17: each row records the photos ITS value was derived from ("mf:<frameId>", the runner's per-row provenance); they travel with the
        // value along the edges into the save, so a side branch that also read the photo never adds its sources
        for (row in db.canvasDao().items(runId)) {
            if (row.status != "OK") continue
            val v = CanvasValueJson.decodeExtraction(row.outputJson) ?: continue
            val derivedFrom = row.batchId?.split('|')?.filter { it.startsWith(CanvasRunner.MEMBER_TOKEN) }?.map { it.removePrefix(CanvasRunner.MEMBER_TOKEN) }.orEmpty()
            outputs.getOrPut(row.nodeId) { LinkedHashMap() }[row.frameId] = ItemFlow.Out(v, row.branch, com.rokid.context.model.PayloadSources.frames(derivedFrom), imageDelivered = row.imageDelivered)
        }
        class Cand(val frameId: String, val fact: Fact, var ok: Boolean) { val carried = HashSet<String>(); var delivered = true }
        val facts = LinkedHashMap<Triple<String, String, List<String>>, Cand>()
        val captions = LinkedHashMap<String, String>()
        for (sink in sinks) {
            val gathered = ItemFlow.gather(p.edges.filter { it.to == sink.id }, outputs)
            for ((frameId, v) in gathered.inputs.entries.sortedBy { it.key }) {
                val x = v as? CanvasValue.Extraction ?: continue
                val trusted = x.trusted == true
                for (f in x.facts.filter { it.text.isNotBlank() }) {
                    val src = f.sourceFrameIds.ifEmpty { listOf(frameId) }.distinct().sorted()
                    val c = facts.getOrPut(Triple(f.text.trim(), f.type, src)) { Cand(frameId, f.copy(text = f.text.trim(), sourceFrameIds = src), true) }
                    if (!trusted) c.ok = false // a fact only counts as checked when every photo that carried it was
                    c.carried += gathered.sources[frameId]?.frameIds.orEmpty()
                    if (gathered.imageDelivered[frameId] != true) c.delivered = false // proved only when every item that carried it was
                }
                // Room v16: only a caption whose photo the stored run proved delivered (a pre-v16 run has no proof: refused as before)
                if (gathered.imageDelivered[frameId] == true) x.captions.filter { it.caption.isNotBlank() && it.frameId == frameId }.forEach { captions.putIfAbsent(it.frameId, it.caption.trim()) }
            }
        }
        // valid memories only: the photo they came from must still be live (not forgotten, not tombstoned, not expired)
        val candidates = (facts.values.flatMap { it.fact.sourceFrameIds + it.carried } + captions.keys).distinct()
        val live = if (candidates.isEmpty()) emptySet() else PipelineRecords.framesByIds(app, candidates).map { it.id }.toSet()
        val kept = facts.values.mapNotNull { c ->
            val src = c.fact.sourceFrameIds.filter { it in live }
            // codex r15: a fact derived from a photo that is gone (forgotten) is not saved, even when its own cited photo is live
            if (src.isEmpty() || c.carried.any { it !in live }) null else Cand(c.frameId, c.fact.copy(sourceFrameIds = src), c.ok).also { k -> k.carried += c.carried; k.delivered = c.delivered }
        }
        val caps = captions.filterKeys { it in live }.map { FrameCaption(it.key, it.value) }
        val frames = (kept.flatMap { it.fact.sourceFrameIds + it.carried } + caps.map { it.frameId }).toSet()
        return Loaded(null, Built(kept.map { it.fact.copy(tags = (it.fact.tags + TAG_CANVAS).distinct()) }, kept.map { it.ok }, caps, frames,
            kept.map { it.carried + it.fact.sourceFrameIds }, kept.map { it.delivered }))
    }

    companion object {
        private const val TAG = "CanvasRunSave"
        /** Memory ids of a canvas save start with this, then the run id (`rokid_` keeps them in the pipeline-memory family). */
        const val PREFIX = "rokid_cv_"
        const val TAG_CANVAS = "canvas"
    }
}
