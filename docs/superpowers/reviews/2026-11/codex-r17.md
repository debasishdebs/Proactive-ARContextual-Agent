**NOT READY — 3 P1 findings.** Reviewed `7ef00f8f`, its diff from `17140f2c`, and the regression range from `815c1552`.

1. **P1 — Singleton fallback still links independent memories to unrelated photos.** [Executors.kt:133](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/Executors.kt:133), [ExtractSave.kt:138](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/ExtractSave.kt:138).

   **Repro:** run a LIVE `Photo → Vision(batch=2)` pipeline over unrelated A/B outside each other’s Forget radius. Return invalid batch output, causing successful singleton retries. There is no upstream JSON; A’s successful request contains only A’s image and metadata. Nevertheless, the executor passes `{A,B}` as context provenance into both retries, and `callGroups` records `{A,B}` for each. A’s saved memory acquires `CARRIED B`. Forget B deletes A’s independent memory.

   **Introduced after `815c1552`:** CARRIED persistence in `17140f2c`; still present in `7ef00f8f`.

2. **P1 — Canvas save mixes provenance from unrelated branches.** [CanvasRunSave.kt:183](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunSave.kt:183), [CanvasRunSave.kt:215](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunSave.kt:215).

   `load()` unions lineage from **every node row sharing a frame ID**, then assigns that union to the saved fact.

   **Repro:** create an experiment with nodes ordered `[photos, soloVision(batch=1), memory.write, sideVision(batch=2)]`; connect `photos→soloVision→memory.write` and `photos→sideVision`. This passes experiment validation. The save branch produces an independent A fact before the unrelated side branch consumes A+B. Saving nevertheless gives that fact `CARRIED B`. Forget B deletes it, although the side branch never produced or supplied its content.

   **Introduced after `815c1552`:** `17140f2c`; the new per-memory callback retains this mixing.

3. **P1 — Forget resummarization still loses durable photo provenance.** [ResummarizeWorker.kt:154](/Users/<user>/Desktop/rokid/pca-v098/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ResummarizeWorker.kt:154).

   Rebuilt summaries receive `memory_source` edges but inherit neither ordinary nor CARRIED photo provenance.

   **Repro:** SHORT memory MA cites A and carries private detail from X; a longer-lived summary S derives from MA/MB. Forget **fact MB**, invalidating S. The resummarizer rebuilds R from MA without photo provenance. Retention subsequently expires MA and removes `R→MA`. Forget X now misses R, which remains OK and searchable with X-derived text.

   **Predates `815c1552`.** The ordinary-A variant reproduces with that baseline’s DAO SQL and unchanged resummarizer writer.

The five r16 fixes assess as follows: **(1) incomplete**, because of findings 1–2; **(2) fixed for queued reprocessing**; **(3) fixed**—summary, edges and inherited provenance share the transaction, with generation/source/tombstone checks; **(4) fixed at the audited readers**; **(5) fixed**—empty `frames()`/`memories()` remain stored-content declarations, and TestBed supplies liveness.

The query audit matches the claimed CARRIED filtering. Each named query below was checked:

| Query or operation | CARRIED handling |
|---|---|
| `WipeDao.frameSources` | Deletes all roles |
| `FrameSourceDao.deleteForMemory`, `deleteForMemories`, `deleteForFrames` | Deletes all roles |
| `ForgetDao.deleteFrameSourcesFor`, `deleteFrameSourcesOfFrames` | Deletes all roles |
| `FrameSourceDao.forMemory` | Includes all roles for provenance inheritance |
| `ForgetDao.carriedMemoryIdsOfFrame` | CARRIED only, excluding ordinary citations |
| `ForgetDao.memoryIdsOfFrame`, `sourceFramesOf`, `remainingRefs` | Excludes CARRIED |
| `MomentDao.memoryIdsOfFrames` | Excludes CARRIED |
| `ForgetDao.orphanTombstonedFramePaths`, `pruneTombstones`, `orphanFramesBefore` | Excludes CARRIED |
| `ForgetDao.citedCount`, `strandedPhotos` / `CITED_NOT_DESCRIBED` | Excludes CARRIED |
| `EmbeddingDao.deletedPhotoReducedCount` | Excludes CARRIED; this is the relevant method called “deletedPhotoCount” in the commit audit |
| `FrameStateDao.describedCount`, `resetForReprocess` | Excludes CARRIED |
| `FrameAdoptionDao.awaiting`, `extracted` | Excludes CARRIED |
| `CanvasDao.momentMemories`, `momentVisibleTexts`, `liveMemories` | Excludes CARRIED |
| `CanvasDao.recentFrames`, `recentFramesBetween` | Excludes CARRIED from counts |
| `FrameSourceDao.memoriesForFrame` | Excludes CARRIED |
| `MemoryOrphan.SQL`, consumed by export `memoryPage` and purge `orphanMemoryIds` | Excludes CARRIED |
| `describedAmong`; description/grace predicates; `queueForDescribe`; `noteUncitedDescribed` | FACT/CLUSTER_REP filters exclude CARRIED |
| `NOT_A_REP` | CLUSTER_REP only |
| `PhotoSearchDeps` | Table invalidation signal, not a content reader |

CARRIED invalidation itself correctly preserves cited photos: no photo locks, tombstones or deletion scheduling. It removes memory vectors in every space; descendant closure terminates cycles. No additional orphan embedding/index path or permanent deadlock was found by static tracing. The r12–r15 description, grace, retry-parking and dispatch protections remain present, subject to the findings above.

Verification used static tracing and in-memory SQLite with repository SQL and the v15 schema, including baseline reproduction. Room **15**, protocol **1**, and trace schema **1** are unchanged. **No Gradle run or workspace modifications.**