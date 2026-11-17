package com.rokid.context.memory.forget.lifecycle

/**
 * DESIGN_DIVERGENCE (Forget lifecycle M4, 2026-11-17): the places where the design `2026-11-16-forget-lifecycle-design.md` would delete something DIFFERENT from v1. The user has not ruled on them, so the
 * [ForgetExecutor] implements EXACTLY v1 (the planner mirrors v1 and `GoldenDeleteSetTest` is the parity gate, run with the flag OFF and ON). Each entry names the golden test that pins the v1 behaviour: a later ruling
 * changes that golden and the family code together. `DesignDivergenceTest` fails when a listed golden does not exist. Nothing here is read at runtime.
 */
object DesignDivergence {
    data class Entry(val id: String, val design: String, val v1: String, val golden: String)

    val ALL: List<Entry> = listOf(
        Entry("CARRIED_BATCH_MATE", "B1 / design 3: a memory reached only by a CARRIED edge is REBUILT (re-described from its own live photo).",
            "Invalidated with its descendants and removed from feeds; the photos it cites are never locked or deleted; no re-describe.", "forgetPhoto"),
        Entry("FACT_FORGET_LOCKS_CITED_PHOTO", "A fact Forget removes the fact only; its photo stays extractable.",
            "Every photo the fact cites is tombstoned (never extracted again) and, when no surviving memory cites it, its image vector and file go; the photo row stays.", "forgetFact"),
        Entry("SCENE_REBUILD_VS_DROP", "A Scene that loses some Moments is REBUILT.",
            "A Scene with surviving Moments shrinks (its derived fields hidden, audit rows written); a Scene with none left is dropped whole.", "momentScopeSpans"),
        Entry("ASK_NUDGE_REDACTED_IN_PLACE", "A1 / N1: an answer is rebuilt by re-answering from the survivors; a hit nudge is deleted.",
            "Asks and nudges that cite or depend on a forgotten, invalidated or photo-cited id are redacted in place (the question stays); their proposals are expired and scrubbed.", "forgetFact"),
        Entry("MULTI_PHOTO_CASCADE", "A memory that cites several photos is rebuilt from the surviving ones.",
            "Forgetting one photo of a multi-photo memory deletes the memory and so locks and cascades to its OTHER photo too.", "forgetFarPhotoCitedByAMultiPhotoFact"),
        Entry("REPLAY_SWEEP_INVALIDATION", "Replays and sweeps are RunResult nodes of the plan.",
            "Replays and sweeps are invalidated by the range Forget (`invalidateBySources`) and are not planned or hashed; helper jobs are voided and scrubbed; `moment_sensor` rows of a range are purged by time.", "rangeForget"),
    )
}
