package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Forget fact / Forget moment cascade queries (spec §6.1.1, R-77), the extraction lock (`forget_tombstone`) and the
 * `memory_source` provenance edges. Callers chunk every list parameter (≤ 500 ids, under SQLite's 999 limit).
 * A photo is referenced by a live memory through `memory.frameId` OR a `frame_source` row (schema v2); every reference
 * count, orphan selection and tombstone prune below checks both.
 */
@Dao
abstract class ForgetDao {
    /**
     * Every memory derived, directly or transitively, from [ids] over `memory_source` (`memoryId` is derived from
     * `sourceMemoryId`). `UNION` (not `UNION ALL`) drops repeats, so a cycle terminates. May include members of [ids].
     */
    @Query(
        "WITH RECURSIVE d(id) AS (SELECT memoryId FROM memory_source WHERE sourceMemoryId IN (:ids) " +
            "UNION SELECT ms.memoryId FROM memory_source ms JOIN d ON ms.sourceMemoryId = d.id) SELECT id FROM d",
    )
    abstract suspend fun descendants(ids: List<String>): List<String>
    /** The facts built DIRECTLY from [ids] (one level of `memory_source`): the bounded walk of the Forget scope uses it instead of the unbounded recursive query. */
    @Query("SELECT DISTINCT memoryId FROM memory_source WHERE sourceMemoryId IN (:ids)") abstract suspend fun childrenOf(ids: List<String>): List<String>

    @Query("SELECT * FROM memory WHERE id IN (:ids)") abstract suspend fun memories(ids: List<String>): List<MemoryEntity>
    /** Memories citing [frameId], through `memory.frameId` or a citation `frame_source` row (never CARRIED: that is [carriedMemoryIdsOfFrame]). */
    @Query("SELECT id FROM memory WHERE frameId = :frameId UNION SELECT memoryId FROM frame_source WHERE frameId = :frameId AND role != 'CARRIED'")
    abstract suspend fun memoryIdsOfFrame(frameId: String): List<String>
    /** Codex r16: memories only DERIVED from [frameId] (a CARRIED row, no citation): a Forget of the photo invalidates them, never their own photos. */
    @Query("SELECT memoryId FROM frame_source WHERE frameId = :frameId AND role = 'CARRIED' AND memoryId NOT IN (SELECT id FROM memory WHERE frameId = :frameId) " +
        "AND memoryId NOT IN (SELECT memoryId FROM frame_source WHERE frameId = :frameId AND role != 'CARRIED')")
    abstract suspend fun carriedMemoryIdsOfFrame(frameId: String): List<String>
    /** Frames cited by [memoryIds] through `frame_source` (their `memory.frameId` comes from the memory rows). */
    /** The photos [memoryIds] cite. A `CARRIED` row (codex r15: a photo the memory may be derived from, e.g. a batch mate) is not a citation: Forget of the memory never locks or deletes that photo. */
    @Query("SELECT DISTINCT frameId FROM frame_source WHERE memoryId IN (:memoryIds) AND role != 'CARRIED'") abstract suspend fun sourceFramesOf(memoryIds: List<String>): List<String>
    /** Every `frame_source` row of [memoryIds], any role: the retention lineage records which photo each expired memory belonged to. */
    @Query("SELECT * FROM frame_source WHERE memoryId IN (:memoryIds)") abstract suspend fun frameSourceRowsOf(memoryIds: List<String>): List<FrameSourceEntity>
    @Query("DELETE FROM frame_source WHERE memoryId IN (:memoryIds)") abstract suspend fun deleteFrameSourcesFor(memoryIds: List<String>): Int
    @Query("DELETE FROM frame_vector WHERE frameId IN (:frameIds)") abstract suspend fun deleteFrameVectorsFor(frameIds: List<String>): Int
    @Query("DELETE FROM frame_meta WHERE frameId IN (:frameIds)") abstract suspend fun deleteFrameMetaFor(frameIds: List<String>): Int
    @Query("DELETE FROM frame_state WHERE frameId IN (:frameIds)") abstract suspend fun deleteFrameStateFor(frameIds: List<String>): Int
    /** Wave 3 (S2): how many per-photo rows (metadata, image vector, state) exist for [frameId]; a moment with no `frame` row and no memory still counts. */
    @Query("SELECT (SELECT COUNT(*) FROM frame_meta WHERE frameId = :frameId) + (SELECT COUNT(*) FROM frame_vector WHERE frameId = :frameId) + (SELECT COUNT(*) FROM frame_state WHERE frameId = :frameId)")
    abstract suspend fun photoRowCount(frameId: String): Int
    @Query("DELETE FROM frame_source WHERE frameId IN (:frameIds)") abstract suspend fun deleteFrameSourcesOfFrames(frameIds: List<String>): Int
    @Query("DELETE FROM memory WHERE id IN (:ids)") abstract suspend fun deleteMemories(ids: List<String>): Int
    /** Every embedding space, not only the current one. */
    @Query("DELETE FROM memory_vector WHERE memoryId IN (:ids)") abstract suspend fun deleteVectorsFor(ids: List<String>): Int
    @Query("DELETE FROM memory_source WHERE memoryId IN (:ids)") abstract suspend fun deleteEdgesFrom(ids: List<String>): Int
    @Query("DELETE FROM memory_source WHERE sourceMemoryId IN (:ids)") abstract suspend fun deleteEdgesTo(ids: List<String>): Int
    @Query("UPDATE memory SET reviewState = 'INVALIDATED' WHERE id IN (:ids)") abstract suspend fun invalidate(ids: List<String>): Int
    /** `forgetV2` (F3 residue): an INVALIDATED derived memory is only a placeholder for the re-summarise worker (it reads the id, tier and creation time, never the text): its text, tags and search text (and so its FTS terms, via the triggers) are erased now. */
    @Query("UPDATE memory SET text = '', tags = '', searchText = '' WHERE id IN (:ids)") abstract suspend fun blankText(ids: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertTombstone(t: ForgetTombstoneEntity): Long
    /** Those of [frameIds] that are wave-1+ keyframes (have a `frame_meta` row). */
    @Query("SELECT frameId FROM frame_meta WHERE frameId IN (:frameIds)") abstract suspend fun keyframeIds(frameIds: List<String>): List<String>
    @Query("SELECT frameId FROM forget_tombstone WHERE frameId IN (:frameIds)") abstract suspend fun tombstoned(frameIds: List<String>): List<String>

    @Query("SELECT frameId FROM forget_tombstone") abstract suspend fun allTombstones(): List<String>

    // --- FrameSweep (final review I2): set-based, so a pass costs the same however many tombstones there are -------
    /**
     * Paths of tombstoned frames whose row is kept but that no memory points at any more (their JPEG may go). A wave-1+ keyframe (a
     * `frame_meta` row) never qualifies: it is deleted only by the photo rules (`expirePhotos`) or an explicit Forget / Delete all.
     */
    @Query(
        "SELECT f.path FROM forget_tombstone t JOIN frame f ON f.id = t.frameId " +
            "WHERE f.path != '' AND NOT EXISTS (SELECT 1 FROM memory m WHERE m.frameId = t.frameId) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = t.frameId AND fs.role != 'CARRIED') " +
            "AND NOT EXISTS (SELECT 1 FROM frame_meta fm WHERE fm.frameId = t.frameId)",
    )
    abstract suspend fun orphanTombstonedFramePaths(): List<String>

    /** Those of [frameIds] (file names found on disk) that are tombstoned and have no frame row. Callers chunk. */
    @Query("SELECT t.frameId FROM forget_tombstone t WHERE t.frameId IN (:frameIds) AND NOT EXISTS (SELECT 1 FROM frame f WHERE f.id = t.frameId)")
    abstract suspend fun rowlessTombstones(frameIds: List<String>): List<String>

    /**
     * Drops tombstones nothing needs: created before [beforeMs], no frame row (so no live frame's extraction lock) and no
     * memory pointing at the frame id; [keep] (ids whose JPEG is still on disk) stay for the next sweep. Callers pass
     * at most 500 [keep] ids.
     */
    @Query(
        "DELETE FROM forget_tombstone WHERE createdAt < :beforeMs AND frameId NOT IN (:keep) " +
            "AND NOT EXISTS (SELECT 1 FROM frame f WHERE f.id = forget_tombstone.frameId) " +
            "AND NOT EXISTS (SELECT 1 FROM memory m WHERE m.frameId = forget_tombstone.frameId) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = forget_tombstone.frameId AND fs.role != 'CARRIED')",
    )
    abstract suspend fun pruneTombstones(beforeMs: Long, keep: List<String>): Int

    /** The ids [pruneTombstones] would delete with the same arguments (read in the same transaction, so the expiry markers of exactly those go with them). */
    @Query(
        "SELECT frameId FROM forget_tombstone WHERE createdAt < :beforeMs AND frameId NOT IN (:keep) " +
            "AND NOT EXISTS (SELECT 1 FROM frame f WHERE f.id = forget_tombstone.frameId) " +
            "AND NOT EXISTS (SELECT 1 FROM memory m WHERE m.frameId = forget_tombstone.frameId) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = forget_tombstone.frameId AND fs.role != 'CARRIED')",
    )
    abstract suspend fun prunableTombstones(beforeMs: Long, keep: List<String>): List<String>

    /**
     * Forget lifecycle F1 / F5 (`forgetV2` only, rulings 2026-11-17): a SURVIVING photo whose memory was erased (it was carried or shared with a forgotten photo) goes back to PENDING with the solo marker, so the
     * ordinary extraction pipeline (with its provenance, power and pause gates) describes it ALONE. Only a photo that still has its `frame` row, is not tombstoned and has no memory left; never one being worked on.
     */
    @Query(
        "UPDATE frame_state SET state = 'PENDING', attempts = 0, lastError = '" + SOLO_REBUILD + "', claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE frameId IN (:frameIds) AND state IN ('EXTRACTED','FAILED','FAILED_FINAL') " +
            "AND EXISTS (SELECT 1 FROM frame f WHERE f.id = frame_state.frameId) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = frame_state.frameId) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = frame_state.frameId AND fs.role != 'CARRIED') " +
            "AND NOT EXISTS (SELECT 1 FROM memory m WHERE m.frameId = frame_state.frameId)",
    )
    abstract suspend fun requeueSolo(frameIds: List<String>, nowMs: Long): Int

    /** Memories (any review state) that still cite [frameId], via `memory.frameId` or `frame_source`. */
    @Query("SELECT (SELECT COUNT(*) FROM memory WHERE frameId = :frameId) + (SELECT COUNT(*) FROM frame_source WHERE frameId = :frameId AND role != 'CARRIED')") abstract suspend fun remainingRefs(frameId: String): Int
    /** Codex r12: 1 when a memory still DESCRIBES [frameId] ([DESCRIBED_BY_EXTRACTION]), else 0: the expiry recheck of every deadline-based delete. */
    @Query("SELECT COUNT(*) FROM frame f WHERE f.id = :frameId AND " + DESCRIBED_BY_EXTRACTION) abstract suspend fun describedRefs(frameId: String): Int
    /**
     * Codex r12 Diagnostics: keyframes (not forgotten) that some memory cites but none describes: only canvas, derived, NEEDS_REVIEW or cluster-member
     * rows cite them. The night job describes them before their deadline; until then they are kept.
     */
    @Query("SELECT COUNT(*) FROM frame f JOIN frame_meta m ON m.frameId = f.id WHERE NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = f.id) AND " + CITED_NOT_DESCRIBED)
    abstract suspend fun strandedPhotos(): Int
    // --- Room v16 (user ruling 2026-11-10): suspect descriptions of the old text-only retry, and the user's "Mark for review" -------------------------
    /**
     * Pre-fix memories that may describe a photo they never saw: delivery unknown (written before v16), created before the build that ended the text-only
     * retry ([TEXT_RETRY_FIXED_MS]), not yet marked, and today counted as a photo's description ([SUSPECT]). The database keeps no trace of which calls
     * fell back to text, so this is every such row, not only the fabricated ones. Newest first.
     */
    @Query("SELECT a.id AS id, a.frameId AS frameId, a.type AS type, a.createdAt AS createdAt, a.text AS text FROM memory a WHERE " + SUSPECT + " ORDER BY a.createdAt DESC, a.id LIMIT :limit")
    abstract suspend fun suspectDescriptions(limit: Int): List<SuspectDescriptionRow>
    @Query("SELECT COUNT(*) FROM memory a WHERE " + SUSPECT) abstract suspend fun suspectDescriptionCount(): Int
    /**
     * Codex r20: the newest suspects AND the total, observed as ONE query (Room re-runs it when memory, frame or frame_source change), so the count and the
     * snippets always come from the same snapshot: a deleted memory can never leave a snippet beside a count of zero. No rows means a total of 0.
     */
    @Query("SELECT a.id AS id, a.frameId AS frameId, a.type AS type, a.createdAt AS createdAt, a.text AS text, (SELECT COUNT(*) FROM memory a WHERE " + SUSPECT + ") AS total " +
        "FROM memory a WHERE " + SUSPECT + " ORDER BY a.createdAt DESC, a.id LIMIT :limit")
    abstract fun observeSuspects(limit: Int): kotlinx.coroutines.flow.Flow<List<SuspectObservedRow>>
    /** Marks [ids] (still suspects) for review at [at]; returns how many. Only the flag: no text, photo or provenance changes. */
    @Query("UPDATE memory SET reviewFlaggedAt = :at WHERE id IN (:ids) AND reviewFlaggedAt IS NULL") abstract suspend fun flagForReview(ids: List<String>, at: Long): Int
    /** Marks every current suspect at [at]; returns how many. */
    @Query("UPDATE memory SET reviewFlaggedAt = :at WHERE id IN (SELECT a.id FROM memory a WHERE " + SUSPECT + ")") abstract suspend fun flagAllSuspects(at: Long): Int
    /** Undo: clears exactly the marks made at [at]. */
    @Query("UPDATE memory SET reviewFlaggedAt = NULL WHERE reviewFlaggedAt = :at") abstract suspend fun unflag(at: Long): Int

    // --- v1.1 row 8 (user ruling 2026-11-16): the night job over the suspects the user marked (no schema change: queries only) ------------------------
    /** Marked suspects whose photo(s) are all gone (expired): their description is removed. Oldest first. */
    @Query("SELECT a.id FROM memory a WHERE " + MARKED_SUSPECT + " AND NOT " + SUSPECT_PHOTO_STORED + " ORDER BY a.createdAt, a.id LIMIT :limit")
    abstract suspend fun markedSuspectsExpired(limit: Int): List<String>
    /** Marked suspects with at least one photo still stored: they are re-described. Oldest first. */
    @Query("SELECT a.id FROM memory a WHERE " + MARKED_SUSPECT + " AND " + SUSPECT_PHOTO_STORED + " ORDER BY a.createdAt, a.id LIMIT :limit")
    abstract suspend fun markedSuspectsStored(limit: Int): List<String>
    /** The photos of [memoryId] that still have a `frame` row (memory.frameId plus its FACT / CLUSTER_REP sources). */
    @Query("SELECT f.id FROM frame f WHERE f.id = (SELECT m.frameId FROM memory m WHERE m.id = :memoryId) OR f.id IN (SELECT fs.frameId FROM frame_source fs WHERE fs.memoryId = :memoryId AND fs.role IN ('FACT','CLUSTER_REP')) ORDER BY f.id")
    abstract suspend fun storedPhotosOf(memoryId: String): List<String>
    /** How many other memories describe [frameId] WITH proof the image reached the model (`imageDelivered = 1`), unmarked and not [selfId]. */
    @Query("SELECT COUNT(*) FROM memory a WHERE a.id != :selfId AND a.imageDelivered = 1 AND " + DESCRIBES + " AND (a.frameId = :frameId OR EXISTS (SELECT 1 FROM frame_source fs WHERE fs.memoryId = a.id AND fs.frameId = :frameId AND fs.role IN ('FACT','CLUSTER_REP')))")
    abstract suspend fun describedWithProof(frameId: String, selfId: String): Int

    /** Retention v3 (r2-4): the capture time of a photo from whatever is left of it (`frame.ts`, then `frame_meta.capturedAtMs`, then `frame_meta.createdAt`), or null. */
    @Query("SELECT COALESCE((SELECT ts FROM frame WHERE id = :id), (SELECT COALESCE(capturedAtMs, createdAt) FROM frame_meta WHERE frameId = :id))") abstract suspend fun captureTsOf(id: String): Long?
    @Query("SELECT * FROM frame WHERE id = :id") abstract suspend fun frame(id: String): FrameEntity?
    @Query("DELETE FROM frame WHERE id = :id") abstract suspend fun deleteFrame(id: String): Int

    /**
     * Retention (legacy pass): frames older than [beforeMs] that no memory points at. A frame not yet extracted or still owed work
     * (frame_state PENDING, EXTRACTING, FAILED, IN_CLUSTER or CAPTION_QUEUED) is kept: retention must not forget a photo the pipeline
     * still owes a memory (review B M-6). Wave-1+ keyframes (a `frame_meta` row; Ruling 14) follow the per-kind rules instead, and so
     * does any frame whose cluster is not yet SUMMARIZED or FAILED_FINAL.
     */
    @Query("SELECT id FROM frame WHERE ts < :beforeMs AND NOT EXISTS (SELECT 1 FROM memory m WHERE m.frameId = frame.id) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = frame.id AND fs.role != 'CARRIED') " +
            "AND NOT EXISTS (SELECT 1 FROM frame_meta fm WHERE fm.frameId = frame.id) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_state s WHERE s.frameId = frame.id AND s.state IN ('PENDING','EXTRACTING','FAILED','IN_CLUSTER','CAPTION_QUEUED')) " +
            "ORDER BY ts LIMIT :limit")
    abstract suspend fun orphanFramesBefore(beforeMs: Long, limit: Int): List<String>

    /**
     * Retention, ordinary skipped photos (spec §P6.1): a keyframe (has `frame_meta`, not tombstoned) that was shed, a semantic near-dup
     * (NEAR_DUP) or a non-representative member of a summarised cluster (CLUSTERED, no CLUSTER_REP `frame_source` row), with a recorded salience below
     * 0.5 (never an unscored wave-1 frame: final review I1) and no dwell: never a key moment, never a representative. A frame in a cluster that is not SUMMARIZED or FAILED_FINAL is
     * never returned. Deadline: `max(frame_state.updatedAt, [since]) + [windowMs] <= [nowMs]`. Callers expire in chunks; the same frames
     * come back until their row is gone, so a failed chunk is retried by the next pass.
     */
    @Query(
        "SELECT f.id FROM frame f JOIN frame_meta m ON m.frameId = f.id JOIN frame_state s ON s.frameId = f.id " +
            "WHERE s.state IN ('NEAR_DUP','CLUSTERED') " + SKIPPED_PHOTO +
            "AND MAX(s.updatedAt, :since) + :windowMs <= :nowMs AND (f.ts >= :since OR :since + :protectMs <= :nowMs) ORDER BY s.updatedAt, f.id LIMIT :limit",
    )
    abstract suspend fun skippedPhotosDue(since: Long, windowMs: Long, nowMs: Long, protectMs: Long, limit: Int): List<String>

    /** S4: 1 when [frameId] (it has a `frame` row) is still an ordinary skipped photo right now (the [SKIPPED_PHOTO] rule, without the deadline). */
    @Query(
        "SELECT COUNT(*) FROM frame f JOIN frame_meta m ON m.frameId = f.id JOIN frame_state s ON s.frameId = f.id " +
            "WHERE f.id = :frameId AND s.state IN ('NEAR_DUP','CLUSTERED') " + SKIPPED_PHOTO,
    )
    abstract suspend fun stillSkippedPhoto(frameId: String): Int

    /**
     * Per-kind photo retention: keyframes past `max(frame.ts, [since]) + [windowMs]` that a memory DESCRIBES ([DESCRIBED_BY_EXTRACTION], codex r11:
     * an OK, non-derived memory that is not a canvas save, cited through `memory.frameId` or `frame_source`).
     * A photo the night job described for describe-before-delete (`lastError = describe_before_delete`) waits [graceMs] more, and only when
     * [describeReady] is 1. A photo no memory cites is never returned: an undescribed photo is never deleted (the night job describes it first).
     */
    @Query(
        "SELECT f.id FROM frame f JOIN frame_meta m ON m.frameId = f.id JOIN frame_state s ON s.frameId = f.id " +
            "WHERE " + PHOTO_ELIGIBLE +
            "AND " + DESCRIBED_BY_EXTRACTION +
            "AND (COALESCE(s.lastError, '') != 'describe_before_delete' OR (:describeReady = 1 AND MAX(MAX(f.ts, :since) + :windowMs, " +
            // the grace counts from the later of the deadline and the describe (the earliest DESCRIBING memory's creation, codex r13: the shared rule, so an
            // older canvas or derived citation cannot shorten it), so a photo described after offline nights is kept 3 days from its caption
            "(SELECT MIN(c) FROM (SELECT a.createdAt AS c FROM memory a WHERE a.frameId = f.id AND " + DESCRIBES + " UNION ALL " +
            "SELECT a.createdAt FROM frame_source fs2 JOIN memory a ON a.id = fs2.memoryId WHERE fs2.frameId = f.id AND fs2.role IN ('FACT','CLUSTER_REP') AND " + DESCRIBES +
            "))) + :graceMs <= :nowMs)) " +
            "AND MAX(f.ts, :since) + :windowMs <= :nowMs AND (f.ts >= :since OR :since + :protectMs <= :nowMs) ORDER BY f.ts, f.id LIMIT :limit",
    )
    abstract suspend fun linkedPhotosDue(since: Long, windowMs: Long, nowMs: Long, protectMs: Long, describeReady: Int, graceMs: Long, limit: Int): List<String>

    /**
     * Night job (Task 9): keyframes that become due within [aheadMs] (`max(frame.ts, [since]) + [windowMs] <= [nowMs] + [aheadMs]`), that no
     * memory cites, and that are not ordinary skipped photos, a cluster still being summarised, or owed work (the [PHOTO_ELIGIBLE] rule),
     * in one of the settled states, deadline first. They are the photos describe-before-delete must caption before they may expire.
     */
    @Query(
        "SELECT f.id FROM frame f JOIN frame_meta m ON m.frameId = f.id JOIN frame_state s ON s.frameId = f.id " +
            "WHERE s.state IN ('FAILED_FINAL','EXTRACTED','NEAR_DUP','CLUSTERED') AND " + PHOTO_ELIGIBLE +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = f.id) " +
            "AND COALESCE(s.lastError, '') != 'describe_failed' AND NOT (s.lastError = 'describe_before_delete' AND COALESCE(s.claimedAt, 0) > :nowMs) " + // parked, or in its cooldown
            "AND NOT " + DESCRIBED_BY_EXTRACTION + // codex r12: a photo only canvas/derived/review/member rows cite is still describable
            "AND MAX(f.ts, :since) + :windowMs <= :nowMs + :aheadMs AND (f.ts >= :since OR :since + :protectMs <= :nowMs + :aheadMs) " +
            "ORDER BY MAX(f.ts, :since), f.id LIMIT :limit",
    )
    abstract suspend fun describeDue(since: Long, windowMs: Long, nowMs: Long, aheadMs: Long, protectMs: Long, limit: Int): List<String>

    /** How many of [frameIds] some memory cites now (`memory.frameId` or `frame_source`); not the description rule (that is [describedRefs]). */
    @Query(
        "SELECT COUNT(*) FROM frame f WHERE f.id IN (:frameIds) AND (EXISTS (SELECT 1 FROM memory x WHERE x.frameId = f.id) " +
            "OR EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = f.id AND fs.role != 'CARRIED'))",
    )
    abstract suspend fun citedCount(frameIds: List<String>): Int

    /**
     * A keyframe still CAPTION_QUEUED [ageMs] after `max(its last change, since)` becomes a skipped photo: NEAR_DUP with lastError
     * `caption_expired` (the skipped rules then apply, behind the upgrade protection). Returns how many moved.
     */
    @Query(
        "UPDATE frame_state SET state = 'NEAR_DUP', lastError = 'caption_expired', claimedAt = NULL, claimId = NULL, updatedAt = :nowMs " +
            "WHERE state = 'CAPTION_QUEUED' AND COALESCE(lastError, '') != 'describe_before_delete' AND MAX(updatedAt, :since) + :ageMs <= :nowMs " +
            // a key moment (dwell or salience >= 0.5) is never converted: it waits for its caption (the night job's bounded retry parks it, nothing deletes it)
            "AND frameId IN (SELECT k.frameId FROM frame_meta k WHERE k.dwell = 0 AND k.salience IS NOT NULL AND k.salience < 0.5) " +
            "AND frameId IN (SELECT m.frameId FROM frame_meta m WHERE NOT EXISTS (SELECT 1 FROM frame_cluster c WHERE c.clusterId = m.clusterId AND c.state NOT IN ('SUMMARIZED','FAILED_FINAL')))",
    )
    abstract suspend fun expireCaptionQueued(since: Long, ageMs: Long, nowMs: Long): Int

    /**
     * Image vectors of keyframes created before [beforeMs] (by `frame_meta.createdAt`), all spaces. Returns the frame ids whose vectors were
     * deleted; [markVectorsExpired] then stops the backfill from re-embedding them.
     */
    @Query("SELECT DISTINCT v.frameId FROM frame_vector v JOIN frame_meta m ON m.frameId = v.frameId WHERE m.createdAt < :beforeMs ORDER BY v.frameId LIMIT :limit")
    abstract suspend fun vectorFramesBefore(beforeMs: Long, limit: Int): List<String>
    @Query("UPDATE frame_meta SET imageVectorSpace = 'expired' WHERE frameId IN (:frameIds) AND (imageVectorSpace IS NULL OR imageVectorSpace != 'unembeddable')")
    abstract suspend fun markVectorsExpired(frameIds: List<String>): Int

    /**
     * Retention v3 DEMOTION: SHORT facts (never a caption or a cluster summary) created before [beforeMs] that still have a text vector in any space. Their rows, edges and text are NEVER touched: only the vectors go
     * ([deleteVectorsFor]), so they leave the semantic pool and the scan stays bounded, while a nudge, answer, Moment or card that cites them keeps its source.
     */
    @Query("SELECT m.id FROM memory m WHERE m.tier = 'SHORT' AND m.type NOT IN ('caption','cluster_summary') AND m.createdAt < :beforeMs AND EXISTS (SELECT 1 FROM memory_vector v WHERE v.memoryId = m.id) ORDER BY m.createdAt, m.id LIMIT :limit")
    abstract suspend fun demotableFactIds(beforeMs: Long, limit: Int): List<String>
    /** Counts-only plan line: how many facts [demotableFactIds] would return, uncapped. */
    @Query("SELECT COUNT(*) FROM memory m WHERE m.tier = 'SHORT' AND m.type NOT IN ('caption','cluster_summary') AND m.createdAt < :beforeMs AND EXISTS (SELECT 1 FROM memory_vector v WHERE v.memoryId = m.id)")
    abstract suspend fun countDemotable(beforeMs: Long): Int

    /**
     * Retention v3 QUOTA EVICTION (K6): the oldest keyframe photos a memory DESCRIBES ([DESCRIBED_BY_EXTRACTION]: describe-before-delete holds), with a `frame` row, not tombstoned, captured before [olderThanMs], that carry NO
     * explicit importance: no `place` memory cites them, no mark lies within [radiusMs] of their capture time, no accepted (EXECUTING / EXECUTED) proposal's nudge or answer cites them or a memory of them (`memory.frameId` OR any `frame_source` row: a batch fact's secondary photo, Codex v3 r1 P1-9). [protectedIds] (photos named
     * by place cards or pins, which live outside this database; pass at least one id) are excluded as well. Oldest first, so a failed chunk is retried by the next pass.
     */
    @Query(
        "SELECT f.id FROM frame f JOIN frame_meta m ON m.frameId = f.id JOIN frame_state s ON s.frameId = f.id " +
            "WHERE f.ts < :olderThanMs AND s.state NOT IN ('PENDING','EXTRACTING','FAILED','IN_CLUSTER','CAPTION_QUEUED') AND f.id NOT IN (:protectedIds) " +
            "AND NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = f.id) AND " + DESCRIBED_BY_EXTRACTION +
            "AND NOT EXISTS (SELECT 1 FROM memory pm WHERE pm.type = 'place' AND (pm.frameId = f.id OR EXISTS (SELECT 1 FROM frame_source pf WHERE pf.memoryId = pm.id AND pf.frameId = f.id))) " +
            "AND NOT EXISTS (SELECT 1 FROM canvas_label l WHERE l.atMs BETWEEN f.ts - :radiusMs AND f.ts + :radiusMs) " +
            "AND NOT EXISTS (SELECT 1 FROM proposal p JOIN nudge n ON n.id = p.originId WHERE p.state IN ('EXECUTING','EXECUTED') AND ((n.sourceIdsJson LIKE '%\"' || f.id || '\"%' OR n.sourceIdsJson LIKE '%\"frame:' || f.id || '\"%') " +
            "OR EXISTS (SELECT 1 FROM memory cm WHERE (cm.frameId = f.id OR EXISTS (SELECT 1 FROM frame_source cfs WHERE cfs.memoryId = cm.id AND cfs.frameId = f.id)) AND n.sourceIdsJson LIKE '%\"' || cm.id || '\"%'))) " +
            "AND NOT EXISTS (SELECT 1 FROM proposal p JOIN ask a ON a.id = p.originId WHERE p.state IN ('EXECUTING','EXECUTED') AND ((a.sourcesJson LIKE '%\"' || f.id || '\"%' OR a.sourcesJson LIKE '%\"frame:' || f.id || '\"%') " +
            "OR EXISTS (SELECT 1 FROM memory cm WHERE (cm.frameId = f.id OR EXISTS (SELECT 1 FROM frame_source cfs WHERE cfs.memoryId = cm.id AND cfs.frameId = f.id)) AND a.sourcesJson LIKE '%\"' || cm.id || '\"%'))) " +
            "ORDER BY f.ts, f.id LIMIT :limit",
    )
    abstract suspend fun quotaPhotosDue(olderThanMs: Long, radiusMs: Long, protectedIds: List<String>, limit: Int): List<String>

    /** Quota eviction (K6): the photos captured within `[fromMs, toMs]` (the second of a pinned Moment). */
    @Query("SELECT id FROM frame WHERE ts >= :fromMs AND ts <= :toMs") abstract suspend fun frameIdsBetween(fromMs: Long, toMs: Long): List<String>

    /** [likePattern] escapes `\`, `%` and `_` with `\`. It is only a prefilter: the caller parses the JSON exactly. */
    @Query("SELECT * FROM ask WHERE redacted = 0 AND sourcesJson LIKE :likePattern ESCAPE '\\'") abstract suspend fun asksCiting(likePattern: String): List<AskEntity>
    @Query("SELECT * FROM nudge WHERE redacted = 0 AND sourceIdsJson LIKE :likePattern ESCAPE '\\'") abstract suspend fun nudgesCiting(likePattern: String): List<NudgeEntity>
    @Query("UPDATE ask SET answer = :text, sourcesJson = '[]', redacted = 1 WHERE id = :id") abstract suspend fun redactAsk(id: String, text: String): Int
    /** M5 (`forgetV2`): the answer is erased, the QUESTION stays, and the source state says why (the row offers "Ask again"). */
    @Query("UPDATE ask SET answer = :text, sourcesJson = '[]', redacted = 1, sourceStates = :states WHERE id = :id") abstract suspend fun redactAskForgotten(id: String, text: String, states: String): Int
    /** M5 prefilter: answers whose `sourceStates` (cited or history ids) may contain an id; the caller parses the JSON exactly. */
    @Query("SELECT * FROM ask WHERE redacted = 0 AND sourceStates LIKE :likePattern ESCAPE '\\'") abstract suspend fun asksCitingStates(likePattern: String): List<AskEntity>
    /** M5 N1 (`forgetV2`): a nudge reached by a dependency is deleted (nudges are time-bound; the proposals are expired and scrubbed by their own family). */
    @Query("DELETE FROM nudge WHERE id = :id") abstract suspend fun deleteNudge(id: String): Int
    @Query("UPDATE nudge SET text = :text, why = '', sourceIdsJson = '[]', redacted = 1 WHERE id = :id") abstract suspend fun redactNudge(id: String, text: String): Int

    // --- provenance edges, used by the reprocess path and the re-summarise worker --------------------------------
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertEdges(edges: List<MemorySourceEntity>)
    @Query("SELECT * FROM memory_source WHERE memoryId IN (:ids)") abstract suspend fun edgesFrom(ids: List<String>): List<MemorySourceEntity>
    @Query("SELECT * FROM memory_source WHERE sourceMemoryId IN (:ids)") abstract suspend fun edgesTo(ids: List<String>): List<MemorySourceEntity>
    @Query("SELECT * FROM memory WHERE reviewState = 'INVALIDATED' AND derived = 1 ORDER BY createdAt LIMIT :limit")
    abstract suspend fun invalidatedDerived(limit: Int): List<MemoryEntity>

    companion object {
        /** `frame_state.lastError` of a photo queued by a Forget to be described ALONE (F1 / F5): the extraction pipeline claims such a photo without any batch mate. */
        const val SOLO_REBUILD = "solo_rebuild"
        /** A frame whose cluster is still being built, summarised or retried is never expired. */
        /**
         * Not a cluster representative: no CLUSTER_REP `frame_source` row, and not in its cluster's `repFrameIds` (the row goes with an expired
         * summary; the cluster keeps the list). LIKE may over-match ids with `_`: that only protects a frame.
         */
        const val NOT_A_REP =
            "AND NOT EXISTS (SELECT 1 FROM frame_source r WHERE r.frameId = f.id AND r.role = 'CLUSTER_REP') " +
                "AND NOT EXISTS (SELECT 1 FROM frame_cluster rc WHERE rc.clusterId = m.clusterId AND rc.repFrameIds LIKE '%\"' || f.id || '\"%') "
        const val CLUSTER_SETTLED =
            "AND NOT EXISTS (SELECT 1 FROM frame_cluster c WHERE c.clusterId = m.clusterId AND c.state NOT IN ('SUMMARIZED','FAILED_FINAL')) "
        /**
         * Final review I1: a skipped photo needs a REAL salience below 0.5, recorded by the scene indexer. Wave 1 never scored salience
         * (`frame_meta.salience IS NULL` means "never scored", not "non-salient"), so its shed and near-dup trip photos are not skipped: they
         * follow the photo path, and the night job describes them before any delete.
         */
        const val SCORED_LOW = "(m.salience IS NOT NULL AND m.salience < 0.5)"
        /**
         * Codex r11/r12: THE qualifying-description predicate, the one rule for "this photo is described" used by photo retention ([linkedPhotosDue]),
         * the skipped-photo exemption ([NOT_DESCRIBED], S3), the night describe queue ([describeDue], `FrameStateDao.queueForDescribe`), the
         * expiry recheck ([describedRefs]) and the stranded count ([strandedPhotos]). A memory describes a photo only when it is review state OK, not
         * derived (no summary of other memories) and not a canvas save (`CanvasRunSave` stamps its rows `rokid_cv_`; they stay searchable but never
         * authorise a delete), AND it cites that photo as delivered: `memory.frameId`, or a `frame_source` row of role FACT or CLUSTER_REP (the photos a
         * call actually sent). A CLUSTER_MEMBER link never describes a member photo: the scene summary never saw it. GLOB: `_` is literal.
         */
        /** The build that ended the text-only retry (commit 4eb149c2, 2026-11-07 21:05:17 UTC): nothing written after it can be a text-retry caption. */
        const val TEXT_RETRY_FIXED_MS = 1_794_085_517_000L
        /** [suspectDescriptions]: a pre-fix, delivery-unknown, unmarked memory that [DESCRIBES] a photo through memory.frameId or a FACT/CLUSTER_REP row. */
        const val SUSPECT = "a.imageDelivered IS NULL AND a.createdAt < 1794085517000 AND a.reviewFlaggedAt IS NULL AND a.reviewState = 'OK' AND a.derived = 0 " +
            "AND a.id NOT GLOB 'rokid_cv_*' " +
            // opus r19: only while the described photo still exists (marking an expired photo's memory would change nothing)
            "AND EXISTS (SELECT 1 FROM frame f WHERE f.id = a.frameId OR f.id IN (SELECT fs.frameId FROM frame_source fs WHERE fs.memoryId = a.id AND fs.role IN ('FACT','CLUSTER_REP'))) "
        /**
         * Row 8: the suspects the user MARKED for review (the confirmation: nothing is removed or re-described unmarked). A pre-fix, delivery-unknown, OK, non-derived,
         * non-canvas memory that describes a photo (memory.frameId or a FACT/CLUSTER_REP row), marked.
         */
        const val MARKED_SUSPECT = "a.imageDelivered IS NULL AND a.createdAt < 1794085517000 AND a.reviewFlaggedAt IS NOT NULL AND a.reviewState = 'OK' AND a.derived = 0 " +
            "AND a.id NOT GLOB 'rokid_cv_*' " +
            "AND (a.frameId IS NOT NULL OR EXISTS (SELECT 1 FROM frame_source fs0 WHERE fs0.memoryId = a.id AND fs0.role IN ('FACT','CLUSTER_REP'))) "
        /** At least one photo this memory describes still has a `frame` row. */
        const val SUSPECT_PHOTO_STORED = "EXISTS (SELECT 1 FROM frame f WHERE f.id = a.frameId OR f.id IN (SELECT fs.frameId FROM frame_source fs WHERE fs.memoryId = a.id AND fs.role IN ('FACT','CLUSTER_REP'))) "
        // Room v16 (user ruling 2026-11-10): a memory marked for review, or written without delivery proof (imageDelivered = 0), never describes;
        // unknown (null, every pre-v16 row) keeps the old behaviour so good old captions do not strand their photos
        const val DESCRIBES = "a.reviewState = 'OK' AND a.derived = 0 AND a.id NOT GLOB 'rokid_cv_*' AND a.reviewFlaggedAt IS NULL AND COALESCE(a.imageDelivered, 1) = 1"
        const val DESCRIBED_BY_EXTRACTION =
            "(EXISTS (SELECT 1 FROM memory a WHERE a.frameId = f.id AND $DESCRIBES) " +
                "OR EXISTS (SELECT 1 FROM frame_source fs JOIN memory a ON a.id = fs.memoryId WHERE fs.frameId = f.id AND fs.role IN ('FACT','CLUSTER_REP') AND $DESCRIBES)) "
        /** [DESCRIBED_BY_EXTRACTION] for a query over `frame_state` (no `f` alias). */
        const val STATE_DESCRIBED =
            "(EXISTS (SELECT 1 FROM memory a WHERE a.frameId = frame_state.frameId AND $DESCRIBES) " +
                "OR EXISTS (SELECT 1 FROM frame_source fs JOIN memory a ON a.id = fs.memoryId WHERE fs.frameId = frame_state.frameId AND fs.role IN ('FACT','CLUSTER_REP') AND $DESCRIBES)) "
        /** S3, codex r12: a photo DESCRIBED ([DESCRIBED_BY_EXTRACTION]) leaves the skipped-photo rule for good; a canvas or member citation does not. */
        const val NOT_DESCRIBED = "AND NOT $DESCRIBED_BY_EXTRACTION"
        /** Codex r12: cited by some memory (`memory.frameId` or any `frame_source` row) yet not described: the photos only canvas/derived/review/member rows cite. */
        const val CITED_NOT_DESCRIBED =
            "(EXISTS (SELECT 1 FROM memory x WHERE x.frameId = f.id) OR EXISTS (SELECT 1 FROM frame_source x WHERE x.frameId = f.id AND x.role != 'CARRIED')) AND NOT $DESCRIBED_BY_EXTRACTION"
        /** Skipped-photo guard, shared by [skippedPhotosDue] and the photo query that must leave those frames to it. */
        const val SKIPPED_PHOTO =
            "AND COALESCE(s.lastError, '') != 'data_saver' AND ($SCORED_LOW OR COALESCE(s.lastError, '') = 'caption_expired') AND m.dwell = 0 " + // a frame parked for Wi-Fi is never a skipped photo
                NOT_A_REP + NOT_DESCRIBED + CLUSTER_SETTLED
        /** Photo retention applies to every keyframe not owed work, not forgotten, not a skipped photo (those follow [SKIPPED_PHOTO]'s window). */
        const val PHOTO_ELIGIBLE =
            "s.state NOT IN ('PENDING','EXTRACTING','FAILED','IN_CLUSTER','CAPTION_QUEUED') " + CLUSTER_SETTLED +
                "AND NOT (s.state IN ('NEAR_DUP','CLUSTERED') AND ($SCORED_LOW OR COALESCE(s.lastError, '') = 'caption_expired') AND m.dwell = 0 " + NOT_A_REP + NOT_DESCRIBED + ") "
    }
}

/** One row of [ForgetDao.observeSuspects]: a suspect-description row plus [total], the count of ALL suspects from the same snapshot. */
data class SuspectObservedRow(val id: String, val frameId: String?, val type: String, val createdAt: Long, val text: String, val total: Int)
/** One row of the v16 suspect-description list (Diagnostics), from [ForgetDao.suspectDescriptions]. */
data class SuspectDescriptionRow(val id: String, val frameId: String?, val type: String, val createdAt: Long, val text: String)
