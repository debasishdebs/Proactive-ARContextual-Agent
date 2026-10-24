package com.rokid.context.canvas.moment

import androidx.room.withTransaction
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.DerivedValue
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.moment.ObjectKind
import com.rokid.context.memory.moment.ObjectRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One stored Scene as the canvas reads it: ids, window, the rule that cut it, its activity and its Moments BY REFERENCE; [derived] holds only fields that are fresh (never a hidden one). */
data class SceneView(
    val id: String, val hourStartMs: Long, val startMs: Long, val endMs: Long, val rule: String, val activity: String?,
    val startTrigger: String?, val endTrigger: String?, val membershipRev: Int, val momentIds: List<String>, val derived: Map<String, DerivedValue>,
    /** The compact sensor summary over the member seconds that have `moment_sensor` rows, and the summary of each such member; null and empty when no member has one. */
    val sensor: SensorSummary? = null, val momentSensor: Map<String, SensorSummary> = emptyMap(),
    /** What a later step may read as the Scene's words: the VISIBLE summary and caption of the Scene, then of each member Moment (a hidden or stale field adds nothing). Never in [SceneInputExecutor.toJson]. */
    val text: String = "",
)

/**
 * The phone-side executor of `input.scenes` (a root: it reads, nothing flows into it). It returns the stored Scenes that overlap a time window, oldest first, at most
 * [MAX_SCENES]. A derived field (summary, caption, embedding) is returned only while it is fresh: a Scene that lost a member has all three hidden at once ([ObjectAudit.readDerived]
 * is the one reader, so a hidden field cannot leak through this node). Read-only; [DaoSceneRunSource] feeds these views to a run.
 */
class SceneInputExecutor(private val db: ContextDatabase) {
    val type = NodeTypes.INPUT_SCENES
    private val audit = ObjectAudit(db.objectDao())

    /** Test seam: runs after the Scenes and their parts were read and before the derived fields are, so a test can commit a Forget in between. Always null in production. */
    @Volatile internal var testBetweenReads: (suspend () -> Unit)? = null

    suspend fun run(fromMs: Long, toMs: Long, limit: Int = MAX_SCENES): List<SceneView> {
        if (toMs < fromMs) return emptyList()
        return read { it.scenesBetween(fromMs, toMs, limit.coerceIn(1, MAX_SCENES)) }
    }

    /** The named Scenes as they are now (a Scene that is gone is simply absent), oldest first; the run machinery re-reads them with this. */
    suspend fun byIds(ids: List<String>): List<SceneView> =
        if (ids.isEmpty()) emptyList() else read { d -> ids.distinct().take(MAX_SCENES).chunked(500).flatMap { d.scenesByIds(it) }.sortedWith(compareBy({ it.startMs }, { it.sceneId })) }

    private suspend fun read(load: suspend (com.rokid.context.memory.db.SceneDao) -> List<com.rokid.context.memory.db.SceneEntity>): List<SceneView> {
        // ONE transaction: the Scenes, their members and their derived fields come from the same state, so a Forget cannot commit between two of the reads (it waits for this one).
        return db.withTransaction {
            val dao = db.sceneDao()
            val scenes = load(dao)
            if (scenes.isEmpty()) return@withTransaction emptyList()
            val parts = dao.partsOf(scenes.map { it.sceneId }).groupBy { it.sceneId }
            val memberIds = parts.values.flatten().filter { it.kind == com.rokid.context.memory.db.ScenePartKind.MOMENT }.map { it.refId }.distinct()
            val sensorRows = memberIds.chunked(500).flatMap { db.momentDao().sensorOf(it) }.groupBy { it.momentId } // read in the same transaction as the Scenes
            testBetweenReads?.invoke()
            scenes.map { s ->
                // a field shows only while it is fresh AND was built from the Scene's current input revision
                val seq = audit.inputSeq(s)
                val derived = DerivedField.ALL.mapNotNull { f -> audit.readDerived(s, f)?.takeIf { it.builtRev == seq }?.let { f to it } }.toMap()
                val ids = parts[s.sceneId].orEmpty().filter { it.kind == com.rokid.context.memory.db.ScenePartKind.MOMENT }.map { it.refId }
                val mine = ids.mapNotNull { id -> sensorRows[id]?.let { id to it } }.toMap()
                val words = listOf(DerivedField.SUMMARY, DerivedField.CAPTION).mapNotNull { derived[it]?.value } +
                    ids.flatMap { id -> listOf(DerivedField.SUMMARY, DerivedField.CAPTION).mapNotNull { f -> audit.readDerived(ObjectRef(ObjectKind.MOMENT, id), f)?.value } }
                SceneView(s.sceneId, s.hourStartMs, s.startMs, s.endMs, s.boundaryRule, s.activity, s.startTrigger, s.endTrigger, s.membershipRev, ids, derived,
                    SensorSummary.of(mine.values.flatten()), mine.mapNotNull { (id, r) -> SensorSummary.of(r)?.let { id to it } }.toMap(), words.filter { it.isNotBlank() }.joinToString("\n"))
            }
        }
    }

    fun toJson(views: List<SceneView>): JsonArray = JsonArray(views.map { v ->
        buildJsonObject {
            put("id", v.id); put("hourStartMs", v.hourStartMs); put("startMs", v.startMs); put("endMs", v.endMs); put("rule", v.rule)
            put("activity", v.activity?.let { JsonPrimitive(it) } ?: JsonNull)
            put("startTrigger", v.startTrigger?.let { JsonPrimitive(it) } ?: JsonNull); put("endTrigger", v.endTrigger?.let { JsonPrimitive(it) } ?: JsonNull)
            put("membershipRev", v.membershipRev)
            put("momentIds", JsonArray(v.momentIds.map { JsonPrimitive(it) }))
            v.sensor?.let { put("sensor", it.toJson()) }
            if (v.momentSensor.isNotEmpty()) put("momentSensor", JsonObject(v.momentSensor.mapValues { (_, s) -> s.toJson() }))
            put("derived", JsonObject(v.derived.mapValues { (_, d) -> buildJsonObject { put("value", d.value?.let { JsonPrimitive(it) } ?: JsonNull); put("ref", d.ref?.let { JsonPrimitive(it) } ?: JsonNull); put("builtRev", d.builtRev) } }))
        }
    })

    companion object { const val MAX_SCENES = 500 }
}
