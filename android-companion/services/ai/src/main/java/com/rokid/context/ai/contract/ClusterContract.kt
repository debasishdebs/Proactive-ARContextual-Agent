package com.rokid.context.ai.contract

import android.util.Base64
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.PhotoLiveness
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptDraft
import com.rokid.context.prompt.PromptVars
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One representative photo of a CLUSTER_SUMMARY call. [line] = "cluster_id: … frame_id: … time: … motion: …". */
class ClusterPhoto(val clusterId: String, val frameId: String, val jpeg: ByteArray, val line: String)

/** [facts] reuse the VISION_EXTRACT [Fact]; a fact's [Fact.sourceFrameIds] are photos of THIS cluster. */
data class ClusterResult(val clusterId: String, val summary: String, val facts: List<Fact>)

/**
 * A CLUSTER_SUMMARY output (wave 2 §P4.1): one result per cluster sent, one caption per photo sent. [imagesDelivered]: codex r10 delivery proof (in
 * memory only, set by [ClusterContract.call] from every accepted reply); `ClusterSave` persists nothing without it.
 */
data class ClusterOutput(
    val clusters: List<ClusterResult>, val captions: List<FrameCaption>, override val extras: JsonObject = JsonObject(emptyMap()),
    val imagesDelivered: Boolean = false,
) : ContractOutput

/** The cluster-batch call (spec wave 2 §P4.1): ≤ 3 clusters and ≤ 6 photos per call, ids checked per cluster. */
object ClusterContract {
    private const val TAG = "ClusterContract"
    const val MAX_CLUSTERS = 3
    const val MAX_PHOTOS = 6

    /** True when [schema] is the provenance one: `clusters[].cluster_id` and per-fact `source_frame_ids`. */
    fun promptIsCurrent(schema: String?): Boolean {
        val root = schema?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return false
        val item = ((root["properties"] as? JsonObject)?.get("clusters") as? JsonObject)?.get("items") as? JsonObject ?: return false
        val props = item["properties"] as? JsonObject ?: return false
        if (!props.containsKey("cluster_id")) return false
        val factProps = ((props["facts"] as? JsonObject)?.get("items") as? JsonObject)?.get("properties") as? JsonObject ?: return false
        return factProps.containsKey("source_frame_ids")
    }

    /**
     * [sent]: cluster id to the photos sent for it. INVALID when: an unknown or repeated cluster_id, a sent cluster missing or with a
     * blank summary, a source_frame_id not sent for THAT cluster (or a fact with none), a caption for a photo not sent, or (when
     * [captionsRequired]) a sent photo without a caption.
     */
    fun validate(out: ClusterOutput, sent: Map<String, List<String>>, captionsRequired: Boolean = true): Boolean {
        val ids = out.clusters.map { it.clusterId }
        if (ids.size != ids.toSet().size || ids.any { it !in sent } || sent.keys.any { it !in ids }) return false
        for (c in out.clusters) {
            if (c.summary.isBlank()) return false
            val own = sent.getValue(c.clusterId).toSet()
            if (c.facts.any { it.sourceFrameIds.isEmpty() || it.sourceFrameIds.any { s -> s !in own } }) return false
        }
        val all = sent.values.flatten().toSet()
        if (out.captions.any { it.frameId !in all || it.caption.isBlank() }) return false
        return !captionsRequired || all.all { f -> out.captions.any { it.frameId == f } }
    }

    /**
     * CLUSTER_SUMMARY over [photos] (grouped by cluster, in order), at most [MAX_CLUSTERS] clusters / [MAX_PHOTOS] photos per call.
     * A call whose reply is invalid is retried one cluster per call; a cluster still invalid alone is LEFT OUT of the result (the
     * caller fails it, an attempt each); all clusters invalid throws INVALID_OUTPUT. Any other failure is thrown as is. When the stored
     * prompt isn't current ([promptIsCurrent]) every cluster is a single call, its facts are attributed to that cluster's photos
     * and captions are optional.
     */
    suspend fun call(
        runner: RoleRunner, photos: List<ClusterPhoto>, callClass: CallClass, liveness: PhotoLiveness, override: PromptDraft? = null,
    ): ClusterOutput {
        require(photos.isNotEmpty()) { "no photos" }
        val current = promptIsCurrent(if (override != null) (override.outputSchema ?: DefaultSchemas.text(PromptKey.CLUSTER_SUMMARY)) else runner.activeSchema(PromptKey.CLUSTER_SUMMARY))
        val groups = photos.groupBy { it.clusterId }.values.toList()
        val batches = if (current) pack(groups) else groups.map { listOf(it) }
        val results = mutableListOf<ClusterResult>()
        val captions = mutableListOf<FrameCaption>()
        var invalid: ModelCallException? = null
        var delivered = true
        fun keep(o: ClusterOutput) { results += o.clusters; captions += o.captions; delivered = delivered && o.imagesDelivered }
        for (batch in batches) {
            val first = attempt(runner, batch, callClass, override, current, liveness)
            if (first.out != null) { keep(first.out); continue }
            invalid = first.error
            if (batch.size == 1) continue
            PcLog.i(TAG, "batch of ${batch.size} clusters rejected as INVALID_OUTPUT; retrying one cluster per call")
            for (g in batch) {
                val solo = attempt(runner, listOf(g), callClass, override, current, liveness)
                if (solo.out != null) keep(solo.out) else invalid = solo.error
            }
        }
        if (results.isEmpty()) throw invalid ?: ModelCallException("CLUSTER_SUMMARY reply was invalid", retryable = true, kind = FailureKind.INVALID_OUTPUT)
        return ClusterOutput(results, captions, imagesDelivered = delivered)
    }

    private class Attempt(val out: ClusterOutput?, val error: ModelCallException?)

    private fun pack(groups: List<List<ClusterPhoto>>): List<List<List<ClusterPhoto>>> {
        val batches = mutableListOf<MutableList<List<ClusterPhoto>>>()
        for (g in groups) {
            val cur = batches.lastOrNull()
            if (cur != null && cur.size < MAX_CLUSTERS && cur.sumOf { it.size } + g.size <= MAX_PHOTOS) cur += g else batches += mutableListOf(g)
        }
        return batches
    }

    /** One call over [batch]; INVALID_OUTPUT (from the runner or from [validate]) comes back as an [Attempt], other failures throw. */
    private suspend fun attempt(runner: RoleRunner, batch: List<List<ClusterPhoto>>, callClass: CallClass, override: PromptDraft?, current: Boolean, liveness: PhotoLiveness): Attempt {
        val sent = batch.associate { g -> g.first().clusterId to g.map { it.frameId } }
        val flat = batch.flatten()
        var delivered = false
        val raw = try {
            val r = runner.run(
                RoleCall(
                    PromptKey.CLUSTER_SUMMARY, PromptVars(recentContext = PromptComposer.dataBlock("photos", flat.map { it.line })),
                    images = flat.map { ModelImageInput("image/jpeg", Base64.encodeToString(it.jpeg, Base64.NO_WRAP), detail = "low", ref = it.frameId) },
                    inputIds = sent.keys + sent.values.flatten(), fromFrames = true, callClass = callClass, override = override, liveness = liveness,
                ),
            )
            delivered = r.imagesDelivered
            r.output as ClusterOutput
        } catch (e: ModelCallException) {
            if (e.kind != FailureKind.INVALID_OUTPUT) throw e
            return Attempt(null, e)
        }
        val out = (if (current) raw else legacy(raw, sent.entries.single()))?.copy(imagesDelivered = delivered)
        if (out == null || !validate(out, sent, captionsRequired = current)) {
            return Attempt(null, ModelCallException("CLUSTER_SUMMARY reply failed the provenance check", retryable = true, kind = FailureKind.INVALID_OUTPUT))
        }
        return Attempt(out, null)
    }

    /** An out-of-date prompt's reply: the one cluster sent is the only possible owner of everything in it. */
    private fun legacy(raw: ClusterOutput, only: Map.Entry<String, List<String>>): ClusterOutput? {
        val c = raw.clusters.firstOrNull() ?: return null
        val facts = c.facts.map { f -> f.copy(sourceFrameIds = f.sourceFrameIds.filter { it in only.value }.ifEmpty { only.value }) }
        return ClusterOutput(listOf(ClusterResult(only.key, c.summary, facts)), raw.captions.filter { it.frameId in only.value })
    }
}
