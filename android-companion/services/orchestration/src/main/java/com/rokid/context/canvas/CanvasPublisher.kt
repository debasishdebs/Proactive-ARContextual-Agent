package com.rokid.context.canvas

import com.rokid.context.ai.canvas.LiveShape
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PromptSource
import com.rokid.context.ai.canvas.promptSource
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasPublishEntity
import com.rokid.context.prompt.PromptDefaults
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptRepository
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

    suspend fun publish(pipelineId: String, version: Int): Outcome {
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
        val compat = edits.flatMap { (_, key, draft) -> compatibility(key, draft).map { "${key.label}: $it" } }
        if (compat.isNotEmpty()) return Outcome.Blocked(compat)
        val untested = edits.map { it.first }.filter { dao.testedNodeCount(v.hash, listOf(it)) == 0 }
        if (untested.isNotEmpty()) return Outcome.Blocked(listOf("Run this version first: ${untested.joinToString(", ")} have no successful run"))

        val prev = edits.map { (_, key, _) ->
            val a = prompts.active(key)
            buildJsonObject {
                put("key", key.name); put("version", a.version); put("template", a.template)
                put("schema", a.outputSchema?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
        val published = edits.map { (_, key, draft) ->
            prompts.save(key, draft, note = "Canvas: ${p.name.take(50)} v$version", testedOk = true)
            val a = prompts.active(key)
            buildJsonObject { put("key", key.name); put("fp", PromptDefaults.fingerprint(a.template, a.outputSchema)) }
        }
        val snapshot = buildJsonObject { put("prev", JsonArray(prev)); put("published", JsonArray(published)) }.toString()
        val id = dao.insertPublish(CanvasPublishEntity(pipelineId = pipelineId, version = version, hash = v.hash, publishedAt = clock(), rolledBackAt = null, snapshotJson = snapshot))
        return Outcome.Published(id, edits.map { it.second })
    }

    /** Undoes the newest active publish, unless any of its prompts was edited since. */
    suspend fun rollback(): Outcome {
        val pub = dao.activePublishes().firstOrNull() ?: return Outcome.Blocked(listOf("Nothing to roll back"))
        val snap = runCatching { Json.parseToJsonElement(pub.snapshotJson).jsonObject }.getOrNull()
            ?: return Outcome.Blocked(listOf("The publish record is unreadable"))
        val published = snap["published"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val key = (o["key"] as? JsonPrimitive)?.contentOrNull?.let(PromptKey::parse) ?: return@mapNotNull null
            key to (o["fp"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        val changed = published.filter { (key, fp) ->
            val a = prompts.active(key)
            PromptDefaults.fingerprint(a.template, a.outputSchema) != fp
        }.map { "${it.first.label} was changed after the publish. Use Settings \u2192 Prompts \u2192 History instead." }
        if (changed.isNotEmpty()) return Outcome.Blocked(changed)
        val prev = snap["prev"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val key = (o["key"] as? JsonPrimitive)?.contentOrNull?.let(PromptKey::parse) ?: return@mapNotNull null
            val t = (o["template"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            key to PromptDraft(t, (o["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        }
        prev.forEach { (key, draft) -> prompts.save(key, draft, note = "Canvas rollback", testedOk = true) }
        dao.markRolledBack(pub.id, clock())
        return Outcome.Published(pub.id, prev.map { it.first })
    }
}
