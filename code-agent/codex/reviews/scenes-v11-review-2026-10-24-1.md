# Scenes (Room v11) review

Reviewer: Codex gpt-6.1-sol (read-only, medium). Scope: diff v0.9.2..v0.9.3 Scene tables, cascade, SceneBuilder, input.scenes executor.
Outcome: NOT READY (3 Important); fixed in f0437a3f, not re-reviewed

- **Important — Scene invalidation misses surviving Moment changes.** [SceneInputExecutor.kt:37](/Users/debasishkanhar/Desktop/rokid/pca-v093/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/SceneInputExecutor.kt:37) trusts `staleSince` alone. Undoing a mark invalidates its Moment, but leaves the containing Scene’s summary fresh—even after MomentBuilder updates that Moment. Until SceneBuilder runs, `input.scenes` returns obsolete text; an in-flight Scene derive also retains a valid input revision.

- **Important — Scene recreation reuses the derive fence revision.** [SceneBuilder.kt:97](/Users/debasishkanhar/Desktop/rokid/pca-v093/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/SceneBuilder.kt:97) restarts audit sequence at 1. Start a derive for Scene `SA={A,B}`; an earlier Moment X causes replacement by `SX`; MomentBuilder subsequently removes X and B, and rebuilding recreates `SA={A}`. The original derive’s generation and input sequence still match, so its obsolete A+B summary is accepted as fresh.

- **Important — Executor reads can straddle Forget.** [SceneInputExecutor.kt:33](/Users/debasishkanhar/Desktop/rokid/pca-v093/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/SceneInputExecutor.kt:33) reads Scenes, membership, and individual derived fields without a transaction or revision recheck. If Forget commits after SUMMARY is read but before CAPTION, the returned result still contains the now-hidden summary and forgotten membership references.

VERDICT NOT READY