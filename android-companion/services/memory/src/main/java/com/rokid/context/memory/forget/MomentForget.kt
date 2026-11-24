package com.rokid.context.memory.forget

import androidx.room.withTransaction
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.MomentTombstoneEntity

/**
 * Moment Forget (moment-model spec section 8): forget the Moments of given ids or of a time range, in ONE transaction, idempotently.
 *
 * Discovery NEVER trusts the projection: the affected sources are the UNION of (a) what `moment_part` names for those Moments and (b) what the authoritative stores hold for the
 * time (frames by `frame.ts` and by `frame_meta.capturedAtMs`, so a photo retention already expired is still found; memories by `createdAt` and by their frame; marks by time),
 * so a projection that is empty, stale or defective loses nothing. The existing cascade code is CALLED, not copied: [ForgetService.forgetMomentTx] per photo (memories, cites,
 * tombstone, canvas outputs, helper jobs), [ForgetService.forgetFactsTx] for the memories no photo reaches, and [com.rokid.context.memory.db.CanvasSlice3Dao.forgetRangeTx]
 * (traces, marks, the `frame_forget_rev` fence). Fences first: the outward-rounded full-second range (`momentForgetRanges`) and one `moment_tombstone` per requested second
 * (also when no Moment exists), so a frame, mark or builder run for that time is refused whatever the arrival order. The durable `cleanup_outbox` rows (phone files, glasses
 * frames, the glasses range, tombstoned traces) are inserted in the SAME transaction; listeners fire only after commit.
 */
class MomentForget(
    private val db: ContextDatabase,
    private val service: ForgetService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Counts only (never ids): seconds fenced, photos, memories and marks found, projection rows dropped, outbox rows actually inserted, and the underlying cascade result. */
    data class Result(
        val seconds: Int, val frames: Int, val memories: Int, val marks: Int, val momentsDropped: Int, val outboxRows: Int,
        val cascade: ForgetService.Result = ForgetService.Result.EMPTY,
        /** The glasses traces this Forget tombstoned (ids, for the file and glasses follow-up of the canvas range Forget), the marks deleted and the results invalidated by the range cascade. */
        val tombstonedTraceIds: List<String> = emptyList(), val labelsDeleted: Int = 0, val resultsInvalidated: Int = 0,
        /** Regenerable run results of OTHER keys deleted because they share a provider call with the closure (the actual count, not the preview's estimate). */
        val peerResultsDeleted: Int = 0,
    ) { companion object { val EMPTY = Result(0, 0, 0, 0, 0, 0) } }

    /** Test seam: runs inside the transaction after everything was written, so a test can throw and prove all of it rolls back. Always null in production. */
    @Volatile internal var testFaultBeforeCommit: (() -> Unit)? = null

    /** Forgets the Moments [ids]; a malformed id is ignored. */
    suspend fun forgetMoments(ids: Collection<String>): Result {
        val seconds = ids.mapNotNull { MomentId.parse(it) }.distinct().sorted()
        if (seconds.isEmpty()) return Result.EMPTY
        return run(seconds, mergeSpans(seconds.map { it * 1000..it * 1000 + 999 }))
    }

    /** Forgets every Moment of `[fromMs, toMs]` rounded OUTWARD to whole seconds. */
    suspend fun forgetRange(fromMs: Long, toMs: Long): Result {
        if (toMs < 0 || toMs < fromMs) return Result.EMPTY
        val lo = Math.floorDiv(maxOf(fromMs, 0L), 1000L).coerceAtMost(MomentId.MAX_SECOND)
        val hi = Math.floorDiv(toMs, 1000L).coerceAtMost(MomentId.MAX_SECOND)
        val seconds = if (hi - lo < MAX_TOMBSTONED_SPAN) (lo..hi).toList() else emptyList() // a longer span relies on the durable range fence alone (a late frame is refused by it)
        return run(seconds, listOf(lo * 1000..hi * 1000 + 999))
    }

    /**
     * v0.9.7 slice C (Forget scope MOMENT / SCENE): forgets every source inside [spans] (millisecond ranges, rounded OUTWARD to whole seconds and merged) with the very code of [forgetRange]: photos, memories,
     * marks, traces, sensor rows, run outputs, projection, durable outbox and the fences (`momentForgetRanges` + a tombstone per second). [sceneIds] are Scenes that go WHOLE in the same transaction
     * (rows, parts, audit, derived fields); the run outputs of those Scenes and of every Moment that went (`scene:<id>`, `moment:<id>` rows) are deleted by key as well, not only through their lineage tokens.
     */
    suspend fun forgetSpans(spans: List<LongRange>, sceneIds: Collection<String> = emptyList(), expectedHash: String? = null, guard: () -> Unit = {}): Result {
        val merged = wholeSeconds(spans)
        if (merged.isEmpty()) return Result.EMPTY
        return run(secondsOf(merged), merged, sceneIds.toList().distinct(), byKey = true, expectedHash = expectedHash, guard = guard)
    }

    /** What [forgetSpans] would remove, from the SAME discovery (read-only, one snapshot): the numbers the confirm dialog shows. */
    /** [scenesRemoved] = Scenes that go whole (named, or every one of their Moments goes); [scenesShrunk] = Scenes that lose some Moments but survive. */
    data class Counts(val photos: Int, val memories: Int, val marks: Int, val moments: Int, val scenesRemoved: Int, val scenesShrunk: Int,
        /** Identifies exactly the set counted (ids, spans, Scene effect): a Forget confirmed with it deletes that set or nothing ([ForgetContentChanged]). */
        val planHash: String = "",
        /** Items OUTSIDE the spans that are affected but NOT deleted: photos an in-span fact cites (locked only) and derived facts elsewhere that become invalid. */
        val outside: Int = 0,
        /** Regenerable run results of OTHER photos that share a provider call with an outside cited photo whose outputs are scrubbed: they ARE deleted (the next run recreates them); counted so the user sees them. */
        val peerResults: Int = 0,
        /** More than [MAX_UNACKED_RESULTS] regenerable results would be rebuilt: the dialog warns, and the delete needs [ackHash] (the plan hash plus the explicit acknowledgement) instead of [planHash]. */
        val needsAck: Boolean = false,
        /** The token that confirms a Forget WITH the acknowledgement of a large closure ([needsAck]); equals [planHash] when none is needed. */
        val ackHash: String = "",
        /** The set is too connected to forget in one step (depth or node cap): nothing can be deleted by this scope. */
        val tooLarge: Boolean = false,
        /** `forgetV2` only (F6): the replays, sweeps, helper jobs and sensor rows this Forget invalidates, and the surviving photos (F1, F5) it queues to be described again alone. Both are part of [planHash]. */
        val sideEffects: Int = 0, val rebuiltPhotos: Int = 0)
    suspend fun preview(spans: List<LongRange>, sceneIds: Collection<String> = emptyList()): Counts {
        val merged = wholeSeconds(spans)
        if (merged.isEmpty()) return Counts(0, 0, 0, 0, 0, 0)
        return try { db.withTransaction {
            val d = discover(secondsOf(merged), merged, bounded = true)
            val e = sceneEffect(d.moments, sceneIds); val peers = peersOf(d, sceneEffect(d.moments + d.projected, sceneIds))
            val v2 = com.rokid.context.memory.forget.lifecycle.ForgetV2.on
            val sceneIdList = sceneIds.toList().distinct()
            val plan = if (v2) planV2(merged, secondsOf(merged), sceneIdList, true) else null
            val hash = if (plan != null) hashOfV2(merged, d, e, plan) else hashOf(merged, d, e); val big = peers.size > MAX_UNACKED_RESULTS
            Counts(d.frames.size, d.memories.size, d.labels.size, d.moments.size, e.removed.size, e.shrunk.size, hash, outside = d.outsideFrames.size + d.descendants.size, peerResults = peers.size,
                needsAck = big, ackHash = if (big) hash + ACK_SUFFIX else hash,
                sideEffects = plan?.nodes?.keys?.count { com.rokid.context.memory.forget.lifecycle.SideEffectRefs.isSideEffect(it) } ?: 0, rebuiltPhotos = plan?.refs(com.rokid.context.memory.forget.lifecycle.Family.PHOTO, com.rokid.context.memory.forget.lifecycle.Fate.REBUILD)?.size ?: 0)
        } } catch (e: ForgetTooLarge) { Counts(0, 0, 0, 0, 0, 0, tooLarge = true) }
    }

    /** (removed, shrunk): of the Scenes holding any of [momentIds] (and the named [sceneIds]), those left with no Moment (or named) go; the others only lose parts. */
    internal class SceneEffect(val removed: Set<String>, val shrunk: Set<String>, val membership: Map<String, List<String>>) {
        operator fun component1() = removed; operator fun component2() = shrunk
    }

    internal suspend fun sceneEffect(momentIds: Collection<String>, sceneIds: Collection<String> = emptyList()): SceneEffect {
        val named = sceneIds.toSet(); val going = momentIds.toSet()
        val affected = LinkedHashSet<String>(named)
        going.toList().chunked(CHUNK).forEach { affected += db.sceneDao().sceneIdsHoldingMoments(it) }
        if (affected.isEmpty()) return SceneEffect(emptySet(), emptySet(), emptyMap())
        val parts = affected.toList().chunked(CHUNK).flatMap { db.sceneDao().partsOf(it) }.filter { it.kind == com.rokid.context.memory.db.ScenePartKind.MOMENT }.groupBy({ it.sceneId }, { it.refId })
        val removed = affected.filter { it in named || (parts[it].orEmpty().all { r -> r in going }) }.toSet()
        return SceneEffect(removed, affected - removed, affected.associateWith { parts[it].orEmpty().sorted() })
    }

    /**
     * The lineage CLOSURE of a Forget: every key whose run results go (photos, scrubbed outside photos, marks, every Moment that is requested, stored or dropped, every Scene that goes whole, auto-removed ones included),
     * grown with the batch-wide peers of every key in it until nothing is added. Batch peers are regenerable run results, not user data: they are NOT part of the plan hash (the preview's count is an estimate); the delete
     * computes this closure once, before it deletes anything, and removes it in one call, so no per-photo step can lose the lineage a later step needs. Returns (all keys, the peers among them).
     */
    internal suspend fun closureOf(d: Found, e: SceneEffect?, sceneIds: Collection<String>, byKey: Boolean = true): Pair<Set<String>, Set<String>> {
        // the range / id Forget keeps its old behaviour: only the photos' lineage (the by-key rows are the scoped Forget's)
        val base = (if (!byKey) d.frames else d.frames + d.outsideFrames + d.labels + (d.moments + d.projected).map { "moment:$it" } + ((e?.removed ?: emptySet()) + sceneIds).map { "scene:$it" }).toSet()
        var keys = base
        while (true) {
            val more = db.canvasDao().batchPeersOf(keys.toList()) - keys; if (more.isEmpty()) break
            keys = keys + more
            if (keys.size - base.size > hardCeiling) throw ForgetTooLarge() // the lineage keeps growing: nothing is deleted
        }
        return keys to (keys - base)
    }

    /** The preview's estimate of the regenerable results that will be rebuilt (informational, never hashed). */
    private suspend fun peersOf(d: Found, e: SceneEffect): Set<String> = closureOf(d, e, emptyList()).second

    internal fun hashOf(spans: List<LongRange>, d: Found, e: SceneEffect): String = ForgetPlanHash.of(
        ForgetScope.MOMENT, spans,
        mapOf("frames" to d.frames, "memories" to d.memories, "marks" to d.labels, "moments" to d.moments, "scenesRemoved" to e.removed, "scenesShrunk" to e.shrunk, "outsideFrames" to d.outsideFrames, "descendants" to d.descendants) + e.membership.mapKeys { "scene:" + it.key }, // the FULL membership of every affected Scene
    )

    private suspend fun planV2(spans: List<LongRange>, requestedSeconds: List<Long>, sceneIds: List<String>, byKey: Boolean) =
        com.rokid.context.memory.forget.lifecycle.ForgetPlanner(db).plan(emptySet(), com.rokid.context.memory.forget.lifecycle.Policy.Spans(spans, requestedSeconds, sceneIds, byKey))

    /**
     * The `forgetV2` hash (F6, ruling 2026-11-17): the v1 sections PLUS the plan's side effects (replays, sweeps, helper jobs, sensor rows), the photos rebuilt alone (F1, F5) and the Scenes rebuilt (F3), and a plan version.
     * The version makes EVERY confirmation token of the v1 hash invalid: the user must preview again and acknowledge the larger plan. Ids only; the delete recomputes it from the same plan, so preview == delete.
     */
    internal fun hashOfV2(spans: List<LongRange>, d: Found, e: SceneEffect, plan: com.rokid.context.memory.forget.lifecycle.ForgetPlan): String = ForgetPlanHash.of(
        ForgetScope.MOMENT, spans,
        mapOf("frames" to d.frames, "memories" to d.memories, "marks" to d.labels, "moments" to d.moments, "scenesRemoved" to e.removed, "scenesShrunk" to e.shrunk, "outsideFrames" to d.outsideFrames, "descendants" to d.descendants,
            "planV" to listOf("2"),
            "sideEffects" to plan.nodes.keys.filter { com.rokid.context.memory.forget.lifecycle.SideEffectRefs.isSideEffect(it) }.map { it.toString() },
            "rebuiltPhotos" to plan.refs(com.rokid.context.memory.forget.lifecycle.Family.PHOTO, com.rokid.context.memory.forget.lifecycle.Fate.REBUILD),
            "scenesRebuilt" to plan.refs(com.rokid.context.memory.forget.lifecycle.Family.SCENE, com.rokid.context.memory.forget.lifecycle.Fate.REBUILD),
            "content" to plan.revisions.map { "${it.key}=${it.value}" }, // what each affected row says NOW: a row updated in place under the same id is a different token
        ) + e.membership.mapKeys { "scene:" + it.key },
    )

    /** The hash the delete compares with the confirmed token: v1 with the flag OFF, the plan hash with it ON. */
    internal suspend fun currentHash(spans: List<LongRange>, d: Found, e: SceneEffect, requestedSeconds: List<Long>, sceneIds: List<String>, byKey: Boolean): String =
        if (com.rokid.context.memory.forget.lifecycle.ForgetV2.on) hashOfV2(spans, d, e, planV2(spans, requestedSeconds, sceneIds, byKey)) else hashOf(spans, d, e)

    internal fun wholeSeconds(spans: List<LongRange>): List<LongRange> = mergeSpans(spans.filter { it.last >= 0 && it.last >= it.first }.map {
        val lo = Math.floorDiv(maxOf(it.first, 0L), 1000L).coerceAtMost(MomentId.MAX_SECOND); val hi = Math.floorDiv(it.last, 1000L).coerceAtMost(MomentId.MAX_SECOND)
        lo * 1000..hi * 1000 + 999
    })
    internal fun secondsOf(merged: List<LongRange>): List<Long> =
        if (merged.sumOf { it.last / 1000 - it.first / 1000 + 1 } <= MAX_TOMBSTONED_SPAN) merged.flatMap { (it.first / 1000..it.last / 1000).toList() } else emptyList()

    private fun mergeSpans(spans: List<LongRange>): List<LongRange> = com.rokid.context.memory.db.CanvasSlice3Dao.mergeRanges(spans)

    /**
     * Everything the Forget of [spans] reaches. Reads only; call it inside the Forget transaction.
     * - UNBOUNDED (range Forget, Moment ids; the old behaviour): the stored projection names extra sources, and the cascade follows what it follows.
     * - BOUNDED (Forget scope MOMENT / SCENE, round 3 design): ONLY what lies inside the spans, one query per entity type and NO closure: photos by capture time, marks by time, memories created in the spans or citing an in-span photo,
     *   and the stored Moments of those seconds (or holding those sources). What the delete merely TOUCHES outside them is counted, never deleted: [outsideFrames] (photos an in-span fact cites: locked only) and [descendants]
     *   (derived facts elsewhere that become invalid).
     */
    internal class Found(
        val frames: Set<String>, val memories: Set<String>, val labels: Set<String>, val projected: Set<String>, val moments: Set<String>,
        val outsideFrames: Set<String> = emptySet(), val descendants: Set<String> = emptySet(),
    )

    internal suspend fun discover(requestedSeconds: List<Long>, spans: List<LongRange>, bounded: Boolean = false): Found {
        val md = db.momentDao(); val fd = db.forgetDao()
        val frames = LinkedHashSet<String>(); val memories = LinkedHashSet<String>(); val labels = LinkedHashSet<String>(); val projected = LinkedHashSet<String>()
        val between = LinkedHashSet<String>()
        for (span in spans) {
            val inSpan = md.momentIdsBetween(MomentId.format(span.first / 1000), MomentId.format(span.last / 1000))
            projected += inSpan; between += inSpan
            frames += md.frameIdsBetween(span.first, span.last); frames += md.metaFrameIdsBetween(span.first, span.last)
            memories += md.memoryIdsCreatedBetween(span.first, span.last); labels += md.labelIdsBetween(span.first, span.last)
            // Codex v3 r1 P1-4: a photo retention expired has no `frame` row and, when it was a legacy one, no `frame_meta` either: its expiry marker (by capture time) still names it, so the Forget reaches its cites and history
            frames += ExpiryMarks.idsBetween(db.canvasSlice3Dao(), span.first, span.last)
        }
        requestedSeconds.forEach { projected += MomentId.format(it) }
        if (!bounded) projected.toList().chunked(CHUNK).forEach { c ->
            md.partsOf(c).forEach { p ->
                when (p.kind) {
                    MomentPartKind.FRAME -> frames += p.refId
                    MomentPartKind.MEMORY, MomentPartKind.SUMMARY -> memories += p.refId
                    MomentPartKind.MARK -> labels += p.refId
                    MomentPartKind.CITE -> frames += p.refId.removePrefix("frame:") // a cite names the photo it cites
                    else -> Unit // OTHER and the reserved SENSOR have no source store yet
                }
            }
        }
        if (bounded) { // projection sources of the scoped Forget: the FRAME parts of the Moments in the spans that name an expired photo (the marker keeps it "expired", so it is a source of the Moment even without a row)
            val fromProjection = LinkedHashSet<String>()
            between.toList().chunked(CHUNK).forEach { c -> md.partsOf(c).filter { it.kind == MomentPartKind.FRAME }.forEach { fromProjection += it.refId } }
            frames += ExpiryMarks.marked(db.canvasSlice3Dao(), fromProjection)
        }
        frames.toList().chunked(CHUNK).forEach { c -> memories += md.memoryIdsOfFrames(c) }
        // a mark LINKED to an in-set photo goes with the photo whatever its own time (the photo's Forget deletes it): it is part of the set, previewed and hashed
        if (bounded) frames.toList().chunked(CHUNK).forEach { c -> labels += md.labelIdsLinkedToFrames(c) }
        val moments = LinkedHashSet<String>(between)
        frames.toList().chunked(CHUNK).forEach { c -> moments += md.momentIdsHolding(listOf(MomentPartKind.FRAME, MomentPartKind.CITE), c + c.map { "frame:$it" }) }
        memories.toList().chunked(CHUNK).forEach { moments += md.momentIdsHolding(listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY), it) }
        labels.toList().chunked(CHUNK).forEach { moments += md.momentIdsHolding(listOf(MomentPartKind.MARK), it) }
        if (!bounded) return Found(frames, memories, labels, projected, moments)
        val ml = memories.toList()
        val cited = ml.chunked(CHUNK).flatMap { c -> fd.memories(c).mapNotNull { it.frameId } + fd.sourceFramesOf(c) }.toSet()
        val descendants = boundedDescendants(ml)
        return Found(frames, memories, labels, projected, moments, outsideFrames = cited - frames, descendants = descendants)
    }

    /** The derived facts that depend on [seed], found level by level with a HARD cap of [maxDepth] levels and [maxNodes] facts; past either, [ForgetTooLarge] (nothing is deleted). */
    private suspend fun boundedDescendants(seed: List<String>): Set<String> {
        val fd = db.forgetDao(); val seen = HashSet<String>(seed); val out = LinkedHashSet<String>()
        var frontier = seed.toList(); var depth = 0
        while (frontier.isNotEmpty()) {
            if (++depth > maxDepth) throw ForgetTooLarge()
            val next = frontier.chunked(CHUNK).flatMap { fd.childrenOf(it) }.filter { seen.add(it) }
            out += next; if (out.size > maxNodes) throw ForgetTooLarge()
            frontier = next
        }
        return out
    }

    private suspend fun run(requestedSeconds: List<Long>, spans: List<LongRange>, sceneIds: List<String> = emptyList(), byKey: Boolean = false, expectedHash: String? = null, guard: () -> Unit = {}): Result {
        var counts = Result.EMPTY
        if (com.rokid.context.memory.forget.lifecycle.ForgetV2.on) { // Forget lifecycle M4 (flag forgetV2, off by default): the one executor, same delete set
            service.notifying { counts = service.executor.forgetSpans(this, clock(), requestedSeconds, spans, sceneIds, byKey, expectedHash, guard, testFaultBeforeCommit); counts.cascade }
            return counts
        }
        service.notifying {
        db.withTransaction {
            guard() // the caller's last authorization check, inside the deleting transaction (a throw rolls everything back)
            val md = db.momentDao(); val s3 = db.canvasSlice3Dao(); val now = clock()
            // 1. Fences first: the durable range, then a tombstone per requested second.
            spans.forEach { s3.addMomentForgetRange(it.first, it.last) }
            db.momentLocationDao().deleteForMoments(requestedSeconds.map(MomentId::format)) // v18 WP2: user Forget only: the saved location of every requested second
            md.insertTombstones((requestedSeconds + spans.map { it.first / 1000 }).distinct().map { MomentTombstoneEntity(MomentId.format(it), now) }) // + the start of every span: the recency marker of a long range
            // 2. Independent discovery, unioned with the projection.
            val found = discover(requestedSeconds, spans, bounded = byKey)
            val frames = found.frames; val memories = found.memories; val labels = found.labels; val projected = found.projected
            if (expectedHash != null) { // the confirmed set must be EXACTLY the one that was previewed; anything else rolls this transaction back (fences included) and deletes nothing
                if (hashOf(spans, found, sceneEffect(found.moments, sceneIds)) != expectedHash.removeSuffix(ACK_SUFFIX)) throw ForgetContentChanged()
            }
            // the closure of the run results, computed BEFORE anything is deleted and removed in one call (the cascade below then finds nothing left to follow)
            val closure = closureOf(found, if (byKey) sceneEffect(found.moments + found.projected, sceneIds) else null, sceneIds, byKey)
            // a large closure is deleted only when the confirm token carried the explicit acknowledgement (the preview's ackHash); rolled back otherwise
            if (closure.second.size > MAX_UNACKED_RESULTS && expectedHash?.endsWith(ACK_SUFFIX) != true) throw ForgetNeedsAck()
            db.canvasDao().deleteCanvasOutputsForFrames(closure.first.toList())
            val wire = frames.toList().chunked(CHUNK).flatMap { md.wireIds(it) }.map { it.wireFrameId }
            val liveTracesBefore = s3.forgettableTraces().map { it.traceId }.toSet()
            // 3. The cascade: the existing code paths, in this transaction.
            var cascade = ForgetService.Result.EMPTY
            for (f in frames) cascade = cascade.plus(service.forgetMomentTx(f, queueOutbox = false, bounded = if (byKey) frames else null)) // the rows below are this transaction's own, written once
            val memoryIds = memories.toList()
            if (memoryIds.isNotEmpty()) cascade = cascade.plus(service.forgetFactsTx(memoryIds, queueOutbox = false, bounded = if (byKey) frames else null))
            var labelsDeleted = 0; var resultsInvalidated = 0
            spans.forEach { val o = s3.forgetRangeTx(it.first, it.last, now); labelsDeleted += o.labelsDeleted; resultsInvalidated += o.resultsInvalidated } // traces, marks of the range, labelRev, frame_forget_rev, helper jobs
            val strayLabels = labels.toList().chunked(CHUNK).flatMap { md.labelIdsAmong(it) }
            strayLabels.chunked(CHUNK).forEach { md.deleteLabels(it) }
            if (strayLabels.isNotEmpty()) s3.bumpLabelRev()
            // the rows of a run over a MARK Moment list its marks as lineage tokens (mf:<labelId>): a mark that is forgotten takes them with it
            if (labels.isNotEmpty()) s3.deleteRunRowsOfLabels(labels)
            // 4. The projection: every Moment named, in range, or holding any forgotten source.
            val dropped = HashSet<String>(projected)
            dropped += md.dropHolding(frames, memories, labels)
            md.remove(projected.toList())
            spans.forEach { dropped += md.dropBetween(it.first, it.last) }
            if (byKey && cascade.invalidated.isNotEmpty()) { // derived facts elsewhere were hidden (not deleted): the Moments that hold them survive, lose their text copy and rebuild; their derived fields are hidden
                val remote = cascade.invalidated.toList().chunked(CHUNK).flatMap { md.momentIdsHolding(listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY), it) }.distinct() - dropped
                remote.chunked(CHUNK).forEach { md.blankText(it) } // chunked: more than SQLite's bind limit of Moments may hold one hidden fact
                s3.staleMomentsHolding(listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY), cascade.invalidated, step = "forget")
            }
            if (byKey) { // Forget scope MOMENT / SCENE: whole Scenes go, and the run rows of every Moment and Scene that went are deleted by their own key too (lineage tokens alone miss e.g. a sensor-only summary)
                if (sceneIds.isNotEmpty()) db.sceneDao().remove(sceneIds)
                db.canvasDao().deleteCanvasOutputsForFrames(sceneIds.map { "scene:$it" } + dropped.map { "moment:$it" })
            }
            // 5. The durable cleanup, same transaction.
            val tombstonedTraces = liveTracesBefore - s3.forgettableTraces().map { it.traceId }.toSet()
            val inserted = CleanupOutbox.queue(md, now, cascade.framesToDelete, wire, tombstonedTraces, spans)
            testFaultBeforeCommit?.invoke()
            counts = Result(requestedSeconds.size, frames.size, memories.size, labels.size, dropped.size, inserted, cascade, tombstonedTraces.toList(), labelsDeleted, resultsInvalidated, closure.second.size)
            cascade
        }
        }
        return counts
    }

    private fun ForgetService.Result.plus(o: ForgetService.Result) = ForgetService.Result(
        forgotten + o.forgotten, invalidated + o.invalidated, redactedAsks + o.redactedAsks, redactedNudges + o.redactedNudges,
        (framesToDelete + o.framesToDelete).distinct(), (redactedProposals + o.redactedProposals).distinct(), expiredFrames + o.expiredFrames,
        (redactedAskIds + o.redactedAskIds).distinct(),
    )

    companion object {
        private const val CHUNK = 500
        /** A Moment/Scene Forget that would rebuild more regenerable results than this needs an explicit acknowledgement (in the confirm token). */
        const val MAX_UNACKED_RESULTS = 200
        /** Past this many the lineage is too connected to delete in one step: [ForgetTooLarge]. */
        const val HARD_CEILING_RESULTS = 5_000
        /** Appended to the plan hash by the preview's `ackHash`: the user acknowledged the large rebuild. */
        const val ACK_SUFFIX = "+ack"
        /** A range up to this many seconds gets one `moment_tombstone` per second; a longer one is fenced by the durable range alone (the builder and the frame commit both check it). */
        const val MAX_TOMBSTONED_SPAN = 3600L
        /** Hard caps of the derived-fact walk of the bounded scopes (levels, facts). Variables only so a test can lower them. */
        @Volatile internal var hardCeiling = HARD_CEILING_RESULTS
        @Volatile internal var maxDepth = 32
        @Volatile internal var maxNodes = 5_000
    }
}
