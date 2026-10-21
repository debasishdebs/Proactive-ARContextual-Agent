package com.rokid.context.memory.forget

import androidx.room.withTransaction
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.ForgetTombstoneEntity
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
    ) {
        companion object { val EMPTY = Result(emptySet(), emptySet(), 0, 0, emptyList()) }
    }

    private val dao = db.forgetDao()

    suspend fun forgetFact(memoryId: String): Result = forgetFacts(listOf(memoryId))

    /**
     * All memories of the frame, the frame row and its JPEG (always listed). A photo that retention already expired without a description
     * (`frame_meta`, `frame_vector`, state, no `frame` row, no memory) is still a moment (S2). Saved answers that cited it (`frame:<id>`) are
     * redacted with their proposals and alarms (S1), unless [redactCites] is false (retention's legacy-frame expiry never redacts). Empty only when no frame, metadata, vector, state or memory exists.
     */
    suspend fun forgetMoment(frameId: String, redactCites: Boolean = true): Result = db.withTransaction {
        val cites = if (redactCites) setOf(PHOTO_CITE_PREFIX + frameId) else emptySet()
        val frame = dao.frame(frameId)
        val ids = dao.memoryIdsOfFrame(frameId)
        if (frame == null && ids.isEmpty() && dao.photoRowCount(frameId) == 0) {
            // Nothing left of the moment: still redact stray `frame:<id>` cites and drop orphan canvas rows (safer default). No frame_source row
            // can exist here: memoryIdsOfFrame already lists them, so an orphan one takes the main path below.
            dropCanvasOutputs(listOf(frameId))
            return@withTransaction cascade(emptyList(), lockFrames = true, extraCited = cites)
        }
        val r = cascade(ids, lockFrames = true, extraCited = cites)
        dao.insertTombstone(ForgetTombstoneEntity(frameId, clock()))
        dropCanvasOutputs(listOf(frameId))
        dao.deleteFrame(frameId)
        listOf(frameId).let { dao.deleteFrameSourcesOfFrames(it); dao.deleteFrameVectorsFor(it); dao.deleteFrameMetaFor(it); dao.deleteFrameStateFor(it) }
        r.copy(framesToDelete = (r.framesToDelete + listOfNotNull(frame?.path?.takeIf { it.isNotBlank() }) + listOfNotNull(frame?.path?.takeIf { it.isNotBlank() }?.let(::sidecarOf))).distinct())
    }

    /**
     * Forget fact for [ids] (retention passes chunks). A frame's JPEG is listed only when no memory points at it any
     * more; its row is kept (with the tombstone) so the frame is never extracted again.
     */
    suspend fun forgetFacts(ids: Collection<String>, lockFrames: Boolean = true): Result = db.withTransaction {
        cascade(ids.distinct(), lockFrames)
    }

    /**
     * Retention expiry (fix round 1 C1 ruling): like [forgetFacts] (rows, vectors, edges, tombstones, redaction of the
     * asks/nudges citing [ids]) but derived memories are left alone: each one expires on its own tier window.
     * Final review C1: retention is storage hygiene, not a user removal, so an accepted proposal (EXECUTING/EXECUTED)
     * keeps its params, log and armed alarm; it is not listed in [Result.redactedProposals]. PENDING still expires.
     */
    suspend fun expire(ids: Collection<String>): Result = db.withTransaction {
        cascade(ids.distinct(), lockFrames = true, invalidateDescendants = false, scrubAccepted = false, keepKeyframes = true)
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
    suspend fun expirePhotos(frameIds: List<String>, requireLinked: Boolean = false, recheckSkipped: Boolean = false): Result = db.withTransaction {
        val paths = mutableListOf<String>()
        var expired = 0
        val now = clock()
        for (id in frameIds.distinct()) {
            val frame = dao.frame(id) ?: continue
            if (requireLinked && dao.remainingRefs(id) == 0) continue
            if (recheckSkipped && dao.stillSkippedPhoto(id) == 0) continue
            expired++
            dao.insertTombstone(ForgetTombstoneEntity(id, now))
            dropCanvasOutputs(listOf(id)) // S25: frame retention uses the same cascade
            dao.deleteFrame(id)
            frame.path.takeIf { it.isNotBlank() }?.let { paths += it; paths += sidecarOf(it) }
        }
        Result.EMPTY.copy(framesToDelete = paths.distinct(), expiredFrames = expired)
    }

    private fun sidecarOf(jpegPath: String) = jpegPath.removeSuffix(".jpg") + ".meta.json"

    private suspend fun cascade(ids: List<String>, lockFrames: Boolean, invalidateDescendants: Boolean = true, scrubAccepted: Boolean = true, keepKeyframes: Boolean = false, extraCited: Set<String> = emptySet()): Result {
        val found = ids.chunked(CHUNK).flatMap { dao.memories(it) }
        if (found.isEmpty() && extraCited.isEmpty()) return Result.EMPTY
        val forgotten = found.map { it.id }.toSet()
        // Frames cited by F: memory.frameId plus frame_source rows (a 3-photo batch cites 3). Each is locked at once.
        val citedFrames = (found.mapNotNull { it.frameId } + forgotten.toList().chunked(CHUNK).flatMap { dao.sourceFramesOf(it) }).distinct()
        // Retention expiry of a memory ([keepKeyframes]) never owns a keyframe (a frame with frame_meta): no tombstone, no JPEG or vector
        // delete. Photos live and die by the photo rules (expirePhotos). Legacy frames and the user's Forget keep the full cascade.
        val frames = if (keepKeyframes) citedFrames - citedFrames.chunked(CHUNK).flatMap { dao.keyframeIds(it) }.toSet() else citedFrames
        // 1. Closure, before any edge goes.
        val invalidated = if (!invalidateDescendants) emptySet() else forgotten.chunked(CHUNK).flatMap { dao.descendants(it) }.toSet() - forgotten
        val f = forgotten.toList()
        val d = invalidated.toList()
        // 2. F: rows, vectors (all spaces), every edge touching F.
        f.chunked(CHUNK).forEach { c -> dao.deleteFrameSourcesFor(c); dao.deleteEdgesFrom(c); dao.deleteEdgesTo(c); dao.deleteVectorsFor(c); dao.deleteMemories(c) }
        // 3. D: hidden, vectors gone.
        d.chunked(CHUNK).forEach { c -> dao.invalidate(c); dao.deleteVectorsFor(c) }
        // 4. Extraction lock.
        val now = clock()
        if (lockFrames) { frames.forEach { dao.insertTombstone(ForgetTombstoneEntity(it, now)) }; dropCanvasOutputs(frames) }
        // 5. Redaction (exact JSON match after a LIKE prefilter: "A1" never matches "A").
        val cited = forgotten + invalidated + extraCited // extraCited: `frame:<id>` photo cites (S1)
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
        // The photo goes only when no surviving memory cites it (memory.frameId or frame_source); its image vector goes with it.
        val uncited = frames.filter { dao.remainingRefs(it) == 0 }
        uncited.chunked(CHUNK).forEach { dao.deleteFrameVectorsFor(it) }
        val files = uncited.mapNotNull { dao.frame(it)?.path?.takeIf { p -> p.isNotBlank() } }
        return Result(forgotten, invalidated, asks.size, nudges.size, files, redactedProposals)
    }

    /** Experiment canvas outputs are photo-derived: they go with the photo, in the same transaction as its tombstone. */
    private suspend fun dropCanvasOutputs(frameIds: List<String>) {
        db.canvasDao().deleteCanvasOutputsForFrames(frameIds) // items, scores and live traces, batch-wide (S25)
    }

    private fun likeFor(id: String) = "%\"${MemoryRepository.escapeLike(id)}\"%"

    companion object {
        const val REDACTED = "Removed \u2014 a source was forgotten"
        /**
         * `proposal.paramsJson` after its origin was redacted. Has no action fields, so executors and boot re-arm
         * (Stream B) find nothing to run; an EXECUTED reminder is never re-armed from it.
         */
        const val REDACTED_PARAMS = "{\"redacted\":true}"
        private const val CHUNK = 500
        /** Proposal states retention never scrubs or disarms (final review C1). */
        private val ACCEPTED = listOf(com.rokid.context.memory.ProposalState.EXECUTING.name, com.rokid.context.memory.ProposalState.EXECUTED.name)

        private fun parseIds(json: String): Set<String> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i -> (a.opt(i) as? String)?.trim() }.toSet()
        }.getOrDefault(emptySet())
    }

}
