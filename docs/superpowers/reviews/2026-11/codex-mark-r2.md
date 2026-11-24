VERDICT: NOT READY

Remaining findings, present at both `5bbff101` and `6725c88f`:

1. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/marks/MarkRecorder.kt:217 - Voice undo discards `removeMark()`’s `CACHE_CLEANUP_FAILED`; `MarkUtteranceCommands.kt:43` treats completion as success and `Utterance.kt:125` replies “mark undone” while exported private text remains - Carry committed cleanup notices through `CommandOutcome` into the reply.
2. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ForgetActions.kt:208 - A JPEG/sidecar deletion fails; `FrameSweep.delete()` logs and returns a count that `apply()` ignores, allowing `DONE` and “Forgotten” while the photo remains (pre-existing) - Include failed photo-file cleanup in the returned outcome and user notice.
3. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/marks/MarkRecorder.kt:180 - Photo Forget commits, removes the linked label and returns cache-left; an export/upload then blocks this second gate acquisition, losing the notice; retry finds no frame at :169 and returns `DONE` through `markOutcome():101` while the cache remains - Preserve cleanup obligations across retries or recheck outstanding cleanup before reporting success.
4. [P3] android-companion/phone/src/main/java/com/rokid/context/phone/voice/MarkUtteranceCommands.kt:45 - Voice undo of a photo-less mark times out behind an upload; `UploadInProgress` still matches `WriteInProgress`, producing “a save is running” - Catch upload separately and provide accurate voice copy.

| Earlier finding | Status | Trace |
|---|---|---|
| R1 #1: prune deletes newly started run | CLOSED | `CanvasRunner.kt:202–203` protects registered/active/A-B IDs; `CanvasDao.kt:230` orders by insertion rowid rather than wall clock. |
| R1 #2: phone discards cache-left | CLOSED | `MarkGraph.kt:28` → `MarkRecorder.kt:174–176,190` → `ForgetActions.kt:101` → `ProductionForget.kt:42` → `MemoryDetailViewModel.kt:160–166` preserves the notice. Retry/voice gaps remain above. |
| R1 #3: phone calls upload a save | CLOSED | `markOutcome():102` catches upload first; `ProductionForget.kt:40` and `MemoryDetailViewModel.kt:144` preserve it through the displayed message. Voice remains #4. |

Additional checks:

- SQLite rowid reuse after deleting the maximum does **not** break ordering among surviving rows: the replacement receives current maximum + 1. Confirmed with an in-memory SQLite probe; “globally monotonic” in the DAO comment is inaccurate.
- `WITHOUT ROWID` rejects this query, but the actual entity/migration creates an ordinary rowid table (`CanvasEntities.kt:30`, `ContextDatabase.kt:86`); no current-schema regression.
- Concurrent starts use `tryLock()`; prune selects transactionally. Later inserts remain newest, and registered/active/A-B protection covers delayed pruning. No runner-lock/DataOpGate deadlock found: prune never acquires the runner lock.
- `undoMark` itself returns `MarkPhotoOutcome?`, and production passes `::undoMark` directly. The `Any?` callback remains unsafe: Unit, false, or an uncommitted wrapper become `DONE` (`ForgetActions.kt:101`). No current production coercion found; use an explicit sealed result.
- Both production `when` dispatchers over this Outcome handle `UPLOAD_IN_PROGRESS`: `ProductionForget.kt:40`, `SelectionController.kt:139`; the latter uses accurate generic busy copy.

Read-only static review plus in-memory SQLite checks. No files edited; no Gradle, adb, or tools scripts run.