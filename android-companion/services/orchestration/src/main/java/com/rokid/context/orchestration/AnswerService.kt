package com.rokid.context.orchestration

import android.content.Context
import androidx.room.withTransaction
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.contract.AnswerOutput
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.core.model.TextHygiene
import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.connectors.actions.ActionsGraph
import com.rokid.context.connectors.sources.SourcesGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
import com.rokid.context.memory.retrieval.MemoryRetriever
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.ScoredMemory
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.model.ModelImageInput
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars
import com.rokid.context.work.frames.FramePhotos
import com.rokid.context.work.frames.recall.PhotoHit
import com.rokid.context.work.frames.recall.PhotoRecall
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** `{{context_sources}}` (spec §5.4). Phase 7 supplies calendar/contacts/notes/location lines; v1 Phase 5 has none. */
fun interface ContextSources {
    suspend fun lines(question: String): List<String>
    /** Phase 7: [nowMs] is the ask's clock. Defaults to [lines] without it (additive). */
    suspend fun lines(question: String, nowMs: Long): List<String> = lines(question)
    companion object { val None = ContextSources { emptyList() } }
}

class EmptyQuestionException : IllegalArgumentException("Empty question")
/** Message kept from the legacy READ path: RetryClassifier retries "temporarily unavailable". */
class NoMemoriesException : IllegalStateException("Context temporarily unavailable")

/** Shared text hygiene for questions and retrieved memories (Phase 4 rules, moved here). */
object AskText {
    /** NEL, LS and PS are listed explicitly: the JVM's `\s` and `\p{Cntrl}` miss them (Android ICU may not). */
    fun collapse(text: String) = text.replace(TextHygiene.SEPARATORS, " ").trim()

    /** Cut at a word boundary (when there is one) and mark the cut with an ellipsis. */
    fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        val at = cut.lastIndexOf(' ')
        return (if (at > max / 2) cut.take(at) else cut).trimEnd() + "…"
    }

    /** Lines in rank order; whole lowest-ranked lines are dropped until the joined text fits. */
    fun bounded(lines: List<String>, maxChars: Int = 5_000): List<String> {
        val kept = mutableListOf<String>()
        var size = 0
        for (l in lines) {
            val add = l.length + if (kept.isEmpty()) 0 else 1
            if (size + add > maxChars) break
            kept += l; size += add
        }
        return kept
    }
}

/**
 * spec §4.7 answer path: retriever (current mode) → ANSWER prompt with the top-k memories (id, time, confidence,
 * frame ref, text) as delimited data + `{{context_sources}}` → RoleRunner (guided decode, contract, sources ⊆
 * retrieved ids) → one `ask` row. HUD overlay + TTS are Phase 6; `proposedAction` handling is Phase 7.
 */
class AnswerService(
    private val memory: MemoryGraph,
    private val runner: RoleRunner,
    private val retriever: MemoryRetriever,
    private val settings: () -> RetrievalSettings,
    private val sources: ContextSources = ContextSources.None,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val proposals: ProposalIntake = ProposalIntake.None,
    /**
     * G10 (MVP section 9.5 "trigger immediate priority reprocessing"): retrieval found nothing to answer from. The app
     * runs the capture pipeline now, so photos not processed yet become memories before the next ask. Never fails the ask.
     */
    private val onNoMatch: () -> Unit = {},
    /** Spec P2.3: with a keyframe backlog, the newest frames are extracted first (user-started, bypassing the budget). Never fails the ask. */
    private val beforeRetrieve: suspend () -> Unit = {},
    /** Wave 3 (R4): the photos that match the question by image vector, at most 2 are used. Never fails the ask: an error or a timeout is "none". */
    private val photos: suspend (question: String, nowMs: Long) -> List<PhotoHit> = { _, _ -> emptyList() },
    /** Asked before every photo search ([AskPhotoPolicy.allowed]: the user's switch, pipeline pause, quiet hours, an Answer model that sees images). False = no scan, no image, no photo line. */
    private val photosAllowed: suspend () -> Boolean = { false },
    /** The endpoint the gate checked (the ANSWER primary): images are sent only there, never to a fallback. */
    private val imageEndpointId: suspend () -> String? = { null },
    /** Decrypted in memory only (`FramePhotos.load`); a missing key = unreadable photo, which is skipped. */
    private val photoImages: suspend (frameIds: List<String>) -> Map<String, ModelImageInput> = { emptyMap() },
) {
    /** [photos]: the photo lines of the prompt, in order; [images]: the attached images (`image=<n>` is a 1-based index into it, S10). */
    data class Retrieved(
        val vars: PromptVars, val memories: List<MemoryEntity>, val photos: List<PhotoCite> = emptyList(), val images: List<ModelImageInput> = emptyList(),
        val imageEndpointId: String? = null,
    ) {
        val ids: Set<String> get() = memories.map { it.id }.toSet() + photos.map { it.citeId }
    }

    /** [citeId] = the caption memory's id, else `frame:<frameId>`. [ts] is the capture time (the stored `frame.ts`, or `frame_meta.capturedAtMs` once expired). */
    data class PhotoCite(val citeId: String, val frameId: String, val ts: Long?, val stored: Boolean, val receivedAt: Long? = null)

    suspend fun retrieve(question: String, nowMs: Long): Retrieved {
        val q = AskText.collapse(question).take(MAX_QUESTION)
        val s = settings().clamped()
        val hits = retriever.retrieve(q, s, nowMs).hits.map { it.memory }
        // No hits: the newest trusted memories, under the same minConfidence and top-k the retriever applies.
        val poolAll = hits.ifEmpty {
            memory.memories.recent(30).filter { it.reviewState == ReviewState.OK && it.confidence >= s.minConfidence }.take(minOf(FALLBACK, s.topK))
        }
        val recalled = recalledPhotos(q, nowMs)
        // A photo whose caption is already a retrieved memory is listed once, as the photo line.
        val citeIds = recalled.map { it.second }.toSet()
        val pool = poolAll.filter { it.id !in citeIds }
        val images = mutableListOf<ModelImageInput>()
        val imageNo = HashMap<String, Int>()
        for ((hit, citeId) in recalled) {
            if (images.size >= MAX_PHOTO_IMAGES || !hit.photoStored) continue
            val image = try { photoImages(listOf(hit.frameId))[hit.frameId] } catch (c: CancellationException) { throw c } catch (e: Exception) { null }
            if (image != null) { images += image; imageNo[citeId] = images.size }
        }
        val photoLines = recalled.map { (hit, citeId) -> photoLine(hit, citeId, imageNo[citeId]) }
            .let { if (images.isEmpty()) it else listOf(IMAGE_NOTE) + it }
        val lines = AskText.bounded(pool.map(::line), 5_000 - photoLines.sumOf { it.length + 1 })
        val kept = pool.take(lines.size)
        return Retrieved(
            PromptVars(
                // The question is user (later ASR) text: a data block folds look-alike markers as for memories.
                question = PromptComposer.dataBlock("question", listOf(q)),
                retrievedMemories = PromptComposer.dataBlock("memories", lines + photoLines),
                contextSources = PromptComposer.dataBlock("sources", sources.lines(q, nowMs)),
            ),
            kept,
            recalled.map { (hit, citeId) -> PhotoCite(citeId, hit.frameId, hit.ts, hit.photoStored, hit.receivedAt) },
            images,
            if (images.isEmpty()) null else try { imageEndpointId() } catch (c: CancellationException) { throw c } catch (_: Exception) { null },
        )
    }

    /**
     * [retrieved] is what [retrieve] returns for the same arguments; [hits] are the scored candidates before the prompt bound (empty on [fallback]);
     * [lines] are the memory lines of the prompt, in order. [fallback] = no hits, the newest trusted memories stand in.
     */
    data class ScoredRetrieved(
        val retrieved: Retrieved, val hits: List<ScoredMemory>, val mode: RetrievalMode, val semanticUnavailable: Boolean, val fallback: Boolean,
        val lines: List<String> = emptyList(),
    )

    /**
     * As [retrieve], but keeps the scores. [notAfterMs] drops memories with createdAt > it from the hits AND the fallback pool, BEFORE the retriever's
     * limits (S34). Used by the Ask debug view only; [retrieve] and [answer] are unchanged. It sends no photos itself: the debug flow takes its photos
     * through [com.rokid.context.canvas.AskDebugPhotos] ([askDebugPhotos], the live Ask's gate; Refuse until bound).
     */
    suspend fun retrieveScored(question: String, nowMs: Long, notAfterMs: Long? = null): ScoredRetrieved {
        val q = AskText.collapse(question).take(MAX_QUESTION)
        val s = settings().clamped()
        val r = retriever.retrieve(q, s, nowMs, notAfterMs = notAfterMs)
        val fallback = r.hits.isEmpty()
        val pool = if (!fallback) r.hits.map { it.memory } else
            memory.memories.recent(30, notAfterMs).filter { it.reviewState == ReviewState.OK && it.confidence >= s.minConfidence }.take(minOf(FALLBACK, s.topK))
        val lines = AskText.bounded(pool.map(::line))
        val kept = pool.take(lines.size)
        val vars = PromptVars(
            question = PromptComposer.dataBlock("question", listOf(q)),
            retrievedMemories = PromptComposer.dataBlock("memories", lines),
            contextSources = PromptComposer.dataBlock("sources", sources.lines(q, nowMs)),
        )
        return ScoredRetrieved(Retrieved(vars, kept), r.hits, r.modeUsed, r.semanticUnavailable, fallback, lines)
    }

    private suspend fun recalledPhotos(q: String, nowMs: Long, notAfterMs: Long? = null): List<Pair<PhotoHit, String>> {
        val hits = try { if (photosAllowed()) photos(q, nowMs) else emptyList() } catch (c: CancellationException) { throw c } catch (e: Exception) {
            Log.i(TAG, "photo recall failed (${e.javaClass.simpleName})"); emptyList()
        }
        // The relevance floor: below it a photo gets neither an image nor a line (provisional values: AskPhotoPolicy).
        // Ask debug only: a photo known to be captured (else received) after the ask did not exist for it.
        val visible = if (notAfterMs == null) hits else hits.filter { h -> (h.ts ?: h.receivedAt)?.let { it <= notAfterMs } ?: true }
        return AskPhotoPolicy.select(visible.distinctBy { it.frameId }).map { it to (it.captionMemoryId ?: (PHOTO_CITE_PREFIX + it.frameId)) }
    }

    /**
     * `id=<cite> · <time> · photo[ · image=<n>] · <caption | no caption yet>`. The time is the capture time; an expired photo without one falls back to
     * the receive time and says so ("received", user ruling 2026-10-22 over S9).
     */
    private fun photoLine(hit: PhotoHit, citeId: String, imageNo: Int?): String {
        val fmt = TIME.withZone(zone())
        val at = hit.ts?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: hit.receivedAt?.let { "received " + fmt.format(Instant.ofEpochMilli(it)) } ?: "time unknown"
        val caption = hit.captionText?.let { AskText.collapse(it) }?.takeIf { it.isNotEmpty() }?.let { AskText.clip(it, MAX_PHOTO_CAPTION) } ?: "no caption yet"
        return "id=$citeId · $at · photo" + (imageNo?.let { " · image=$it" } ?: "") + " · $caption"
    }

    /**
     * The Ask debug view's photo gate (canvas slice 2, S13): the SAME gate and photo search a live Ask uses. [allowed] is the wave 3 gate
     * ([photosAllowed]: switch, pause, quiet hours, vision on the checked endpoint); [recall] runs the gated search, [AskPhotoPolicy.select] and the live
     * photo lines. The images load lazily (replay only) and carry the endpoint the gate checked: [com.rokid.context.canvas.AskPhotoRecall.imageEndpointId].
     * With no such endpoint no image is offered at all (fail closed).
     */
    fun askDebugPhotos(): com.rokid.context.canvas.AskDebugPhotos = object : com.rokid.context.canvas.AskDebugPhotos {
        override suspend fun allowed(): Boolean = photosAllowed()
        override suspend fun recall(question: String, nowMs: Long): com.rokid.context.canvas.AskPhotoRecall = debugPhotoRecall(question, nowMs)
    }

    internal suspend fun debugPhotoRecall(question: String, nowMs: Long): com.rokid.context.canvas.AskPhotoRecall {
        val none = com.rokid.context.canvas.AskPhotoRecall(emptyList(), emptySet(), emptySet())
        val q = AskText.collapse(question).take(MAX_QUESTION)
        val recalled = recalledPhotos(q, nowMs, notAfterMs = nowMs) // re-checks the gate before any search; photos after the ask are dropped
        if (recalled.isEmpty()) return none
        val endpoint = try { imageEndpointId() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
        val withImage = if (endpoint == null) emptyList() else recalled.filter { it.first.photoStored }.take(MAX_PHOTO_IMAGES)
        val imageNo = withImage.mapIndexed { i, (_, cite) -> cite to i + 1 }.toMap()
        val lines = recalled.map { (hit, cite) -> photoLine(hit, cite, imageNo[cite]) }.let { if (imageNo.isEmpty()) it else listOf(IMAGE_NOTE) + it }
        return com.rokid.context.canvas.AskPhotoRecall(
            lines, recalled.map { it.second }.toSet(), recalled.map { it.first.frameId }.toSet(), recalled.mapNotNull { it.first.captionMemoryId }.toSet(),
            images = { withImage.mapNotNull { (hit, _) -> photoImages(listOf(hit.frameId))[hit.frameId] } },
            imageEndpointId = endpoint.takeIf { withImage.isNotEmpty() },
        )
    }

    /** Phone/HUD asks: every failure becomes one of the fixed short messages. */
    suspend fun answer(question: String, source: String, nowMs: Long = System.currentTimeMillis()): AskOutcome = try {
        answerOrThrow(question, source, nowMs)
    } catch (c: CancellationException) {
        throw c
    } catch (e: EmptyQuestionException) {
        AskOutcome.Failed(AskActions.EMPTY_QUESTION)
    } catch (e: NoMemoriesException) {
        AskOutcome.Failed(AskActions.NO_MEMORIES)
    } catch (e: ModelCallException) {
        Log.i(TAG, "answer failed: kind=${e.kind} retryable=${e.retryable}") // never the message: it can carry provider text
        AskOutcome.Failed(if (e.kind == FailureKind.INVALID_OUTPUT) AskActions.FAILED else AskActions.AI_UNAVAILABLE)
    } catch (e: Exception) {
        Log.i(TAG, "answer failed (${e.javaClass.simpleName})")
        AskOutcome.Failed(AskActions.FAILED)
    }

    /** Queue jobs: failures propagate so the queue's retry policy applies (RetryClassifier). */
    /** [askId]: a queue job passes one derived from its id, so a re-run after a cancellation adds no second Ask row. */
    /**
     * [generation]: read before retrieval. If Delete all runs while the model answers, the ask (built from memories that
     * no longer exist) is not saved and the call fails as "no memories" (review I1).
     */
    suspend fun answerOrThrow(
        question: String, source: String, nowMs: Long = System.currentTimeMillis(), askId: String? = null,
        generation: Long = MemoryWipe.generation.get(),
    ): AskOutcome.Answered {
        val q = AskText.collapse(question).take(MAX_QUESTION)
        if (q.isEmpty()) throw EmptyQuestionException()
        try {
            beforeRetrieve()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "backlog extraction before ask failed (${e.javaClass.simpleName})")
        }
        val r = retrieve(q, nowMs)
        // An action command ("note that…") needs no memory: only other asks stop at "nothing yet" (user notes).
        if (r.memories.isEmpty() && r.photos.isEmpty() && !ActionTriggers.matches(q)) {
            runCatching(onNoMatch).onFailure { Log.w(TAG, "no-match reprocess not queued (${it.javaClass.simpleName})") }
            throw NoMemoriesException()
        }
        val out = runner.run(RoleCall(PromptKey.ANSWER, r.vars, images = r.images, imagesOnlyOnEndpoint = r.imageEndpointId, inputIds = r.ids, callClass = com.rokid.context.model.CallClass.USER)).output as AnswerOutput
        val text = AskText.collapse(out.answer).take(MAX_ANSWER)
        if (text.isEmpty()) throw ModelCallException("ANSWER reply was empty", retryable = true, kind = FailureKind.INVALID_OUTPUT)
        val id = askId ?: "ask_${UUID.randomUUID()}"
        var redacted = false
        val saved = memory.db.withTransaction {
            if (MemoryWipe.generation.get() != generation) return@withTransaction false
            // Phase 7 Forget fence (Stream F fix round 1 I1): a memory in the prompt or cited was forgotten while the
            // model answered, so the reply may hold forgotten text: store it redacted, with no sources and no proposal.
            redacted = !com.rokid.context.memory.forget.ForgetFence.allLive(memory.db, r.ids + out.sources)
            memory.asks.add(
                if (redacted) AskEntity(id, nowMs, q, com.rokid.context.memory.forget.ForgetService.REDACTED, "[]", null, source, true)
                else AskEntity(id, nowMs, q, text, AskSources.encode(out.sources), null, source, false),
            )
            true
        }
        if (!saved) throw NoMemoriesException()
        if (redacted) return AskOutcome.Answered(id)
        val photo = firstCitedPhoto(r, out.sources)
        // Phase 7 (R-62): only after the ask row commits, so a proposal always has its origin row.
        // The ask is saved: a failing intake must not fail it (nor make a queue job retry it). Cancellation still propagates.
        val proposalId = out.proposedAction?.let { action ->
            try {
                proposals.onProposed(ProposalOrigin.ASK, id, action, nowMs)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "proposal intake failed (${t.javaClass.simpleName})")
                null
            }
        }
        return AskOutcome.Answered(id, out.proposedAction, proposalId, photo?.first, photo?.second)
    }

    /** The first cited source that is a photo still in storage: (frame id, capture time). An expired photo gives no HUD label (S9). */
    internal suspend fun firstCitedPhoto(r: Retrieved, cited: List<String>): Pair<String, Long>? {
        val cites = r.photos.associateBy { it.citeId }
        val memories = r.memories.associateBy { it.id }
        // The fence ran at the commit; a Forget can land between it and here, so the moment is checked again (only a time could leak, but none does).
        suspend fun live(frameId: String, memoryId: String? = null) =
            com.rokid.context.memory.forget.ForgetFence.allLive(memory.db, setOfNotNull(PHOTO_CITE_PREFIX + frameId, memoryId))
        for (id in cited) {
            val cite = cites[id]
            if (cite != null) { if (cite.stored && cite.ts != null && live(cite.frameId)) return cite.frameId to cite.ts; continue }
            val frameId = memories[id]?.frameId ?: continue
            memory.db.frameDao().byIds(listOf(frameId)).firstOrNull()?.takeIf { live(it.id, id) }?.let { return it.id to it.ts }
        }
        return null
    }

    private fun line(m: MemoryEntity): String =
        "id=${m.id} · ${TIME.withZone(zone()).format(Instant.ofEpochMilli(m.createdAt))} · confidence ${"%.2f".format(Locale.US, m.confidence)} · " +
            "frame=${m.frameId ?: "none"} · ${AskText.clip(AskText.collapse(m.text), MAX_MEMORY_CHARS)}"

    companion object {
        private const val TAG = "AnswerService"
        private const val MAX_QUESTION = 500
        private const val MAX_ANSWER = 2_000
        private const val MAX_MEMORY_CHARS = 500
        private const val FALLBACK = 10
        private const val MAX_PHOTO_IMAGES = AskPhotoPolicy.MAX_PHOTOS
        private const val MAX_PHOTO_CAPTION = 300
        private const val IMAGE_NOTE = "note: image=N on a photo line is the Nth attached image"
        private const val ASK_EXTRACT_TIMEOUT_MS = 30_000L
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

        /**
         * The privacy gate of Ask photos: the user's switch (off by default), the pipeline not paused, not inside quiet hours (unknown quiet hours count as
         * quiet), and an ANSWER endpoint that can see images. Runs off Main (reads prefs and the endpoint table).
         */
        /** The endpoint that receives the images: the ANSWER binding's primary (null = none). */
        internal suspend fun answerImageEndpoint(context: Context): String? = AiGraph.get(context).bindings.get(com.rokid.context.ai.Role.ANSWER)?.endpointId

        internal suspend fun photosAllowedNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
            if (!AskPhotoPolicy.enabled(context)) return false
            val hud = com.rokid.context.orchestration.nudge.NudgeEnvironment.hud?.invoke()
            val quiet = hud == null || hud.quietHours?.let { com.rokid.context.core.model.QuietWindow.occurrenceAt(it, nowMs, hud.zone) } != null
            val ai = AiGraph.get(context)
            val vision = ai.bindings.get(com.rokid.context.ai.Role.ANSWER)?.endpointId?.let { ai.endpoints.byId(it) }
                ?.capabilities?.contains(com.rokid.context.ai.Capability.VISION) == true
            // Paused = a pause covers now (the pause button writes pause records) or the legacy pipeline flag is off.
            val paused = MemoryGraph.get(context).db.pauseDao().countCovering(nowMs) > 0
            return AskPhotoPolicy.allowed(true, com.rokid.context.data.runtime.AppConfigStore.isPipelineEnabled(context) && !paused, quiet, vision)
        }

        /** Adapts the connectors provider: the ask's nowMs reaches it (the one-argument form falls back to the wall clock). */
        fun sourcesFrom(provider: ContextSourceProvider): ContextSources = object : ContextSources {
            override suspend fun lines(question: String): List<String> = provider.lines(question, System.currentTimeMillis())
            override suspend fun lines(question: String, nowMs: Long): List<String> = provider.lines(question, nowMs)
        }

        /** [extractFirst] false: no backlog extraction before retrieving (a queued ask that is no longer fresh, review M6). */
        fun forApp(context: Context, extractFirst: Boolean = true): AnswerService {
            val ai = AiGraph.get(context)
            val store = RetrievalSettingsStore.get(context)
            val provider = SourcesGraph.get(context).provider
            return AnswerService(
                MemoryGraph.get(context), ai.runner, ai.retriever, { store.settings.value },
                sources = sourcesFrom(provider),
                proposals = OriginFencedIntake(context, MemoryGraph.get(context), ActionsGraph.get(context).intake),
                photosAllowed = { photosAllowedNow(context) },
                imageEndpointId = { answerImageEndpoint(context) },
                // The scan has its own budget (below the outer timeout), so partial hits survive; the timeout is only the backstop.
                photos = { q, _ ->
                    kotlinx.coroutines.withTimeoutOrNull(AskPhotoPolicy.ASK_TIMEOUT_MS) {
                        PhotoRecall.forApp(context).search(q, imageK = AskPhotoPolicy.MAX_PHOTOS, captionK = 0, budgetMs = AskPhotoPolicy.SCAN_BUDGET_MS).image
                    }.orEmpty()
                },
                photoImages = { ids -> FramePhotos.load(context, ids).photos.associate { it.frameId to it.image } },
                beforeRetrieve = { if (extractFirst) kotlinx.coroutines.withTimeoutOrNull(ASK_EXTRACT_TIMEOUT_MS) { com.rokid.context.work.frames.KeyframeGraph.get(context).pipeline.extractForAsk() } },
                onNoMatch = { if (com.rokid.context.data.runtime.AppConfigStore.isPipelineEnabled(context)) com.rokid.context.work.OrchestrationScheduler(context).enqueueFrameIngestIfIdle() },
            )
        }
    }
}
