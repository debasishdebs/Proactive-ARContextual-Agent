package com.rokid.context.canvas.moment

import com.rokid.context.memory.db.ContextDatabase
import java.security.MessageDigest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One Scene as a run takes it. [json] is the item a later node receives (no photo id, no path, no image: the Scene's window, rule, activity and its VISIBLE text);
 * [frameIds] are the member photos of its Moments (the Forget cascade and the fence need them, a node never sees them); [binding] fingerprints everything a run would read.
 */
data class SceneRunItem(val id: String, val binding: String, val frameIds: List<String>, val json: JsonObject)

/** Resolves Scenes for a run, as they are NOW; a Scene that is gone is absent. */
fun interface SceneRunSource { suspend fun resolve(ids: List<String>): List<SceneRunItem> }

class DaoSceneRunSource(db: ContextDatabase, private val moments: MomentResolver) : SceneRunSource {
    private val scenes = SceneInputExecutor(db)

    override suspend fun resolve(ids: List<String>): List<SceneRunItem> = scenes.byIds(ids).map { v ->
        val frames = moments.resolve(v.momentIds).flatMap { m -> m.frames.map { it.id } }
        val json = buildJsonObject {
            put("itemType", "scene"); put("sceneId", v.id); put("startMs", v.startMs); put("endMs", v.endMs); put("rule", v.rule)
            put("activity", v.activity?.let { JsonPrimitive(it) } ?: JsonNull)
            put("momentCount", v.momentIds.size)
            put("text", v.text)
            v.sensor?.let { put("sensor", it.toJson()) }
        }
        SceneRunItem(v.id, "${v.id}:${v.membershipRev}:" + sha256(json.toString() + "|" + frames.sorted().joinToString(",")), frames, json)
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
