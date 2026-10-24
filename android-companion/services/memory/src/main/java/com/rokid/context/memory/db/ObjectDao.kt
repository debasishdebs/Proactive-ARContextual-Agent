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

    // --- delete with the object ---
    @Query("DELETE FROM object_audit WHERE objectKind = :kind AND objectId IN (:ids)") protected abstract suspend fun deleteAudit(kind: String, ids: List<String>)
    @Query("DELETE FROM object_derived WHERE objectKind = :kind AND objectId IN (:ids)") protected abstract suspend fun deleteDerived(kind: String, ids: List<String>)

    /** Removes the audit rows and the derived fields of these objects (the object itself was deleted in the same transaction). */
    @Transaction open suspend fun removeObjects(kind: String, ids: Collection<String>) = ids.toList().chunked(500).forEach { deleteAudit(kind, it); deleteDerived(kind, it) }
}
