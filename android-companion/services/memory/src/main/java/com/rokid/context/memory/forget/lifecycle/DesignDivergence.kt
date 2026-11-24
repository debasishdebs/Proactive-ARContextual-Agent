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
            "Flag OFF (v1): erased with its descendants invalidated; the photos it cites are never locked or deleted; no re-describe. RULED 2026-11-17 (F1) and built for the flag ON only: the carried caption and its descendants are invalidated at once, and each surviving photo is queued to be described ALONE through the ordinary extraction pipeline (provenance, pause-zone and power gates unchanged; the photo is preserved); pinned by the `on` expectation of the same golden and `ForgetStage2Test`.", "forgetPhoto"),
        Entry("FACT_FORGET_LOCKS_CITED_PHOTO", "A fact Forget removes the fact only; its photo stays extractable. RULED 2026-11-17 (F2): v1 stays for this field build; changing it needs the real user.",
            "Every photo the fact cites is tombstoned (never extracted again) and, when no surviving memory cites it, its image vector and file go; the photo row stays.", "forgetFact"),
        Entry("SCENE_REBUILD_VS_DROP", "A Scene that loses some Moments is REBUILT.",
            "Flag OFF (v1): a Scene with surviving Moments shrinks (its derived fields hidden, audit rows written); a Scene with none left, or named by a scoped Forget, is dropped whole. RULED 2026-11-17 (F3) and built for the flag ON only: the surviving Scene is REBUILT from its surviving Moments and its old derived text is ERASED at once (the derive pass writes it again); an empty or named Scene is DROPPED as before; pinned by `ForgetStage2Test` (the golden worlds hold no Scene field).", "momentScopeSpans"),
        Entry("ASK_NUDGE_REDACTED_IN_PLACE", "A1 / N1: an answer is rebuilt by re-answering from the survivors; a hit nudge is deleted.",
            "Flag OFF (v1): asks and nudges that cite or depend on a forgotten, invalidated or photo-cited id are redacted in place (the question stays); their proposals are expired and scrubbed. RULED 2026-11-17 and built in M5 for the flag ON only: no automatic re-answer (the answer becomes \"Answered from a forgotten fact\" with an Ask again button, only CITED sources and history items count, a hit nudge is erased); pinned by the `on` expectation of the same golden.", "forgetFact"),
        Entry("MULTI_PHOTO_CASCADE", "A memory that cites several photos is rebuilt from the surviving ones.",
            "Flag OFF (v1): forgetting one photo of a multi-photo memory deletes the memory and so locks and cascades to its OTHER photo too. RULED 2026-11-17 (F5) and built for the flag ON only: the mixed memory is erased (its text goes first), the other original photo is never locked, deleted or tombstoned and is queued to be described alone; its stored outputs are scrubbed; pinned by the `on` expectation of the same golden. A Forget of the FACT itself keeps v1 (F2, needs the real user).", "forgetFarPhotoCitedByAMultiPhotoFact"),
        Entry("REPLAY_SWEEP_INVALIDATION", "Replays and sweeps are RunResult nodes of the plan.",
            "Flag OFF (v1): replays and sweeps are invalidated by the range Forget (`invalidateBySources`) and are not planned or hashed; helper jobs are voided and scrubbed; `moment_sensor` rows of a range are purged by time. RULED 2026-11-17 (F6) and built for the flag ON only: the plan names them (RUN_RESULT `replay:` `sweep:` `job:`, MOMENT `sensor:`) and the scoped plan hash carries them with a plan version, so every v1 confirmation token is invalid and a fresh preview and acknowledgement are required; preview == delete (`ForgetStage2Test`).", "rangeForget"),
        Entry("PLACE_HISTORY_NOT_PLANNED", "The planner enumerates every deletable object, so a place card history row would be a node of the plan and its preview.",
            "The place card history (`place_card_log`) is a leaf the planner does not enumerate (it is not shown in a Forget preview or a confirmation hash); the user-Forget step code deletes it in the same transaction by photo and by time, photo expiry only sets photoGone, and pin removal deletes it before the pin store is written (v18 WP3). Pinned with the flag OFF and ON by `placeHistoryRows` (golden) and `PlaceHistoryForgetTest`.", "placeHistoryRows"),
        Entry("MOMENT_LOCATION_NOT_PLANNED", "The planner enumerates every deletable object, so a Moment location would be a node of the plan and its preview.",
            "A Moment location is a leaf the planner does not enumerate (its rows carry no text and no photo data, and never appear in a preview or a count); the user-Forget step code deletes it in the same transaction by photo or mark anchor, by second and by time (v18 WP2). Photo expiry, the sweep, the invalid-Moment purge and the Moment rebuilds keep it. Pinned with the flag OFF and ON by `momentLocationRows` (golden) and `MomentLocationForgetTest`.", "momentLocationRows"),
    )
}
