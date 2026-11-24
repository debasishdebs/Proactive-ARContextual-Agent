package com.rokid.context.memory.db

/**
 * Retention v3 WP3: the one definition of a DEMOTED fact, as SQL over a `memory` row (alias `memory`) and the named parameter `:recallSince`.
 *
 * A fact is demoted when ALL hold: tier is SHORT, it is older than the Quick recall window (`createdAt < :recallSince`), an active embedding space exists, and it has NO text
 * vector in that space. The vector is the marker: the retention agent (WP1) deletes it at demotion and the backfill never restores it, so there is no per-row flag, and a fact
 * whose vector has not been removed yet (retention not run, Pause ON) is NOT demoted and behaves exactly as before. With no active space nothing is demoted (lexical-only use).
 * `:recallSince = Long.MIN_VALUE` means "no recall window": nothing is demoted.
 */
object Demotion {
    // The newest ACTIVE/STALE space (EmbeddingDao.current), written with MAX instead of ORDER BY ... LIMIT 1 so the plan builds no temp sort (QueryPlanTest).
    private const val ACTIVE_SPACE = "(SELECT s1.id FROM embedding_space s1 WHERE s1.state IN ('ACTIVE','STALE') AND s1.createdAt = " +
        "(SELECT MAX(s2.createdAt) FROM embedding_space s2 WHERE s2.state IN ('ACTIVE','STALE')) LIMIT 1)"
    const val DEMOTED_SQL = "(memory.tier = 'SHORT' AND memory.createdAt < :recallSince AND $ACTIVE_SPACE IS NOT NULL " +
        "AND NOT EXISTS (SELECT 1 FROM memory_vector dv WHERE dv.memoryId = memory.id AND dv.spaceId = $ACTIVE_SPACE))"
    const val NOT_DEMOTED_SQL = "NOT $DEMOTED_SQL"

    /** [recallSince] for "no window": the predicate is false for every row. */
    const val NO_WINDOW = Long.MIN_VALUE
}
