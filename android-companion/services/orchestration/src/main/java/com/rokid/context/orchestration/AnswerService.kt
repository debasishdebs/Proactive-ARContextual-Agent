package com.rokid.context.orchestration

import android.content.Context
import androidx.room.withTransaction
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.canvas.FlowPlan
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
import com.rokid.context.orchestration.retrieval.TimeWindow
import com.rokid.context.orchestration.retrieval.WindowResolver
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars
import com.rokid.context.work.frames.FramePhotos
import com.rokid.context.work.frames.recall.PhotoHit
import com.rokid.context.work.frames.recall.PhotoRecall
import java.time.ZoneId
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
    /** The Ask debug view's historical photo search: [PhotoRecall.search] with the ask time as the cutoff, applied BEFORE top-k (vectors and captions). */
    private val photosAsOf: suspend (question: String, notAfterMs: Long) -> List<PhotoHit> = { _, _ -> emptyList() },
    /** Asked before every photo search ([AskPhotoPolicy.allowed]: the user's switch, pipeline pause, quiet hours, an Answer model that sees images). False = no scan, no image, no photo line. */
    /** Words to photos inside a time window (a windowed [RagSource.PhotoStore]); null = no windowed search, which finds nothing. Ask itself uses no window. */
    private val photosWindowed: (suspend (question: String, nowMs: Long, notAfterMs: Long?, notBeforeMs: Long) -> List<PhotoHit>)? = null,
    private val photosAllowed: suspend () -> Boolean = { false },
    /** The endpoint the gate checked (the ANSWER primary): images are sent only there, never to a fallback. */
    private val imageEndpointId: suspend () -> String? = { null },
    /** Decrypted in memory only (`FramePhotos.load`); a missing key = unreadable photo, which is skipped. */
    private val photoImages: suspend (frameIds: List<String>) -> Map<String, ModelImageInput> = { emptyMap() },
    /** The published Query pipeline (v0.9.8 slice B); none = the built-in plan. */
    private val flows: FlowPlans = FlowPlans.None,
    /**
     * Retrieval-quality spec section 3: the date and time words of a question as a window for the memory store. The rules (pure, local) by default; the
     * optional LLM fallback and the canvas `rag.window` switch (a later package) replace this one seam. [WindowResolver.None] = no window, today's behaviour.
     */
    private val windows: WindowResolver = WindowResolver.Rules,
) {
    /** [photos]: the photo lines of the prompt, in order; [images]: the attached images (`image=<n>` is a 1-based index into it, S10). */
    data class Retrieved(
        val vars: PromptVars, val memories: List<MemoryEntity>, val photos: List<PhotoCite> = emptyList(), val images: List<ModelImageInput> = emptyList(),
        val imageEndpointId: String? = null,
        /** The sources of the nudges a plan's history read (not citable): the commit fence covers them and the row keeps them as dependencies, as a nudge does. */
        val fenceIds: Set<String> = emptySet(),
        /** The plan's delivery target (`caller` for the built-in flow). */
        val target: String = "caller",
        /** Why semantic search did not run for this ask (null = it ran, or lexical was chosen). */
        val semanticOff: com.rokid.context.memory.retrieval.SemanticOff? = null,
    ) {
        val ids: Set<String> get() = memories.map { it.id }.toSet() + photos.map { it.citeId }

        /**
         * Codex r12: the stored content this prompt carries. Frames: the recalled photos still stored (their lines are frame metadata; an expired photo's
         * line comes from its caption memory). Memories: the retrieved memories, the photo cite ids and the history dependencies ([fenceIds]).
         */
        val sources: com.rokid.context.model.PayloadSources get() = com.rokid.context.model.PayloadSources(
            frameIds = photos.filter { it.stored }.map { it.frameId }.toSet(), memoryIds = ids + fenceIds,
        )
    }

    /** The question's time window, or null. A resolver failure is no window (never fails the ask); nothing about the question is logged. */
    private suspend fun windowOf(q: String, nowMs: Long): TimeWindow? =
        try { windows.resolve(q, nowMs, zone()) } catch (c: CancellationException) { throw c } catch (e: Exception) {
            PcLog.i(TAG, "time window unavailable (${e.javaClass.simpleName})"); null
        }

    /** The shared retrieval core (v0.9.8): memories and photos with every gate; the prompt vars of the ask are built here. */
    internal val rag = RagCore(memory, retriever, settings, zone, photos, photosAsOf, photosWindowed = photosWindowed, photosAllowed = photosAllowed, imageEndpointId = imageEndpointId, photoImages = photoImages)

    /** [plan]: the sources and params of the Query flow; the built-in plan is today's Ask (the memory and photo stores, the user's settings). */
    suspend fun retrieve(question: String, nowMs: Long, plan: FlowPlan = FlowPlan.BUILT_IN_QUERY): Retrieved {
        val q = AskText.collapse(question).take(MAX_QUESTION)
        val c = rag.retrieve(RagQuery(q), plan.askSources(windowOf(q, nowMs)), plan.ragParams(), nowMs)
        return Retrieved(
            PromptVars(
                // The question is user (later ASR) text: a data block folds look-alike markers as for memories.
                question = PromptComposer.dataBlock("question", listOf(q)),
                retrievedMemories = PromptComposer.dataBlock("memories", listOfNotNull(c.windowNote) + c.memoryLines + c.photoLines + c.recentLines + c.history),
                contextSources = PromptComposer.dataBlock("sources", sources.lines(q, nowMs)),
            ),
            c.memories + c.recent, c.photos, c.images, c.imageEndpointId, c.historyIds, semanticOff = c.semanticOff,
        )
    }

    /**
     * [retrieved] is what [retrieve] returns for the same arguments; [hits] are the scored candidates before the prompt bound (empty on [fallback]);
     * [lines] are the memory lines of the prompt, in order. [fallback] = no hits, the newest trusted memories stand in.
     */
    data class ScoredRetrieved(
        val retrieved: Retrieved, val hits: List<ScoredMemory>, val mode: RetrievalMode, val semanticUnavailable: Boolean, val fallback: Boolean,
        val lines: List<String> = emptyList(),
        val semanticOff: com.rokid.context.memory.retrieval.SemanticOff? = null,
    )

    /**
     * As [retrieve], but keeps the scores. [notAfterMs] drops memories with createdAt > it from the hits AND the fallback pool, BEFORE the retriever's
     * limits (S34). Used by the Ask debug view only; [retrieve] and [answer] are unchanged. It sends no photos itself: the debug flow takes its photos
     * through [com.rokid.context.canvas.AskDebugPhotos] ([askDebugPhotos], the live Ask's gate; Refuse until bound).
     */
    suspend fun retrieveScored(
        question: String, nowMs: Long, notAfterMs: Long? = null,
        /** The retrieve-debug route's knobs (mode, topK); the default is the user's setting, as for a live Ask. */
        params: RagParams = RagParams(),
        /** False: the other context sources (calendar and the like) are not read; the retrieve-debug route wants the memory store only. */
        withSources: Boolean = true,
    ): ScoredRetrieved {
        val q = AskText.collapse(question).take(MAX_QUESTION)
        val c = rag.retrieve(RagQuery(q), listOf(RagSource.MemoryStore(notAfterMs, windowOf(q, nowMs))), params, nowMs)
        val vars = PromptVars(
            question = PromptComposer.dataBlock("question", listOf(q)),
            retrievedMemories = PromptComposer.dataBlock("memories", listOfNotNull(c.windowNote) + c.memoryLines),
            contextSources = PromptComposer.dataBlock("sources", if (withSources) sources.lines(q, nowMs) else emptyList()),
        )
        return ScoredRetrieved(Retrieved(vars, c.memories), c.hits, c.mode!!, c.semanticUnavailable, c.fallback, c.memoryLines, c.semanticOff)
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
        val d = rag.debugRecall(q, nowMs) { try { imageEndpointId() } catch (c: CancellationException) { throw c } catch (_: Exception) { null } } ?: return none
        val endpoint = d.endpoint
        val recalled = d.recalled
        val withImage = d.withImage
        return com.rokid.context.canvas.AskPhotoRecall(
            d.lines, recalled.map { it.second }.toSet(), recalled.map { it.first.frameId }.toSet(), recalled.mapNotNull { it.first.captionMemoryId }.toSet(),
            images = { withImage.mapNotNull { (hit, _) -> photoImages(listOf(hit.frameId))[hit.frameId]?.copy(ref = hit.frameId) } },
            imageEndpointId = endpoint.takeIf { withImage.isNotEmpty() },
            imageCount = withImage.size,
            liveness = com.rokid.context.work.frames.FrameLiveness.forGraph(memory),
            storedFrameIds = recalled.filter { it.first.photoStored }.map { it.first.frameId }.toSet(),
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
        PcLog.i(TAG, "answer failed: kind=${e.kind} retryable=${e.retryable}") // never the message: it can carry provider text
        AskOutcome.Failed(if (e.kind == FailureKind.INVALID_OUTPUT) AskActions.FAILED else AskActions.AI_UNAVAILABLE)
    } catch (e: Exception) {
        PcLog.i(TAG, "answer failed (${e.javaClass.simpleName})")
        AskOutcome.Failed(AskActions.FAILED)
    }

    /** Queue jobs: failures propagate so the queue's retry policy applies (RetryClassifier). */
    /** [askId]: a queue job passes one derived from its id, so a re-run after a cancellation adds no second Ask row. */
    /**
     * [generation]: read before retrieval. If Delete all runs while the model answers, the ask (built from memories that
     * no longer exist) is not saved and the call fails as "no memories" (review I1).
     */
    /** Test seam: runs after the model answered (its upload slot released) and before the ask commits through its lease (a test runs a Forget there). Null in production. */
    @Volatile internal var testBeforeCommit: (suspend () -> Unit)? = null

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
            PcLog.i(TAG, "backlog extraction before ask failed (${e.javaClass.simpleName})")
        }
        val lease = com.rokid.context.orchestration.DataOpGate.shared.lease(com.rokid.context.memory.forget.lifecycle.Leases.Kind.ASK, emptyList(), generation)
        try {
            val r = retrieveWithFlow(q, nowMs)
            lease.extend(com.rokid.context.memory.forget.lifecycle.Leases.refsOf(r.ids + r.fenceIds)) // what this answer reads: a Forget that reaches it revokes the lease
            // An action command ("note that…") needs no memory: only other asks stop at "nothing yet" (user notes).
            if (r.memories.isEmpty() && r.photos.isEmpty() && !ActionTriggers.matches(q)) {
                runCatching(onNoMatch).onFailure { PcLog.w(TAG, "no-match reprocess not queued (${it.javaClass.simpleName})") }
                throw NoMemoriesException()
            }
            // Codex r10: recalled photos carry their refs; the router re-checks them live inside the call's permit (a photo forgotten meanwhile fails the ask
            // unsent, PhotoForgottenException; it is never re-asked as text).
            val out = runner.run(RoleCall(PromptKey.ANSWER, r.vars, images = r.images, imagesOnlyOnEndpoint = r.imageEndpointId, inputIds = r.ids, callClass = com.rokid.context.model.CallClass.USER,
                // codex r12: everything stored the prompt carries (memories, photo lines, history dependencies) is re-checked live by the router inside the permit
                sources = r.sources, liveness = com.rokid.context.work.frames.FrameLiveness.forGraph(memory, generation))).output as AnswerOutput
            val text = AskText.collapse(out.answer).take(MAX_ANSWER)
            if (text.isEmpty()) throw ModelCallException("ANSWER reply was empty", retryable = true, kind = FailureKind.INVALID_OUTPUT)
            val id = askId ?: "ask_${UUID.randomUUID()}"
            var redacted = false
            testBeforeCommit?.invoke()
            lease.extend(com.rokid.context.memory.forget.lifecycle.Leases.refsOf(out.sources))
            // M3: the ask commits THROUGH its read lease: revoked by a Forget (or Delete all) while the model answered, nothing is written. checkLive = false keeps the redacted-row rule below as it was.
            val saved = (lease.commit(memory.db, checkLive = false) {
                // Phase 7 Forget fence (Stream F fix round 1 I1): a memory in the prompt or cited was forgotten while the
                // model answered, so the reply may hold forgotten text: store it redacted, with no sources and no proposal. One gate: a wipe writes nothing, a forgotten source redacts.
                val verdict = com.rokid.context.provenance.ProvenanceGate.forGraph(memory).check(r.ids + r.fenceIds + out.sources, generation)
                if (verdict == com.rokid.context.provenance.ProvenanceGate.Verdict.Denied(com.rokid.context.provenance.ProvenanceGate.Reason.WIPED)) return@commit false
                redacted = verdict != com.rokid.context.provenance.ProvenanceGate.Verdict.Live
                memory.asks.add(
                    if (redacted) AskEntity(id, nowMs, q, com.rokid.context.memory.forget.ForgetService.REDACTED, "[]", null, source, true)
                    else AskEntity(id, nowMs, q, text, AskSources.encodeWithDeps(out.sources, r.fenceIds + r.ids) /* codex r15: every input, cited or not, is a dependency */, null, source, false),
                )
                true
            } as? com.rokid.context.memory.forget.lifecycle.Leases.Commit.Committed)?.value == true
            if (!saved) throw NoMemoriesException()
            if (redacted) return AskOutcome.Answered(id)
            val photo = firstCitedPhoto(r, out.sources)
            // Phase 7 (R-62): only after the ask row commits, so a proposal always has its origin row.
            // The ask is saved: a failing intake must not fail it (nor make a queue job retry it). Cancellation still propagates.
            // An eval ask (tools/eval_pca.py) never takes a proposal in: no HUD confirmation, notification or action may come from it.
            val proposalId = out.proposedAction?.takeIf { source != AskActions.EVAL_SOURCE }?.let { action ->
                try {
                    proposals.onProposed(ProposalOrigin.ASK, id, action, nowMs)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    PcLog.w(TAG, "proposal intake failed (${t.javaClass.simpleName})")
                    null
                }
            }
            return AskOutcome.Answered(id, out.proposedAction, proposalId, photo?.first, photo?.second, route = routeOf(r.target, source, id, nowMs), notice = AskActions.semanticNotice(r.semanticOff))
        } finally {
            lease.close()
        }
    }

    /**
     * The delivery of a published flow's answer. The voice surfaces (the pipeline's HUD overlay) and the Home chat (the typed-answer mirror to the HUD, [com.rokid.context.core.model.DeliveryRoute.mirrorsChatToHud]) have a glasses path: a queue or other ask has none, so a glasses target
     * lands on the phone with `no_glasses_path`. A fallback is recorded as a trace row (a fixed reason token, no text): never silent. `caller` (the built-in flow) returns null.
     */
    private suspend fun routeOf(target: String, source: String, askId: String, nowMs: Long): com.rokid.context.core.model.DeliveryRoute? {
        val caps = DeliveryEnvironment.caps()
        val hasGlassesPath = source == "glasses_voice" || source == "phone_voice" || source == AskActions.TYPED_SOURCE // the Home chat reaches the HUD through the phone's typed-answer mirror
        val reach = if (hasGlassesPath) DeliveryEnvironment.reach() else com.rokid.context.core.model.GlassesReach.NO_PATH
        val route = com.rokid.context.core.model.DeliveryRoute.resolve(target, source == "glasses_voice", reach, caps)
        if (target == "caller" && route.fallback == null) return null
        route.fallback?.let { memory.traces.upsert(com.rokid.context.memory.db.SummaryTraceEntity("ask_deliver_$askId", "ASK_DELIVERY_FALLBACK", nowMs, it)) }
        return route
    }

    /** The published Query pipeline's retrieval; any problem with it (lookup, compile, retrieval) runs the built-in flow instead. Cancellation propagates. */
    private suspend fun retrieveWithFlow(q: String, nowMs: Long): Retrieved {
        val plan = try { flows.plan(FlowPlan.Kind.QUERY) } catch (c: CancellationException) { throw c } catch (e: Exception) {
            PcLog.w(TAG, "query pipeline lookup failed (${e.javaClass.simpleName}); built-in flow"); null
        }
        if (plan == null || plan == FlowPlan.BUILT_IN_QUERY) return retrieve(q, nowMs)
        // the delivery target belongs to the plan, not to its retrieval: a retrieval that fell back still answers where the flow says
        return try { retrieve(q, nowMs, plan).copy(target = plan.target) } catch (c: CancellationException) { throw c } catch (e: Exception) {
            PcLog.w(TAG, "query pipeline failed (${e.javaClass.simpleName}); built-in flow"); retrieve(q, nowMs).copy(target = plan.target)
        }
    }

    /** The first cited source that is a photo still in storage: (frame id, capture time). An expired photo gives no HUD label (S9). */
    internal suspend fun firstCitedPhoto(r: Retrieved, cited: List<String>): Pair<String, Long>? {
        val cites = r.photos.associateBy { it.citeId }
        val memories = r.memories.associateBy { it.id }
        // The fence ran at the commit; a Forget can land between it and here, so the moment is checked again (only a time could leak, but none does).
        suspend fun live(frameId: String, memoryId: String? = null) =
            com.rokid.context.provenance.ProvenanceGate.forGraph(memory).allLive(setOfNotNull(PHOTO_CITE_PREFIX + frameId, memoryId))
        for (id in cited) {
            val cite = cites[id]
            if (cite != null) { if (cite.stored && cite.ts != null && live(cite.frameId)) return cite.frameId to cite.ts; continue }
            val frameId = memories[id]?.frameId ?: continue
            memory.db.frameDao().byIds(listOf(frameId)).firstOrNull()?.takeIf { live(it.id, id) }?.let { return it.id to it.ts }
        }
        return null
    }

    companion object {
        private const val TAG = "AnswerService"
        private const val MAX_QUESTION = 500
        private const val MAX_ANSWER = 2_000
        private const val ASK_EXTRACT_TIMEOUT_MS = 30_000L

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
                photosWindowed = RagCore.photosWindowedFrom(context),
                flows = com.rokid.context.canvas.DaoFlowPlans(MemoryGraph.get(context).db.canvasDao()),
                photosAllowed = { photosAllowedNow(context) },
                imageEndpointId = { answerImageEndpoint(context) },
                // The scan has its own budget (below the outer timeout), so partial hits survive; the timeout is only the backstop.
                photos = { q, _ ->
                    kotlinx.coroutines.withTimeoutOrNull(AskPhotoPolicy.ASK_TIMEOUT_MS) {
                        PhotoRecall.forApp(context).search(q, imageK = AskPhotoPolicy.MAX_PHOTOS, captionK = 0, budgetMs = AskPhotoPolicy.SCAN_BUDGET_MS).image
                    }.orEmpty()
                },
                photosAsOf = { q, notAfter ->
                    kotlinx.coroutines.withTimeoutOrNull(AskPhotoPolicy.ASK_TIMEOUT_MS) {
                        PhotoRecall.forApp(context).search(q, imageK = AskPhotoPolicy.MAX_PHOTOS, captionK = 0, budgetMs = AskPhotoPolicy.SCAN_BUDGET_MS, notAfterMs = notAfter).image
                    }.orEmpty()
                },
                photoImages = { ids -> FramePhotos.load(context, ids).photos.associate { it.frameId to it.image } },
                beforeRetrieve = { if (extractFirst) kotlinx.coroutines.withTimeoutOrNull(ASK_EXTRACT_TIMEOUT_MS) { com.rokid.context.work.frames.KeyframeGraph.get(context).pipeline.extractForAsk() } },
                onNoMatch = { if (com.rokid.context.data.runtime.AppConfigStore.isPipelineEnabled(context)) com.rokid.context.work.OrchestrationScheduler(context).enqueueFrameIngestIfIdle() },
            )
        }
    }
}
