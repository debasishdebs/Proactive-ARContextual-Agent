package com.rokid.context.canvas

import com.rokid.context.ai.canvas.LiveShape
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PromptSource
import com.rokid.context.ai.canvas.promptSource
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasPublishEntity
import com.rokid.context.memory.db.CasOutcome
import com.rokid.context.prompt.CasEdit
import com.rokid.context.prompt.PromptDefaults
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptRepository
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
) {
    sealed interface Outcome {
        data class Published(val publishId: Long, val keys: List<PromptKey>) : Outcome
        data class Blocked(val reasons: List<String>) : Outcome
    }

    private val lock = Mutex()

    suspend fun publish(pipelineId: String, version: Int): Outcome = lock.withLock { publishLocked(pipelineId, version) }

    private suspend fun publishLocked(pipelineId: String, version: Int): Outcome {
        val v = dao.version(pipelineId, version) ?: return Outcome.Blocked(listOf("Version not found"))
        val p = PipelineJson.decode(v.json).getOrNull() ?: return Outcome.Blocked(listOf("Version is unreadable"))
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
        when (val r = prompts.saveAllIfUnchanged(casEdits)) {
            is CasOutcome.Mismatch -> return Outcome.Blocked(r.ids.map { "${labelOf(it)} was changed during publish. Try again." })
            is CasOutcome.Saved -> Unit
        }
        // fingerprint what this CAS wrote (the normalised draft), never a later re-read that could be an outside edit
        val published = edits.map { (_, key, draft) ->
            val w = prompts.normalise(key, draft)
            buildJsonObject { put("key", key.name); put("fp", PromptDefaults.fingerprint(w.template, w.outputSchema)) }
        }
        val snapshot = buildJsonObject { put("prev", JsonArray(prev)); put("published", JsonArray(published)) }.toString()
        val id = dao.insertPublish(CanvasPublishEntity(pipelineId = pipelineId, version = version, hash = v.hash, publishedAt = clock(), rolledBackAt = null, snapshotJson = snapshot))
        return Outcome.Published(id, edits.map { it.second })
    }

    /** Undoes the newest active publish, unless any of its prompts was edited since. */
    suspend fun rollback(): Outcome = lock.withLock { rollbackLocked() }

    private suspend fun rollbackLocked(): Outcome {
        val pub = dao.activePublishes().firstOrNull() ?: return Outcome.Blocked(listOf("Nothing to roll back"))
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
        // ONE read per key: the fingerprint check and the compare-and-set expectation use this same object, so an
        // outside edit landing after the read can only make the CAS refuse, never be overwritten.
        val current = published.associate { (key, _) -> key to prompts.active(key) }
        val fps = published.toMap()
        val changed = (published.map { it.first } + prev.map { it.first }).distinct().filter { key ->
            val a = current[key]
            a == null || PromptDefaults.fingerprint(a.template, a.outputSchema) != fps[key]
        }.map { "${it.label} was changed after the publish. Use Settings \u2192 Prompts \u2192 History instead." }
        if (changed.isNotEmpty()) return Outcome.Blocked(changed)
        val casEdits = prev.map { (key, draft) ->
            val a = current.getValue(key)
            CasEdit(key, PromptDraft(a.template, a.outputSchema), draft, "Canvas rollback", testedOk = true)
        }
        when (val r = prompts.saveAllIfUnchanged(casEdits)) {
            is CasOutcome.Mismatch -> return Outcome.Blocked(r.ids.map { "${labelOf(it)} was changed after the publish. Use Settings \u2192 Prompts \u2192 History instead." })
            is CasOutcome.Saved -> Unit
        }
        dao.markRolledBack(pub.id, clock())
        return Outcome.Published(pub.id, prev.map { it.first })
    }

    private fun labelOf(id: String): String = PromptKey.parse(id)?.label ?: id
}
