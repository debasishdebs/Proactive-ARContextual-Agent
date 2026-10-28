package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CanvasDao {
    // --- pipelines and versions ---
    @Upsert abstract suspend fun upsertPipeline(p: CanvasPipelineEntity)
    @Query("SELECT * FROM canvas_pipeline WHERE id = :id") abstract suspend fun pipeline(id: String): CanvasPipelineEntity?
    @Query("SELECT * FROM canvas_pipeline ORDER BY builtIn DESC, updatedAt DESC") abstract suspend fun pipelines(): List<CanvasPipelineEntity>
    @Query("SELECT * FROM canvas_pipeline ORDER BY builtIn DESC, updatedAt DESC") abstract fun observePipelines(): Flow<List<CanvasPipelineEntity>>
    @Query("DELETE FROM canvas_pipeline WHERE id = :id AND builtIn = 0") abstract suspend fun deletePipeline(id: String): Int

    /** Compare-and-set on draftRev: returns rows updated (0 = stale or built-in). */
    @Query("UPDATE canvas_pipeline SET draftJson = :json, draftRev = draftRev + 1, updatedAt = :now WHERE id = :id AND draftRev = :rev AND builtIn = 0")
    abstract suspend fun saveDraft(id: String, json: String, rev: Int, now: Long): Int
    @Insert abstract suspend fun insertVersion(v: CanvasPipelineVersionEntity)
    @Query("SELECT * FROM canvas_pipeline_version WHERE pipelineId = :id ORDER BY version DESC") abstract suspend fun versions(id: String): List<CanvasPipelineVersionEntity>
    @Query("SELECT * FROM canvas_pipeline_version WHERE pipelineId = :id AND version = :v") abstract suspend fun version(id: String, v: Int): CanvasPipelineVersionEntity?

    // --- runs ---
    @Insert abstract suspend fun insertRun(r: CanvasRunEntity)
    @Query("UPDATE canvas_run SET status = :status, calls = :calls, failures = :failures, finishedAt = :finishedAt WHERE runId = :id")
    abstract suspend fun updateRun(id: String, status: String, calls: Int, failures: Int, finishedAt: Long?)
    @Query("SELECT * FROM canvas_run WHERE runId = :id") abstract suspend fun run(id: String): CanvasRunEntity?
    @Query("SELECT * FROM canvas_run ORDER BY startedAt DESC LIMIT :limit") abstract suspend fun runs(limit: Int): List<CanvasRunEntity>
    @Query("SELECT * FROM canvas_run ORDER BY startedAt DESC LIMIT :limit") abstract fun observeRuns(limit: Int): Flow<List<CanvasRunEntity>>
    @Query("UPDATE canvas_run SET status = 'INTERRUPTED', finishedAt = :now WHERE status = 'RUNNING'") abstract suspend fun interruptRunning(now: Long): Int
    /** QUEUED to RUNNING with the real start time; 0 rows = the row is gone or no longer queued, so the caller must not run it. */
    @Query("UPDATE canvas_run SET status = 'RUNNING', startedAt = :now WHERE runId = :id AND status = 'QUEUED'") abstract suspend fun markStarted(id: String, now: Long): Int
    /** S31 recovery: the QUEUED and RUNNING rows of an A/B group are CANCELLED (B must never start after a restart). Returns the rows changed. */
    @Query("UPDATE canvas_run SET status = 'CANCELLED', finishedAt = :now WHERE abGroup IS NOT NULL AND status IN ('RUNNING', 'QUEUED')") abstract suspend fun cancelOpenGroupRuns(now: Long): Int

    @Transaction open suspend fun deleteRun(id: String) { deleteItems(id); deleteScores(id); deleteSaveMarker(CanvasSaveMarker.key(id)); deleteRunRow(id) }
    /** v0.9.6: the "this run was saved" marker of a canvas run (a run id and a count, no content) goes with the run. */
    @Query("DELETE FROM canvas_meta WHERE `key` = :key") protected abstract suspend fun deleteSaveMarker(key: String)

    /**
     * Deletes every run that holds a watch value (its items are keyed `watch:<atMs>`, WatchMotion.KEY_PREFIX) with its items and scores; returns the runs deleted. Called when the watch source
     * is switched off: the hub forgets the values it gave, so a run row must not keep them either. A run with no item yet holds no value and stays.
     */
    @Transaction open suspend fun deleteWatchRuns(): Int = watchRunIds().also { ids -> ids.forEach { deleteRun(it) } }.size

    /** Keeps the newest [keep] runs (by startedAt) and deletes the rest with their items and scores. */
    @Transaction open suspend fun pruneRuns(keep: Int) { runIdsBeyond(keep).forEach { deleteRun(it) } }

    @Upsert abstract suspend fun upsertItem(i: CanvasRunItemEntity)
    @Query("SELECT * FROM canvas_run_item WHERE runId = :id") abstract suspend fun items(id: String): List<CanvasRunItemEntity>
    @Upsert abstract suspend fun upsertScore(s: CanvasScoreEntity)
    @Query("SELECT COUNT(*) FROM canvas_run_item WHERE runId = :runId AND frameId = :frameId AND nodeId = :nodeId") protected abstract suspend fun itemCount(runId: String, frameId: String, nodeId: String): Int
    /**
     * The user's score and note, written only if, INSIDE this transaction, the wipe generation is still [generation], the frame is not tombstoned and the
     * parent item still exists: a Forget or Delete all between the caller's check and this write can never leave an orphan score holding the note.
     */
    @Transaction open suspend fun upsertScoreFenced(s: CanvasScoreEntity, generation: Long, currentGeneration: () -> Long): Boolean {
        if (!fenceHolds(generation, setOf(s.frameId), currentGeneration)) return false
        if (itemCount(s.runId, s.frameId, s.nodeId) == 0) return false
        upsertScore(s)
        return true
    }
    @Query("SELECT * FROM canvas_score WHERE runId = :id") abstract suspend fun scores(id: String): List<CanvasScoreEntity>

    /** How many of [nodeIds] have at least one OK item in a DONE run of [hash] (publish's "tested" gate). */
    @Query("SELECT COUNT(DISTINCT i.nodeId) FROM canvas_run_item i JOIN canvas_run r ON r.runId = i.runId WHERE r.pipelineHash = :hash AND r.status = 'DONE' AND i.status = 'OK' AND i.nodeId IN (:nodeIds)")
    abstract suspend fun testedNodeCount(hash: String, nodeIds: List<String>): Int

    // --- publish ---
    @Insert abstract suspend fun insertPublish(p: CanvasPublishEntity): Long
    @Query("SELECT * FROM canvas_publish WHERE rolledBackAt IS NULL ORDER BY id DESC") abstract suspend fun activePublishes(): List<CanvasPublishEntity>
    @Query("SELECT * FROM canvas_publish WHERE rolledBackAt IS NULL ORDER BY id DESC") abstract fun observeActivePublishes(): Flow<List<CanvasPublishEntity>>
    @Query("UPDATE canvas_publish SET rolledBackAt = :at WHERE id = :id AND rolledBackAt IS NULL") abstract suspend fun markRolledBack(id: Long, at: Long): Int
    @Query("SELECT * FROM canvas_publish WHERE rolledBackAt IS NULL AND kind = 'interpreter' ORDER BY id DESC LIMIT 1") abstract suspend fun activeInterpreter(): CanvasPublishEntity?
    /** The active Query or Nudge pipeline publish (`kind = flow`, `component` query or nudge; v0.9.8 slice B). */
    @Query("SELECT * FROM canvas_publish WHERE rolledBackAt IS NULL AND kind = 'flow' AND component = :component ORDER BY id DESC LIMIT 1") abstract suspend fun activeFlow(component: String): CanvasPublishEntity?
    @Query("UPDATE canvas_publish SET rolledBackAt = :at, rollbackReason = :reason WHERE id = :id AND rolledBackAt IS NULL") abstract suspend fun markRolledBackWithReason(id: Long, at: Long, reason: String?): Int
    /** Every publish row, newest first (S43 history route). */
    @Query("SELECT * FROM canvas_publish WHERE id = :id") abstract suspend fun publishRow(id: Long): CanvasPublishEntity?
    /** Every capture-plan publish row (slice 3a), newest first, rolled back or not. */
    @Query("SELECT * FROM canvas_publish WHERE component = 'capture_plan' ORDER BY id DESC") abstract suspend fun capturePlanPublishes(): List<CanvasPublishEntity>
    @Query("SELECT * FROM canvas_publish WHERE groupId = :groupId ORDER BY id") abstract suspend fun publishesOfGroup(groupId: String): List<CanvasPublishEntity>
    /** How many TRIAL publishes of this config were ever made (S11: at most 3 per `configHash`, kept even after a rollback). */
    @Query("SELECT COUNT(*) FROM canvas_publish WHERE component = 'capture_plan' AND mode = 'TRIAL' AND configHash = :configHash") abstract suspend fun trialCount(configHash: String): Int
    @Query("UPDATE canvas_publish SET gen = :gen, state = :state, trialEndsAt = :trialEndsAt WHERE id = :id") abstract suspend fun setPlanPublishState(id: Long, gen: Long?, state: String?, trialEndsAt: Long?): Int
    @Query("SELECT * FROM canvas_publish ORDER BY id DESC LIMIT :limit") abstract suspend fun publishHistory(limit: Int): List<CanvasPublishEntity>

    // --- live trace (Room v6) ---
    @Upsert abstract suspend fun upsertTrace(rows: List<CanvasLiveTraceEntity>)
    @Query("SELECT * FROM canvas_live_trace WHERE publishId = :publishId ORDER BY at DESC LIMIT :limit") abstract suspend fun traces(publishId: Long, limit: Int): List<CanvasLiveTraceEntity>
    /** The [limit] newest photos of [publishId] by their latest trace row (the live view). */
    @Query("SELECT frameId FROM canvas_live_trace WHERE publishId = :publishId GROUP BY frameId ORDER BY MAX(at) DESC, frameId LIMIT :limit")
    abstract suspend fun traceFrames(publishId: Long, limit: Int): List<String>
    /** Every trace row of [publishId] for [frameIds] (callers pass at most 100 ids). */
    @Query("SELECT * FROM canvas_live_trace WHERE publishId = :publishId AND frameId IN (:frameIds) ORDER BY at, nodeId")
    abstract suspend fun tracesOfFrames(publishId: Long, frameIds: List<String>): List<CanvasLiveTraceEntity>
    @Query("SELECT * FROM canvas_live_trace WHERE frameId = :frameId ORDER BY at DESC") abstract suspend fun tracesForFrame(frameId: String): List<CanvasLiveTraceEntity>
    /** The last [window] per-photo outcomes of [publishId]: the count of ERROR among them. */
    @Query("SELECT COUNT(*) FROM (SELECT status FROM canvas_live_trace WHERE publishId = :publishId AND nodeId = '_batch' ORDER BY at DESC, rowid DESC LIMIT :window) WHERE status = 'ERROR'")
    abstract suspend fun recentFallbacks(publishId: Long, window: Int): Int
    @Query("SELECT COUNT(*) FROM canvas_live_trace WHERE publishId = :publishId AND nodeId = '_batch'") abstract suspend fun interpretedCount(publishId: Long): Int
    /** As [interpretedCount], read no further than [limit] rows (the auto-rollback only needs to know whether a full window exists). */
    @Query("SELECT COUNT(*) FROM (SELECT 1 FROM canvas_live_trace WHERE publishId = :publishId AND nodeId = '_batch' LIMIT :limit)") abstract suspend fun interpretedCountUpTo(publishId: Long, limit: Int): Int
    @Query("DELETE FROM canvas_live_trace WHERE at < :olderThan") protected abstract suspend fun deleteTracesOlderThan(olderThan: Long): Int
    @Query("DELETE FROM canvas_live_trace WHERE rowid IN (SELECT rowid FROM canvas_live_trace ORDER BY at DESC, rowid DESC LIMIT -1 OFFSET :keep)") protected abstract suspend fun deleteTracesBeyond(keep: Int): Int
    /** Retention: rows older than [olderThan], then all but the newest [keep]. Returns the rows deleted. */
    @Transaction open suspend fun pruneTraces(olderThan: Long, keep: Int): Int = deleteTracesOlderThan(olderThan) + deleteTracesBeyond(keep)

    /**
     * Fenced trace commit (S24): writes nothing (returns false) when the wipe generation moved since [generation] was captured, or when any of
     * [frameIds] is tombstoned. [currentGeneration] is read inside the transaction (the app passes `MemoryWipe.generation::get`).
     */
    @Transaction open suspend fun commitTracesFenced(rows: List<CanvasLiveTraceEntity>, generation: Long, frameIds: Set<String>, currentGeneration: () -> Long): Boolean {
        if (!fenceHolds(generation, frameIds + rows.map { it.frameId }, currentGeneration)) return false
        upsertTrace(rows)
        return true
    }

    /** Fenced run-item (and score) commit of the experiment runner (S24): same fence as [commitTracesFenced]. */
    @Transaction open suspend fun insertRunItemsFenced(rows: List<CanvasRunItemEntity>, scores: List<CanvasScoreEntity>, generation: Long, frameIds: Set<String>, labelIds: Set<String> = emptySet(), currentGeneration: () -> Long): Boolean {
        if (!fenceHolds(generation, frameIds + rows.map { it.frameId } + scores.map { it.frameId }, currentGeneration)) return false
        if (labelIds.isNotEmpty() && labelsMissing(labelIds) > 0) return false // a run over a mark: the mark was deleted while the call waited
        rows.forEach { upsertItem(it) }
        scores.forEach { upsertScore(it) }
        return true
    }

    /** How many of [ids] are not labels (marks) any more. */
    open suspend fun labelsMissing(ids: Collection<String>): Int = ids.distinct().let { u -> u.size - u.chunked(CHUNK).sumOf { labelsAmong(it).size } }
    @Query("SELECT labelId FROM canvas_label WHERE labelId IN (:ids)") protected abstract suspend fun labelsAmong(ids: List<String>): List<String>

    private suspend fun fenceHolds(generation: Long, frameIds: Set<String>, currentGeneration: () -> Long): Boolean {
        if (currentGeneration() != generation) return false
        return frameIds.toList().chunked(CHUNK).none { tombstonedCount(it) > 0 }
    }
    /** How many of [frameIds] are tombstoned (forgotten). */
    open suspend fun tombstonedAmong(frameIds: Collection<String>): Int = frameIds.toList().chunked(CHUNK).sumOf { tombstonedCount(it) }
    @Query("SELECT COUNT(*) FROM forget_tombstone WHERE frameId IN (:frameIds)") protected abstract suspend fun tombstonedCount(frameIds: List<String>): Int

    // --- A/B groups, asks, token means ---
    @Query("SELECT * FROM canvas_run WHERE abGroup = :group ORDER BY startedAt, runId") abstract suspend fun runsInGroup(group: String): List<CanvasRunEntity>
    @Query("SELECT * FROM ask ORDER BY ts DESC LIMIT :limit") abstract suspend fun recentAsks(limit: Int): List<AskEntity>
    @Query("SELECT * FROM ask WHERE id = :id") abstract suspend fun ask(id: String): AskEntity?
    /** How many of [ids] are memories that still exist and are not INVALIDATED (the Forget fence of a replay, S35). */
    open suspend fun liveMemoryCount(ids: Collection<String>): Int = ids.toList().chunked(CHUNK).sumOf { liveMemories(it) }
    @Query("SELECT COUNT(*) FROM memory WHERE id IN (:ids) AND reviewState != 'INVALIDATED'") protected abstract suspend fun liveMemories(ids: List<String>): Int
    /**
     * Mean tokens per provider CALL (S32): sum of tokens / sum of calls over the last [limit] OK owner rows (rows that recorded both tokens and a call count)
     * whose nodeId is in [nodeIds]. A batch-mate row has neither, so a three-photo call counts once. [TokenMeans.n] is the number of owner rows.
     */
    @Query("SELECT CAST(SUM(promptTokens) AS REAL) / SUM(calls) AS p, CAST(SUM(completionTokens) AS REAL) / SUM(calls) AS c, COUNT(*) AS n FROM (SELECT promptTokens, completionTokens, calls FROM canvas_run_item WHERE nodeId IN (:nodeIds) AND status = 'OK' AND promptTokens IS NOT NULL AND calls IS NOT NULL AND calls > 0 ORDER BY rowid DESC LIMIT :limit)")
    abstract suspend fun tokenMeans(nodeIds: List<String>, limit: Int): TokenMeans

    // --- frames for the picker (read-only joins over existing tables) ---
    @Query(
        "SELECT f.id AS id, f.ts AS ts, m.motion AS motion, m.dwell AS dwell, m.blur AS blur, s.state AS state, " +
            "(SELECT COUNT(*) FROM frame_source fs WHERE fs.frameId = f.id) + (SELECT COUNT(*) FROM memory mm WHERE mm.frameId = f.id) AS liveCount " +
            "FROM frame f LEFT JOIN frame_meta m ON m.frameId = f.id LEFT JOIN frame_state s ON s.frameId = f.id " +
            "WHERE f.ts < :beforeMs AND (:dwellOnly = 0 OR m.dwell = 1) AND f.id NOT IN (SELECT frameId FROM forget_tombstone) ORDER BY f.ts DESC LIMIT :limit",
    )
    abstract suspend fun recentFrames(beforeMs: Long, dwellOnly: Boolean, limit: Int): List<CanvasFrameRow>

    /** [recentFrames] with a lower time bound (the day filter): `afterMs <= ts < beforeMs`. */
    @Query(
        "SELECT f.id AS id, f.ts AS ts, m.motion AS motion, m.dwell AS dwell, m.blur AS blur, s.state AS state, " +
            "(SELECT COUNT(*) FROM frame_source fs WHERE fs.frameId = f.id) + (SELECT COUNT(*) FROM memory mm WHERE mm.frameId = f.id) AS liveCount " +
            "FROM frame f LEFT JOIN frame_meta m ON m.frameId = f.id LEFT JOIN frame_state s ON s.frameId = f.id " +
            "WHERE f.ts >= :afterMs AND f.ts < :beforeMs AND (:dwellOnly = 0 OR m.dwell = 1) AND f.id NOT IN (SELECT frameId FROM forget_tombstone) ORDER BY f.ts DESC LIMIT :limit",
    )
    abstract suspend fun recentFramesBetween(afterMs: Long, beforeMs: Long, dwellOnly: Boolean, limit: Int): List<CanvasFrameRow>
    // --- Moment-lite (read-only; no schema): the live frames of a time range and the memories that belong to them ---
    /** Live (not forgotten) frames with `fromMs <= ts <= toMs`, oldest first. `rev` fingerprints what the row says (never the image). */
    @Query(
        "SELECT f.id AS id, f.ts AS ts, (f.ts || ':' || COALESCE(f.phash, '') || ':' || COALESCE(f.placeLabel, '') || ':' || COALESCE(f.luma, '')) AS rev FROM frame f " +
            "WHERE f.ts >= :fromMs AND f.ts <= :toMs AND f.id NOT IN (SELECT frameId FROM forget_tombstone) ORDER BY f.ts, f.id LIMIT :limit",
    )
    abstract suspend fun momentFrames(fromMs: Long, toMs: Long, limit: Int): List<MomentFrameRow>

    /** The NEWEST [limit] live frames in the range, returned oldest first (the Moment picker lists the newest page). */
    @Query(
        "SELECT * FROM (SELECT f.id AS id, f.ts AS ts, (f.ts || ':' || COALESCE(f.phash, '') || ':' || COALESCE(f.placeLabel, '') || ':' || COALESCE(f.luma, '')) AS rev FROM frame f " +
            "WHERE f.ts >= :fromMs AND f.ts <= :toMs AND f.id NOT IN (SELECT frameId FROM forget_tombstone) ORDER BY f.ts DESC, f.id DESC LIMIT :limit) ORDER BY ts, id",
    )
    abstract suspend fun momentFramesNewest(fromMs: Long, toMs: Long, limit: Int): List<MomentFrameRow>

    /** The not-invalidated memories of [frameIds] (their own frame, or a source frame): (frameId, memoryId, rev). */
    @Query(
        "SELECT m.frameId AS frameId, m.id AS id, (m.reviewState || ':' || m.type || ':' || m.tier || ':' || m.confidence || ':' || m.trusted || ':' || m.derived || ':' || m.createdAt || ':' || m.tags || ':' || m.text) AS rev FROM memory m WHERE m.frameId IN (:frameIds) AND m.reviewState != 'INVALIDATED' " +
            "UNION SELECT fs.frameId AS frameId, m.id AS id, (m.reviewState || ':' || m.type || ':' || m.tier || ':' || m.confidence || ':' || m.trusted || ':' || m.derived || ':' || m.createdAt || ':' || m.tags || ':' || m.text) AS rev FROM frame_source fs JOIN memory m ON m.id = fs.memoryId " +
            "WHERE fs.frameId IN (:frameIds) AND m.reviewState != 'INVALIDATED'",
    )
    abstract suspend fun momentMemories(frameIds: List<String>): List<MomentMemoryRow>

    /**
     * The VISIBLE memory texts of [frameIds] (live frames), the same validity the export applies: reviewState OK (not forgotten, not hidden for review) and, for a derived memory, at least one
     * source memory that is not forgotten (a derived memory whose sources are all forgotten is stale-hidden). A memory whose photo is gone is never found (it is read through the live frames).
     */
    @Query(
        "SELECT x.frameId AS frameId, m.id AS id, m.createdAt AS createdAt, m.text AS text FROM (SELECT id AS mid, frameId AS frameId FROM memory WHERE frameId IN (:frameIds) " +
            "UNION SELECT memoryId AS mid, frameId AS frameId FROM frame_source WHERE frameId IN (:frameIds)) x JOIN memory m ON m.id = x.mid " +
            "WHERE m.reviewState = 'OK' AND (m.derived = 0 OR NOT EXISTS (SELECT 1 FROM memory_source s WHERE s.memoryId = m.id) OR EXISTS (SELECT 1 FROM memory_source s JOIN memory m2 ON m2.id = s.sourceMemoryId " +
            "WHERE s.memoryId = m.id AND m2.reviewState != 'INVALIDATED'))",
    )
    abstract suspend fun momentVisibleTexts(frameIds: List<String>): List<MomentTextRow>

    @Query("SELECT id, text, type FROM memory WHERE frameId = :frameId OR id IN (SELECT memoryId FROM frame_source WHERE frameId = :frameId) ORDER BY createdAt")
    abstract suspend fun liveMemories(frameId: String): List<LiveMemoryRow>

    // --- helpers and the Forget cascade ---
    @Query("DELETE FROM canvas_run_item WHERE runId = :id") protected abstract suspend fun deleteItems(id: String)
    @Query("DELETE FROM canvas_score WHERE runId = :id") protected abstract suspend fun deleteScores(id: String)
    @Query("DELETE FROM canvas_run WHERE runId = :id") protected abstract suspend fun deleteRunRow(id: String)
    @Query("SELECT DISTINCT runId FROM canvas_run_item WHERE frameId LIKE 'watch:%'") protected abstract suspend fun watchRunIds(): List<String>
    @Query("SELECT runId FROM canvas_run ORDER BY startedAt DESC, runId DESC LIMIT -1 OFFSET :keep") protected abstract suspend fun runIdsBeyond(keep: Int): List<String>
    @Query("DELETE FROM canvas_run_item WHERE frameId IN (:frameIds)") abstract suspend fun deleteItemsForFrames(frameIds: List<String>): Int
    @Query("DELETE FROM canvas_score WHERE frameId IN (:frameIds)") abstract suspend fun deleteScoresForFrames(frameIds: List<String>): Int
    @Query("DELETE FROM canvas_live_trace WHERE frameId IN (:frameIds)") abstract suspend fun deleteTracesForFrames(frameIds: List<String>): Int

    // batchId is a lineage list ("own|earlier|..."): a row belongs to a call when the call is one of its '|'-separated tokens.
    @Query("SELECT DISTINCT batchId FROM canvas_run_item WHERE frameId IN (:frameIds) AND batchId IS NOT NULL") protected abstract suspend fun itemBatchesOf(frameIds: List<String>): List<String>
    @Query("SELECT DISTINCT batchId FROM canvas_live_trace WHERE frameId IN (:frameIds) AND batchId IS NOT NULL") protected abstract suspend fun traceBatchesOf(frameIds: List<String>): List<String>
    @Query("SELECT DISTINCT frameId FROM canvas_run_item WHERE batchId IS NOT NULL AND instr('|' || batchId || '|', '|' || :token || '|') > 0") protected abstract suspend fun itemKeysOfBatch(token: String): List<String>
    @Query("SELECT DISTINCT frameId FROM canvas_live_trace WHERE batchId IS NOT NULL AND instr('|' || batchId || '|', '|' || :token || '|') > 0") protected abstract suspend fun traceKeysOfBatch(token: String): List<String>
    @Query("DELETE FROM canvas_score WHERE EXISTS (SELECT 1 FROM canvas_run_item i WHERE i.runId = canvas_score.runId AND i.frameId = canvas_score.frameId AND i.nodeId = canvas_score.nodeId AND i.batchId IS NOT NULL AND instr('|' || i.batchId || '|', '|' || :token || '|') > 0)")
    protected abstract suspend fun deleteScoresOfBatch(token: String): Int
    @Query("DELETE FROM canvas_run_item WHERE batchId IS NOT NULL AND instr('|' || batchId || '|', '|' || :token || '|') > 0") protected abstract suspend fun deleteItemsOfBatch(token: String): Int
    @Query("DELETE FROM canvas_live_trace WHERE batchId IS NOT NULL AND instr('|' || batchId || '|', '|' || :token || '|') > 0") protected abstract suspend fun deleteTracesOfBatch(token: String): Int

    /**
     * The Forget cascade (S25): every item, score and trace row of [frameIds], plus every row (of any frame) that lists one of their calls in its
     * lineage, because one provider call produced them all and later nodes (critic, write) derive from it. Idempotent; call it inside the Forget transaction.
     */
    @Transaction open suspend fun deleteCanvasOutputsForFrames(frameIds: List<String>) {
        if (frameIds.isEmpty()) return
        val tokens = lineageTokensOf(frameIds)
        // Scores first: they are matched through the items that still exist.
        tokens.forEach { deleteScoresOfBatch(it) }
        frameIds.chunked(CHUNK).forEach { deleteScoresForFrames(it) }
        tokens.forEach { deleteItemsOfBatch(it); deleteTracesOfBatch(it) }
        frameIds.chunked(CHUNK).forEach { deleteItemsForFrames(it); deleteTracesForFrames(it) }
    }

    // Moment runs list every member photo as an "mf:<frameId>" lineage token on all their rows, so a Forget of any member photo finds the Moment's rows (no schema change).
    private suspend fun lineageTokensOf(frameIds: List<String>): List<String> =
        (frameIds.chunked(CHUNK).flatMap { itemBatchesOf(it) + traceBatchesOf(it) }.flatMap { it.split('|') } + frameIds.map { "mf:$it" }).filter { it.isNotEmpty() }.distinct()

    /**
     * The row keys (photo ids, `moment:`/`scene:` keys) OTHER than [frameIds] whose results [deleteCanvasOutputsForFrames] would also delete through a shared lineage token: the batch peers. Read only, the same discovery the delete uses,
     * so a Forget preview can count and hash them (the cascade stays batch-wide on purpose: one provider call produced them all, and a peer's result may carry text of the forgotten photo).
     */
    open suspend fun batchPeersOf(frameIds: List<String>): Set<String> {
        if (frameIds.isEmpty()) return emptySet()
        return lineageTokensOf(frameIds).flatMap { itemKeysOfBatch(it) + traceKeysOfBatch(it) }.toSet() - frameIds.toSet()
    }

    private companion object { const val CHUNK = 500 }
}

data class TokenMeans(val p: Double?, val c: Double?, val n: Int)

data class CanvasFrameRow(val id: String, val ts: Long, val motion: String?, val dwell: Boolean?, val blur: Float?, val state: String?, val liveCount: Int)
data class MomentFrameRow(val id: String, val ts: Long, val rev: String)
data class MomentTextRow(val frameId: String, val id: String, val createdAt: Long, val text: String)
data class MomentMemoryRow(val frameId: String, val id: String, val rev: String)
data class LiveMemoryRow(val id: String, val text: String, val type: String)
