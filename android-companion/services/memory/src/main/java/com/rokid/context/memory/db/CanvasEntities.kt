package com.rokid.context.memory.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Experiment canvas (Room v3, additive). Pipelines and frozen versions hold prompts, not photo-derived data. */
@Entity(tableName = "canvas_pipeline")
data class CanvasPipelineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val draftJson: String,
    val draftRev: Int,
    val builtIn: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "canvas_pipeline_version", primaryKeys = ["pipelineId", "version"])
data class CanvasPipelineVersionEntity(
    val pipelineId: String,
    val version: Int,
    val json: String,
    val hash: String,
    val note: String,
    val frozenAt: Long,
)

@Entity(tableName = "canvas_run", indices = [Index("pipelineId"), Index("startedAt")])
data class CanvasRunEntity(
    @PrimaryKey val runId: String,
    val pipelineId: String,
    val version: Int?,
    val pipelineHash: String,
    val pipelineJson: String,
    val status: String,
    val frameCount: Int,
    val estCalls: Int,
    val calls: Int,
    val failures: Int,
    val startedAt: Long,
    val finishedAt: Long?,
    val modelsJson: String,
    /** Room v6: the A/B group this run belongs to (null = a plain run). */
    val abGroup: String? = null,
)

/** Photo-derived output of one node for one photo: deleted by Delete all and by Forget of that photo. */
@Entity(tableName = "canvas_run_item", primaryKeys = ["runId", "frameId", "nodeId"], indices = [Index("frameId")])
data class CanvasRunItemEntity(
    val runId: String,
    val frameId: String,
    val nodeId: String,
    val status: String,
    val branch: String?,
    val outputJson: String?,
    val error: String?,
    val latencyMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val model: String?,
    val promptVersion: Int?,
    val batchId: String?,
    /** Room v6: provider calls made for this item (null = not recorded). */
    val calls: Int? = null,
)

@Entity(tableName = "canvas_score", primaryKeys = ["runId", "frameId", "nodeId"], indices = [Index("frameId")])
data class CanvasScoreEntity(
    val runId: String,
    val frameId: String,
    val nodeId: String,
    val score: Int,
    val note: String,
    val updatedAt: Long,
)

@Entity(tableName = "canvas_publish")
data class CanvasPublishEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pipelineId: String,
    val version: Int,
    val hash: String,
    val publishedAt: Long,
    val rolledBackAt: Long?,
    val snapshotJson: String,
    /** Room v6: `prompts` (the MVP prompt publish) or `interpreter`. */
    @ColumnInfo(defaultValue = "prompts") val kind: String = "prompts",
    /** Room v6: SHADOW or LIVE for an interpreter publish; null for prompts. */
    val mode: String? = null,
    /** Room v6: fixed short reason a publish was rolled back (auto-rollback), never provider text. */
    val rollbackReason: String? = null,
    /** Room v7 (slice 3a capture-plan publish; all null for a prompts or interpreter publish): the publish group this row belongs to. */
    val groupId: String? = null,
    /** Room v7: which half of a plan group (`phone`, `glasses`, ...). */
    val component: String? = null,
    /** Room v7: the canonical CapturePlan JSON (config and meta; no photo data). */
    val planJson: String? = null,
    /** Room v7: SHA-256 of the plan's config block. */
    val configHash: String? = null,
    /** Room v7: the group generation. */
    val gen: Long? = null,
    /** Room v7: the group state-machine state. */
    val state: String? = null,
    /** Room v7: when a TRIAL ends (glasses enforce it themselves). */
    val trialEndsAt: Long? = null,
)

/**
 * Room v6: one row per (publish, photo, node) of an interpreter publish (Live or Shadow). `nodeId = "_batch"` is the per-photo outcome row
 * (OK when the interpreter's extraction was used, ERROR with a fixed text when the batch fell back). Photo-derived debug data: deleted by Delete all
 * and by Forget (batch-wide through [batchId]), pruned by retention, never exported or backed up.
 */
@Entity(tableName = "canvas_live_trace", primaryKeys = ["publishId", "frameId", "nodeId"], indices = [Index("frameId"), Index("batchId"), Index("at")])
data class CanvasLiveTraceEntity(
    val publishId: Long,
    val frameId: String,
    val nodeId: String,
    val mode: String,
    val status: String,
    val branch: String?,
    val outputJson: String?,
    val error: String?,
    val batchId: String?,
    val at: Long,
)
