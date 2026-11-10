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
    /** Memories citing [frameId], through `memory.frameId` or `frame_source`. */
    @Query("SELECT id FROM memory WHERE frameId = :frameId UNION SELECT memoryId FROM frame_source WHERE frameId = :frameId")
    abstract suspend fun memoryIdsOfFrame(frameId: String): List<String>
    /** Frames cited by [memoryIds] through `frame_source` (their `memory.frameId` comes from the memory rows). */
    @Query("SELECT DISTINCT frameId FROM frame_source WHERE memoryId IN (:memoryIds)") abstract suspend fun sourceFramesOf(memoryIds: List<String>): List<String>
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
            "AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = t.frameId) " +
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
            "AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = forget_tombstone.frameId)",
    )
    abstract suspend fun pruneTombstones(beforeMs: Long, keep: List<String>): Int

    /** Memories (any review state) that still cite [frameId], via `memory.frameId` or `frame_source`. */
    @Query("SELECT (SELECT COUNT(*) FROM memory WHERE frameId = :frameId) + (SELECT COUNT(*) FROM frame_source WHERE frameId = :frameId)") abstract suspend fun remainingRefs(frameId: String): Int
    /** Codex r12: 1 when a memory still DESCRIBES [frameId] ([DESCRIBED_BY_EXTRACTION]), else 0: the expiry recheck of every deadline-based delete. */
    @Query("SELECT COUNT(*) FROM frame f WHERE f.id = :frameId AND " + DESCRIBED_BY_EXTRACTION) abstract suspend fun describedRefs(frameId: String): Int
    /**
     * Codex r12 Diagnostics: keyframes (not forgotten) that some memory cites but none describes: only canvas, derived, NEEDS_REVIEW or cluster-member
     * rows cite them. The night job describes them before their deadline; until then they are kept.
     */
    @Query("SELECT COUNT(*) FROM frame f JOIN frame_meta m ON m.frameId = f.id WHERE NOT EXISTS (SELECT 1 FROM forget_tombstone t WHERE t.frameId = f.id) AND " + CITED_NOT_DESCRIBED)
    abstract suspend fun strandedPhotos(): Int
    @Query("SELECT * FROM frame WHERE id = :id") abstract suspend fun frame(id: String): FrameEntity?
    @Query("DELETE FROM frame WHERE id = :id") abstract suspend fun deleteFrame(id: String): Int

    /**
     * Retention (legacy pass): frames older than [beforeMs] that no memory points at. A frame not yet extracted or still owed work
     * (frame_state PENDING, EXTRACTING, FAILED, IN_CLUSTER or CAPTION_QUEUED) is kept: retention must not forget a photo the pipeline
     * still owes a memory (review B M-6). Wave-1+ keyframes (a `frame_meta` row; Ruling 14) follow the per-kind rules instead, and so
     * does any frame whose cluster is not yet SUMMARIZED or FAILED_FINAL.
     */
    @Query("SELECT id FROM frame WHERE ts < :beforeMs AND NOT EXISTS (SELECT 1 FROM memory m WHERE m.frameId = frame.id) " +
            "AND NOT EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = frame.id) " +
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
            // the grace counts from the later of the deadline and the describe (the earliest citing memory's creation), so a photo described
            // after offline nights is kept 3 days from its caption, not deleted within hours of it
            "(SELECT MIN(c) FROM (SELECT x.createdAt AS c FROM memory x WHERE x.frameId = f.id UNION ALL " +
            "SELECT y.createdAt FROM frame_source fs2 JOIN memory y ON y.id = fs2.memoryId WHERE fs2.frameId = f.id))) + :graceMs <= :nowMs)) " +
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
            "OR EXISTS (SELECT 1 FROM frame_source fs WHERE fs.frameId = f.id))",
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

    /** Memories of [type] created before [beforeMs], in any review state. */
    @Query("SELECT id FROM memory WHERE type = :type AND createdAt < :beforeMs ORDER BY createdAt LIMIT :limit")
    abstract suspend fun expiredIdsOfType(type: String, beforeMs: Long, limit: Int): List<String>

    /**
     * Image vectors of keyframes created before [beforeMs] (by `frame_meta.createdAt`), all spaces. Returns the frame ids whose vectors were
     * deleted; [markVectorsExpired] then stops the backfill from re-embedding them.
     */
    @Query("SELECT DISTINCT v.frameId FROM frame_vector v JOIN frame_meta m ON m.frameId = v.frameId WHERE m.createdAt < :beforeMs ORDER BY v.frameId LIMIT :limit")
    abstract suspend fun vectorFramesBefore(beforeMs: Long, limit: Int): List<String>
    @Query("UPDATE frame_meta SET imageVectorSpace = 'expired' WHERE frameId IN (:frameIds) AND (imageVectorSpace IS NULL OR imageVectorSpace != 'unembeddable')")
    abstract suspend fun markVectorsExpired(frameIds: List<String>): Int

    /** Retention: memories of [tier] created before [beforeMs], in any review state. Captions and cluster summaries have their own kinds (Ruling 12). */
    @Query("SELECT id FROM memory WHERE tier = :tier AND type NOT IN ('caption','cluster_summary') AND createdAt < :beforeMs ORDER BY createdAt LIMIT :limit")
    abstract suspend fun expiredIds(tier: String, beforeMs: Long, limit: Int): List<String>

    /** [likePattern] escapes `\`, `%` and `_` with `\`. It is only a prefilter: the caller parses the JSON exactly. */
    @Query("SELECT * FROM ask WHERE redacted = 0 AND sourcesJson LIKE :likePattern ESCAPE '\\'") abstract suspend fun asksCiting(likePattern: String): List<AskEntity>
    @Query("SELECT * FROM nudge WHERE redacted = 0 AND sourceIdsJson LIKE :likePattern ESCAPE '\\'") abstract suspend fun nudgesCiting(likePattern: String): List<NudgeEntity>
    @Query("UPDATE ask SET answer = :text, sourcesJson = '[]', redacted = 1 WHERE id = :id") abstract suspend fun redactAsk(id: String, text: String): Int
    @Query("UPDATE nudge SET text = :text, why = '', sourceIdsJson = '[]', redacted = 1 WHERE id = :id") abstract suspend fun redactNudge(id: String, text: String): Int

    // --- provenance edges, used by the reprocess path and the re-summarise worker --------------------------------
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertEdges(edges: List<MemorySourceEntity>)
    @Query("SELECT * FROM memory_source WHERE memoryId IN (:ids)") abstract suspend fun edgesFrom(ids: List<String>): List<MemorySourceEntity>
    @Query("SELECT * FROM memory_source WHERE sourceMemoryId IN (:ids)") abstract suspend fun edgesTo(ids: List<String>): List<MemorySourceEntity>
    @Query("SELECT * FROM memory WHERE reviewState = 'INVALIDATED' AND derived = 1 ORDER BY createdAt LIMIT :limit")
    abstract suspend fun invalidatedDerived(limit: Int): List<MemoryEntity>

    companion object {
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
        const val DESCRIBES = "a.reviewState = 'OK' AND a.derived = 0 AND a.id NOT GLOB 'rokid_cv_*'"
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
            "(EXISTS (SELECT 1 FROM memory x WHERE x.frameId = f.id) OR EXISTS (SELECT 1 FROM frame_source x WHERE x.frameId = f.id)) AND NOT $DESCRIBED_BY_EXTRACTION"
        /** Skipped-photo guard, shared by [skippedPhotosDue] and the photo query that must leave those frames to it. */
        const val SKIPPED_PHOTO =
            "AND ($SCORED_LOW OR COALESCE(s.lastError, '') = 'caption_expired') AND m.dwell = 0 " +
                NOT_A_REP + NOT_DESCRIBED + CLUSTER_SETTLED
        /** Photo retention applies to every keyframe not owed work, not forgotten, not a skipped photo (those follow [SKIPPED_PHOTO]'s window). */
        const val PHOTO_ELIGIBLE =
            "s.state NOT IN ('PENDING','EXTRACTING','FAILED','IN_CLUSTER','CAPTION_QUEUED') " + CLUSTER_SETTLED +
                "AND NOT (s.state IN ('NEAR_DUP','CLUSTERED') AND ($SCORED_LOW OR COALESCE(s.lastError, '') = 'caption_expired') AND m.dwell = 0 " + NOT_A_REP + NOT_DESCRIBED + ") "
    }
}
