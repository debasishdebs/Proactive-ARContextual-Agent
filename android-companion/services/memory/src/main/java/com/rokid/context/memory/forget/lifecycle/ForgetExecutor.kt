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
import com.rokid.context.memory.forget.ForgetTooLarge
import com.rokid.context.memory.forget.ExpiredLineage
import com.rokid.context.memory.forget.ExpiryMarks
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
    private fun ctx(now: Long = clock()) = DeleteCtx(db, proposals, now) { f, s, e -> service.addPending(f, s, e) }

    /** Re-plans inside the transaction and revokes what the plan reaches. Never throws except cancellation: a planner failure revokes every lease. */
    private suspend fun revoke(plan: suspend (ForgetPlanner) -> ForgetPlan): ForgetPlan? = try {
        plan(ForgetPlanner(db)).also { leases.revoke(it) }
    } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c
    } catch (e: Exception) { leases.revokeAll(); null }

    /**
     * F3 (`forgetV2`, ruling 2026-11-17): the Scenes the [plan] says are REBUILT (they survive with the Moments that are left) lose their old derived text NOW (v1 only hid it); the derive pass writes it again from the survivors.
     * A Scene that is empty or named is DROPPED by the v1 steps. No plan (the planner failed): every hidden Scene field is erased, fail closed.
     */
    private suspend fun rebuildScenes(c: DeleteCtx, plan: ForgetPlan?) {
        if (!ForgetV2.on) return
        if (plan == null) { c.db.sceneDao().eraseStaleSceneDerived(); return }
        val ids = plan.refs(Family.SCENE, Fate.REBUILD)
        if (ids.isNotEmpty()) SceneFamily.selfDelete(ids, Fate.REBUILD, c)
    }

    // ---- fact, photo ------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    /** Forget fact for [ids] (`ForgetService.forgetFactsTx`). [top] false when a caller already re-planned in this transaction. */
    internal suspend fun forgetFacts(ids: Collection<String>, lockFrames: Boolean = true, queueOutbox: Boolean = true, bounded: Set<String>? = null, top: Boolean = true, protectAll: Set<String>? = null): ForgetService.Result = db.withTransaction {
        val plan = if (top) revoke { it.plan(ids.map { id -> Ref(Family.MEMORY, id) }.toSet(), Policy.User) } else null
        val c = ctx()
        cascade(c, ids.distinct(), lockFrames, queueOutbox = queueOutbox, bounded = bounded, protect = protectAll).also { if (top) rebuildScenes(c, plan) }
    }

    /** Forget moment (`ForgetService.forgetMomentTx`): the photo, its memories, cites, tombstone and canvas outputs. */
    internal suspend fun forgetPhoto(frameId: String, redactCites: Boolean = true, queueOutbox: Boolean = true, phoneFilesOnly: Boolean = false, bounded: Set<String>? = null, top: Boolean = true, protectAll: Set<String>? = null): ForgetService.Result = db.withTransaction {
        val plan = if (top) revoke { it.plan(setOf(Ref(Family.PHOTO, frameId)), Policy.User) } else null
        val c = ctx()
        // F1 / F5 (`forgetV2`): the photos this Forget forgets; a photo a memory ALSO cites (carried or shared) is a survivor: never locked or deleted, described again alone
        val protect = if (ForgetV2.on) (protectAll ?: setOf(frameId)) else null
        val radiusCascade = bounded == null
        val cites = if (redactCites) setOf(PHOTO_CITE_PREFIX + frameId) else emptySet()
        val frame = dao.frame(frameId)
        val capturedAt = ExpiryMarks.captureTs(dao, db.canvasSlice3Dao(), listOf(frameId)) // retention v3 (r2-4): read before the rows and the expiry marker go
        val ids = dao.memoryIdsOfFrame(frameId)
        // CARRIED memories (a batch mate's caption) are a provenance invalidation: their text and descendants go, but the photos THEY cite are never locked, tombstoned or deleted (v1; the design would REBUILD them)
        val carried = dao.carriedMemoryIdsOfFrame(frameId) - ids.toSet()
        val invalidatedCarried = if (carried.isEmpty()) ForgetService.Result.EMPTY else cascade(c, carried, lockFrames = false, provenanceOnly = true, queueOutbox = false, protect = protect)
        if (frame == null && ids.isEmpty() && dao.photoRowCount(frameId) == 0) {
            dropCanvasOutputs(c, listOf(frameId), capturedAt, radiusCascade)
            PhotoFamily.unmark(listOf(frameId), c) // retention v3 (r2-3): an expired photo with nothing left is still forgotten by this Forget
            return@withTransaction cascade(c, emptyList(), lockFrames = true, extraCited = cites, bounded = bounded, protect = protect, lineageFrames = listOf(frameId)).with(invalidatedCarried).also { if (top) rebuildScenes(c, plan) }
        }
        val r = cascade(c, ids, lockFrames = true, extraCited = cites, queueOutbox = queueOutbox, phoneFilesOnly = phoneFilesOnly, bounded = bounded, protect = protect, lineageFrames = listOf(frameId))
        val wire = db.momentDao().wireIds(listOf(frameId)).map { it.wireFrameId } // read before frame_meta goes
        PhotoFamily.lock(listOf(frameId), c)
        MomentFamily.dropHolding(c, listOf(frameId), ids)
        val spans = dropCanvasOutputs(c, listOf(frameId), capturedAt, radiusCascade)
        PhotoFamily.unmark(listOf(frameId), c) // retention v3 (r2-3): a forgotten photo is never read as expired
        PhotoFamily.selfDelete(setOf(frameId), Fate.ERASE, c)
        val files = (r.framesToDelete + listOfNotNull(frame?.path?.takeIf { it.isNotBlank() }) + listOfNotNull(frame?.path?.takeIf { it.isNotBlank() }?.let(::sidecarOf))).distinct()
        val out = r.copy(framesToDelete = files).with(invalidatedCarried)
        if (top) rebuildScenes(c, plan)
        if (queueOutbox) queueCleanup(c, out.framesToDelete, wire, spans, phoneFilesOnly)
        out
    }

    // ---- multi-select (activity pagination A8, ruling P2) ----------------------------------------------------------------------------------------------------------------------------------

    /**
     * The multi-select Forget of [roots] (photos, memories, marks), in ONE transaction: re-plan as a user Forget, refuse a selection or closure above [SelectionLimits.REFUSE_ABOVE] ([ForgetTooLarge]), check the
     * confirmed [token] against the plan just taken ([ForgetContentChanged] when anything differs: nothing was deleted), require the acknowledgement of a large one ([ForgetNeedsAck]), revoke the read leases the plan
     * reaches, then run the single-item cascades in the planner's own order (memories, photos, marks) so what is deleted is what the plan named. Everything below is the v1 / v2 step code, not a copy of it.
     */
    internal suspend fun forgetSelection(roots: Set<Ref>, token: String): ForgetService.Result {
        if (roots.size > SelectionLimits.REFUSE_ABOVE) throw ForgetTooLarge()
        return db.withTransaction {
            val plan = try { ForgetPlanner(db).plan(roots, Policy.User) } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c
            } catch (e: ForgetTooLarge) { throw e
            } catch (e: Exception) { leases.revokeAll(); throw IllegalStateException("plan failed") } // fail closed: nothing is deleted
            if (ForgetSelection.tooLarge(roots, plan)) throw ForgetTooLarge()
            if (token.removeSuffix(SelectionLimits.ACK_SUFFIX) != ForgetSelection.hashOf(roots, plan)) throw ForgetContentChanged()
            if (ForgetSelection.needsAck(roots, plan) && !token.endsWith(SelectionLimits.ACK_SUFFIX)) throw ForgetNeedsAck()
            leases.revoke(plan)
            val c = ctx()
            var out = ForgetService.Result.EMPTY
            val memories = roots.filter { it.family == Family.MEMORY }.map { it.id }.distinct()
            if (memories.isNotEmpty()) out = out.with(forgetFacts(memories, top = false))
            for (f in roots.filter { it.family == Family.PHOTO }.map { it.id }.distinct()) out = out.with(forgetPhoto(f, top = false))
            val marks = roots.filter { it.family == Family.MARK }.map { it.id }.distinct()
            if (marks.isNotEmpty()) MarkFamily.selfDelete(marks.toSet(), Fate.ERASE, c)
            rebuildScenes(c, plan) // F3
            out
        }
    }

    // ---- retention --------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    /**
     * Retention v3 DEMOTION (the one place a retention step removes memory data): the TEXT VECTORS of SHORT facts created before [beforeMs] (never a caption or a cluster summary), at most [limit] facts, in ONE transaction. The memory
     * rows, their text, edges, frame links and every citation are NEVER touched: only the vectors go, so the facts leave the semantic pool and the scan stays bounded. Returns how many facts were demoted.
     */
    internal suspend fun demoteFacts(beforeMs: Long, limit: Int): Int = db.withTransaction {
        val ids = dao.demotableFactIds(beforeMs, limit)
        ids.chunked(CHUNK).forEach { dao.deleteVectorsFor(it) }
        ids.size
    }

    /** Row 8, suspect descriptions: the memories go, the photos never do (`ForgetService.removeDescriptions`). The plan used for the revocation is the user plan (a superset: fail closed). */
    internal suspend fun removeDescriptions(ids: Collection<String>): ForgetService.Result = db.withTransaction {
        val plan = revoke { it.plan(ids.map { id -> Ref(Family.MEMORY, id) }.toSet(), Policy.User) }
        val c = ctx()
        cascade(c, ids.distinct(), lockFrames = false, invalidateDescendants = true, scrubAccepted = false, keepKeyframes = true, phoneFilesOnly = true).also { rebuildScenes(c, plan) }
    }

    /** Photo expiry (`ForgetService.expirePhotosTx`, Ruling 11): the frame row goes with a tombstone, everything described about it stays. Storage only (retention r1): no Moment, Scene, mark, trace or run output is touched and no radius cascade runs. */
    internal suspend fun expirePhotos(frameIds: List<String>, requireLinked: Boolean = false, recheckSkipped: Boolean = false): ForgetService.Result = db.withTransaction {
        revoke { it.plan(frameIds.map { id -> Ref(Family.PHOTO, id) }.toSet(), Policy.RetentionPhotos(requireLinked, recheckSkipped)) }
        val c = ctx()
        val paths = mutableListOf<String>(); var expired = 0; val expiredIds = mutableListOf<String>()
        for (id in frameIds.distinct()) {
            val frame = dao.frame(id) ?: continue
            if (dao.tombstoned(listOf(id)).isNotEmpty()) continue // Codex v3 r1 P1-1: never write the expiry marker over a user tombstone (re-checked under this transaction)
            if (requireLinked && dao.describedRefs(id) == 0) continue
            if (recheckSkipped && dao.stillSkippedPhoto(id) == 0) continue
            expired++; expiredIds += id
            PhotoFamily.expire(id, frame.ts, c)
            frame.path.takeIf { it.isNotBlank() }?.let { paths += it; paths += sidecarOf(it) }
        }
        RunResultFamily.selfDelete(expiredIds.toSet(), Fate.DROP, c) // the experiment canvas outputs of the photo go with it; nothing a user sees depends on them
        queueCleanup(c, paths, emptyList(), emptyList(), phoneFilesOnly = true) // routine retention is storage hygiene: phone files only
        if (expiredIds.isNotEmpty()) c.notifyAfterCommit(expiredIds, emptyList(), expired = true) // told ONLY onPhotosExpired after the commit
        ForgetService.Result.EMPTY.copy(framesToDelete = paths.distinct(), expiredFrames = expired)
    }

    /** Retention of legacy frames nothing cites (`ForgetService.expireOrphanFramesTx`): the tombstone, the `frame` row and the JPEG; no cascade of any kind. */
    internal suspend fun expireOrphanFrames(frameIds: List<String>): ForgetService.Result = db.withTransaction {
        revoke { it.plan(frameIds.map { id -> Ref(Family.PHOTO, id) }.toSet(), Policy.RetentionPhotos()) }
        val c = ctx()
        val paths = mutableListOf<String>(); var expired = 0; val expiredIds = mutableListOf<String>()
        for (id in frameIds.distinct()) {
            val frame = dao.frame(id) ?: continue
            if (dao.tombstoned(listOf(id)).isNotEmpty()) continue // Codex v3 r1 P1-1
            if (dao.remainingRefs(id) != 0 || dao.keyframeIds(listOf(id)).isNotEmpty()) continue
            expired++; expiredIds += id
            PhotoFamily.expire(id, frame.ts, c)
            frame.path.takeIf { it.isNotBlank() }?.let { paths += it; paths += sidecarOf(it) }
        }
        queueCleanup(c, paths, emptyList(), emptyList(), phoneFilesOnly = true)
        if (expiredIds.isNotEmpty()) c.notifyAfterCommit(expiredIds, emptyList(), expired = true) // told ONLY onPhotosExpired after the commit
        ForgetService.Result.EMPTY.copy(framesToDelete = paths.distinct(), expiredFrames = expired)
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
        queueOutbox: Boolean = true, phoneFilesOnly: Boolean = false, bounded: Set<String>? = null, provenanceOnly: Boolean = false, protect: Set<String>? = null, lineageFrames: List<String> = emptyList(),
    ): ForgetService.Result {
        val found = ids.chunked(CHUNK).flatMap { dao.memories(it) }
        if (found.isEmpty() && extraCited.isEmpty() && lineageFrames.isEmpty()) return ForgetService.Result.EMPTY
        val forgotten = found.map { it.id }.toSet()
        val citedFrames = (found.mapNotNull { it.frameId } + forgotten.toList().chunked(CHUNK).flatMap { dao.sourceFramesOf(it) }).distinct()
        // F1 / F5 (`forgetV2`, [protect] = the photos this Forget forgets): a cited photo that is NOT forgotten is a survivor, never locked, deleted or tombstoned
        val forgottenFrames = if (protect == null) citedFrames else citedFrames.filter { it in protect }
        val survivors = if (protect == null) emptyList() else citedFrames - forgottenFrames.toSet()
        val frames = if (provenanceOnly) emptyList() else if (keepKeyframes) forgottenFrames - forgottenFrames.chunked(CHUNK).flatMap { dao.keyframeIds(it) }.toSet() else forgottenFrames
        // Codex v3 r1 P1-3: the legacy lineage (`lin:` keys of builds before v3), read exactly as ForgetService.cascade and the planner read it
        val meta = db.canvasSlice3Dao()
        val lineageSeed = if (lockFrames) (citedFrames + lineageFrames).distinct().filter { bounded == null || it in bounded } else emptyList()
        val lineage = ExpiredLineage.reach(meta, forgotten, lineageSeed)
        val derivedLive = lineage.derived.toList().chunked(CHUNK).flatMap { dao.memories(it) }.map { it.id }.toSet() - forgotten
        val invalidated = if (!invalidateDescendants) emptySet() else ((derivedLive + forgotten).toList().chunked(CHUNK).flatMap { dao.descendants(it) }.toSet() + derivedLive) - forgotten
        MemoryFamily.selfDelete(forgotten, Fate.ERASE, c)
        MemoryFamily.selfDelete(invalidated, Fate.REBUILD, c)
        val radiusCascade = bounded == null
        val inside = if (bounded == null) frames else frames.filter { it in bounded }
        MomentFamily.dropHolding(c, inside, (if (bounded == null) forgotten + invalidated else forgotten) + lineage.expired)
        var spans = emptyList<Triple<Long, Long, List<String>>>()
        if (lockFrames) {
            PhotoFamily.lock(frames, c)
            spans = dropCanvasOutputs(c, inside, null, radiusCascade)
            PhotoFamily.unmark(frames, c) // retention v3 (r2-3): after the radius cleanup, which reads the marker's capture time
            if (bounded != null) { val outside = frames - inside.toSet(); if (outside.isNotEmpty()) RunResultFamily.scrubLocked(c, outside) }
        }
        if (survivors.isNotEmpty()) { // the stored outputs of a survivor may carry the forgotten text (scrubbed); one with no description left is described again, alone
            RunResultFamily.scrubLocked(c, survivors)
            PhotoFamily.requeueSolo(survivors, c)
        }
        val uncited = inside.filter { dao.remainingRefs(it) == 0 } // the photo goes only when no surviving memory cites it
        val goneCites = if (scrubAccepted) uncited.map { PHOTO_CITE_PREFIX + it } else emptyList()
        val cited = forgotten + invalidated + extraCited + goneCites + lineage.expired + derivedLive
        val tx = PlanTx(db)
        val v2Rules = ForgetV2.on // M5: A1 (answers by their cited ids, "Answered from a forgotten fact"), A2 (history items), N1 (a hit nudge is erased); off = v1 redaction in place
        val reached = AskRules.reach(cited, tx, v2Rules)
        val asks = reached.asks; val nudges = reached.nudges
        AnswerFamily.selfDelete(asks.toSet(), Fate.REBUILD, c)
        NudgeFamily.selfDelete(nudges.toSet(), if (v2Rules) Fate.ERASE else Fate.REBUILD, c)
        val redactedProposals = ProposalFamily.expireAndScrub(c, asks + nudges, scrubAccepted)
        if (lineage.keys.isNotEmpty()) ExpiredLineage.consume(meta, lineage)
        PhotoFamily.deleteVectors(uncited, c)
        val files = uncited.mapNotNull { dao.frame(it)?.path?.takeIf { p -> p.isNotBlank() } }
        if (queueOutbox) queueCleanup(c, files, uncited.chunked(CHUNK).flatMap { db.momentDao().wireIds(it) }.map { it.wireFrameId }, spans, phoneFilesOnly)
        return ForgetService.Result(forgotten, invalidated, asks.size, nudges.size, files, redactedProposals, redactedAskIds = asks)
    }

    /** `ForgetService.dropCanvasOutputs`: the marks and traces within the radius of each photo (a durable range fence), the run results and the slice outputs of the photos. Returns the spans fenced. */
    private suspend fun dropCanvasOutputs(c: DeleteCtx, frameIds: List<String>, capturedAtOrNull: List<Long>? = null, radiusCascade: Boolean = true, expired: Boolean = false): List<Triple<Long, Long, List<String>>> {
        val capturedAt = capturedAtOrNull ?: ExpiryMarks.captureTs(dao, db.canvasSlice3Dao(), frameIds) // retention v3 (r2-4)
        val radius = ForgetRadius.ms() // read once per Forget
        val spans = (if (radiusCascade) capturedAt else emptyList()).map { (it - radius)..(it + radius) }.let(CanvasSlice3Dao::mergeRanges).map { r ->
            val o = TraceFamily.eraseRange(c, r.first, r.last)
            MomentFamily.dropBetween(c, r.first, r.last)
            Triple(r.first, r.last, o.tombstonedTraceIds)
        }
        if (frameIds.isNotEmpty() || spans.isNotEmpty()) c.notifyAfterCommit(frameIds, spans, expired)
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
        val plan = revoke { it.plan(emptySet(), Policy.Spans(spans, requestedSeconds, sceneIds, byKey)) }
        val md = db.momentDao(); val s3 = db.canvasSlice3Dao(); val c = ctx(now) // [now] is the MomentForget's own clock reading, as in v1
        // 1. Fences first: the durable range, then a tombstone per requested second (and the start of every span: the recency marker of a long range).
        spans.forEach { s3.addMomentForgetRange(it.first, it.last) }
        md.insertTombstones((requestedSeconds + spans.map { it.first / 1000 }).distinct().map { MomentTombstoneEntity(MomentId.format(it), now) })
        // 2. Independent discovery, unioned with the projection.
        val found = moments.discover(requestedSeconds, spans, bounded = byKey)
        val frames = found.frames; val memories = found.memories; val labels = found.labels; val projected = found.projected
        if (expectedHash != null && moments.currentHash(spans, found, moments.sceneEffect(found.moments, sceneIds), requestedSeconds, sceneIds, byKey) != expectedHash.removeSuffix(MomentForget.ACK_SUFFIX)) throw ForgetContentChanged()
        val closure = moments.closureOf(found, if (byKey) moments.sceneEffect(found.moments + found.projected, sceneIds) else null, sceneIds, byKey)
        if (closure.second.size > MomentForget.MAX_UNACKED_RESULTS && expectedHash?.endsWith(MomentForget.ACK_SUFFIX) != true) throw ForgetNeedsAck()
        RunResultFamily.selfDelete(closure.first, Fate.DROP, c)
        val wire = frames.toList().chunked(CHUNK).flatMap { md.wireIds(it) }.map { it.wireFrameId }
        val liveTracesBefore = s3.forgettableTraces().map { it.traceId }.toSet()
        // 3. The cascades, in this transaction.
        var cascade = ForgetService.Result.EMPTY
        val protect = if (ForgetV2.on) frames else null // F1 / F5: only the photos of this Forget are forgotten; a photo a memory of the range also cites is rebuilt alone
        for (f in frames) cascade = cascade.with(forgetPhoto(f, queueOutbox = false, bounded = if (byKey) frames else null, top = false, protectAll = protect))
        if (memories.isNotEmpty()) cascade = cascade.with(forgetFacts(memories.toList(), queueOutbox = false, bounded = if (byKey) frames else null, top = false, protectAll = protect))
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
            if (ForgetV2.on) MomentFamily.eraseDerivedHolding(c, remote) // F3: their derived text and their Scenes' are erased, not only hidden (the Scenes a Forget names are dropped below)
        }
        if (byKey) { // whole Scenes go, and the run rows of every Moment and Scene that went are deleted by their own key too
            if (sceneIds.isNotEmpty()) SceneFamily.selfDelete(sceneIds.toSet(), Fate.DROP, c)
            RunResultFamily.selfDelete((sceneIds.map { "scene:$it" } + dropped.map { "moment:$it" }).toSet(), Fate.DROP, c)
        }
        rebuildScenes(c, plan) // F3
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
