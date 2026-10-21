package com.rokid.context.memory.db

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
)
