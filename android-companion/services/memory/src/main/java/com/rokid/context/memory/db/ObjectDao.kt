package com.rokid.context.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Audit rows and derived fields of Moment-family objects (rows, one table each; see [ObjectAuditEntity], [ObjectDerivedEntity]). Every call that changes rows belongs INSIDE the
 * transaction of the operation it records, so the audit can never disagree with the object. Callers chunk id lists at 500.
 */
@Dao
abstract class ObjectDao {
    // --- audit: append-only ---
    @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM object_audit WHERE objectKind = :kind AND objectId = :id") protected abstract suspend fun nextSeq(kind: String, id: String): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertAudit(row: ObjectAuditEntity)

    /** Appends one row for the object and returns its `seq` (from 1, per object). Content-free by construction: only ids, an op name, a time and a step name. */
    @Transaction open suspend fun appendAudit(kind: String, id: String, op: String, atMs: Long, step: String, refKind: String? = null, refId: String? = null): Int {
        val seq = nextSeq(kind, id)
        insertAudit(ObjectAuditEntity(kind, id, seq, op, atMs, step, refKind, refId))
        return seq
    }

    @Query("SELECT * FROM object_audit WHERE objectKind = :kind AND objectId = :id ORDER BY seq") abstract suspend fun audit(kind: String, id: String): List<ObjectAuditEntity>
    @Query("SELECT * FROM object_audit WHERE op = :op ORDER BY objectKind, objectId, seq") abstract suspend fun auditByOp(op: String): List<ObjectAuditEntity>
    @Query("SELECT * FROM object_audit WHERE objectKind = :kind AND objectId = :id AND op = :op ORDER BY seq") abstract suspend fun auditOf(kind: String, id: String, op: String): List<ObjectAuditEntity>
    @Query("SELECT COUNT(*) FROM object_audit") abstract suspend fun auditCount(): Int

    // --- derived fields ---
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertDerived(row: ObjectDerivedEntity)
    @Query("SELECT * FROM object_derived WHERE objectKind = :kind AND objectId = :id AND field = :field") abstract suspend fun derivedRow(kind: String, id: String, field: String): ObjectDerivedEntity?
    @Query("SELECT * FROM object_derived WHERE objectKind = :kind AND objectId = :id ORDER BY field") abstract suspend fun derivedRows(kind: String, id: String): List<ObjectDerivedEntity>
    @Query("SELECT COUNT(*) FROM object_derived") abstract suspend fun derivedCount(): Int
    /** Marks every still-fresh derived field of the object stale (the first stale time is kept). Returns the fields marked. */
    @Query("SELECT field FROM object_derived WHERE objectKind = :kind AND objectId = :id AND staleSince IS NULL ORDER BY field") abstract suspend fun freshFields(kind: String, id: String): List<String>
    @Query("UPDATE object_derived SET staleSince = :now WHERE objectKind = :kind AND objectId = :id AND staleSince IS NULL") abstract suspend fun staleAll(kind: String, id: String, now: Long): Int

    /**
     * Hides every fresh derived field of the object NOW (readers get null from this transaction on) and records it: one INVALIDATED row for the object (with the part or source that
     * caused it), and per field one DERIVED_STALE and one DERIVED_HIDDEN row (the field name only). Returns the fields hidden; 0 (and no row) when there was nothing fresh.
     */
    @Transaction open suspend fun markStaleTx(kind: String, id: String, now: Long, step: String, refKind: String?, refId: String?): Int {
        val fields = freshFields(kind, id)
        if (fields.isEmpty()) return 0
        staleAll(kind, id, now)
        appendAudit(kind, id, "INVALIDATED", now, step, refKind, refId)
        fields.forEach { f -> appendAudit(kind, id, "DERIVED_STALE", now, step, "FIELD", f); appendAudit(kind, id, "DERIVED_HIDDEN", now, step, "FIELD", f) }
        return fields.size
    }

    /** Writes a fresh derived field (clears `staleSince`) and records DERIVED_BUILT with the field name only. */
    @Transaction open suspend fun writeDerivedTx(row: ObjectDerivedEntity, step: String) {
        upsertDerived(row)
        appendAudit(row.objectKind, row.objectId, "DERIVED_BUILT", row.atMs, step, "FIELD", row.field)
    }

    // --- delete with the object ---
    @Query("DELETE FROM object_audit WHERE objectKind = :kind AND objectId IN (:ids)") protected abstract suspend fun deleteAudit(kind: String, ids: List<String>)
    @Query("DELETE FROM object_derived WHERE objectKind = :kind AND objectId IN (:ids)") protected abstract suspend fun deleteDerived(kind: String, ids: List<String>)

    /** Removes the audit rows and the derived fields of these objects (the object itself was deleted in the same transaction). */
    @Transaction open suspend fun removeObjects(kind: String, ids: Collection<String>) = ids.toList().chunked(500).forEach { deleteAudit(kind, it); deleteDerived(kind, it) }
}
