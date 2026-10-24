package com.rokid.context.memory.forget

import androidx.room.withTransaction
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.db.CleanupOutboxEntity
import com.rokid.context.memory.db.CleanupPayload
import com.rokid.context.memory.db.CleanupTarget
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

    private fun mergeSpans(spans: List<LongRange>): List<LongRange> = com.rokid.context.memory.db.CanvasSlice3Dao.mergeRanges(spans)

    private suspend fun run(requestedSeconds: List<Long>, spans: List<LongRange>): Result {
        var counts = Result.EMPTY
        service.notifying {
        db.withTransaction {
            val md = db.momentDao(); val s3 = db.canvasSlice3Dao(); val now = clock()
            // 1. Fences first: the durable range, then a tombstone per requested second.
            spans.forEach { s3.addMomentForgetRange(it.first, it.last) }
            md.insertTombstones((requestedSeconds + spans.map { it.first / 1000 }).distinct().map { MomentTombstoneEntity(MomentId.format(it), now) }) // + the start of every span: the recency marker of a long range
            // 2. Independent discovery, unioned with the projection.
            val frames = LinkedHashSet<String>(); val memories = LinkedHashSet<String>(); val labels = LinkedHashSet<String>(); val projected = LinkedHashSet<String>()
            for (span in spans) {
                projected += md.momentIdsBetween(MomentId.format(span.first / 1000), MomentId.format(span.last / 1000))
                frames += md.frameIdsBetween(span.first, span.last); frames += md.metaFrameIdsBetween(span.first, span.last)
                memories += md.memoryIdsCreatedBetween(span.first, span.last); labels += md.labelIdsBetween(span.first, span.last)
            }
            requestedSeconds.forEach { projected += MomentId.format(it) }
            projected.toList().chunked(CHUNK).forEach { c ->
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
            frames.toList().chunked(CHUNK).forEach { c -> memories += md.memoryIdsOfFrames(c) }
            val wire = frames.toList().chunked(CHUNK).flatMap { md.wireIds(it) }.map { it.wireFrameId }
            val liveTracesBefore = s3.liveTraces().map { it.traceId }.toSet()
            // 3. The cascade: the existing code paths, in this transaction.
            var cascade = ForgetService.Result.EMPTY
            for (f in frames) cascade = cascade.plus(service.forgetMomentTx(f))
            val memoryIds = memories.toList()
            if (memoryIds.isNotEmpty()) cascade = cascade.plus(service.forgetFactsTx(memoryIds))
            spans.forEach { s3.forgetRangeTx(it.first, it.last, now) } // traces, marks of the range, labelRev, frame_forget_rev, helper jobs
            val strayLabels = labels.toList().chunked(CHUNK).flatMap { md.labelIdsAmong(it) }
            strayLabels.chunked(CHUNK).forEach { md.deleteLabels(it) }
            if (strayLabels.isNotEmpty()) s3.bumpLabelRev()
            // 4. The projection: every Moment named, in range, or holding any forgotten source.
            val dropped = HashSet<String>(projected)
            dropped += md.dropHolding(frames, memories, labels)
            md.remove(projected.toList())
            spans.forEach { dropped += md.dropBetween(it.first, it.last) }
            // 5. The durable cleanup, same transaction.
            val tombstonedTraces = liveTracesBefore - s3.liveTraces().map { it.traceId }.toSet()
            val rows = ArrayList<CleanupOutboxEntity>()
            fun row(target: String, json: String) = rows.add(CleanupOutboxEntity(target = target, payloadJson = json, createdAt = now))
            fun one(key: String, v: String) = CleanupPayload.one(key, v)
            cascade.framesToDelete.distinct().sorted().forEach { row(CleanupTarget.PHONE_FILE, one("path", it)) }
            wire.distinct().sorted().forEach { row(CleanupTarget.GLASSES_FRAME, one("wireFrameId", it)) }
            tombstonedTraces.sorted().forEach { row(CleanupTarget.GLASSES_TRACE, one("traceId", it)) }
            spans.forEach { row(CleanupTarget.GLASSES_RANGE, CleanupPayload.range(it.first, it.last)) }
            val inserted = md.insertOutbox(rows).count { it != -1L }
            testFaultBeforeCommit?.invoke()
            counts = Result(requestedSeconds.size, frames.size, memories.size, labels.size, dropped.size, inserted, cascade)
            cascade
        }
        }
        return counts
    }

    private fun ForgetService.Result.plus(o: ForgetService.Result) = ForgetService.Result(
        forgotten + o.forgotten, invalidated + o.invalidated, redactedAsks + o.redactedAsks, redactedNudges + o.redactedNudges,
        (framesToDelete + o.framesToDelete).distinct(), (redactedProposals + o.redactedProposals).distinct(), expiredFrames + o.expiredFrames,
    )

    companion object {
        private const val CHUNK = 500
        /** A range up to this many seconds gets one `moment_tombstone` per second; a longer one is fenced by the durable range alone (the builder and the frame commit both check it). */
        const val MAX_TOMBSTONED_SPAN = 3600L
    }
}
