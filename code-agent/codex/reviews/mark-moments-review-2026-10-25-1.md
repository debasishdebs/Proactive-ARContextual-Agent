# Mark-only Moments review

Reviewer: Codex gpt-6.1-sol (read-only, medium). Scope: diff 37bf2d84..HEAD (MomentType, Room v13, mark lineage, Activity Marks).
Outcome: NOT READY (3 Important: mark lineage fences, deletion paths, Scene lineage); fixed in 90ad61ec, not re-reviewed

- **Important — Forget can be followed by resurrected run output.** [CanvasRunner.kt:536](android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt:536) adds label lineage but leaves the pre-call and commit fences checking only frame tombstones and wipe generation. Start a model call over a MARK Moment, then Forget it while the call waits. Its frame set is empty, so the returning result passes `flush()` and recreates deleted run rows.

- **Important — Other mark-deletion paths leave run rows behind.** Cleanup is added only in [MomentForget.kt:95](android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/MomentForget.kt:95). Run a MARK Moment pipeline, then undo its mark: [MarkRecorder.kt:72](android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/marks/MarkRecorder.kt:72) deletes labels and schedules rebuilding, but neither action deletes `mf:<labelId>` run rows. They survive even after the Moment disappears. Range Forget and forgetting a nearby photo likewise delete labels without this new cleanup.

- **Important — Scene runs omit mark lineage.** [CanvasRunner.kt:533](android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/CanvasRunner.kt:533) adds mark tokens only for `momentByKey`; Scene lineage still contains only member frames. Run a Scene containing a MARK Moment, then Forget that mark. Scene membership shrinks, but persisted Scene run outputs survive because they carry no matching label token.

VERDICT NOT READY