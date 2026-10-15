package com.rokid.context.memory.forget

import androidx.room.withTransaction
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.ForgetTombstoneEntity
import org.json.JSONArray

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
    ) {
        companion object { val EMPTY = Result(emptySet(), emptySet(), 0, 0, emptyList()) }
    }

    private val dao = db.forgetDao()

    suspend fun forgetFact(memoryId: String): Result = forgetFacts(listOf(memoryId))

    /** All memories of the frame, the frame row and its JPEG (always listed). Unknown frame and no memories → empty. */
    suspend fun forgetMoment(frameId: String): Result = db.withTransaction {
        val frame = dao.frame(frameId)
        val ids = dao.memoryIdsOfFrame(frameId)
        if (frame == null && ids.isEmpty()) return@withTransaction Result.EMPTY
        val r = cascade(ids, lockFrames = true)
        dao.insertTombstone(ForgetTombstoneEntity(frameId, clock()))
        dao.deleteFrame(frameId)
        listOf(frameId).let { dao.deleteFrameVectorsFor(it); dao.deleteFrameMetaFor(it); dao.deleteFrameStateFor(it) }
        r.copy(framesToDelete = (r.framesToDelete + listOfNotNull(frame?.path?.takeIf { it.isNotBlank() })).distinct())
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
        cascade(ids.distinct(), lockFrames = true, invalidateDescendants = false, scrubAccepted = false)
    }

    private suspend fun cascade(ids: List<String>, lockFrames: Boolean, invalidateDescendants: Boolean = true, scrubAccepted: Boolean = true): Result {
        val found = ids.chunked(CHUNK).flatMap { dao.memories(it) }
        if (found.isEmpty()) return Result.EMPTY
        val forgotten = found.map { it.id }.toSet()
        // Frames cited by F: memory.frameId plus frame_source rows (a 3-photo batch cites 3). Each is locked at once.
        val frames = (found.mapNotNull { it.frameId } + forgotten.toList().chunked(CHUNK).flatMap { dao.sourceFramesOf(it) }).distinct()
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
        if (lockFrames) frames.forEach { dao.insertTombstone(ForgetTombstoneEntity(it, now)) }
        // 5. Redaction (exact JSON match after a LIKE prefilter: "A1" never matches "A").
        val cited = forgotten + invalidated
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
