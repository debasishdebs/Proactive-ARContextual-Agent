package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

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
    @Query("DELETE FROM canvas_label WHERE frameId IN (:frameIds)") protected abstract suspend fun deleteLabelRowsForFrames(frameIds: List<String>): Int
    @Query("DELETE FROM canvas_label WHERE atMs >= :fromMs AND atMs <= :toMs") protected abstract suspend fun deleteLabelRowsBetween(fromMs: Long, toMs: Long): Int

    /**
     * Inserts a mark only if, inside this transaction, the fence still holds AND the mark's occurrence time is not before the latest wipe
     * (`wipedAtMs`, S57), then bumps `labelRev`. False writes nothing.
     */
    @Transaction open suspend fun insertLabelFenced(label: CanvasLabelEntity, fence: DaoFence): Boolean {
        if (!fence.holds()) return false
        if (label.atMs < fence.now().wipedAtMs) return false
        if (label(label.labelId) != null) return true // a re-sent mark: already recorded, and labelRev must not move (it would refuse every in-flight replay commit)
        insertLabel(label)
        bumpLabelRev()
        return true
    }

    @Transaction open suspend fun deleteLabel(id: String): Boolean {
        val n = deleteLabelRow(id)
        if (n > 0) bumpLabelRev()
        return n > 0
    }

    @Transaction open suspend fun correctLabel(id: String, name: String): Boolean {
        val n = renameLabelRow(id, name)
        if (n > 0) bumpLabelRev()
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
    @Query("UPDATE canvas_trace SET tombstoned = 1, manifestJson = '{}' WHERE traceId = :id") protected abstract suspend fun markTombstoned(id: String): Int

    /**
     * A trace (or its updated manifest) is accepted only while the fence holds, the trace is not tombstoned, and it was recorded under the CURRENT
     * epoch (same `installId` and `deletionCounter`): a chunk from before a Delete all or from another pairing is refused (S57).
     */
    @Transaction open suspend fun acceptTraceFenced(t: CanvasTraceEntity, fence: DaoFence): Boolean {
        if (!fence.holds()) return false
        val epoch = fence.now()
        if (t.installId != epoch.installId || t.deletionCounter != epoch.counter) return false
        if (trace(t.traceId)?.tombstoned == true) return false
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
    @Query("SELECT * FROM canvas_sweep WHERE sweepId = :id") abstract suspend fun sweep(id: String): CanvasSweepEntity?
    @Query("SELECT * FROM canvas_sweep ORDER BY createdAt DESC, sweepId LIMIT :limit") abstract suspend fun sweeps(limit: Int): List<CanvasSweepEntity>
    @Insert abstract suspend fun insertReplay(r: CanvasReplayEntity)
    @Insert abstract suspend fun insertSweep(s: CanvasSweepEntity)
    @Query("UPDATE canvas_replay SET jobState = 'DONE', resultJson = :result, finishedAt = :now WHERE replayId = :id AND jobState = 'RUNNING' AND invalidated = 0 AND configHash IS :configHash")
    protected abstract suspend fun finishReplay(id: String, result: String, configHash: String?, now: Long): Int
    @Query("UPDATE canvas_sweep SET jobState = 'DONE', resultJson = :result, finishedAt = :now WHERE sweepId = :id AND jobState = 'RUNNING' AND invalidated = 0 AND configHash IS :configHash")
    protected abstract suspend fun finishSweep(id: String, result: String, configHash: String?, now: Long): Int
    @Query("UPDATE canvas_replay SET jobState = 'CANCELLED', finishedAt = :now WHERE replayId = :id AND jobState = 'RUNNING'") abstract suspend fun cancelReplay(id: String, now: Long): Int
    @Query("UPDATE canvas_sweep SET jobState = 'CANCELLED', finishedAt = :now WHERE sweepId = :id AND jobState = 'RUNNING'") abstract suspend fun cancelSweep(id: String, now: Long): Int
    @Query("UPDATE canvas_replay SET jobState = 'ABORTED', finishedAt = :now WHERE jobState = 'RUNNING'") protected abstract suspend fun abortReplays(now: Long): Int
    @Query("UPDATE canvas_sweep SET jobState = 'ABORTED', finishedAt = :now WHERE jobState = 'RUNNING'") protected abstract suspend fun abortSweeps(now: Long): Int

    /** Start-up recovery (S77): a job still RUNNING belonged to a dead process. Marks every such replay and sweep ABORTED; returns how many. */
    @Transaction open suspend fun markRunningAborted(now: Long): Int = abortReplays(now) + abortSweeps(now)

    /**
     * True when every source still exists, is not tombstoned and is at the revision that was read, and [expectedLabelRev] is still the label counter.
     * Called inside a commit transaction.
     */
    private suspend fun sourcesHold(sources: List<TraceSource>, expectedLabelRev: Long): Boolean {
        if (labelRev() != expectedLabelRev) return false
        return sources.all { s -> trace(s.traceId)?.let { !it.tombstoned && it.revision == s.revision } == true }
    }

    /**
     * Commits a replay result (of the config [configHash], which must be the row's) only if, INSIDE this transaction, the wipe generation and the persisted epoch hold, no source trace is tombstoned or
     * rewritten, `labelRev` did not move, and the job is still RUNNING and not invalidated. Otherwise nothing is written (the caller drops the result).
     */
    @Transaction open suspend fun commitReplayFenced(replayId: String, resultJson: String, configHash: String?, sources: List<TraceSource>, expectedLabelRev: Long, fence: DaoFence, now: Long): Boolean {
        if (!fence.holds()) return false
        if (!sourcesHold(sources, expectedLabelRev)) return false
        if (TraceSources.decode(replay(replayId)?.sourcesJson ?: return false) == null) return false // unreadable provenance: fail closed
        return finishReplay(replayId, resultJson, configHash, now) > 0
    }

    /** As [commitReplayFenced], for a sweep. */
    @Transaction open suspend fun commitSweepFenced(sweepId: String, resultJson: String, configHash: String?, sources: List<TraceSource>, expectedLabelRev: Long, fence: DaoFence, now: Long): Boolean {
        if (!fence.holds()) return false
        if (!sourcesHold(sources, expectedLabelRev)) return false
        if (TraceSources.decode(sweep(sweepId)?.sourcesJson ?: return false) == null) return false
        return finishSweep(sweepId, resultJson, configHash, now) > 0
    }

    @Query("UPDATE canvas_replay SET invalidated = 1, resultJson = NULL WHERE invalidated = 0 AND instr(sourcesJson, :needle) > 0") protected abstract suspend fun invalidateReplaysOfTrace(needle: String): Int
    @Query("UPDATE canvas_sweep SET invalidated = 1, resultJson = NULL WHERE invalidated = 0 AND instr(sourcesJson, :needle) > 0") protected abstract suspend fun invalidateSweepsOfTrace(needle: String): Int
    @Query("UPDATE canvas_replay SET invalidated = 1, resultJson = NULL WHERE invalidated = 0 AND (fromMs IS NULL OR toMs IS NULL OR (fromMs <= :toMs AND toMs >= :fromMs))") protected abstract suspend fun invalidateReplaysInRange(fromMs: Long, toMs: Long): Int
    @Query("UPDATE canvas_sweep SET invalidated = 1, resultJson = NULL WHERE invalidated = 0 AND (fromMs IS NULL OR toMs IS NULL OR (fromMs <= :toMs AND toMs >= :fromMs))") protected abstract suspend fun invalidateSweepsInRange(fromMs: Long, toMs: Long): Int
    @Query("UPDATE canvas_replay SET invalidated = 1, resultJson = NULL WHERE invalidated = 0 AND labelRev != :labelRev") protected abstract suspend fun invalidateReplaysOfOtherLabelRev(labelRev: Long): Int
    @Query("UPDATE canvas_sweep SET invalidated = 1, resultJson = NULL WHERE invalidated = 0 AND labelRev != :labelRev") protected abstract suspend fun invalidateSweepsOfOtherLabelRev(labelRev: Long): Int
    @Query("UPDATE canvas_replay SET invalidated = 1, resultJson = NULL WHERE invalidated = 0 AND sweepId IN (SELECT sweepId FROM canvas_sweep WHERE invalidated = 1)") protected abstract suspend fun invalidateReplaysOfInvalidSweeps(): Int

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

    // --- calibration ---
    @Insert abstract suspend fun insertCalibration(c: CanvasCalibrationEntity): Long
    @Query("SELECT * FROM canvas_calibration WHERE configHash = :configHash ORDER BY id DESC") abstract suspend fun calibrations(configHash: String): List<CanvasCalibrationEntity>
    @Query("SELECT COUNT(*) FROM canvas_calibration WHERE configHash = :configHash AND verified = 1") abstract suspend fun verifiedCount(configHash: String): Int

    // --- publish groups (S62 recovery table) ---
    @Upsert abstract suspend fun upsertGroupState(s: CanvasGroupStateEntity)
    @Query("SELECT * FROM canvas_group_state WHERE groupId = :groupId") abstract suspend fun groupState(groupId: String): CanvasGroupStateEntity?
    @Query("SELECT * FROM canvas_group_state ORDER BY updatedAt DESC, groupId") abstract suspend fun groupStates(): List<CanvasGroupStateEntity>
    @Query("DELETE FROM canvas_group_state WHERE groupId = :groupId") abstract suspend fun deleteGroupState(groupId: String): Int

    // --- helper jobs (3b writes them) ---
    @Insert abstract suspend fun insertHelperJob(j: CanvasHelperJobEntity)
    @Query("SELECT * FROM canvas_helper_job WHERE jobId = :id") abstract suspend fun helperJob(id: String): CanvasHelperJobEntity?
    @Query("SELECT * FROM canvas_helper_job WHERE state NOT IN ('DONE', 'VOIDED', 'FAILED')") abstract suspend fun openHelperJobs(): List<CanvasHelperJobEntity>
    @Query("UPDATE canvas_helper_job SET state = 'VOIDED', frameIdsJson = '[]', leaseOwner = NULL, leaseUntilMs = NULL, updatedAt = :now WHERE state NOT IN ('DONE', 'VOIDED', 'FAILED') AND instr(frameIdsJson, :needle) > 0")
    protected abstract suspend fun voidHelperJobsCarrying(needle: String, now: Long): Int

    /** Forget of photos: every open helper job that carries one of them is voided and drops the ids (the helper deletes its inputs on its next poll). */
    @Transaction open suspend fun voidHelperJobsForFrames(frameIds: List<String>, now: Long): Int =
        frameIds.sumOf { voidHelperJobsCarrying("\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"", now) }

    /**
     * The Forget cascade of photos for the slice 3a tables (called by [CanvasDao.deleteCanvasOutputsForFrames], inside the Forget or retention
     * transaction): their labels, the results that depended on those labels, and the helper jobs that carry them.
     */
    @Transaction open suspend fun deleteSliceOutputsForFrames(frameIds: List<String>, now: Long) {
        if (frameIds.isEmpty()) return
        val labels = deleteLabelsForFrames(frameIds)
        if (labels > 0) invalidateBySources(emptyList(), null, null, labelRevChanged = true)
        voidHelperJobsForFrames(frameIds, now)
    }

    companion object {
        const val LABEL_REV = "labelRev"
        private const val CHUNK = 500
    }
}
