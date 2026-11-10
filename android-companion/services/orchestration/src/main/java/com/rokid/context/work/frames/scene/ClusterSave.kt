package com.rokid.context.work.frames.scene

import android.content.Context
import com.rokid.context.core.log.PcLog
import androidx.room.withTransaction
import com.rokid.context.ai.contract.ClusterOutput
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameState
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.SavedMemory

sealed interface ClusterSaveResult {
    /** [failedClusters]: clusters of the call that had no live representative left; they were failed for good, nothing of theirs was written. */
    data class Saved(val memories: List<SavedMemory>, val failedClusters: List<String> = emptyList()) : ClusterSaveResult
    /** A cluster claim no longer held: the whole output was dropped, nothing was written. */
    data object Stale : ClusterSaveResult
    /** Delete all ran since the work began: nothing was written. */
    data object Wiped : ClusterSaveResult
    /** Codex r10: the output carries no proof its photos reached the model ([ClusterOutput.imagesDelivered]): nothing was written. */
    data object Undelivered : ClusterSaveResult
}

/** The atomic commit of a CLUSTER_SUMMARY call (wave 2 §P4.1, spec §P2.5): one transaction that starts with the CAS on every cluster row. */
object ClusterSave {
    private const val TAG = "ClusterSave"
    private const val MAX_IN = 500

    private class Rollback(val result: ClusterSaveResult) : RuntimeException(null, null, false, false)

    /**
     * [sent]: cluster id to the representatives sent for it (the first one still alive is the medoid, as `closeRow` puts it first).
     * A representative or member forgotten or swept since the call began (tombstoned or its frame row gone) is dropped; a cluster left
     * with no representative is failed for good (FAILED_FINAL, surviving members NEAR_DUP `cluster_failed`) and writes no memory.
     * Ids: summary `rokid_cl_<claimId>_<i>`, facts `…_<i>f<n>`, captions `…_c<n>`. A member an ask claimed meanwhile (EXTRACTING) keeps its state.
     */
    suspend fun save(
        context: Context, claimId: String, out: ClusterOutput, sent: Map<String, List<String>>, generation: Long, nowMs: Long, factReview: ReviewState,
    ): ClusterSaveResult {
        require(sent.keys.all { id -> out.clusters.any { it.clusterId == id } }) { "output misses a sent cluster" }
        // Codex r10: a scene summary or caption is never persisted unless the router proved the photos reached the model.
        if (!out.imagesDelivered) { PcLog.w(TAG, "cluster save refused: no image delivery proof"); return ClusterSaveResult.Undelivered }
        val db = MemoryGraph.get(context).db
        val results = out.clusters.filter { it.clusterId in sent }
        return try {
            db.withTransaction {
                if (MemoryWipe.generation.get() != generation) throw Rollback(ClusterSaveResult.Wiped)
                val members = results.associate { it.clusterId to db.frameMetaDao().members(it.clusterId).map { m -> m.frameId } }
                val live = liveFrames(context, (members.values.flatten() + sent.values.flatten()).distinct())
                // codex r15: one call (up to 3 clusters) saw every representative it was sent, so any output may carry any of them; a representative
                // forgotten since the call rolls the whole output back (the clusters are retried), and every saved memory is linked to all of them
                val carried = sent.values.flatten().toSet()
                if (carried.any { it !in live }) throw Rollback(ClusterSaveResult.Stale)
                // The CAS comes first for every cluster: a stale attempt must not write a single row. A cluster with no live representative
                // is failed (also a CAS on its claim) instead of completed.
                val repsOf = results.associate { c -> c.clusterId to sent.getValue(c.clusterId).filter { it in live }.distinct() }
                val failed = mutableListOf<String>()
                results.forEachIndexed { i, c ->
                    val reps = repsOf.getValue(c.clusterId)
                    val ok = if (reps.isEmpty()) {
                        failed += c.clusterId
                        db.frameClusterDao().fail(c.clusterId, claimId, "reps_forgotten", nowMs, maxAttempts = 1)
                    } else db.frameClusterDao().completeIfClaimed(c.clusterId, claimId, "rokid_cl_${claimId}_$i", nowMs)
                    if (ok == 0) { PcLog.i(TAG, "cluster save dropped: claim no longer holds"); throw Rollback(ClusterSaveResult.Stale) }
                }
                val saved = mutableListOf<SavedMemory>()
                val rows = mutableListOf<FrameSourceEntity>()
                results.forEachIndexed { i, c ->
                    val reps = repsOf.getValue(c.clusterId)
                    if (reps.isEmpty()) return@forEachIndexed
                    val summaryId = "rokid_cl_${claimId}_$i"
                    if (!PipelineRecords.appendFact(context, summaryId, reps.first(), c.summary, "cluster_summary", listOf("scene"), 1.0, MemoryTier.LONG, ReviewState.OK,
                            nowMs, generation, fenceFrameIds = reps, imageDelivered = out.imagesDelivered)) {
                        throw Rollback(if (MemoryWipe.generation.get() != generation) ClusterSaveResult.Wiped else ClusterSaveResult.Stale)
                    }
                    saved += SavedMemory(summaryId, reps, null)
                    val all = (members.getValue(c.clusterId) + reps).distinct().filter { it in live }
                    rows += all.map { FrameSourceEntity(summaryId, it, c.clusterId, if (it in reps) "CLUSTER_REP" else "CLUSTER_MEMBER") }
                    c.facts.forEachIndexed { n, f ->
                        val ids = f.sourceFrameIds.filter { it in reps }.distinct()
                        if (f.text.isBlank() || ids.isEmpty()) return@forEachIndexed
                        val id = "${summaryId}f$n"
                        if (PipelineRecords.appendFact(context, id, ids.first(), f.text, f.type, f.tags, f.confidence, MemoryTier.SHORT, factReview, nowMs, generation, fenceFrameIds = ids, imageDelivered = out.imagesDelivered)) {
                            saved += SavedMemory(id, ids, n)
                            rows += ids.map { FrameSourceEntity(id, it, c.clusterId, "FACT") }
                        }
                    }
                }
                val clusterOf = results.filter { it.clusterId !in failed }.flatMap { c -> repsOf.getValue(c.clusterId).map { it to c.clusterId } }.toMap()
                out.captions.filter { it.caption.isNotBlank() && it.frameId in clusterOf }.distinctBy { it.frameId }.forEachIndexed { n, c ->
                    val id = "rokid_cl_${claimId}_c$n"
                    if (PipelineRecords.appendFact(context, id, c.frameId, c.caption, "caption", listOf("caption"), 1.0, MemoryTier.LONG, ReviewState.OK, nowMs, generation,
                            fenceFrameIds = listOf(c.frameId), imageDelivered = out.imagesDelivered)) {
                        saved += SavedMemory(id, listOf(c.frameId), null)
                        rows += FrameSourceEntity(id, c.frameId, clusterOf.getValue(c.frameId), "FACT")
                    }
                }
                db.frameSourceDao().deleteForMemories(saved.map { it.memoryId }) // a re-run replaces a memory by id: its earlier provenance goes first
                db.frameSourceDao().insertAll(rows)
                // codex r16: per memory, the representatives of the call(s) that saw its photos (not every call of the output)
                fun carriedOf(ids: List<String>): Set<String> = if (out.callGroups.isEmpty()) carried else out.callGroups.filter { g -> ids.any { it in g } }.flatten().toSet()
                db.frameSourceDao().insertAll(saved.flatMap { m -> (carriedOf(m.frameIds) - m.frameIds.toSet()).map { FrameSourceEntity(m.memoryId, it, null, com.rokid.context.orchestration.ExtractSave.CARRIED) } })
                // Members still IN_CLUSTER only: a member an ask claimed meanwhile stays EXTRACTING.
                results.forEach { c ->
                    if (c.clusterId in failed) db.frameStateDao().settleCluster(c.clusterId, FrameState.NEAR_DUP.name, "cluster_failed", nowMs)
                    else db.frameStateDao().settleCluster(c.clusterId, FrameState.CLUSTERED.name, null, nowMs)
                }
                PcLog.i(TAG, "cluster save: ${results.size - failed.size} clusters, ${saved.size} memories, ${failed.size} failed")
                ClusterSaveResult.Saved(saved, failed)
            }
        } catch (r: Rollback) {
            r.result
        }
    }

    /** The frames of [ids] that still exist and were not forgotten (tombstoned). */
    private suspend fun liveFrames(context: Context, ids: List<String>): Set<String> {
        val db = MemoryGraph.get(context).db
        return ids.chunked(MAX_IN).flatMap { chunk ->
            val gone = db.forgetDao().tombstoned(chunk).toSet()
            db.frameDao().byIds(chunk).map { it.id }.filter { it !in gone }
        }.toSet()
    }
}
