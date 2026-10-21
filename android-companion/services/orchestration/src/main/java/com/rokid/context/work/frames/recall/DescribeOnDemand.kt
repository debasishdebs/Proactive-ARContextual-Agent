package com.rokid.context.work.frames.recall

import android.content.Context
import android.util.Log
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.FrameState
import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.work.frames.ExtractNowOutcome
import com.rokid.context.work.frames.ExtractOutcome
import com.rokid.context.work.frames.KeyframeExtract
import com.rokid.context.work.frames.KeyframePipeline
import com.rokid.context.work.frames.scene.RepCandidate
import com.rokid.context.work.frames.scene.SceneRules
import com.rokid.context.work.frames.scene.SceneTuning
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** What a describe-on-demand call concluded (spec §3.3, R6). */
sealed interface DescribeResult {
    /** [memories] were saved for [frames] photos (a photo the model found nothing in still counts as described). */
    data class Saved(val memories: Int, val frames: Int) : DescribeResult
    /** Every chosen photo has no `frame` row or no readable file any more. */
    data object Expired : DescribeResult
    /** Every chosen photo is being extracted under someone else's claim. */
    data object Busy : DescribeResult
    /** Offline, no provider set up, or the model call failed. */
    data object Unavailable : DescribeResult
    /** Delete all ran during the call; nothing was saved. */
    data object Wiped : DescribeResult
}

/**
 * The user's own VISION_EXTRACT over 1 to 3 chosen photos or one scene, class USER (it bypasses the budget like an ask). Photos still waiting for
 * extraction go through the pipeline's own claim ([KeyframePipeline.extractNow]); everything nobody is working on goes through a no-claim save with
 * ids `rokid_desc_<uuid>_<n>` (S6), whose transaction also moves the photo out of the skipped states (S4). The `frame_source` FACT rows that save
 * writes are the durable mark that a photo was described (S3). One call at a time. Every write is behind the wipe-generation and tombstone fences of
 * the save (`ExtractSave.saveExtraction`, `PipelineRecords.appendFact`).
 */
class DescribeOnDemand(
    private val context: Context,
    private val pipeline: KeyframePipeline,
    private val db: ContextDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    /** A full random UUID per call (S6); injectable for the collision test. */
    private val idSource: () -> String = { UUID.randomUUID().toString() },
    private val online: () -> Boolean = { true },
    /** The ACTIVE image space's id, for the scene medoid (S19); null = none, so the newest members are used. */
    private val activeSpaceId: suspend () -> String? = { null },
    private val extractNow: suspend (List<String>) -> Map<String, ExtractNowOutcome> = { pipeline.extractNow(it, CallClass.USER) },
) {
    private val one = Mutex()

    /** 1..3 frames (more: the first 3), one call at a time. */
    suspend fun describePhotos(frameIds: List<String>): DescribeResult =
        one.withLock { describe(frameIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_PHOTOS)) }

    /** The cluster's representatives (at most 3); none: the members' medoid by ACTIVE-space image vector (S19), or the newest members; up to 3 either way. */
    suspend fun describeScene(clusterId: String): DescribeResult {
        val ids = sceneFrames(clusterId)
        return if (ids.isEmpty()) DescribeResult.Expired else describePhotos(ids)
    }

    private suspend fun describe(ids: List<String>): DescribeResult {
        if (ids.isEmpty()) return DescribeResult.Expired
        val stored = PipelineRecords.framesByIds(context, ids).map { it.id }.toSet() // a forgotten, deleted or file-less photo is not there
        val live = ids.filter { it in stored }
        if (live.isEmpty()) { Log.i(TAG, "PcDescribe frames=${ids.size} result=Expired"); return DescribeResult.Expired }
        val states = db.frameStateDao().byIds(live).associate { it.frameId to it.state }
        val openClusters = db.frameClusterDao().let { dao -> live.filter { states[it] == FrameState.IN_CLUSTER.name }.let { ids ->
            if (ids.isEmpty()) emptySet() else db.frameMetaDao().byIds(ids).filter { m -> m.clusterId?.let { c -> dao.get(c)?.state in OPEN_CLUSTER } == true }.map { it.frameId }.toSet()
        } }
        val claimable = live.filter { states[it] in CLAIMABLE || it in openClusters }
        var memories = 0; var described = 0; var expired = ids.size - live.size
        var busy = live.count { states[it] == FrameState.EXTRACTING.name }
        var unavailable = false; var wiped = false
        val direct = live.filter { it !in claimable && states[it] != FrameState.EXTRACTING.name }.toMutableList()

        if (claimable.isNotEmpty()) {
            for ((id, o) in extractNow(claimable)) when (o) {
                is ExtractNowOutcome.Extracted -> { memories += o.memories; described++ }
                ExtractNowOutcome.ExtractedEmpty -> described++
                // Only a lost claim is re-read (S5): another extractor holds it now (Busy), or nobody does (the no-claim path).
                // Never claimed by us: re-read the state (S5). Someone else's claim, or a frame that is claimable again, is not ours to describe (no second call).
                ExtractNowOutcome.LostClaim ->
                    if (db.frameStateDao().get(id)?.state in HELD) busy++ else direct += id
                // The call ran and was paid for, but the claim no longer held: never a second call; the pipeline owns the frame again.
                ExtractNowOutcome.Stale -> busy++
                is ExtractNowOutcome.Failed -> if (o.kind == "unreadable") expired++ else unavailable = true // never retried in the no-claim path: that would be a second, uncounted call
                ExtractNowOutcome.Wiped -> wiped = true
            }
        }
        if (direct.isNotEmpty() && !wiped) {
            val prefix = "$ID_ROOT${idSource()}_"
            when (val o = KeyframeExtract.run(context, direct, null, CallClass.USER, prefix, online, nudge = false) { sent, _ -> markDescribed(sent) }) {
                is ExtractOutcome.Saved -> {
                    memories += o.memories.size; described += direct.size - o.unreadable.size - o.unseen.size
                    expired += o.unreadable.size; if (o.unseen.isNotEmpty()) unavailable = true
                }
                is ExtractOutcome.Wiped -> wiped = true
                is ExtractOutcome.Stale -> unavailable = true // cannot happen without a claim; never a silent success
                is ExtractOutcome.Failed -> if (o.unreadable.size == direct.size) expired += direct.size else unavailable = true
            }
        }
        val result = when {
            described > 0 -> DescribeResult.Saved(memories, described)
            wiped -> DescribeResult.Wiped
            unavailable -> DescribeResult.Unavailable
            busy > 0 -> DescribeResult.Busy
            else -> DescribeResult.Expired
        }
        Log.i(TAG, "PcDescribe frames=${ids.size} claimed=${claimable.size} direct=${direct.size} saved=$memories result=${result.javaClass.simpleName}")
        return result
    }

    /**
     * Inside the save transaction (S4): a described photo in NEAR_DUP, CLUSTERED, FAILED or FAILED_FINAL becomes EXTRACTED (reason `described_on_demand`),
     * so it follows normal photo retention. A frame in another state (EXTRACTED already, IN_CLUSTER of a summarizing cluster) is left to its owner: the
     * FACT rows the save just wrote exempt it from the skipped-photo rule whatever the state does later (S3).
     */
    private suspend fun markDescribed(sent: Set<String>) {
        val now = clock()
        val ids = sent.toList()
        for (from in MOVE_FROM) db.frameStateDao().move(ids, from, FrameState.EXTRACTED.name, DESCRIBED, now)
    }

    private suspend fun sceneFrames(clusterId: String): List<String> {
        val cluster = db.frameClusterDao().get(clusterId) ?: return emptyList()
        val reps = try { JSONArray(cluster.repFrameIds).let { a -> List(a.length()) { a.getString(it) } } } catch (_: Exception) { emptyList() }
        val storedReps = stored(reps)
        if (storedReps.isNotEmpty()) return storedReps.take(MAX_PHOTOS)
        val members = stored(db.frameMetaDao().members(clusterId).map { it.frameId })
        if (members.isEmpty()) return emptyList()
        val newest = members.chunked(CHUNK).flatMap { db.frameDao().byIds(it) }.sortedByDescending { it.ts }.map { it.id }
        val space = try { activeSpaceId() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
        val cands = if (space == null) emptyList() else members.chunked(CHUNK).flatMap { db.frameVectorDao().forFrames(space, it) }.mapNotNull { v ->
            try { RepCandidate(v.frameId, VectorCodec.decode(v.blob), db.frameMetaDao().get(v.frameId)?.blur) } catch (_: IllegalArgumentException) { null }
        }.sortedBy { c -> members.indexOf(c.frameId) }
        val picked = if (cands.isEmpty()) emptyList() else SceneRules.pickReps(cands, SceneTuning())
        return (picked + newest.filter { it !in picked }).take(MAX_PHOTOS) // members without a vector top the list up, newest first
    }

    /** [ids] (order kept) that still have a `frame` row. */
    private suspend fun stored(ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val have = ids.chunked(CHUNK).flatMap { db.frameDao().byIds(it) }.map { it.id }.toSet()
        return ids.filter { it in have }
    }

    companion object {
        private const val TAG = "DescribeOnDemand"
        const val MAX_PHOTOS = 3
        private const val CHUNK = 500
        /** Must start with `rokid_` and never with `rokid_review_`; the uuid is the full 36 characters (S6). */
        const val ID_ROOT = "rokid_desc_"
        const val DESCRIBED = "described_on_demand"
        private val CLAIMABLE = setOf(FrameState.PENDING.name, FrameState.CAPTION_QUEUED.name)
        /** Another extractor holds it, or it is waiting for its own (a describe must not make a second call for it). IN_CLUSTER is checked per cluster state. */
        private val HELD = setOf(FrameState.EXTRACTING.name, FrameState.PENDING.name, FrameState.CAPTION_QUEUED.name)
        private val OPEN_CLUSTER = setOf("OPEN", "CLOSED")
        private val MOVE_FROM = listOf(FrameState.NEAR_DUP.name, FrameState.CLUSTERED.name, FrameState.FAILED.name, FrameState.FAILED_FINAL.name)
    }
}
