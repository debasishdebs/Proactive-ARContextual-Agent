package com.rokid.context.memory.db

/**
 * The ONE SQL definition of "derived text that may leave Room" (alias `m` = `memory`): valid and visible (OK, so not forgotten/INVALIDATED nor hidden), not an orphan of its photo ([MemoryOrphan]), and, when derived,
 * with at least one source memory that is not forgotten (stale-hidden otherwise). The export reads it; the Kotlin side of the same rule is `ProvenanceGate` (orchestration). Moved verbatim from `ExportDao`.
 */
object ProvenanceSql {
    const val LIVE_DERIVED: String =
        "m.reviewState = 'OK' " +
            // not an orphan: photo retention deletes the `frame` row but keeps `frame_meta` (its memories are still exported); a Forget-removed frame has neither (one definition, shared with the one-time purge)
            "AND NOT (${MemoryOrphan.SQL}) " +
            // a derived memory needs at least one source memory that is not forgotten (stale-hidden otherwise)
            "AND (m.derived = 0 OR NOT EXISTS (SELECT 1 FROM memory_source s WHERE s.memoryId = m.id) OR EXISTS (SELECT 1 FROM memory_source s JOIN memory m2 ON m2.id = s.sourceMemoryId " +
            "WHERE s.memoryId = m.id AND m2.reviewState != 'INVALIDATED'))"
}
