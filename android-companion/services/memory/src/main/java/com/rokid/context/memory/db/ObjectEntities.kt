package com.rokid.context.memory.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * The audit of one Moment-family object (Moment now; Audio, Sensor and Scene later): one ROW per operation done to it, APPEND-ONLY, CONTENT-FREE (object ids, op names, times and
 * step names only; never text, labels or photo data) and deleted together with its object. `seq` is per object, from 1. `refKind`/`refId` name the part or source an op concerned.
 */
@Entity(tableName = "object_audit", primaryKeys = ["objectKind", "objectId", "seq"], indices = [Index("op"), Index("refKind", "refId")])
data class ObjectAuditEntity(
    val objectKind: String,
    val objectId: String,
    val seq: Int,
    val op: String,
    val atMs: Long,
    val step: String,
    val refKind: String? = null,
    val refId: String? = null,
)

/**
 * A derived field (SUMMARY, CAPTION or EMBEDDING) of ANY Moment-family object, one row per `(objectKind, objectId, field)`. The value of a child object stays on the child. [value] holds
 * the text of a summary or caption, [ref] points at an embedding (no vector here). A row with [staleSince] set is STALE: readers get null for it at once and it is rebuilt later.
 * Deleted with its object, emptied by Delete all.
 */
@Entity(tableName = "object_derived", primaryKeys = ["objectKind", "objectId", "field"], indices = [Index("objectKind", "objectId")])
data class ObjectDerivedEntity(
    val objectKind: String,
    val objectId: String,
    val field: String,
    val value: String? = null,
    val ref: String? = null,
    val builtRev: Int,
    @ColumnInfo(defaultValue = "NULL") val staleSince: Long? = null,
    val atMs: Long,
)
