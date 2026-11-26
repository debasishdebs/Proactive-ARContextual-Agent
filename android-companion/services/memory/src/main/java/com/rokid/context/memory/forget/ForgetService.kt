package com.rokid.context.memory.forget

import androidx.room.withTransaction
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.forget.lifecycle.ForgetExecutor
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import org.json.JSONArray

/** Wave 3: a saved answer cites a photo as `frame:<frameId>` in its sources. Forget on the photo redacts those answers (S1). */
const val PHOTO_CITE_PREFIX = "frame:"

/**
 * Forget fact / Forget moment (spec §6.1.1, R-77, R-16), one transaction each:
 * 1. D = everything derived from the forgotten set F over `memory_source`, minus F.
 * 2. F's rows go (the FTS triggers follow), with their vectors in every space and every edge touching F.
 * 3. D becomes INVALIDATED (hidden from feeds, retrieval and the critic) and loses its vectors. The re-summarise worker
 *    rebuilds D from the sources that survive.
 * 4. Each F frame is tombstoned (the extraction lock) when [lockFrames].
 * 5. Asks and nudges citing F ∪ D are redacted. 6. Their PENDING proposals expire (and, except on retention expiry,
 *    every proposal of theirs is scrubbed and disarmed).
 * Every `IN (…)` is chunked at [CHUNK]. Trace notes are untouched: they hold ids and counts, never memory text.
 * File deletion is the caller's job (on IO), from [Result.framesToDelete].
 */
class ForgetService(
    private val db: ContextDatabase,
    private val proposals: ProposalRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(
        val forgotten: Set<String>,
        val invalidated: Set<String>,
        val redactedAsks: Int,
        val redactedNudges: Int,
        val framesToDelete: List<String>,
        /** Proposals of the redacted asks/nudges (any state): their posted notifications must go. */
        val redactedProposals: List<String> = emptyList(),
        /** [expirePhotos]: how many photos it actually expired (a recheck or `requireLinked` skip is not counted). */
        val expiredFrames: Int = 0,
        /** rc1 fix: the ids of the redacted asks ([redactedAsks] of them): an answer still shown for one (phone, glasses HUD) is withdrawn after the commit. */
        val redactedAskIds: List<String> = emptyList(),
    ) {
        companion object { val EMPTY = Result(emptySet(), emptySet(), 0, 0, emptyList()) }
        /** Codex r16: two results of one transaction (a photo's Forget and the CARRIED invalidation it triggers). The invalidation never adds a frame to delete. */
        internal fun with(o: Result) = Result(forgotten + o.forgotten, invalidated + o.invalidated, redactedAsks + o.redactedAsks, redactedNudges + o.redactedNudges,
            (framesToDelete + o.framesToDelete).distinct(), (redactedProposals + o.redactedProposals).distinct(), expiredFrames + o.expiredFrames,
            (redactedAskIds + o.redactedAskIds).distinct())
    }

    private val dao = db.forgetDao()
    /** Forget lifecycle M4: the v2 executor (used only while [ForgetV2] is on; every legacy body below stays as it was). */
    internal val executor by lazy { ForgetExecutor(db, proposals, clock, this) }
    private val v2: Boolean get() = ForgetV2.on

    suspend fun forgetFact(memoryId: String): Result = forgetFacts(listOf(memoryId))

    /**
     * All memories of the frame, the frame row and its JPEG (always listed). A photo that retention already expired without a description
     * (`frame_meta`, `frame_vector`, state, no `frame` row, no memory) is still a moment (S2). Saved answers that cited it (`frame:<id>`) are
     * redacted with their proposals and alarms (S1), unless [redactCites] is false (retention's legacy-frame expiry never redacts). Empty only when no frame, metadata, vector, state or memory exists.
     */
    suspend fun forgetMoment(frameId: String, redactCites: Boolean = true, phoneFilesOnly: Boolean = false): Result = notifying { if (v2) executor.forgetPhoto(frameId, redactCites, phoneFilesOnly = phoneFilesOnly) else forgetMomentTx(frameId, redactCites, phoneFilesOnly = phoneFilesOnly) }

    internal suspend fun forgetMomentTx(frameId: String, redactCites: Boolean = true, queueOutbox: Boolean = true, phoneFilesOnly: Boolean = false, bounded: Set<String>? = null): Result = db.withTransaction {
        val radiusCascade = bounded == null
        val cites = if (redactCites) setOf(PHOTO_CITE_PREFIX + frameId) else emptySet()
        val frame = dao.frame(frameId)
        val capturedAt = ExpiryMarks.captureTs(dao, db.canvasSlice3Dao(), listOf(frameId)) // retention v3 (r2-4): read before the rows and the expiry marker go; an expired photo still has a capture time
        val ids = dao.memoryIdsOfFrame(frameId)
        // codex r16: memories only DERIVED from this photo (CARRIED, e.g. a batch mate's caption) are a provenance invalidation: their text, vectors, edges and
        // descendants go and what cites them is redacted, but the photos THEY cite are never locked, tombstoned or deleted
        val carried = dao.carriedMemoryIdsOfFrame(frameId) - ids.toSet()
        val invalidatedCarried = if (carried.isEmpty()) Result.EMPTY else cascade(carried, lockFrames = false, provenanceOnly = true, queueOutbox = false)
        if (frame == null && ids.isEmpty() && dao.photoRowCount(frameId) == 0) {
            // Nothing left of the moment: still redact stray `frame:<id>` cites and drop orphan canvas rows (safer default). No frame_source row
            // can exist here: memoryIdsOfFrame already lists them, so an orphan one takes the main path below.
            dropCanvasOutputs(listOf(frameId), capturedAt, radiusCascade)
            ExpiryMarks.clear(db.canvasSlice3Dao(), listOf(frameId)) // retention v3 (r2-3): an expired photo with nothing left is still forgotten by this Forget
            return@withTransaction cascade(emptyList(), lockFrames = true, extraCited = cites, bounded = bounded).with(invalidatedCarried)
        }
        val r = cascade(ids, lockFrames = true, extraCited = cites, queueOutbox = queueOutbox, phoneFilesOnly = phoneFilesOnly, bounded = bounded)
        val wire = db.momentDao().wireIds(listOf(frameId)).map { it.wireFrameId } // read before frame_meta goes
        dao.insertTombstone(ForgetTombstoneEntity(frameId, clock()))
        db.momentDao().dropHolding(listOf(frameId), ids) // source-to-projection cleanup: no stored Moment keeps a reference to what Forget removed
        val spans = dropCanvasOutputs(listOf(frameId), capturedAt, radiusCascade)
        ExpiryMarks.clear(db.canvasSlice3Dao(), listOf(frameId)) // retention v3 (r2-3): a forgotten photo is never read as expired
        dao.deleteFrame(frameId)
        listOf(frameId).let { dao.deleteFrameSourcesOfFrames(it); dao.deleteFrameVectorsFor(it); dao.deleteFrameMetaFor(it); dao.deleteFrameStateFor(it) }
        val out = r.copy(framesToDelete = (r.framesToDelete + listOfNotNull(frame?.path?.takeIf { it.isNotBlank() }) + listOfNotNull(frame?.path?.takeIf { it.isNotBlank() }?.let(::sidecarOf))).distinct()).with(invalidatedCarried)
        if (queueOutbox) queueCleanup(out.framesToDelete, wire, spans, phoneFilesOnly) // same transaction as the delete
        out
    }

    /**
     * Forget fact for [ids] (retention passes chunks). A frame's JPEG is listed only when no memory points at it any
     * more; its row is kept (with the tombstone) so the frame is never extracted again.
     */
    /** Multi-select Forget (activity pagination A8): what [roots] would do, read only. Same plan, same hash as [forgetSelection]. */
    suspend fun previewSelection(roots: Set<com.rokid.context.memory.forget.lifecycle.Ref>): com.rokid.context.memory.forget.lifecycle.SelectionPreview =
        com.rokid.context.memory.forget.lifecycle.ForgetSelection.preview(db, roots)

    /**
     * Multi-select Forget (activity pagination A8, proxy ruling P2): exists ONLY behind `forgetV2` (the planner and the executor are the one path); off, it fails closed and nothing is deleted. [token] is the preview's
     * [com.rokid.context.memory.forget.lifecycle.SelectionPreview.token]. Throws [ForgetContentChanged], [ForgetNeedsAck] or [ForgetTooLarge] with nothing deleted.
     */
    suspend fun forgetSelection(roots: Set<com.rokid.context.memory.forget.lifecycle.Ref>, token: String): Result = notifying {
        check(v2) { "multi-select Forget needs forgetV2" }
        executor.forgetSelection(roots, token)
    }

    suspend fun forgetFacts(ids: Collection<String>, lockFrames: Boolean = true): Result = notifying { if (v2) executor.forgetFacts(ids, lockFrames) else forgetFactsTx(ids, lockFrames) }

    internal suspend fun forgetFactsTx(ids: Collection<String>, lockFrames: Boolean = true, queueOutbox: Boolean = true, bounded: Set<String>? = null): Result = db.withTransaction {
        cascade(ids.distinct(), lockFrames, queueOutbox = queueOutbox, bounded = bounded)
    }

    /** Retention v3 demotion (see [ForgetExecutor.demoteFacts]): the text vectors of SHORT facts older than [beforeMs] go, nothing else. Never deletes a memory row. Returns the number of facts demoted. */
    suspend fun demoteFacts(beforeMs: Long, limit: Int): Int = executor.demoteFacts(beforeMs, limit)

    /**
     * Row 8 (suspect descriptions): removes the marked suspect [ids] (their rows, vectors and edges; the derived summaries built on them are invalidated and rebuilt; asks and nudges that cited them are
     * redacted). Never touches a photo: no tombstone, no JPEG or vector delete (keyframes are kept; the caller proved a replacement exists or the photo is already gone), accepted proposals keep their params.
     */
    suspend fun removeDescriptions(ids: Collection<String>): Result = notifying {
        if (v2) return@notifying executor.removeDescriptions(ids)
        db.withTransaction { cascade(ids.distinct(), lockFrames = false, invalidateDescendants = true, scrubAccepted = false, keepKeyframes = true, phoneFilesOnly = true) }
    }

    /**
     * The suspect sweep's removal: like [removeDescriptions], but every id is re-validated INSIDE the deletion transaction ([SuspectRecheck]): an Undo (mark cleared) or a change of the photos
     * between the sweep's selection and this call keeps that memory. An id that no longer qualifies is skipped, never deleted.
     */
    suspend fun removeSuspectDescriptions(ids: Collection<String>, mode: SuspectRecheck): Result = notifying {
        if (v2) return@notifying executor.removeSuspectDescriptions(ids, mode)
        db.withTransaction {
            val live = ids.distinct().filter { dao.suspectStillEligible(it, mode) }
            if (live.isEmpty()) Result.EMPTY else cascade(live, lockFrames = false, invalidateDescendants = true, scrubAccepted = false, keepKeyframes = true, phoneFilesOnly = true)
        }
    }

    /**
     * Photo expiry (Ruling 11), one transaction: each of [frameIds] that still has a `frame` row is tombstoned (the extraction lock, and the
     * marker the sweep retries a failed delete from) and loses its `frame` row. `frame_meta`, `frame_vector`, `frame_state`, `frame_source`,
     * the memories and everything derived from them stay. [Result.framesToDelete] lists each photo's JPEG and its `.meta.json` sidecar for
     * the caller to delete (on IO, under the WipeFence, through `FrameSweep.delete`). An unknown or already expired frame is skipped.
     * UI that assumes `memory.frameId` has a row shows "Photo expired". With [requireLinked] (every deadline-based expiry) a photo that no
     * memory cites any more (the memory was forgotten or expired since the caller selected it) is skipped inside the same transaction: an
     * undescribed photo is never deleted by retention. With [recheckSkipped] (the skipped-photo retention caller) each id is re-evaluated against
     * the skipped-photo rule inside the transaction and skipped when it no longer qualifies: a photo the user described after the caller selected
     * it is kept (S4).
     */
    suspend fun expirePhotos(frameIds: List<String>, requireLinked: Boolean = false, recheckSkipped: Boolean = false): Result = notifying { if (v2) executor.expirePhotos(frameIds, requireLinked, recheckSkipped) else expirePhotosTx(frameIds, requireLinked, recheckSkipped) }

    internal suspend fun expirePhotosTx(frameIds: List<String>, requireLinked: Boolean = false, recheckSkipped: Boolean = false): Result = expireFrameRows(frameIds, canvasOutputs = true) { id ->
        !(requireLinked && dao.describedRefs(id) == 0) && // codex r12: the same description rule the selection used, not any citation
            !(recheckSkipped && dao.stillSkippedPhoto(id) == 0)
    }

    /**
     * The one storage-only expiry of `frame` rows: each of [frameIds] that has a row and passes [accept] is tombstoned and loses its row; its JPEG and sidecar are queued (phone files only, same transaction).
     * Retention r1 (P1-1): NO Moment is dropped, no Scene shrunk, no mark / trace / run output other than the photo's own experiment outputs ([canvasOutputs]) touched, no radius cascade run and no Forget listener told:
     * a Moment keeps its FRAME part and shows "Photo expired".
     */
    private suspend fun expireFrameRows(frameIds: List<String>, canvasOutputs: Boolean, accept: suspend (String) -> Boolean): Result = db.withTransaction {
        val paths = mutableListOf<String>()
        val expiredIds = mutableListOf<String>()
        val now = clock()
        for (id in frameIds.distinct()) {
            val frame = dao.frame(id) ?: continue
            if (dao.tombstoned(listOf(id)).isNotEmpty()) continue // Codex v3 r1 P1-1: a photo that already carries a tombstone (a user Forget of one of its facts) is never marked expired; checked inside this transaction, so a concurrent Forget cannot be overwritten
            if (!accept(id)) continue
            expiredIds += id
            dao.insertTombstone(ForgetTombstoneEntity(id, now))
            ExpiryMarks.mark(db.canvasSlice3Dao(), id, now, frame.ts) // retention v3 (r2-3, r2-6): the durable marker, in the SAME transaction as the tombstone and the row delete
            dao.deleteFrame(id)
            frame.path.takeIf { it.isNotBlank() }?.let { paths += it; paths += sidecarOf(it) }
        }
        // the experiment canvas outputs of the photo (items, scores, live traces) go with it; nothing a user sees depends on them
        if (canvasOutputs) expiredIds.chunked(CHUNK).forEach { db.canvasDao().deleteCanvasOutputsForFrames(it) }
        com.rokid.context.memory.forget.lifecycle.PlaceHistoryFamily.unlinkExpired(db, expiredIds) // v18 WP3: the place card history keeps its rows and only loses the photo link (photoGone = 1), in this transaction
        queueCleanup(paths, emptyList(), emptyList(), phoneFilesOnly = true) // routine retention is storage hygiene: phone files only (glasses and helper rows are for user Forgets)
        if (expiredIds.isNotEmpty()) addPending(expiredIds, emptyList(), expired = true) // told ONLY onPhotosExpired, after the commit (notifying); never a forget callback
        Result.EMPTY.copy(framesToDelete = paths.distinct(), expiredFrames = expiredIds.size)
    }

    /**
     * Retention of legacy frames (no `frame_meta`) that no memory cites (retention r1 P1-3): only the extraction lock (tombstone), the `frame` row and the JPEG go. Nothing cascades: `frame_source` rows (CARRIED lineage),
     * Moments, Scenes, marks, canvas outputs, asks and nudges are left as they are, so no privacy cascade (radius, CARRIED invalidation, redaction) can run for a storage-hygiene delete. A frame that is cited by now, has
     * metadata or no longer has a row is skipped (re-checked inside the transaction).
     */
    suspend fun expireOrphanFrames(frameIds: List<String>): Result = notifying { if (v2) executor.expireOrphanFrames(frameIds) else expireOrphanFramesTx(frameIds) }

    internal suspend fun expireOrphanFramesTx(frameIds: List<String>): Result = expireFrameRows(frameIds, canvasOutputs = false) { id -> dao.remainingRefs(id) == 0 && dao.keyframeIds(listOf(id)).isEmpty() }

    private fun sidecarOf(jpegPath: String) = jpegPath.removeSuffix(".jpg") + ".meta.json"

    /** [provenanceOnly] (codex r16): the memories go but no photo is touched (no lock, no tombstone, no file or Moment of their photos): a CARRIED invalidation. */
    private suspend fun cascade(ids: List<String>, lockFrames: Boolean, invalidateDescendants: Boolean = true, scrubAccepted: Boolean = true, keepKeyframes: Boolean = false, extraCited: Set<String> = emptySet(), queueOutbox: Boolean = true, phoneFilesOnly: Boolean = false, bounded: Set<String>? = null, provenanceOnly: Boolean = false): Result {
        val found = ids.chunked(CHUNK).flatMap { dao.memories(it) }
        if (found.isEmpty() && extraCited.isEmpty()) return Result.EMPTY
        val forgotten = found.map { it.id }.toSet()
        // Frames cited by F: memory.frameId plus frame_source rows (a 3-photo batch cites 3). Each is locked at once.
        val citedFrames = (found.mapNotNull { it.frameId } + forgotten.toList().chunked(CHUNK).flatMap { dao.sourceFramesOf(it) }).distinct()
        // Retention expiry of a memory ([keepKeyframes]) never owns a keyframe (a frame with frame_meta): no tombstone, no JPEG or vector
        // delete. Photos live and die by the photo rules (expirePhotos). Legacy frames and the user's Forget keep the full cascade.
        val frames = if (provenanceOnly) emptyList() else if (keepKeyframes) citedFrames - citedFrames.chunked(CHUNK).flatMap { dao.keyframeIds(it) }.toSet() else citedFrames
        // 1. Closure, before any edge goes.
        val invalidated = if (!invalidateDescendants) emptySet() else (forgotten.toList().chunked(CHUNK).flatMap { dao.descendants(it) }.toSet() - forgotten)
        val f = forgotten.toList()
        val d = invalidated.toList()
        // 2. F: rows, vectors (all spaces), every edge touching F.
        f.chunked(CHUNK).forEach { c -> dao.deleteFrameSourcesFor(c); dao.deleteEdgesFrom(c); dao.deleteEdgesTo(c); dao.deleteVectorsFor(c); dao.deleteMemories(c) }
        // 3. D: hidden, vectors gone.
        d.chunked(CHUNK).forEach { c -> dao.invalidate(c); dao.deleteVectorsFor(c) }
        val radiusCascade = bounded == null
        // BOUNDED (Forget scope MOMENT / SCENE): only photos inside the planned spans lose outputs, files and Moments; a cited photo outside them is merely LOCKED below, and Moments elsewhere that hold a hidden derived fact are not dropped (the caller blanks and stales them).
        val inside = if (bounded == null) frames else frames.filter { it in bounded }
        // the stored Moments holding a deleted or invalidated memory (or a locked photo) are rebuildable: drop them.
        db.momentDao().dropHolding(inside, if (bounded == null) f + d else f)
        // 4. Extraction lock.
        val now = clock()
        var spans = emptyList<Triple<Long, Long, List<String>>>()
        if (lockFrames) {
            frames.forEach { dao.insertTombstone(ForgetTombstoneEntity(it, now)) }
            spans = dropCanvasOutputs(inside, null, radiusCascade)
            ExpiryMarks.clear(db.canvasSlice3Dao(), frames) // retention v3 (r2-3): a user Forget never leaves an expiry marker behind (after the radius cleanup, which reads the marker's capture time)
            if (bounded != null) { // a locked photo outside the spans keeps itself, but the stored run outputs and DONE helper results of it may carry the forgotten fact's text: scrubbed
                val outside = frames - inside.toSet()
                if (outside.isNotEmpty()) { db.canvasDao().deleteCanvasOutputsForFrames(outside); db.canvasSlice3Dao().scrubOutputsOfLockedFrames(outside, now) }
            }
        }
        // The photo goes only when no surviving memory cites it (memory.frameId or frame_source); its image vector goes with it.
        val uncited = inside.filter { dao.remainingRefs(it) == 0 }
        // 5. Redaction (exact JSON match after a LIKE prefilter: "A1" never matches "A").
        // rc1 fix: a user's Forget that deletes a photo (a Forget fact of its last caption) also redacts the answers that cited the photo itself as `frame:<id>`
        // (an answer recalled it before it had a caption memory). Retention ([scrubAccepted] false) keeps its rule: it never redacts photo cites.
        val goneCites = if (scrubAccepted) uncited.map { PHOTO_CITE_PREFIX + it } else emptyList()
        val cited = forgotten + invalidated + extraCited + goneCites // extraCited: `frame:<id>` photo cites (S1)
        val asks = cited.flatMap { id -> dao.asksCiting(likeFor(id)) }.distinctBy { it.id }.filter { a -> parseIds(a.sourcesJson).any { it in cited } }
        val nudges = cited.flatMap { id -> dao.nudgesCiting(likeFor(id)) }.distinctBy { it.id }.filter { n -> parseIds(n.sourceIdsJson).any { it in cited } }
        asks.forEach { dao.redactAsk(it.id, REDACTED) }
        nudges.forEach { dao.redactNudge(it.id, REDACTED) }
        // 6. Proposals of redacted asks/nudges: PENDING expires (nested in this transaction); every proposal of those
        //    origins loses its params (titles, items) and its action_log details (fix round 1 I2). Retention
        //    ([scrubAccepted] false) skips the accepted ones: EXECUTING/EXECUTED keep params, log and alarm.
        val origins = asks.map { it.id } + nudges.map { it.id }
        var redactedProposals = emptyList<String>()
        if (origins.isNotEmpty()) {
            proposals.expireForOrigins(origins, now)
            val actions = db.actionDao()
            if (scrubAccepted) {
                redactedProposals = origins.chunked(CHUNK).flatMap { actions.allIdsForOrigins(it) }
                origins.chunked(CHUNK).forEach { c -> actions.scrubLogsForOrigins(c); actions.scrubParamsForOrigins(c, REDACTED_PARAMS) }
            } else {
                redactedProposals = origins.chunked(CHUNK).flatMap { actions.idsForOriginsExcept(it, ACCEPTED) }
                redactedProposals.chunked(CHUNK).forEach { c -> actions.scrubLogs(c); actions.scrubParams(c, REDACTED_PARAMS) }
            }
        }
        uncited.chunked(CHUNK).forEach { dao.deleteFrameVectorsFor(it) }
        val files = uncited.mapNotNull { dao.frame(it)?.path?.takeIf { p -> p.isNotBlank() } }
        if (queueOutbox) queueCleanup(files, uncited.chunked(CHUNK).flatMap { db.momentDao().wireIds(it) }.map { it.wireFrameId }, spans, phoneFilesOnly) // same transaction as the delete
        return Result(forgotten, invalidated, asks.size, nudges.size, files, redactedProposals, redactedAskIds = asks.map { it.id })
    }

    /** Experiment canvas outputs are photo-derived: they go with the photo, in the same transaction as its tombstone. */
    private class Pending(val frames: List<String>, val spans: List<Triple<Long, Long, List<String>>>, val expired: Boolean = false)
    private val pendingFrames = java.util.concurrent.ConcurrentLinkedQueue<Pending>()
    /** The executor's way to register what listeners are told after the commit (the same queue [dropCanvasOutputs] fills). */
    internal fun addPending(frames: List<String>, spans: List<Triple<Long, Long, List<String>>>, expired: Boolean = false) { pendingFrames.add(Pending(frames, spans, expired)) }

    /** Runs [tx]; only after it committed are the registered [ForgetListener]s told which photos went (a rolled-back transaction tells nobody). */
    internal suspend fun notifying(tx: suspend () -> Result): Result {
        val r = try { tx() } catch (e: Throwable) { pendingFrames.clear(); throw e }
        val all = generateSequence { pendingFrames.poll() }.toList()
        val ids = all.filter { !it.expired }.flatMap { it.frames }.distinct()
        if (ids.isNotEmpty()) ForgetListeners.framesForgotten(ids)
        val expiredIds = all.filter { it.expired }.flatMap { it.frames }.distinct()
        if (expiredIds.isNotEmpty()) ForgetListeners.photosExpired(expiredIds) // retention: expiry-aware listeners only (the place card history unlinks the photo); never a forget callback
        all.filter { !it.expired }.flatMap { it.spans }.groupBy { it.first to it.second }.forEach { (r, v) -> ForgetListeners.rangeForgotten(r.first, r.second, v.flatMap { it.third }.distinct()) } // files and glasses follow the time link
        all.filter { it.expired }.flatMap { it.spans }.groupBy { it.first to it.second }.forEach { (r, v) -> ForgetListeners.rangeExpired(r.first, r.second, v.flatMap { it.third }.distinct()) }
        return r
    }

    /** The shared outbox helper, called inside the deleting transaction: phone JPEGs, glasses frames, tombstoned glasses traces and the glasses time ranges. */
    private suspend fun queueCleanup(files: Collection<String>, wireFrameIds: Collection<String>, spans: List<Triple<Long, Long, List<String>>>, phoneFilesOnly: Boolean = false) {
        if (phoneFilesOnly) CleanupOutbox.queue(db.momentDao(), clock(), files)
        else CleanupOutbox.queue(db.momentDao(), clock(), files, wireFrameIds, spans.flatMap { it.third }, spans.map { it.first..it.second })
    }

    /** Returns the time spans it fenced (from, to, tombstoned trace ids) so the caller can queue the glasses cleanup in the same transaction. */
    /**
     * [radiusCascade] false (the Forget scope MOMENT / SCENE, whose planned spans ALREADY include the radius): no per-photo radius cascade, so the deletion set is exactly the previewed spans and a photo at the edge cannot
     * cascade to marks or Moments outside them. The photo's own outputs are still deleted.
     */
    private suspend fun dropCanvasOutputs(frameIds: List<String>, capturedAtOrNull: List<Long>? = null, radiusCascade: Boolean = true, expired: Boolean = false): List<Triple<Long, Long, List<String>>> {
        val capturedAt = capturedAtOrNull ?: ExpiryMarks.captureTs(dao, db.canvasSlice3Dao(), frameIds) // retention v3 (r2-4): frame.ts, frame_meta, then the expiry marker
        // A mark carries no frame id (the glasses tap does not know which photo it is near), so the link between a photo and the marks and traces of its moment is TIME:
        // everything within the [ForgetRadius] (default PHOTO_LINK_MS) of a forgotten photo goes with it (marks, traces, and the results that read them), through the same durable range fence as a
        // range Forget. Conservative on purpose: a privacy action errs on deleting a little more.
        val radius = ForgetRadius.ms() // read once per Forget: the user's setting, default PHOTO_LINK_MS
        val spans = (if (radiusCascade) capturedAt else emptyList()).map { (it - radius)..(it + radius) }.let(CanvasSlice3Dao::mergeRanges).map { r ->
            val o = db.canvasSlice3Dao().forgetRangeTx(r.first, r.last, clock())
            db.momentDao().dropBetween(r.first, r.last) // the marks of that time are gone: so are the stored Moments that held them
            Triple(r.first, r.last, o.tombstonedTraceIds)
        }
        if (frameIds.isNotEmpty() || spans.isNotEmpty()) pendingFrames.add(Pending(frameIds, spans, expired))
        db.canvasDao().deleteCanvasOutputsForFrames(frameIds) // items, scores and live traces, batch-wide (S25)
        if (!expired) { db.momentLocationDao().deleteForAnchors(frameIds); db.momentLocationDao().deleteForMoments(capturedAt.map(com.rokid.context.memory.moment.MomentId::ofMs)); db.placeLinkDao().deleteForAnchors(frameIds); db.placeLinkDao().deleteForMoments(capturedAt.map(com.rokid.context.memory.moment.MomentId::ofMs)) } // v18 WP2: a USER Forget deletes the saved location of the photo and of its second; expiry keeps it
        if (!expired) db.modelAttributionDao().deleteGone() // v18 WP11a: net for the model attribution of every memory, derived field and answer this Forget removed (the delete paths already did it)
        if (!expired) db.sceneCentroidDao().deleteStale() // v18 WP10: a USER Forget deletes the centroid of every Scene it changed or removed (the Moments are already dropped here); expiry keeps it
        if (!expired) com.rokid.context.memory.forget.lifecycle.PlaceHistoryFamily.eraseFrames(db, frameIds) // v18 WP3: a USER Forget deletes the place card history rows of the photos (same transaction); expiry unlinks them instead
        db.canvasSlice3Dao().deleteSliceOutputsForFrames(frameIds, clock()) // slice 3a: labels of the photos, the results that read them, helper jobs that carry them
        return spans
    }

    private fun likeFor(id: String) = Companion.likeFor(id)

    companion object {
        /** The DEFAULT of [ForgetRadius] (a mark labels the 10 s before it): how far around a forgotten photo's capture time marks and traces are linked to it unless the user set another. */
        const val PHOTO_LINK_MS = 10_000L
        const val REDACTED = "Removed \u2014 a source was forgotten"
        /**
         * `proposal.paramsJson` after its origin was redacted. Has no action fields, so executors and boot re-arm
         * (Stream B) find nothing to run; an EXECUTED reminder is never re-armed from it.
         */
        const val REDACTED_PARAMS = "{\"redacted\":true}"
        private const val CHUNK = 500
        /** Proposal states retention never scrubs or disarms (final review C1). */
        private val ACCEPTED = listOf(com.rokid.context.memory.ProposalState.EXECUTING.name, com.rokid.context.memory.ProposalState.EXECUTED.name)

        /** The LIKE prefilter of a cited id (exact JSON match follows): shared with the lifecycle planner so both read the same asks and nudges. */
        internal fun likeFor(id: String) = "%\"${MemoryRepository.escapeLike(id)}\"%"

        internal fun parseIds(json: String): Set<String> = runCatching {
            val a = JSONArray(json)
            // A trailing {"dependsOn":[...]} (a nudge built from earlier nudges) lists ids the text rests on without citing them: forgetting one redacts too.
            (0 until a.length()).flatMap { i ->
                when (val e = a.opt(i)) {
                    is String -> listOf(e.trim())
                    is org.json.JSONObject -> e.optJSONArray("dependsOn")?.let { d -> (0 until d.length()).mapNotNull { (d.opt(it) as? String)?.trim() } }.orEmpty()
                    else -> emptyList()
                }
            }.toSet()
        }.getOrDefault(emptySet())
    }

}

/** Why the sweep selected a suspect: [EXPIRED] = every photo is gone; [REPLACED] = every stored photo has another description with delivery proof. Re-checked inside the deletion transaction. */
enum class SuspectRecheck { EXPIRED, REPLACED }

/** True when [id] is still a marked suspect AND the selection condition of [mode] still holds (the same rules the sweep's queries use). Call inside the deletion transaction. */
internal suspend fun com.rokid.context.memory.db.ForgetDao.suspectStillEligible(id: String, mode: SuspectRecheck): Boolean {
    if (isMarkedSuspect(id) == 0) return false
    val photos = storedPhotosOf(id)
    return when (mode) {
        SuspectRecheck.EXPIRED -> photos.isEmpty()
        SuspectRecheck.REPLACED -> photos.isNotEmpty() && photos.all { describedWithProof(it, id) > 0 }
    }
}
