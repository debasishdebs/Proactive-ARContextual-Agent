package com.rokid.context.canvas

import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.contract.AnswerOutput
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.AskSources
import com.rokid.context.orchestration.AskText
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.prompt.PersonaSettings
import com.rokid.context.prompt.NowText
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptVars
import com.rokid.context.prompt.PromptRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** A view of a redacted ask: its text was removed by Forget and must not be rebuilt. A type of its own, so no caller matches on a message. */
class AskRedacted : IllegalStateException("redacted")

/**
 * What the photo seam hands the Ask debug flow once its gate has said yes: the photo [lines] of the prompt (same shape as a live Ask's), the ids the
 * answer may cite, the frames and caption memories those lines stand for (rechecked after a replay, S35), and a lazy [images] loader that only a
 * replay calls.
 */
data class AskPhotoRecall(
    val lines: List<String>, val citeIds: Set<String>, val frameIds: Set<String>, val memoryIds: Set<String> = emptySet(),
    val images: suspend () -> List<ModelImageInput> = { emptyList() },
    /** The endpoint the gate checked: a replay sends the images only there ([RoleCall.imagesOnlyOnEndpoint]); null with images = they are dropped (fail closed). */
    val imageEndpointId: String? = null,
    /** How many images the numbered `image=<n>` lines promise. A replay whose loaded images differ in number is refused (`attachments_changed`): the numbering would point at the wrong photo. Null = unchecked. */
    val imageCount: Int? = null,
    /** Codex r10: the recalled photos' liveness; a replay attaches it so the router re-checks them before upload (null with images = refused). */
    val liveness: com.rokid.context.model.PhotoLiveness? = null,
    /** Codex r11: the recalled photos still stored (their lines are frame metadata, re-checked before a replay is sent); an expired one is not here. */
    val storedFrameIds: Set<String> = emptySet(),
)

/**
 * The seam to the Ask photo gate of wave 3. A re-run of the user's Ask against the canvas may send photos to a model, so it passes exactly the
 * gate a live Ask passes: the user's "Send matching photos with questions" switch (default OFF), the pipeline not paused, not inside quiet hours,
 * and an ANSWER endpoint that can see images. The production binding is `AnswerService.askDebugPhotos()` (bound in `CanvasGraph` through
 * `AskPhotoBinding`); the default [Refuse] never allows a photo. [allowed] is asked before every [recall]; a throw from either means "no photos"
 * (fail closed). Photos captured after the ask are not offered.
 */
interface AskDebugPhotos {
    suspend fun allowed(): Boolean
    /** Called only after [allowed] returned true. [nowMs] is the ask's own time. */
    suspend fun recall(question: String, nowMs: Long): AskPhotoRecall

    companion object {
        /** The default: no photo ever, no scan, no image. */
        val Refuse: AskDebugPhotos = object : AskDebugPhotos {
            override suspend fun allowed() = false
            override suspend fun recall(question: String, nowMs: Long) = AskPhotoRecall(emptyList(), emptySet(), emptySet())
        }
    }
}

/**
 * The Ask debug backend (spec section 6, S13, S34, S35). A view re-runs retrieval as of the ask's own time and shows the scores, the cited ids, which hits
 * made the prompt, and the composed prompt. A replay re-runs the ANSWER call with a template of the caller's choice as `CallClass.EXPERIMENT`, saves
 * nothing (no ask, no proposal) and holds no state. The re-run goes through [photos], so photos are sent only when the live Ask gate would allow them.
 * Logs carry class names only, never prompt or answer text.
 */
class AskDebug(
    private val dao: CanvasDao,
    private val answers: AnswerService,
    private val runner: RoleRunner,
    private val prompts: PromptRepository,
    private val persona: suspend () -> PersonaSettings,
    /** TestBed.compatibility: the problems of a draft for the endpoints bound to the key's role (empty = compatible). */
    private val compatibility: suspend (PromptKey, PromptDraft) -> List<String>,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val photos: AskDebugPhotos = AskDebugPhotos.Refuse,
    private val generation: () -> Long = MemoryWipe.generation::get,
    /** Codex r12: the replay's liveness for the wipe generation it captured (the router re-checks every memory and photo of the prompt with it). */
    private val liveness: (Long) -> com.rokid.context.model.PhotoLiveness? = { null },
) {
    data class Hit(
        val memoryId: String, val text: String, val frameId: String?, val lexical: Double?, val semantic: Double?, val fused: Double, val score: Double,
        val cited: Boolean, val inPrompt: Boolean,
    )

    data class View(
        val askId: String, val ts: Long, val question: String, val answer: String, val cited: List<String>, val rating: Int?, val hits: List<Hit>,
        val mode: String, val fallback: Boolean, val composedPrompt: String, val activeTemplate: String, val activeVersion: Int,
    )

    sealed interface Replay {
        data class Ok(val answer: String, val sources: List<String>, val latencyMs: Long, val promptTokens: Int?, val completionTokens: Int?, val model: String) : Replay
        /** [error] is a fixed word: `incompatible`, `redacted`, `not_found`, `forgotten`, or a failure kind name / exception class name. */
        data class Failed(val error: String) : Replay
    }

    private class Loaded(val sr: AnswerService.ScoredRetrieved, val photos: AskPhotoRecall?, val vars: PromptVars)

    suspend fun list(limit: Int = 50): List<AskEntity> = dao.recentAsks(limit)

    /** Null: unknown ask. Throws [AskRedacted] for a redacted ask: its text was removed by Forget and must not be rebuilt. */
    suspend fun view(askId: String): View? {
        val gen = generation() // before anything is loaded: a Forget / Delete all that finishes while the view is built must not be served
        val ask = dao.ask(askId) ?: return null
        if (ask.redacted) throw AskRedacted()
        val l = load(ask)
        val cited = AskSources.parse(ask.sourcesJson)
        val kept = l.sr.retrieved.ids
        val hits = if (l.sr.fallback) {
            l.sr.retrieved.memories.map { Hit(it.id, it.text, it.frameId, null, null, 0.0, 0.0, it.id in cited, true) }
        } else {
            l.sr.hits.map { Hit(it.memory.id, it.memory.text, it.memory.frameId, it.lexical, it.semantic, it.fused, it.score, it.memory.id in cited, it.memory.id in kept) }
        }
        val active = prompts.active(PromptKey.ANSWER)
        val view = View(
            ask.id, ask.ts, ask.question, ask.answer, cited, ask.rating, hits, l.sr.mode.name, l.sr.fallback,
            compose(active.template, l.vars, ask.ts), active.template, active.version,
        )
        // Rechecked right before returning: generation, the ask itself, and EVERY hit shown (also those outside the prompt) and every photo line.
        if (generation() != gen) throw AskRedacted()
        val now = dao.ask(askId) ?: return null
        if (now.redacted) throw AskRedacted()
        val memoryIds = hits.map { it.memoryId }.toSet() + (l.photos?.memoryIds ?: emptySet())
        if (dao.liveMemoryCount(memoryIds) != memoryIds.size) throw AskRedacted()
        val frameIds = (l.photos?.frameIds ?: emptySet()) + hits.mapNotNull { it.frameId }
        if (frameIds.isNotEmpty() && dao.tombstonedAmong(frameIds) != 0) throw AskRedacted()
        return view
    }

    /**
     * Re-runs ANSWER for [askId] with [template] (the active schema) as the EXPERIMENT class. The retrieval is the view's (as of the ask). Before the call it
     * captures the wipe generation; after the reply it rechecks that the ask is not redacted, the generation is unchanged and every memory and photo it
     * used is still live, else `Failed("forgotten")` with no text (S35).
     */
    suspend fun replay(askId: String, template: String): Replay {
        val ask = dao.ask(askId) ?: return Replay.Failed("not_found")
        if (ask.redacted) return Replay.Failed("redacted")
        val draft = PromptDraft(template, runner.activeSchema(PromptKey.ANSWER))
        if (compatibility(PromptKey.ANSWER, draft).isNotEmpty()) return Replay.Failed("incompatible")
        val gen = generation()
        val l = load(ask)
        val memoryIds = l.sr.retrieved.ids + (l.photos?.memoryIds ?: emptySet())
        val frameIds = l.photos?.frameIds ?: emptySet()
        val endpoint = l.photos?.imageEndpointId
        val images = if (endpoint == null) emptyList() else l.photos?.let { p -> try { p.images() } catch (c: CancellationException) { throw c } catch (e: Exception) { return Replay.Failed(e.javaClass.simpleName) } } ?: emptyList()
        if (l.photos?.imageCount != null && images.size != l.photos.imageCount) return Replay.Failed("attachments_changed")
        val result = try {
            runner.run(RoleCall(PromptKey.ANSWER, l.vars, images = images, imagesOnlyOnEndpoint = if (images.isEmpty()) null else endpoint, inputIds = memoryIds + (l.photos?.citeIds ?: emptySet()), callClass = CallClass.EXPERIMENT, override = draft, liveness = liveness(gen) ?: l.photos?.liveness,
                // codex r12: the memories, photo cites, history dependencies and stored photo lines of the prompt
                sources = l.sr.retrieved.sources + com.rokid.context.model.PayloadSources(l.photos?.storedFrameIds.orEmpty(), memoryIds + (l.photos?.citeIds ?: emptySet()))))
        } catch (c: CancellationException) {
            throw c
        } catch (e: ModelCallException) {
            PcLog.i(TAG, "replay failed: kind=${e.kind}")
            return Replay.Failed(e.kind.name)
        } catch (e: Exception) {
            PcLog.i(TAG, "replay failed (${e.javaClass.simpleName})")
            return Replay.Failed(e.javaClass.simpleName)
        }
        if (!stillValid(askId, gen, memoryIds, frameIds)) return Replay.Failed("forgotten")
        val out = result.output as AnswerOutput
        val r = result.reply
        return Replay.Ok(AskText.collapse(out.answer).take(MAX_ANSWER), out.sources, r.latencyMs, r.promptTokens, r.completionTokens, r.model)
    }

    private suspend fun load(ask: AskEntity): Loaded {
        val sr = answers.retrieveScored(ask.question, ask.ts, ask.ts)
        val p = photoRecall(ask)
        val vars = if (p == null || p.lines.isEmpty()) sr.retrieved.vars
        else sr.retrieved.vars.copy(retrievedMemories = PromptComposer.dataBlock("memories", AskText.bounded(sr.lines + p.lines)))
        return Loaded(sr, p, vars)
    }

    /** The gate first, then the photo search; any failure of either means no photos (fail closed). */
    private suspend fun photoRecall(ask: AskEntity): AskPhotoRecall? {
        val allowed = try { photos.allowed() } catch (c: CancellationException) { throw c } catch (e: Exception) { PcLog.i(TAG, "photo gate failed (${e.javaClass.simpleName})"); false }
        if (!allowed) return null
        return try { photos.recall(ask.question, ask.ts) } catch (c: CancellationException) { throw c } catch (e: Exception) { PcLog.i(TAG, "photo recall failed (${e.javaClass.simpleName})"); null }
    }

    private suspend fun compose(template: String, vars: PromptVars, ts: Long): String {
        val system = prompts.active(PromptKey.SYSTEM).template
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), zone())
        return PromptComposer.compose(system, template, vars.toMap(persona().persona.value, NowText.of(at)))
    }

    private suspend fun stillValid(askId: String, gen: Long, memoryIds: Set<String>, frameIds: Set<String>): Boolean {
        if (generation() != gen) return false
        val ask = dao.ask(askId)
        if (ask == null || ask.redacted) return false
        if (dao.liveMemoryCount(memoryIds) != memoryIds.size) return false
        return frameIds.isEmpty() || dao.tombstonedAmong(frameIds) == 0
    }

    private companion object {
        const val TAG = "AskDebug"
        const val MAX_ANSWER = 2_000
    }
}
