**NOT READY**

1. **P1 — A session-close race can restore an answer indefinitely.** [HudController.kt:233](/Users/<user>/Desktop/rokid/pca-v098/android-companion/glasses/src/main/java/com/rokid/context/glasses/link/HudController.kt:233) publishes `_frame` after `cleared()` releases `answerLock`. A concurrent close clears the answer and sets `answerKey = null`; the pending publication then restores it. The five-minute expiry skips it because its key no longer matches.

   **Repro:** Display answer A. Pause an incoming repeat of A after `cleared()` returns, before assignment. Close the session, then resume the callback. Forget A’s photo while disconnected. The forgotten answer remains in the controller beyond five minutes and can redisplay when the activity resumes before receiving another frame. Frame publication and session closure need one atomic, session-fenced operation.

2. **P2 — An in-flight broadcast can erase withdrawal IDs immediately.** [Hub.kt:579](/Users/<user>/Desktop/rokid/pca-v098/android-companion/phone/src/main/java/com/rokid/context/phone/hub/Hub.kt:579) passes the timestamp captured before its suspending IO reads into `recentlyWithdrawn()`. If withdrawal occurs during those reads, `prune()` sees `now < withdrawalTime` and permanently removes the new IDs.

   **Repro:** Suspend `sources.today()` during broadcast, advance the clock, call `withdrawAsks(A)`, then release the read. That broadcast deletes A’s hint; subsequent frames omit it throughout the promised 60-second window. Use current time when pruning.

Static inspection confirmed protocol **1**, Room **16**, trace schema **1**, safe missing-field defaults, unknown-key handling, and content-free new milestone messages. The background hook posts to Main without waiting on the worker.

Both findings were checked with source-level execution models. Gradle and Android tests were **not run**, as requested.