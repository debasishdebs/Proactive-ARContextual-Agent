package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperConfirmOutcome
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.canvas.HelperFenceLive
import com.rokid.context.memory.canvas.HelperJobInfo
import com.rokid.context.memory.canvas.HelperLeaseResult
import com.rokid.context.memory.canvas.HelperJobState
import com.rokid.context.memory.canvas.JobEvent
import com.rokid.context.memory.canvas.LeasedHelperJob

/**
 * Slice 3a (Room v7) DAO. Every write that commits DERIVED data (a trace chunk, a replay or sweep result, a mark) is fenced INSIDE its transaction on
 * the wipe generation and the persisted deletion epoch ([DaoFence]) and, where it applies, on tombstones, source revisions and `labelRev` (S57, S58, S75).
 * Transactional tombstones and invalidation for Forget live here too; the Forget cascade itself is Task 34.
 */
@Dao
abstract class CanvasSlice3Dao {
    // --- canvas_meta and labelRev (S75) ---
    @Query("SELECT value FROM canvas_meta WHERE `key` = :key") abstract suspend fun meta(key: String): String?
    @Query("INSERT OR IGNORE INTO canvas_meta (`key`, value) VALUES (:key, :value)") protected abstract suspend fun insertMetaIgnore(key: String, value: String)
    @Query("UPDATE canvas_meta SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) WHERE `key` = :key") protected abstract suspend fun incrementMeta(key: String): Int

    /** Writes a durable key (kept by Delete all; hold no user data: the M1 evidence flag, plan JSON of a publish group). */
    @Query("INSERT OR REPLACE INTO canvas_meta (`key`, value) VALUES (:key, :value)") abstract suspend fun putMeta(key: String, value: String)
    @Query("INSERT OR REPLACE INTO canvas_meta (`key`, value) VALUES (:key, :value)") protected abstract suspend fun setMeta(key: String, value: String)

    // --- the durable forget-range fence (S58, P7): `canvas_meta` key `forgetRanges` = "from-to;from-to" (merged, at most MAX_RANGES) ---
    /** Every time range the user forgot (merged). Delete all clears it (the epoch fence covers everything before it). */
    open suspend fun forgottenRanges(): List<LongRange> = parseRanges(meta(FORGET_RANGES))

    private suspend fun addForgetRange(from: Long, to: Long) = addRange(FORGET_RANGES, from, to)

    private suspend fun addRange(key: String, from: Long, to: Long) {
        val merged = mergeRanges(parseRanges(meta(key)) + listOf(minOf(from, to)..maxOf(from, to)))
        setMeta(key, merged.joinToString(";") { "${it.first}-${it.last}" })
    }

    // --- the Moment Forget fence (Moment model phase 1): the outward-rounded full-second ranges the user asked to forget through Moment Forget, kept apart from `forgetRanges` because
    // that one also holds the +-10 s widening around every forgotten photo (a burst neighbour must still be admitted). Delete all empties it (the deletion epoch covers what came before).
    /** Persists the Moment Forget range FIRST in the Forget transaction: a frame, mark or builder run for that time is refused from now on, whatever its arrival order. */
    @Transaction open suspend fun addMomentForgetRange(fromMs: Long, toMs: Long) = addRange(MOMENT_FORGET_RANGES, fromMs, toMs)

    open suspend fun momentForgetRanges(): List<LongRange> = parseRanges(meta(MOMENT_FORGET_RANGES))

    /** True when [fromMs, toMs] touches a range that Moment Forget fenced. Call inside the committing transaction. */
    open suspend fun momentFenced(fromMs: Long, toMs: Long): Boolean = momentForgetRanges().any { it.first <= toMs && it.last >= fromMs }

    /** True when [atMs] lies inside a forgotten range: a mark, a voice command or a trace for that time must not come back. */
    open suspend fun isForgotten(atMs: Long): Boolean = forgottenRanges().any { atMs in it }
    open suspend fun overlapsForgotten(fromMs: Long, toMs: Long): Boolean = forgottenRanges().any { it.first <= toMs && it.last >= fromMs }

    /** The outcome of one forget: the traces that were tombstoned, the marks deleted, the results invalidated and the open helper jobs voided (3b). */
    data class ForgetOutcome(val tombstonedTraceIds: List<String>, val labelsDeleted: Int, val resultsInvalidated: Int, val helperJobsVoided: Int = 0)

    /**
     * Test seam (like `MemoryWipe.afterEpochCommit`): runs INSIDE [forgetRangeTx] and [deleteSliceOutputsForFrames] right after the frame-Forget fence moved, the open helper
     * jobs were voided and their cleanup rows were written, so a test can throw there and prove all three roll back together. Always null in production; module-internal.
     */
    @Volatile internal var testFaultAfterFenceMove: (() -> Unit)? = null

    /**
     * Forget of the time range [fromMs, toMs], in ONE transaction: the range is recorded as a durable fence FIRST (a mark, a voice command or a trace import for it is
     * refused from now on, whatever its delivery order), the live traces overlapping it are tombstoned, its marks are deleted, `labelRev` moves, and every result that read
     * any of that is invalidated. Idempotent. 3b: in the SAME transaction `frame_forget_rev` moves, every open helper job (also one still awaiting its tap) is voided with its
     * payload and ids dropped, and one cleanup row per job is written (see [moveFrameForgetFence]); it tombstones TRACES only, never a frame.
     */
    @Transaction open suspend fun forgetRangeTx(fromMs: Long, toMs: Long, now: Long): ForgetOutcome {
        addForgetRange(fromMs, toMs)
        val jobs = moveFrameForgetFence(now)
        val ids = liveTraces().filter { it.startedAtMs <= toMs && (it.endedAtMs ?: Long.MAX_VALUE) >= fromMs }.map { it.traceId }
        ids.forEach { tombstoneTrace(it, now) }
        val labels = deleteLabelsBetween(fromMs, toMs)
        val invalid = invalidateBySources(ids, fromMs, toMs, labelRevChanged = labels > 0)
        return ForgetOutcome(ids, labels, invalid, jobs)
    }

    /**
     * The 3b deletion step shared by every Forget entry point, called INSIDE the Forget transaction: bumps `frame_forget_rev` (so a runner fence captured earlier and a
     * result committed later both fail), voids EVERY open helper job including `AWAITING_CONFIRM` (a Forget of any frame voids the in-flight jobs, fail closed; a later tap is
     * refused because the job is no longer awaiting), and writes one cleanup row per voided job (`closeJob`). Returns the jobs voided.
     */
    private suspend fun moveFrameForgetFence(now: Long): Int {
        bumpFrameForgetRev()
        val voided = voidAllHelperJobs("frame_forget", now)
        testFaultAfterFenceMove?.invoke()
        return voided
    }

    /** The durable label counter (0 until the first label write). */
    open suspend fun labelRev(): Long = meta(LABEL_REV)?.toLongOrNull() ?: 0L

    /** Bumps the label counter and returns the new value. Called INSIDE every label write's transaction. */
    @Transaction open suspend fun bumpLabelRev(): Long {
        insertMetaIgnore(LABEL_REV, "0")
        incrementMeta(LABEL_REV)
        return labelRev()
    }

    // --- labels (marks); there is no text column ---
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertLabel(l: CanvasLabelEntity)
    @Query("SELECT * FROM canvas_label WHERE labelId = :id") abstract suspend fun label(id: String): CanvasLabelEntity?
    @Query("SELECT * FROM canvas_label ORDER BY atMs, labelId") abstract suspend fun labels(): List<CanvasLabelEntity>
    @Query("SELECT * FROM canvas_label WHERE atMs >= :fromMs AND atMs <= :toMs ORDER BY atMs, labelId") abstract suspend fun labelsBetween(fromMs: Long, toMs: Long): List<CanvasLabelEntity>
    @Query("DELETE FROM canvas_label WHERE labelId = :id") protected abstract suspend fun deleteLabelRow(id: String): Int
    @Query("UPDATE canvas_label SET name = :name WHERE labelId = :id") protected abstract suspend fun renameLabelRow(id: String, name: String): Int
    @Query("UPDATE canvas_label SET source = :source WHERE labelId = :id") protected abstract suspend fun retagLabelRow(id: String, source: String): Int
    @Query("DELETE FROM canvas_label WHERE frameId IN (:frameIds)") protected abstract suspend fun deleteLabelRowsForFrames(frameIds: List<String>): Int
    @Query("DELETE FROM canvas_label WHERE atMs >= :fromMs AND atMs <= :toMs") protected abstract suspend fun deleteLabelRowsBetween(fromMs: Long, toMs: Long): Int

    /**
     * Inserts a mark only if, inside this transaction, the fence still holds AND the mark's occurrence time is not before the latest wipe
     * (`wipedAtMs`, S57), then bumps `labelRev`. False writes nothing.
     */
    @Transaction open suspend fun insertLabelFenced(label: CanvasLabelEntity, fence: DaoFence): Boolean {
        if (!fence.holds()) return false
        if (label.atMs < fence.now().wipedAtMs) return false
        if (isForgotten(label.atMs)) return false // a mark of a forgotten range: neither a late delivery nor a delayed voice command brings it back
        if (label(label.labelId) != null) return true // a re-sent mark: already recorded, and labelRev must not move (it would refuse every in-flight replay commit)
        insertLabel(label)
        bumpLabelRev()
        return true
    }

    /** Deletes one label; the results computed under the old labels (they embody it) are invalidated in the same transaction. */
    @Transaction open suspend fun deleteLabel(id: String): Boolean {
        val n = deleteLabelRow(id)
        if (n > 0) { bumpLabelRev(); invalidateBySources(emptyList(), null, null, labelRevChanged = true) }
        return n > 0
    }

    /** Changes how a mark was made (tap to voice, tap to ask_tap). Bumps `labelRev` only when the source really changed. */
    @Transaction open suspend fun retagLabel(id: String, source: String): Boolean {
        val cur = label(id) ?: return false
        if (cur.source == source) return true
        val n = retagLabelRow(id, source)
        if (n > 0) bumpLabelRev()
        return n > 0
    }

    @Transaction open suspend fun correctLabel(id: String, name: String): Boolean {
        val n = renameLabelRow(id, name)
        if (n > 0) { bumpLabelRev(); invalidateBySources(emptyList(), null, null, labelRevChanged = true) }
        return n > 0
    }

    /** Forget of photos: their labels go, `labelRev` moves (the caller invalidates the dependent results). Returns the labels deleted. */
    @Transaction open suspend fun deleteLabelsForFrames(frameIds: List<String>): Int {
        if (frameIds.isEmpty()) return 0
        val n = frameIds.chunked(CHUNK).sumOf { deleteLabelRowsForFrames(it) }
        if (n > 0) bumpLabelRev()
        return n
    }

    /** Forget of a time range [fromMs, toMs] (both inclusive, the same bounds as [invalidateBySources]): the marks inside it go and `labelRev` moves. */
    @Transaction open suspend fun deleteLabelsBetween(fromMs: Long, toMs: Long): Int {
        val n = deleteLabelRowsBetween(fromMs, toMs)
        if (n > 0) bumpLabelRev()
        return n
    }

    // --- traces and events ---
    @Query("SELECT * FROM canvas_trace WHERE traceId = :id") abstract suspend fun trace(id: String): CanvasTraceEntity?
    @Query("SELECT * FROM canvas_trace WHERE tombstoned = 0 ORDER BY startedAtMs, traceId") abstract suspend fun liveTraces(): List<CanvasTraceEntity>
    @Query("SELECT * FROM canvas_trace_event WHERE traceId = :id ORDER BY seq") abstract suspend fun events(id: String): List<CanvasTraceEventEntity>
    @Query("SELECT COUNT(*) FROM canvas_trace_event WHERE traceId = :id") abstract suspend fun eventCount(id: String): Int
    @Upsert protected abstract suspend fun upsertTrace(t: CanvasTraceEntity)
    @Upsert protected abstract suspend fun upsertEvents(e: List<CanvasTraceEventEntity>)
    @Query("DELETE FROM canvas_trace_event WHERE traceId = :id") protected abstract suspend fun deleteEventsOf(id: String): Int
    @Query("INSERT OR IGNORE INTO canvas_trace (traceId, source, status, manifestJson, wipeGen, tombstoned, revision, installId, deletionCounter, exact, startedAtMs, createdAt) VALUES (:id, 'UNKNOWN', 'PARTIAL', '{}', 0, 1, 0, '', 0, 0, 0, :now)")
    protected abstract suspend fun insertTombstoneStub(id: String, now: Long)
    @Query("UPDATE canvas_trace SET tombstoned = 1, manifestJson = '{}', startedAtMs = 0, endedAtMs = NULL, parentTraceId = NULL, installId = '', deletionCounter = 0 WHERE traceId = :id") protected abstract suspend fun markTombstoned(id: String): Int

    /**
     * A trace (or its updated manifest) is accepted only while the fence holds, the trace is not tombstoned, and it was recorded under the CURRENT
     * epoch (same `installId` and `deletionCounter`): a chunk from before a Delete all or from another pairing is refused (S57).
     */
    @Transaction open suspend fun acceptTraceFenced(t: CanvasTraceEntity, fence: DaoFence): Boolean {
        if (!fence.holds()) return false
        val epoch = fence.now()
        if (t.installId != epoch.installId || t.deletionCounter != epoch.counter) return false
        if (trace(t.traceId)?.tombstoned == true) return false
        if (overlapsForgotten(t.startedAtMs, t.endedAtMs ?: t.startedAtMs)) return false
        upsertTrace(t)
        return true
    }

    /** Hub events of a live trace, under the same fence and tombstone rule. */
    @Transaction open suspend fun appendEventsFenced(traceId: String, events: List<CanvasTraceEventEntity>, fence: DaoFence): Boolean {
        if (!fence.holds()) return false
        val t = trace(traceId) ?: return false
        if (t.tombstoned) return false
        upsertEvents(events)
        return true
    }

    /**
     * Tombstones [traceId] in one transaction: its events go, the row keeps only its identity (a stub is created for an id never seen), so a late chunk is
     * rejected by [acceptTraceFenced]. Idempotent. Results that read it are invalidated by [invalidateBySources].
     */
    @Transaction open suspend fun tombstoneTrace(traceId: String, now: Long) {
        deleteEventsOf(traceId)
        insertTombstoneStub(traceId, now)
        markTombstoned(traceId)
    }

    // --- replays and sweeps (S75, S77) ---
    @Query("SELECT * FROM canvas_replay WHERE replayId = :id") abstract suspend fun replay(id: String): CanvasReplayEntity?
    @Query("SELECT * FROM canvas_replay ORDER BY createdAt DESC, replayId LIMIT :limit") abstract suspend fun replays(limit: Int): List<CanvasReplayEntity>
    /** The evidence gate 3 reads (S60): finished, valid, EXACT replays of exactly this config, newest first. */
    @Query("SELECT * FROM canvas_replay WHERE configHash = :configHash AND replayClass = 'EXACT' AND jobState = 'DONE' AND invalidated = 0 ORDER BY createdAt DESC, replayId")
    abstract suspend fun validExactReplays(configHash: String): List<CanvasReplayEntity>
    /** Gate 3 of a plan publish (S60, S75): finished, valid replays of exactly this config of EITHER class, newest first (a never-recorded plan can only have been replayed as a candidate). */
    @Query("SELECT * FROM canvas_replay WHERE configHash = :configHash AND jobState = 'DONE' AND invalidated = 0 ORDER BY createdAt DESC, replayId")
    abstract suspend fun validReplays(configHash: String): List<CanvasReplayEntity>
    @Query("SELECT * FROM canvas_sweep WHERE sweepId = :id") abstract suspend fun sweep(id: String): CanvasSweepEntity?
    @Query("SELECT * FROM canvas_sweep ORDER BY createdAt DESC, sweepId LIMIT :limit") abstract suspend fun sweeps(limit: Int): List<CanvasSweepEntity>
    @Insert abstract suspend fun insertReplay(r: CanvasReplayEntity)
    @Insert abstract suspend fun insertSweep(s: CanvasSweepEntity)
    @Query("UPDATE canvas_replay SET jobState = 'DONE', resultJson = :result, finishedAt = :now WHERE replayId = :id AND jobState = 'RUNNING' AND invalidated = 0 AND configHash IS :configHash")
    protected abstract suspend fun finishReplay(id: String, result: String, configHash: String?, now: Long): Int
    @Query("UPDATE canvas_sweep SET jobState = 'DONE', resultJson = :result, finishedAt = :now WHERE sweepId = :id AND jobState = 'RUNNING' AND invalidated = 0 AND configHash IS :configHash")
    protected abstract suspend fun finishSweep(id: String, result: String, configHash: String?, now: Long): Int
    @Query("UPDATE canvas_replay SET jobState = 'CANCELLED', finishedAt = :now, sourcesJson = '[]', fromMs = NULL, toMs = NULL, paramsJson = '{}' WHERE replayId = :id AND jobState = 'RUNNING'") abstract suspend fun cancelReplay(id: String, now: Long): Int
    @Query("UPDATE canvas_sweep SET jobState = 'CANCELLED', finishedAt = :now, sourcesJson = '[]', fromMs = NULL, toMs = NULL, paramsJson = '{}' WHERE sweepId = :id AND jobState = 'RUNNING'") abstract suspend fun cancelSweep(id: String, now: Long): Int
    @Query("UPDATE canvas_replay SET jobState = 'ABORTED', finishedAt = :now WHERE jobState = 'RUNNING'") protected abstract suspend fun abortReplays(now: Long): Int
    @Query("UPDATE canvas_sweep SET jobState = 'ABORTED', finishedAt = :now WHERE jobState = 'RUNNING'") protected abstract suspend fun abortSweeps(now: Long): Int

    /** Start-up recovery (S77): a job still RUNNING belonged to a dead process. Marks every such replay and sweep, and every OPEN helper job (their tokens and confirmations are memory-only, so they are all dead), ABORTED; returns how many. */
    @Transaction open suspend fun markRunningAborted(now: Long): Int = abortReplays(now) + abortSweeps(now) + abortOpenHelperJobs(now)

    /**
     * True when every source still exists, is not tombstoned and is at the revision that was read, and [expectedLabelRev] is still the label counter.
     * Called inside a commit transaction.
     */
    private suspend fun sourcesHold(sources: List<TraceSource>, expectedLabelRev: Long, epoch: DeletionEpoch): Boolean {
        if (labelRev() != expectedLabelRev) return false
        // A source recorded under another install or an older deletion counter (a Delete all cut short) never counts, even if its row is still there (S57).
        return sources.all { s -> trace(s.traceId)?.let { !it.tombstoned && it.revision == s.revision && it.installId == epoch.installId && it.deletionCounter == epoch.counter } == true }
    }

    /**
     * Commits a replay result (of the config [configHash], which must be the row's) only if, INSIDE this transaction, the wipe generation and the persisted epoch hold, no source trace is tombstoned or
     * rewritten, `labelRev` did not move, and the job is still RUNNING and not invalidated. Otherwise nothing is written (the caller drops the result).
     */
    @Transaction open suspend fun commitReplayFenced(replayId: String, resultJson: String, configHash: String?, sources: List<TraceSource>, expectedLabelRev: Long, fence: DaoFence, now: Long): Boolean {
        if (!fence.holds()) return false
        if (!sourcesHold(sources, expectedLabelRev, fence.now())) return false
        if (TraceSources.decode(replay(replayId)?.sourcesJson ?: return false) == null) return false // unreadable provenance: fail closed
        return finishReplay(replayId, resultJson, configHash, now) > 0
    }

    /** As [commitReplayFenced], for a sweep. */
    @Transaction open suspend fun commitSweepFenced(sweepId: String, resultJson: String, configHash: String?, sources: List<TraceSource>, expectedLabelRev: Long, fence: DaoFence, now: Long): Boolean {
        if (!fence.holds()) return false
        if (!sourcesHold(sources, expectedLabelRev, fence.now())) return false
        if (TraceSources.decode(sweep(sweepId)?.sourcesJson ?: return false) == null) return false
        return finishSweep(sweepId, resultJson, configHash, now) > 0
    }

    @Query("UPDATE canvas_replay SET invalidated = 1, resultJson = NULL, sourcesJson = '[]', fromMs = NULL, toMs = NULL WHERE invalidated = 0 AND instr(sourcesJson, :needle) > 0") protected abstract suspend fun invalidateReplaysOfTrace(needle: String): Int
    @Query("UPDATE canvas_sweep SET invalidated = 1, resultJson = NULL, sourcesJson = '[]', fromMs = NULL, toMs = NULL WHERE invalidated = 0 AND instr(sourcesJson, :needle) > 0") protected abstract suspend fun invalidateSweepsOfTrace(needle: String): Int
    @Query("UPDATE canvas_replay SET invalidated = 1, resultJson = NULL, sourcesJson = '[]', fromMs = NULL, toMs = NULL WHERE invalidated = 0 AND (fromMs IS NULL OR toMs IS NULL OR (fromMs <= :toMs AND toMs >= :fromMs))") protected abstract suspend fun invalidateReplaysInRange(fromMs: Long, toMs: Long): Int
    @Query("UPDATE canvas_sweep SET invalidated = 1, resultJson = NULL, sourcesJson = '[]', fromMs = NULL, toMs = NULL WHERE invalidated = 0 AND (fromMs IS NULL OR toMs IS NULL OR (fromMs <= :toMs AND toMs >= :fromMs))") protected abstract suspend fun invalidateSweepsInRange(fromMs: Long, toMs: Long): Int
    @Query("UPDATE canvas_replay SET invalidated = 1, resultJson = NULL, sourcesJson = '[]', fromMs = NULL, toMs = NULL WHERE invalidated = 0 AND labelRev != :labelRev") protected abstract suspend fun invalidateReplaysOfOtherLabelRev(labelRev: Long): Int
    @Query("UPDATE canvas_sweep SET invalidated = 1, resultJson = NULL, sourcesJson = '[]', fromMs = NULL, toMs = NULL WHERE invalidated = 0 AND labelRev != :labelRev") protected abstract suspend fun invalidateSweepsOfOtherLabelRev(labelRev: Long): Int
    @Query("UPDATE canvas_replay SET invalidated = 1, resultJson = NULL, sourcesJson = '[]', fromMs = NULL, toMs = NULL WHERE invalidated = 0 AND sweepId IN (SELECT sweepId FROM canvas_sweep WHERE invalidated = 1)") protected abstract suspend fun invalidateReplaysOfInvalidSweeps(): Int

    /**
     * Marks the replays and sweeps that read any of [traceIds], or whose source envelope overlaps `[fromMs, toMs]` (inclusive) or is unknown (no range: fail closed), or (with [labelRevChanged]) that
     * were computed under another `labelRev`, INVALIDATED with their results deleted; the replays of an invalidated sweep follow. One transaction. Returns the rows changed.
     */
    @Transaction open suspend fun invalidateBySources(traceIds: Collection<String>, fromMs: Long?, toMs: Long?, labelRevChanged: Boolean): Int {
        var n = 0
        traceIds.forEach { id -> val needle = TraceSources.needle(id); n += invalidateReplaysOfTrace(needle) + invalidateSweepsOfTrace(needle) }
        if (fromMs != null && toMs != null) n += invalidateReplaysInRange(fromMs, toMs) + invalidateSweepsInRange(fromMs, toMs)
        if (labelRevChanged) { val rev = labelRev(); n += invalidateReplaysOfOtherLabelRev(rev) + invalidateSweepsOfOtherLabelRev(rev) }
        n += invalidateReplaysOfInvalidSweeps()
        return n
    }

    /**
     * The INITIAL insert of a replay or sweep job, under the same fence as its final commit: if a Forget or Delete all landed after the sources were loaded, the
     * sources are gone, tombstoned, of another epoch or the labels moved, and NOTHING is written (no row keeps their ids, intervals or parameters).
     */
    @Transaction open suspend fun insertReplayFenced(r: CanvasReplayEntity, sources: List<TraceSource>, expectedLabelRev: Long, fence: DaoFence): Boolean {
        if (!fence.holds() || !sourcesHold(sources, expectedLabelRev, fence.now())) return false
        insertReplay(r); return true
    }

    @Transaction open suspend fun insertSweepFenced(s: CanvasSweepEntity, sources: List<TraceSource>, expectedLabelRev: Long, fence: DaoFence): Boolean {
        if (!fence.holds() || !sourcesHold(sources, expectedLabelRev, fence.now())) return false
        insertSweep(s); return true
    }

    // --- calibration ---
    // TODO(3b, deletion graph fix 1): insertCalibration is NOT epoch-fenced (no production writer exists yet). The first writer must go through a fenced insert like
    // insertLabelFenced; DeletionFencePinsTest pins today's behaviour so that change is deliberate. (insertHelperJob: see the helper-job section; production uses insertHelperJobFenced.)
    @Insert abstract suspend fun insertCalibration(c: CanvasCalibrationEntity): Long
    @Query("SELECT * FROM canvas_calibration WHERE configHash = :configHash ORDER BY id DESC") abstract suspend fun calibrations(configHash: String): List<CanvasCalibrationEntity>
    @Query("SELECT COUNT(*) FROM canvas_calibration WHERE configHash = :configHash AND verified = 1") abstract suspend fun verifiedCount(configHash: String): Int

    // --- publish groups (S62 recovery table) ---
    @Upsert abstract suspend fun upsertGroupState(s: CanvasGroupStateEntity)
    @Query("SELECT * FROM canvas_group_state WHERE groupId = :groupId") abstract suspend fun groupState(groupId: String): CanvasGroupStateEntity?
    @Query("SELECT * FROM canvas_group_state ORDER BY updatedAt DESC, groupId") abstract suspend fun groupStates(): List<CanvasGroupStateEntity>
    @Query("DELETE FROM canvas_group_state WHERE groupId = :groupId") abstract suspend fun deleteGroupState(groupId: String): Int

    // --- helper jobs (3b, Room v8) ---
    /** Legacy unfenced insert (v7 tests only): NOTHING in production calls it (pinned by DeletionFencePinsTest); production inserts through [insertHelperJobFenced]. */
    @Insert abstract suspend fun insertHelperJob(j: CanvasHelperJobEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertHelperJobRow(j: CanvasHelperJobEntity): Long
    @Query("SELECT * FROM canvas_helper_job WHERE jobId = :id") abstract suspend fun helperJob(id: String): CanvasHelperJobEntity?
    @Query("SELECT * FROM canvas_helper_job WHERE state NOT IN ('DONE', 'VOIDED', 'FAILED', 'CANCELLED', 'ABORTED')") abstract suspend fun openHelperJobs(): List<CanvasHelperJobEntity>
    @Query("SELECT jobId FROM canvas_helper_job WHERE state IN ('AWAITING_CONFIRM', 'QUEUED', 'LEASED') ORDER BY createdAt, jobId") protected abstract suspend fun openHelperJobIds(): List<String>
    @Query("SELECT jobId FROM canvas_helper_job WHERE state IN ('AWAITING_CONFIRM', 'QUEUED', 'LEASED') AND frameForgetRev != :rev ORDER BY createdAt, jobId") protected abstract suspend fun openJobIdsOfOtherFrameRev(rev: Long): List<String>
    @Query("SELECT jobId FROM canvas_helper_job WHERE state = 'AWAITING_CONFIRM' AND confirmExpiresAtMs <= :now ORDER BY createdAt, jobId") protected abstract suspend fun awaitingPastWindow(now: Long): List<String>
    @Query("SELECT jobId FROM canvas_helper_job WHERE state = 'QUEUED' AND confirmedAtMs + :queueWaitMs <= :now ORDER BY createdAt, jobId") protected abstract suspend fun queuedPastWait(now: Long, queueWaitMs: Long): List<String>
    @Query("SELECT jobId FROM canvas_helper_job WHERE state = 'LEASED' AND (leaseUntilMs <= :now OR deadlineMs <= :now) ORDER BY createdAt, jobId") protected abstract suspend fun leasedPastExpiry(now: Long): List<String>

    // A job is OPEN until it reaches DONE, VOIDED, FAILED, CANCELLED or ABORTED. The voided job drops its frame ids, payload and result: nothing about the photos stays on the phone.
    @Query("SELECT jobId FROM canvas_helper_job WHERE state IN ('AWAITING_CONFIRM', 'QUEUED', 'LEASED') AND instr(frameIdsJson, :needle) > 0 ORDER BY createdAt, jobId") protected abstract suspend fun openJobIdsCarrying(needle: String): List<String>
    @Query("SELECT jobId FROM canvas_helper_job WHERE state = 'DONE' AND instr(frameIdsJson, :needle) > 0 ORDER BY createdAt, jobId") protected abstract suspend fun doneJobIdsCarrying(needle: String): List<String>
    @Query("UPDATE canvas_helper_job SET resultJson = NULL, payloadJson = NULL, frameIdsJson = '[]', voidReason = 'frame_forget', updatedAt = :now WHERE jobId = :id AND state = 'DONE'")
    protected abstract suspend fun scrubDoneRow(id: String, now: Long): Int

    private fun frameNeedle(frameId: String) = "\"" + frameId.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Voids the open helper jobs (also one still awaiting the tap) that carry one of [frameIds]: state VOIDED, ids, payload and result dropped, one cleanup row each, in this
     * transaction. NEVER a deletion entry point: it does NOT bump `frame_forget_rev` and voids only the jobs that carry these frames. Forget paths call
     * [moveFrameForgetFence] (through [forgetRangeTx] / [deleteSliceOutputsForFrames]). It has no production caller; it stays for the DAO tests.
     */
    @Transaction internal open suspend fun voidHelperJobsForFrames(frameIds: List<String>, now: Long): Int =
        frameIds.flatMap { openJobIdsCarrying(frameNeedle(it)) }.distinct().count { closeById(it, JobEvent.VOID, now, voidReason = "frame_forget") }

    /**
     * A finished job whose input carried a forgotten frame keeps its DONE state but loses its result and ids (the hash stays so a retried commit is answered `voided`,
     * never acked), and one more cleanup row tells the laptop to drop any copy. Returns the jobs scrubbed.
     */
    @Transaction open suspend fun scrubDoneJobsForFrames(frameIds: List<String>, now: Long): Int =
        frameIds.flatMap { doneJobIdsCarrying(frameNeedle(it)) }.distinct().count { id ->
            (scrubDoneRow(id, now) > 0).also { if (it) insertCleanup(CanvasHelperCleanupEntity(jobId = id, reason = "frame_forget", createdAt = now)) }
        }

    // --- frame_forget_rev (core plan section 6, "Frame-Forget fence"): a counter in canvas_meta, bumped in the transaction of every Forget (`forgetRangeTx`, `deleteSliceOutputsForFrames`, Delete all) ---
    /** The frame-Forget revision (0 until the first bump). Captured by the runner BEFORE it loads frames, re-read inside every helper-job transaction. */
    open suspend fun frameForgetRev(): Long = meta(FRAME_FORGET_REV)?.toLongOrNull() ?: 0L

    /** Bumps `frame_forget_rev` and returns the new value. A primitive: it moves the fence by itself (tests call it directly; WP4 calls it inside the Forget transactions). */
    @Transaction open suspend fun bumpFrameForgetRev(): Long {
        insertMetaIgnore(FRAME_FORGET_REV, "0")
        incrementMeta(FRAME_FORGET_REV)
        return frameForgetRev()
    }

    /** True when nothing was deleted since [r] was inserted: wipe generation, deletion epoch and `frame_forget_rev`, the last read in this transaction. */
    private suspend fun rowFenceHolds(r: CanvasHelperJobEntity, live: HelperFenceLive): Boolean {
        if (r.wipeGen != live.wipeGen()) return false
        val e = live.epoch()
        if (r.installId != e.installId || r.deletionCounter != e.counter) return false
        return frameForgetRev() == r.frameForgetRev
    }

    private fun stateOf(r: CanvasHelperJobEntity): HelperJobState? = runCatching { HelperJobState.valueOf(r.state) }.getOrNull()

    // --- insert ---
    /**
     * Inserts a `py_exec` job in AWAITING_CONFIRM only if, INSIDE this transaction, [fence] (captured BEFORE the inputs loaded) still holds against [live] and the
     * persisted `frame_forget_rev`. A frame with no labels and no trace sources is invisible to every other check, so the revision is the only thing that tells this
     * insert that a Forget landed after the runner's pre-check. The row stores the fence; the payload and its `bundleHash` are written once here and never updated, only nulled.
     */
    @Transaction open suspend fun insertHelperJobFenced(job: CanvasHelperJobEntity, fence: HelperFence, live: HelperFenceLive): Boolean {
        if (fence.wipeGen != live.wipeGen()) return false
        if (!fence.deletionEpoch.sameEpoch(live.epoch())) return false
        if (frameForgetRev() != fence.frameForgetRev) return false
        if (job.bundleHash == null || job.payloadJson == null || job.confirmExpiresAtMs == null) return false
        val row = job.copy(
            state = HelperJobState.AWAITING_CONFIRM.name, jobType = job.jobType.ifBlank { job.capability }, leaseOwner = null, leaseUntilMs = null, leaseGen = 0, lastHeartbeatMs = null,
            deadlineMs = null, resultJson = null, resultHash = null, confirmedAtMs = null, wipeGen = fence.wipeGen, installId = fence.deletionEpoch.installId,
            deletionCounter = fence.deletionEpoch.counter, frameForgetRev = fence.frameForgetRev, failCode = null, voidReason = null,
        )
        return insertHelperJobRow(row) != -1L
    }

    // --- terminal transitions (every one enqueues the cleanup row in the same transaction) ---
    @Query("UPDATE canvas_helper_job SET state = :to, failCode = :failCode, voidReason = :voidReason, payloadJson = NULL, resultJson = NULL, frameIdsJson = '[]', leaseOwner = NULL, leaseUntilMs = NULL, updatedAt = :now WHERE jobId = :id AND state = :from")
    protected abstract suspend fun closeRow(id: String, from: String, to: String, failCode: String?, voidReason: String?, now: Long): Int

    @Insert protected abstract suspend fun insertCleanup(c: CanvasHelperCleanupEntity): Long

    /** Moves [r] by [event] through [HelperJobState.next] into a terminal state, nulls its payload and result, and enqueues its cleanup row. False when the state machine refuses. */
    private suspend fun closeJob(r: CanvasHelperJobEntity, event: JobEvent, now: Long, failCode: String? = null, voidReason: String? = null): Boolean {
        val from = stateOf(r) ?: return false
        val to = from.next(event)?.takeIf { !it.open } ?: return false
        if (closeRow(r.jobId, from.name, to.name, failCode, voidReason, now) == 0) return false
        insertCleanup(CanvasHelperCleanupEntity(jobId = r.jobId, reason = voidReason ?: failCode ?: to.name.lowercase(), createdAt = now))
        return true
    }

    private suspend fun closeById(id: String, event: JobEvent, now: Long, failCode: String? = null, voidReason: String? = null): Boolean =
        helperJob(id)?.let { closeJob(it, event, now, failCode, voidReason) } ?: false

    @Transaction open suspend fun cancelHelperJob(jobId: String, now: Long): Boolean = closeById(jobId, JobEvent.CANCEL, now, voidReason = "cancelled")

    /** A deletion cause, grant off, Disconnect or session end for one job: any open state to VOIDED. */
    @Transaction open suspend fun voidHelperJob(jobId: String, reason: String, now: Long): Boolean = closeById(jobId, JobEvent.VOID, now, voidReason = reason)

    @Transaction open suspend fun voidAllHelperJobs(reason: String, now: Long): Int = openHelperJobIds().count { closeById(it, JobEvent.VOID, now, voidReason = reason) }

    /** Voids every open job captured under another `frame_forget_rev` (fail closed: a Forget of any frame voids the in-flight helper jobs). */
    @Transaction open suspend fun voidJobsWithStaleFrameRev(now: Long): Int = openJobIdsOfOtherFrameRev(frameForgetRev()).count { closeById(it, JobEvent.VOID, now, voidReason = "frame_forget") }

    /** After a process restart every open job is dead (tokens and confirmations are memory-only): ABORTED, payload nulled, cleanup enqueued. */
    @Transaction open suspend fun abortOpenHelperJobs(now: Long): Int = openHelperJobIds().count { closeById(it, JobEvent.ABORT, now, voidReason = "restart") }

    // --- the confirmation window ---
    @Query("UPDATE canvas_helper_job SET state = 'QUEUED', confirmedAtMs = :now, updatedAt = :now WHERE jobId = :id AND state = 'AWAITING_CONFIRM'")
    protected abstract suspend fun confirmRow(id: String, now: Long): Int

    /**
     * The ONE transaction that moves AWAITING_CONFIRM to QUEUED (the native tap). It re-checks the state, that [binding] equals the stored job in every field (job id, bundle
     * hash, session generation, expiry; the HMAC tag was verified by `:phone`), that the window has not closed and that the fence still holds. A closed window ends the job
     * (FAILED `not_confirmed`); a moved fence VOIDS it, so a tap after a Forget can never release a payload. A repeat of the same binding is a harmless DUPLICATE.
     */
    @Transaction open suspend fun confirmJobFenced(jobId: String, binding: ConfirmBinding, live: HelperFenceLive, now: Long): HelperConfirmOutcome {
        val r = helperJob(jobId) ?: return HelperConfirmOutcome.NOT_FOUND
        val st = stateOf(r)
        val same = binding.jobId == r.jobId && binding.bundleHash == r.bundleHash && binding.sessionGen == r.sessionGen && binding.expiresAtMs == r.confirmExpiresAtMs
        if ((st == HelperJobState.QUEUED || st == HelperJobState.LEASED) && r.confirmedAtMs != null && same) return HelperConfirmOutcome.DUPLICATE
        if (st != HelperJobState.AWAITING_CONFIRM) return HelperConfirmOutcome.NOT_AWAITING
        if (!same) return HelperConfirmOutcome.MISMATCH
        if (now >= (r.confirmExpiresAtMs ?: 0L)) { closeJob(r, JobEvent.EXPIRE, now, failCode = "not_confirmed"); return HelperConfirmOutcome.EXPIRED }
        if (!rowFenceHolds(r, live)) { closeJob(r, JobEvent.VOID, now, voidReason = "fence"); return HelperConfirmOutcome.STALE_FENCE }
        return if (confirmRow(jobId, now) == 1) HelperConfirmOutcome.CONFIRMED else HelperConfirmOutcome.NOT_AWAITING
    }

    /** The user declined the tap: AWAITING_CONFIRM to CANCELLED (`declined`). False for any other state. */
    @Transaction open suspend fun declineConfirm(jobId: String, now: Long): Boolean {
        val r = helperJob(jobId)?.takeIf { stateOf(it) == HelperJobState.AWAITING_CONFIRM } ?: return false
        return closeJob(r, JobEvent.DECLINE, now, voidReason = "declined")
    }

    /** Ends every job whose confirmation window closed (FAILED `not_confirmed`); returns their ids so the service completes the waiters. */
    @Transaction open suspend fun expireAwaitingConfirm(now: Long): List<String> = awaitingPastWindow(now).filter { closeById(it, JobEvent.EXPIRE, now, failCode = "not_confirmed") }

    /** Ends every confirmed job that waited [queueWaitMs] without a lease (FAILED `resources_busy`). */
    @Transaction open suspend fun expireQueuedHelperJobs(now: Long, queueWaitMs: Long): List<String> = queuedPastWait(now, queueWaitMs).filter { closeById(it, JobEvent.EXPIRE, now, failCode = "resources_busy") }

    /** Ends every lease whose lease or job deadline passed (`deadlineMs <= now` wins over the lease: FAILED `deadline_exceeded`, else `lease_expired`). */
    @Transaction open suspend fun expireHelperLeases(now: Long): List<String> = leasedPastExpiry(now).filter { id ->
        val r = helperJob(id) ?: return@filter false
        closeJob(r, JobEvent.LEASE_EXPIRE, now, failCode = if ((r.deadlineMs ?: Long.MAX_VALUE) <= now) "deadline_exceeded" else "lease_expired")
    }

    // --- lease, payload release, heartbeat, result, fail ---
    @Query("SELECT * FROM canvas_helper_job WHERE state = 'QUEUED' AND confirmedAtMs IS NOT NULL AND jobType IN (:types) AND sessionGen = :sessionGen AND NOT EXISTS (SELECT 1 FROM canvas_helper_job WHERE state = 'LEASED' AND leaseOwner = :helperId) ORDER BY createdAt, jobId LIMIT 1")
    protected abstract suspend fun nextLeaseCandidate(types: List<String>, sessionGen: Long, helperId: String): CanvasHelperJobEntity?

    @Query("UPDATE canvas_helper_job SET state = 'LEASED', leaseOwner = :helperId, leaseUntilMs = :leaseUntil, leaseGen = leaseGen + 1, lastHeartbeatMs = :now, deadlineMs = :deadline, updatedAt = :now WHERE jobId = :id AND state = 'QUEUED'")
    protected abstract suspend fun leaseRow(id: String, helperId: String, leaseUntil: Long, deadline: Long, now: Long): Int

    @Query("SELECT payloadJson FROM canvas_helper_job WHERE jobId = :id AND state = 'LEASED' AND leaseOwner = :helperId AND leaseGen = :leaseGen AND leaseUntilMs > :now AND deadlineMs > :now")
    protected abstract suspend fun payloadOfLiveLease(id: String, helperId: String, leaseGen: Long, now: Long): String?

    @Query("UPDATE canvas_helper_job SET leaseUntilMs = :leaseUntil, lastHeartbeatMs = :now, updatedAt = :now WHERE jobId = :id AND state = 'LEASED' AND leaseOwner = :helperId AND leaseGen = :leaseGen AND leaseUntilMs > :now AND deadlineMs > :now")
    protected abstract suspend fun renewLease(id: String, helperId: String, leaseGen: Long, leaseUntil: Long, now: Long): Int

    @Query("UPDATE canvas_helper_job SET state = 'DONE', resultJson = :resultJson, resultHash = :resultHash, payloadJson = NULL, updatedAt = :now WHERE jobId = :id AND state = 'LEASED' AND leaseOwner = :helperId AND leaseGen = :leaseGen AND leaseUntilMs > :now AND deadlineMs > :now")
    protected abstract suspend fun commitRow(id: String, helperId: String, leaseGen: Long, resultHash: String, resultJson: String, now: Long): Int

    /**
     * Leases the oldest CONFIRMED job of an allowed type and the current session to [helperId], only when that helper has no other LEASED job (`maxActiveJobs = 1`, in SQL, so two
     * racing leases of one helper grant exactly one). An AWAITING_CONFIRM job is never a candidate. A candidate whose fence moved is voided here (fail closed) and skipped.
     * The lease lasts [leaseMs]; the job deadline starts NOW (at lease, not at confirm) and lasts [jobDeadlineMs]; queued jobs older than [queueWaitMs] since their confirmation are expired first. Returns metadata only: the payload goes through [releasePayload].
     */
    @Transaction open suspend fun leaseHelperJob(types: List<String>, helperId: String, sessionGen: Long, now: Long, leaseMs: Long, jobDeadlineMs: Long, queueWaitMs: Long, live: HelperFenceLive): LeasedHelperJob? {
        // A confirmed job that waited queueWaitMs ends here (FAILED resources_busy + cleanup) even if nobody ran the sweep: an overdue job is never leased.
        queuedPastWait(now, queueWaitMs).forEach { closeById(it, JobEvent.EXPIRE, now, failCode = "resources_busy") }
        while (true) {
            val c = nextLeaseCandidate(types, sessionGen, helperId) ?: return null
            if (!rowFenceHolds(c, live)) { closeJob(c, JobEvent.VOID, now, voidReason = "fence"); continue }
            if (leaseRow(c.jobId, helperId, now + leaseMs, now + jobDeadlineMs, now) == 0) return null
            return LeasedHelperJob(c.jobId, c.jobType, c.leaseGen + 1, now + leaseMs, now + jobDeadlineMs, c.bundleHash.orEmpty(), c.payloadJson.orEmpty().toByteArray(Charsets.UTF_8).size)
        }
    }

    /** The protocol denial code for [r] when it is not the live lease of [helperId] at [leaseGen]: `not_found`, `not_confirmed`, `voided`, `cancelled`, `stale_generation`, `lease_expired`, `deadline_exceeded`. */
    private fun denialOf(r: CanvasHelperJobEntity?, helperId: String, leaseGen: Long, now: Long): String {
        if (r == null) return "not_found"
        return when (stateOf(r)) {
            HelperJobState.AWAITING_CONFIRM -> "not_confirmed"
            HelperJobState.CANCELLED -> "cancelled"
            HelperJobState.VOIDED, HelperJobState.ABORTED -> "voided"
            HelperJobState.FAILED -> when (r.failCode) { "not_confirmed", "deadline_exceeded" -> r.failCode!!; else -> "lease_expired" }
            HelperJobState.DONE, HelperJobState.QUEUED, null -> "stale_generation"
            HelperJobState.LEASED -> when {
                r.leaseOwner != helperId || r.leaseGen != leaseGen -> "stale_generation"
                (r.deadlineMs ?: Long.MAX_VALUE) <= now -> "deadline_exceeded"
                else -> "lease_expired"
            }
        }
    }

    /** True when [r] is LEASED to [helperId] at [leaseGen] (before any time check): the only rows whose fence is re-checked (and voided) by release, heartbeat and commit. */
    private fun holdsLease(r: CanvasHelperJobEntity?, helperId: String, leaseGen: Long) =
        r != null && stateOf(r) == HelperJobState.LEASED && r.leaseOwner == helperId && r.leaseGen == leaseGen

    /**
     * Releases the payload to the lease holder: the job is LEASED to [helperId] at [leaseGen], the lease and the job deadline are live AND the fence holds
     * (wipe generation, deletion epoch, `frame_forget_rev` re-read here). A moved fence VOIDS the job and no payload leaves. Repeatable for the same lease (a helper retry).
     */
    @Transaction open suspend fun releasePayload(jobId: String, helperId: String, leaseGen: Long, now: Long, live: HelperFenceLive): HelperLeaseResult {
        val r = helperJob(jobId)
        if (holdsLease(r, helperId, leaseGen) && !rowFenceHolds(r!!, live)) { closeJob(r, JobEvent.VOID, now, voidReason = "fence"); return HelperLeaseResult(false, "voided") }
        val payload = payloadOfLiveLease(jobId, helperId, leaseGen, now) ?: return HelperLeaseResult(false, denialOf(r, helperId, leaseGen, now))
        return HelperLeaseResult(true, payload = payload)
    }

    /**
     * Renews the lease: the single place that extends a lease and the single place whose success means a 200 to the helper. A moved fence voids the job and answers a denial, so the
     * helper is never acked after a deletion event.
     */
    @Transaction open suspend fun heartbeatHelperJob(jobId: String, helperId: String, leaseGen: Long, now: Long, leaseMs: Long, live: HelperFenceLive): HelperLeaseResult {
        val r = helperJob(jobId)
        if (holdsLease(r, helperId, leaseGen) && !rowFenceHolds(r!!, live)) { closeJob(r, JobEvent.VOID, now, voidReason = "fence"); return HelperLeaseResult(false, "voided") }
        if (renewLease(jobId, helperId, leaseGen, now + leaseMs, now) == 0) return HelperLeaseResult(false, denialOf(r, helperId, leaseGen, now))
        return HelperLeaseResult(true, leaseUntilMs = now + leaseMs)
    }

    /**
     * Commits the helper's result: the fence is re-checked first (a moved fence voids the job and nothing is stored), then the job must be the live lease of [helperId] at [leaseGen]
     * (`deadlineMs <= now` is expired). The payload is nulled with the commit. The same result repeated is an idempotent DUPLICATE; a different one for a DONE job is refused.
     */
    @Transaction open suspend fun commitHelperResultFenced(jobId: String, helperId: String, leaseGen: Long, resultHash: String, resultJson: String, now: Long, live: HelperFenceLive): HelperLeaseResult {
        val r = helperJob(jobId)
        if (r != null && stateOf(r) == HelperJobState.DONE && r.leaseOwner == helperId && r.leaseGen == leaseGen && r.resultHash == resultHash) {
            // DONE is terminal: a moved fence is answered with the deletion-class denial, never by another transition (and never an ok for forgotten data).
            return if (rowFenceHolds(r, live)) HelperLeaseResult(true, duplicate = true) else HelperLeaseResult(false, "voided")
        }
        if (holdsLease(r, helperId, leaseGen) && !rowFenceHolds(r!!, live)) { closeJob(r, JobEvent.VOID, now, voidReason = "fence"); return HelperLeaseResult(false, "voided") }
        if (commitRow(jobId, helperId, leaseGen, resultHash, resultJson, now) == 0) return HelperLeaseResult(false, denialOf(r, helperId, leaseGen, now))
        insertCleanup(CanvasHelperCleanupEntity(jobId = jobId, reason = "done", createdAt = now)) // the laptop copies of a finished job are deleted too
        return HelperLeaseResult(true)
    }

    /**
     * The helper reports a failure ([code] from the fail enum): only the live lease holder at its generation; payload nulled, cleanup enqueued. Like every other helper-facing
     * call, a moved fence VOIDS the job and an expired lease or job deadline ends it `lease_expired` / `deadline_exceeded`: both answer false, so the helper's code is never
     * acknowledged for a stale job.
     */
    @Transaction open suspend fun failHelperJob(jobId: String, helperId: String, leaseGen: Long, code: String, now: Long, live: HelperFenceLive): Boolean {
        val r = helperJob(jobId)?.takeIf { holdsLease(it, helperId, leaseGen) } ?: return false
        if (!rowFenceHolds(r, live)) { closeJob(r, JobEvent.VOID, now, voidReason = "fence"); return false }
        if ((r.leaseUntilMs ?: 0L) <= now || (r.deadlineMs ?: Long.MAX_VALUE) <= now) {
            closeJob(r, JobEvent.LEASE_EXPIRE, now, failCode = if ((r.deadlineMs ?: Long.MAX_VALUE) <= now) "deadline_exceeded" else "lease_expired")
            return false
        }
        return closeJob(r, JobEvent.FAIL, now, failCode = code)
    }

    // --- reads for the poll and the native confirm card (no payload, no result) ---
    @Query("SELECT jobId, jobType, state, bundleHash, sessionGen, confirmExpiresAtMs, itemCount, sourceChars, COALESCE(LENGTH(CAST(payloadJson AS BLOB)), 0) AS payloadBytes, createdAt FROM canvas_helper_job WHERE state = 'AWAITING_CONFIRM' ORDER BY createdAt, jobId")
    abstract suspend fun awaitingConfirmJobs(): List<HelperJobInfo>

    /** The confirmed, queued jobs a helper of this session may lease (the poll's `available`); never an AWAITING_CONFIRM job. */
    @Query("SELECT jobId, jobType, state, bundleHash, sessionGen, confirmExpiresAtMs, itemCount, sourceChars, COALESCE(LENGTH(CAST(payloadJson AS BLOB)), 0) AS payloadBytes, createdAt FROM canvas_helper_job WHERE state = 'QUEUED' AND confirmedAtMs IS NOT NULL AND jobType IN (:types) AND sessionGen = :sessionGen ORDER BY createdAt, jobId")
    abstract suspend fun availableHelperJobs(types: List<String>, sessionGen: Long): List<HelperJobInfo>

    // --- the cleanup commands the helper executes (survive Delete all) ---
    @Query("SELECT * FROM canvas_helper_cleanup ORDER BY cleanupId LIMIT :limit") abstract suspend fun pendingCleanup(limit: Int): List<CanvasHelperCleanupEntity>
    @Query("DELETE FROM canvas_helper_cleanup WHERE cleanupId IN (:ids)") abstract suspend fun ackCleanup(ids: List<Long>): Int
    /** Drops cleanup rows older than the retention (30 days on the phone). */
    @Query("DELETE FROM canvas_helper_cleanup WHERE createdAt < :olderThanMs") abstract suspend fun purgeOldCleanup(olderThanMs: Long): Int

    /**
     * The Forget cascade of photos for the slice 3a tables (called by [CanvasDao.deleteCanvasOutputsForFrames], inside the Forget or retention
     * transaction): their labels, the results that depended on those labels, and (3b) the helper jobs: `frame_forget_rev` moves, every open job is voided with its cleanup
     * row, and the result of a DONE job that carried one of the frames is nulled. Called for every frame deletion (Forget moment, Forget fact with a frame lock, retention).
     */
    @Transaction open suspend fun deleteSliceOutputsForFrames(frameIds: List<String>, now: Long) {
        if (frameIds.isEmpty()) return
        val labels = deleteLabelsForFrames(frameIds)
        if (labels > 0) invalidateBySources(emptyList(), null, null, labelRevChanged = true)
        moveFrameForgetFence(now)
        scrubDoneJobsForFrames(frameIds, now)
    }


    companion object {
        const val LABEL_REV = "labelRev"
        const val FRAME_FORGET_REV = "frame_forget_rev"
        const val FORGET_RANGES = "forgetRanges"
        const val MOMENT_FORGET_RANGES = "momentForgetRanges"
        const val MAX_RANGES = 200
        fun parseRanges(text: String?): List<LongRange> = text.orEmpty().split(';').mapNotNull { p ->
            val i = p.indexOf('-', 1).takeIf { it > 0 } ?: return@mapNotNull null
            val a = p.substring(0, i).toLongOrNull(); val b = p.substring(i + 1).toLongOrNull()
            if (a != null && b != null && a <= b) a..b else null
        }
        /** Overlapping or touching ranges are merged; past [MAX_RANGES] the two closest are merged too (fencing a little more, never less). */
        fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
            val out = ArrayList<LongRange>()
            for (r in ranges.sortedBy { it.first }) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
            }
            while (out.size > MAX_RANGES) {
                val i = (0 until out.size - 1).minByOrNull { out[it + 1].first - out[it].last }!!
                out[i] = out[i].first..out[i + 1].last; out.removeAt(i + 1)
            }
            return out
        }
        private const val CHUNK = 500
    }
}
