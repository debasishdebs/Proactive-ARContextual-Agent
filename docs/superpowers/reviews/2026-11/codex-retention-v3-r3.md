VERDICT: NOT READY

Reviewed `499ce1d1` and `0a2612aa`; HEAD verified. No files edited; no Gradle, adb, or tools scripts run.

Paths below are relative to `android-companion/`; **M** = `services/memory/src/main/java/com/rokid/context/memory`, **O** = `services/orchestration/src/main/java/com/rokid/context`.

1. [P1] O/orchestration/PipelineRecords.kt:179 - An automatic retry returning 3 facts after committing 5 deletes facts 3–4 and their provenance; `QueueDrainWorker.kt:271,395` bypass Pause - Preserve committed facts until explicit Forget.
2. [P1] O/orchestration/forget/RetentionWorker.kt:49 - With Pause ON, `FrameSweep.kt:83–89` still deletes leftover JPEGs for tombstones from earlier automatic retention, without distinguishing them from user Forget - Under Pause, retry only deletions affirmatively authorized by user Forget.

The backlog’s P2 designation does not close finding 1: pre-existing automatic data loss remains **P1 under your stated severity definition**.

| Earlier r2 finding | Status | Trace |
|---|---|---|
| 1 Unsafe marker backfill | CLOSED | `RetentionMigration.kt:18–31` writes no `rx:` marker; selector deleted. `ProvenanceGate.kt:124–128` treats unmarked tombstones as forgotten. |
| 2 Transitive lineage pruning | NOT APPLICABLE | `ExpiredLineage` and its pruning caller deleted; supplied install history excludes legacy `lin:` state. |
| 3 Incomplete lineage traversal/cutoff | NOT APPLICABLE | Reach/consume machinery deleted; no installed lineage to traverse. Live descendants remain available through `ForgetDao.kt:20–24`. |
| 4 Unrelated-photo lineage seeding | CLOSED | Service/executor/planner contain no lineage seeds or `lineageFrames`; survivor protection remains in executor/planner. |
| 5 Marker range truncated at 20,000 | CLOSED | `ExpiryMarks.kt:64–73` pages until a short page; `CanvasSlice3Dao.kt:38` orders by key. |
| 6 Retry deletes trailing facts | STILL OPEN | Executable code unchanged; `0a2612aa` only documents it. Confirmed present in `18c1b76a`. |

No additional removal regression found. Operational `lin:` references remain only in migration purge and Delete all; unrelated CARRIED, batch, and history provenance remains intact. `ForgetDao.kt:39–40` retains an unused lineage-era accessor/comment.

Cascade early returns are restored to `found.isEmpty() && extraCited.isEmpty()` in service `:190`, executor `:220`, planner `:95`; photo citations still prevent premature return. Existing user Forget assertions remain intact, including later Forget and unrelated-photo protection; only two obsolete synthetic-lineage tests were removed.

Paging uses matching ordered half-open SQL bounds, an inclusive millisecond interval, and `lastKey + NUL` for strict advancement. An in-memory SQLite model passed 20,150 keys, shared timestamps, boundary/prefix exclusion, and five page sizes. Android/Kotlin tests were not executed.

Migration is restart-safe: each purge chunk commits transactionally, interrupted runs resume from remaining keys, and DONE is written last. Subsequent runs do nothing; no marker backfill occurs.

**Shipping answer: yes, automatic deletion remains possible with Pause ON.** Pause gates quota, demotion, and ordinary expiry, but neither the extraction retry deletion nor the pre-gate file sweep. The candidate therefore cannot honestly promise “deletes nothing at all.”