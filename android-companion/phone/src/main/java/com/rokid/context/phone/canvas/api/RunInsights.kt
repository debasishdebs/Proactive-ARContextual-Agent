package com.rokid.context.phone.canvas.api

import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.CanvasScoreEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The intent summary of a run and of an A/B pair (spec section 7): the count per branch of each switch, the agreement, the score sums. Pure. */
internal object RunInsights {
    /** The ids of the `route.switch` nodes of a run's frozen pipeline JSON (none when it can't be decoded). */
    fun switchIds(pipelineJson: String): List<String> =
        PipelineJson.decode(pipelineJson).getOrNull()?.nodes?.filter { it.type == NodeTypes.SWITCH }?.map { it.id }.orEmpty()

    /** `{"<switchNodeId>": {"<label>": count}}` over the items of those switches that took a branch. */
    fun branches(items: List<CanvasRunItemEntity>, switches: List<String>): JsonObject = buildJsonObject {
        switches.forEach { sid ->
            val counts = items.filter { it.nodeId == sid && it.status == "OK" && it.branch != null }.groupingBy { it.branch!! }.eachCount()
            put(sid, JsonObject(counts.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        }
    }

    /** Per switch, the share of photos (present in both runs with a branch) whose branch is the same; a switch with no common photo is left out. */
    fun agreement(a: List<CanvasRunItemEntity>, b: List<CanvasRunItemEntity>, switches: List<String>): JsonObject = buildJsonObject {
        switches.forEach { sid ->
            val ba = a.filter { it.nodeId == sid && it.branch != null }.associate { it.frameId to it.branch }
            val bb = b.filter { it.nodeId == sid && it.branch != null }.associate { it.frameId to it.branch }
            val common = ba.keys.intersect(bb.keys)
            if (common.isNotEmpty()) put(sid, common.count { ba[it] == bb[it] }.toDouble() / common.size)
        }
    }

    /** `{"<nodeId>": {a, b}}`: the sum of the scores per node in each run. */
    fun scoreSums(a: List<CanvasScoreEntity>, b: List<CanvasScoreEntity>): JsonObject = buildJsonObject {
        val sa = a.groupBy { it.nodeId }.mapValues { (_, v) -> v.sumOf { it.score } }
        val sb = b.groupBy { it.nodeId }.mapValues { (_, v) -> v.sumOf { it.score } }
        (sa.keys + sb.keys).sorted().forEach { n -> put(n, buildJsonObject { put("a", sa[n] ?: 0); put("b", sb[n] ?: 0) }) }
    }
}
