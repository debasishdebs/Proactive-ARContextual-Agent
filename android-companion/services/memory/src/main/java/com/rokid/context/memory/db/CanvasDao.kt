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

    @Transaction open suspend fun deleteRun(id: String) { deleteItems(id); deleteScores(id); deleteRunRow(id) }

    /** Keeps the newest [keep] runs (by startedAt) and deletes the rest with their items and scores. */
    @Transaction open suspend fun pruneRuns(keep: Int) { runIdsBeyond(keep).forEach { deleteRun(it) } }

    @Upsert abstract suspend fun upsertItem(i: CanvasRunItemEntity)
    @Query("SELECT * FROM canvas_run_item WHERE runId = :id") abstract suspend fun items(id: String): List<CanvasRunItemEntity>
    @Upsert abstract suspend fun upsertScore(s: CanvasScoreEntity)
    @Query("SELECT * FROM canvas_score WHERE runId = :id") abstract suspend fun scores(id: String): List<CanvasScoreEntity>

    /** How many of [nodeIds] have at least one OK item in a DONE run of [hash] (publish's "tested" gate). */
    @Query("SELECT COUNT(DISTINCT i.nodeId) FROM canvas_run_item i JOIN canvas_run r ON r.runId = i.runId WHERE r.pipelineHash = :hash AND r.status = 'DONE' AND i.status = 'OK' AND i.nodeId IN (:nodeIds)")
    abstract suspend fun testedNodeCount(hash: String, nodeIds: List<String>): Int

    // --- publish ---
    @Insert abstract suspend fun insertPublish(p: CanvasPublishEntity): Long
    @Query("SELECT * FROM canvas_publish WHERE rolledBackAt IS NULL ORDER BY id DESC") abstract suspend fun activePublishes(): List<CanvasPublishEntity>
    @Query("SELECT * FROM canvas_publish WHERE rolledBackAt IS NULL ORDER BY id DESC") abstract fun observeActivePublishes(): Flow<List<CanvasPublishEntity>>
    @Query("UPDATE canvas_publish SET rolledBackAt = :at WHERE id = :id AND rolledBackAt IS NULL") abstract suspend fun markRolledBack(id: Long, at: Long): Int

    // --- frames for the picker (read-only joins over existing tables) ---
    @Query(
        "SELECT f.id AS id, f.ts AS ts, m.motion AS motion, m.dwell AS dwell, m.blur AS blur, s.state AS state, " +
            "(SELECT COUNT(*) FROM frame_source fs WHERE fs.frameId = f.id) + (SELECT COUNT(*) FROM memory mm WHERE mm.frameId = f.id) AS liveCount " +
            "FROM frame f LEFT JOIN frame_meta m ON m.frameId = f.id LEFT JOIN frame_state s ON s.frameId = f.id " +
            "WHERE f.ts < :beforeMs AND (:dwellOnly = 0 OR m.dwell = 1) AND f.id NOT IN (SELECT frameId FROM forget_tombstone) ORDER BY f.ts DESC LIMIT :limit",
    )
    abstract suspend fun recentFrames(beforeMs: Long, dwellOnly: Boolean, limit: Int): List<CanvasFrameRow>
    @Query("SELECT id, text, type FROM memory WHERE frameId = :frameId OR id IN (SELECT memoryId FROM frame_source WHERE frameId = :frameId) ORDER BY createdAt")
    abstract suspend fun liveMemories(frameId: String): List<LiveMemoryRow>

    // --- helpers and the Forget cascade ---
    @Query("DELETE FROM canvas_run_item WHERE runId = :id") protected abstract suspend fun deleteItems(id: String)
    @Query("DELETE FROM canvas_score WHERE runId = :id") protected abstract suspend fun deleteScores(id: String)
    @Query("DELETE FROM canvas_run WHERE runId = :id") protected abstract suspend fun deleteRunRow(id: String)
    @Query("SELECT runId FROM canvas_run ORDER BY startedAt DESC LIMIT -1 OFFSET :keep") protected abstract suspend fun runIdsBeyond(keep: Int): List<String>
    @Query("DELETE FROM canvas_run_item WHERE frameId IN (:frameIds)") abstract suspend fun deleteItemsForFrames(frameIds: List<String>): Int
    @Query("DELETE FROM canvas_score WHERE frameId IN (:frameIds)") abstract suspend fun deleteScoresForFrames(frameIds: List<String>): Int
}

data class CanvasFrameRow(val id: String, val ts: Long, val motion: String?, val dwell: Boolean?, val blur: Float?, val state: String?, val liveCount: Int)
data class LiveMemoryRow(val id: String, val text: String, val type: String)
