package com.rokid.context.memory.moment

import com.rokid.context.memory.db.ObjectAuditEntity
import com.rokid.context.memory.db.ObjectDao
import com.rokid.context.memory.db.ObjectDerivedEntity

/**
 * The audit and the derived fields of Moment-family objects, behind one small API (see [MomentObject]).
 *
 * **Audit.** One ROW per operation done to an object ([AuditOp]), append-only, content-free: object kind and id, an op name, a time, a step name and at most one reference
 * (`refKind`, `refId`: the part, source or field the op concerned). Only names from the closed lists [AuditOp] and [AuditStep] are accepted and references are short ids, so text,
 * labels and photo data cannot get in. The rows go with their object: [ObjectDao.removeObjects] (the Forget cascade, a removed Moment) and Delete all. Because rows go with the object,
 * an object that is deleted wholesale keeps no FORGOTTEN or OUTBOX_QUEUED row; those are for objects that SURVIVE a forget of one of their parts (a Scene later).
 *
 * **Stale rule.** A part that is added, removed or forgotten changes what the object's derived fields (SUMMARY, CAPTION, EMBEDDING) were built from, so [partAdded], [partRemoved] and
 * [forgotten] call [markStale]. A STALE field is HIDDEN from readers immediately ([readDerived] returns null for it, in the same transaction) and is rebuilt later (a derive port
 * writes it again with [writeDerived], which clears `staleSince`). Nothing is deleted by marking stale; the value stays until it is rewritten or its object goes. Call every
 * function inside the transaction of the operation it records, so the audit can never disagree with the object.
 */
class ObjectAudit(private val dao: ObjectDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun record(obj: MomentObject, op: String, step: String, refKind: String? = null, refId: String? = null): Int {
        require(op in AuditOp.ALL) { "unknown audit op" }
        require(step in AuditStep.ALL) { "unknown audit step" }
        require(refKind == null || SHORT_ID.matches(refKind)) { "refKind" }
        require(refId == null || SHORT_ID.matches(refId)) { "refId" }
        return dao.appendAudit(obj.kind, obj.id, op, clock(), step, refKind, refId)
    }

    suspend fun partAdded(obj: MomentObject, step: String, refKind: String, refId: String) { record(obj, AuditOp.PART_ADDED, step, refKind, refId); markStale(obj.kind, obj.id, step, refKind, refId) }
    suspend fun partRemoved(obj: MomentObject, step: String, refKind: String, refId: String) { record(obj, AuditOp.PART_REMOVED, step, refKind, refId); markStale(obj.kind, obj.id, step, refKind, refId) }

    /** A part or source of a SURVIVING object was forgotten: recorded, and the derived fields built from it are hidden. */
    suspend fun forgotten(obj: MomentObject, refKind: String? = null, refId: String? = null) { record(obj, AuditOp.FORGOTTEN, AuditStep.FORGET, refKind, refId); markStale(obj.kind, obj.id, AuditStep.FORGET, refKind, refId) }

    /** A durable cleanup row was queued because of this object (the target NAME only, e.g. GLASSES_FRAME). */
    suspend fun outboxQueued(obj: MomentObject, target: String) { record(obj, AuditOp.OUTBOX_QUEUED, AuditStep.FORGET, "OUTBOX", target) }

    /** Hides every fresh derived field of the object now and records it. Returns the number of fields hidden. */
    suspend fun markStale(objectKind: String, objectId: String, step: String = AuditStep.STALE, refKind: String? = null, refId: String? = null): Int {
        require(step in AuditStep.ALL) { "unknown audit step" }
        require(refKind == null || SHORT_ID.matches(refKind)) { "refKind" }
        require(refId == null || SHORT_ID.matches(refId)) { "refId" }
        return dao.markStaleTx(objectKind, objectId, clock(), step, refKind, refId)
    }

    /** Writes (or rewrites) a derived field; it is fresh from now on. */
    suspend fun writeDerived(obj: MomentObject, field: String, value: String?, ref: String?, builtRev: Int) {
        require(field in DerivedField.ALL) { "unknown derived field" }
        dao.writeDerivedTx(ObjectDerivedEntity(obj.kind, obj.id, field, value, ref, builtRev, null, clock()), AuditStep.DERIVE)
    }

    /** The derived field, or null when it is absent or STALE (hidden). */
    suspend fun readDerived(obj: MomentObject, field: String): DerivedValue? =
        dao.derivedRow(obj.kind, obj.id, field)?.takeIf { it.staleSince == null }?.let { DerivedValue(it.field, it.value, it.ref, it.builtRev, null, it.atMs) }

    /** When the field went stale, or null when it is fresh or absent. */
    suspend fun staleSince(obj: MomentObject, field: String): Long? = dao.derivedRow(obj.kind, obj.id, field)?.staleSince

    suspend fun audit(obj: MomentObject): List<ObjectAuditEntity> = dao.audit(obj.kind, obj.id)
    suspend fun auditByOp(op: String): List<ObjectAuditEntity> = dao.auditByOp(op)

    /** Why a rebuild is needed: the latest build, part or forget operation of the object (names and ids only). */
    suspend fun reason(obj: MomentObject): RebuildReason =
        dao.audit(obj.kind, obj.id).lastOrNull { it.op in REASON_OPS }?.let { RebuildReason(it.op, it.refKind, it.refId) } ?: RebuildReason(null, null, null)

    private companion object {
        val SHORT_ID = Regex("[A-Za-z0-9:_.\\-@]{1,128}")
        val REASON_OPS = setOf(AuditOp.BUILT, AuditOp.REBUILT, AuditOp.PART_ADDED, AuditOp.PART_REMOVED, AuditOp.FORGOTTEN)
    }
}
