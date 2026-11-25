package com.rokid.context.canvas.moment

import androidx.room.withTransaction
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.SceneCentroidEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.memory.retrieval.VectorMath
import com.rokid.context.work.frames.scene.SceneRules

/**
 * v18 WP12a: the producer of `scene_centroid` (owner decision 2026-11-26: the centroid is Scene metadata). Behind the Developer flag `sceneCentroids` (default OFF). It runs on the path that already
 * rebuilds Scenes ([SceneBuilder.rebuildWindow], so the 15-minute Moment cadence worker; no new worker) and never inside the rebuild's own transaction: a failure here can never undo a Scene rebuild.
 *
 * Per Scene and per embedding space (the ACTIVE image space; any other space only when [spaces] offers it, which production does for SigLIP 2 only while its shadow flag is ON and only if it has stored vectors):
 * the centroid is the mean of the member frames' vectors, then L2-normalised ([SceneRules.centroid], the wave-2 scene centroid). The Scene row's `membershipRev` is read INSIDE the same transaction that writes
 * the row, so a Forget shrink (which bumps the revision in its own transaction) either lands before (the row carries the new revision) or after (the row is stale and never read). A Scene whose members have
 * no usable vector in a space gets no row there; an empty, non-finite or zero-norm result is never written.
 *
 * DEDUP: the existing near-duplicate marking is reused (`frame_meta.nearDupOf`, set by the semantic dedupe of [com.rokid.context.work.frames.scene.SceneIndexer] at cosine 0.93). Each duplicate group counts ONCE:
 * a frame marked as a duplicate stands for the frame it duplicates (its representative), and the representative's own vector is used when it has one (else the first member's vector). One hop only.
 *
 * Content-free: the vectors derive from photos (so the rows go with the Scene and with every Forget, see WP10), and nothing here logs more than counts.
 */
class SceneCentroidProducer(
    private val db: ContextDatabase,
    private val enabled: () -> Boolean = { false },
    /** The spaces to write, asked once per refresh and outside any transaction (it may build an embedder). Default: none. */
    private val spaces: suspend () -> List<CentroidSpace> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [spaceId] keys `frame_vector`; [fingerprint] is the identity stored in `scene_centroid.space` (what an embedder reports as its `spaceId`), [dim] the image width. */
    data class CentroidSpace(val spaceId: String, val fingerprint: String, val dim: Int)

    /** [written] rows upserted, [fresh] rows already current, [noVectors] (scene, space) pairs skipped for lack of usable vectors, [gone] Scenes that no longer exist. */
    data class Report(val written: Int, val fresh: Int, val noVectors: Int, val gone: Int) { companion object { val NONE = Report(0, 0, 0, 0) } }

    /** Every Scene of the clock hours `[fromHourMs, toHourMs]` (hour starts). */
    suspend fun refreshHours(fromHourMs: Long, toHourMs: Long): Report {
        if (!enabled()) return Report.NONE
        val ids = db.sceneDao().scenesInHours(fromHourMs, toHourMs).map { it.sceneId }
        return refresh(ids)
    }

    suspend fun refresh(sceneIds: Collection<String>): Report {
        if (!enabled() || sceneIds.isEmpty()) return Report.NONE
        val targets = spaces().filter { it.dim > 0 }.distinctBy { it.fingerprint }
        if (targets.isEmpty()) return Report.NONE
        var written = 0; var fresh = 0; var none = 0; var gone = 0
        for (id in sceneIds.distinct().sorted()) {
            val r = db.withTransaction { one(id, targets) }
            written += r.written; fresh += r.fresh; none += r.noVectors; gone += r.gone
        }
        return Report(written, fresh, none, gone)
    }

    /** One Scene, inside the caller's transaction: the revision, the members and the write are one consistent read. */
    private suspend fun one(sceneId: String, targets: List<CentroidSpace>): Report {
        val scene = db.sceneDao().scene(sceneId) ?: return Report(0, 0, 0, 1)
        val rev = scene.membershipRev
        val existing = db.sceneCentroidDao().getForScene(sceneId).associateBy { it.space }
        val todo = targets.filter { t -> existing[t.fingerprint]?.membershipRev != rev }
        if (todo.isEmpty()) return Report(0, targets.size, 0, 0)
        val momentIds = db.sceneDao().parts(sceneId).filter { it.kind == ScenePartKind.MOMENT }.map { it.refId }
        val frames = momentIds.chunked(CHUNK).flatMap { db.momentDao().partsOf(it) }.filter { it.kind == MomentPartKind.FRAME }.map { it.refId }.distinct().sorted()
        val metas = frames.chunked(CHUNK).flatMap { db.frameMetaDao().byIds(it) }.associateBy { it.frameId }
        // One group per duplicate set: the key is the representative (the frame this one duplicates), or the frame itself.
        val groups = frames.groupBy { metas[it]?.nearDupOf?.takeIf { rep -> rep.isNotEmpty() } ?: it }
        var written = 0; var none = 0
        for (t in todo) {
            val wanted = (groups.keys + groups.values.flatten()).distinct()
            val blobs = wanted.chunked(CHUNK).flatMap { db.frameVectorDao().forFrames(t.spaceId, it) }.associate { it.frameId to it.blob }
            val picked = groups.entries.sortedBy { it.key }.mapNotNull { (rep, members) -> (listOf(rep) + members).firstNotNullOfOrNull { f -> blobs[f]?.let { decodeOrNull(it, t.dim) } } }
            val v = build(picked, t.dim)
            if (v == null) { none++; continue }
            db.sceneCentroidDao().upsert(SceneCentroidEntity(sceneId, t.fingerprint, rev, t.dim, VectorCodec.encode(v), picked.size, clock()))
            written++
        }
        return Report(written, targets.size - todo.size, none, 0)
    }

    private fun decodeOrNull(b: ByteArray, dim: Int): FloatArray? =
        if (b.size != dim * 4) null else try { VectorCodec.decode(b).takeIf { v -> v.all(Float::isFinite) && VectorMath.usable(v) } } catch (_: IllegalArgumentException) { null }

    companion object {
        private const val CHUNK = 500

        /** The mean of [vectors] then L2; null when there is no usable vector or the result is empty, non-finite or of zero norm. Pure. */
        fun build(vectors: List<FloatArray>, dim: Int): FloatArray? {
            val ok = vectors.filter { it.size == dim && it.all(Float::isFinite) && VectorMath.usable(it) }
            if (ok.isEmpty()) return null
            val c = SceneRules.centroid(ok)
            return c.takeIf { it.size == dim && it.all(Float::isFinite) && VectorMath.usable(it) }
        }
    }
}
