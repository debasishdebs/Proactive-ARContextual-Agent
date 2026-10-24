package com.rokid.context.memory.moment

/**
 * The base concept of the Moment family (Moment now; Audio, Sensor and Scene later): every such object has a [kind], an [id] unique within its kind and a [buildRev] (the builder
 * code version that produced it). Everything generic (the audit, the derived fields SUMMARY, CAPTION and EMBEDDING, the stale rule, the derive ports) takes a [MomentObject], never a
 * concrete class, so the Audio and Scene objects use the same call sites.
 */
interface MomentObject {
    val kind: String
    val id: String
    val buildRev: Int
}

/** A bare reference to a Moment-family object (what generic code needs; also the fake parent of tests). */
data class ObjectRef(override val kind: String, override val id: String, override val buildRev: Int = 0) : MomentObject

object ObjectKind {
    const val MOMENT = "MOMENT"
    const val AUDIO = "AUDIO"
    const val SENSOR = "SENSOR"
    const val SCENE = "SCENE"
}

/** The operations the audit records. Names only. */
object AuditOp {
    const val BUILT = "BUILT"
    const val REBUILT = "REBUILT"
    const val PART_ADDED = "PART_ADDED"
    const val PART_REMOVED = "PART_REMOVED"
    const val FORGOTTEN = "FORGOTTEN"
    const val INVALIDATED = "INVALIDATED"
    const val OUTBOX_QUEUED = "OUTBOX_QUEUED"
    const val DERIVED_BUILT = "DERIVED_BUILT"
    const val DERIVED_STALE = "DERIVED_STALE"
    const val DERIVED_HIDDEN = "DERIVED_HIDDEN"
    val ALL = setOf(BUILT, REBUILT, PART_ADDED, PART_REMOVED, FORGOTTEN, INVALIDATED, OUTBOX_QUEUED, DERIVED_BUILT, DERIVED_STALE, DERIVED_HIDDEN)
    val DERIVED = setOf(DERIVED_BUILT, DERIVED_STALE, DERIVED_HIDDEN)
}

/** The step (code path) that did an operation. Names only. */
object AuditStep {
    const val BUILD = "build"
    const val FORGET = "forget"
    const val DERIVE = "derive"
    const val STALE = "stale"
    val ALL = setOf(BUILD, FORGET, DERIVE, STALE)
}

/** The derived fields every Moment-family object can carry. */
object DerivedField {
    const val SUMMARY = "SUMMARY"
    const val CAPTION = "CAPTION"
    const val EMBEDDING = "EMBEDDING"
    val ALL = listOf(SUMMARY, CAPTION, EMBEDDING)
}

/** One derived field as a reader sees it: only ever a FRESH one (a stale field is hidden, see [ObjectAudit.readDerived]). */
data class DerivedValue(val field: String, val value: String?, val ref: String?, val builtRev: Int, val staleSince: Long?, val atMs: Long)
