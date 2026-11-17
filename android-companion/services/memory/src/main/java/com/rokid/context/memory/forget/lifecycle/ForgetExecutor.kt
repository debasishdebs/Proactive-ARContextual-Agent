package com.rokid.context.memory.forget.lifecycle

import androidx.room.withTransaction
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.MomentTombstoneEntity
import com.rokid.context.memory.forget.CleanupOutbox
import com.rokid.context.memory.forget.ForgetContentChanged
import com.rokid.context.memory.forget.ForgetNeedsAck
import com.rokid.context.memory.forget.ForgetRadius
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
import com.rokid.context.memory.moment.MomentId

/**
 * The process-wide switch of the destructive switch-over (Forget lifecycle M4, user ruling 2026-11-17): OFF by default, so every Forget runs the legacy code unchanged. The orchestration layer installs [provider]
 * from `DeveloperFlags.forgetV2` (the backup-excluded `pc_dev` preferences); a test sets [testOverride]. It is read once at the start of each Forget.
 */
object ForgetV2 {
    /** The Developer-mode preference file (`pc_dev`: excluded from backup and device transfer, never exported) and the key of the flag. `DeveloperFlags.forgetV2` reads and writes the same. */
    const val PREFS = "pc_dev"
    const val KEY = "forget_v2"
    @Volatile var provider: () -> Boolean = { false }
    @Volatile private var app: android.content.Context? = null
    /** Reads the flag from the preferences on every Forget (cheap: cached by the platform). Installed once by `MemoryGraph.get`; never throws, OFF on any failure. */
    fun install(context: android.content.Context) {
        val a = context.applicationContext ?: return
        if (app === a) return
        app = a
        provider = { a.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY, false) }
    }
    @Volatile var testOverride: Boolean? = null
    val on: Boolean get() = testOverride ?: runCatching { provider() }.getOrDefault(false)
}

/**
 * The single place that deletes (design 2.4), behind [ForgetV2]. Every v2 Forget is ONE transaction: re-plan with the [ForgetPlanner] (reads only), revoke the read leases the plan reaches ([Leases.revoke]; a planner
 * failure revokes them ALL, fail closed), then the families' [ForgetFamily.selfDelete] in the order of the v1 cascade, then the durable outbox rows. What gets deleted is EXACTLY the v1 set: the golden delete-set tests
 * run with the flag OFF and ON ([DesignDivergence] lists where the design would differ and was not implemented). The legacy bodies stay in [ForgetService] and [MomentForget] (flag OFF) until the user rules.
 *
 * The plan is used here for the revocation and (scoped Forget) the confirmed hash; the delete reads the same database state in the same transaction and follows the v1 steps, because several of them are order dependent
 * (a photo deleted earlier in the Forget has no capture time left for the radius). Ids only; nothing here logs or holds text.
 */
class ForgetExecutor internal constructor(
    private val db: ContextDatabase,
    private val proposals: ProposalRepository,
    private val clock: () -> Long,
    private val service: ForgetService,
    private val leases: Leases = Leases.shared,
) {
    private val dao = db.forgetDao()
    private fun ctx(now: Long = clock()) = DeleteCtx(db, proposals, now) { f, s -> service.addPending(f, s) }

    /** Re-plans inside the transaction and revokes what the plan reaches. Never throws except cancellation: a planner failure revokes every lease. */
    private suspend fun revoke(plan: suspend (ForgetPlanner) -> ForgetPlan): Int = try {
        leases.revoke(plan(ForgetPlanner(db)))
    } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c
    } catch (e: Exception) { leases.revokeAll() }

    // ---- fact, photo ------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    /** Forget fact for [ids] (`ForgetService.forgetFactsTx`). [top] false when a caller already re-planned in this transaction. */
    internal suspend fun forgetFacts(ids: Collection<String>, lockFrames: Boolean = true, queueOutbox: Boolean = true, bounded: Set<String>? = null, top: Boolean = true): ForgetService.Result = db.withTransaction {
        if (top) revoke { it.plan(ids.map { id -> Ref(Family.MEMORY, id) }.toSet(), Policy.User) }
        cascade(ctx(), ids.distinct(), lockFrames, queueOutbox = queueOutbox, bounded = bounded)
    }

    /** Forget moment (`ForgetService.forgetMomentTx`): the photo, its memories, cites, tombstone and canvas outputs. */
    internal suspend fun forgetPhoto(frameId: String, redactCites: Boolean = true, queueOutbox: Boolean = true, phoneFilesOnly: Boolean = false, bounded: Set<String>? = null, top: Boolean = true): ForgetService.Result = db.withTransaction {
        if (top) revoke { it.plan(setOf(Ref(Family.PHOTO, frameId)), Policy.User) }
        val c = ctx()
        val radiusCascade = bounded == null
        val cites = if (redactCites) setOf(PHOTO_CITE_PREFIX + frameId) else emptySet()
        val frame = dao.frame(frameId)
        val ids = dao.memoryIdsOfFrame(frameId)
        // CARRIED memories (a batch mate's caption) are a provenance invalidation: their text and descendants go, but the photos THEY cite are never locked, tombstoned or deleted (v1; the design would REBUILD them)
        val carried = dao.carriedMemoryIdsOfFrame(frameId) - ids.toSet()
        val invalidatedCarried = if (carried.isEmpty()) ForgetService.Result.EMPTY else cascade(c, carried, lockFrames = false, provenanceOnly = true, queueOutbox = false)
        if (frame == null && ids.isEmpty() && dao.photoRowCount(frameId) == 0) {
            dropCanvasOutputs(c, listOf(frameId), null, radiusCascade)
            return@withTransaction cascade(c, emptyList(), lockFrames = true, extraCited = cites, bounded = bounded).with(invalidatedCarried)
        }
        val r = cascade(c, ids, lockFrames = true, extraCited = cites, queueOutbox = queueOutbox, phoneFilesOnly = phoneFilesOnly, bounded = bounded)
        val wire = db.momentDao().wireIds(listOf(frameId)).map { it.wireFrameId } // read before frame_meta goes
        PhotoFamily.lock(listOf(frameId), c)
        MomentFamily.dropHolding(c, listOf(frameId), ids)
        val spans = dropCanvasOutputs(c, listOf(frameId), listOfNotNull(frame?.ts), radiusCascade)
        PhotoFamily.selfDelete(setOf(frameId), Fate.ERASE, c)
        val files = (r.framesToDelete + listOfNotNull(frame?.path?.takeIf { it.isNotBlank() }) + listOfNotNull(frame?.path?.takeIf { it.isNotBlank() }?.let(::sidecarOf))).distinct()
        val out = r.copy(framesToDelete = files).with(invalidatedCarried)
        if (queueOutbox) queueCleanup(c, out.framesToDelete, wire, spans, phoneFilesOnly)
        out
    }

    // ---- retention --------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    /** Retention expiry of memories (`ForgetService.expireTx`): derived memories are left alone, accepted proposals keep their params, keyframes are kept. */
    internal suspend fun expire(ids: Collection<String>): ForgetService.Result = db.withTransaction {
        revoke { it.plan(ids.map { id -> Ref(Family.MEMORY, id) }.toSet(), Policy.Retention) }
        cascade(ctx(), ids.distinct(), lockFrames = true, invalidateDescendants = false, scrubAccepted = false, keepKeyframes = true, phoneFilesOnly = true)
    }

    /** Row 8, suspect descriptions: the memories go, the photos never do (`ForgetService.removeDescriptions`). The plan used for the revocation is the user plan (a superset: fail closed). */
    internal suspend fun removeDescriptions(ids: Collection<String>): ForgetService.Result = db.withTransaction {
        revoke { it.plan(ids.map { id -> Ref(Family.MEMORY, id) }.toSet(), Policy.User) }
        cascade(ctx(), ids.distinct(), lockFrames = false, invalidateDescendants = true, scrubAccepted = false, keepKeyframes = true, phoneFilesOnly = true)
    }

    /** Photo expiry (`ForgetService.expirePhotosTx`, Ruling 11): the frame row goes with a tombstone, everything described about it stays. */
    internal suspend fun expirePhotos(frameIds: List<String>, requireLinked: Boolean = false, recheckSkipped: Boolean = false): ForgetService.Result = db.withTransaction {
        revoke { it.plan(frameIds.map { id -> Ref(Family.PHOTO, id) }.toSet(), Policy.RetentionPhotos(requireLinked, recheckSkipped)) }
        val c = ctx()
        val paths = mutableListOf<String>(); val expiredIds = mutableListOf<String>(); val expiredTimes = mutableListOf<Long>()
        for (id in frameIds.distinct()) {
            val frame = dao.frame(id) ?: continue
            if (requireLinked && dao.describedRefs(id) == 0) continue
            if (recheckSkipped && dao.stillSkippedPhoto(id) == 0) continue
            expiredIds += id; expiredTimes += frame.ts
            PhotoFamily.lock(listOf(id), c)
            PhotoFamily.expireRows(listOf(id), c)
            frame.path.takeIf { it.isNotBlank() }?.let { paths += it; paths += sidecarOf(it) }
        }
        MomentFamily.dropHolding(c, expiredIds, emptyList()) // a Moment with no live photo is not a Moment
        val wire = expiredIds.chunked(CHUNK).flatMap { db.momentDao().wireIds(it) }.map { it.wireFrameId } // frame_meta stays on expiry
        val spans = dropCanvasOutputs(c, expiredIds, expiredTimes)
        queueCleanup(c, paths, wire, spans, phoneFilesOnly = true) // routine retention is storage hygiene: phone files only
        ForgetService.Result.EMPTY.copy(framesToDelete = paths.distinct(), expiredFrames = expiredIds.size)
    }

    // ---- marks ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    /** The mark undo (`MarkRecorder.removeMark`): each mark with its intent row, the results computed under it, `labelRev`, the stale Moments, the pruned claims. */
    suspend fun deleteMarks(ids: List<String>) = db.withTransaction {
        revoke { it.plan(ids.map { id -> Ref(Family.MARK, id) }.toSet(), Policy.User) }
        MarkFamily.selfDelete(ids.toSet(), Fate.ERASE, ctx())
    }

    // ---- the cascade ------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    /** `ForgetService.cascade`: F (the memories), D (their descendants), the Moments holding them, the extraction lock, the redaction of what cited them. */
    private suspend fun cascade(
        c: DeleteCtx, ids: List<String>, lockFrames: Boolean, invalidateDescendants: Boolean = true, scrubAccepted: Boolean = true, keepKeyframes: Boolean = false, extraCited: Set<String> = emptySet(),
        queueOutbox: Boolean = true, phoneFilesOnly: Boolean = false, bounded: Set<String>? = null, provenanceOnly: Boolean = false,
    ): ForgetService.Result {
        val found = ids.chunked(CHUNK).flatMap { dao.memories(it) }
        if (found.isEmpty() && extraCited.isEmpty()) return ForgetService.Result.EMPTY
        val forgotten = found.map { it.id }.toSet()
        val citedFrames = (found.mapNotNull { it.frameId } + forgotten.toList().chunked(CHUNK).flatMap { dao.sourceFramesOf(it) }).distinct()
        val frames = if (provenanceOnly) emptyList() else if (keepKeyframes) citedFrames - citedFrames.chunked(CHUNK).flatMap { dao.keyframeIds(it) }.toSet() else citedFrames
        val invalidated = if (!invalidateDescendants) emptySet() else forgotten.chunked(CHUNK).flatMap { dao.descendants(it) }.toSet() - forgotten
        MemoryFamily.selfDelete(forgotten, Fate.ERASE, c)
        MemoryFamily.selfDelete(invalidated, Fate.REBUILD, c)
        val radiusCascade = bounded == null
        val inside = if (bounded == null) frames else frames.filter { it in bounded }
        MomentFamily.dropHolding(c, inside, if (bounded == null) forgotten + invalidated else forgotten)
        var spans = emptyList<Triple<Long, Long, List<String>>>()
        if (lockFrames) {
            PhotoFamily.lock(frames, c); spans = dropCanvasOutputs(c, inside, null, radiusCascade)
            if (bounded != null) { val outside = frames - inside.toSet(); if (outside.isNotEmpty()) RunResultFamily.scrubLocked(c, outside) }
        }
        val uncited = inside.filter { dao.remainingRefs(it) == 0 } // the photo goes only when no surviving memory cites it
        val goneCites = if (scrubAccepted) uncited.map { PHOTO_CITE_PREFIX + it } else emptyList()
        val cited = forgotten + invalidated + extraCited + goneCites
        val tx = PlanTx(db)
        val asks = AnswerFamily.citing(cited, tx); val nudges = NudgeFamily.citing(cited, tx)
        AnswerFamily.selfDelete(asks.toSet(), Fate.REBUILD, c)
        NudgeFamily.selfDelete(nudges.toSet(), Fate.REBUILD, c)
        val redactedProposals = ProposalFamily.expireAndScrub(c, asks + nudges, scrubAccepted)
        PhotoFamily.deleteVectors(uncited, c)
        val files = uncited.mapNotNull { dao.frame(it)?.path?.takeIf { p -> p.isNotBlank() } }
        if (queueOutbox) queueCleanup(c, files, uncited.chunked(CHUNK).flatMap { db.momentDao().wireIds(it) }.map { it.wireFrameId }, spans, phoneFilesOnly)
        return ForgetService.Result(forgotten, invalidated, asks.size, nudges.size, files, redactedProposals, redactedAskIds = asks)
    }

    /** `ForgetService.dropCanvasOutputs`: the marks and traces within the radius of each photo (a durable range fence), the run results and the slice outputs of the photos. Returns the spans fenced. */
    private suspend fun dropCanvasOutputs(c: DeleteCtx, frameIds: List<String>, capturedAtOrNull: List<Long>? = null, radiusCascade: Boolean = true): List<Triple<Long, Long, List<String>>> {
        val capturedAt = capturedAtOrNull ?: frameIds.mapNotNull { dao.frame(it)?.ts }
        val radius = ForgetRadius.ms() // read once per Forget
        val spans = (if (radiusCascade) capturedAt else emptyList()).map { (it - radius)..(it + radius) }.let(CanvasSlice3Dao::mergeRanges).map { r ->
            val o = TraceFamily.eraseRange(c, r.first, r.last)
            MomentFamily.dropBetween(c, r.first, r.last)
            Triple(r.first, r.last, o.tombstonedTraceIds)
        }
        if (frameIds.isNotEmpty() || spans.isNotEmpty()) c.notifyAfterCommit(frameIds, spans)
        RunResultFamily.selfDelete(frameIds.toSet(), Fate.DROP, c)
        RunResultFamily.dropSliceOutputs(c, frameIds)
        return spans
    }

    private suspend fun queueCleanup(c: DeleteCtx, files: Collection<String>, wireFrameIds: Collection<String>, spans: List<Triple<Long, Long, List<String>>>, phoneFilesOnly: Boolean = false) {
        if (phoneFilesOnly) CleanupOutbox.queue(db.momentDao(), c.now, files)
        else CleanupOutbox.queue(db.momentDao(), c.now, files, wireFrameIds, spans.flatMap { it.third }, spans.map { it.first..it.second })
    }

    private fun sidecarOf(jpegPath: String) = jpegPath.removeSuffix(".jpg") + ".meta.json"

    // ---- range, Moment ids, Moment / Scene scope ---------------------------------------------------------------------------------------------------------------------------------------------

    /** `MomentForget.run`: the fences, the discovery, the closure of the run results, then the cascades and the projection, in ONE transaction. [testFault] runs after everything was written (a test throws there). */
    internal suspend fun forgetSpans(
        moments: MomentForget, now: Long, requestedSeconds: List<Long>, spans: List<LongRange>, sceneIds: List<String>, byKey: Boolean, expectedHash: String?, guard: () -> Unit, testFault: (() -> Unit)?,
    ): MomentForget.Result = db.withTransaction {
        guard() // the caller's last authorization check, inside the deleting transaction
        revoke { it.plan(emptySet(), Policy.Spans(spans, requestedSeconds, sceneIds, byKey)) }
        val md = db.momentDao(); val s3 = db.canvasSlice3Dao(); val c = ctx(now) // [now] is the MomentForget's own clock reading, as in v1
        // 1. Fences first: the durable range, then a tombstone per requested second (and the start of every span: the recency marker of a long range).
        spans.forEach { s3.addMomentForgetRange(it.first, it.last) }
        md.insertTombstones((requestedSeconds + spans.map { it.first / 1000 }).distinct().map { MomentTombstoneEntity(MomentId.format(it), now) })
        // 2. Independent discovery, unioned with the projection.
        val found = moments.discover(requestedSeconds, spans, bounded = byKey)
        val frames = found.frames; val memories = found.memories; val labels = found.labels; val projected = found.projected
        if (expectedHash != null && moments.hashOf(spans, found, moments.sceneEffect(found.moments, sceneIds)) != expectedHash.removeSuffix(MomentForget.ACK_SUFFIX)) throw ForgetContentChanged()
        val closure = moments.closureOf(found, if (byKey) moments.sceneEffect(found.moments + found.projected, sceneIds) else null, sceneIds, byKey)
        if (closure.second.size > MomentForget.MAX_UNACKED_RESULTS && expectedHash?.endsWith(MomentForget.ACK_SUFFIX) != true) throw ForgetNeedsAck()
        RunResultFamily.selfDelete(closure.first, Fate.DROP, c)
        val wire = frames.toList().chunked(CHUNK).flatMap { md.wireIds(it) }.map { it.wireFrameId }
        val liveTracesBefore = s3.forgettableTraces().map { it.traceId }.toSet()
        // 3. The cascades, in this transaction.
        var cascade = ForgetService.Result.EMPTY
        for (f in frames) cascade = cascade.with(forgetPhoto(f, queueOutbox = false, bounded = if (byKey) frames else null, top = false))
        if (memories.isNotEmpty()) cascade = cascade.with(forgetFacts(memories.toList(), queueOutbox = false, bounded = if (byKey) frames else null, top = false))
        var labelsDeleted = 0; var resultsInvalidated = 0
        spans.forEach { val o = TraceFamily.eraseRange(c, it.first, it.last); labelsDeleted += o.labelsDeleted; resultsInvalidated += o.resultsInvalidated }
        MarkFamily.deleteFound(c, labels, labels) // stray marks, then the run rows that list them as lineage tokens (`mf:<labelId>`)
        // 4. The projection: every Moment named, in range, or holding any forgotten source.
        val dropped = HashSet<String>(projected)
        dropped += MomentFamily.dropHolding(c, frames, memories, labels)
        MomentFamily.selfDelete(projected, Fate.DROP, c)
        spans.forEach { dropped += MomentFamily.dropBetween(c, it.first, it.last) }
        if (byKey && cascade.invalidated.isNotEmpty()) { // derived facts elsewhere were hidden: the Moments that hold them survive, lose their text copy and rebuild
            val remote = cascade.invalidated.toList().chunked(CHUNK).flatMap { md.momentIdsHolding(listOf(MomentPartKind.MEMORY, MomentPartKind.SUMMARY), it) }.distinct() - dropped
            MomentFamily.blankAndStale(c, remote, cascade.invalidated)
        }
        if (byKey) { // whole Scenes go, and the run rows of every Moment and Scene that went are deleted by their own key too
            if (sceneIds.isNotEmpty()) SceneFamily.selfDelete(sceneIds.toSet(), Fate.DROP, c)
            RunResultFamily.selfDelete((sceneIds.map { "scene:$it" } + dropped.map { "moment:$it" }).toSet(), Fate.DROP, c)
        }
        // 5. The durable cleanup, same transaction.
        val tombstonedTraces = liveTracesBefore - s3.forgettableTraces().map { it.traceId }.toSet()
        val inserted = CleanupOutbox.queue(md, now, cascade.framesToDelete, wire, tombstonedTraces, spans)
        testFault?.invoke()
        MomentForget.Result(requestedSeconds.size, frames.size, memories.size, labels.size, dropped.size, inserted, cascade, tombstonedTraces.toList(), labelsDeleted, resultsInvalidated, closure.second.size)
    }

    companion object {
        private const val CHUNK = 500
        /** The mark undo for callers that hold only the database (`MarkRecorder`): one transaction, re-plan, revoke, then the marks go. */
        /**
         * Delete all (`MemoryWipe`, allowlisted: it clears every table and the files): revokes EVERY read lease, then wipes the memory tables in one transaction. Returns the proposal ids whose alarms and notifications must go.
         * [leases] is the registry of the gate that holds the exclusive side.
         */
        suspend fun wipeMemory(db: ContextDatabase, leases: Leases = Leases.shared): List<String> { leases.revokeAll(); return db.wipeDao().wipeMemory() }
        suspend fun deleteMarks(db: ContextDatabase, ids: List<String>) = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)).executor.deleteMarks(ids)
    }
}
