package com.rokid.context.orchestration

import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.memory.retrieval.RetrievalMode

/**
 * The published Query or Nudge pipeline, if any (v0.9.8 slice B). Null = run the built-in flow. A lookup that throws is treated as null by the callers:
 * a pipeline problem never silences the user.
 */
fun interface FlowPlans {
    suspend fun plan(kind: FlowPlan.Kind): FlowPlan?
    companion object { val None = FlowPlans { null } }
}

/** The sources a plan reads for an Ask (the built-in plan gives exactly the memory store and the photo store). */
internal fun FlowPlan.askSources(): List<RagSource> = listOfNotNull(RagSource.MemoryStore().takeIf { memories }, RagSource.PhotoStore().takeIf { photos })

/** The sources of a Nudge; the photo store joins only when the trigger gave an image vector and the plan uses it. */
internal fun FlowPlan.nudgeSources(hasPhotoVector: Boolean): List<RagSource> = listOfNotNull(
    RagSource.RecentWindow(recentWindowMin * 60_000L).takeIf { recent }, RagSource.NudgeHistory(historyWindowMin * 60_000L).takeIf { history },
    RagSource.PhotoStore().takeIf { imageQuery && hasPhotoVector },
)

/** [base] with every field the pipeline sets replacing its own; a field the pipeline leaves out stays as [base] has it (null = the user's setting). */
internal fun FlowPlan.ragParams(base: RagParams = RagParams()): RagParams {
    fun str(k: String) = (rag[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    fun num(k: String) = (rag[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }
    return base.copy(
        mode = str("mode")?.let { m -> RetrievalMode.entries.firstOrNull { it.name == m } } ?: base.mode,
        fusion = str("fusion")?.let { m -> RagFusion.entries.firstOrNull { it.name == m } } ?: base.fusion,
        semanticWeight = num("semanticWeight")?.content?.toDoubleOrNull() ?: base.semanticWeight,
        rrfK = num("rrfK")?.content?.toIntOrNull() ?: base.rrfK,
        topK = num("topK")?.content?.toIntOrNull() ?: base.topK,
        poolSize = num("poolSize")?.content?.toIntOrNull() ?: base.poolSize,
        minConfidence = num("minConfidence")?.content?.toDoubleOrNull() ?: base.minConfidence,
        recencyBoost = num("recencyBoost")?.content?.toBooleanStrictOrNull() ?: base.recencyBoost,
        halfLifeDays = num("halfLifeDays")?.content?.toDoubleOrNull() ?: base.halfLifeDays,
        maxChars = num("maxChars")?.content?.toIntOrNull() ?: base.maxChars,
        maxRecent = num("maxRecent")?.content?.toIntOrNull() ?: base.maxRecent,
    )
}
