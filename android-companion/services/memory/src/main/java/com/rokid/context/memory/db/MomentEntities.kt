package com.rokid.context.memory.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Moment model phase 1 (Room v9, additive). `moment` and `moment_part` are a PROJECTION over the authoritative stores (frames, memories, marks): the builder can drop and
 * rebuild them and Forget never trusts them (it discovers sources from the authoritative stores and unions the projection). `moment_tombstone` and `cleanup_outbox` are the
 * deletion protection. `moment_trace_dep` is reserved and empty until a trace writer exists. Names only here: no photo bytes, no text, no paths of photos.
 */

/** One stored Moment: [contentRev] and [contentHash] are exactly Moment-lite's (the derived read model); [buildRev] is the builder code version. */
@Entity(tableName = "moment")
data class MomentEntity(
    @PrimaryKey val momentId: String,
    val buildRev: Int,
    val contentRev: Int,
    val contentHash: String,
    val builtAt: Long,
)

/** Part kinds of a stored Moment. SENSOR is reserved for the sensor phase and is never written here. */
object MomentPartKind {
    const val FRAME = "FRAME"
    const val MEMORY = "MEMORY"
    const val CITE = "CITE"
    const val MARK = "MARK"
    const val SUMMARY = "SUMMARY"
    const val OTHER = "OTHER"
    val ALL = listOf(FRAME, MEMORY, CITE, MARK, SUMMARY, OTHER)
}

/** A membership edge: Moment [momentId] holds [refId] (a frame id, memory id, `frame:<id>` cite, mark label id) as [kind]. [partRev] fingerprints the part's content. */
@Entity(tableName = "moment_part", primaryKeys = ["momentId", "kind", "refId"], indices = [Index("momentId"), Index("kind", "refId")])
data class MomentPartEntity(
    val momentId: String,
    val kind: String,
    val refId: String,
    val partRev: String,
    val partTimeMs: Long,
)

/** A forgotten second. Inserted for every requested Moment id (also when no Moment row exists): the builder refuses to recreate it. Wiped by Delete all (the epoch fence covers that). */
@Entity(tableName = "moment_tombstone")
data class MomentTombstoneEntity(@PrimaryKey val momentId: String, val createdAt: Long)

/** Reserved for trace-derived dependencies; created empty, no writer in phase 1. */
@Entity(tableName = "moment_trace_dep", primaryKeys = ["momentId", "traceId", "revision"], indices = [Index("traceId")])
data class MomentTraceDepEntity(val momentId: String, val traceId: String, val revision: Long)

/**
 * Durable, idempotent cleanup of copies the cascade cannot delete itself (phone files, glasses frames and queued marks, laptop copies). Inserted in the SAME transaction as
 * the cascade; a drainer (later) does the I/O and acks. (target, payloadJson) is unique so a repeated Forget inserts nothing new. [payloadJson] holds ids, paths and ranges
 * only. KEPT by Delete all until acknowledged.
 */
@Entity(tableName = "cleanup_outbox", indices = [Index(value = ["target", "payloadJson"], unique = true), Index("ackedAt")])
data class CleanupOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val target: String,
    val payloadJson: String,
    val createdAt: Long,
    val ackedAt: Long? = null,
)

/** Outbox targets. */
object CleanupTarget {
    const val PHONE_FILE = "PHONE_FILE"
    const val GLASSES_FRAME = "GLASSES_FRAME"
    /** A phone-clock range: queued marks and traces on the glasses inside it must go. */
    const val GLASSES_RANGE = "GLASSES_RANGE"
    const val GLASSES_TRACE = "GLASSES_TRACE"
    /** Delete all: every device-side copy. */
    const val ALL = "ALL"
}

/** Payload builders: fixed key order and minimal escaping, because the payload text is the idempotence key of the outbox row. */
object CleanupPayload {
    fun one(key: String, value: String) = "{\"$key\":${str(value)}}"
    fun range(fromMs: Long, toMs: Long) = "{\"fromMs\":$fromMs,\"toMs\":$toMs}"
    private fun str(v: String): String = buildString {
        append('"')
        v.forEach { c -> when { c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c < ' ' -> append("\\u%04x".format(c.code)); else -> append(c) } }
        append('"')
    }
}
