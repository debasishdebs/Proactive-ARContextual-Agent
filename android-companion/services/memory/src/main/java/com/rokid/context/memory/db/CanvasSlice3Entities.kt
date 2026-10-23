package com.rokid.context.memory.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Experiment canvas slice 3a (Room v7, migration A, additive). Nine new tables; the seven new `canvas_publish` columns are on
 * [CanvasPublishEntity]. Data-safety rule: every table here is either WIPED by Delete all or KEPT BY DESIGN (it holds no user data), and
 * every table that can hold derived data is invalidated by the label/trace/frame deletion paths (Task 34). WipeDaoTest pins the classification.
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
@Entity(tableName = "canvas_replay", indices = [Index("sweepId"), Index("createdAt"), Index("configHash")])
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
    /** The `configHash` of the capture config this replay evaluated (S60: all evidence is keyed by it; gate 3 needs an exact-hash replay). Null for a replay with no single config. */
    val configHash: String? = null,
)

/** A parameter sweep (grid or seeded random) with its split days, results and provenance (same invalidation and job-state rules as a replay). */
@Entity(tableName = "canvas_sweep", indices = [Index("createdAt"), Index("configHash")])
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
    /** The `configHash` of the base config the sweep varies from (S60); each point's own hash lives in its result. Null when the sweep has no single base config. */
    val configHash: String? = null,
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

/**
 * The S62 recovery table of a publish group: the last durable transition per group (no user data; kept by Delete all).
 * `component` is `capture_plan` in core (the only component; later ASYNC components add values). `state` is DESIRED, PREPARED, SENT, EFFECTIVE, FAILED,
 * UNKNOWN_REMOTE or REVERTED (terminal per gen). `mode` (SHADOW, TRIAL, LIVE), `phoneInstall` and `sentAtMs` complete the S62 matching tuple
 * (`gen`, `publishId`, `mode`, `configHash`, `phoneInstall`) and the 10-minute UNKNOWN_REMOTE timer.
 */
@Entity(tableName = "canvas_group_state")
data class CanvasGroupStateEntity(
    @PrimaryKey val groupId: String,
    val gen: Long,
    val publishId: Long,
    val configHash: String,
    val component: String,
    val state: String,
    val updatedAt: Long,
    val mode: String? = null,
    val phoneInstall: String? = null,
    val sentAtMs: Long? = null,
)

/**
 * A laptop-helper job (3b, Room v8). `capability` is the legacy v7 column and stays; v8 jobs write the same value as `jobType` (`py_exec`).
 * `frameIdsJson` lists the photos it carries, so Forget can void it. States are the names of `HelperJobState` (core plan section 6): every CORE
 * `py_exec` job is inserted as AWAITING_CONFIRM and leaves it only through the DAO's confirm, decline, expiry, cancel or void.
 *
 * The v8 columns are FROZEN at WP3's merge (no later WP adds one; the Moment slice takes v9/v10): the lease (`leaseGen`, `lastHeartbeatMs`,
 * `deadlineMs`), the immutable payload and its `bundleHash` (written once at insert; only ever nulled afterwards), the result, the confirmation
 * window (`confirmExpiresAtMs`, `confirmedAtMs`) and the fence the job captured BEFORE its inputs loaded (`wipeGen`, `installId`,
 * `deletionCounter`, `frameForgetRev`), re-checked in every transaction that releases the payload, renews the lease or commits the result.
 * `itemCount` and `sourceChars` are what the native confirm card shows (no payload read). The HMAC tag is never stored.
 */
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
    @ColumnInfo(defaultValue = "''") val jobType: String = "",
    val ownerRef: String? = null,
    @ColumnInfo(defaultValue = "0") val sessionGen: Long = 0,
    @ColumnInfo(defaultValue = "0") val leaseGen: Long = 0,
    val lastHeartbeatMs: Long? = null,
    val deadlineMs: Long? = null,
    val bundleHash: String? = null,
    val payloadJson: String? = null,
    val resultJson: String? = null,
    val resultHash: String? = null,
    val confirmExpiresAtMs: Long? = null,
    val confirmedAtMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val wipeGen: Long = 0,
    @ColumnInfo(defaultValue = "''") val installId: String = "",
    @ColumnInfo(defaultValue = "0") val deletionCounter: Long = 0,
    @ColumnInfo(defaultValue = "0") val frameForgetRev: Long = 0,
    @ColumnInfo(defaultValue = "0") val itemCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val sourceChars: Int = 0,
    val failCode: String? = null,
    val voidReason: String? = null,
)

/**
 * A pending instruction for the laptop helper to delete its copies of a job (`jobId` = "*" means every job, Delete all). Opaque ids and a reason code only:
 * no user data, so it SURVIVES Delete all (the laptop copies must still be deleted). Re-sent by the poll until the helper acks it; purged after 30 days.
 */
@Entity(tableName = "canvas_helper_cleanup")
data class CanvasHelperCleanupEntity(
    @PrimaryKey(autoGenerate = true) val cleanupId: Long = 0,
    val jobId: String,
    val reason: String,
    val createdAt: Long,
)
