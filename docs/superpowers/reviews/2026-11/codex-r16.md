**NOT READY — 4 P1 findings, 1 P2.** Reviewed `17140f2c` on `feat/v099-watch`, including `815c1552..17140f2c`.

1. **P1 — Run-and-Save links independent memories to every photo in the run.** [CanvasRunSave.kt:122](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunSave.kt:122).

   `b.frames` combines all saved items’ sources; `saveExtraction` then uses that entire set as every memory’s carried provenance.

   **Repro:** run `Vision(batch=1) → LocalCritic → memory.write` over unrelated A/B outside each other’s Forget radius. Save, then Forget B. Both memories are removed; A’s ordinary citation also causes A to be tombstoned and its JPEG scheduled for deletion. Actual SQL reproduced both photos entering the deletion set. Preserve carried sources **per saved memory**.

2. **P1 — Queued reprocessing drops source memories’ carried photos.** [QueueDrainWorker.kt:316](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt:316), [ExtractFollowUp.kt:99](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/ExtractFollowUp.kt:99).

   The queued payload lists ordinary fact citations; the summary persists only those reloaded frames. It never copies the source memories’ `CARRIED` union.

   **Repro:** extract A/B together; memory MA cites A and carries B-derived private detail. Reprocess MA into a longer-lived summary. Retention expires MA, removing its provenance and `memory_source` edge while preserving the summary. Forget B. The summary has only carried A, survives, and remains available to Ask. Reproduced with the repository SQL.

3. **P1 — Queued summary and carried provenance commit separately.** [QueueDrainWorker.kt:311](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/work/QueueDrainWorker.kt:311).

   `appendFact` commits before the `frame_source` insert; that insert has no generation/tombstone recheck.

   **Repro:** summarize source memories SA/SB and photos A/B; pause after `appendFact` returns. Retention expires SB without invalidating the summary. Forget B, then resume. Forget misses the summary, and the late insert attaches already-forgotten B to an **OK** summary containing B’s text. SA keeps it visible. SQL reproduction confirms this state. Both writes must share the fenced transaction.

4. **P1 — Moment readers turn carried provenance into fabricated photo content.** [CanvasDao.kt:203](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/memory/src/main/java/com/rokid/context/memory/db/CanvasDao.kt:203).

   `momentMemories`, `momentVisibleTexts`, and `liveMemories` accept every role. Consequently, `CARRIED` is presented as content belonging to that photo.

   **Repro:** save an A-only “Visited the bank” memory with B as a carried batch mate; B depicts a different event at another time. Resolve/build B’s Moment. The query returns A’s text labelled with `frameId=B`; the resolver places it under B’s timestamp and the builder persists it. Derive can then summarize that false event. The frame API likewise lists it under B’s live memories. SQL reproduced the incorrect association.

5. **P2 — The `NONE` audit misses TestBed NUDGE’s empty-ID path.** [TestBedInputs.kt:52](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/TestBedInputs.kt:52).

   **Repro:** enable Notes context, retain a note, and run the NUDGE test with no retrieved memories/history dependencies. `PayloadSources.memories(emptySet())` returns `NONE`, although `p.vars` contains stored note text; preparation supplies no liveness. This contradicts the claimed complete audit. It needs an explicit stored-content declaration even with empty identities.

The mark-ID recheck and queued explicit-write generation fence are correct. The `memory.write@1` normalization correctly handles runner-stored canonical pipelines.

The r12–r14 qualifying-description, grace-period, and five-attempt parking SQL checks passed. Runtime union/fallback guards remain present; no permanent deadlock was found by static tracing. Room **15**, protocol **1**, and trace schema **1** are unchanged.

Verification used static tracing and in-memory SQLite against the v15 schema and repository queries. **No Gradle run.**