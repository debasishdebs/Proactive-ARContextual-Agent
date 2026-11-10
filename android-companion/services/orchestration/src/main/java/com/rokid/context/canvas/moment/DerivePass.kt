package com.rokid.context.canvas.moment

import android.content.Context
import androidx.room.withTransaction
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Role
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.ObjectDao.WriteOutcome
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.moment.CaptionInput
import com.rokid.context.memory.moment.DeriveRegistry
import com.rokid.context.memory.moment.DeriveRequest
import com.rokid.context.memory.moment.DeriveRunner
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.DerivedWrite
import com.rokid.context.memory.moment.MomentObject
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.moment.ObjectKind
import com.rokid.context.memory.moment.SummarizeInput
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelRequest
import com.rokid.context.orchestration.MemoryWipe
import kotlin.coroutines.cancellation.CancellationException

/** The one place the derive step asks a model for text: a prompt in, a reply out (null = no answer). Production adapts the app's SUMMARIZE role; tests pass a fake. */
/**
 * [sources] (codex r12/r13): the memories and photos the prompt's notes were read from, and [generation] the wipe generation read in the SAME snapshot;
 * the router re-checks both before the call leaves (a Forget or Delete all after the snapshot drops it unsent).
 */
fun interface DeriveText { suspend fun complete(prompt: String, sources: com.rokid.context.model.PayloadSources, generation: Long): String? }

/** Production [DeriveText]: the app's existing router and the user's SUMMARIZE binding (no new client). Background priority; a short, cool, capped request. */
class RouterDeriveText(private val model: com.rokid.context.model.LanguageModel, private val liveness: (Long) -> com.rokid.context.model.PhotoLiveness) : DeriveText {
    override suspend fun complete(prompt: String, sources: com.rokid.context.model.PayloadSources, generation: Long): String? =
        model.complete(Role.SUMMARIZE, CallClass.BACKFILL) { ModelRequest(prompt, temperature = 0.2, maxTokens = 160, sources = sources, liveness = liveness(generation)) }.text
}

/**
 * The derive step's own stored cadence (`canvas_meta`), separate from the Scene build cadence: a kill switch that is OFF until the user turns it on (the step spends model calls) and
 * "times a day", default [DEFAULT_PER_DAY], clamped to `1..`[MAX_PER_DAY]. There is no settings screen yet.
 */
class DeriveCadence(db: ContextDatabase) {
    private val meta = db.canvasSlice3Dao()
    suspend fun enabled(): Boolean = meta.meta(KEY_ENABLED) == "1"
    suspend fun setEnabled(on: Boolean) = meta.putMeta(KEY_ENABLED, if (on) "1" else "0")
    suspend fun perDay(): Int = (meta.meta(KEY_PER_DAY)?.toIntOrNull() ?: DEFAULT_PER_DAY).coerceIn(1, MAX_PER_DAY)
    suspend fun setPerDay(n: Int) = meta.putMeta(KEY_PER_DAY, n.coerceIn(1, MAX_PER_DAY).toString())
    suspend fun isDue(nowMs: Long): Boolean {
        val last = meta.meta(KEY_LAST)?.toLongOrNull() ?: return true
        return nowMs < last || nowMs - last >= SceneCadence.DAY_MS / perDay() - SceneCadence.TOLERANCE_MS
    }
    suspend fun markRan(nowMs: Long) = meta.putMeta(KEY_LAST, nowMs.toString())

    companion object {
        const val KEY_ENABLED = "derive_enabled"
        const val KEY_PER_DAY = "derive_per_day"
        const val KEY_LAST = "derive_last_run_ms"
        const val DEFAULT_PER_DAY = 4
        const val MAX_PER_DAY = 96
    }
}

/** The two permissions a model call needs, asked before EVERY call: the stored switch is still on and the app admits one more background call. */
class DeriveGate(private val enabled: suspend () -> Boolean, private val admit: suspend () -> Boolean) {
    /** Set when a call was refused: the pass stops at once. */
    @Volatile var refused = false
    suspend fun allow(): Boolean = (enabled() && admit()).also { if (!it) refused = true }
    /** After the call: the switch must still be on for the answer to be kept. */
    suspend fun stillOn(): Boolean = enabled().also { if (!it) refused = true }
}

/**
 * What the model may read of an object: ONLY text a reader can see. A Moment: its visible memories (review state OK) and its marks' names. A Scene: for each member Moment its fresh
 * (never hidden or stale) SUMMARY, else that Moment's own visible text. Never photo bytes, paths, ids or any hidden field. Bounded in count and length.
 */
class DeriveInputs(private val db: ContextDatabase) {
    private val audit = ObjectAudit(db.objectDao())

    /** Test seam: runs after the inputs of one object were read and before the model call. Always null in production. */
    @Volatile internal var testAfterRead: (suspend () -> Unit)? = null

    /** Codex r13: what one derive call reads, captured together: the lines, the stored content they come from, and the wipe generation. */
    data class Snapshot(val lines: List<String>, val sources: com.rokid.context.model.PayloadSources, val generation: Long)

    /**
     * The lines, their provenance ([sources]) and the wipe generation of the input revision [seq], read in ONE transaction (a Forget or review change
     * cannot commit between the reads); null when the revision is not [seq] any more. The call sends exactly these: provenance is never re-read later.
     */
    suspend fun snapshot(obj: MomentObject, seq: Int): Snapshot? = db.withTransaction {
        if (audit.inputSeq(obj) != seq) null else Snapshot(lines(obj), sources(obj), MemoryWipe.generation.get())
    }

    /** Test seam: runs after the last pre-call check and before the model call. Always null in production. */
    @Volatile internal var testBeforeSend: (suspend () -> Unit)? = null

    suspend fun lines(obj: MomentObject): List<String> = when (obj.kind) {
        ObjectKind.MOMENT -> momentLines(obj.id)
        ObjectKind.SCENE -> db.sceneDao().parts(obj.id).filter { it.kind == ScenePartKind.MOMENT }.flatMap { p ->
            val own = audit.readDerived(com.rokid.context.memory.moment.ObjectRef(ObjectKind.MOMENT, p.refId), DerivedField.SUMMARY)?.value?.takeIf { it.isNotBlank() }
            if (own != null) listOf(own) else momentLines(p.refId)
        }
        else -> emptyList()
    }.map { it.replace(WS, " ").trim().take(MAX_LINE) }.filter { it.isNotEmpty() }.take(MAX_LINES)

    /** Codex r12: the memories and photos [lines] reads for [obj] (a Scene: its member Moments'), declared on the model call. */
    private suspend fun sources(obj: MomentObject): com.rokid.context.model.PayloadSources = when (obj.kind) {
        ObjectKind.MOMENT -> momentSources(obj.id)
        ObjectKind.SCENE -> db.sceneDao().parts(obj.id).filter { it.kind == ScenePartKind.MOMENT }
            .fold(com.rokid.context.model.PayloadSources.NONE) { acc, p -> acc + momentSources(p.refId) }
        else -> com.rokid.context.model.PayloadSources.NONE
    }

    private suspend fun momentSources(id: String): com.rokid.context.model.PayloadSources {
        val parts = db.momentDao().parts(id)
        val memories = parts.filter { it.kind == MomentPartKind.MEMORY || it.kind == MomentPartKind.CITE }.map { it.refId }
        val frames = parts.filter { it.kind == MomentPartKind.FRAME }.map { it.refId }
        // codex r15: the marks whose intent words [lines] read are sources too (a range Forget deletes the label without moving the wipe generation)
        val marks = parts.filter { it.kind == MomentPartKind.MARK }.map { it.refId }
        return com.rokid.context.model.PayloadSources.stored(frames, memories, marks)
    }

    private suspend fun momentLines(id: String): List<String> {
        val parts = db.momentDao().parts(id)
        val memories = db.memoryViewDao().byIds(parts.filter { it.kind == MomentPartKind.MEMORY }.map { it.refId }).filter { it.reviewState == ReviewState.OK }.sortedBy { it.id }.map { it.text }
        // only an INTENT word is words: a plain mark is named MOMENT, which says nothing, so a MARK Moment with no intent word and no memory has no visible text and gets no model call
        val marks = parts.filter { it.kind == MomentPartKind.MARK }.sortedBy { it.refId }.mapNotNull { db.canvasSlice3Dao().label(it.refId)?.takeIf { l -> l.kind == com.rokid.context.canvas.marks.MarkRecorder.KIND_INTENT }?.name }
        return memories + marks
    }

    companion object { const val MAX_LINES = 40; const val MAX_LINE = 300; private val WS = Regex("\\s+") }
}

/** The real SUMMARY and CAPTION ports for MOMENT and SCENE: a short fixed prompt over [DeriveInputs]; null (nothing written) when there is nothing to say or the model gave nothing. */
class TextDerivePorts(private val inputs: DeriveInputs, private val text: DeriveText, private val gate: DeriveGate) : SummarizeInput, CaptionInput {
    override suspend fun summarize(req: DeriveRequest): DerivedWrite? = derive(req, DerivedField.SUMMARY, "Write one short plain sentence saying what happened", MAX_SUMMARY)
    override suspend fun caption(req: DeriveRequest): DerivedWrite? = derive(req, DerivedField.CAPTION, "Write a caption of at most 8 words for", MAX_CAPTION)

    private suspend fun derive(req: DeriveRequest, field: String, task: String, max: Int): DerivedWrite? {
        val snap = inputs.snapshot(req.obj, req.inputSeq)?.takeIf { it.lines.isNotEmpty() } ?: return null // the inputs moved since the derive started: no call
        val lines = snap.lines
        inputs.testAfterRead?.invoke()
        if (!gate.allow()) return null // the switch and the app's admission are asked before EVERY call
        // immediately before the call: the same inputs at the same revision, and no Delete all since
        if (!req.fence.holds() || inputs.snapshot(req.obj, req.inputSeq) != snap) return null
        val what = if (req.obj.kind == ObjectKind.SCENE) "this scene" else "this moment"
        inputs.testBeforeSend?.invoke()
        // codex r13: the provenance and generation of THIS snapshot travel with the text; the router re-checks them inside the permit
        val reply = text.complete(prompt(task, what, lines), snap.sources, snap.generation) ?: return null
        if (!gate.stillOn()) return null // switched off while the model ran: nothing is written
        val clean = reply.replace(Regex("\\s+"), " ").trim().take(max)
        return if (clean.isEmpty()) null else DerivedWrite(field, value = clean, builtRev = req.inputSeq)
    }

    fun registerIn(r: DeriveRegistry) { listOf(ObjectKind.MOMENT, ObjectKind.SCENE).forEach { r.register(it, summarize = this, caption = this) } }

    companion object {
        const val MAX_SUMMARY = 280; const val MAX_CAPTION = 80
        fun prompt(task: String, what: String, lines: List<String>): String = "$task $what, using only the notes below. Reply with the text only.\nNotes:\n" + lines.joinToString("\n") { "- $it" }
    }
}

/**
 * One bounded derive pass, run by `MomentCadenceWorker`: when the stored switch is on, the stored cadence is due and the app admits a background model call, the newest Scenes and then
 * Moments whose SUMMARY or CAPTION is missing, hidden or built from an older input revision are derived through [DeriveRunner] (the GUARDED write: generation, input revision, parent
 * exists). At most [maxObjects] objects per run; an obsolete answer is rejected with nothing written; the first model failure ends the pass (the fields stay missing, the next due run
 * retries). Counts only in the log.
 */
class DerivePass(
    private val db: ContextDatabase, registry: DeriveRegistry, private val cadence: DeriveCadence, fence: () -> DaoFence,
    private val inputs: DeriveInputs, private val gate: DeriveGate, private val maxObjects: Int = MAX_OBJECTS,
) {
    data class Report(val written: Int, val rejected: Int, val failed: Int, val objects: Int)
    private val audit = ObjectAudit(db.objectDao())
    private val runner = DeriveRunner(audit, registry, fence)

    suspend fun runIfDue(nowMs: Long): Report? {
        if (!cadence.enabled() || !cadence.isDue(nowMs)) return null
        gate.refused = false
        var written = 0; var rejected = 0; var failed = 0; var objects = 0
        for (obj in candidates(nowMs)) {
            if (objects >= maxObjects || failed > 0 || gate.refused || !cadence.enabled()) break
            val seq = audit.inputSeq(obj)
            val need = listOf(DerivedField.SUMMARY, DerivedField.CAPTION).filter { f -> audit.readDerived(obj, f)?.takeIf { it.builtRev == seq } == null }
            if (need.isEmpty() || inputs.lines(obj).isEmpty()) continue
            objects++
            for (f in need) {
                if (gate.refused || !cadence.enabled()) break // the switch is re-read before every field call; a refused call stops the pass
                try {
                    when (runner.rebuildOutcome(obj, f)) { WriteOutcome.WRITTEN -> written++; null -> {}; else -> rejected++ }
                } catch (c: CancellationException) { throw c } catch (e: Exception) { failed++; break }
            }
        }
        if (gate.refused && written + rejected + failed == 0) return null // never admitted: it stays due
        cadence.markRan(nowMs)
        PcLog.i("derive", "objects=$objects written=$written rejected=$rejected failed=$failed")
        return Report(written, rejected, failed, objects)
    }

    /** Newest first, Scenes before Moments (the Scene text is what readers lack); a bounded look-back so a long history is never scanned. */
    private suspend fun candidates(nowMs: Long): List<MomentObject> {
        val hour = Math.floorDiv(nowMs, SceneConfig.HOUR_MS) * SceneConfig.HOUR_MS
        val scenes = db.sceneDao().scenesInHours(hour - SCENE_LOOKBACK_MS, hour).asReversed().take(SCAN)
        val moments = try { db.momentDao().momentsBetween(MomentId.ofMs(maxOf(0L, nowMs - MOMENT_LOOKBACK_MS)), MomentId.ofMs(nowMs)).asReversed().take(SCAN) } catch (_: IllegalArgumentException) { emptyList() }
        return scenes + moments
    }

    companion object {
        const val MAX_OBJECTS = 10; const val SCAN = 200
        const val SCENE_LOOKBACK_MS = 7 * 24 * SceneConfig.HOUR_MS; const val MOMENT_LOOKBACK_MS = 24 * SceneConfig.HOUR_MS
    }
}

/** The process-wide derive pass (production wiring): the user's SUMMARIZE model through the app router, admitted only when it is bound and the keyframe pipeline's background gates (battery, validated network, power) allow a call. */
object DeriveTriggers {
    @Volatile private var instance: DerivePass? = null

    init { MemoryGraph.onTestReset { resetForTests() } } // captures the graph's database (tests only)

    fun get(context: Context): DerivePass = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val db = MemoryGraph.get(app).db
            val ai = AiGraph.get(app); val epoch = DeletionEpochStore.of(app)
            val inputs = DeriveInputs(db)
            val cadence = DeriveCadence(db)
            val gate = DeriveGate(enabled = cadence::enabled, admit = { ai.bindings.get(Role.SUMMARIZE) != null && com.rokid.context.work.frames.KeyframeGraph.get(app).admitBackgroundCall() })
            val registry = DeriveRegistry().also { TextDerivePorts(inputs, RouterDeriveText(ai.router) { gen -> com.rokid.context.work.frames.FrameLiveness.forApp(app, gen) }, gate).registerIn(it) }
            DerivePass(db, registry, cadence, { DaoFence(MemoryWipe.generation.get(), epoch.read(), MemoryWipe.generation::get, epoch::read) }, inputs, gate)
        }.also { instance = it }
    }

    fun resetForTests() { synchronized(this) { instance = null } }
}
