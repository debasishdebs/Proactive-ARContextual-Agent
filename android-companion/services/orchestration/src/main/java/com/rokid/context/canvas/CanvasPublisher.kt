package com.rokid.context.canvas

import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.canvas.InterpretableShape
import com.rokid.context.ai.canvas.LiveShape
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PromptSource
import com.rokid.context.ai.canvas.plan.PlanShape
import com.rokid.context.ai.canvas.promptSource
import com.rokid.context.ai.canvas.PipelineHash
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasPublishEntity
import com.rokid.context.memory.db.CasOutcome
import com.rokid.context.prompt.CasEdit
import com.rokid.context.prompt.PromptDefaults
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptRepository
import com.rokid.context.core.log.PcLog
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Publishes a frozen, tested, live-shaped pipeline version by writing its edited prompts through [PromptRepository],
 * and rolls the newest publish back only while those prompts are still exactly what the publish wrote.
 * Publish and rollback are serialised by one [Mutex], so exactly one instance must serve the app. A prompt edit from
 * another writer (Settings) is not under that lock, so every prompt write is a multi-key compare-and-set
 * ([PromptRepository.saveAllIfUnchanged]): a changed prompt writes nothing, for any key.
 */
class CanvasPublisher(
    private val dao: CanvasDao,
    private val prompts: PromptRepository,
    private val compatibility: suspend (PromptKey, PromptDraft) -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transact: suspend (suspend () -> Unit) -> Unit,
) {
    companion object {
        /** The production transaction runner: both DAOs live in one Room database, and Room nests transactions. */
        fun inTransaction(db: RoomDatabase): suspend (suspend () -> Unit) -> Unit = { body -> db.withTransaction { body() } }

        const val KIND_PROMPTS = "prompts"
        const val KIND_INTERPRETER = "interpreter"
        /** v0.9.8 slice B: a Query or Nudge pipeline (`component` `query` or `nudge`). Not a prompts or interpreter publish: those stacks ignore it. */
        const val KIND_FLOW = "flow"
        /** Slice 3a: a capture-plan publish row (`component` = `capture_plan`, one per publish group). It is NOT a prompts or interpreter publish: those stacks ignore it. */
        const val KIND_CAPTURE_PLAN = "capture_plan"
        /** The `component` of an ASYNC interpreter row that belongs to a publish group. */
        const val COMPONENT_ASYNC = "interpreter"

        /**
         * The conditional update behind [autoRollback]: one statement (`WHERE rolledBackAt IS NULL`), so it is safe without the publisher's lock and the live
         * path (which has no publisher instance) can call it. Only an active interpreter row is touched.
         */
        suspend fun autoRollbackOn(dao: CanvasDao, clock: () -> Long, publishId: Long, reason: String): Boolean {
            if (dao.activePublishes().none { it.id == publishId && it.kind == KIND_INTERPRETER }) return false
            return dao.markRolledBackWithReason(publishId, clock(), reason) > 0
        }
    }

    sealed interface Outcome {
        data class Published(val publishId: Long, val keys: List<PromptKey>) : Outcome
        data class Blocked(val reasons: List<String>) : Outcome
    }

    private val lock = Mutex()
    private val TAG = "CanvasPublisher"
    private val PUBLISH_FAILED = "Publishing failed. Nothing was changed."
    private val ROLLBACK_FAILED = "Rolling back failed. Nothing was changed."
    private val ALREADY_ROLLED_BACK = "That publish was already rolled back."

    suspend fun publish(pipelineId: String, version: Int): Outcome = lock.withLock { publishLocked(pipelineId, version) }

    /**
     * Publishes a frozen version as an interpreter, in [mode] (spec section 5/6). Writes no prompts: the interpreter reads the frozen version's JSON.
     * Gates, in order: the shape ([InterpretableShape.problems]); the draft extract prompts' compatibility; every `ai.prompt` schema a JSON object;
     * every AI node before extraction has a successful run of this exact hash.
     */
    suspend fun publishInterpreter(pipelineId: String, version: Int, mode: LiveMode, groupId: String? = null): Outcome = lock.withLock {
        val v = dao.version(pipelineId, version) ?: return@withLock Outcome.Blocked(listOf("Version not found"))
        val full = PipelineJson.decode(v.json).getOrNull() ?: return@withLock Outcome.Blocked(listOf("Version is unreadable"))
        // S6: tier 3 (`py.fn`, any `tool.` node) never publishes. First, on the FULL graph, before the projection can drop or keep anything.
        if (PlanShape.tier3Nodes(full).isNotEmpty()) return@withLock Outcome.Blocked(listOf(PlanShape.S6_REFUSAL))
        // S72: a full three-domain graph publishes its ASYNC projection; the hash is the slice 2 hash of that projection, so a tested identity is kept.
        val p = PlanShape.asyncProjection(full)
        val hash = if (p === full) v.hash else PipelineHash.of(p)
        val shape = InterpretableShape.problems(p)
        if (shape.isNotEmpty()) return@withLock Outcome.Blocked(shape)
        val compat = p.nodes.filter { it.type == NodeTypes.VISION_EXTRACT }.flatMap { n ->
            val key = NodeTypes.byType(n.type)?.promptKey ?: return@flatMap emptyList()
            val d = n.promptSource() as? PromptSource.Draft ?: return@flatMap emptyList()
            compatibility(key, PromptDraft(d.template, d.schema)).map { "${key.label}: $it" }
        }
        if (compat.isNotEmpty()) return@withLock Outcome.Blocked(compat)
        val badSchema = p.nodes.filter { it.type == NodeTypes.PROMPT }.filter { n ->
            val s = (n.params["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            !s.isNullOrBlank() && runCatching { Json.parseToJsonElement(s) }.getOrNull() !is JsonObject
        }.map { "node ${it.id}: schema is not a JSON object" }
        if (badSchema.isNotEmpty()) return@withLock Outcome.Blocked(badSchema)
        val ai = InterpretableShape.preSaveNodes(p).filter { it.type == NodeTypes.PROMPT || it.type == NodeTypes.VISION_EXTRACT }
        val untested = ai.map { it.id }.filter { dao.testedNodeCount(hash, listOf(it)) == 0 }
        if (untested.isNotEmpty()) return@withLock Outcome.Blocked(listOf("Run this version first: ${untested.joinToString(", ")} have no successful run"))
        val snapshot = buildJsonObject { put("pipelineId", pipelineId); put("version", version); put("hash", hash) }.toString()
        try {
            val id = dao.insertPublish(CanvasPublishEntity(pipelineId = pipelineId, version = version, hash = hash, publishedAt = clock(), rolledBackAt = null,
                snapshotJson = snapshot, kind = KIND_INTERPRETER, mode = mode.name, groupId = groupId, component = groupId?.let { COMPONENT_ASYNC }))
            Outcome.Published(id, emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PcLog.w(TAG, "interpreter publish failed: ${e.javaClass.simpleName}")
            Outcome.Blocked(listOf(PUBLISH_FAILED))
        }
    }

    /**
     * Publishes a frozen Query or Nudge pipeline (v0.9.8 slice B): it must validate and compile ([FlowPlan.compile]; a slice C trigger or an unrouted delivery target is refused).
     * It writes no prompts (a flow always uses the live ANSWER or NUDGE prompt), so there is no untested text to gate on. One row, superseding the active row of the same component.
     */
    suspend fun publishFlow(pipelineId: String, version: Int): Outcome = lock.withLock {
        val v = dao.version(pipelineId, version) ?: return@withLock Outcome.Blocked(listOf("Version not found"))
        val p = PipelineJson.decode(v.json).getOrNull() ?: return@withLock Outcome.Blocked(listOf("Version is unreadable"))
        val plan = when (val c = FlowPlan.compile(p)) {
            is FlowPlan.Compiled.Refused -> return@withLock Outcome.Blocked(c.reasons)
            is FlowPlan.Compiled.Ok -> c.plan
        }
        val snapshot = buildJsonObject { put("pipelineId", pipelineId); put("version", version); put("hash", v.hash) }.toString()
        var result: Outcome = Outcome.Blocked(listOf(PUBLISH_FAILED))
        try {
            transact {
                val now = clock()
                dao.activePublishes().filter { it.kind == KIND_FLOW && it.component == plan.kind.component }.forEach { dao.markRolledBackWithReason(it.id, now, "superseded") }
                val id = dao.insertPublish(CanvasPublishEntity(pipelineId = pipelineId, version = version, hash = v.hash, publishedAt = now, rolledBackAt = null,
                    snapshotJson = snapshot, kind = KIND_FLOW, component = plan.kind.component))
                result = Outcome.Published(id, emptyList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PcLog.w(TAG, "flow publish failed: ${e.javaClass.simpleName}")
            return@withLock Outcome.Blocked(listOf(PUBLISH_FAILED))
        }
        result
    }

    /** Rolls an interpreter publish back with a fixed [reason] (auto-rollback). False when it is not an active interpreter publish (already rolled back). */
    suspend fun autoRollback(publishId: Long, reason: String): Boolean = lock.withLock { autoRollbackOn(dao, clock, publishId, reason) }

    private suspend fun publishLocked(pipelineId: String, version: Int): Outcome {
        val v = dao.version(pipelineId, version) ?: return Outcome.Blocked(listOf("Version not found"))
        val p = PipelineJson.decode(v.json).getOrNull() ?: return Outcome.Blocked(listOf("Version is unreadable"))
        if (PlanShape.tier3Nodes(p).isNotEmpty()) return Outcome.Blocked(listOf(PlanShape.S6_REFUSAL)) // S6, before the shape test
        if (!LiveShape.matches(p)) return Outcome.Blocked(listOf("Only pipelines shaped like Live extraction can go live in this version"))
        val nodes = LiveShape.draftPromptNodes(p)
        if (nodes.isEmpty()) return Outcome.Blocked(listOf("Nothing to publish: every prompt is Live"))
        val edits = nodes.mapNotNull { n ->
            val key = NodeTypes.byType(n.type)?.promptKey ?: return@mapNotNull null
            val d = n.promptSource() as? PromptSource.Draft ?: return@mapNotNull null
            Triple(n.id, key, PromptDraft(d.template, d.schema))
        }
        // snapshot first: every check below is then inside the compare-and-set window
        val before = edits.map { (_, key, _) -> prompts.active(key) }
        val compat = edits.flatMap { (_, key, draft) -> compatibility(key, draft).map { "${key.label}: $it" } }
        if (compat.isNotEmpty()) return Outcome.Blocked(compat)
        val untested = edits.map { it.first }.filter { dao.testedNodeCount(v.hash, listOf(it)) == 0 }
        if (untested.isNotEmpty()) return Outcome.Blocked(listOf("Run this version first: ${untested.joinToString(", ")} have no successful run"))

        val prev = before.mapIndexed { i, a ->
            buildJsonObject {
                put("key", edits[i].second.name); put("version", a.version); put("template", a.template)
                put("schema", a.outputSchema?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
        val casEdits = edits.mapIndexed { i, (_, key, draft) ->
            CasEdit(key, PromptDraft(before[i].template, before[i].outputSchema), draft, "Canvas: ${p.name.take(50)} v$version", testedOk = true)
        }
        // fingerprint what the CAS writes (the normalised draft), never a later re-read that could be an outside edit
        val published = edits.map { (_, key, draft) ->
            val w = prompts.normalise(key, draft)
            buildJsonObject { put("key", key.name); put("fp", PromptDefaults.fingerprint(w.template, w.outputSchema)) }
        }
        val snapshot = buildJsonObject { put("prev", JsonArray(prev)); put("published", JsonArray(published)) }.toString()
        // S27: the prompt CAS and the journal row commit together; a throw anywhere rolls both back
        var result: Outcome = Outcome.Blocked(listOf(PUBLISH_FAILED))
        try {
            transact {
                when (val r = prompts.saveAllIfUnchanged(casEdits)) {
                    is CasOutcome.Mismatch -> result = Outcome.Blocked(r.ids.map { "${labelOf(it)} was changed during publish. Try again." })
                    is CasOutcome.Saved -> {
                        val id = dao.insertPublish(CanvasPublishEntity(pipelineId = pipelineId, version = version, hash = v.hash, publishedAt = clock(), rolledBackAt = null, snapshotJson = snapshot))
                        result = Outcome.Published(id, edits.map { it.second })
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PcLog.w(TAG, "publish failed: ${e.javaClass.simpleName}")
            return Outcome.Blocked(listOf(PUBLISH_FAILED))
        }
        return result
    }

    /**
     * Undoes the newest active publish, unless any of its prompts was edited since.
     * [expectedId]: the publish the caller saw as the newest. When it is no longer active (an auto-rollback won the race) or a newer publish
     * exists, nothing is undone, so a manual click can never undo a publish the user did not see (Task 6 review M6). Null: the newest, as before.
     */
    suspend fun rollback(expectedId: Long? = null): Outcome = lock.withLock { rollbackLocked(expectedId) }

    private suspend fun rollbackLocked(expectedId: Long?): Outcome {
        val active = dao.activePublishes()
        if (expectedId != null) {
            if (active.none { it.id == expectedId }) return Outcome.Blocked(listOf(ALREADY_ROLLED_BACK))
            if (active.first().id != expectedId) return Outcome.Blocked(listOf("A newer publish exists. Reload and try again."))
        }
        val pub = active.firstOrNull() ?: return Outcome.Blocked(listOf("Nothing to roll back"))
        if (pub.kind == KIND_INTERPRETER || pub.kind == KIND_FLOW) return rollbackInterpreter(pub) // neither holds prompts: undoing is the mark
        val snap = runCatching { Json.parseToJsonElement(pub.snapshotJson).jsonObject }.getOrNull()
            ?: return Outcome.Blocked(listOf("The publish record is unreadable"))
        val published = snap["published"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val key = (o["key"] as? JsonPrimitive)?.contentOrNull?.let(PromptKey::parse) ?: return@mapNotNull null
            key to (o["fp"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        val prev = snap["prev"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val key = (o["key"] as? JsonPrimitive)?.contentOrNull?.let(PromptKey::parse) ?: return@mapNotNull null
            val t = (o["template"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            key to PromptDraft(t, (o["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        }
        // Warm every key BEFORE the transaction: PromptRepository.active() may take its app-wide upgradeLock on the first
        // read of a key, and the live path takes that lock and then the database, so taking it inside the transaction
        // (database, then lock) could deadlock. Once warmed, the reads below never touch the lock.
        (published.map { it.first } + prev.map { it.first }).distinct().forEach { prompts.active(it) }
        val stale = "was changed after the publish. Use Settings \u2192 Prompts \u2192 History instead."
        var result: Outcome = Outcome.Blocked(listOf(ROLLBACK_FAILED))
        try {
            transact {
                // ONE read per key, inside the transaction: the fingerprint check and the compare-and-set expectation
                // use this same object, so an outside edit landing after the read can only make the CAS refuse.
                val current = published.associate { (key, _) -> key to prompts.active(key) }
                val fps = published.toMap()
                val changed = (published.map { it.first } + prev.map { it.first }).distinct().filter { key ->
                    val a = current[key]
                    a == null || PromptDefaults.fingerprint(a.template, a.outputSchema) != fps[key]
                }.map { "${it.label} $stale" }
                if (changed.isNotEmpty()) { result = Outcome.Blocked(changed); return@transact }
                val casEdits = prev.map { (key, draft) ->
                    val a = current.getValue(key)
                    CasEdit(key, PromptDraft(a.template, a.outputSchema), draft, "Canvas rollback", testedOk = true)
                }
                when (val r = prompts.saveAllIfUnchanged(casEdits)) {
                    is CasOutcome.Mismatch -> result = Outcome.Blocked(r.ids.map { "${labelOf(it)} $stale" })
                    is CasOutcome.Saved -> {
                        dao.markRolledBack(pub.id, clock())
                        result = Outcome.Published(pub.id, prev.map { it.first })
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PcLog.w(TAG, "rollback failed: ${e.javaClass.simpleName}")
            return Outcome.Blocked(listOf(ROLLBACK_FAILED))
        }
        return result
    }

    /** An interpreter row holds no prompts: undoing it is just the mark (inside the transaction runner like every other journal write). */
    private suspend fun rollbackInterpreter(pub: CanvasPublishEntity): Outcome {
        var result: Outcome = Outcome.Blocked(listOf(ROLLBACK_FAILED))
        try {
            transact { result = if (dao.markRolledBack(pub.id, clock()) > 0) Outcome.Published(pub.id, emptyList()) else Outcome.Blocked(listOf(ALREADY_ROLLED_BACK)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PcLog.w(TAG, "rollback failed: ${e.javaClass.simpleName}")
            return Outcome.Blocked(listOf(ROLLBACK_FAILED))
        }
        return result
    }

    private fun labelOf(id: String): String = PromptKey.parse(id)?.label ?: id
}
