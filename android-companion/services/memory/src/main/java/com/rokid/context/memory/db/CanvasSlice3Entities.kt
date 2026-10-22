package com.rokid.context.memory.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Experiment canvas slice 3a (Room v7, migration A, additive). Nine new tables; the seven new `canvas_publish` columns are on
 * [CanvasPublishEntity]. Data-safety rule: every table here is either WIPED by Delete all or KEPT BY DESIGN (it holds no user data), and
 * every table that can hold derived data is invalidated by the label/trace/frame deletion paths (Task 34). SchemaTest pins the classification.
 *
 * Privacy: no audio, transcript, location, address or device name is ever stored here. Marks keep times, sources and enum or canvas label names.
 */

/**
 * One recorded trace (an IMU trace pulled from the glasses, or a hub trace). `status` is COMPLETE or PARTIAL; `tombstoned` rows keep only
 * their identity so a late chunk for a deleted or rewritten trace is rejected. A rewrite creates a NEW id (`revision + 1`, `parentTraceId`) and
 * tombstones the original (S58). `installId` and `deletionCounter` are the persisted deletion epoch the trace was recorded under (S57).
 */
@Entity(tableName = "canvas_trace", indices = [Index("startedAtMs"), Index("parentTraceId")])
data class CanvasTraceEntity(
    @PrimaryKey val traceId: String,
    /** `IMU` (glasses) or `HUB` (phone fusion evidence). */
    val source: String,
    /** `COMPLETE` or `PARTIAL`. */
    val status: String,
    val manifestJson: String,
    val wipeGen: Long,
    @ColumnInfo(defaultValue = "0") val tombstoned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val revision: Int = 0,
    val parentTraceId: String? = null,
    @ColumnInfo(defaultValue = "") val installId: String = "",
    @ColumnInfo(defaultValue = "0") val deletionCounter: Long = 0,
    /** True when the recorder kept every sample (exact parity is possible); false for a down-sampled trace. */
    @ColumnInfo(defaultValue = "1") val exact: Boolean = true,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val createdAt: Long,
)

/** Hub trace event (enum names, kmh rounded to 5, confidence): numbers and enum names only. */
@Entity(tableName = "canvas_trace_event", primaryKeys = ["traceId", "seq"], indices = [Index("tMs")])
data class CanvasTraceEventEntity(
    val traceId: String,
    val seq: Int,
    val tMs: Long,
    val kind: String,
    val payloadJson: String,
)

/**
 * A replay result. `replayClass` is EXACT or COUNTERFACTUAL. `sourcesJson` lists the traces (id, revision, interval) it read; `labelRev` is the
 * label counter it was computed under (S75). A result is INVALIDATED (flag set, `resultJson` deleted) when a source or the labels change.
 * `jobState` is RUNNING, DONE, CANCELLED or ABORTED (S77). `fromMs`/`toMs` are the envelope of the source intervals (null when unknown),
 * used by time-range invalidation; they are also the columns reserved for the later ranged replay.
 */
@Entity(tableName = "canvas_replay", indices = [Index("sweepId"), Index("createdAt")])
data class CanvasReplayEntity(
    @PrimaryKey val replayId: String,
    val sweepId: String? = null,
    val paramsJson: String,
    val replayClass: String,
    val resultJson: String? = null,
    @ColumnInfo(defaultValue = "0") val invalidated: Boolean = false,
    @ColumnInfo(defaultValue = "'[]'") val sourcesJson: String = "[]",
    @ColumnInfo(defaultValue = "0") val labelRev: Long = 0,
    @ColumnInfo(defaultValue = "'RUNNING'") val jobState: String = "RUNNING",
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

/** A parameter sweep (grid or seeded random) with its split days, results and provenance (same invalidation and job-state rules as a replay). */
@Entity(tableName = "canvas_sweep", indices = [Index("createdAt")])
data class CanvasSweepEntity(
    @PrimaryKey val sweepId: String,
    val paramsJson: String,
    @ColumnInfo(defaultValue = "'[]'") val splitDaysJson: String = "[]",
    val resultJson: String? = null,
    @ColumnInfo(defaultValue = "0") val invalidated: Boolean = false,
    @ColumnInfo(defaultValue = "'[]'") val sourcesJson: String = "[]",
    @ColumnInfo(defaultValue = "0") val labelRev: Long = 0,
    @ColumnInfo(defaultValue = "'RUNNING'") val jobState: String = "RUNNING",
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

/**
 * A mark (MOMENT) or an intent label (INTENT). There is NO text column: `name` is an enum or canvas label name, never a transcript.
 * `atMs` is the occurrence time (wall clock) and is fenced against `wipedAtMs`. `provenance` is a short fixed token (for example the listen id).
 */
@Entity(tableName = "canvas_label", indices = [Index("atMs"), Index("frameId")])
data class CanvasLabelEntity(
    @PrimaryKey val labelId: String,
    /** `MOMENT` or `INTENT`. */
    val kind: String,
    /** tap, voice, phone, retro, ... */
    val source: String,
    val name: String,
    val atMs: Long,
    val frameId: String? = null,
    val traceId: String? = null,
    @ColumnInfo(defaultValue = "") val provenance: String = "",
    @ColumnInfo(defaultValue = "0") val deletionCounter: Long = 0,
    val createdAt: Long,
)

/** Small durable key/value counters. `labelRev` is bumped by every label insert, delete or correction (S75). Holds no user data. */
@Entity(tableName = "canvas_meta")
data class CanvasMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** A measured-run calibration artifact (counts only; kept unless Delete all). `verified` is set only by a fail-closed import. */
@Entity(tableName = "canvas_calibration", indices = [Index("configHash")])
data class CanvasCalibrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val configHash: String,
    val settingsRev: Long,
    val mode: String,
    val phasesJson: String,
    val measuredJson: String? = null,
    val estimatedJson: String? = null,
    val artifactJson: String? = null,
    @ColumnInfo(defaultValue = "0") val verified: Boolean = false,
    val createdAt: Long,
)

/** The S62 recovery table of a publish group: the last durable transition per group (no user data; kept by Delete all). */
@Entity(tableName = "canvas_group_state")
data class CanvasGroupStateEntity(
    @PrimaryKey val groupId: String,
    val gen: Long,
    val publishId: Long,
    val configHash: String,
    val component: String,
    val state: String,
    val updatedAt: Long,
)

/** A laptop-helper job (3b only starts writing it). `frameIdsJson` lists the photos it carries, so Forget can void it. */
@Entity(tableName = "canvas_helper_job", indices = [Index("state")])
data class CanvasHelperJobEntity(
    @PrimaryKey val jobId: String,
    val capability: String,
    val state: String,
    val leaseOwner: String? = null,
    val leaseUntilMs: Long? = null,
    @ColumnInfo(defaultValue = "'[]'") val frameIdsJson: String = "[]",
    val createdAt: Long,
    val updatedAt: Long,
)
