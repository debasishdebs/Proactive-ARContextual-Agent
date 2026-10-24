package com.rokid.context.memory.db

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The shared core of the audit append and the stale rule (see `ObjectAudit`), as a base class so that EVERY DAO that changes a source of a Moment (marks, memories) can hide the
 * derived fields of the Moments holding it INSIDE its own transaction, through the same code that writes the audit rows. None of these methods opens a transaction itself: the
 * caller's `@Transaction` method is the one.
 */
abstract class DerivedStaleDao {
    @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM object_audit WHERE objectKind = :kind AND objectId = :id") abstract suspend fun nextAuditSeq(kind: String, id: String): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertAuditRow(row: ObjectAuditEntity)
    @Query("SELECT field FROM object_derived WHERE objectKind = :kind AND objectId = :id AND staleSince IS NULL ORDER BY field") abstract suspend fun freshFields(kind: String, id: String): List<String>
    @Query("UPDATE object_derived SET staleSince = :now WHERE objectKind = :kind AND objectId = :id AND staleSince IS NULL") abstract suspend fun staleAll(kind: String, id: String, now: Long): Int
    @Query("SELECT DISTINCT momentId FROM moment_part WHERE kind IN (:kinds) AND refId IN (:refIds)") abstract suspend fun momentIdsHoldingParts(kinds: List<String>, refIds: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM moment WHERE momentId = :id") abstract suspend fun momentExists(id: String): Int
    @Query("SELECT COUNT(*) FROM object_audit WHERE objectKind = :kind AND objectId = :id") abstract suspend fun auditRowCount(kind: String, id: String): Int
    /** The revision of an object's INPUTS: the latest audit seq that is not a derived-field row (a build, part, forget or source-invalidation op). 0 = none. */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM object_audit WHERE objectKind = :kind AND objectId = :id AND op NOT IN ('DERIVED_BUILT', 'DERIVED_STALE', 'DERIVED_HIDDEN', 'OUTBOX_QUEUED')")
    abstract suspend fun inputSeq(kind: String, id: String): Int

    suspend fun appendAuditRow(kind: String, id: String, op: String, atMs: Long, step: String, refKind: String? = null, refId: String? = null): Int {
        val seq = nextAuditSeq(kind, id)
        insertAuditRow(ObjectAuditEntity(kind, id, seq, op, atMs, step, refKind, refId))
        return seq
    }

    /** Hides every fresh derived field of the object NOW and writes INVALIDATED plus DERIVED_STALE and DERIVED_HIDDEN per field. Returns the fields hidden (0 and no row when nothing was fresh). */
    suspend fun markStaleCore(kind: String, id: String, now: Long, step: String, refKind: String?, refId: String?, force: Boolean = false): Int {
        val fields = freshFields(kind, id)
        // [force]: a SOURCE changed. The INVALIDATED row is written even when no field is fresh, because it is the revision a derive that started earlier is checked against.
        if (fields.isEmpty()) { if (force) appendAuditRow(kind, id, "INVALIDATED", now, step, refKind, refId); return 0 }
        staleAll(kind, id, now)
        appendAuditRow(kind, id, "INVALIDATED", now, step, refKind, refId)
        fields.forEach { f -> appendAuditRow(kind, id, "DERIVED_STALE", now, step, "FIELD", f); appendAuditRow(kind, id, "DERIVED_HIDDEN", now, step, "FIELD", f) }
        return fields.size
    }

    /** A SOURCE (a mark, a memory) that Moments hold as a part was removed or changed: hides the derived fields of every Moment holding it. Call inside the source's transaction. */
    suspend fun staleMomentsHolding(partKinds: List<String>, refIds: Collection<String>, step: String = "stale", now: Long = System.currentTimeMillis()): Int {
        if (refIds.isEmpty()) return 0
        val ids = refIds.toList().chunked(500).flatMap { momentIdsHoldingParts(partKinds, it) }.distinct()
        val single = refIds.singleOrNull()?.takeIf { partKinds.size == 1 }
        return ids.sumOf { markStaleCore("MOMENT", it, now, step, if (single != null) partKinds.single() else null, single, force = true) }
    }
}
